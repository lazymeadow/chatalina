package net.chatalina.jsonrpc.endpoints

import net.chatalina.chat.MessageContent
import net.chatalina.jsonrpc.JsonRpcStatus
import net.chatalina.jsonrpc.Parameter
import net.chatalina.jsonrpc.ParameterType
import net.chatalina.plugins.ChatHandler
import java.security.InvalidAlgorithmParameterException
import javax.crypto.IllegalBlockSizeException

// make sure you directly implement the interface!! it's absolutely necessary for initializing and endpoint
object KeyExchange: OpenSocketEndpoint(), Endpoint {
    override val methodName = "keyExchange"
    override lateinit var chatHandler: ChatHandler

    override val requiredParams = listOf(
        Parameter("key", ParameterType.STRING)
    )

    override suspend fun execute(params: Map<String, Any>?): ExecutionResult {
        return ExecutionResult(JsonRpcStatus.NOTIFICATION, params?.get("key").toString())
    }
}