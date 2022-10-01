package net.chatalina.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import net.chatalina.routing.jsonRpc
import java.io.File


fun Application.configureRouting() {
    routing {
        route("/api/v1") {
            jsonRpc()

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
