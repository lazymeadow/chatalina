package net.chatalina.jsonrpc

import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.requestvalidation.*
import net.chatalina.database.Parasite
import net.chatalina.plugins.AuthorizationException
import net.chatalina.plugins.ChatHandler
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.IOException
import java.security.PublicKey
import java.util.*
import javax.crypto.BadPaddingException

private val logger = LoggerFactory.getLogger("RPC")

suspend fun processJsonRpcRequest(
    getBody: suspend () -> JsonRpcCallBody,
    getPrincipal: () -> JWTPrincipal?,
    getClientKey: () -> PublicKey?,
    chatHandler: ChatHandler,
    source: RequestSource
): JsonRpcResult {
    val rpcBody: JsonRpcCallBody = try {
        getBody()
    } catch (e: IOException) {
        return generateErrorResult(null, JsonRpcStatus.PARSE_ERROR)
    } catch (e: BadRequestException) {
        return generateErrorResult(null, JsonRpcStatus.INVALID_REQUEST, e.message.takeUnless { it.isNullOrBlank() })
    } catch (e: RequestValidationException) {
        return generateErrorResult(null, JsonRpcStatus.INVALID_REQUEST, e.reasons)
    }

    // method must be recognized
    val methodHandler = MethodHandler.getOrNull(rpcBody.method) ?: return generateErrorResult(
        rpcBody.id,
        JsonRpcStatus.METHOD_NOT_FOUND,
        "method '${rpcBody.method}' not recognized"
    )

    try {
        fun getParasite(principal: JWTPrincipal): Parasite? {
            val parasiteId = try {
                UUID.fromString(principal.subject)
            } catch (e: IllegalArgumentException) {
                return null
            }
            return transaction {
                // find or add the parasite. no need to pass it on (yet)
                Parasite.findById(parasiteId) ?: Parasite.new(parasiteId) {
                    this.displayName = principal["preferred_username"] ?: "user-${parasiteId}"
                }
            }
        }
        // now execute and return
        return methodHandler.run(rpcBody, source, getPrincipal, ::getParasite, getClientKey, chatHandler)
    } catch (e: NotImplementedError) {
        return generateErrorResult(
            rpcBody.id,
            JsonRpcStatus.SERVER_ERROR,
            "method '${rpcBody.method}' has not yet been implemented."
        )
    } catch (e: AuthorizationException) {
        return generateErrorResult(rpcBody.id, JsonRpcStatus.FORBIDDEN)
    } catch (e: BadPaddingException) {
        return generateErrorResult(rpcBody.id, JsonRpcStatus.ENCRYPTION_ERROR)
    } catch (e: InvalidEncryptedRequestException) {
        return generateErrorResult(rpcBody.id, JsonRpcStatus.REFUSED, e.errorDetails)
    } catch (e: Throwable) {
        logger.error("Error processing request")
        e.printStackTrace()
        return generateErrorResult(rpcBody.id, JsonRpcStatus.SERVER_ERROR)
    }
}
