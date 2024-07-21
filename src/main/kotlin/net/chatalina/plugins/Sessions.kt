package net.chatalina.plugins

import net.chatalina.chat.encryptSecret
import net.chatalina.chat.signSecret
import net.chatalina.hostname
import net.chatalina.isProduction
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.sessions.*
import kotlin.collections.set
import kotlin.time.Duration.Companion.days

data class ParasiteSession(val id: String): Principal

fun Application.configureSessions() {
    val cookieDomain = this.hostname

    install(Sessions) {
        cookie<ParasiteSession>("parasite") {
            cookie.extensions["SameSite"] = "None"
            if (this@configureSessions.isProduction) {
                cookie.secure = true
            }
            cookie.path = "/"
            cookie.maxAge = 90.days
            cookieDomain.let { cookie.domain = it }
            transform(
                SessionTransportTransformerEncrypt(encryptSecret, signSecret)
            )
        }
    }
    install(Authentication) {
        session<ParasiteSession>("auth-parasite") {
            validate { session ->
                if (session.id.isBlank()) {
                    null
                } else {
                    session
                }
            }
            challenge {
                call.respondRedirect("/login")
            }
        }
        session<ParasiteSession>("auth-parasite-socket") {
            validate { session ->
                if (session.id.isBlank()) {
                    null
                } else {
                    session
                }
            }
            challenge { /* skip handling, let the socket deal with it */ }
        }
    }
}
