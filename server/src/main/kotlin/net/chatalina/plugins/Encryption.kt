package net.chatalina.plugins

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.util.*
import java.io.File
import java.security.*
import java.security.spec.InvalidKeySpecException
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.*
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

const val BEC_SERVER_HEADER = "BEC-Server-Key"
const val BEC_CLIENT_HEADER = "BEC-Client-Key"

val ApplicationRequest.clientKey: String
    get() = call.request.headers[BEC_CLIENT_HEADER] ?: ""

class Encryption(configuration: PluginConfiguration) {
    private var serverPair: KeyPair
    private var dbPair: KeyPair
    private var aesKey: Key
    private var ecKF = KeyFactory.getInstance("EC")
    private var rsaKF = KeyFactory.getInstance("RSA")
    private var aesKG = KeyGenerator.getInstance("AES")
    private val rsaAlg = "RSA/ECB/PKCS1Padding"
    private val aesAlg = "AES/CBC/PKCS5PADDING"

    class PluginConfiguration {
        lateinit var publicKeyPath: String
        lateinit var dbPublicKeyPath: String
        lateinit var privateKeyPath: String
        lateinit var dbPrivateKeyPath: String
        lateinit var dbAesKeyPath: String
    }

    private fun readOrGenerateKeyPairs(pubKeyPath: String, privKeyPath: String, kf: KeyFactory, keySize: Int): KeyPair {
        val pubKeyFile = File(pubKeyPath)
        val privKeyFile = File(privKeyPath)
        if (pubKeyFile.exists() && privKeyFile.exists()) {
            val pubKey = X509EncodedKeySpec(pubKeyFile.readBytes())
            val privKey = PKCS8EncodedKeySpec(privKeyFile.readBytes())
            return KeyPair(kf.generatePublic(pubKey), kf.generatePrivate(privKey))
        } else {
            pubKeyFile.parentFile?.mkdirs()
            pubKeyFile.createNewFile()
            privKeyFile.parentFile?.mkdirs()
            privKeyFile.createNewFile()
            val keyPairGen = KeyPairGenerator.getInstance(kf.algorithm)
            keyPairGen.initialize(keySize)
            val keyPair = keyPairGen.generateKeyPair()
            pubKeyFile.writeBytes(keyPair.public.encoded)
            privKeyFile.writeBytes(keyPair.private.encoded)
            return keyPair
        }
    }

    init {
        // the server will need the same key pair on restarts, so we want to read them from files
        serverPair = readOrGenerateKeyPairs(configuration.publicKeyPath, configuration.privateKeyPath, ecKF, 256)
        dbPair = readOrGenerateKeyPairs(configuration.dbPublicKeyPath, configuration.dbPrivateKeyPath, rsaKF, 1024)
        // ok now... the AES key for db encryption
        val aesKeyFile = File(configuration.dbAesKeyPath)
        if (aesKeyFile.exists()) {
            // decrypt the key before saving it
            val cipher = Cipher.getInstance(rsaAlg)
            cipher.init(Cipher.DECRYPT_MODE, dbPair.private)
            val keyContent = cipher.doFinal(aesKeyFile.readBytes())
            aesKey = SecretKeySpec(keyContent, "AES")
        } else {
            aesKey = aesKG.generateKey()
            // encrypt the key before writing it
            val cipher = Cipher.getInstance(rsaAlg)
            cipher.init(Cipher.ENCRYPT_MODE, dbPair.public)
            val keyContent = cipher.doFinal(aesKey.encoded)
            aesKeyFile.parentFile?.mkdirs()
            aesKeyFile.createNewFile()
            aesKeyFile.writeBytes(keyContent)
        }
    }

    val publicKey: ByteArray
        get() = serverPair.public.encoded

    fun validateAndGetPublicKey(keyString: String): PublicKey? {
        return try {
            ecKF.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(keyString)))
        } catch (e: InvalidKeySpecException) {
            e.printStackTrace()
            null
        }
    }

    private fun getDerivedKey(otherKey: PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(serverPair.private)
        ka.doPhase(otherKey, true)
        return ka.generateSecret()
    }

    private fun getAESCipher(key: Key, mode: Int, iv: ByteArray): Cipher {
        val cipher = Cipher.getInstance(aesAlg)
        cipher.init(mode, key, IvParameterSpec(iv))
        return cipher
    }

    private fun getAESCipher(key: ByteArray, mode: Int, iv: ByteArray): Cipher {
        return getAESCipher(SecretKeySpec(key, "AES"), mode, iv)
    }

    private fun getNonce(): ByteArray {
        val nonce = ByteArray(16)
        SecureRandom().nextBytes(nonce)
        return nonce
    }

    fun decryptEC(content: String, iv: String, otherKey: PublicKey): ByteArray {
        val derivedKey = getDerivedKey(otherKey)
        val cipher = getAESCipher(derivedKey, Cipher.DECRYPT_MODE, Base64.getDecoder().decode(iv))
        return cipher.doFinal(Base64.getDecoder().decode(content))
    }

    fun encryptEC(content: ByteArray, otherKey: PublicKey): Pair<ByteArray, ByteArray> {
        val derivedKey = getDerivedKey(otherKey)
        val nonce = getNonce()
        val cipher = getAESCipher(derivedKey, Cipher.ENCRYPT_MODE, nonce)
        return Pair(nonce, cipher.doFinal(content))
    }

    fun encryptDB(content: ByteArray): Pair<String, String> {
        val nonce = getNonce()
        val cipher = getAESCipher(aesKey, Cipher.ENCRYPT_MODE, nonce)
        val encrypted = cipher.doFinal(content)
        return Pair(Base64.getEncoder().encodeToString(nonce), Base64.getEncoder().encodeToString(encrypted))
    }

    fun decryptDB(content: String, iv: String): ByteArray {
        val cipher = getAESCipher(aesKey, Cipher.DECRYPT_MODE, Base64.getDecoder().decode(iv))
        return cipher.doFinal(Base64.getDecoder().decode(content))
    }

    companion object Feature : BaseApplicationPlugin<ApplicationCallPipeline, PluginConfiguration, Encryption> {
        override val key = AttributeKey<Encryption>("Encryption")

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
        dbPublicKeyPath = environment.config.property("encryption.db_public_key").getString()
        dbPrivateKeyPath = environment.config.property("encryption.db_private_key").getString()
        dbAesKeyPath = environment.config.property("encryption.db_aes_key").getString()
    }
}

val Application.encryption: Encryption
    get() = this.plugin(Encryption)
