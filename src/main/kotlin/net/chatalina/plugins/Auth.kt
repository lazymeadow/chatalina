package net.chatalina.plugins

import com.auth0.jwk.JwkProvider
import com.auth0.jwk.JwkProviderBuilder
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.sessions.*
import net.chatalina.chat.encryptSecret
import net.chatalina.chat.signSecret
import net.chatalina.chat.tokenDecrypt
import net.chatalina.chat.tokenEncrypt
import net.chatalina.hostname
import net.chatalina.isProduction
import org.jetbrains.exposed.dao.id.EntityID
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.crypto.BadPaddingException
import kotlin.random.Random
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

data class ParasiteSession(val id: String) : Principal {
    constructor(entityId: EntityID<String>) : this(entityId.value)
}
data class PreAuthSession(val t: String = tokenEncrypt(Random.nextBytes(16).decodeToString())) : Principal {
    fun getVal() = try {
        tokenDecrypt(t)
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: BadPaddingException) {
        null
    }
}

class BecAuthentication(
    private val issuer: String,
    private val clientId: String,
    jwks: String
) {
    val jwkProvider: JwkProvider = JwkProviderBuilder(URI(jwks).toURL())
        .cached(10, 24, TimeUnit.HOURS)
        .rateLimited(10, 1, TimeUnit.MINUTES)
        .build()

    val becVerifier: JWTConfigureFunction = {
        acceptLeeway(5)
        withIssuer(issuer)
        withClaim("azp", clientId)
    }
}

var becAuthentication: BecAuthentication? = null

fun Application.configureAuth() {
    val issuer = environment.config.property("jwt.issuer").getString()
    val client = environment.config.property("jwt.client").getString()
    val jwks = environment.config.property("jwt.jwks").getString()

    becAuthentication = BecAuthentication(issuer, client, jwks)

    val cookieDomain = this.hostname

    install(Sessions) {
        cookie<ParasiteSession>("parasite") {
            if (this@configureAuth.isProduction) {
                cookie.extensions["SameSite"] = "None"
                cookie.secure = true
            }
            cookie.path = "/"
            cookie.maxAge = 90.days
            cookieDomain.let { cookie.domain = it }
            cookie.httpOnly = false
            transform(SessionTransportTransformerEncrypt(encryptSecret, signSecret))
        }
        cookie<PreAuthSession>("bec-pre-auth") {
            if (this@configureAuth.isProduction) {
                cookie.extensions["SameSite"] = "None"
                cookie.secure = true
            }
            cookie.path = "/"
            cookie.maxAge = 1.hours
            cookieDomain.let { cookie.domain = it }
            transform(SessionTransportTransformerEncrypt(encryptSecret, signSecret))
        }
    }
    install(Authentication) {
        jwt("obei") {
            verifier(becAuthentication!!.jwkProvider, issuer, becAuthentication!!.becVerifier)
            validate { credential ->
                if (credential.payload.getClaim("username").asString() != "") {
                    JWTPrincipal(credential.payload)
                } else {
                    null
                }
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