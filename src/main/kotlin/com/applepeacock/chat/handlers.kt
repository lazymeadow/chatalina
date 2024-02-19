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
import io.ktor.server.plugins.*
import io.ktor.server.websocket.*
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
        val clientVersion: String? by other("client version")
    }

    override suspend fun handleMessage(
        connection: ChatSocketConnection,
        parasite: Parasites.ParasiteObject,
        body: MessageBody
    ) {
        onMessage<VersionMessageBody>(body) { messageBody ->
            val message =
                if (messageBody.clientVersion.isNullOrBlank() || messageBody.clientVersion.toString() > CLIENT_VERSION) {
                    "How did you mess up a perfectly good client version number?"
                } else if (messageBody.clientVersion.toString() < CLIENT_VERSION) {
                    "Your client is out of date. You'd better refresh your page!"
                } else {
                    null
                }
            message?.let {
                connection.send(ServerMessage(AlertData.permanent(message)))
            }
        }
    }
}

object ClientLogMessageHandler : MessageHandler {
    class LogMessageBody(
        type: MessageTypes
    ) : MessageBody(type) {
        val level: String? by other("level")
        val log: String? by other("log")
    }

    override suspend fun handleMessage(
        connection: ChatSocketConnection,
        parasite: Parasites.ParasiteObject,
        body: MessageBody
    ) {
        onMessage<LogMessageBody>(body) { messageBody ->
            messageBody.log?.let {
                val messageToLog = "(${connection.parasiteId}) $it"
                when (messageBody.level?.lowercase()) {
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
        val status: ParasiteStatus? by otherEnum<ParasiteStatus, StatusMessageBody>("status")
    }

    override suspend fun handleMessage(
        connection: ChatSocketConnection,
        parasite: Parasites.ParasiteObject,
        body: MessageBody
    ) {
        onMessage<StatusMessageBody>(body) { messageBody ->
            val newStatus = ParasiteStatus.fromString(messageBody.status?.toString())
            ChatManager.updateParasiteStatus(parasite.id, newStatus)
        }
    }
}


object TypingMessageHandler : MessageHandler {
    class TypingMessageBody(type: MessageTypes) : MessageBody(type) {
        val currentDestination: String? by other("status")
    }

    override suspend fun handleMessage(
        connection: ChatSocketConnection,
        parasite: Parasites.ParasiteObject,
        body: MessageBody
    ) {
        onMessage<TypingMessageBody>(body) { messageBody ->
            ChatManager.updateParasiteTypingStatus(parasite.id, messageBody.currentDestination)
        }
    }
}

object SettingsMessageHandler : MessageHandler {
    class SettingsMessageBody(type: MessageTypes) : MessageBody(type) {
        val data: SettingsData? by other("data")
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
            val thingsToUpdate = messageBody.data ?: return
            // if setting password, check if valid, then update password and send alerts. no broadcast.
            val passwordBody = thingsToUpdate.password
            if (!passwordBody["password"].isNullOrBlank()) {
                if (passwordBody["password"] != passwordBody["password2"]) {
                    connection.send(ServerMessage(AlertData.fade("Password entries did not match.")))
                } else {
                    val hashed = BCrypt.with(BCrypt.Version.VERSION_2B).hash(
                        12,
                        passwordBody["password"]!!.toByteArray()
                    )
                    val success = Parasites.DAO.updatePassword(parasite.id, hashed)
                    if (success) connection.session.application.sendEmail(EmailTypes.ChangedPassword, parasite)
                    alerts.add(ServerMessage(AlertData.fade("Password ${if (!success) "not " else ""}changed.")))
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
                ChatManager.broadcast(
                    ServerMessage(ServerMessageTypes.UserList, mapOf("users" to ChatManager.buildParasiteList()))
                )
            }
            broadcastAlerts.forEach { ChatManager.broadcastToOthers(parasite.id.value, it) }

            // broadcast "update" to self connections
            if (updates.isNotEmpty()) {
                ChatManager.broadcastToParasite(parasite.id.value, ServerMessage(ServerMessageTypes.Update, updates))
            }
            alerts.forEach { connection.send(it) }
        }
    }
}

object ChatMessageHandler : MessageHandler {
    class ChatMessageBody(type: MessageTypes) : MessageBody(type) {
        val roomId: UUID? by other("room id")
        val message: String? by other("message")
    }

    override suspend fun handleMessage(
        connection: ChatSocketConnection,
        parasite: Parasites.ParasiteObject,
        body: MessageBody
    ) {
        onMessage<ChatMessageBody>(body) { messageBody ->
            if (messageBody.roomId == null) {
                throw BadRequestException("Invalid room id")
            }
            if (messageBody.message.isNullOrBlank()) {
                connection.logger.error("Bad message content")
            } else {
                ChatManager.handleChatMessage(messageBody.roomId!!, parasite, messageBody.message!!)
            }
        }
    }
}

object PrivateMessageHandler : MessageHandler {
    class PrivateMessageBody(type: MessageTypes) : MessageBody(type) {
        val recipientId: String? by other("recipient id")
        val message: String? by other("message")
    }

    override suspend fun handleMessage(
        connection: ChatSocketConnection,
        parasite: Parasites.ParasiteObject,
        body: MessageBody
    ) {
        onMessage<PrivateMessageBody>(body) { messageBody ->
            if (messageBody.message.isNullOrBlank() || messageBody.recipientId.isNullOrBlank()) {
                connection.logger.error("Bad message content")
            } else {
                ChatManager.handlePrivateMessage(messageBody.recipientId!!, parasite, messageBody.message!!)
            }
        }
    }
}

object ImageMessageHandler : MessageHandler {
    class ImageMessageBody(type: MessageTypes) : MessageBody(type) {
        val destination: String? by other("room id")  // it could be a parasite, too
        val url: String? by other("image url")
        val nsfw: Boolean? by other("nsfw")
    }

    override suspend fun handleMessage(
        connection: ChatSocketConnection,
        parasite: Parasites.ParasiteObject,
        body: MessageBody
    ) {
        onMessage<ImageMessageBody>(body) { messageBody ->
            if (messageBody.destination.isNullOrBlank() || messageBody.url.isNullOrBlank()) {
                connection.logger.error("Bad message content")
            } else {
                messageBody.destination?.let {
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
                                AlertData.dismiss(
                                    "Failed to send image. Admins have been notified of this incident.",
                                    "Sorry"
                                )
                            )
                        )
                        connection.session.application.sendErrorEmail(e)
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
        val destination: String? by other("room id")  // it could be a parasite, too
        val imageData: String? by other("image data")
        val imageType: String? by other("image type")
        val nsfw: Boolean? by other("nsfw")
    }

    override suspend fun handleMessage(
        connection: ChatSocketConnection,
        parasite: Parasites.ParasiteObject,
        body: MessageBody
    ) {
        onMessage<ImageUploadMessageBody>(body) { messageBody ->
            if (messageBody.destination.isNullOrBlank()
                || messageBody.imageData.isNullOrBlank()
                || messageBody.imageType.isNullOrBlank()
            ) {
                connection.logger.error("Bad message content")
            } else {
                messageBody.destination?.let {
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
                                AlertData.dismiss(
                                    "Failed to upload image. Admins have been notified of this incident.",
                                    "Sorry"
                                )
                            )
                        )
                        connection.session.application.sendErrorEmail(e)
                    }
                } ?: let {
                    connection.logger.error("Bad message content")
                }
            }
        }
    }
}

object RemoveAlertHandler : MessageHandler {
    class RemoveAlertMessageBody(type: MessageTypes) : MessageBody(type) {
        val id: UUID? by other("id")
    }

    override suspend fun handleMessage(
        connection: ChatSocketConnection,
        parasite: Parasites.ParasiteObject,
        body: MessageBody
    ) {
        onMessage<RemoveAlertMessageBody>(body) { messageBody ->
            if (messageBody.id == null) throw BadRequestException("Bad message content")
            Alerts.DAO.delete(messageBody.id!!, parasite.id)
        }
    }
}

object GithubIssueMessageHandler : MessageHandler {
    class GithubIssueMessageBody(type: MessageTypes) : MessageBody(type) {
        val title: String? by other("title")
        val body: String? by other("body")
    }

    override suspend fun handleMessage(
        connection: ChatSocketConnection,
        parasite: Parasites.ParasiteObject,
        body: MessageBody
    ) {
        onMessage<GithubIssueMessageBody>(body) { messageBody ->
            if (messageBody.title.isNullOrBlank() || messageBody.body.isNullOrBlank()) {
                connection.logger.error("Bad message content")
            } else {
                ChatManager.handleGithubIssueMessage(
                    connection,
                    messageBody.type.toString(),
                    messageBody.title!!,
                    messageBody.body!!
                )
            }
        }
    }
}


object ToolListMessageHandler : MessageHandler {
    class ToolListMessageBody(type: MessageTypes) : MessageBody(type) {
        val permissionLevel: ParasitePermissions? by otherEnum<ParasitePermissions, ToolListMessageBody>("tool set")
    }

    override suspend fun handleMessage(
        connection: ChatSocketConnection,
        parasite: Parasites.ParasiteObject,
        body: MessageBody
    ) {
        onMessage<ToolListMessageBody>(body) { messageBody ->
            if (messageBody.permissionLevel == null) {
                connection.logger.error("Bad message content")
            } else {
                ChatManager.handleToolListRequest(connection, parasite, messageBody.permissionLevel!!)
            }
        }
    }
}

object ToolDataMessageHandler : MessageHandler {
    class ToolDataMessageBody(type: MessageTypes) : MessageBody(type) {
        val toolId: String? by other("data type")
    }

    override suspend fun handleMessage(
        connection: ChatSocketConnection,
        parasite: Parasites.ParasiteObject,
        body: MessageBody
    ) {
        onMessage<ToolDataMessageBody>(body) { messageBody ->
            if (messageBody.toolId.isNullOrBlank()) {
                connection.logger.error("Bad message content")
            } else {
                ChatManager.handleToolDataRequest(connection, parasite, messageBody.toolId!!)
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
    RemoveAlert("remove alert") {
        override val handler = RemoveAlertHandler
    },
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
