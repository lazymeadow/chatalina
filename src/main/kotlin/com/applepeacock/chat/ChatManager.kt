package com.applepeacock.chat

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.ObjectCannedAcl
import aws.sdk.kotlin.services.s3.putObject
import aws.smithy.kotlin.runtime.content.ByteStream
import com.applepeacock.database.*
import com.applepeacock.database.AlertData.Companion.toMap
import com.applepeacock.emoji.EmojiManager
import com.applepeacock.http.AuthenticationException
import com.applepeacock.plugins.ChatSocketConnection
import com.applepeacock.plugins.defaultMapper
import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.jackson.*
import io.ktor.server.plugins.*
import io.ktor.server.websocket.*
import io.ktor.util.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.dao.id.EntityID
import org.slf4j.LoggerFactory
import java.util.*
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

enum class ParasiteStatus(val value: String) {
    Active("active"),
    Idle("idle"),
    Offline("offline");

    override fun toString(): String = value

    companion object {
        fun fromString(statusString: String?): ParasiteStatus = entries.find { it.value == statusString } ?: Offline
    }
}

object ChatManager {
    val logger = LoggerFactory.getLogger("CHAT")
    private lateinit var s3Client: S3Client
    private lateinit var ktorClient: HttpClient
    private lateinit var imageCacheBucket: String
    private lateinit var imageCacheHost: String
    private lateinit var githubUser: String
    private lateinit var githubToken: String
    private lateinit var githubRepo: String

    val currentSocketConnections: MutableSet<ChatSocketConnection> = Collections.synchronizedSet(LinkedHashSet())
    val parasiteStatusMap: MutableMap<String, ParasiteStatus> = mutableMapOf()
    val parasiteTypingStatus: MutableMap<String, String?> = mutableMapOf()

    fun configure(
        imageCacheBucket: String,
        imageCacheHost: String,
        githubUser: String,
        githubToken: String,
        githubRepo: String
    ) {
        logger.debug("Initializing Chat Manager...")

        logger.debug("Initializing variables...")
        this.imageCacheBucket = imageCacheBucket
        this.imageCacheHost = imageCacheHost
        this.githubUser = githubUser
        this.githubToken = githubToken
        this.githubRepo = githubRepo
        logger.debug("Initializing OkHttp...")
        ktorClient = HttpClient(OkHttp) {
            install(ContentNegotiation) {
                jackson()
            }
        }

        logger.debug("Initializing S3 client...")
        runBlocking {
            s3Client = S3Client.fromEnvironment {
                this.region = "us-west-2"
            }
        }

        logger.debug("Chat Manager initialized.")
    }

    fun buildParasiteList(): List<Map<String, Any?>> = Parasites.DAO.list(active = true).map {
        buildMap {
            put("id", it.id.value)
            put("email", it.email)
            put("lastActive", it.lastActive?.toString())
            put("status", parasiteStatusMap.getOrDefault(it.id.value, ParasiteStatus.Offline))
            put("typing", parasiteTypingStatus[it.id.value])
            put("username", it.name)
            put("faction", it.settings.faction)
            put("color", it.settings.color)
            put("permission", it.settings.permission)
            put("soundSet", it.settings.soundSet)
            put("volume", it.settings.volume)
        }
    }

    fun updateParasiteStatus(parasiteId: String, newStatus: ParasiteStatus) {
        // update status map
        parasiteStatusMap[parasiteId] = newStatus
        // build user list
        val parasites = buildParasiteList()
        // broadcast to all connected sockets
        broadcast(ServerMessage(ServerMessageTypes.UserList, mapOf("users" to parasites)))
    }

    fun updateParasiteTypingStatus(parasiteId: String, newDestination: String?) {
        // update status map
        parasiteTypingStatus[parasiteId] = newDestination
        // build user list
        val parasites = buildParasiteList()
        // broadcast to all connected sockets
        broadcast(ServerMessage(ServerMessageTypes.UserList, mapOf("users" to parasites)))
    }

    private fun processMessage(message: String): String {
        return EmojiManager.convertEmojis(message)
    }

    fun handlePrivateMessage(destinationId: String, sender: Parasites.ParasiteObject, message: String) {
        if (Parasites.DAO.exists(destinationId)) {
            val processedMessage = processMessage(message)
            Messages.DAO.create(
                sender.id,
                MessageDestination(destinationId, MessageDestinationTypes.Parasite),
                mapOf(
                    "username" to sender.name,
                    "color" to sender.settings.color,
                    "message" to processedMessage
                )
            )?.let {
                val broadcastContent = mapOf("type" to MessageTypes.PrivateMessage, "data" to it.toMessageBody())
                if (destinationId != sender.id.value) {
                    broadcastToParasite(destinationId, broadcastContent)
                }
                broadcastToParasite(sender.id.value, broadcastContent)
            }
        }
    }

    fun handleChatMessage(destinationId: UUID, sender: Parasites.ParasiteObject, message: String) {
        val destinationRoom = Rooms.DAO.get(destinationId)
        destinationRoom?.let {
            val memberConnections = currentSocketConnections.filter { destinationRoom.members.contains(it.parasiteId) }
            val processedMessage = processMessage(message)
            Messages.DAO.create(
                sender.id,
                MessageDestination(destinationId.toString(), MessageDestinationTypes.Room),
                mapOf(
                    "username" to sender.name,
                    "color" to sender.settings.color,
                    "message" to processedMessage
                )
            )?.let {
                broadcast(memberConnections, mapOf("type" to MessageTypes.ChatMessage, "data" to it.toMessageBody()))
            }
        }
    }

    private suspend fun uploadImageToS3(
        messageId: EntityID<UUID>,
        imageContent: ByteArray,
        imageContentType: ContentType?
    ): String? {
        return if (imageContent.size > 0) {
            val objectKey = "images/${messageId}"
            s3Client.putObject {
                this.bucket = imageCacheBucket
                this.key = objectKey
                this.acl = ObjectCannedAcl.PublicRead
                this.body = ByteStream.fromBytes(imageContent)
                this.contentType = imageContentType.toString()
            }
            "${imageCacheHost}/$objectKey"
        } else {
            null
        }
    }

    fun handleImageMessage(
        destination: MessageDestination,
        sender: Parasites.ParasiteObject,
        url: String,
        nsfw: Boolean
    ) {
        fun createMessageAndCacheImage(broadcastImage: (Map<String, Any?>) -> Unit) {
            Messages.DAO.create(
                sender.id,
                destination,
                mapOf(
                    "username" to sender.name,
                    "color" to sender.settings.color,
                    "image url" to url,
                    "nsfw flag" to nsfw
                )
            ) { newMessage ->
                val cachedUrl = runBlocking {
                    val imageResponse = ktorClient.request(url)
                    if (imageResponse.status.isSuccess()) {
                        val imageContentType =
                            imageResponse.contentType() ?: ContentType.fromFilePath(url).firstOrNull()
                        val imageContent = imageResponse.readBytes()
                        uploadImageToS3(newMessage.id, imageContent, imageContentType) ?: url
                    } else {
                        url
                    }
                }
                val newData = newMessage.data.plus("image src url" to cachedUrl)
                Messages.DAO.update(newMessage.id, newData)
                broadcastImage(newMessage.toMessageBody() + newData)
            }
        }

        when (destination.type) {
            MessageDestinationTypes.Room -> {
                Rooms.DAO.get(UUID.fromString(destination.id))?.let { destinationRoom ->
                    val memberConnections =
                        currentSocketConnections.filter { destinationRoom.members.contains(it.parasiteId) }
                    createMessageAndCacheImage { data ->
                        broadcast(memberConnections, mapOf("type" to MessageTypes.ChatMessage, "data" to data))
                    }
                }
            }
            MessageDestinationTypes.Parasite -> {
                if (Parasites.DAO.exists(destination.id)) {
                    createMessageAndCacheImage { data ->
                        val broadcastContent = mapOf("type" to MessageTypes.PrivateMessage, "data" to data)
                        if (destination.id != sender.id.value) {
                            broadcastToParasite(destination.id, broadcastContent)
                        }
                        broadcastToParasite(sender.id, broadcastContent)
                    }
                }
            }
        }
    }

    fun handleImageUploadMessage(
        destination: MessageDestination,
        sender: Parasites.ParasiteObject,
        imageData: String,
        imageType: String,
        nsfw: Boolean
    ) {
        val imageContentType = ContentType.parse(imageType)
        val imageBytes = runBlocking {
            imageData.substringAfter(",").decodeBase64Bytes()
        }

        fun createMessageAndUploadImage(
            broadcastResult: (Map<String, Any?>) -> Unit
        ) {
            Messages.DAO.create(
                sender.id,
                destination,
                mapOf(
                    "username" to sender.name,
                    "color" to sender.settings.color,
                    "image url" to "",
                    "nsfw flag" to nsfw
                )
            ) {
                runBlocking {
                    uploadImageToS3(it.id, imageBytes, imageContentType)
                }?.let { cachedUrl ->
                    val newData = it.data.plus("image url" to cachedUrl).plus("image src url" to cachedUrl)
                    Messages.DAO.update(it.id, newData)
                    broadcastResult(it.toMessageBody() + newData)
                } ?: throw IllegalStateException("Image upload failed")
            }
        }

        when (destination.type) {
            MessageDestinationTypes.Room -> {
                Rooms.DAO.get(UUID.fromString(destination.id))?.let { destinationRoom ->
                    val memberConnections =
                        currentSocketConnections.filter { destinationRoom.members.contains(it.parasiteId) }
                    createMessageAndUploadImage { data ->
                        broadcast(memberConnections, mapOf("type" to MessageTypes.ChatMessage, "data" to data))
                    }
                }
            }
            MessageDestinationTypes.Parasite -> {
                if (Parasites.DAO.exists(destination.id)) {
                    createMessageAndUploadImage { data ->
                        val broadcastContent = mapOf("type" to MessageTypes.PrivateMessage, "data" to data)
                        if (destination.id != sender.id.value) {
                            broadcastToParasite(destination.id, broadcastContent)
                        }
                        broadcastToParasite(sender.id, broadcastContent)
                    }
                }
            }
        }
    }

    fun handleGithubIssueMessage(
        connection: ChatSocketConnection,
        issueType: String,
        issueTitle: String,
        issueBody: String
    ) {
        runBlocking {
            try {
                val githubResponse = ktorClient.post("https://api.github.com/repos/$githubUser/$githubRepo/issues") {
                    this.setBody(mapOf("title" to issueTitle, "body" to issueBody, "labels" to listOf(issueType)))
                    this.contentType(ContentType.Application.Json)
                    this.accept(ContentType.parse("application/vnd.github+json"))
                    this.basicAuth(githubUser, githubToken)
                }
                val responseBody = githubResponse.body<Map<String, *>>()
                val message = if (githubResponse.status.isSuccess()) {
                    "${issueType.replaceFirstChar { it.uppercase() }} #${responseBody["number"]} created! View it at <a href=${responseBody["html_url"]}>${responseBody["html_url"]}</a>"
                } else {
                    "Failed to create ${issueType.replaceFirstChar { it.uppercase() }}! (${responseBody["message"]})"
                }
                connection.send(ServerMessage(ServerMessageTypes.Alert, AlertData("dismiss", message, "OK")))
            } catch (e: Throwable) {
                e.printStackTrace()
                connection.send(
                    ServerMessage(
                        ServerMessageTypes.Alert,
                        AlertData(
                            "dismiss",
                            "Failed to create ${issueType.replaceFirstChar { it.uppercase() }}!",
                            "OK"
                        )
                    )
                )
                connection.session.application.sendErrorEmail(e)
            }
        }
    }

    fun handleToolListRequest(
        connection: ChatSocketConnection,
        sender: Parasites.ParasiteObject,
        accessLevel: ParasitePermissions
    ) {
        if (ParasitePermissions.permissionLevelAccess(accessLevel, sender.settings.permission)) {
            connection.logger.debug("Sending {} tool list to parasite.", accessLevel)
            val toolList = accessLevel.getToolList()
            connection.send(
                ServerMessage(
                    ServerMessageTypes.ToolList,
                    mapOf("perm level" to accessLevel, "data" to toolList)
                )
            )
        }
    }

    fun handleToolDataRequest(connection: ChatSocketConnection, sender: Parasites.ParasiteObject, toolId: String) {
        toolDefinitions.find { it.id == toolId }?.let { definition ->
            var data: Any? = null
            var info: ToolDefinition<*, *>? = null
            var error: String? = null
            if (ParasitePermissions.permissionLevelAccess(definition.accessLevel, sender.settings.permission)) {
                info = definition
                data = definition.dataFunction(sender)
            } else {
                error = "Insufficient permissions"
            }
            connection.send(
                ServerMessage(
                    ServerMessageTypes.ToolResponse,
                    mapOf("tool info" to info, "data" to data, "request" to toolId, "error" to error)
                )
            )
        }
    }

    fun addConnection(connection: ChatSocketConnection) {
        val wasOffline = (parasiteStatusMap.getOrDefault(
            connection.parasiteId,
            ParasiteStatus.Offline
        ) == ParasiteStatus.Offline)
        currentSocketConnections.add(connection)
        Parasites.DAO.setLastActive(connection.parasiteId)
        updateParasiteStatus(connection.parasiteId, ParasiteStatus.Active)
        val roomList = Rooms.DAO.list(connection.parasiteId)
        connection.send(ServerMessage(ServerMessageTypes.RoomList, mapOf("rooms" to roomList)))
        val privateMessagesList = Messages.DAO.list(connection.parasiteId)
        connection.send(ServerMessage(ServerMessageTypes.PrivateMessageList, mapOf("threads" to privateMessagesList)))
        connection.send(
            ServerMessage(
                ServerMessageTypes.Alert,
                mapOf("message" to "Connection successful.", "type" to "fade")
            )
        )
        val parasiteName = Parasites.DAO.find(connection.parasiteId)?.name
        if (wasOffline) {
            broadcastToOthers(
                connection.parasiteId,
                ServerMessage(
                    ServerMessageTypes.Alert,
                    mapOf("message" to "${parasiteName ?: connection.parasiteId} is online.", "type" to "fade")
                )
            )
        }
    }

    fun removeConnection(connection: ChatSocketConnection) {
        currentSocketConnections.remove(connection)
        // if that was the last connection for a parasite, make their status "offline" and tell everyone
        val hasMoreConnections = currentSocketConnections.any { it.parasiteId == connection.parasiteId }
        if (!hasMoreConnections) {
            updateParasiteStatus(connection.parasiteId, ParasiteStatus.Offline)
            val parasiteName = Parasites.DAO.find(connection.parasiteId)?.name
            broadcastToOthers(
                connection.parasiteId,
                ServerMessage(
                    ServerMessageTypes.Alert,
                    mapOf("message" to "${parasiteName ?: connection.parasiteId} is offline.", "type" to "fade")
                )
            )
        }
    }

    fun broadcastToParasite(parasiteId: EntityID<String>, data: Any) {
        broadcastToParasite(parasiteId.value, data)
    }

    fun broadcastToParasite(parasiteId: String, data: Any) {
        synchronized(currentSocketConnections) {
            broadcast(currentSocketConnections.filter { it.parasiteId == parasiteId }.toList(), data)
        }
    }

    fun broadcastToOthers(excludeParasite: String, data: Any) {
        synchronized(currentSocketConnections) {
            broadcast(currentSocketConnections.filterNot { it.parasiteId == excludeParasite }.toList(), data)
        }
    }

    fun broadcast(data: Any) {
        synchronized(currentSocketConnections) {
            broadcast(currentSocketConnections, data)
        }
    }

    private fun broadcast(connections: Collection<ChatSocketConnection>, data: Any) {
        connections.forEach { it.send(data) }
    }

    suspend fun handleMessage(connection: ChatSocketConnection, body: String) {
        val currentParasite = Parasites.DAO.find(connection.parasiteId) ?: throw AuthenticationException()
        val messageBody = try {
            defaultMapper.readValue<MessageBody>(body)
        } catch (e: Throwable) {
            connection.session.application.sendErrorEmail(e)
            throw BadRequestException("Bad message content")
        }
        connection.logger.debug("received message: {}", messageBody.type)
        messageBody.type.handler.handleMessage(connection, currentParasite, messageBody)
    }
}

open class MessageBody(
    val type: MessageTypes,
    @JsonAnySetter
    @JsonAnyGetter
    val other: MutableMap<String, Any?> = mutableMapOf()
) {
    private fun <T, TValue> map(properties: MutableMap<String, TValue>, key: String): ReadOnlyProperty<T, TValue?> {
        return object : ReadOnlyProperty<T, TValue?> {
            override fun getValue(thisRef: T, property: KProperty<*>) = properties[key]
        }
    }

    fun <T> fromOther(key: String): ReadOnlyProperty<T, Any?> = map(other, key)
}

enum class ServerMessageTypes(val value: String) {
    Alert("alert"),
    Update("update"),
    AuthFail("auth fail"),
    UserList("user list"),
    RoomList("room data"),
    PrivateMessageList("private message data"),
    ToolList("tool list"),
    ToolResponse("data response");

    override fun toString(): String {
        return this.value
    }
}

data class ServerMessage(val type: ServerMessageTypes, val data: Map<String, Any?>) {
    constructor(type: ServerMessageTypes, data: AlertData) : this(type, data.toMap())
}
