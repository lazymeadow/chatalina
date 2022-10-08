package net.chatalina.plugins

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException
import com.fasterxml.jackson.module.kotlin.MissingKotlinParameterException
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.server.application.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import net.chatalina.chat.ChatSocketConnection
import net.chatalina.chat.ServerMethodTypes
import net.chatalina.database.Parasite
import net.chatalina.jsonrpc.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.PublicKey
import java.time.Duration
import java.util.*


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
            val chatHandler = application.chatHandler
            // set up a new connection instance for this session
            val theConnection = ChatSocketConnection(this)
            // add this connection to the handler
            chatHandler.currentSocketConnections.add(theConnection)
            try {
                for (frame in incoming) {
                    val mapper = application.jacksonMapper
                    when (frame) {
                        is Frame.Text -> {
                            try {
                                theConnection.log.debug("received message")
                                val body = mapper.readValue<JsonRpcCallBody>(frame.data)
                                fun getBody(): JsonRpcCallBody {
                                    return body
                                }

                                fun getPrincipal(): JWTPrincipal? {
                                    return theConnection.principal
                                }

                                fun getClientKey(): PublicKey? {
                                    return theConnection.publicKey
                                }

                                // we send the socket principal for processing because the method will handle checking
                                // validity (if authorization call) and necessary permissions (for all others)
                                val (_, response, passAlongResult) = processJsonRpcRequest(
                                    ::getBody,
                                    ::getPrincipal,
                                    ::getClientKey,
                                    chatHandler,
                                    RequestSource.SOCKET
                                )
                                if (body.method == MethodHandler.AUTHORIZATION.toString()) {
                                    val principal = passAlongResult as JWTPrincipal?
                                    if (principal == null) {
                                        throw NoAuthException()
                                    } else {
                                        theConnection.principal = principal
                                        theConnection.log.debug("set auth for ${theConnection}")
                                        // we know this is a valid parasite, based on their token.
                                        val userId = try {
                                            UUID.fromString(principal.subject)
                                        } catch (e: IllegalArgumentException) {
                                            throw BadAuthException()
                                        }
                                        if (theConnection.isParasiteSet()) {
                                            if (theConnection.parasite.id.value != userId) {
                                                throw BadAuthException()
                                            }
                                        } else {
                                            val thisParasite = transaction {
                                                // find or add the parasite
                                                Parasite.findById(userId) ?: Parasite.new(userId) {
                                                    this.displayName = principal.get("preferred_username") ?: ""
                                                }
                                            }
                                            theConnection.parasite = thisParasite
                                            theConnection.log.debug("set parasite for ${theConnection}")
                                        }
                                        // don't initiate a key exchange if we have a perfectly good key already
                                        if (theConnection.publicKey == null) {
                                            theConnection.log.debug("initiating key exchange with ${theConnection}")
                                            chatHandler.sendToConnection(
                                                theConnection, mapOf(
                                                    "method" to ServerMethodTypes.ENCRYPTION_KEY,
                                                    "params" to mapOf("key" to application.encryption.publicKey)
                                                )
                                            )
                                        }
                                    }
                                } else if (body.method == MethodHandler.ENCRYPTION_KEY.toString() && passAlongResult != null) {
                                    theConnection.log.debug("setting key for ${theConnection}")
                                    theConnection.publicKey = application.encryption.validateAndGetPublicKey(
                                        passAlongResult.toString()
                                    )
                                } else if (response != null) {
                                    theConnection.log.debug("sending result to ${theConnection}")
                                    chatHandler.sendToConnection(theConnection, response)
                                }
                            } catch (e: MissingKotlinParameterException) {
                                val errorResponse = generateErrorResponse(
                                    null,
                                    JsonRpcStatus.INVALID_PARAMS,
                                    "missing field: ${e.parameter.name}, ${e.parameter.type}"
                                )
                                outgoing.send(Frame.Text(mapper.writeValueAsString(errorResponse)))
                            } catch (e: UnrecognizedPropertyException) {
                                val errorResponse = generateErrorResponse(
                                    null,
                                    JsonRpcStatus.INVALID_PARAMS,
                                    "unknown field: ${e.propertyName} (allowed: ${e.knownPropertyIds})"
                                )
                                outgoing.send(Frame.Text(mapper.writeValueAsString(errorResponse)))
                                outgoing.send(Frame.Text("{\"error\": \"\"}"))
                            } catch (e: JsonParseException) {
                                e.printStackTrace()
                                val errorResponse = generateErrorResponse(null, JsonRpcStatus.PARSE_ERROR, e.message)
                                outgoing.send(Frame.Text(mapper.writeValueAsString(errorResponse)))
                            } catch (e: MismatchedInputException) {
                                e.printStackTrace()
                                val errorResponse = generateErrorResponse(null, JsonRpcStatus.INVALID_REQUEST, e.message)
                                outgoing.send(Frame.Text(mapper.writeValueAsString(errorResponse)))
                            } catch (e: BadRequestException) {
                                val errorResponse = generateErrorResponse(null, JsonRpcStatus.INVALID_REQUEST, e.message)
                                outgoing.send(Frame.Text(mapper.writeValueAsString(errorResponse)))
                                outgoing.send(Frame.Text("{\"error\": \"${e.message}\"}"))
                            } catch (e: NoAuthException) {
                                val errorResponse = generateErrorResponse(null, JsonRpcStatus.UNAUTHORIZED, e.message)
                                outgoing.send(Frame.Text(mapper.writeValueAsString(errorResponse)))
                                outgoing.send(Frame.Text("{\"error\": \"${e.message}\"}"))
                            } catch (e: BadAuthException) {
                                val errorResponse = generateErrorResponse(null, JsonRpcStatus.FORBIDDEN, e.message)
                                outgoing.send(Frame.Text(mapper.writeValueAsString(errorResponse)))
                                outgoing.close(e)
                            }
                        }
                        else -> {
                            outgoing.send(Frame.Text("huh???"))
                        }
                    }
                }
            } catch (e: ClosedReceiveChannelException) {
                application.log.info("onClose ${theConnection.name}: ${closeReason.await()}")
            } finally {
                chatHandler.currentSocketConnections.remove(theConnection)
            }
        }
    }
}

class NoAuthException : Exception("send call for method '${MethodHandler.AUTHORIZATION}' and valid token")
class BadAuthException : Exception("Invalid auth")
