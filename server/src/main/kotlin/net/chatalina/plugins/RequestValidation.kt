package net.chatalina.plugins

import io.ktor.server.application.*
import io.ktor.server.plugins.requestvalidation.*
import net.chatalina.jsonrpc.JsonRpcCallBody

fun Application.configureValidation() {
    install(RequestValidation) {
        validate<JsonRpcCallBody> { rpcBody ->
            val reasons = mutableListOf<String>()
            rpcBody.version.let {
                if (it != "2.0") reasons.add("only jsonrpc 2.0 is supported")
            }

            if (reasons.size > 0) {
                ValidationResult.Invalid(reasons)
            } else {
                ValidationResult.Valid
            }
        }
    }
}
