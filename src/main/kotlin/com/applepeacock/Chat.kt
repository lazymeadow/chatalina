package com.applepeacock

import com.applepeacock.chat.ChatManager
import com.applepeacock.chat.EmailHandler
import com.applepeacock.chat.configureEncryption
import com.applepeacock.database.configureDatabases
import com.applepeacock.emoji.EmojiManager
import com.applepeacock.http.configureHTTP
import com.applepeacock.plugins.configureMonitoring
import com.applepeacock.plugins.configureSerialization
import com.applepeacock.plugins.configureSessions
import com.applepeacock.plugins.configureSockets
import io.ktor.server.application.*

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

private const val HISTORY_LIMIT_DEFAULT = 200L
lateinit var historyLimit: Number

@Suppress("unused") // application.conf references the main function. This annotation prevents the IDE from marking it as unused.
fun Application.module() {
    historyLimit = environment.config.propertyOrNull("bec.history_limit_override")?.getString()?.toLong()
            ?: HISTORY_LIMIT_DEFAULT

//    configureSecurity()
    configureEncryption()
    configureSessions()
    configureHTTP()
    configureMonitoring()
    configureSerialization()
    configureDatabases()
    configureSockets()

    EmojiManager.configure()  // requires database

    ChatManager.configure(
        environment.config.property("bec.image_cache.bucket").getString(),
        environment.config.property("bec.image_cache.host").getString(),
        environment.config.property("bec.github.user").getString(),
        environment.config.property("bec.github.token").getString(),
        environment.config.property("bec.github.repo").getString(),
        environment.config.property("bec.gorillagroove.host").getString()
    )
    EmailHandler.configure(
        environment.config.property("bec.email.from_address").getString(),
        environment.config.property("bec.email.smtp_host").getString(),
        environment.config.property("bec.email.smtp_port").getString(),
        environment.config.propertyOrNull("bec.email.smtp_tls")?.getString()?.toBooleanStrictOrNull(),
        environment.config.propertyOrNull("bec.email.smtp_user")?.getString(),
        environment.config.propertyOrNull("bec.email.smtp_pass")?.getString(),
    )
}
