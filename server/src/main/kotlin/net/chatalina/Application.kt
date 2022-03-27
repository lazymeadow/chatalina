package net.chatalina

import io.ktor.application.*
import net.chatalina.plugins.*

fun main(args: Array<String>): Unit =
    io.ktor.server.netty.EngineMain.main(args)


@Suppress("unused") // application.conf references the main function. This annotation prevents the IDE from marking it as unused.
fun Application.module() {
    configureHTTP()
    configureSecurity()
    configureMonitoring()
    configureAdministration()

    configureSerialization()
    configureEncryption()

    // it relies on other features (encryption, sockets
    configureChatHandler()

    configureRouting()
    configureJsonRpc()
    configureSockets()
}
