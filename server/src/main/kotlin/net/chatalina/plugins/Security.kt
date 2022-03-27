package net.chatalina.plugins

import com.auth0.jwk.JwkProvider
import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.impl.JWTParser
import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.auth.jwt.*
import java.net.URL
import java.security.interfaces.RSAPublicKey
import java.util.*
import java.util.concurrent.TimeUnit

class BecAuthentication(private val issuer: String, private val audience: String, jwks: String) {
    val jwkProvider: JwkProvider = JwkProviderBuilder(URL(jwks))
        .cached(10, 24, TimeUnit.HOURS)
        .rateLimited(10, 1, TimeUnit.MINUTES)
        .build()

    fun roleValidator(credential: JWTCredential, role: String): JWTPrincipal? {
        val access = credential.payload.getClaim("resource_access").asMap()[audience] as Map<String, List<String>>
        return if (access["roles"]?.contains(role) == true) JWTPrincipal(credential.payload) else null
    }

    val becVerifier: JWTConfigureFunction = {
        acceptLeeway(5)
        withAudience("account")
        withClaim("azp", audience)
    }

    fun validateJwt(token: String): JWTPrincipal? {
        return try {
            val jwk = jwkProvider.get(JWT.decode(token).keyId)
            val verifier = JWT.require(Algorithm.RSA256(jwk.publicKey as RSAPublicKey, null))
                .withIssuer(issuer)
                .apply(becVerifier)
                .build()
            val jwt = verifier.verify(token)
            val jwtParser = JWTParser()
            val payload = jwtParser.parsePayload(String(Base64.getUrlDecoder().decode(jwt.payload)))
            val credentials = JWTCredential(payload)
            JWTPrincipal(credentials.payload)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

private var becAuthentication: BecAuthentication? = null
var ApplicationEnvironment.becAuth: BecAuthentication?
    get() = becAuthentication
    set(value) {
        becAuthentication = value
    }

fun Application.configureSecurity() {
    val issuer = environment.config.property("jwt.issuer").getString()
    val audience = environment.config.property("jwt.audience").getString()
    val jwks = environment.config.property("jwt.jwks").getString()

    becAuthentication = BecAuthentication(issuer, audience, jwks)

    authentication {
        jwt("obei-bec-parasite") {
            if (becAuthentication != null) {
                verifier(becAuthentication!!.jwkProvider, issuer, becAuthentication!!.becVerifier)
                validate { becAuthentication!!.roleValidator(it, "parasite") }
            }
        }
        jwt("obei-bec-mod") {
            if (becAuthentication != null) {
                verifier(becAuthentication!!.jwkProvider, issuer, becAuthentication!!.becVerifier)
                validate { becAuthentication!!.roleValidator(it, "mod") }
            }
        }
        jwt("obei-bec-admin") {
            if (becAuthentication != null) {
                verifier(becAuthentication!!.jwkProvider, issuer, becAuthentication!!.becVerifier)
                validate { becAuthentication!!.roleValidator(it, "admin") }
            }
        }
    }
}
