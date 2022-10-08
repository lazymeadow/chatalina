package net.chatalina.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import net.chatalina.jsonrpc.JsonRpcCallBody
import net.chatalina.jsonrpc.RequestSource
import net.chatalina.jsonrpc.processJsonRpcRequest
import java.io.File
import java.security.PublicKey


fun Application.configureRouting() {
    routing {
        route("/api/v1") {
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
                    val (statusCode, response, _) = processJsonRpcRequest(
                        ::getBody,
                        ::getPrincipal,
                        ::getClientKey,
                        application.chatHandler,
                        RequestSource.HTTP
                    )
                    call.response.status(statusCode)
                    response?.let { call.respond(response) } ?: finish()
                }

                handle {
                    call.response.headers.append(HttpHeaders.Allow, HttpMethod.Post.value)
                    call.respond(HttpStatusCode.MethodNotAllowed)
                }
            }

            // endpoint for auth server to get public key for jwks
            route("bec/k_jwks") {
                get {
                    call.respondFile(File("src/main/resources/keystore.jks"))
                }
                // respond to everything except POST with 403 (same thing CORS plugin returns)
                handle {
                    call.respond(HttpStatusCode.Forbidden)
                }
            }
        }
    }

    install(StatusPages) {
        exception<AuthenticationException> { call, _ ->
            call.respond(HttpStatusCode.Unauthorized)
        }
        exception<AuthorizationException> { call, _ ->
            call.respond(HttpStatusCode.Forbidden)
        }
    }
}

class AuthenticationException : RuntimeException()
class AuthorizationException : RuntimeException()
