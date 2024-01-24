package com.applepeacock.chat

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.listObjects
import aws.sdk.kotlin.services.s3.model.ObjectCannedAcl
import aws.sdk.kotlin.services.s3.putObject
import aws.smithy.kotlin.runtime.content.ByteStream
import com.applepeacock.database.*
import com.applepeacock.http.AuthenticationException
import com.applepeacock.plugins.ChatSocketConnection
import com.applepeacock.plugins.defaultMapper
import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.toJavaInstant
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

    val currentSocketConnections: MutableSet<ChatSocketConnection> = Collections.synchronizedSet(LinkedHashSet())
    val parasiteStatusMap: MutableMap<String, ParasiteStatus> = mutableMapOf()
    val parasiteTypingStatus: MutableMap<String, String?> = mutableMapOf()

    fun configure(imageCacheBucket: String, imageCacheHost: String) {
        this.imageCacheBucket = imageCacheBucket
        this.imageCacheHost = imageCacheHost
        logger.debug("Initializing OkHttp...")
        ktorClient = HttpClient(OkHttp)
        logger.debug("OkHttp initialized.")

        logger.debug("Initializing S3 client...")
        runBlocking {
            s3Client = S3Client.fromEnvironment {
                this.region = "us-west-2"
            }
        }
        logger.debug("S3 client initialized.")
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

    fun handlePrivateMessage(destinationId: String, senderId: String, message: String) {
        val parasite = Parasites.DAO.find(senderId)
        parasite?.let {
            if (Parasites.DAO.exists(destinationId)) {
                Messages.DAO.create(
                    parasite.id,
                    MessageDestination(destinationId, MessageDestinationTypes.Parasite),
                    mapOf(
                        "username" to parasite.name,
                        "color" to parasite.settings.color,
                        "message" to message
                    )
                )?.let {
                    val broadcastContent = mapOf(
                        "type" to MessageTypes.PrivateMessage,
                        "data" to it.data.plus("recipient id" to it.destination.id)
                            .plus("time" to it.sent.toJavaInstant())
                            .plus("sender id" to it.sender)
                    )
                    if (destinationId != senderId) {
                        broadcastToParasite(destinationId, broadcastContent)
                    }
                    broadcastToParasite(senderId, broadcastContent)
                }
            }
        }
    }

    fun handleChatMessage(destinationId: UUID, senderId: String, message: String) {
        val destinationRoom = Rooms.DAO.get(destinationId)
        destinationRoom?.let {
            val memberConnections = currentSocketConnections.filter { destinationRoom.members.contains(it.parasiteId) }
            val parasite = Parasites.DAO.find(senderId)
            parasite?.let {
                Messages.DAO.create(
                    parasite.id,
                    MessageDestination(destinationId.toString(), MessageDestinationTypes.Room),
                    mapOf(
                        "username" to parasite.name,
                        "color" to parasite.settings.color,
                        "message" to message
                    )
                )?.let {
                    broadcast(
                        memberConnections,
                        mapOf(
                            "type" to MessageTypes.ChatMessage,
                            "data" to it.data.plus("room id" to it.destination.id)
                                .plus("time" to it.sent.toJavaInstant())
                                .plus("sender id" to it.sender)
                        )
                    )
                }
            }
        }
    }
    private suspend fun uploadImageToS3(messageId: EntityID<UUID>, imageContent: ByteArray, imageContentType: ContentType?): String? {
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

    fun handleImageMessage(destination: MessageDestination, senderId: String, url: String, nsfw: Boolean) {
        fun handleImageCaching(newMessage: Messages.MessageObject): String {
            return runBlocking {
                val imageResponse = ktorClient.request(url)
                if (imageResponse.status.isSuccess()) {
                    val imageContentType = imageResponse.contentType() ?: ContentType.fromFilePath(url).firstOrNull()
                    val imageContent = imageResponse.readBytes()
                    uploadImageToS3(newMessage.id, imageContent, imageContentType) ?: url
                } else {
                    url
                }
            }
        }

        val parasite = Parasites.DAO.find(senderId)
        when (destination.type) {
            MessageDestinationTypes.Room -> {
                val destinationRoom = Rooms.DAO.get(UUID.fromString(destination.id))
                destinationRoom?.let {
                    val memberConnections =
                        currentSocketConnections.filter { destinationRoom.members.contains(it.parasiteId) }
                    parasite?.let {
                        Messages.DAO.create(
                            parasite.id,
                            destination,
                            mapOf(
                                "username" to parasite.name,
                                "color" to parasite.settings.color,
                                "image url" to url,
                                "nsfw flag" to nsfw
                            )
                        ) {
                            val cachedUrl = handleImageCaching(it)
                            broadcast(
                                memberConnections, mapOf(
                                    "type" to MessageTypes.ChatMessage,
                                    "data" to it.data.plus("room id" to it.destination.id)
                                        .plus("time" to it.sent.toJavaInstant())
                                        .plus("sender id" to it.sender)
                                        .plus("image src url" to cachedUrl)
                                )
                            )
                            Messages.DAO.update(it.id, it.data.plus("image src url" to cachedUrl))
                        }
                    }
                }
            }
            MessageDestinationTypes.Parasite -> {
                parasite?.let {
                    if (Parasites.DAO.exists(destination.id)) {
                        Messages.DAO.create(
                            parasite.id,
                            destination,
                            mapOf(
                                "username" to parasite.name,
                                "color" to parasite.settings.color,
                                "image url" to url,
                                "nsfw flag" to nsfw
                            )
                        ) {
                            val cachedUrl = handleImageCaching(it)
                            val broadcastContent = mapOf(
                                "type" to MessageTypes.PrivateMessage,
                                "data" to it.data.plus("recipient id" to it.destination.id)
                                    .plus("time" to it.sent.toJavaInstant())
                                    .plus("sender id" to it.sender)
                                    .plus("image src url" to cachedUrl)
                            )
                            if (destination.id != senderId) {
                                broadcastToParasite(destination.id, broadcastContent)
                            }
                            broadcastToParasite(senderId, broadcastContent)
                            Messages.DAO.update(it.id, it.data.plus("image src url" to cachedUrl))
                        }
                    }
                }
            }
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
        val messageBody = defaultMapper.readValue<MessageBody>(body)
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
    PrivateMessageList("private message data");

    override fun toString(): String {
        return this.value
    }
}

data class ServerMessage(val type: ServerMessageTypes, val data: Map<String, Any?>)
