package net.chatalina.jsonrpc

import io.ktor.server.auth.jwt.*
import net.chatalina.database.Parasite
import net.chatalina.plugins.ChatHandler
import java.security.GeneralSecurityException
import java.security.PublicKey


enum class MethodHandler : Executor {
    // socket only methods
    AUTHORIZATION {
        override val httpAllowed = false
        override val socketAllowed = true
        override val isNotification = true

        override val requiredParams = ParameterList(Parameter("token", ParameterType.STRING))

        override suspend fun execute(
            params: Map<String, Any>?,
            principal: JWTPrincipal?,
            parasite: Parasite?,
            clientKey: PublicKey?,
            chatHandler: ChatHandler
        ): ExecutionResult {
            return ExecutionResult.createResult(
                JsonRpcStatus.NOTIFICATION,
                chatHandler.validateToken(params?.get("token").toString())
            )
        }
    },
    ENCRYPTION_KEY {
        override val httpAllowed = false
        override val socketAllowed = true
        override val isNotification = true

        override val requiredParams = ParameterList(Parameter("key", ParameterType.STRING))

        override suspend fun execute(
            params: Map<String, Any>?,
            principal: JWTPrincipal?,
            parasite: Parasite?,
            clientKey: PublicKey?,
            chatHandler: ChatHandler
        ): ExecutionResult {
            // this call is really just a pass through to set the key on the socket.
            return ExecutionResult.createResult(JsonRpcStatus.NOTIFICATION, params?.get("key").toString())
        }
    },

    // encrypted methods
    GROUPS_CREATE {
        override val authenticated = true
        override val encrypted = true
        override val httpAllowed = true
        override val socketAllowed = true

        override val requiredParams = ParameterList(
            Parameter("iv", ParameterType.STRING),
            Parameter("content", ParameterType.STRING)
        )

        override suspend fun execute(
            params: Map<String, Any>?,
            principal: JWTPrincipal?,
            parasite: Parasite?,
            clientKey: PublicKey?,
            chatHandler: ChatHandler
        ): ExecutionResult {
            return executeEncrypted(params!!) { messageContent ->
                val response = chatHandler.createGroup(
                    messageContent,
                    clientKey!!,
                    parasite!!
                )
                ExecutionResult.createResult(JsonRpcStatus.EXCELLENT, response)
            }
        }
    },
    DESTINATIONS_GET {
        override val authenticated = true
        override val encrypted = true
        override val httpAllowed = true
        override val socketAllowed = true

        override suspend fun execute(
            params: Map<String, Any>?,
            principal: JWTPrincipal?,
            parasite: Parasite?,
            clientKey: PublicKey?,
            chatHandler: ChatHandler
        ): ExecutionResult {
            return executeEncrypted {
                val response = chatHandler.getDestinations(clientKey!!, parasite!!)
                ExecutionResult.createResult(JsonRpcStatus.EXCELLENT, response)
            }
        }
    },
    MESSAGES_GET {
        override val authenticated = true
        override val encrypted = true
        override val httpAllowed = true
        override val socketAllowed = true

        override suspend fun execute(
            params: Map<String, Any>?,
            principal: JWTPrincipal?,
            parasite: Parasite?,
            clientKey: PublicKey?,
            chatHandler: ChatHandler
        ): ExecutionResult {
            return executeEncrypted {
                val response = chatHandler.getMessages(clientKey!!, parasite!!)
                ExecutionResult.createListResult(JsonRpcStatus.EXCELLENT, response)
            }
        }
    },
    MESSAGES_SEND {
        override val authenticated = true
        override val encrypted = true
        override val httpAllowed = true

        override val requiredParams = ParameterList(
            Parameter("iv", ParameterType.STRING),
            Parameter("content", ParameterType.STRING)
        )

        override suspend fun execute(
            params: Map<String, Any>?,
            principal: JWTPrincipal?,
            parasite: Parasite?,
            clientKey: PublicKey?,
            chatHandler: ChatHandler
        ): ExecutionResult {
            return executeEncrypted(params!!) {messageContent ->
                val response = chatHandler.processNewMessage(messageContent, clientKey!!, parasite!!)
                ExecutionResult.createResult(JsonRpcStatus.EXCELLENT, response)
            }
        }
    },
    SETTINGS_GET {
        override val authenticated = true
        override val encrypted = true
        override val httpAllowed = true
        override val socketAllowed = true
        override val isNotification = false

        override suspend fun execute(
            params: Map<String, Any>?,
            principal: JWTPrincipal?,
            parasite: Parasite?,
            clientKey: PublicKey?,
            chatHandler: ChatHandler
        ): ExecutionResult {
            return executeEncrypted {
                val response = chatHandler.getSettings(clientKey!!, parasite!!)
                ExecutionResult.createResult(JsonRpcStatus.EXCELLENT, response)
            }
        }
    },
    SETTINGS_UPDATE {
        override val authenticated = true
        override val encrypted = true
        override val httpAllowed = true

        override val requiredParams = ParameterList(
            Parameter("iv", ParameterType.STRING),
            Parameter("content", ParameterType.STRING)
        )

        override suspend fun execute(
            params: Map<String, Any>?,
            principal: JWTPrincipal?,
            parasite: Parasite?,
            clientKey: PublicKey?,
            chatHandler: ChatHandler
        ): ExecutionResult {
            return executeEncrypted(params!!) { messageContent ->
                val response = chatHandler.updateSettings(messageContent, clientKey!!, parasite!!)
                ExecutionResult.createResult(JsonRpcStatus.EXCELLENT, response)
            }
        }
    };

    suspend fun run(
        rpcBody: JsonRpcCallBody,
        source: RequestSource,
        getPrincipal: () -> JWTPrincipal?,
        getParasite: (JWTPrincipal) -> Parasite?,
        getClientKey: () -> PublicKey?,
        chatHandler: ChatHandler
    ): JsonRpcResult {
        validate(rpcBody, source)?.let { validationFailResult ->
            return validationFailResult
        }
        var principal: JWTPrincipal? = null
        var parasite: Parasite? = null
        var clientKey: PublicKey? = null
        if (this.encrypted) {
            // encryption requires a parasite and a key
            principal = getPrincipal() ?: return generateErrorResult(rpcBody.id, JsonRpcStatus.UNAUTHORIZED)
            parasite = getParasite(principal) ?: return generateErrorResult(rpcBody.id, JsonRpcStatus.UNAUTHORIZED)
            clientKey = try {
                getClientKey() ?: return generateErrorResult(rpcBody.id, JsonRpcStatus.ENCRYPTION_ERROR, "invalid key")
            } catch (e: GeneralSecurityException) {
                null
            } ?: return generateErrorResult(rpcBody.id, JsonRpcStatus.KEY_ERROR)
        }
        val executionResult = execute(rpcBody.params, principal, parasite, clientKey, chatHandler)
        return if (executionResult.jsonRpcStatus == JsonRpcStatus.NOTIFICATION || rpcBody.id.isNullOrBlank()) {
            generateNotificationResult(executionResult.result)
        } else if (executionResult.jsonRpcStatus != JsonRpcStatus.EXCELLENT) {
            generateErrorResult(rpcBody.id, executionResult.jsonRpcStatus, executionResult.errorMessage)
        } else {
            // send the result of execution, or no data if we get here and result is null
            generateSuccessResult(rpcBody.id, executionResult.result, encrypted)
        }
    }

    override fun toString(): String {
        return this.name.replace("_", ".").lowercase()
    }

    companion object {
        fun getOrNull(method: String): MethodHandler? = MethodHandler.values().firstOrNull { t -> t.toString() == method }
    }
}
