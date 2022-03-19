package net.chatalina.plugins

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.jacksonMapperBuilder
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.jackson.*

val Application.jacksonMapper: JsonMapper
    get() = jacksonMapperBuilder()
        .enable(SerializationFeature.WRITE_ENUMS_USING_TO_STRING)
        .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE)
        .enable(DeserializationFeature.READ_ENUMS_USING_TO_STRING)
        .build()

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        val converter = JacksonConverter(jacksonMapper)
        register(ContentType.Application.Json, converter)
    }
}
