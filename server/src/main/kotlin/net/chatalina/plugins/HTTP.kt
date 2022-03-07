package net.chatalina.plugins

import io.ktor.features.*
import io.ktor.http.*
import io.ktor.application.*
import io.ktor.response.*
import io.ktor.request.*

fun Application.configureHTTP() {
    install(ConditionalHeaders)
    install(CORS) {
        method(HttpMethod.Options)
        method(HttpMethod.Put)
        method(HttpMethod.Delete)
        method(HttpMethod.Patch)
        header(HttpHeaders.Authorization)
        allowHeadersPrefixed("BEC-")
        val clientIsSsl = environment.config.propertyOrNull("bec.client_ssl")?.getString() === "true"
        host(environment.config.property("bec.client_domain").getString(), listOf(if (clientIsSsl) "https" else "http"), listOf())
    }
    install(DefaultHeaders)
}
