package net.chatalina.jsonrpc

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

@JsonInclude(JsonInclude.Include.NON_NULL)
abstract class Response(
    open val id: String?,
    @JsonProperty("jsonrpc") val version: String = "2.0",
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

fun generateErrorResponse(id: String?, status: JsonRpcStatus, errorDetails: Any? = null): ErrorResponse {
    return ErrorResponse(id, JsonRpcError(status.rpcCode, status.rpcMessage ?: "", errorDetails))
}

data class ErrorResponse(override val id: String?, val error: JsonRpcError) : Response(id)
