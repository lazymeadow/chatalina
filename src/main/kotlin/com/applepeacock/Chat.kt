package com.applepeacock

import com.applepeacock.chat.ChatManager
import com.applepeacock.database.configureDatabases
import com.applepeacock.http.configureHTTP
import com.applepeacock.plugins.configureMonitoring
import com.applepeacock.plugins.configureSerialization
import com.applepeacock.plugins.configureSessions
import com.applepeacock.plugins.configureSockets
import io.ktor.server.application.*
import io.ktor.util.*
import javax.crypto.spec.SecretKeySpec

fun main(args: Array<String>): Unit =
    io.ktor.server.netty.EngineMain.main(args)

val Application.hostname
    get() = environment.config.propertyOrNull("ktor.hostname")?.getString()

val Application.hostUrl: String
    get() {
        val isSecure = environment.config.propertyOrNull("ktor.is_secure")?.getString()?.toBoolean() ?: false
        return "http${if (isSecure) "s" else ""}://${
            hostname ?: "localhost:${
                environment.config.property("ktor.deployment.port").getString()
            }"
        }"
    }

val Application.isProduction
    get() = environment.config.propertyOrNull("ktor.env")?.getString()?.equals("PROD") ?: false

val Application.siteName
    get() = environment.config.property("bec.site_name").getString()

lateinit var secretKeyField: SecretKeySpec
val Application.secretKey
    get() = secretKeyField

@Suppress("unused") // application.conf references the main function. This annotation prevents the IDE from marking it as unused.
fun Application.module() {
    val secretKeyString = environment.config.property("bec.secret_key").getString()
    secretKeyField = SecretKeySpec(secretKeyString.decodeBase64Bytes(), "AES")

//    configureSecurity()
    configureSessions()
    configureHTTP()
    configureMonitoring()
    configureSerialization()
    configureDatabases()
    configureSockets()
    ChatManager.configure(environment.config.property("bec.image_cache.bucket").getString(), environment.config.property("bec.image_cache.host").getString())
}
