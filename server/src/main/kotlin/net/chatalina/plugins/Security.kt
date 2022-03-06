package net.chatalina.plugins

import com.auth0.jwk.JwkProviderBuilder
import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.auth.jwt.*
import java.net.URL
import java.util.concurrent.TimeUnit


fun Application.configureSecurity() {
    val issuer = environment.config.property("jwt.issuer").getString()
    val audience = environment.config.property("jwt.audience").getString()

    val jwkProvider = JwkProviderBuilder(URL(environment.config.property("jwt.jwks").getString()))
        .cached(10, 24, TimeUnit.HOURS)
        .rateLimited(10, 1, TimeUnit.MINUTES)
        .build()
    val becVerifier: JWTConfigureFunction = {
        acceptLeeway(3)
        withAudience("account")
        withClaim("azp", audience)
    }

    authentication {
        jwt("obei-bec-parasite") {
            verifier(jwkProvider, issuer, becVerifier)
            validate { credential ->
                val access =
                    credential.payload.getClaim("resource_access").asMap()[audience] as Map<String, List<String>>
                if (access["roles"]?.contains("parasite") == true) JWTPrincipal(credential.payload) else null
            }
        }
        jwt("obei-bec-mod") {
            verifier(jwkProvider, issuer, becVerifier)
            validate { credential ->
                val access =
                    credential.payload.getClaim("resource_access").asMap()[audience] as Map<String, List<String>>
                if (access["roles"]?.contains("mod") == true) JWTPrincipal(credential.payload) else null
            }
        }
        jwt("obei-bec-admin") {
            verifier(jwkProvider, issuer, becVerifier)
            validate { credential ->
                val access =
                    credential.payload.getClaim("resource_access").asMap()[audience] as Map<String, List<String>>
                if (access["roles"]?.contains("admin") == true) JWTPrincipal(credential.payload) else null
            }
        }
    }
}
