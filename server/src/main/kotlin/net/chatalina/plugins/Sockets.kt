package net.chatalina.plugins

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException
import com.fasterxml.jackson.module.kotlin.MissingKotlinParameterException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.http.cio.websocket.*
import io.ktor.routing.*
import io.ktor.websocket.*
import java.time.Duration
import java.util.*

data class RequestBody(
    val id: String,
    val type: String,
    val content: String
)


fun Application.configureSockets() {
    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(15)
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    routing {
        withEncryption {
            authenticate("obei-bec-parasite") {
                webSocket("/ws") {
                    for (frame in incoming) {
                        val mapper = jacksonObjectMapper()
                        val encryption = application.feature(Encryption)
                        when (frame) {
                            is Frame.Text -> {
                                try {
                                    val received = mapper.readValue<RequestBody>(frame.data)
                                    val (nonce, encrypted) = encryption.encrypt(received.content.toByteArray(), call.request.clientKey)
                                    outgoing.send(Frame.Text(mapper.writeValueAsString(mapOf("iv" to nonce, "encrypted" to Base64.getEncoder().encodeToString(encrypted)))))
                                    val decrypted = encryption.decrypt(encrypted, nonce, call.request.clientKey)
                                    outgoing.send(Frame.Text(mapper.writeValueAsString(mapOf("decrypted" to decrypted.decodeToString()))))
                                    outgoing.send(Frame.Text("id: ${received.id}"))
                                } catch (e: MissingKotlinParameterException) {
                                    outgoing.send(Frame.Text("{\"error\": \"missing field: ${e.parameter.name}, ${e.parameter.type}\""))
                                } catch (e: UnrecognizedPropertyException) {
                                    outgoing.send(Frame.Text("{\"error\": \"unknown field: ${e.propertyName} (allowed: ${e.knownPropertyIds})\""))
                                } catch (e: JsonParseException) {
                                    outgoing.send(Frame.Text("{\"error\": \"invalid json\""))
                                } catch (e: MismatchedInputException) {
                                    outgoing.send(Frame.Text("{\"error\": \"invalid request\""))
                                }
                            }
                            else -> {
                                outgoing.send(Frame.Text("huh???"))
                            }
                        }
                    }
                }
            }
        }
    }
}
