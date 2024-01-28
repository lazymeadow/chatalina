package com.applepeacock.chat

import at.favre.lib.crypto.bcrypt.BCrypt
import com.applepeacock.database.*
import com.applepeacock.plugins.CLIENT_VERSION
import com.applepeacock.plugins.ChatSocketConnection
import com.applepeacock.plugins.defaultMapper
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


object TypingMessageHandler : MessageHandler {
    class TypingMessageBody(type: MessageTypes) : MessageBody(type) {
        val currentDestination by fromOther("status")
    }

    override suspend fun handleMessage(
        connection: ChatSocketConnection,
        parasite: Parasites.ParasiteObject,
        body: MessageBody
    ) {
        onMessage<TypingMessageBody>(body) { messageBody ->
            ChatManager.updateParasiteTypingStatus(connection.parasiteId, messageBody.currentDestination?.toString())
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
                ChatManager.handleChatMessage(destinationRoom, parasite, messageContent)
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
                ChatManager.handlePrivateMessage(destinationParasite, parasite, messageContent)
            }
        }
    }
}

object ImageMessageHandler : MessageHandler {
    class ImageMessageBody(type: MessageTypes) : MessageBody(type) {
        val url by fromOther("image url")
        val destination by fromOther("room id")
        val nsfw by fromOther("nsfw")
    }

    override suspend fun handleMessage(
        connection: ChatSocketConnection,
        parasite: Parasites.ParasiteObject,
        body: MessageBody
    ) {
        onMessage<ImageMessageBody>(body) { messageBody ->
            if (messageBody.destination?.toString().isNullOrBlank() || messageBody.url?.toString().isNullOrBlank()) {
                connection.logger.error("Bad message content")
            } else {
                messageBody.destination?.toString()?.let {
                    try {
                        UUID.fromString(it)
                        MessageDestination(it, MessageDestinationTypes.Room)
                    } catch (e: IllegalArgumentException) {
                        MessageDestination(it, MessageDestinationTypes.Parasite)
                    }
                }?.let {
                    try {
                        ChatManager.handleImageMessage(
                            it,
                            parasite,
                            messageBody.url.toString(),
                            messageBody.nsfw.toString().toBoolean()
                        )
                    } catch (e: Throwable) {
                        e.printStackTrace()
                        connection.send(
                            ServerMessage(
                                ServerMessageTypes.Alert,
                                AlertData("dismiss", "Failed to send image.", "dismiss")
                            )
                        )
                    }
                } ?: let {
                    connection.logger.error("Bad message content")
                }
            }
        }
    }
}

object ImageUploadMessageHandler : MessageHandler {
    class ImageUploadMessageBody(type: MessageTypes) : MessageBody(type) {
        val imageData by fromOther("image data")
        val imageType by fromOther("image type")
        val destination by fromOther("room id")
        val nsfw by fromOther("nsfw")
    }

    override suspend fun handleMessage(
        connection: ChatSocketConnection,
        parasite: Parasites.ParasiteObject,
        body: MessageBody
    ) {
        onMessage<ImageUploadMessageBody>(body) { messageBody ->
            if (messageBody.destination?.toString().isNullOrBlank() || messageBody.imageData?.toString()
                    .isNullOrBlank() || messageBody.imageType?.toString().isNullOrBlank()
            ) {
                connection.logger.error("Bad message content")
            } else {
                messageBody.destination?.toString()?.let {
                    try {
                        UUID.fromString(it)
                        MessageDestination(it, MessageDestinationTypes.Room)
                    } catch (e: IllegalArgumentException) {
                        MessageDestination(it, MessageDestinationTypes.Parasite)
                    }
                }?.let {
                    try {
                        ChatManager.handleImageUploadMessage(
                            it,
                            parasite,
                            messageBody.imageData.toString(),
                            messageBody.imageType.toString(),
                            messageBody.nsfw.toString().toBoolean()
                        )
                    } catch (e: Throwable) {
                        e.printStackTrace()
                        connection.send(
                            ServerMessage(
                                ServerMessageTypes.Alert,
                                AlertData("dismiss", "Failed to upload image.", "dismiss")
                            )
                        )
                    }
                } ?: let {
                    connection.logger.error("Bad message content")
                }
            }
        }
    }
}

object GithubIssueMessageHandler : MessageHandler {
    class GithubIssueMessageBody(type: MessageTypes) : MessageBody(type) {
        val title by fromOther("title")
        val body by fromOther("body")
    }

    override suspend fun handleMessage(
        connection: ChatSocketConnection,
        parasite: Parasites.ParasiteObject,
        body: MessageBody
    ) {
        onMessage<GithubIssueMessageBody>(body) { messageBody ->
            if (messageBody.title?.toString().isNullOrBlank() || messageBody.body?.toString().isNullOrBlank()) {
                connection.logger.error("Bad message content")
            } else {
                ChatManager.handleGithubIssueMessage(
                    connection,
                    messageBody.type.toString(),
                    messageBody.title.toString(),
                    messageBody.body.toString()
                )
            }
        }
    }
}


object ToolListMessageHandler : MessageHandler {
    class ToolListMessageBody(type: MessageTypes) : MessageBody(type) {
        val permissionLevel by fromOther("tool set")
    }

    override suspend fun handleMessage(
        connection: ChatSocketConnection,
        parasite: Parasites.ParasiteObject,
        body: MessageBody
    ) {
        onMessage<ToolListMessageBody>(body) { messageBody ->
            if (messageBody.permissionLevel?.toString().isNullOrBlank()) {
                connection.logger.error("Bad message content")
            } else {
                enumValues<ParasitePermissions>().find { it.toString() == messageBody.permissionLevel.toString() }?.let { accessLevel ->
                    ChatManager.handleToolListRequest(connection, parasite, accessLevel)
                }
            }
        }
    }
}
object ToolDataMessageHandler : MessageHandler {
    class ToolDataMessageBody(type: MessageTypes) : MessageBody(type) {
        val toolId by fromOther("data type")
    }

    override suspend fun handleMessage(
        connection: ChatSocketConnection,
        parasite: Parasites.ParasiteObject,
        body: MessageBody
    ) {
        onMessage<ToolDataMessageBody>(body) { messageBody ->
            if (messageBody.toolId?.toString().isNullOrBlank()) {
                connection.logger.error("Bad message content")
            } else {
                ChatManager.handleToolDataRequest(connection, parasite, messageBody.toolId.toString())
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
    Typing("typing") {
        override val handler = TypingMessageHandler
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
    Image("image") {
        override val handler = ImageMessageHandler
    },
    ImageUpload("image upload") {
        override val handler = ImageUploadMessageHandler
    },

    //    RoomAction("room action"),
//    RemoveAlert("remove alert"),
    Bug("bug") {
        override val handler = GithubIssueMessageHandler
    },
    Feature("feature") {
        override val handler = GithubIssueMessageHandler
    },
    ToolList("tool list") {
        override val handler = ToolListMessageHandler
    },
    ToolData("data request") {
        override val handler = ToolDataMessageHandler
                                },
//    AdminRequest("admin request"),

    @JsonEnumDefaultValue Unknown("unknown") {
        override val handler = UnknownMessageHandler
    };

    abstract val handler: MessageHandler

    override fun toString(): String {
        return this.value
    }
}
