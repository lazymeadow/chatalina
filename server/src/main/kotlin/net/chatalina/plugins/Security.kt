package net.chatalina.plugins

import com.auth0.jwk.JwkProviderBuilder
import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.auth.jwt.*
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/*** TODO: REMOVE THIS WHEN AUTH IS IN PROD ***/
class TrustAllX509TrustManager : X509TrustManager {
    override fun getAcceptedIssuers(): Array<X509Certificate?> {
        return arrayOfNulls(0)
    }

    override fun checkClientTrusted(certs: Array<X509Certificate?>?, authType: String?) {}

    override fun checkServerTrusted(certs: Array<X509Certificate?>?, authType: String?) {}
}
/***/

fun Application.configureSecurity() {
    /*** TODO: REMOVE THIS WHEN AUTH IS IN PROD ***/
    val sc: SSLContext = SSLContext.getInstance("TLS")
    sc.init(null, arrayOf(TrustAllX509TrustManager()), SecureRandom())
    HttpsURLConnection.setDefaultSSLSocketFactory(sc.socketFactory)
    HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
    /***/


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
        jwt("obei-bec-user") {
            verifier(jwkProvider, issuer, becVerifier)
            validate { credential ->
                val access =
                    credential.payload.getClaim("resource_access").asMap()[audience] as Map<String, List<String>>
                if (access["roles"]?.contains("user") == true) JWTPrincipal(credential.payload) else null
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
