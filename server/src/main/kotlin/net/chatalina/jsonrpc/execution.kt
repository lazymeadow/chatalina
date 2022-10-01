package net.chatalina.jsonrpc

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.JsonMappingException
import io.ktor.server.auth.jwt.*
import net.chatalina.chat.MessageContent
import net.chatalina.database.Parasite
import net.chatalina.plugins.AuthorizationException
import net.chatalina.plugins.ChatHandler
import java.security.GeneralSecurityException
import java.security.InvalidAlgorithmParameterException
import java.security.PublicKey
import javax.crypto.IllegalBlockSizeException

internal fun interface Executor {
    val authenticated: Boolean
        get() = false
    val encrypted: Boolean
        get() = false
    val httpAllowed: Boolean
        get() = true
    val socketAllowed: Boolean
        get() = false
    val isNotification: Boolean
        get() = false

    val requiredParams: ParameterList
        get() = ParameterList()
    val optionalParams: ParameterList
        get() = ParameterList()

    private fun validateParams(params: MutableMap<String, Any>?): Pair<String?, List<String>> {
        val paramErrors = mutableListOf<String>()

        // first, check for no parameters when there are some required.
        if (params.isNullOrEmpty() && requiredParams.isNotEmpty()) {
            return "This method requires parameters: (${requiredParams})" to paramErrors
        } else if (params != null) {
            // check for unknown parameters (all valid ones are either required or optional)
            val validParamNames = requiredParams.names + optionalParams.names
            params.keys.filterNot { it in validParamNames }.let { badParams ->
                if (badParams.isNotEmpty()) {
                    // stop processing RIGHT NOW, this request is just terrible.
                    return "Unknown parameters: ${badParams.joinToString(", ") { "'$it'" }}" to paramErrors
                }
            }

            // check that required parameters are present and not null
            requiredParams.mapNotNullTo(paramErrors) { requiredParam ->
                params[requiredParam.name].let { paramValue ->
                    if (paramValue == null) {
                        "Param '${requiredParam.name}' is required"
                    } else if (!requiredParam.type.validate(paramValue)) {
                        requiredParam.type.getErrorMessage(requiredParam.name)
                    } else {
                        null
                    }
                }
            }

            // optional params need only have a valid value - they can be null or missing
            optionalParams.mapNotNullTo(paramErrors) { optionalParam ->
                params[optionalParam.name]?.let { paramValue ->
                    if (!optionalParam.type.validate(paramValue)) {
                        optionalParam.type.getErrorMessage(optionalParam.name)
                    } else {
                        null
                    }
                }
            }
        }

        return null to paramErrors
    }

    fun validate(rpcBody: JsonRpcCallBody, source: RequestSource): JsonRpcResult? {
        // the endpoint must be accessible in the current protocol
        return if ((source == RequestSource.SOCKET && !socketAllowed) || (source == RequestSource.HTTP && !httpAllowed)) {
            generateErrorResult(
                rpcBody.id,
                JsonRpcStatus.METHOD_NOT_FOUND,
                "method '${rpcBody.method}' not recognized"
            )
        } else if (!isNotification && rpcBody.id.isNullOrBlank()) {
            generateErrorResult(
                rpcBody.id,
                JsonRpcStatus.INVALID_REQUEST,
                "field 'id' is required"
            )
        } else {
            val (message, errors) = validateParams(rpcBody.params)
            if (!message.isNullOrBlank()) {
                generateErrorResult(
                    rpcBody.id,
                    JsonRpcStatus.INVALID_PARAMS,
                    message
                )
            } else if (errors.isNotEmpty()) {
                generateErrorResult(
                    rpcBody.id,
                    JsonRpcStatus.INVALID_PARAMS,
                    errors
                )
            } else {
                null
            }
        }
    }

    suspend fun execute(
        params: Map<String, Any>?,
        principal: JWTPrincipal?,
        parasite: Parasite?,
        clientKey: PublicKey?,
        chatHandler: ChatHandler
    ): ExecutionResult
}

suspend fun executeEncrypted(params: Map<String, Any>, execution: suspend (MessageContent) -> ExecutionResult): ExecutionResult {
    return try {
        val messageContent = params.let { MessageContent(it["iv"].toString(), it["content"].toString()) }
        execution(messageContent)
    } catch (e: AuthorizationException) {
        ExecutionResult.createResult(JsonRpcStatus.FORBIDDEN, null)
    } catch (e: IllegalArgumentException) {
        ExecutionResult.createResult(JsonRpcStatus.ENCRYPTION_ERROR, null, "bad data")
    } catch (e: GeneralSecurityException) {
        ExecutionResult.createResult(JsonRpcStatus.ENCRYPTION_ERROR, null, "bad data")
    } catch (e: JsonMappingException) {
        ExecutionResult.createResult(JsonRpcStatus.ENCRYPTION_ERROR, null, "bad data")
    } catch (e: JsonParseException) {
        ExecutionResult.createResult(JsonRpcStatus.ENCRYPTION_ERROR, null, "bad data")
    }
}

suspend fun executeEncrypted(execution: suspend () -> ExecutionResult): ExecutionResult {
    return try {
        execution()
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
