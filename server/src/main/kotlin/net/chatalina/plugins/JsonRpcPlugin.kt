package net.chatalina.plugins

import io.ktor.server.application.*
import net.chatalina.jsonrpc.JsonRpc

fun Application.configureJsonRpc() {
    install(JsonRpc)
}

val Application.jsonRpc: JsonRpc
    get() = this.plugin(JsonRpc)