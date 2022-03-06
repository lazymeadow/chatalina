package net.chatalina.plugins

import com.fasterxml.jackson.module.kotlin.MissingKotlinParameterException
import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import java.io.File

data class KeyExchangeRequest(
    val token: String
)


fun Application.configureRouting() {
    routing {
        route("/api/v1") {
            authenticate("obei-bec-parasite") {
                get("/heehoo") {
                    call.respond(":)")
                }

                post("/test/server-key") {
                    val requestData = try {
                        call.receive<KeyExchangeRequest>()
                    } catch (e: MissingKotlinParameterException) {
                        throw BadRequestException("Missing field: ${e.parameter.name}")
                    }
                    val publicKey = application.feature(Encryption).publicKey
                    val derivedKey = application.feature(Encryption).getDerivedKey(requestData.token)
                    call.respond(mapOf("publicKey" to publicKey, "derivedKey" to derivedKey))
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
            exception<AuthenticationException> { cause ->
                call.respond(HttpStatusCode.Unauthorized)
            }
            exception<AuthorizationException> { cause ->
                call.respond(HttpStatusCode.Forbidden)
            }
            exception<BadRequestException> { cause ->
                call.respond(HttpStatusCode.BadRequest, cause.localizedMessage)
            }
        }
    }
}

class AuthenticationException : RuntimeException()
class AuthorizationException : RuntimeException()
