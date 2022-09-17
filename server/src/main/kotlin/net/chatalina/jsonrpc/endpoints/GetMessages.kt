package net.chatalina.jsonrpc.endpoints

import io.ktor.server.auth.jwt.*
import net.chatalina.database.Parasite
import net.chatalina.jsonrpc.JsonRpcStatus
import net.chatalina.plugins.ChatHandler
import java.security.InvalidAlgorithmParameterException
import java.security.PublicKey
import javax.crypto.IllegalBlockSizeException

// make sure you directly implement the interface!! it's absolutely necessary for initializing an endpoint
object GetMessages : OpenSocketEndpoint(), Endpoint {
    override val methodName = "getMessages"
    override lateinit var chatHandler: ChatHandler
    override val authenticated = true
    override val encrypted = true
    override val executeInSocket = true

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
            val response = chatHandler.getMessages(clientKey, parasite)
            ExecutionResult.createListResult(JsonRpcStatus.EXCELLENT, response)
        } catch (e: InvalidAlgorithmParameterException) {
            ExecutionResult.createResult(JsonRpcStatus.ENCRYPTION_ERROR, null, "bad iv")
        } catch (e: IllegalBlockSizeException) {
            ExecutionResult.createResult(JsonRpcStatus.ENCRYPTION_ERROR, null, "bad content")
        }
    }
}
