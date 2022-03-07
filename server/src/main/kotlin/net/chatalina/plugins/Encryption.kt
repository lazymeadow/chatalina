package net.chatalina.plugins

import io.ktor.application.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.*
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.*
import javax.crypto.KeyAgreement

const val BEC_SERVER_HEADER = "BEC-Server-Key"
const val BEC_CLIENT_HEADER = "BEC-Client-Key"

val ApplicationRequest.clientKey: String
    get() = call.request.headers[BEC_CLIENT_HEADER] ?: ""

fun Route.withEncryption(callback: Route.() -> Unit): Route {
    val routeWithEncryption = this.createChild(object : RouteSelector() {
        override fun evaluate(context: RoutingResolveContext, segmentIndex: Int): RouteSelectorEvaluation =
            RouteSelectorEvaluation.Constant
    })

    routeWithEncryption.intercept(ApplicationCallPipeline.Features) {
        if (call.request.clientKey.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, "Missing header $BEC_CLIENT_HEADER")
            return@intercept finish()
        }
        val publicKey = call.application.feature(Encryption).publicKey
        call.response.header(BEC_SERVER_HEADER, Base64.getEncoder().encodeToString(publicKey))
    }
    callback(routeWithEncryption)

    return routeWithEncryption
}

class Encryption(configuration: PluginConfiguration) {
    private var serverPair: KeyPair
    private var ecKF = KeyFactory.getInstance("EC")

    class PluginConfiguration {
        lateinit var publicKeyPath: String
        lateinit var privateKeyPath: String
    }

    init {
        // the server will need the same key pair on restarts, so we want to read them from files
        val pubKeyFile = File(configuration.publicKeyPath)
        val privKeyFile = File(configuration.privateKeyPath)
        if (pubKeyFile.exists() && privKeyFile.exists()) {
            val pubKey = X509EncodedKeySpec(pubKeyFile.readBytes())
            val privKey = PKCS8EncodedKeySpec(privKeyFile.readBytes())
            serverPair = KeyPair(ecKF.generatePublic(pubKey), ecKF.generatePrivate(privKey))
        } else {
            pubKeyFile.parentFile?.mkdirs()
            pubKeyFile.createNewFile()
            privKeyFile.parentFile?.mkdirs()
            privKeyFile.createNewFile()
            val keyPairGen = KeyPairGenerator.getInstance("EC")
            keyPairGen.initialize(256)
            serverPair = keyPairGen.generateKeyPair()
            pubKeyFile.writeBytes(serverPair.public.encoded)
            privKeyFile.writeBytes(serverPair.private.encoded)
        }
    }

    val publicKey: ByteArray
        get() = serverPair.public.encoded

    // TODO: this should be a private function
    fun getDerivedKey(otherKey: String): ByteArray? {
        val pubKey = ecKF.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(otherKey)))
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(serverPair.private)
        ka.doPhase(pubKey, true)
        return ka.generateSecret()
    }

    companion object Feature : ApplicationFeature<ApplicationCallPipeline, PluginConfiguration, Encryption> {
        override val key = AttributeKey<Encryption>("encryption")

        override fun install(pipeline: ApplicationCallPipeline, configure: PluginConfiguration.() -> Unit): Encryption {
            val configuration = PluginConfiguration().apply(configure)
            return Encryption(configuration)
        }
    }
}

fun Application.configureEncryption() {
    install(Encryption) {
        publicKeyPath = environment.config.property("encryption.public_key").getString()
        privateKeyPath = environment.config.property("encryption.private_key").getString()
    }
}