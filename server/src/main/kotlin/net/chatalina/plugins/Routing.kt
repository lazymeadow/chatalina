package net.chatalina.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import net.chatalina.jsonrpc.Request
import java.io.File
import java.security.PublicKey
import java.util.*


fun Application.configureRouting() {
    routing {
        route("/api/v1") {
            route("/rpc") {
                post {
                    suspend fun getBody(): Request {
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

                    fun getClientKey(): PublicKey? {
                        return application.encryption.validateAndGetPublicKey(call.request.clientKey)
                    }

                    val jsonrpc = application.jsonRpc
                    val (statusCode, response, _) = jsonrpc.handleRequest(::getBody, ::getPrincipal, ::getClientKey)
                    if (response?.isEncryptedEndpoint == true) {
                        val publicKey = call.application.encryption.publicKey
                        call.response.header(BEC_SERVER_HEADER, Base64.getEncoder().encodeToString(publicKey))
                    }
                    call.response.status(statusCode)
                    response?.let { call.respond(response) } ?: finish()
                }

                // respond to everything except POST with 403 (same thing CORS plugin returns)
                handle {
                    call.respond(HttpStatusCode.Forbidden)
                }
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
