package com.applepeacock.chat

import io.ktor.server.application.*
import io.ktor.util.*
import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.security.*
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.*
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.Path

private const val PUBLIC_KEY_FILE = "public.key"
private const val PRIVATE_KEY_FILE = "private.key"
private const val ENCRYPT_SECRET_FILE = "encrypt.key"
private const val SIGN_SECRET_FILE = "sign.key"
private const val AES_SECRET_FILE = "aes.key"

private val rsaAlg = "RSA/ECB/PKCS1Padding"
private val aesAlg = "AES/CBC/PKCS5PADDING"
private val hmacAlg = "HmacSHA256"
private val rsaKF = KeyFactory.getInstance("RSA")
private val aesKG = KeyGenerator.getInstance("AES")
private val hmacKG = KeyGenerator.getInstance("HmacSHA256")


private fun readOrGenerateRSAKeyPair(pubKeyPath: Path, privKeyPath: Path): KeyPair {
    val pubKeyFile = pubKeyPath.toFile()
    val privKeyFile = privKeyPath.toFile()
    if (pubKeyFile.exists() && privKeyFile.exists()) {
        val pubKey = X509EncodedKeySpec(pubKeyFile.readBytes())
        val privKey = PKCS8EncodedKeySpec(privKeyFile.readBytes())
        return KeyPair(rsaKF.generatePublic(pubKey), rsaKF.generatePrivate(privKey))
    } else {
        pubKeyFile.parentFile?.mkdirs()
        pubKeyFile.createNewFile()
        privKeyFile.parentFile?.mkdirs()
        privKeyFile.createNewFile()
        val keyPairGen = KeyPairGenerator.getInstance(rsaKF.algorithm)
        keyPairGen.initialize(1024)
        val keyPair = keyPairGen.generateKeyPair()
        pubKeyFile.writeBytes(keyPair.public.encoded)
        privKeyFile.writeBytes(keyPair.private.encoded)
        return keyPair
    }
}


fun readOrGenerateKey(keyPath: Path, keyPair: KeyPair, keyGen: KeyGenerator, keyAlg: String): Key {
    val aesKeyFile = keyPath.toFile()
    if (aesKeyFile.exists()) {
        // decrypt the key before saving it
        val cipher = Cipher.getInstance(rsaAlg)
        cipher.init(Cipher.DECRYPT_MODE, keyPair.private)
        val keyContent = cipher.doFinal(aesKeyFile.readBytes())
        return SecretKeySpec(keyContent, keyAlg)
    } else {
        val newKey = keyGen.generateKey()
        // encrypt the key before writing it
        val cipher = Cipher.getInstance(rsaAlg)
        cipher.init(Cipher.ENCRYPT_MODE, keyPair.public)
        val keyContent = cipher.doFinal(newKey.encoded)
        aesKeyFile.parentFile?.mkdirs()
        aesKeyFile.createNewFile()
        aesKeyFile.writeBytes(keyContent)
        return newKey
    }
}


private fun getAESCipher(key: Key, mode: Int, iv: ByteArray): Cipher {
    val cipher = Cipher.getInstance(aesAlg)
    cipher.init(mode, key, IvParameterSpec(iv))
    return cipher
}

private fun getNonce(): ByteArray {
    val nonce = ByteArray(16)
    SecureRandom().nextBytes(nonce)
    return nonce
}

private fun decryptRSA(key: Key, iv: String, content: String): ByteArray {
    val cipher = getAESCipher(key, Cipher.DECRYPT_MODE, Base64.getDecoder().decode(iv))
    return cipher.doFinal(Base64.getDecoder().decode(content))
}

private fun encryptRSA(key: Key, content: ByteArray): Pair<String, String> {
    val nonce = getNonce()
    val cipher = getAESCipher(key, Cipher.ENCRYPT_MODE, nonce)
    val encrypted = cipher.doFinal(content)
    return Pair(Base64.getEncoder().encodeToString(nonce), Base64.getEncoder().encodeToString(encrypted))
}

@Serializable
data class EncryptedData(val iv: String, val content: String)

fun dbDecrypt(data: EncryptedData): ByteArray {
    return decryptRSA(aesKey, data.iv, data.content)
}

fun dbEncrypt(content: String): EncryptedData {
    return encryptRSA(aesKey, content.toByteArray()).let { EncryptedData(it.first, it.second) }
}

fun tokenDecrypt(content: String): String {
    val cipher = Cipher.getInstance("AES")
    cipher.init(Cipher.DECRYPT_MODE, secretKey)
    return cipher.doFinal(content.decodeBase64Bytes()).decodeToString()
}

fun tokenEncrypt(content: String): String {
    val cipher = Cipher.getInstance("AES")
    cipher.init(Cipher.ENCRYPT_MODE, secretKey)
    return cipher.doFinal(content.toByteArray()).encodeBase64()
}

private lateinit var secretKey: SecretKeySpec
private lateinit var keyPair: KeyPair
private lateinit var aesKey: Key
private lateinit var encryptSecretField: Key
private lateinit var signSecretField: Key

// session keys
val encryptSecret: SecretKeySpec
    get() = SecretKeySpec(encryptSecretField.encoded, "AES")
val signSecret: SecretKeySpec
    get() = SecretKeySpec(signSecretField.encoded, hmacAlg)


fun Application.configureEncryption() {
    val appSecretKey = environment.config.property("bec.secret_key").getString()
    val keysPath = environment.config.property("bec.encryption.key_path").getString()

    secretKey = SecretKeySpec(appSecretKey.decodeBase64Bytes(), "AES")

    aesKG.init(128)
    hmacKG.init(128)

    // rsa keys, for generating aes keys
    keyPair = readOrGenerateRSAKeyPair(Path(keysPath, PUBLIC_KEY_FILE), Path(keysPath, PRIVATE_KEY_FILE))
    // AES key for db encryption
    aesKey = readOrGenerateKey(Path(keysPath, AES_SECRET_FILE), keyPair, aesKG, "AES")

    encryptSecretField = readOrGenerateKey(Path(keysPath, ENCRYPT_SECRET_FILE), keyPair, aesKG, "AES")
    signSecretField = readOrGenerateKey(Path(keysPath, SIGN_SECRET_FILE), keyPair, hmacKG, hmacAlg)
}