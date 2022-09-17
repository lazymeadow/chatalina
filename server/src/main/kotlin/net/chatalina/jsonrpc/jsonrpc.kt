package net.chatalina.jsonrpc


import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.config.*
import io.ktor.util.*
import io.ktor.utils.io.errors.*
import net.chatalina.database.Parasite
import net.chatalina.jsonrpc.endpoints.Endpoint
import net.chatalina.plugins.chatHandler
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.Logger
import java.security.PublicKey
import java.util.*


// Requests
data class Request(
    val jsonrpc: String?,
    @JsonInclude(JsonInclude.Include.NON_NULL) val id: String?,
    val method: String?,
    val params: Map<String, Any>?
)


// Responses
@JsonInclude(JsonInclude.Include.NON_NULL)
abstract class Response(
    open val id: String?,
    val jsonrpc: String = "2.0",
    @JsonIgnore open val isEncryptedEndpoint: Boolean = false
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SuccessResponse(
    override val id: String?,
    val result: Any?,
    @JsonIgnore override val isEncryptedEndpoint: Boolean = false
) : Response(id, isEncryptedEndpoint = isEncryptedEndpoint)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class JsonRpcError(val code: Int, val message: String, @get:JsonProperty("data") var errorDetails: Any? = null)

enum class JsonRpcStatus(val rpcCode: Int, val rpcMessage: String?, val statusCode: HttpStatusCode) {
    EXCELLENT(1, null, HttpStatusCode.OK),
    NOTIFICATION(0, null, HttpStatusCode.OK),
    PARSE_ERROR(-32700, "Parse Error", HttpStatusCode.BadRequest),
    INVALID_REQUEST(-32600, "Invalid Request", HttpStatusCode.BadRequest),
    METHOD_NOT_FOUND(-32601, "Method Not Found", HttpStatusCode.BadRequest),
    INVALID_PARAMS(-32602, "Invalid Params", HttpStatusCode.BadRequest),
    SERVER_ERROR(-32500, "Server Error", HttpStatusCode.InternalServerError),
    KEY_ERROR(-32003, "Key Exchange Needed", HttpStatusCode.BadRequest),
    ENCRYPTION_ERROR(-32004, "Encryption Error", HttpStatusCode.BadRequest),
    DESTINATION_ERROR(-32005, "Destination Error", HttpStatusCode.BadRequest),
    UNAUTHORIZED(-32001, "Unauthorized", HttpStatusCode.Unauthorized),
    FORBIDDEN(-32002, "Forbidden", HttpStatusCode.Forbidden)
}

fun generateErrorResponse(id: String?, status: JsonRpcStatus, errorDetails: Any? = null): ErrorResponse {
    return ErrorResponse(id, JsonRpcError(status.rpcCode, status.rpcMessage ?: "", errorDetails))
}


data class ErrorResponse(override val id: String?, val error: JsonRpcError) : Response(id)


// Results
data class Result(
    val statusCode: HttpStatusCode = HttpStatusCode.OK,
    val response: Response?,
    val passAlongResult: Any? = null
)

fun generateNotificationResult(result: Any?): Result {
    return Result(response = null, passAlongResult = result)
}

fun generateSuccessResult(id: String?, result: Any?, encrypted: Boolean = false): Result {
    return Result(HttpStatusCode.OK, SuccessResponse(id, result, encrypted))
}

fun generateErrorResult(id: String?, status: JsonRpcStatus, errorDetails: Any? = null): Result {
    return Result(status.statusCode, generateErrorResponse(id, status, errorDetails))
}


// Ktor plugin definition
class JsonRpc(configuration: Configuration, private val logger: Logger) {
    private val availableEndpoints = configuration.endpoints

    class Configuration {
        var endpoints: Map<String, Endpoint> = mapOf()
    }

    suspend fun handleRequest(
        getBody: suspend () -> Request,
        getPrincipal: () -> JWTPrincipal?,
        getClientKey: () -> PublicKey?,
        executingInSocket: Boolean = false
    ): Result {
        val rpcBody: Request = try {
            getBody()
        } catch (e: IOException) {
            return generateErrorResult(null, JsonRpcStatus.PARSE_ERROR)
        }

        // json rpc must be "2.0", we don't support anything else
        rpcBody.jsonrpc ?: return generateErrorResult(
            rpcBody.id,
            JsonRpcStatus.INVALID_REQUEST,
            "field 'jsonrpc' is required"
        )
        if (rpcBody.jsonrpc != "2.0") return generateErrorResult(
            rpcBody.id,
            JsonRpcStatus.INVALID_REQUEST,
            "only jsonrpc 2.0 is supported"
        )

        // method is required
        rpcBody.method ?: return generateErrorResult(
            rpcBody.id,
            JsonRpcStatus.INVALID_REQUEST,
            "field 'method' is required"
        )
        // method must be recognized
        if (!availableEndpoints.containsKey(rpcBody.method)) return generateErrorResult(
            rpcBody.id,
            JsonRpcStatus.METHOD_NOT_FOUND,
            "method '${rpcBody.method}' not recognized"
        )

        val endpoint: Endpoint = availableEndpoints[rpcBody.method] ?: run {
            logger.error("Method '${rpcBody.method}' returned null from endpoints map, investigation required.")
            return generateErrorResult(
                rpcBody.id,
                JsonRpcStatus.SERVER_ERROR,
                "error when executing method '${rpcBody.method}'"
            )
        }

        if (executingInSocket && !endpoint.executeInSocket) {
            return generateErrorResult(
                rpcBody.id,
                JsonRpcStatus.METHOD_NOT_FOUND,
                "method '${rpcBody.method}' not recognized"
            )
        }

        if (!endpoint.isNotification && rpcBody.id.isNullOrBlank()) {
            return generateErrorResult(
                rpcBody.id,
                JsonRpcStatus.INVALID_REQUEST,
                "field 'id' is required"
            )
        }

        try {
            var principal: JWTPrincipal? = null
            var parasite: Parasite? = null
            var clientKey: PublicKey? = null
            if (endpoint.authenticated) {
                principal = getPrincipal() ?: return generateErrorResult(rpcBody.id, JsonRpcStatus.UNAUTHORIZED)
                // we know this is a valid parasite, based on their token.
                val parasiteId = try {
                    UUID.fromString(principal.subject)
                } catch (e: IllegalArgumentException) {
                    return generateErrorResult(rpcBody.id, JsonRpcStatus.UNAUTHORIZED)
                }
                parasite = transaction {
                    // find or add the parasite. no need to pass it on (yet)
                    Parasite.findById(parasiteId) ?: Parasite.new(parasiteId) {
                        this.displayName = principal.get("preferred_username") ?: ""
                    }
                }
            }
            if (endpoint.encrypted) {
                clientKey = getClientKey() ?: return generateErrorResult(rpcBody.id, JsonRpcStatus.KEY_ERROR)
            }

            // call endpoint class's validate function for request parameters
            val requestParams: MutableMap<String, Any>? =
                if (rpcBody.params != null) mutableMapOf(*rpcBody.params.toList().toTypedArray()) else null
            val validationResult = endpoint.validate(requestParams)
            if (validationResult.jsonRpcStatus != JsonRpcStatus.EXCELLENT) {
                return generateErrorResult(rpcBody.id, validationResult.jsonRpcStatus, validationResult.getErrorData())
            }
            // call endpoint class's execute function with validated parameters
            val executionResult = endpoint.execute(requestParams, principal, parasite, clientKey)

            return if (executionResult.jsonRpcStatus == JsonRpcStatus.NOTIFICATION) {
                generateNotificationResult(executionResult.result)
            } else if (executionResult.jsonRpcStatus != JsonRpcStatus.EXCELLENT) {
                generateErrorResult(rpcBody.id, executionResult.jsonRpcStatus, executionResult.errorMessage)
            } else {
                // send the result of execution, or no data if we get here and result is null
                generateSuccessResult(rpcBody.id, executionResult.result, endpoint.encrypted)
            }
        } catch (e: NotImplementedError) {
            return generateErrorResult(
                rpcBody.id,
                JsonRpcStatus.SERVER_ERROR,
                "method '${rpcBody.method}' has not yet been implemented."
            )
        } catch (e: Throwable) {
            logger.error("Error processing request")
            e.printStackTrace()
            return generateErrorResult(rpcBody.id, JsonRpcStatus.SERVER_ERROR)
        }
    }

    companion object Feature : BaseApplicationPlugin<Application, Configuration, JsonRpc> {
        override val key = AttributeKey<JsonRpc>("JsonRPC")

        override fun install(pipeline: Application, configure: Configuration.() -> Unit): JsonRpc {
            val chatHandler = pipeline.chatHandler
            pipeline.log.debug("Reticulating JSON-RPC Endpoints...")
            // skip all the abstract classes, they're important but also irrelevant.
            val endpoints: Map<String, Endpoint> = Endpoint::class.sealedSubclasses.filter { !it.isAbstract }
                .mapNotNull {
                    it.objectInstance?.init(chatHandler)
                        ?: pipeline.log.debug("Skipping: ${it.simpleName}, it hasn't been declared as an object")
                    it.objectInstance
                }.associateBy {
                    pipeline.log.debug("Associating method '${it.methodName}' with class ${it::class.simpleName}")
                    it.methodName
                }
            if (endpoints.isNotEmpty()) {
                pipeline.log.debug("Reticulation complete")
            } else {
                pipeline.log.error("Reticulation failed! Did you forget to include Endpoint as an implemented interface?")
                throw ApplicationConfigurationException("Unable to configure JSON-RPC")
            }

            val configuration = Configuration().apply(configure)
            configuration.endpoints = endpoints
            return JsonRpc(configuration, pipeline.log)
        }
    }
}
