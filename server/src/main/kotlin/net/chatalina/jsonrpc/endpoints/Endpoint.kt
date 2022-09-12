package net.chatalina.jsonrpc.endpoints


import io.ktor.server.auth.jwt.*
import net.chatalina.database.Parasite
import net.chatalina.jsonrpc.JsonRpcStatus
import net.chatalina.jsonrpc.Parameter
import net.chatalina.plugins.ChatHandler
import java.security.PublicKey


data class ValidationResult(
    val jsonRpcStatus: JsonRpcStatus,
    val errorMessage: String? = null,
    val errors: List<String>? = null
) {
    constructor(errorMessage: String) : this(JsonRpcStatus.INVALID_PARAMS, errorMessage)
    constructor(errorMessages: List<String>) : this(JsonRpcStatus.INVALID_PARAMS, errors = errorMessages)
    constructor() : this(JsonRpcStatus.EXCELLENT)

    fun getErrorData(): Any? {
        return errorMessage ?: errors
    }
}

data class ExecutionResult(
    val jsonRpcStatus: JsonRpcStatus,
    private val resultMap: Map<String, Any>?,
    private val resultList: List<Any>?,
    private val resultAny: Any?,
    val errorMessage: String?
) {
    val result: Any?
        get() {
            return resultList ?: resultMap ?: resultAny
        }

    companion object Factory {
        fun createMapResult(
            jsonRpcStatus: JsonRpcStatus,
            result: Map<String, Any>,
            errorMessage: String? = null
        ): ExecutionResult {
            return ExecutionResult(jsonRpcStatus, result, null, null, errorMessage)
        }

        fun createListResult(
            jsonRpcStatus: JsonRpcStatus,
            result: List<Any>,
            errorMessage: String? = null
        ): ExecutionResult {
            return ExecutionResult(jsonRpcStatus, null, result, null, errorMessage)
        }

        fun createResult(jsonRpcStatus: JsonRpcStatus, result: Any?, errorMessage: String? = null): ExecutionResult {
            return ExecutionResult(jsonRpcStatus, null, null, result, errorMessage)
        }
    }
}

typealias ParameterList = List<Parameter>

abstract class OpenSocketEndpoint() : Endpoint {
    override val executeInSocket = true
    override val isNotification = true
}

abstract class AuthenticatedEndpoint() : Endpoint {
    override val authenticated = true
}

abstract class EncryptedEndpoint() : AuthenticatedEndpoint() {
    override val encrypted = true
}

// all implementing classes must be in the same package as this one. this means we can't really move all of jsonrpc
// into a separate dependency until the "expect" keyword from KMM is out of beta :/
sealed interface Endpoint {
    var chatHandler: ChatHandler

    val authenticated: Boolean
        get() = false
    val encrypted: Boolean
        get() = false

    val methodName: String
    val requiredParams: ParameterList
        get() = listOf()
    val optionalParams: ParameterList
        get() = listOf()
    val executeInSocket: Boolean
        get() = false
    val isNotification: Boolean
        get() = false

    fun init(ch: ChatHandler) {
        chatHandler = ch
    }

    /**
     * Validate request parameters. Checks for:
     *  - Required parameters (existence and type) as defined in [requiredParams]
     * Validation of parameter contents is implemented per endpoint by overriding [additionalValidation]
     *
     * @param params Request params to validate
     * @return a [ValidationResult] object with the current execution status and any errors
     */
    fun validate(params: MutableMap<String, Any>?): ValidationResult {
        val paramErrors = mutableListOf<String>()

        // check for unknown parameters (all valid ones are either required or optional)
        val validParamNames = requiredParams.map { param -> param.name } + optionalParams.map { param -> param.name }
        if (params != null && params.isNotEmpty()) {
            (params.keys.filterNot { it in validParamNames }).let { badParams: List<String> ->
                if (badParams.isNotEmpty()) {
                    // stop processing RIGHT NOW, this request is just terrible.
                    return ValidationResult("Unknown parameters: ${badParams.joinToString(", ") { "'$it'" }}")
                }
            }
        }

        if (requiredParams.isNotEmpty()) {
            // if there are required params, then params are also required
            params?.let {
                requiredParams.forEach { param: Parameter ->
                    // the parameter is required, it must be present and not null
                    if (!params.containsKey(param.name)) {
                        paramErrors.add("Param '${param.name}' is required")
                    } else {
                        val paramValue = params[param.name]
                        if (paramValue == null || (paramValue is String && paramValue.isBlank())) {
                            paramErrors.add("Param '${param.name}' is required")
                        } else {
                            if (!param.type.validate(paramValue)) {
                                paramErrors.add(param.type.getErrorMessage(param.name))
                                params.remove(param.name)  // this prevents duplicate validation
                            }
                        }
                    }
                }
            }
                ?: return ValidationResult("This method requires parameters: (${requiredParams.joinToString(", ") { "${it.name} - ${it.type}" }})")
        }

        if (optionalParams.isNotEmpty()) {
            // no params? no problem
            params?.let {
                optionalParams.forEach { param: Parameter ->
                    // only validate the param if it is present
                    if (params.containsKey(param.name)) {
                        val paramValue = params[param.name]
                        // TODO: parameters that allow null
                        if (paramValue == null || !param.type.validate(paramValue)) {
                            paramErrors.add(param.type.getErrorMessage(param.name))
                        }
                    }
                }
            }
        }

        if (params !== null && params.isNotEmpty()) paramErrors.addAll(additionalValidation(params))
        return if (paramErrors.isEmpty()) ValidationResult() else ValidationResult(paramErrors)
    }

    /**
     * Override to provide additional validation beyond top-level type checking
     *
     * @param params params to validate
     * @return All error messages generated during validation
     */
    fun additionalValidation(params: Map<String, Any>): List<String> {
        return listOf()
    }

    /**
     * This is called after [validate]. If the endpoint requires any of the arguments to run, it must validate that
     * they are not null, or respond with the appropriate error.
     *
     *  @param params parameters for this request
     */
    suspend fun execute(
        params: Map<String, Any>? = mapOf(),
        principal: JWTPrincipal?,
        parasite: Parasite?,
        clientKey: PublicKey?
    ): ExecutionResult
}