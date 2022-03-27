package net.chatalina.jsonrpc.endpoints

import net.chatalina.chat.MessageContent
import net.chatalina.jsonrpc.JsonRpcStatus
import net.chatalina.jsonrpc.Parameter
import net.chatalina.jsonrpc.ParameterType
import net.chatalina.plugins.ChatHandler
import java.security.InvalidAlgorithmParameterException
import javax.crypto.IllegalBlockSizeException

// make sure you directly implement the interface!! it's absolutely necessary for initializing and endpoint
object SendMessage : EncryptedEndpoint(), Endpoint {
    override val methodName = "sendMessage"
    override lateinit var chatHandler: ChatHandler

    override val requiredParams = listOf(
        Parameter("iv", ParameterType.STRING),
        Parameter("content", ParameterType.STRING)
    )

    override val optionalParams = listOf(Parameter("id", ParameterType.GUID))

    override suspend fun execute(params: Map<String, Any>?): ExecutionResult {
        return try {
            val response = this.clientKey?.let {
                chatHandler.processNewMessage(
                    MessageContent(params!!["iv"].toString(), params["content"].toString()),
                    it
                )
            }
            if (response == null) {
                ExecutionResult(JsonRpcStatus.ENCRYPTION_ERROR, null, "invalid key")
            } else {
                ExecutionResult(JsonRpcStatus.EXCELLENT, response)
            }
        } catch (e: InvalidAlgorithmParameterException) {
            ExecutionResult(JsonRpcStatus.ENCRYPTION_ERROR, null, "bad iv")
        } catch (e: IllegalBlockSizeException) {
            ExecutionResult(JsonRpcStatus.ENCRYPTION_ERROR, null, "bad content")
        }
    }
}