package net.chatalina.jsonrpc.endpoints

import io.ktor.server.auth.jwt.*
import net.chatalina.chat.MessageContent
import net.chatalina.database.Parasite
import net.chatalina.jsonrpc.JsonRpcStatus
import net.chatalina.jsonrpc.Parameter
import net.chatalina.jsonrpc.ParameterType
import net.chatalina.plugins.AuthorizationException
import net.chatalina.plugins.ChatHandler
import java.security.InvalidAlgorithmParameterException
import java.security.PublicKey
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

    override suspend fun execute(
        params: Map<String, Any>?,
        principal: JWTPrincipal?,
        parasite: Parasite?,
        clientKey: PublicKey?
    ): ExecutionResult {
        if (parasite == null) {
            return ExecutionResult.createResult(JsonRpcStatus.UNAUTHORIZED, null)
        }
        if (clientKey == null) {
            return ExecutionResult.createResult(JsonRpcStatus.ENCRYPTION_ERROR, null, "invalid key")
        }
        return try {
            val response = chatHandler.processNewMessage(
                    MessageContent(params!!["iv"].toString(), params["content"].toString()),
                    clientKey,
                    parasite
                )
            ExecutionResult.createResult(JsonRpcStatus.EXCELLENT, response)
        } catch (e: AuthorizationException) {
            ExecutionResult.createResult(JsonRpcStatus.FORBIDDEN, null)
        } catch (e: IllegalArgumentException) {
            ExecutionResult.createResult(JsonRpcStatus.DESTINATION_ERROR, null, "invalid destination")
        } catch (e: InvalidAlgorithmParameterException) {
            ExecutionResult.createResult(JsonRpcStatus.ENCRYPTION_ERROR, null, "bad iv")
        } catch (e: IllegalBlockSizeException) {
            ExecutionResult.createResult(JsonRpcStatus.ENCRYPTION_ERROR, null, "bad content")
        }
    }
}