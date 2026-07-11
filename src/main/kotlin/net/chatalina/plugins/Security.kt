package net.chatalina.plugins

import com.auth0.jwk.JwkProvider
import com.auth0.jwk.JwkProviderBuilder
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import java.net.URL
import java.util.concurrent.TimeUnit

//class BecAuthentication(
//    private val issuer: String,
//    private val audience: String,
//    private val clientId: String,
//    jwks: String
//) {
//    val jwkProvider: JwkProvider = JwkProviderBuilder(URL(jwks))
//        .cached(10, 24, TimeUnit.HOURS)
//        .rateLimited(10, 1, TimeUnit.MINUTES)
//        .build()
//
////    fun roleValidator(credential: JWTCredential, role: String): JWTPrincipal? {
////        val access = credential.payload.getClaim("resource_access").asMap()[audience] as Map<String, List<String>>
////        return if (access["roles"]?.contains(role) == true) JWTPrincipal(credential.payload) else null
////    }
//
//    val becVerifier: JWTConfigureFunction = {
//        acceptLeeway(5)
//        withAudience(audience)
//        withIssuer(issuer)
//        withClaim("azp", clientId)
//    }
//}
//
//var becAuthentication: BecAuthentication? = null
//
//fun Application.configureSecurity() {
//    val issuer = environment.config.property("jwt.issuer").getString()
//    val audience = environment.config.property("jwt.audience").getString()
//    val client = environment.config.property("jwt.client").getString()
//    val jwks = environment.config.property("jwt.jwks").getString()
////    val obeiRealm = environment.config.property("jwt.realm").getString()
//
//    becAuthentication = BecAuthentication(issuer, audience, client, jwks)
//
//    authentication {
//        jwt("obei") {
////            realm = obeiRealm
//            verifier(becAuthentication!!.jwkProvider, issuer, becAuthentication!!.becVerifier)
////            validate { becAuthentication!!.roleValidator(it, "parasite") }
//        }
//    }
//}
