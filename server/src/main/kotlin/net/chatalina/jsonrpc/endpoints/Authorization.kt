package net.chatalina.jsonrpc.endpoints

import net.chatalina.jsonrpc.JsonRpcStatus
import net.chatalina.jsonrpc.Parameter
import net.chatalina.jsonrpc.ParameterType
import net.chatalina.plugins.ChatHandler

// make sure you directly implement the interface!! it's absolutely necessary for initializing and endpoint
object Authorization : OpenSocketEndpoint(), Endpoint {
    override val methodName = "authorization"
    override lateinit var chatHandler: ChatHandler

    override val requiredParams = listOf(
        Parameter("token", ParameterType.STRING)
    )

    override suspend fun execute(params: Map<String, Any>?): ExecutionResult {
        return ExecutionResult.createResult(
            JsonRpcStatus.NOTIFICATION,
            chatHandler.validateToken(params?.get("token").toString())
        )
    }
}