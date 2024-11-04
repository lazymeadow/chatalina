package net.chatalina.chat

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.headObject
import aws.sdk.kotlin.services.s3.model.ObjectCannedAcl
import aws.sdk.kotlin.services.s3.model.S3Exception
import aws.sdk.kotlin.services.s3.putObject
import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.http.HttpStatusCode
import aws.smithy.kotlin.runtime.http.response.statusCode
import com.fasterxml.jackson.core.exc.StreamConstraintsException
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.module.kotlin.convertValue
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
import kotlinx.datetime.Instant
import net.chatalina.chat.ParasiteListObject.Companion.toListObject
import net.chatalina.database.*
import net.chatalina.database.AlertData.Companion.toMap
import net.chatalina.emoji.EmojiManager
import net.chatalina.http.AuthenticationException
import net.chatalina.plugins.ChatSocketConnection
import net.chatalina.plugins.defaultMapper
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
import aws.smithy.kotlin.runtime.net.url.Url as AwsUrl


data class ParasiteListObject(
    val id: EntityID<String>,
    val email: String,
    val lastActive: Instant?,
    val status: ParasiteStatus,
    val typing: String?,
    val username: String,
    val faction: String,
    val color: String,
    val permission: ParasitePermissions,
    val soundSet: String,
    val volume: String
) {
    companion object {
        fun Parasites.ParasiteObject.toListObject(status: ParasiteStatus, typing: String?) = ParasiteListObject(
            id,
            email,
            lastActive,
            status,
            typing,
            name,
            settings.faction,
            settings.color,
            settings.permission,
            settings.soundSet,
            settings.volume
        )
    }
}

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
    private lateinit var imageCacheSettings: ImageCacheSettings
    private lateinit var githubUser: String
    private lateinit var githubToken: String
    private lateinit var githubRepo: String
    private var gorillaGrooveHost: URI? = null

    val currentSocketConnections: MutableSet<ChatSocketConnection> = Collections.synchronizedSet(LinkedHashSet())

    class ImageCacheSettings(
        val bucket: String,
        val host: String,
        val endpoint: String?,
        val accessKey: String,
        val secret: String,
        val region: String
    )

    fun configure(
        imageCacheSettings: ImageCacheSettings,
        githubUser: String,
        githubToken: String,
        githubRepo: String,
        gorillaGrooveHost: String?
    ) {
        logger.debug("Initializing Chat Manager...")

        logger.debug("Initializing variables...")
        this.imageCacheSettings = imageCacheSettings
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
            s3Client = S3Client {
                forcePathStyle = false
                imageCacheSettings.endpoint?.let { endpointUrl = AwsUrl.parse(it) }
                region = imageCacheSettings.region
                credentialsProvider = StaticCredentialsProvider.invoke {
                    this.accessKeyId = imageCacheSettings.accessKey
                    this.secretAccessKey = imageCacheSettings.secret
                }
            }
        }

        logger.debug("Chat Manager initialized.")
    }

    fun buildParasiteList(): List<ParasiteListObject> = Parasites.DAO.list(active = true)
        .map { it.toListObject(ParasiteStatusMap.getStatus(it.id), ParasiteStatusMap.getTyping(it.id)) }
        .sortedBy { it.status }


    private fun sendRoomList(parasiteId: EntityID<String>, room: Rooms.RoomObject?) =
        sendRoomList(parasiteId.value, room)

    private fun sendRoomList(parasiteId: String, room: Rooms.RoomObject?) {
        val data = room?.let {
            mapOf("rooms" to listOf(it), "all" to false)
        } ?: mapOf("rooms" to Rooms.DAO.list(parasiteId), "all" to true)
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
        } else {
            throw BadRequestException("Invalid destination")
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
        } ?: throw BadRequestException("Invalid destination")
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
                    bucket = imageCacheSettings.bucket
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
                    this.bucket = imageCacheSettings.bucket
                    this.key = objectKey
                    this.acl = ObjectCannedAcl.PublicRead
                    this.body = ByteStream.fromBytes(imageContent)
                    this.contentType = imageContentType?.toString()
                }
            }
            "${imageCacheSettings.host}/$objectKey"
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
            } else {
                throw BadRequestException("Invalid destination")
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
        } ?: let {
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
            } else {
                throw BadRequestException("Invalid destination")
            }
        }
    }

    fun handleCreateRoom(connection: ChatSocketConnection, parasite: Parasites.ParasiteObject, name: String?) {
        val newRoom = Rooms.DAO.create(parasite.id, name ?: "${parasite.name}'s Room")
        sendRoomList(connection.parasiteId, newRoom)
    }

    fun handleDeleteRoom(connection: ChatSocketConnection, parasite: Parasites.ParasiteObject, roomId: Int) {
        val room = Rooms.DAO.find(roomId) ?: throw BadRequestException("Invalid room id")
        if (!room.members.contains(connection.parasiteId)) {
            throw BadRequestException("Invalid room id")
        }
        if (room.owner != parasite.id) {
            throw BadRequestException("Nice try, but you are not the room owner.")
        }
        Rooms.DAO.delete(room.id)
        room.members.forEach {
            // send updated room list & alert to all former member connections
            sendRoomList(it, null)
            broadcastToParasite(
                it,
                ServerMessage(AlertData.dismiss("Room '${room.name}' has been deleted.", "Oh, darn"))
            )
        }
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
        if (accept) {
            val room = Rooms.DAO.find(roomId) ?: throw BadRequestException("Invalid invitation")
            val existingInvitations = RoomInvitations.DAO.list(parasite.id, room.id)
            if (existingInvitations.isEmpty()) {
                throw BadRequestException("Invalid invitation")
            }
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
            val room = Rooms.DAO.find(roomId) ?: return
            val existingInvitations = RoomInvitations.DAO.list(parasite.id, room.id)
            if (existingInvitations.isEmpty()) {
                return
            }
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


    fun handleLeaveRoom(connection: ChatSocketConnection, parasite: Parasites.ParasiteObject, roomId: Int) {
        val room = Rooms.DAO.find(roomId) ?: throw BadRequestException("You can't leave that room!")
        if (!room.members.contains(connection.parasiteId)) {
            throw BadRequestException("You can't leave that room!")
        }
        val updatedRoom = Rooms.DAO.removeMember(room.id, parasite.id)
                ?: throw BadRequestException("Something went wrong when you tried to leave room '${room.name}'.")

        updatedRoom.members.forEach {
            sendRoomList(it, updatedRoom)
            val alertData = AlertData.dismiss("${parasite.name} has left '${updatedRoom.name}'.", "Byeee")
            Alerts.DAO.create(it, alertData).also { a ->
                broadcastToParasite(it, ServerMessage(alertData, a?.id))
            }
        }
        sendRoomList(parasite.id, null)
        broadcastToParasite(parasite.id, ServerMessage(AlertData.fade("You left the room '${updatedRoom.name}'.")))
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
            if (ParasitePermissions.permissionLevelAccess(definition.accessLevel, sender.settings.permission)) {
                ServerMessage(definition, definition.dataFunction(sender))
            } else {
                ServerMessage(definition, null, error = "Insufficient permissions")
            }.also { msg ->
                connection.send(msg)
            }
        }
    }

    fun handleToolRunRequest(
        connection: ChatSocketConnection,
        sender: Parasites.ParasiteObject,
        toolId: String,
        data: Any?
    ) {
        toolDefinitions.find { it.id == toolId }?.let { definition ->
            if (ParasitePermissions.permissionLevelAccess(definition.accessLevel, sender.settings.permission)) {
                logger.debug("Executing tool {} for parasite {}", toolId, sender.id)

                when (definition.type) {
                    ToolTypes.Grant -> {
                        val newPermission = definition.grant

                        data class GrantToolData(val parasite: String)

                        val requestData = data?.let { defaultMapper.convertValue<GrantToolData>(data) }
                                ?: throw BadRequestException("No data")
                        val parasite = Parasites.DAO.find(requestData.parasite)
                                ?: throw BadRequestException("Missing parasite for grant tool")
                        val resultData = definition.runTool(parasite)
                        val newToolData = definition.dataFunction(sender)
                        connection.send(ServerMessage(definition, newToolData))
                        connection.send(ServerMessage(ServerMessageTypes.ToolConfirm, resultData))
                        broadcastToParasite(
                            parasite.id,
                            mapOf("type" to ServerMessageTypes.Update, "data" to mapOf("permission" to newPermission))
                        )
                        definition.affectedAlert?.let { alertData ->
                            Alerts.DAO.create(parasite.id, alertData).also { a ->
                                broadcastToParasite(parasite.id, ServerMessage(alertData, a?.id))
                            }
                        }
                    }
                    ToolTypes.Room -> {
                        data class RoomToolData(val room: Int)

                        val requestData = data?.let { defaultMapper.convertValue<RoomToolData>(data) }
                                ?: throw BadRequestException("No data")

                        val room =
                            Rooms.DAO.find(requestData.room) ?: throw BadRequestException("Missing room for tool")

                        val canRunTool = definition.accessLevel == ParasitePermissions.Admin ||
                                (definition.accessLevel == ParasitePermissions.Mod && room.members.contains(sender.id.value))

                        if (canRunTool) {
                            val resultData = definition.runTool(room)
                            val newToolData = definition.dataFunction(sender)
                            connection.send(ServerMessage(definition, newToolData))
                            connection.send(ServerMessage(ServerMessageTypes.ToolConfirm, resultData))
                            broadcastToRoom(
                                room,
                                ServerMessage(
                                    ServerMessageTypes.RoomList,
                                    mapOf("rooms" to listOf(room), "all" to false, "clear log" to true)
                                )
                            )
                        } else {
                            connection.send(ServerMessage(definition, null, error = "Insufficient permissions"))
                            connection.session.application.sendErrorEmail("Someone tried to use a tool they don't have permission to access!\noffending parasite: ${sender.id}\nattempted tool: ${toolId}")
                        }
                    }
                    ToolTypes.RoomOwner -> {
                        data class RoomOwnerToolData(val room: Int, val parasite: String)

                        val requestData = data?.let { defaultMapper.convertValue<RoomOwnerToolData>(data) }
                                ?: throw BadRequestException("No data")
                        val newOwner = Parasites.DAO.find(requestData.parasite)
                                ?: throw BadRequestException("Invalid parasite id for room")
                        val room =
                            Rooms.DAO.find(requestData.room) ?: throw BadRequestException("Missing room for tool")

                        if (room.owner == newOwner.id) {
                            connection.send(
                                ServerMessage(
                                    ServerMessageTypes.ToolConfirm,
                                    mapOf("message" to "No change", "perm level" to definition.accessLevel)
                                )
                            )
                            return
                        }

                        val canRunTool = definition.accessLevel == ParasitePermissions.Admin ||
                                (definition.accessLevel == ParasitePermissions.Mod && room.members.contains(sender.id.value))

                        if (canRunTool) {
                            if (room.members.contains(requestData.parasite)) {
                                val resultData = definition.runTool(room to newOwner)
                                val newToolData = definition.dataFunction(sender)
                                connection.send(ServerMessage(definition, newToolData))
                                connection.send(ServerMessage(ServerMessageTypes.ToolConfirm, resultData))
                                broadcastToRoom(
                                    room,
                                    ServerMessage(
                                        ServerMessageTypes.RoomList,
                                        mapOf("rooms" to listOf(room.copy(owner = newOwner.id)), "all" to false)
                                    )
                                )
                                AlertData.dismiss(
                                    "You are no longer the owner of the room '${room.name}'.",
                                    "Aww, nuts"
                                ).also { alertData ->
                                    Alerts.DAO.create(room.owner, alertData).also { a ->
                                        broadcastToParasite(room.owner, ServerMessage(alertData, a?.id))
                                    }
                                }
                                AlertData.dismiss("You are now the owner of the room '${room.name}'.", "Neat")
                                    .also { alertData ->
                                        Alerts.DAO.create(newOwner.id, alertData).also { a ->
                                            broadcastToParasite(newOwner.id, ServerMessage(alertData, a?.id))
                                        }
                                    }
                            } else {
                                connection.send(ServerMessage(definition, null, error = "Invalid parasite id for room"))
                            }
                        } else {
                            connection.send(ServerMessage(definition, null, error = "Insufficient permissions"))
                            connection.session.application.sendErrorEmail("Someone tried to use a tool they don't have permission to access!\noffending parasite: ${sender.id}\nattempted tool: ${toolId}")
                        }

                    }
                    ToolTypes.ParasiteActiveState -> {
                        data class ParasiteToolData(val parasite: String)

                        val requestData = data?.let { defaultMapper.convertValue<ParasiteToolData>(data) }
                                ?: throw BadRequestException("No data")
                        if (requestData.parasite == connection.parasiteId) {
                            throw BadRequestException("You can't do that to yourself!")
                        }
                        val parasite = Parasites.DAO.find(requestData.parasite)
                                ?: throw BadRequestException("Invalid parasite id")
                        val resultData = definition.runTool(parasite)
                        // force logout for parasite's connected sockets
                        broadcastToParasite(parasite.id, ServerMessage.AuthFail())
                        connection.send(ServerMessage(ServerMessageTypes.ToolConfirm, resultData))
                    }
                    ToolTypes.Data -> {
                        data class DataToolData(val id: String)

                        val requestData = data?.let { defaultMapper.convertValue<DataToolData>(data) }
                                ?: throw BadRequestException("No data")
                        val resultData = definition.runTool(requestData.id)
                        connection.send(ServerMessage(ServerMessageTypes.ToolConfirm, resultData))
                    }
                }
            } else {
                connection.send(ServerMessage(definition, null, error = "Insufficient permissions"))
                connection.session.application.sendErrorEmail("Someone tried to use a tool they don't have permission to access!\noffending parasite: ${sender.id}\nattempted tool: ${toolId}")
            }
        }
    }

    fun addConnection(connection: ChatSocketConnection) {
        val parasite = Parasites.DAO.find(connection.parasiteId) ?: throw AuthenticationException()
        if (!parasite.active) {
            // deactivated parasites aren't allowed to do anything
            throw AuthenticationException()
        }
        synchronized(currentSocketConnections) { currentSocketConnections.add(connection) }

        val wasOffline = ParasiteStatusMap.getStatus(parasite.id) == ParasiteStatus.Offline

        Parasites.DAO.setLastActive(parasite.id)
        ParasiteStatusMap.setStatus(parasite.id, ParasiteStatus.Active)
        broadcastUserList()

        sendRoomList(parasite.id, null)
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
        if (!currentParasite.active) {
            // deactivated parasites aren't allowed to do anything
            throw AuthenticationException()
        }
        val messageBody = try {
            defaultMapper.readValue<MessageBody>(body)
        } catch (e: Throwable) {
            if (e is  JsonMappingException && e.cause is StreamConstraintsException) {
                throw BadRequestException("Content is too long")
            } else {
                connection.session.application.sendErrorEmail(e)
                throw BadRequestException("Bad message content")
            }
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
    ToolResponse("data response"),
    ToolConfirm("tool confirm");

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

    constructor(
        toolDefinition: ToolDefinition<*, *>,
        toolData: Any?,
        message: String? = null,
        error: String? = null
    ) : this(ServerMessageTypes.ToolResponse, buildMap {
        put("request", toolDefinition.id)
        error?.let {
            put("tool info", null)
            put("data", null)
            put("error", error)
        } ?: let {
            put("tool info", toolDefinition)
            put("data", toolData)
            put("message", message)
        }
    })

    companion object {
        fun AuthFail() = ServerMessage(
            ServerMessageTypes.AuthFail,
            mapOf(
                "username" to "Server",
                "message" to "Cannot connect. Authentication failure!",
                "time" to java.time.Instant.now()
            )
        )
    }
}
