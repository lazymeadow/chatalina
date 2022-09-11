package net.chatalina.chat

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import io.ktor.server.plugins.*
import java.time.Instant
import java.util.*


/*** MESSAGE STUFF ***/


enum class MessageTypes(private val value: String) {
    AUTHORIZATION("authorization"),
    KEY_EXCHANGE("keyExchange"),
    SEND_MESSAGE("sendMessage"),
    NEW_MESSAGE("newMessage"),

    @JsonEnumDefaultValue
    UNKNOWN("unknown");

    override fun toString(): String {
        return value
    }

    companion object {
        fun validValues(): List<String> {
            return values().filter { it != UNKNOWN }.map { it.value }
        }

        fun valueOrNull(string: String): MessageTypes? {
            return values().find { it.value == string }
        }
    }
}

data class ResponseBody(
    val id: UUID,
    val type: MessageTypes,
    val content: MessageContent,
    val time: Instant
)

data class RequestBody(
    val id: String?,
    val type: MessageTypes,
    @JsonProperty("content") val contentMap: Map<String, String>
) {
    // this will validate the content when we try to access it
    val messageContent: RequestContent
        get() = when (type) {
            MessageTypes.AUTHORIZATION -> contentMap["token"]?.let { AuthContent(it) }
                ?: throw BadRequestException("token missing from authorization")

            MessageTypes.KEY_EXCHANGE -> contentMap["key"]?.let { KeyExchangeContent(it) } ?: throw BadRequestException(
                "key missing from key exchange"
            )

            MessageTypes.SEND_MESSAGE -> {
                val iv = contentMap["iv"] ?: throw BadRequestException("iv missing from encrypted message")
                val messageContent =
                    contentMap["content"] ?: throw BadRequestException("content missing from encrypted message")
                if (messageContent.isBlank()) throw BadRequestException("content missing from encrypted message")
                MessageContent(iv, messageContent)
            }

            else -> throw BadRequestException("invalid message type for request")
        }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class MessageContent(
    override val iv: String,
    override val content: String
) : RequestContent(iv = iv, content = content)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class AuthContent(
    override val token: String
) : RequestContent(token = token)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class KeyExchangeContent(
    override val key: String
) : RequestContent(key = key)

abstract class RequestContent(
    open val iv: String? = null,
    open val content: String? = null,
    open val token: String? = null,
    open val key: String? = null
)
