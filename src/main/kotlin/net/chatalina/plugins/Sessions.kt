package net.chatalina.plugins

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.sessions.*
import net.chatalina.chat.encryptSecret
import net.chatalina.chat.signSecret
import net.chatalina.chat.tokenDecrypt
import net.chatalina.chat.tokenEncrypt
import net.chatalina.hostname
import net.chatalina.isProduction
import javax.crypto.BadPaddingException
import kotlin.collections.set
import kotlin.random.Random
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

data class ParasiteSession(val id: String) : Principal
data class PreAuthSession(val t: String = tokenEncrypt(Random.nextBytes(16).decodeToString())) : Principal {
    fun getVal() = try {
        tokenDecrypt(t)
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: BadPaddingException) {
        null
    }
}

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
            transform(SessionTransportTransformerEncrypt(encryptSecret, signSecret))
        }
        cookie<PreAuthSession>("bec-pre-auth") {
            cookie.extensions["SameSite"] = "None"
            if (this@configureSessions.isProduction) {
                cookie.secure = true
            }
            cookie.path = "/"
            cookie.maxAge = 1.hours
            cookieDomain.let { cookie.domain = it }
            transform(SessionTransportTransformerEncrypt(encryptSecret, signSecret))
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
