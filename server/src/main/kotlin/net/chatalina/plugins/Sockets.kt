package net.chatalina.plugins

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
import net.chatalina.chat.ChatSocketConnection
import net.chatalina.chat.MessageTypes
import net.chatalina.jsonrpc.JsonRpc
import net.chatalina.jsonrpc.Request
import net.chatalina.jsonrpc.endpoints.Authorization
import net.chatalina.jsonrpc.endpoints.KeyExchange
import java.security.PublicKey
import java.time.Duration


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
            val chatHandler = feature(ChatHandler)
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
                                val body = mapper.readValue<Request>(frame.data)
                                fun getBody(): Request {
                                    return body
                                }

                                fun getPrincipal(): JWTPrincipal? {
                                    return thisChatSocketConnection.principal
                                }

                                fun getClientKey(): PublicKey? {
                                    return thisChatSocketConnection.publicKey
                                }

                                val jsonrpc = featureOrNull(JsonRpc)
                                if (jsonrpc == null) {
                                    log.error("Missing JsonRpc feature, request cannot be completed")
                                    outgoing.close()
                                } else {
                                    val (_, _, passAlongResult) = jsonrpc.handleRequest(
                                        ::getBody,
                                        ::getPrincipal,
                                        ::getClientKey,
                                        executingInSocket = true
                                    )
                                    if (body.method == Authorization.methodName) {
                                        // okay this is not great. but when we get a principal result, set it,
                                        //   then initiate key exchange via socket.
                                        thisChatSocketConnection.principal =
                                            passAlongResult as JWTPrincipal? ?: throw BadAuthException()
                                        val encryption = application.feature(Encryption)
                                        chatHandler.sendToConnection(
                                            thisChatSocketConnection, mapOf(
                                                "type" to MessageTypes.KEY_EXCHANGE,
                                                "content" to mapOf("key" to encryption.publicKey)
                                            )
                                        )
                                    } else if (body.method == KeyExchange.methodName && passAlongResult != null) {
                                        val encryption = application.feature(Encryption)
                                        thisChatSocketConnection.publicKey =
                                            encryption.validateAndGetPublicKey(passAlongResult.toString())
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
