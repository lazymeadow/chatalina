package net.chatalina.plugins

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue
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
import java.time.Duration
import java.util.*

data class RequestBody(
    val id: String?,
    val type: MessageTypes,
    val content: String
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


fun Application.configureSockets() {
    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(15)
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    routing {
        webSocket("/chat") {
            // we need to know if the auth is valid
            var principal: JWTPrincipal? = null
            // we'll need a public key to do encryption, but there's no headers with browser based WebSockets -_-
            var publicKey: String? = null

            for (frame in incoming) {
                val mapper = jacksonMapper
                val encryption = application.feature(Encryption)
                when (frame) {
                    is Frame.Text -> {
                        try {
                            val received = mapper.readValue<RequestBody>(frame.data)
                            when (received.type) {
                                MessageTypes.AUTHORIZATION -> {
                                    // authorization must be first message received. if any message is received
                                    // auth is verified, socket WILL close.

                                    principal = application.environment.becAuth?.validateJwt(received.content)
                                    if (principal == null) {
                                        outgoing.close(AuthenticationException())
                                    } else {
                                        // if we have a valid principal, they are authenticated. we'll check roles
                                        // on every subsequent message. for now, initiate key exchange.
                                        outgoing.send(
                                            Frame.Text(
                                                mapper.writeValueAsString(
                                                    mapOf(
                                                        "type" to MessageTypes.KEY_EXCHANGE,
                                                        "content" to encryption.publicKey
                                                    )
                                                )
                                            )
                                        )
                                    }
                                }
                                MessageTypes.KEY_EXCHANGE -> {
                                    if (principal == null) {
                                        outgoing.close(AuthenticationException())
                                    }
                                    publicKey = received.content
                                }
                                MessageTypes.SEND_MESSAGE -> {
                                    if (principal == null) {
                                        outgoing.close(AuthenticationException())
                                    }
                                    if (publicKey == null) {
                                        throw BadRequestException("must perform key exchange")
                                    }
                                    val (nonce, encrypted) = encryption.encrypt(
                                        received.content.toByteArray(),
                                        publicKey
                                    )
                                    outgoing.send(
                                        Frame.Text(
                                            mapper.writeValueAsString(
                                                mapOf(
                                                    "iv" to nonce,
                                                    "encrypted" to Base64.getEncoder().encodeToString(encrypted)
                                                )
                                            )
                                        )
                                    )
                                    val decrypted = encryption.decrypt(encrypted, nonce, publicKey)
                                    outgoing.send(Frame.Text(mapper.writeValueAsString(mapOf("decrypted" to decrypted.decodeToString()))))
                                    outgoing.send(Frame.Text("id: ${received.id}"))
                                }
                                else -> {
                                    throw BadRequestException("Unknown message type")
                                }
                            }
                        } catch (e: MissingKotlinParameterException) {
                            outgoing.send(Frame.Text("{\"error\": \"missing field: ${e.parameter.name}, ${e.parameter.type}\""))
                        } catch (e: UnrecognizedPropertyException) {
                            outgoing.send(Frame.Text("{\"error\": \"unknown field: ${e.propertyName} (allowed: ${e.knownPropertyIds})\""))
                        } catch (e: JsonParseException) {
                            e.printStackTrace()
                            outgoing.send(Frame.Text("{\"error\": \"invalid json\""))
                        } catch (e: MismatchedInputException) {
                            e.printStackTrace()
                            outgoing.send(Frame.Text("{\"error\": \"invalid request\""))
                        } catch (e: BadRequestException) {
                            outgoing.send(Frame.Text("{\"error\": \"${e.message}\""))
                        }
                    }
                    else -> {
                        outgoing.send(Frame.Text("huh???"))
                    }
                }
            }
        }
    }
//        }
//    }
}
