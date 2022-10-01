package net.chatalina.plugins

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.exc.InvalidFormatException
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.MissingKotlinParameterException
import com.fasterxml.jackson.module.kotlin.jacksonMapperBuilder
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.*
import io.ktor.serialization.jackson.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.util.reflect.*
import io.ktor.utils.io.*
import io.ktor.utils.io.charsets.*
import java.io.IOException

// define the mapper separately so we can use it outside of the call pipeline
val Application.jacksonMapper: JsonMapper
    get() = jacksonMapperBuilder()
        .enable(SerializationFeature.WRITE_ENUMS_USING_TO_STRING)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE)
        .enable(DeserializationFeature.READ_ENUMS_USING_TO_STRING)
        .addModule(JavaTimeModule())
        .build()

class ErrorHandlingJacksonConverter(mapper: JsonMapper) : ContentConverter {
    private val jacksonConverter = JacksonConverter(mapper)
    override suspend fun deserialize(charset: Charset, typeInfo: TypeInfo, content: ByteReadChannel): Any? {
        return try {
            jacksonConverter.deserialize(charset, typeInfo, content)
        } catch (e: JsonConvertException) {
            e.printStackTrace()
            badRequestForException(e.cause ?: e)
        } catch (e: IOException) {
            e.printStackTrace()
            throw e
        } catch (e: Throwable) {
            e.printStackTrace()
            badRequestForException(e)
        }
    }

    private fun badRequestForException(e: Throwable) {
        val message = when (e) {
            is MissingKotlinParameterException -> "Missing required field: ${e.path.joinToString("->") { it.fieldName ?: "[${it.index}]" }}"
            is UnrecognizedPropertyException -> "Unknown field: ${e.propertyName}"
            is InvalidFormatException -> "invalid value for field ${e.path.joinToString("->") { it.fieldName ?: "[${it.index}]" }}"
            is MismatchedInputException -> if (e.path.isNullOrEmpty()) {
                ""
            } else {
                "Invalid value for field ${e.path.joinToString("->") { it.fieldName ?: "[${it.index}]" }}"
            }
            else -> throw e
        }
        throw BadRequestException(message)
    }

    override suspend fun serializeNullable(
        contentType: ContentType,
        charset: Charset,
        typeInfo: TypeInfo,
        value: Any?
    ): OutgoingContent {
        return jacksonConverter.serializeNullable(contentType, charset, typeInfo, value)
    }
}

fun Application.configureSerialization() {
    val converter = ErrorHandlingJacksonConverter(jacksonMapper)
    install(ContentNegotiation) {
        register(ContentType.Application.Json, converter)
    }
}

