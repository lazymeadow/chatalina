package com.applepeacock.chat

import com.applepeacock.database.Parasites
import com.applepeacock.http.AuthenticationException
import com.applepeacock.plugins.ChatSocketConnection
import com.applepeacock.plugins.defaultMapper
import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.module.kotlin.readValue
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
        fun fromString(statusString: String?): ParasiteStatus = values().find { it.value == statusString } ?: Offline
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
        broadcast(mapOf("users" to parasites))
    }

    fun addConnection(connection: ChatSocketConnection) {
        val wasOffline =
            (parasiteStatusMap.getOrDefault(
                connection.parasiteId,
                ParasiteStatus.Offline
            ) == ParasiteStatus.Offline)
        currentSocketConnections.add(connection)
        Parasites.DAO.setLastActive(connection.parasiteId)
        updateParasiteStatus(connection.parasiteId, ParasiteStatus.Active)
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

    fun broadcastToSelf(parasiteObject: String, data: Any) {
        synchronized(currentSocketConnections) {
            broadcast(currentSocketConnections.filter { it.parasiteId == parasiteObject }.toList(), data)
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
    AuthFail("auth fail");

    override fun toString(): String {
        return this.value
    }
}

data class ServerMessage(val type: ServerMessageTypes, val data: Map<String, Any?>)
