package net.chatalina.jsonrpc

import io.ktor.http.*


enum class JsonRpcStatus(val rpcCode: Int, val rpcMessage: String?, val statusCode: HttpStatusCode) {
    EXCELLENT(1, null, HttpStatusCode.OK),
    NOTIFICATION(0, null, HttpStatusCode.Accepted),
    PARSE_ERROR(-32700, "Parse Error", HttpStatusCode.BadRequest),
    INVALID_REQUEST(-32600, "Invalid Request", HttpStatusCode.UnprocessableEntity),
    METHOD_NOT_FOUND(-32601, "Method Not Found", HttpStatusCode.UnprocessableEntity),
    INVALID_PARAMS(-32602, "Invalid Params", HttpStatusCode.UnprocessableEntity),
    SERVER_ERROR(-32500, "Server Error", HttpStatusCode.InternalServerError),
    KEY_ERROR(-32003, "Key Exchange Needed", HttpStatusCode.UnprocessableEntity),
    ENCRYPTION_ERROR(-32004, "Encryption Error", HttpStatusCode.UnprocessableEntity),
    UNAUTHORIZED(-32001, "Unauthorized", HttpStatusCode.Unauthorized),
    FORBIDDEN(-32002, "Forbidden", HttpStatusCode.Forbidden),
    REFUSED(-32005, "Refused", HttpStatusCode.UnprocessableEntity)
}

data class JsonRpcResult(
    val statusCode: HttpStatusCode = HttpStatusCode.OK,
    val response: Response?,
    val passAlongResult: Any? = null
)

fun generateNotificationResult(result: Any?): JsonRpcResult {
    return JsonRpcResult(response = null, passAlongResult = result)
}

fun generateSuccessResult(id: String?, result: Any?, encrypted: Boolean = false): JsonRpcResult {
    return JsonRpcResult(HttpStatusCode.OK, SuccessResponse(id, result, encrypted))
}

fun generateErrorResult(id: String?, status: JsonRpcStatus, errorDetails: Any? = null): JsonRpcResult {
    return JsonRpcResult(status.statusCode, generateErrorResponse(id, status, errorDetails))
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
