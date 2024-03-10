package com.applepeacock.plugins

import com.applepeacock.hostname
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.sessions.*
import io.ktor.util.*
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlin.collections.set
import kotlin.time.Duration.Companion.days

data class ParasiteSession(val id: String): Principal

private lateinit var signSecret: SecretKey
private lateinit var encryptSecret: SecretKey

private const val LOCAL_ENCRYPT_SECRET_PATH = "local.encrypt.txt"
private const val LOCAL_SIGN_SECRET_PATH = "local.sign.txt"
private const val ENCRYPT_ALG = "AES"
private const val SIGN_ALG = "HmacSHA256"
val encryptKeyGen = KeyGenerator.getInstance(ENCRYPT_ALG)
val signKeyGen = KeyGenerator.getInstance(SIGN_ALG)

fun loadOrWriteSecretFile(path: String, keyGen: KeyGenerator, alg: String): SecretKeySpec {
    val secretFile = File(path)
    if (secretFile.exists()) {
        val content = secretFile.readText()
        return SecretKeySpec(content.decodeBase64Bytes(), alg)
    } else {
        val key = keyGen.generateKey()
        secretFile.createNewFile()
        secretFile.writeText(key.encoded.encodeBase64())
        return SecretKeySpec(key.encoded, alg)
    }
}

fun Application.configureSessions() {
    encryptKeyGen.init(128)
    signKeyGen.init(128)

    encryptSecret = loadOrWriteSecretFile(LOCAL_ENCRYPT_SECRET_PATH, encryptKeyGen, ENCRYPT_ALG)
    signSecret = loadOrWriteSecretFile(LOCAL_SIGN_SECRET_PATH, signKeyGen, SIGN_ALG)

    val cookieDomain = this.hostname

    install(Sessions) {
        cookie<ParasiteSession>("parasite") {
            cookie.extensions["SameSite"] = "None"
            cookie.path = "/"
            cookie.maxAge = 90.days
            cookieDomain.let { cookie.domain = it }
            transform(
                SessionTransportTransformerEncrypt(
                    SecretKeySpec(encryptSecret.encoded, ENCRYPT_ALG),
                    SecretKeySpec(signSecret.encoded, SIGN_ALG)
                )
            )
        }
    }
    install(Authentication) {
        session<ParasiteSession>("auth-parasite") {
            validate { session ->
                if (session.id.isBlank()) {
                    null
                } else {
                    session
                }
            }
            challenge {
                call.respondRedirect("/login")
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
