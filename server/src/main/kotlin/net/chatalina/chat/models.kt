package net.chatalina.chat

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonValue
import net.chatalina.jsonrpc.endpoints.EncryptionKey


/*** MESSAGE STUFF ***/

enum class DestinationType() {
    PARASITE,
    GROUP
}

class JID(val type: DestinationType, val id: Int, val domain: String) {
    companion object {
        fun parseJID(destination: String): JID {
            /* allowed formats (to user or to group only):
                1@bec -> direct to a user
                bec/1 -> a whole group
               YES this is misusing the JID standard, NO i do not care. you're not my real dad
             */
            // split the string on the regex
            val parts = Regex("[@/]").split(destination)
            // now check how many parts we have. we only allow 2
            if (parts.size == 2) {
                // if 2 parts, we need to determine if its going to a user or a group
                if (destination.contains("/")) {
                    val id = parts.last().toIntOrNull() ?: throw IllegalArgumentException("Bad destination JID")
                    return JID(DestinationType.GROUP, id, parts.first())
                } else if (destination.contains("@")) {
                    val id = parts.first().toIntOrNull() ?: throw IllegalArgumentException("Bad destination JID")
                    return JID(DestinationType.PARASITE, id, parts.last())
                }
            }
            // anything else is wrong
            throw IllegalArgumentException("Bad destination JID")
        }
    }

    @JsonValue
    override fun toString(): String {
        return when (type) {
            DestinationType.PARASITE -> "${id}@${domain}"
            DestinationType.GROUP -> "${domain}/${id}"
        }
    }
}

enum class ServerMethodTypes(private val value: String) {
    ENCRYPTION_KEY(EncryptionKey.methodName),
    NEW_MESSAGE("messages.new"),
    UPDATE_DESTINATIONS("destinations.update");

    override fun toString(): String {
        return value
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ParasiteObject(
    val jid: JID,
    val displayName: String
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class GroupObject(
    val jid: String,
    val name: String,
    val parasites: List<String>
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
