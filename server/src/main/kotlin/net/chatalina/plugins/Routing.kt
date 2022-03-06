package net.chatalina.plugins

import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*
import java.io.File

fun Application.configureRouting() {
    routing {
        route("/api/v1") {
            authenticate("obei-bec-parasite") {
                get("/heehoo") {
                    call.respond(":)")
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
        }
    }
}

class AuthenticationException : RuntimeException()
class AuthorizationException : RuntimeException()
