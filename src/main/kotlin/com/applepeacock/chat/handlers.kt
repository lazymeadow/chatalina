package com.applepeacock.chat

import com.applepeacock.plugins.*
import com.fasterxml.jackson.annotation.JsonEnumDefaultValue
import com.fasterxml.jackson.module.kotlin.convertValue
import org.slf4j.event.Level

interface MessageHandler {

    suspend fun handleMessage(connection: ChatSocketConnection, body: MessageBody)
}

inline fun <reified T : MessageBody> onMessage(
    body: MessageBody,
    block: (T) -> Unit
) {
    block(defaultMapper.convertValue(body))
}

object UnknownMessageHandler : MessageHandler {
    override suspend fun handleMessage(connection: ChatSocketConnection, body: MessageBody) {
        onMessage<MessageBody>(body) {
            connection.logger.error("Received unknown message")
        }
    }
}

object VersionMessageHandler : MessageHandler {
    class VersionMessageBody(
        type: MessageTypes
    ) : MessageBody(type) {
        val clientVersion by fromOther("client version")
    }

    override suspend fun handleMessage(connection: ChatSocketConnection, body: MessageBody) {
        onMessage<VersionMessageBody>(body) { messageBody ->
            val message = if (messageBody.clientVersion.toString() < CLIENT_VERSION) {
                "Your client is out of date. You'd better refresh your page!"
            } else if (messageBody.clientVersion.toString() > CLIENT_VERSION) {
                "How did you mess up a perfectly good client version number?"
            } else {
                null
            }
            message?.let {
                connection.send(
                    ServerMessage(ServerMessageTypes.Alert, mapOf("message" to message, "type" to "permanent"))
                )
            }
        }
    }
}

object ClientLogMessageHandler : MessageHandler {
    class LogMessageBody(
        type: MessageTypes
    ) : MessageBody(type) {
        val level by fromOther("level")
        val log by fromOther("log")
    }

    override suspend fun handleMessage(connection: ChatSocketConnection, body: MessageBody) {
        onMessage<LogMessageBody>(body) { messageBody ->
            messageBody.log?.toString()?.let {
                val messageToLog = "(${connection.parasite.id}) $it"
                when (messageBody.level?.toString()?.lowercase()) {
                    Level.INFO.toString().lowercase() -> connection.logger.info(messageToLog)
                    Level.ERROR.toString().lowercase() -> connection.logger.error(messageToLog)
                    Level.DEBUG.toString().lowercase() -> connection.logger.debug(messageToLog)
                    Level.TRACE.toString().lowercase() -> connection.logger.trace(messageToLog)
                    Level.WARN.toString().lowercase() -> connection.logger.warn(messageToLog)
                }
            }
        }
    }
}

object StatusMessageHandler : MessageHandler {
    class StatusMessageBody(type: MessageTypes) : MessageBody(type) {
        val status by fromOther("status")
    }

    override suspend fun handleMessage(connection: ChatSocketConnection, body: MessageBody) {
        onMessage<StatusMessageBody>(body) { messageBody ->
            val newStatus = ParasiteStatus.fromString(messageBody.status?.toString())
            ChatManager.updateParasiteStatus(connection.parasite.id, newStatus)
        }
    }
}

@Suppress("unused")  // they are used actually
enum class MessageTypes(val value: String) {
    Version("version") {
        override val handler = VersionMessageHandler
    },
    ClientLog("client log") {
        override val handler = ClientLogMessageHandler
    },
    Status("status") {
        override val handler = StatusMessageHandler
    },

    //    ChatMessage("chat message"),
//    PrivateMessage("private message"),
//    Image("image"),
//    ImageUpload("image upload"),
//    RoomAction("room action"),
// Typing("typing"),
    //    Settings("settings"),
//    RemoveAlert("remove alert"),
//    Bug("bug"),
//    Feature("feature"),
//    ToolList("tool list"),
//    DataRequest("data request"),
//    AdminRequest("admin request"),
    @JsonEnumDefaultValue Unknown("unknown") {
        override val handler = UnknownMessageHandler
    };

    abstract val handler: MessageHandler

    override fun toString(): String {
        return this.value
    }
}
