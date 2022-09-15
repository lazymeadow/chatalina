package net.chatalina.chat

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue
import com.fasterxml.jackson.annotation.JsonInclude
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

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ParasiteObject(
    val jid: String,
    val displayName: String
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class GroupObject(
    val jid: String,
    val name: String,
    val parasites: List<String>
)

data class ResponseBody(
    val id: UUID,
    val type: MessageTypes,
    val content: MessageContent,
    val time: Instant
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class MessageContent(
    override val iv: String,
    override val content: String
) : RequestContent(iv = iv, content = content)

abstract class RequestContent(
    open val iv: String? = null,
    open val content: String? = null,
    open val token: String? = null,
    open val key: String? = null
)
