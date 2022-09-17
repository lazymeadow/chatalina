package net.chatalina.jsonrpc.endpoints

import io.ktor.server.auth.jwt.*
import net.chatalina.database.Parasite
import net.chatalina.jsonrpc.JsonRpcStatus
import net.chatalina.plugins.ChatHandler
import java.security.PublicKey

object GetGroups : OpenSocketEndpoint(), Endpoint {
    override val methodName = "groups.get"
    override lateinit var chatHandler: ChatHandler
    override val authenticated = true
    override val isNotification = false

    override suspend fun execute(
        params: Map<String, Any>?,
        principal: JWTPrincipal?,
        parasite: Parasite?,
        clientKey: PublicKey?
    ): ExecutionResult {
        if (parasite == null) {
            return ExecutionResult.createResult(JsonRpcStatus.UNAUTHORIZED, null)
        }

        val response = GetMessages.chatHandler.getGroups(parasite)
        return ExecutionResult.createListResult(JsonRpcStatus.EXCELLENT, response)
    }
}
