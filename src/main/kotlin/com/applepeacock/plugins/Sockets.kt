package com.applepeacock.plugins

import com.applepeacock.database.Parasites
import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonEnumDefaultValue
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

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
                val theConnection = ChatSocketConnection(this)// websocketSession
                ChatManager.currentSocketConnections.add(theConnection)

                try {
                    for (frame in incoming) {
                        when (frame) {
                            is Frame.Text -> {
                                ChatManager.handleMessage(frame.readText())
                            }
                            else -> outgoing.send(Frame.Text("huh???"))
                        }
                    }
                } catch (e: ClosedReceiveChannelException) {
                    println("${theConnection.name}: onClose ${closeReason.await()}")
                } catch (e: Throwable) {
                    e.printStackTrace()
                    application.log.error("what the fuck")
                } finally {
                    ChatManager.currentSocketConnections.remove(theConnection)
                }
            }
        }
    }
}

const val CLIENT_VERSION = "4.0.0"

object ChatManager {
    val currentSocketConnections: MutableSet<ChatSocketConnection> = Collections.synchronizedSet(LinkedHashSet())

    fun handleMessage(body: String) {
        val messageBody = defaultMapper.readValue<MessageBody>(body)
        messageBody.type.handler.handleMessage(messageBody)
    }
}

open class MessageBody(
    val type: MessageTypes,
    @JsonAnySetter
    @JsonAnyGetter
    val other: MutableMap<String, Any?> = mutableMapOf()
) {
    private fun <T, TValue> map(properties: MutableMap<String, TValue>, key: String): ReadOnlyProperty<T, TValue> {
        return object : ReadOnlyProperty<T, TValue> {
            override fun getValue(thisRef: T, property: KProperty<*>) = properties[key]!!
        }
    }

    fun <T> fromOther(key: String): ReadOnlyProperty<T, Any?> = map(other, key)
}


interface MessageHandler {

    fun handleMessage(body: MessageBody)
}

inline fun <reified T : MessageBody> MessageHandler.onMessage(
    body: MessageBody,
    block: (T) -> Unit
) {
    block(defaultMapper.convertValue(body))
}

object UnknownMessageHandler : MessageHandler {
    override fun handleMessage(body: MessageBody) {
        onMessage<MessageBody>(body) {
            println("unknown message")
        }
    }
}

object VersionMessageHandler : MessageHandler {
    class VersionMessageBody(
        type: MessageTypes
    ) : MessageBody(type) {
        val clientVersion by fromOther("client version")
    }

    override fun handleMessage(body: MessageBody) {
        onMessage<VersionMessageBody>(body) { messageBody ->
            if (messageBody.clientVersion.toString() < CLIENT_VERSION) {
                // TODO: how to send these back through the socket
                println("Your client is out of date. You\\'d better refresh your page!")
            } else if (messageBody.clientVersion.toString() > CLIENT_VERSION) {
                println("How did you mess up a perfectly good client version number?")
            }
        }
    }
}

enum class MessageTypes(val value: String) {
    //    ClientLog("client log"),
//    ChatMessage("chat message"),
//    PrivateMessage("private message"),
//    Image("image"),
//    ImageUpload("image upload"),
//    RoomAction("room action"),
//    Status("status"),
//    Typing("typing"),
    Version("version") {
        override val handler = VersionMessageHandler
    },

    //    Settings("settings"),
//    RemoveAlert("remove alert"),
//    Bug("bug"),
//    Feature("feature"),
//    ToolList("tool list"),
//    DataRequest("data request"),
//    AdminRequest("admin request"),
    @JsonEnumDefaultValue Unknown("unknown") {
        override val handler = UnknownMessageHandler
    };

    abstract val handler: MessageHandler

    override fun toString(): String {
        return this.value
    }
}

class ChatSocketConnection(val session: DefaultWebSocketServerSession) {
    companion object {
        var lastId = AtomicInteger(0)
    }

    val name = "WS-${lastId.getAndIncrement()}"
    val logger = LoggerFactory.getLogger(name)
    lateinit var parasite: Parasites.ParasiteObject

    fun isParasiteSet(): Boolean {
        return this::parasite.isInitialized
    }

    fun launchForSocket(block: suspend () -> Unit) {
        logger.debug("launching job on socket")
        this.session.launch(CoroutineName(name)) {
            block()
        }
    }

    suspend fun send(data: String) {
        try {
            this.session.send(data)
        } catch (e: ClosedSendChannelException) {
            logger.debug("Attempted to send message to closed session $name")
        }
    }

    override fun toString(): String {
        return this.name
    }
}
