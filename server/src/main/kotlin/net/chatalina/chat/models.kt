package net.chatalina.chat

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue
import com.fasterxml.jackson.annotation.JsonInclude
import net.chatalina.jsonrpc.endpoints.KeyExchange
import java.time.Instant
import java.util.*


/*** MESSAGE STUFF ***/


enum class ServerMethodTypes(private val value: String) {
    KEY_EXCHANGE(KeyExchange.methodName),
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

        fun valueOrNull(string: String): ServerMethodTypes? {
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

data class MessageResult(
    val id: UUID,
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
