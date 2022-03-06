package net.chatalina.plugins

import io.ktor.application.*
import io.ktor.util.*
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.*
import javax.crypto.KeyAgreement

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

    val publicKey
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
            val feature = Encryption(configuration)
            // other stuff.
            return feature
        }
    }
}

fun Application.configureEncryption() {
    install(Encryption) {
        publicKeyPath = environment.config.property("encryption.public_key").getString()
        privateKeyPath = environment.config.property("encryption.private_key").getString()
    }
}