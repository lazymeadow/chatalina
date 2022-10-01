package net.chatalina.jsonrpc

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty


enum class RequestSource { HTTP, SOCKET }

data class JsonRpcCallBody(
    @JsonProperty("jsonrpc") val version: String,
    @JsonInclude(JsonInclude.Include.NON_NULL) val id: String?,
    val method: String,
    val params: MutableMap<String, Any>?
)
