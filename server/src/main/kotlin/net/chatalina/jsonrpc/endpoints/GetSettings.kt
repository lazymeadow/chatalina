package net.chatalina.jsonrpc.endpoints

import io.ktor.server.auth.jwt.*
import net.chatalina.database.Parasite
import net.chatalina.jsonrpc.JsonRpcStatus
import net.chatalina.plugins.ChatHandler
import java.security.PublicKey

object GetSettings : OpenSocketEndpoint(), Endpoint  {
    override val methodName = "settings.get"
    override lateinit var chatHandler: ChatHandler
    override val authenticated = true
    override val encrypted = true

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

        val response = GetMessages.chatHandler.getSettings(clientKey, parasite)
        return ExecutionResult.createResult(JsonRpcStatus.EXCELLENT, response)
    }
}