package net.chatalina.plugins

import io.ktor.application.*
import net.chatalina.jsonrpc.JsonRpc

fun Application.configureJsonRpc() {
    install(JsonRpc)
}