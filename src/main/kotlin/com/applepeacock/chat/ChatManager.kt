package com.applepeacock.chat

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.headObject
import aws.sdk.kotlin.services.s3.model.ObjectCannedAcl
import aws.sdk.kotlin.services.s3.model.S3Exception
import aws.sdk.kotlin.services.s3.putObject
import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.http.HttpStatusCode
import aws.smithy.kotlin.runtime.http.response.statusCode
import com.applepeacock.database.*
import com.applepeacock.database.AlertData.Companion.toMap
import com.applepeacock.emoji.EmojiManager
import com.applepeacock.http.AuthenticationException
import com.applepeacock.plugins.ChatSocketConnection
import com.applepeacock.plugins.defaultMapper
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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jetbrains.exposed.dao.id.EntityID
import org.nibor.autolink.LinkExtractor
import org.nibor.autolink.LinkSpan
import org.nibor.autolink.LinkType
import org.slf4j.LoggerFactory
import java.math.BigInteger
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest
import java.util.*


enum class ParasiteStatus(val value: String) {
    Active("active"),
    Idle("idle"),
    Offline("offline");

    override fun toString(): String = value

    companion object {
        fun fromString(statusString: String?): ParasiteStatus = entries.find { it.value == statusString } ?: Offline
    }
}

object ParasiteStatusMap {
    private val map = mutableMapOf<String, ParasiteStatusObject>()

    data class ParasiteStatusObject(var status: ParasiteStatus = ParasiteStatus.Offline, var typing: String? = null)

    private fun getAlways(parasiteId: String) = map.getOrPut(parasiteId, { ParasiteStatusObject() })

    fun getStatus(parasiteId: String): ParasiteStatus = map[parasiteId]?.status ?: ParasiteStatus.Offline
    fun getStatus(parasiteId: EntityID<String>): ParasiteStatus = getStatus(parasiteId.value)
    fun setStatus(parasiteId: String, newStatus: ParasiteStatus) =
        getAlways(parasiteId).also { it.status = newStatus }.status

    fun setStatus(parasiteId: EntityID<String>, newStatus: ParasiteStatus) = setStatus(parasiteId.value, newStatus)

    fun getTyping(parasiteId: String): String? = map[parasiteId]?.typing
    fun getTyping(parasiteId: EntityID<String>): String? = getTyping(parasiteId.value)
    fun setTyping(parasiteId: String, newTyping: String?) = getAlways(parasiteId).also { it.typing = newTyping }.typing
    fun setTyping(parasiteId: EntityID<String>, newTyping: String?) = setTyping(parasiteId.value, newTyping)
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
    private var gorillaGrooveHost: URI? = null

    val currentSocketConnections: MutableSet<ChatSocketConnection> = Collections.synchronizedSet(LinkedHashSet())

    fun configure(
        imageCacheBucket: String,
        imageCacheHost: String,
        githubUser: String,
        githubToken: String,
        githubRepo: String,
        gorillaGrooveHost: String?
    ) {
        logger.debug("Initializing Chat Manager...")

        logger.debug("Initializing variables...")
        this.imageCacheBucket = imageCacheBucket
        this.imageCacheHost = imageCacheHost
        this.githubUser = githubUser
        this.githubToken = githubToken
        this.githubRepo = githubRepo
        this.gorillaGrooveHost = gorillaGrooveHost?.let { URI(it) }

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
            put("status", ParasiteStatusMap.getStatus(it.id))
            put("typing", ParasiteStatusMap.getTyping(it.id))
            put("username", it.name)
            put("faction", it.settings.faction)
            put("color", it.settings.color)
            put("permission", it.settings.permission)
            put("soundSet", it.settings.soundSet)
            put("volume", it.settings.volume)
        }
    }


    private fun sendRoomList(parasiteId: EntityID<String>, room: Rooms.RoomObject?) =
        sendRoomList(parasiteId.value, room)

    private fun sendRoomList(parasiteId: String, room: Rooms.RoomObject?) {
        val data = room?.let {
            mapOf("rooms" to listOf(it), "all" to false)
        } ?: mapOf("rooms" to Rooms.DAO.list(parasiteId))
        broadcastToParasite(parasiteId, ServerMessage(ServerMessageTypes.RoomList, data))
    }

    private fun broadcastUserList() {
        // build user list
        val parasites = buildParasiteList()
        // broadcast to all connected sockets
        broadcast(ServerMessage(ServerMessageTypes.UserList, mapOf("users" to parasites)))
    }

    fun updateParasiteStatus(parasiteId: EntityID<String>, newStatus: ParasiteStatus) {
        ParasiteStatusMap.setStatus(parasiteId, newStatus)
        broadcastUserList()
    }

    fun updateParasiteTypingStatus(parasiteId: EntityID<String>, newDestination: String?) {
        ParasiteStatusMap.setTyping(parasiteId, newDestination)
        broadcastUserList()
    }

    private fun textToNormalizedUri(text: String) = try {
        URI(text).normalize()
    } catch (e: URISyntaxException) {
        null  // Do nothing - this is fine
    }

    private fun isSneakyImageLink(uri: URI): Boolean = uri.scheme == "data" || (!uri.path.isNullOrBlank() &&
            ContentType.fromFilePath(uri.path).any { it.contentType == ContentType.Image.Any.contentType })

    private fun isGorillaGrooveLink(uri: URI) =
        uri.scheme == gorillaGrooveHost?.scheme && uri.authority == gorillaGrooveHost?.authority

    private val linkExtractor = LinkExtractor.builder().build()
    val disallowedTags = setOf(
        "form", "audio", "video", "iframe", "img", "canvas", "input", "datalist", "button", "dialog", "embed",
        "fieldset", "html", "link", "object", "select", "picture", "search", "template", "textarea"
    )

    private fun processMessageText(message: String): String {
        if ("""<\s?(?:script|${disallowedTags.joinToString("|")})""".toRegex()
                .containsMatchIn(message.lowercase())
        ) {
            return "<pre>${EmojiManager.convertEmojis(message).escapeHTML()}</pre>"
        } else {
            val anchorTagRegex = """<\s?[a|A]\s?(?=>)""".toRegex()  // match stuff like <a href=..., < A ... or just <a>
            return if (anchorTagRegex.containsMatchIn(message)) {
                // skip linkification, since it's already done. just add target="_blank" and rel="noreferrer"
                anchorTagRegex.replace(message) { "<a target=\"_blank\" rel=\"noreferrer\"" }.let {
                    EmojiManager.convertEmojis(it)
                }
            } else {
                // linkify BEFORE emojis, because emoji parser will avoid links.
                val spans = linkExtractor.extractSpans(message)

                buildString {
                    spans.forEach { span ->
                        message.substring(span.beginIndex, span.endIndex).let { text ->
                            if (span is LinkSpan) {
                                val urlToUse = when (span.type) {
                                    LinkType.WWW -> "https://$text"
                                    LinkType.EMAIL -> "mailto:$text"
                                    else -> text
                                }.let { textToNormalizedUri(it)?.toASCIIString() ?: it.encodeURLPath() }
                                append("<a href=\"${urlToUse}\" target=\"_blank\" rel=\"noreferrer\">${text.escapeHTML()}</a>")
                            } else {
                                append(text)
                            }
                        }
                    }
                }.let { EmojiManager.convertEmojis(it) }
            }
        }
    }

    private fun createAndSendChatMessage(
        destination: MessageDestination,
        sender: Parasites.ParasiteObject,
        messageData: MessageData,
        destinationRoom: Rooms.RoomObject? = null
    ) {
        Messages.DAO.create(sender.id, destination, messageData) {
            if (destination.type == MessageDestinationTypes.Room && destinationRoom != null) {
                broadcastToRoom(
                    destinationRoom,
                    mapOf("type" to MessageTypes.ChatMessage, "data" to it)
                )
            } else if (destination.type == MessageDestinationTypes.Parasite) {
                val broadcastContent = mapOf("type" to MessageTypes.PrivateMessage, "data" to it)
                if (destination.id != sender.id.value) {
                    broadcastToParasite(destination.id, broadcastContent)
                }
                broadcastToParasite(sender.id.value, broadcastContent)
            }
        }
    }

    private fun createAndSendGorillaGrooveMessage(
        destination: MessageDestination,
        sender: Parasites.ParasiteObject,
        link: URI,
        destinationRoom: Rooms.RoomObject? = null
    ) {
        createAndSendChatMessage(
            destination,
            sender,
            MessageData.GorillaGrooveMessageData(sender.name, sender.settings.color, link.toASCIIString()),
            destinationRoom
        )
    }

    fun handlePrivateMessage(destinationId: String, sender: Parasites.ParasiteObject, message: String) {
        val messageUri = textToNormalizedUri(message)?.also {
            if (isSneakyImageLink(it)) {
                return handleImageMessage(destinationId, sender, message, false)
            }
        }
        if (Parasites.DAO.exists(destinationId)) {
            val destination = MessageDestination(destinationId, MessageDestinationTypes.Parasite)
            if (messageUri != null && isGorillaGrooveLink(messageUri)) {
                createAndSendGorillaGrooveMessage(destination, sender, messageUri)
            } else {
                createAndSendChatMessage(
                    destination,
                    sender,
                    MessageData.ChatMessageData(sender.name, sender.settings.color, processMessageText(message))
                )
            }
        }
    }

    fun handleRoomMessage(destinationId: Int, sender: Parasites.ParasiteObject, message: String) {
        val messageUri = textToNormalizedUri(message)?.also {
            if (isSneakyImageLink(it)) {
                return handleImageMessage(destinationId.toString(), sender, message, false)
            }
        }
        Rooms.DAO.find(destinationId)?.let { destinationRoom ->
            val destination = MessageDestination(destinationId.toString(), MessageDestinationTypes.Room)
            if (messageUri != null && isGorillaGrooveLink(messageUri)) {
                createAndSendGorillaGrooveMessage(destination, sender, messageUri)
            } else {
                createAndSendChatMessage(
                    destination,
                    sender,
                    MessageData.ChatMessageData(sender.name, sender.settings.color, processMessageText(message)),
                    destinationRoom
                )
            }
        }
    }

    private suspend fun uploadImageToS3(imageContent: ByteArray, imageContentType: ContentType?): String? {
        val digest = MessageDigest.getInstance("SHA256").digest(imageContent)
        val hash = BigInteger(1, digest).toString(16).uppercase()
        println(hash)
        return if (imageContent.size > 0) {
            val objectKey = "images/${hash}"
            println(objectKey)
            val exists = try {
                s3Client.headObject {
                    bucket = imageCacheBucket
                    key = objectKey
                }
                true
            } catch (e: S3Exception) {
                if (e.sdkErrorMetadata.protocolResponse.statusCode() == HttpStatusCode.NotFound) {
                    false
                } else {
                    throw e
                }
            }
            if (!exists) {
                s3Client.putObject {
                    this.bucket = imageCacheBucket
                    this.key = objectKey
                    this.acl = ObjectCannedAcl.PublicRead
                    this.body = ByteStream.fromBytes(imageContent)
                    this.contentType = imageContentType?.toString()
                }
            }
            "${imageCacheHost}/$objectKey"
        } else {
            null
        }
    }

    fun handleImageMessage(destinationId: String, sender: Parasites.ParasiteObject, urlString: String, nsfw: Boolean) {
        fun createMessageAndCacheImage(
            uri: URI,
            destination: MessageDestination,
            broadcastImage: (Messages.MessageObject) -> Unit
        ) {
            val srcUrl = uri.toHttpUrlOrNull()?.let {
                runBlocking {
                    val imageResponse = ktorClient.request(uri.toASCIIString())
                    if (imageResponse.status.isSuccess()) {
                        val imageContentType = imageResponse.contentType()
                                ?: ContentType.fromFilePath(urlString).firstOrNull()
                        val imageContent = imageResponse.readBytes()
                        uploadImageToS3(imageContent, imageContentType) ?: urlString
                    } else {
                        urlString
                    }
                }
            } ?: urlString
            val data = MessageData.ImageMessageData(sender.name, sender.settings.color, nsfw, urlString, srcUrl)
            Messages.DAO.create(sender.id, destination, data) { newMessage -> broadcastImage(newMessage) }
        }

        val messageUri = textToNormalizedUri(urlString)
        if (messageUri == null && nsfw) {
            return handleImageUploadMessage(
                destinationId,
                sender,
                "<html><body><pre>${urlString.escapeHTML()}</pre></body></html>".encodeBase64(),
                ContentType.Text.Html.toString(),
                nsfw
            )
        } else if (messageUri != null && messageUri.scheme == "data") {
            return handleImageUploadMessage(
                destinationId,
                sender,
                messageUri.schemeSpecificPart.substringAfter(";"),
                messageUri.schemeSpecificPart.substringBefore(";"),
                nsfw
            )
        }

        destinationId.toIntOrNull()?.let { destinationInt ->
            if (messageUri == null || messageUri.toHttpUrlOrNull() == null) {
                return handleRoomMessage(destinationInt, sender, urlString)
            }

            val destination = MessageDestination(destinationId, MessageDestinationTypes.Room)
            Rooms.DAO.find(destinationInt)?.let { destinationRoom ->
                if (isGorillaGrooveLink(messageUri)) {
                    createAndSendGorillaGrooveMessage(destination, sender, messageUri, destinationRoom)
                } else {
                    createMessageAndCacheImage(messageUri, destination) { data ->
                        broadcastToRoom(
                            destinationRoom,
                            mapOf("type" to MessageTypes.ChatMessage, "data" to data)
                        )
                    }
                }
            }
        } ?: let {
            // ok, it must be a parasite id.
            if (messageUri == null || messageUri.toHttpUrlOrNull() == null) {
                return handlePrivateMessage(destinationId, sender, urlString)
            }

            val destination = MessageDestination(destinationId, MessageDestinationTypes.Parasite)
            if (Parasites.DAO.exists(destination.id)) {
                if (isGorillaGrooveLink(messageUri)) {
                    createAndSendGorillaGrooveMessage(destination, sender, messageUri)
                } else {
                    createMessageAndCacheImage(messageUri, destination) { data ->
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
        destinationId: String,
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
            destination: MessageDestination,
            broadcastResult: (Messages.MessageObject) -> Unit
        ) {
            val imgUrl = runBlocking {
                uploadImageToS3(imageBytes, imageContentType)
            } ?: throw IllegalStateException("Image upload failed")
            val newImageMessageData = MessageData.ImageMessageData(sender.name, sender.settings.color, nsfw, imgUrl)
            Messages.DAO.create(sender.id, destination, newImageMessageData) { broadcastResult(it) }
        }

        destinationId.toIntOrNull()?.let {
            val destination = MessageDestination(destinationId, MessageDestinationTypes.Room)
            Rooms.DAO.find(it)?.let { destinationRoom ->
                createMessageAndUploadImage(destination) { data ->
                    broadcastToRoom(destinationRoom, mapOf("type" to MessageTypes.ChatMessage, "data" to data))
                }
            }
        } ?: {
            // ok, it must be a parasite id.
            val destination = MessageDestination(destinationId, MessageDestinationTypes.Parasite)
            if (Parasites.DAO.exists(destination.id)) {
                if (Parasites.DAO.exists(destination.id)) {
                    createMessageAndUploadImage(destination) { data ->
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

    fun handleCreateRoom(connection: ChatSocketConnection, parasite: Parasites.ParasiteObject, name: String?) {
        val newRoom = Rooms.DAO.create(parasite.id, name ?: "${parasite.name}'s Room")
        sendRoomList(connection.parasiteId, newRoom)
    }

    fun handleSendInvitations(
        connection: ChatSocketConnection,
        roomId: Int,
        sender: Parasites.ParasiteObject,
        invitees: List<String>
    ) {
        val room = Rooms.DAO.find(roomId) ?: throw BadRequestException("Invalid invitation")
        if (!room.members.contains(sender.id.value)) throw BadRequestException("Invalid invitation")
        val invalidParasites = invitees.filter { i -> i == sender.id.value }.toSet()
        val inviteeParasites = Parasites.DAO.find(*invitees.toTypedArray())
        invalidParasites.plus(invitees.filterNot { i -> inviteeParasites.any { it.id.value == i } })

        if (inviteeParasites.isNotEmpty()) {
            inviteeParasites.forEach { parasite ->
                if (room.members.contains(parasite.id.value)) {
                    connection.send(ServerMessage(AlertData.fade("Can't invite ${parasite.name} (${parasite.id}) to '${room.name}'. They're already in it!")))
                    sendRoomList(sender.id, room)
                } else {
                    RoomInvitations.DAO.create(room.id, sender.id, parasite.id)?.let {
                        val allInvites = RoomInvitations.DAO.list(parasite.id, room.id)
                        broadcastToParasite(parasite.id, ServerMessage(room.id, allInvites))
                        connection.send(ServerMessage(AlertData.fade("Invitation sent to ${parasite.name} (${parasite.id}) to join '${room.name}'.")))
                    }
                }
            }
        }
        if (invalidParasites.isNotEmpty()) {
            connection.send(
                ServerMessage(
                    AlertData.dismiss(
                        "Failed to invite parasites to ${room.name}: ${invalidParasites.joinToString()}",
                        "My bad"
                    )
                )
            )
        }
    }

    fun handleInvitationResponse(
        connection: ChatSocketConnection,
        parasite: Parasites.ParasiteObject,
        roomId: Int,
        accept: Boolean
    ) {
        val room = Rooms.DAO.find(roomId) ?: throw BadRequestException("Invalid invitation")
        val existingInvitations = RoomInvitations.DAO.list(parasite.id, room.id)
        if (existingInvitations.isEmpty()) {
            throw BadRequestException("Invalid invitation")
        }

        if (accept) {
            Rooms.DAO.addMember(parasite.id, room.id)?.let { updatedRoom ->
                // broadcast updated room list & join notice to current members
                val acceptedMessage = "${parasite.name} has accepted your invitation and joined '${updatedRoom.name}'."
                val joinedMessage = "${parasite.name} has joined '${updatedRoom.name}'."
                updatedRoom.members.forEach { m ->
                    sendRoomList(m, updatedRoom)
                    if (m == parasite.id.value) {
                        broadcastToParasite(m, ServerMessage(AlertData.fade("You joined the room '${room.name}'.")))
                    } else {
                        val isInviter = existingInvitations.any { i -> i.sender.value == m }
                        val alertData = AlertData.dismiss(if (isInviter) acceptedMessage else joinedMessage, "Nice")
                        Alerts.DAO.create(m, alertData).also { a ->
                            broadcastToParasite(m, ServerMessage(alertData, a?.id))
                        }
                    }
                }
            } ?: let {
                // send fail to connection
                connection.send(ServerMessage(AlertData.fade("Failed to join room '${room.name}'. Maybe somebody is playing a joke on you?")))
            }
        } else {
            // we remove the parasite from the room, just in case they did something weird.
            Rooms.DAO.removeMember(room.id, parasite.id)?.let { updatedRoom ->
                // send decline notice to inviters
                existingInvitations.forEach { i ->
                    val alertData = AlertData.dismiss(
                        "${parasite.name} has declined your invitation to join '${room.name}'.",
                        "Sad"
                    )
                    Alerts.DAO.create(i.sender, alertData).also { a ->
                        broadcastToParasite(i.sender, ServerMessage(alertData, a?.id))
                    }
                }
                updatedRoom.members.forEach { m -> sendRoomList(m, updatedRoom) }
                connection.send(ServerMessage(AlertData.fade("You declined to join the room '${room.name}'.")))
            } ?: let {
                // send fail to connection
                connection.send(ServerMessage(AlertData.fade("There was a problem declining your invitation to '${room.name}'.")))
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
            val issueTypeFormatted = issueType.replaceFirstChar { it.uppercase() }
            val errorMessage = "Failed to create ${issueTypeFormatted}! Admins have been notified of this incident."
            try {
                val githubResponse = ktorClient.post("https://api.github.com/repos/$githubUser/$githubRepo/issues") {
                    this.setBody(mapOf("title" to issueTitle, "body" to issueBody, "labels" to listOf(issueType)))
                    this.contentType(ContentType.Application.Json)
                    this.accept(ContentType.parse("application/vnd.github+json"))
                    this.basicAuth(githubUser, githubToken)
                }
                val responseBody = githubResponse.body<Map<String, *>>()
                if (githubResponse.status.isSuccess()) {
                    connection.send(
                        ServerMessage(
                            AlertData.dismiss(
                                "<a href=${responseBody["html_url"]}>${issueTypeFormatted} #${responseBody["number"]}</a> created!",
                                "OK"
                            )
                        )
                    )
                } else {
                    connection.send(ServerMessage(AlertData.dismiss(errorMessage, "Oops")))
                    connection.session.application.sendErrorEmail("Failed to create ${issueTypeFormatted} in github.\n\nStatus: ${githubResponse.status}\nResponse: ${responseBody["message"]}")
                }
            } catch (e: Throwable) {
                e.printStackTrace()
                connection.send(ServerMessage(AlertData.dismiss(errorMessage, "Uh oh")))
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
        val parasite = Parasites.DAO.find(connection.parasiteId) ?: throw AuthenticationException()
        synchronized(currentSocketConnections) { currentSocketConnections.add(connection) }

        val wasOffline = ParasiteStatusMap.getStatus(parasite.id) == ParasiteStatus.Offline

        Parasites.DAO.setLastActive(parasite.id)
        ParasiteStatusMap.setStatus(parasite.id, ParasiteStatus.Active)
        broadcastUserList()

        val roomList = Rooms.DAO.list(parasite.id)
        connection.send(ServerMessage(ServerMessageTypes.RoomList, mapOf("rooms" to roomList, "all" to true)))
        val privateMessagesList = Messages.DAO.list(parasite.id)
        connection.send(ServerMessage(ServerMessageTypes.PrivateMessageList, mapOf("threads" to privateMessagesList)))

        val outstandingAlerts = Alerts.DAO.list(parasite.id)
        outstandingAlerts.forEach { connection.send(ServerMessage(it.data, it.id)) }
        val outstandingInvitations = RoomInvitations.DAO.list(parasite.id)
        outstandingInvitations.groupBy({ it.room }, { it })
            .forEach { (roomId, invitations) -> connection.send(ServerMessage(roomId, invitations)) }

        connection.send(ServerMessage(AlertData.fade("Connection successful.")))
        if (wasOffline) {
            broadcastToOthers(parasite.id, ServerMessage(AlertData.fade("${parasite.name} is online.")))
        }
    }

    fun removeConnection(connection: ChatSocketConnection) {
        synchronized(currentSocketConnections) { currentSocketConnections.remove(connection) }
        // if that was the last connection for a parasite, make their status "offline" and tell everyone
        if (!parasiteIsConnected(connection.parasiteId)) {
            Parasites.DAO.find(connection.parasiteId)?.let { parasite ->
                ParasiteStatusMap.setStatus(parasite.id, ParasiteStatus.Offline)
                broadcastToOthers(parasite.id, ServerMessage(AlertData.fade("${parasite.name} is offline.")))
            } ?: let {
                ParasiteStatusMap.setStatus(connection.parasiteId, ParasiteStatus.Offline)
            }
            broadcastUserList()
        }
        // TODO: handle idle status
    }

    private fun parasiteIsConnected(parasiteId: String) = synchronized(currentSocketConnections) {
        currentSocketConnections.any { it.parasiteId == parasiteId }
    }

    private fun getConnectionsForRoom(room: Rooms.RoomObject) = synchronized(currentSocketConnections) {
        currentSocketConnections.filter { room.members.contains(it.parasiteId) }
    }

    private fun getConnectionsForParasite(parasiteId: String) = synchronized(currentSocketConnections) {
        currentSocketConnections.filter { it.parasiteId == parasiteId }
    }

    private fun getOtherConnections(excludeParasite: String) = synchronized(currentSocketConnections) {
        currentSocketConnections.filterNot { it.parasiteId == excludeParasite }
    }

    fun broadcastToParasite(parasiteId: EntityID<String>, data: Any) {
        broadcastToParasite(parasiteId.value, data)
    }

    fun broadcastToParasite(parasiteId: String, data: Any) {
        broadcast(getConnectionsForParasite(parasiteId).toList(), data)
    }

    fun broadcastToOthers(excludeParasite: EntityID<String>, data: Any) = broadcastToOthers(excludeParasite.value, data)
    fun broadcastToOthers(excludeParasite: String, data: Any) {
        broadcast(getOtherConnections(excludeParasite).toList(), data)
    }

    fun broadcastToRoom(room: Rooms.RoomObject, data: Any) {
        broadcast(getConnectionsForRoom(room).toList(), data)
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

enum class ServerMessageTypes(val value: String) {
    Alert("alert"),
    Update("update"),
    AuthFail("auth fail"),
    UserList("user list"),
    RoomList("room data"),
    Invitation("invitation"),
    PrivateMessageList("private message data"),
    ToolList("tool list"),
    ToolResponse("data response");

    override fun toString(): String {
        return this.value
    }
}

data class ServerMessage(val type: ServerMessageTypes, val data: Map<String, Any?>) {
    constructor(data: AlertData, id: EntityID<UUID>? = null) : this(
        ServerMessageTypes.Alert,
        buildMap {
            id?.let { put("id", it) }
            putAll(data.toMap())
        }
    )

    constructor(roomId: EntityID<Int>, invitations: List<RoomInvitations.RoomInvitationObject>) : this(
        ServerMessageTypes.Invitation,
        buildMap {
            invitations.filter { it.room == roomId }.let {
                val roomName = it.first().roomName
                val senderList = if (invitations.size == 1) {
                    it.first().senderName
                } else {
                    it.chunked(invitations.size - 1) { it.joinToString { it.senderName } }.joinToString(" and ")
                }
                put("room id", roomId)
                put("message", "You've been invited to '$roomName' by $senderList.")
            }
        })
}
