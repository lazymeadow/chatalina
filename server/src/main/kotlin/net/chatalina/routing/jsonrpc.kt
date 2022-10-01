package net.chatalina.routing

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import net.chatalina.jsonrpc.JsonRpcCallBody
import net.chatalina.jsonrpc.RequestSource
import net.chatalina.jsonrpc.processJsonRpcRequest
import net.chatalina.plugins.becAuth
import net.chatalina.plugins.chatHandler
import net.chatalina.plugins.clientKey
import net.chatalina.plugins.encryption
import java.security.PublicKey


fun Route.jsonRpc() {
    route("/rpc") {
        post {
            suspend fun getBody(): JsonRpcCallBody {
                return call.receive()
            }
            fun getPrincipal(): JWTPrincipal? {
                // get the token out of the bearer header
                return try {
                    val authToken = call.request.authorization()?.substringAfter("Bearer ")
                    authToken?.let {
                        try {
                            application.environment.becAuth?.validateJwt(it)
                        } catch (e: Exception) {
                            e.printStackTrace()
                            null
                        }
                    }
                } catch (e: IllegalArgumentException) {
                    null
                }
            }

            fun getClientKey(): PublicKey {
                return application.encryption.validateAndGetPublicKey(call.request.clientKey)
            }
            val (statusCode, response, _) = processJsonRpcRequest(::getBody, ::getPrincipal, ::getClientKey, application.chatHandler, RequestSource.HTTP)
            call.response.status(statusCode)
            response?.let { call.respond(response) } ?: finish()
        }

        handle {
            call.response.headers.append(HttpHeaders.Allow, HttpMethod.Post.value)
            call.respond(HttpStatusCode.MethodNotAllowed)
        }
    }
}
