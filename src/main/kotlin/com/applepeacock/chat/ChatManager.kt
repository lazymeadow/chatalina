package com.applepeacock.chat

import com.applepeacock.database.*
import com.applepeacock.http.AuthenticationException
import com.applepeacock.plugins.ChatSocketConnection
import com.applepeacock.plugins.defaultMapper
import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.datetime.toJavaInstant
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
    val currentSocketConnections: MutableSet<ChatSocketConnection> = Collections.synchronizedSet(LinkedHashSet())
    val parasiteStatusMap: MutableMap<String, ParasiteStatus> = mutableMapOf()

    init {
        logger.debug("Initializing user list...")
        logger.debug("User list initialized.")
    }

    fun buildParasiteList(): List<Map<String, Any?>> = Parasites.DAO.list(active = true).map {
        buildMap {
            put("id", it.id.value)
            put("email", it.email)
            put("lastActive", it.lastActive?.toString())
            put("status", parasiteStatusMap.getOrDefault(it.id.value, ParasiteStatus.Offline))
            put("typing", false)
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
        connection.logger.debug("received message: ${messageBody.type}")
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
