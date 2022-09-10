package net.chatalina.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.conditionalheaders.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.defaultheaders.*

fun Application.configureHTTP() {
    val clientIsSsl = environment.config.propertyOrNull("bec.client_ssl")?.getString() === "true"
    val clientDomain = environment.config.property("bec.client_domain").getString()

    install(ConditionalHeaders)
    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)

        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.AccessControlAllowOrigin)
        // stupid cors headers lowercase to normalize but don't do the same when processing prefix predicates
        allowHeadersPrefixed("bec-")
        exposeHeader(BEC_SERVER_HEADER)

        allowHost(clientDomain, listOf(if (clientIsSsl) "https" else "http"), listOf())
    }
    install(DefaultHeaders)
}
