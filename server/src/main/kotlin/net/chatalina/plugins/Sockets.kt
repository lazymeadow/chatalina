package net.chatalina.plugins

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException
import com.fasterxml.jackson.module.kotlin.MissingKotlinParameterException
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.cio.websocket.*
import io.ktor.routing.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import net.chatalina.chat.ChatSocketConnection
import net.chatalina.chat.MessageContent
import net.chatalina.chat.MessageTypes
import net.chatalina.chat.RequestBody
import java.security.InvalidAlgorithmParameterException
import java.time.Duration
import javax.crypto.IllegalBlockSizeException


/**
 * The socket will be used for immediate updates to clients that have socket capabilities. Only a subset of actions will
 * be available via socket messages. The socket will mainly be used to receive updates.
 *
 */
fun Application.configureSockets() {
    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(15)
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    routing {
        webSocket("/chat") {
            val chatHandler = feature(ChatHandlerPlugin)
            // set up a new connection instance for this session
            val thisChatSocketConnection = ChatSocketConnection(this)
            // add this connection to the handler
            chatHandler.currentSocketConnections.add(thisChatSocketConnection)
            try {
                for (frame in incoming) {
                    val mapper = jacksonMapper
                    when (frame) {
                        is Frame.Text -> {
                            try {
                                val received = mapper.readValue<RequestBody>(frame.data)
                                if (received.type != MessageTypes.AUTHORIZATION && thisChatSocketConnection.principal == null) {
                                    throw NoAuthException()
                                }
                                when (received.type) {
                                    MessageTypes.AUTHORIZATION -> {
                                        log.debug("received auth")
                                        // authorization must be first message received. if any message is received
                                        // auth is verified, socket WILL close.

                                        thisChatSocketConnection.principal = received.messageContent.token?.let {
                                            try {
                                                environment.becAuth?.validateJwt(
                                                    it
                                                )
                                            } catch (e: Exception) {
                                                e.printStackTrace()
                                                throw BadAuthException()
                                            }
                                        } ?: throw BadAuthException()

                                        val encryption = application.feature(Encryption)
                                        // if we have a valid principal, they are authenticated. we'll check roles
                                        // on every subsequent message. for now, initiate key exchange.
                                        chatHandler.sendToConnection(
                                            thisChatSocketConnection, mapOf(
                                                "type" to MessageTypes.KEY_EXCHANGE,
                                                "content" to mapOf("key" to encryption.publicKey)
                                            )
                                        )
                                    }
                                    MessageTypes.KEY_EXCHANGE -> {
                                        log.debug("received key exchange")
                                        thisChatSocketConnection.publicKey = received.messageContent.key
                                    }
                                    MessageTypes.SEND_MESSAGE -> {
                                        // TODO: remove this message from socket processing - these will be POST requests instead
                                        if (thisChatSocketConnection.publicKey == null) {
                                            throw BadRequestException("must perform key exchange")
                                        }

                                        // first, we need to decrypt the message
                                        try {
                                            chatHandler.processNewMessage(
                                                received.messageContent as MessageContent,
                                                thisChatSocketConnection
                                            )
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
                            } catch (e: NoAuthException) {
                                outgoing.send(Frame.Text("{\"error\": \"${e.message}\"}"))
                            } catch (e: BadAuthException) {
                                outgoing.send(Frame.Text("{\"error\": \"${e.message}\"}"))
                                outgoing.close(e)
                            }
                        }
                        else -> {
                            outgoing.send(Frame.Text("huh???"))
                        }
                    }
                }
            } catch (e: ClosedReceiveChannelException) {
                log.info("onClose ${closeReason.await()}")
            } finally {
                chatHandler.currentSocketConnections.remove(thisChatSocketConnection)
            }
        }
    }
}

class NoAuthException : Exception("send message with type ${MessageTypes.AUTHORIZATION} and valid token")
class BadAuthException : Exception("Invalid auth")
