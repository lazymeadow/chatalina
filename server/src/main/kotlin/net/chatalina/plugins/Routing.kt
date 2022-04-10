package net.chatalina.plugins

import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.auth.jwt.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import net.chatalina.jsonrpc.JsonRpc
import net.chatalina.jsonrpc.Request
import java.io.File
import java.lang.IllegalArgumentException
import java.security.PublicKey
import java.util.*


fun Application.configureRouting() {
    routing {
        route("/api/v1") {
            route("/rpc") {
                // respond to everything except POST with 405
//                handle {
//                    call.respond(HttpStatusCode.MethodNotAllowed)
//                }

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
                                    environment.becAuth?.validateJwt(it)
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
                        return application.feature(Encryption).validateAndGetPublicKey(call.request.clientKey)
                    }

                    val jsonrpc = featureOrNull(JsonRpc)
                    if (jsonrpc == null) {
                        log.error("Missing JsonRpc feature, request cannot be completed")
                        call.respond(HttpStatusCode.InternalServerError)
                    } else {
                        val (statusCode, response, _) = jsonrpc.handleRequest(::getBody, ::getPrincipal, ::getClientKey)
                        if (response?.isEncryptedEndpoint == true) {
                            val publicKey = call.application.feature(Encryption).publicKey
                            call.response.header(BEC_SERVER_HEADER, Base64.getEncoder().encodeToString(publicKey))
                        }
                        call.response.status(statusCode)
                        response?.let {call.respond(response)} ?: finish()

                    }
                }
            }

            withEncryption {
                authenticate("obei-bec-parasite") {
                    get("/heehoo") {
                        call.respond(":)")
                    }

                    post("/test/server-key") {
                        val publicKey = application.feature(Encryption).publicKey
                        call.respond(mapOf("publicKey" to publicKey))
                    }
                }
            }
        }

        // endpoint for auth server to get public key for jwks
        route("bec/k_jwks") {
            get {
                call.respondFile(File("src/main/resources/keystore.jks"))
            }
        }

        install(StatusPages) {
            exception<AuthenticationException> {
                call.respond(HttpStatusCode.Unauthorized)
            }
            exception<AuthorizationException> {
                call.respond(HttpStatusCode.Forbidden)
            }
        }
    }
}

class AuthenticationException : RuntimeException()
class AuthorizationException : RuntimeException()
