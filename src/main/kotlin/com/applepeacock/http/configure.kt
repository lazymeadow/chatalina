package com.applepeacock.http

import com.applepeacock.hostname
import com.applepeacock.http.routes.authenticationRoutes
import com.applepeacock.http.routes.mainRoutes
import com.applepeacock.isProduction
import com.applepeacock.plugins.CLIENT_VERSION
import com.applepeacock.siteName
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.http.content.*
import io.ktor.server.pebble.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.pebbletemplates.pebble.loader.ClasspathLoader

fun Application.getPebbleContent(name: String, vararg vars: Pair<String, Any>) =
    PebbleContent(name, mapOf("prod" to this.isProduction, "siteTitle" to "${this.siteName} $CLIENT_VERSION") + vars)

fun Application.configureHTTP() {
    val enableCors = this.isProduction
    val hostname = this.hostname

    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)
        allowHeader(HttpHeaders.Authorization)
        allowNonSimpleContentTypes = true

        if (enableCors && !hostname.isNullOrBlank()) {
            allowHost(hostname)
        } else {
            anyHost()
        }
    }
    install(Pebble) {
        loader(ClasspathLoader().apply {
            prefix = "templates/static"
            charset = "UTF-8"
        })
    }
    routing {
        staticResources("/", "static")

        authenticationRoutes()
        authenticate("auth-parasite") {
            mainRoutes()
        }
    }

    install(StatusPages) {
        exception<AuthenticationException> { call, _ ->
            call.respond(HttpStatusCode.Unauthorized)
        }
        exception<AuthorizationException> { call, _ ->
            call.respond(HttpStatusCode.Forbidden)
        }
        exception<BadRequestException> { call, error ->
            call.respond(HttpStatusCode.BadRequest, error.cause?.message ?: error.message ?: "")
        }
        exception<RedirectException> { call, cause ->
            call.respondRedirect(cause.toRoute)
        }
        exception<NotImplementedError> { call, cause ->
            call.respond(HttpStatusCode.NotImplemented,"Not implemented")
        }
    }
}

class AuthenticationException : RuntimeException()
class AuthorizationException : RuntimeException()
class RedirectException(val toRoute: String) : RuntimeException()
