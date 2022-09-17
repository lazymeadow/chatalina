package net.chatalina.jsonrpc.endpoints

import io.ktor.server.auth.jwt.*
import net.chatalina.database.Parasite
import net.chatalina.jsonrpc.JsonRpcStatus
import net.chatalina.jsonrpc.Parameter
import net.chatalina.jsonrpc.ParameterType
import net.chatalina.plugins.ChatHandler
import java.security.PublicKey

// make sure you directly implement the interface!! it's absolutely necessary for initializing an endpoint
object KeyExchange : OpenSocketEndpoint(), Endpoint {
    override val methodName = "keyExchange"
    override lateinit var chatHandler: ChatHandler

    override val requiredParams = listOf(
        Parameter("key", ParameterType.STRING)
    )

    override suspend fun execute(
        params: Map<String, Any>?,
        principal: JWTPrincipal?,
        parasite: Parasite?,
        clientKey: PublicKey?
    ): ExecutionResult {
        return ExecutionResult.createResult(JsonRpcStatus.NOTIFICATION, params?.get("key").toString())
    }
}
