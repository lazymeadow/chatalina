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

// make sure you directly implement the interface!! it's absolutely necessary for initializing an endpoint
object UpdateSettings : EncryptedEndpoint(), Endpoint {
    override val methodName = "settings.update"
    override lateinit var chatHandler: ChatHandler

    override val requiredParams = listOf(
        Parameter("iv", ParameterType.STRING),
        Parameter("content", ParameterType.STRING)
    )

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
            val response = SendMessage.chatHandler.updateSettings(
                MessageContent(params!!["iv"].toString(), params["content"].toString()),
                clientKey,
                parasite
            )
            ExecutionResult.createResult(JsonRpcStatus.EXCELLENT, response)
        } catch (e: AuthorizationException) {
            ExecutionResult.createResult(JsonRpcStatus.FORBIDDEN, null)
        } catch (e: IllegalArgumentException) {
            ExecutionResult.createResult(JsonRpcStatus.ENCRYPTION_ERROR, null, "bad data")
        } catch (e: InvalidAlgorithmParameterException) {
            ExecutionResult.createResult(JsonRpcStatus.ENCRYPTION_ERROR, null, "bad data")
        } catch (e: IllegalBlockSizeException) {
            ExecutionResult.createResult(JsonRpcStatus.ENCRYPTION_ERROR, null, "bad data")
        }
    }
}