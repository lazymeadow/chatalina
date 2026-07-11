package net.chatalina

import io.ktor.server.application.*
import net.chatalina.chat.ChatManager
import net.chatalina.chat.EmailHandler
import net.chatalina.chat.configureEncryption
import net.chatalina.database.configureDatabases
import net.chatalina.emoji.EmojiManager
import net.chatalina.http.configureHTTP
import net.chatalina.plugins.configureAuth
import net.chatalina.plugins.configureMonitoring
import net.chatalina.plugins.configureSerialization
import net.chatalina.plugins.configureSockets

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
//    configureSessions()
    configureAuth()
    configureHTTP()
    configureMonitoring()
    configureSerialization()
    configureDatabases()
    configureSockets()

    EmojiManager.configure()  // requires database

    ChatManager.configure(
        ChatManager.ImageCacheSettings(
            environment.config.property("bec.image_cache.bucket").getString(),
            environment.config.property("bec.image_cache.host").getString(),
            environment.config.propertyOrNull("bec.image_cache.endpoint")?.getString(),
            environment.config.property("bec.image_cache.access_key").getString(),
            environment.config.property("bec.image_cache.secret").getString(),
            environment.config.property("bec.image_cache.region").getString()
        ),
        environment.config.property("bec.github.user").getString(),
        environment.config.property("bec.github.token").getString(),
        environment.config.property("bec.github.repo").getString(),
        environment.config.property("bec.gorillagroove.host").getString()
    )
    EmailHandler.configure(
        environment.config.property("bec.email.from_address").getString(),
        environment.config.property("bec.email.email_api").getString(),
        environment.config.property("bec.email.email_user").getString(),
        environment.config.property("bec.email.email_pass").getString(),
    )
}
