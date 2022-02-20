package net.chatalina.plugins

import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*

fun Application.configureRouting() {
    routing {
        route("/api") {
            authenticate("obei-bec-user") {
                get("/heehoo") {
                    call.respond(":)")
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
}

class AuthenticationException : RuntimeException()
class AuthorizationException : RuntimeException()
