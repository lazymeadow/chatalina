package net.chatalina.plugins

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import net.chatalina.chat.ChatManager
import net.chatalina.chat.ServerMessage
import net.chatalina.database.AlertData
import net.chatalina.http.AuthenticationException
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
                val parasiteSession = call.principal<ParasiteSession>() ?: let {
                    close(reason = CloseReason(3000, "401"))
                    throw AuthenticationException()
                }
                val theConnection = ChatSocketConnection(this, parasiteSession.id)

                try {
                    ChatManager.addConnection(theConnection)
                    theConnection.logger.debug("Client connected successfully.")
                    incoming.consumeEach { frame ->
                        when (frame) {
                            is Frame.Text -> {
                                try {
                                    ChatManager.handleMessage(theConnection, frame.readText())
                                } catch (e: BadRequestException) {
                                    theConnection.send(ServerMessage(AlertData.dismiss(e.message ?: "Bad message content", "Oops")))
                                } catch (e: NotImplementedError) {
                                    theConnection.send(ServerMessage(AlertData.fade(e.message ?: "Not implemented")))
                                }
                            }
                            else -> outgoing.send(Frame.Text("huh???"))
                        }
                    }
                } catch (e: ClosedReceiveChannelException) {
                    theConnection.logger.debug("{}: onClose {}", theConnection.name, closeReason.await())
                } catch (e: AuthenticationException) {
                    theConnection.send(ServerMessage.AuthFail())
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

const val CLIENT_VERSION = "4.0.2"


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
