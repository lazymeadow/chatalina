package net.chatalina.plugins

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException
import com.fasterxml.jackson.module.kotlin.MissingKotlinParameterException
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.application.*
import io.ktor.auth.jwt.*
import io.ktor.features.*
import io.ktor.http.cio.websocket.*
import io.ktor.routing.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import java.lang.IllegalStateException
import java.security.InvalidAlgorithmParameterException
import java.time.Duration
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.IllegalBlockSizeException

data class RequestBody(
    val id: String?,
    val type: MessageTypes,
    @JsonProperty("content") val contentMap: Map<String, String>
) {
    // this will validate the content when we try to access it
    val messageContent: RequestContent
        get() = when (type) {
            MessageTypes.AUTHORIZATION -> contentMap["token"]?.let { AuthContent(it) }
                ?: throw BadRequestException("token missing from authorization")
            MessageTypes.KEY_EXCHANGE -> contentMap["key"]?.let { KeyExchangeContent(it) } ?: throw BadRequestException(
                "key missing from key exchange"
            )
            MessageTypes.SEND_MESSAGE -> {
                val iv = contentMap["iv"] ?: throw BadRequestException("iv missing from encrypted message")
                val messageContent =
                    contentMap["content"] ?: throw BadRequestException("content missing from encrypted message")
                if (messageContent.isBlank()) throw BadRequestException("content missing from encrypted message")
                MessageContent(iv, messageContent)
            }
            MessageTypes.UNKNOWN -> throw BadRequestException("unknown message type")
        }
}

data class MessageContent(
    override val iv: String,
    override val content: String
) : RequestContent(iv = iv, content = content)

data class AuthContent(
    override val token: String
) : RequestContent(token = token)

data class KeyExchangeContent(
    override val key: String
) : RequestContent(key = key)

abstract class RequestContent(
    open val iv: String? = null,
    open val content: String? = null,
    open val token: String? = null,
    open val key: String? = null
)

enum class MessageTypes(val value: String) {
    AUTHORIZATION("authorization"),
    KEY_EXCHANGE("keyExchange"),
    SEND_MESSAGE("sendMessage"),

    @JsonEnumDefaultValue
    UNKNOWN("unknown");

    override fun toString(): String {
        return value
    }
}


class Connection(val session: DefaultWebSocketSession) {
    companion object {
        var lastId = AtomicInteger(0)
    }

    // we need to know if the auth is valid
    var publicKey: String? = null

    // we'll need a public key to do encryption, but there's no headers with browser based WebSockets -_-
    var principal: JWTPrincipal? = null

    val name = "user${lastId.getAndIncrement()}"
}


fun Application.configureSockets() {
    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(15)
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    routing {
        val connections = Collections.synchronizedSet<Connection?>(LinkedHashSet())
        webSocket("/chat") {
            val thisConnection = Connection(this)
            connections += thisConnection
            try {
                for (frame in incoming) {
                    val mapper = jacksonMapper
                    val encryption = application.feature(Encryption)
                    when (frame) {
                        is Frame.Text -> {
                            try {
                                val received = mapper.readValue<RequestBody>(frame.data)
                                when (received.type) {
                                    MessageTypes.AUTHORIZATION -> {
                                        application.log.debug("received auth")
                                        // authorization must be first message received. if any message is received
                                        // auth is verified, socket WILL close.

                                        thisConnection.principal = received.messageContent.token?.let {
                                            application.environment.becAuth?.validateJwt(
                                                it
                                            )
                                        }
                                        if (thisConnection.principal == null) {
                                            outgoing.close(AuthenticationException())
                                        } else {
                                            // if we have a valid principal, they are authenticated. we'll check roles
                                            // on every subsequent message. for now, initiate key exchange.
                                            send(
                                                mapper.writeValueAsString(
                                                    mapOf(
                                                        "type" to MessageTypes.KEY_EXCHANGE,
                                                        "content" to mapOf("key" to encryption.publicKey)
                                                    )
                                                )
                                            )
                                        }
                                    }
                                    MessageTypes.KEY_EXCHANGE -> {
                                        application.log.debug("received key exchange")
                                        if (thisConnection.principal == null) {
                                            outgoing.close(AuthenticationException())
                                        }
                                        thisConnection.publicKey = received.messageContent.key
                                    }
                                    MessageTypes.SEND_MESSAGE -> {
                                        application.log.debug("received message")
                                        if (thisConnection.principal == null) {
                                            outgoing.close(AuthenticationException())
                                        }
                                        if (thisConnection.publicKey == null) {
                                            throw BadRequestException("must perform key exchange")
                                        }

                                        val messageId = UUID.randomUUID()
                                        // first, we need to decrypt the message
                                        try {
                                            val (iv, content) = received.messageContent as MessageContent
                                            application.log.debug("received iv: $iv content: $content")
                                            val decrypted = encryption.decrypt(content, iv, thisConnection.publicKey!!)

                                            connections.forEach {
                                                // then we need to re-encrypt it for sending to any relevant connections
                                                val (nonce, encrypted) = encryption.encrypt(
                                                    decrypted,
                                                    it.publicKey!!
                                                )
                                                val serialized = mapper.writeValueAsString(
                                                    mapOf(
                                                        "type" to "newMessage", "content" to
                                                                mapOf(
                                                                    "id" to messageId,
                                                                    "iv" to nonce,
                                                                    "content" to Base64.getEncoder()
                                                                        .encodeToString(encrypted)
                                                                )
                                                    )
                                                )
                                                it.session.send(serialized)
                                            }
                                        } catch (e: InvalidAlgorithmParameterException) {
                                            e.printStackTrace()
                                            throw BadRequestException("bad iv")
                                        } catch (e: IllegalBlockSizeException) {
                                            e.printStackTrace()
                                            throw BadRequestException("bad content")
                                        }
                                    }
                                    else -> {
                                        throw BadRequestException("Unknown message type")
                                    }
                                }
                            } catch (e: MissingKotlinParameterException) {
                                outgoing.send(Frame.Text("{\"error\": \"missing field: ${e.parameter.name}, ${e.parameter.type}\"}"))
                            } catch (e: UnrecognizedPropertyException) {
                                outgoing.send(Frame.Text("{\"error\": \"unknown field: ${e.propertyName} (allowed: ${e.knownPropertyIds})\"}"))
                            } catch (e: JsonParseException) {
                                e.printStackTrace()
                                outgoing.send(Frame.Text("{\"error\": \"invalid json\"}"))
                            } catch (e: MismatchedInputException) {
                                e.printStackTrace()
                                outgoing.send(Frame.Text("{\"error\": \"invalid request\"}"))
                            } catch (e: BadRequestException) {
                                outgoing.send(Frame.Text("{\"error\": \"${e.message}\"}"))
                            } catch (e: AuthenticationException) {
                                outgoing.send(Frame.Text("{\"error\": \"${e.message}\"}"))
                            }
                        }
                        else -> {
                            outgoing.send(Frame.Text("huh???"))
                        }
                    }
                }
            } catch (e: ClosedReceiveChannelException) {
                application.log.info("onClose ${closeReason.await()}")
            } finally {
                connections.remove(thisConnection)
            }
        }
    }
//        }
//    }
}
