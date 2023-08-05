package com.applepeacock.plugins

import com.applepeacock.chat.ChatManager
import com.applepeacock.http.AuthenticationException
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

fun Application.configureSockets() {
    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(15)
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }
    routing {
        authenticate("auth-parasite-socket") {
            webSocket("/chat") {
                val parasiteSession = call.principal<ParasiteSession>() ?: throw AuthenticationException()
                val theConnection = ChatSocketConnection(this, parasiteSession.id)
                ChatManager.addConnection(theConnection)
                theConnection.logger.debug("Client connected successfully.")

                try {
                    incoming.consumeEach {frame ->
                        when (frame) {
                            is Frame.Text -> {
                                ChatManager.handleMessage(theConnection, frame.readText())
                            }
                            else -> outgoing.send(Frame.Text("huh???"))
                        }
                    }
                } catch (e: ClosedReceiveChannelException) {
                    theConnection.logger.debug("${theConnection.name}: onClose ${closeReason.await()}")
                } catch (e: Throwable) {
                    e.printStackTrace()
                    application.log.error("what the fuck")
                } finally {
                    ChatManager.removeConnection(theConnection)
                }
            }
        }
    }
}

const val CLIENT_VERSION = "4.0.0"


class ChatSocketConnection(val session: DefaultWebSocketServerSession, val parasiteId: String) {
    companion object {
        var lastId = AtomicInteger(0)
    }

    val name = "WS-${lastId.getAndIncrement()}-${parasiteId}"
    val logger = LoggerFactory.getLogger(name)

    fun launchForSocket(block: suspend () -> Unit) {
        logger.debug("launching job on socket")
        this.session.launch(CoroutineName(name)) {
            block()
        }
    }

    fun send(data: Any) {
        try {
            logger.debug("Sending message to connection")
            launchForSocket {
                session.send(defaultMapper.writeValueAsString(mapOf("data" to data)))
            }
        } catch (e: ClosedSendChannelException) {
            logger.debug("Attempted to send message to closed session $name")
        }
    }

    override fun toString(): String {
        return this.name
    }
}
