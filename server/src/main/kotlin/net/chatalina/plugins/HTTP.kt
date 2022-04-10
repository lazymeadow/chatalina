package net.chatalina.plugins

import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.*

fun Application.configureHTTP() {
    install(ConditionalHeaders)
    install(CORS) {
        method(HttpMethod.Options)
        method(HttpMethod.Put)
        method(HttpMethod.Patch)
        method(HttpMethod.Delete)

        header(HttpHeaders.Authorization)
        header(HttpHeaders.ContentType)
        header(HttpHeaders.AccessControlAllowOrigin)
        // stupid cors headers lowercase to normalize but don't do the same when processing prefix predicates
        allowHeadersPrefixed("bec-")
        exposeHeader(BEC_SERVER_HEADER)

        val clientIsSsl = environment.config.propertyOrNull("bec.client_ssl")?.getString() === "true"
        host(environment.config.property("bec.client_domain").getString(), listOf(if (clientIsSsl) "https" else "http"), listOf())
    }
    install(DefaultHeaders)
}
