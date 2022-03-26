package net.chatalina.plugins

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.*
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

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

    private fun getDerivedKey(otherKey: String): ByteArray {
        val pubKey = ecKF.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(otherKey)))
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(serverPair.private)
        ka.doPhase(pubKey, true)
        return ka.generateSecret()
    }

    private fun getAESCipher(key: ByteArray, mode: Int, iv: ByteArray): Cipher {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5PADDING")
        cipher.init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher
    }

    private fun getNonce(): ByteArray {
        val nonce = ByteArray(16)
        SecureRandom().nextBytes(nonce)
        return nonce
    }

    fun decrypt(content: String, iv: String, otherKey: String): ByteArray {
        val derivedKey = getDerivedKey(otherKey)
        val cipher = getAESCipher(derivedKey, Cipher.DECRYPT_MODE, Base64.getDecoder().decode(iv))
        return cipher.doFinal(Base64.getDecoder().decode(content))
    }

    fun encrypt(content: ByteArray, otherKey: String): Pair<ByteArray, ByteArray>  {
        val derivedKey = getDerivedKey(otherKey)
        val nonce = getNonce()
        val cipher = getAESCipher(derivedKey, Cipher.ENCRYPT_MODE, nonce)
        return Pair(nonce, cipher.doFinal(content))
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