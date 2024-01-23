package com.applepeacock.chat

import at.favre.lib.crypto.bcrypt.BCrypt
import com.applepeacock.database.ParasiteSettings
import com.applepeacock.database.Parasites
import com.applepeacock.database.setProperty
import com.applepeacock.plugins.*
import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonEnumDefaultValue
import com.fasterxml.jackson.module.kotlin.convertValue
import org.slf4j.event.Level
import java.util.*
import kotlin.reflect.KProperty1
import kotlin.reflect.full.declaredMemberProperties

interface MessageHandler {

    suspend fun handleMessage(connection: ChatSocketConnection, parasite: Parasites.ParasiteObject, body: MessageBody)
}

inline fun <reified T : MessageBody> onMessage(
    body: MessageBody,
    block: (T) -> Unit
) {
    block(defaultMapper.convertValue(body))
}

object UnknownMessageHandler : MessageHandler {
    override suspend fun handleMessage(
        connection: ChatSocketConnection,
        parasite: Parasites.ParasiteObject,
        body: MessageBody
    ) {
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

    override suspend fun handleMessage(
        connection: ChatSocketConnection,
        parasite: Parasites.ParasiteObject,
        body: MessageBody
    ) {
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

    override suspend fun handleMessage(
        connection: ChatSocketConnection,
        parasite: Parasites.ParasiteObject,
        body: MessageBody
    ) {
        onMessage<LogMessageBody>(body) { messageBody ->
            messageBody.log?.toString()?.let {
                val messageToLog = "(${connection.parasiteId}) $it"
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

    override suspend fun handleMessage(
        connection: ChatSocketConnection,
        parasite: Parasites.ParasiteObject,
        body: MessageBody
    ) {
        onMessage<StatusMessageBody>(body) { messageBody ->
            val newStatus = ParasiteStatus.fromString(messageBody.status?.toString())
            ChatManager.updateParasiteStatus(connection.parasiteId, newStatus)
        }
    }
}

object SettingsMessageHandler : MessageHandler {
    class SettingsMessageBody(type: MessageTypes) : MessageBody(type) {
        val data by fromOther("data")
    }

    data class SettingsData(
        val email: String? = null,
        val password: Map<String, String?> = emptyMap(),
        val username: String? = null,
        @JsonAnyGetter
        @JsonAnySetter
        val other: MutableMap<String, String?> = mutableMapOf()
    )

    override suspend fun handleMessage(
        connection: ChatSocketConnection,
        parasite: Parasites.ParasiteObject,
        body: MessageBody
    ) {
        onMessage<SettingsMessageBody>(body) { messageBody ->
            val alerts: MutableList<ServerMessage> = mutableListOf()
            val broadcastAlerts: MutableList<ServerMessage> = mutableListOf()
            val thingsToUpdate = messageBody.data?.let { defaultMapper.convertValue<SettingsData>(it) } ?: return
            // if setting password, check if valid, then update password and send alerts. no broadcast.
            val passwordBody = defaultMapper.convertValue<Map<String, String?>>(thingsToUpdate.password)
            if (!passwordBody["password"].isNullOrBlank()) {
                if (passwordBody["password"] != passwordBody["password2"]) {
                    connection.send(
                        ServerMessage(
                            ServerMessageTypes.Alert,
                            mapOf("message" to "Password entries did not match.")
                        )
                    )
                } else {
                    val hashed = BCrypt.with(BCrypt.Version.VERSION_2B).hash(
                        12,
                        passwordBody["password"]!!.toByteArray()
                    )
                    val success = Parasites.DAO.updatePassword(parasite.id, hashed)
                    alerts.add(
                        ServerMessage(
                            ServerMessageTypes.Alert,
                            mapOf("message" to "Password ${if (!success) "not " else ""}changed.")
                        )
                    )
                }
            }

            var shouldBroadcastChange = false
            val updates: MutableMap<String, Any> = mutableMapOf()
            // if setting email, update email and send alerts. no broadcast
            var newEmail = thingsToUpdate.email
            if (newEmail.isNullOrBlank() || parasite.email == newEmail) {
                newEmail = null
            } else {
                alerts.add(ServerMessage(ServerMessageTypes.Alert, mapOf("message" to "Email updated to ${newEmail}.")))
            }

            // if setting username, check if valid, then update and send alerts. broadcast change to all others
            val newSettings = parasite.settings.copy()
            val newUsername = thingsToUpdate.username
            if (!newUsername.isNullOrBlank()) {
                // if new is different than current, and new is this users id OR a valid username
                if (newUsername != parasite.settings.displayName) {
                    if (newUsername == parasite.id.value || Parasites.DAO.isValidUsername(newUsername)) {
                        newSettings.displayName = newUsername
                        shouldBroadcastChange = true
                        updates["username"] = newUsername
                        alerts.add(
                            ServerMessage(
                                ServerMessageTypes.Alert,
                                mapOf("message" to "Username changed from ${parasite.name} to ${newUsername}")
                            )
                        )
                        broadcastAlerts.add(
                            ServerMessage(
                                ServerMessageTypes.Alert,
                                mapOf("message" to "${parasite.name} is now ${newUsername}")
                            )
                        )
                    } else {
                        alerts.add(
                            ServerMessage(
                                ServerMessageTypes.Alert,
                                mapOf("message" to "${newUsername} is not a valid username.")
                            )
                        )
                    }
                }
            }
            // for all other items, make an updated settings object and save it. send alerts for changes. faction is a broadcast, too.
            ParasiteSettings::class.declaredMemberProperties.map { prop: KProperty1<ParasiteSettings, *> ->
                thingsToUpdate.other.get(prop.name)?.let {
                    val currentVal = prop.get(parasite.settings)
                    if (it != currentVal) {
                        newSettings.setProperty(prop, it)
                        updates[prop.name] = it
                        alerts.add(
                            ServerMessage(
                                ServerMessageTypes.Alert,
                                mapOf("message" to "${prop.name} changed from ${currentVal} to ${it}")
                            )
                        )
                        if (prop.name == ParasiteSettings::faction.name) {
                            shouldBroadcastChange = true
                        }
                    }
                }
            }
            Parasites.DAO.update(parasite, newEmail, newSettings)

            // if changes should be broadcast, do it now
            if (shouldBroadcastChange) {
                ChatManager.broadcastToOthers(parasite.id.value, ChatManager.buildParasiteList())
            }
            broadcastAlerts.forEach { ChatManager.broadcastToOthers(parasite.id.value, it) }

            // broadcast "update" to self connections
            if (!updates.isEmpty()) {
                // TODO: need to update parasite object on connection. is there any benefit to it being there?
                ChatManager.broadcastToParasite(parasite.id.value, ServerMessage(ServerMessageTypes.Update, updates))
            }
            alerts.forEach { connection.send(it) }
        }
    }
}

object ChatMessageHandler : MessageHandler {
    class ChatMessageBody(type: MessageTypes) : MessageBody(type) {
        val message by fromOther("message")
        val roomId by fromOther("room id")
    }

    override suspend fun handleMessage(
        connection: ChatSocketConnection,
        parasite: Parasites.ParasiteObject,
        body: MessageBody
    ) {
        onMessage<ChatMessageBody>(body) { messageBody ->
            val destinationRoom = UUID.fromString(messageBody.roomId?.toString())
            val messageContent = messageBody.message?.toString()
            if (messageContent.isNullOrBlank()) {
                connection.logger.error("Bad message content")
            } else {
                ChatManager.handleChatMessage(destinationRoom, connection.parasiteId, messageContent)
            }
        }
    }
}
object PrivateMessageHandler : MessageHandler {
    class PrivateMessageBody(type: MessageTypes) : MessageBody(type) {
        val message by fromOther("message")
        val recipientId by fromOther("recipient id")
    }

    override suspend fun handleMessage(
        connection: ChatSocketConnection,
        parasite: Parasites.ParasiteObject,
        body: MessageBody
    ) {
        onMessage<PrivateMessageBody>(body) { messageBody ->
            val destinationParasite = messageBody.recipientId?.toString()
            val messageContent = messageBody.message?.toString()
            if (messageContent.isNullOrBlank() || destinationParasite.isNullOrBlank()) {
                connection.logger.error("Bad message content")
            } else {
                ChatManager.handlePrivateMessage(destinationParasite, connection.parasiteId, messageContent)
            }
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
    Settings("settings") {
        override val handler = SettingsMessageHandler
    },

    ChatMessage("chat message") {
        override val handler = ChatMessageHandler
    },

    PrivateMessage("private message") {
        override val handler = PrivateMessageHandler
    },

    //    Image("image"),
//    ImageUpload("image upload"),
//    RoomAction("room action"),
// Typing("typing"),
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
