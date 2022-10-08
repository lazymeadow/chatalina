package net.chatalina.plugins

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.server.application.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.config.*
import io.ktor.util.*
import io.ktor.websocket.*
import kotlinx.coroutines.runBlocking
import net.chatalina.chat.*
import net.chatalina.database.*
import net.chatalina.jsonrpc.InvalidEncryptedRequestException
import net.chatalina.jsonrpc.JsonRpcCallBody
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.javatime.timestampLiteral
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.PublicKey
import java.time.Instant
import java.util.*

private inline fun <reified T> readMessageContent(
    message: MessageContent,
    mapper: JsonMapper,
    publicKey: PublicKey,
    decryptor: (String, String, PublicKey) -> ByteArray
): T {
    val (iv, content) = message
    val decrypted = decryptor(content, iv, publicKey)
    return mapper.readValue(decrypted)
}

private fun adjustDestination(dests: List<JID>, parasiteJID: JID, msg: MessageData): MessageData {
    val dest = if (dests.size == 2) {
        dests.firstOrNull { it != parasiteJID }
    } else {
        null
    }
    return if (dest != null) {
        MessageData(dest, msg.sender, msg.type, msg.message, msg.time, msg.id)
    } else {
        msg
    }
}

class ChatHandler(
    configuration: PluginConfiguration,
    private val encryption: Encryption,
    private val mapper: JsonMapper,
    private val becAuth: BecAuthentication
) {
    val currentSocketConnections: MutableSet<ChatSocketConnection> = Collections.synchronizedSet(LinkedHashSet())
    private val jidDomain = configuration.jidDomain

    class PluginConfiguration {
        lateinit var jidDomain: String
    }

    private fun serializeToSend(valToSend: Any): String {
        return mapper.writeValueAsString(valToSend)
    }

    suspend fun sendToConnection(connection: ChatSocketConnection, data: Any) {
        connection.send(serializeToSend(data))
    }

    fun sendEncryptedToConnection(connection: ChatSocketConnection, type: ServerMethodTypes, data: Any) {
        with(connection) {
            publicKey?.let { pubKey ->
                // socket sending is a suspend function. send it off, but keep processing our synchronized list of connections
                launchForSocket {
                    // then we need to re-encrypt it for sending to any relevant connections
                    val serialized = serializeToSend(
                        JsonRpcCallBody(
                            "2.0",
                            null,
                            type.toString(),
                            mapper.convertValue(encryptToSend(pubKey, mapper.writeValueAsBytes(data)))
                        )
                    )
                    send(serialized)
                    log.debug("encrypted and sent")
                }
            } ?: log.debug("not sending to $this because public key is missing")
        }
    }

    fun validateToken(token: String?): JWTPrincipal? {
        return token?.let { becAuth.validateJwt(it) }
    }


    private fun encryptToSend(publicKey: PublicKey, decrypted: ByteArray): MessageContent {
        val (nonce, encrypted) = encryption.encryptEC(decrypted, publicKey)

        return MessageContent(
            Base64.getEncoder().encodeToString(nonce),
            Base64.getEncoder().encodeToString(encrypted)
        )
    }

    fun getMessages(message: MessageContent?, publicKey: PublicKey, parasite: Parasite): List<MessageContent> {
        val parasiteJID = parasite.getJID(jidDomain)
        // if message content is present, it'll have filters.
        val filters = message?.let {
            readMessageContent<MessageFilters>(message, mapper, publicKey, encryption::decryptEC)
        }
        return transaction {
            // to find the jids that we need to filter by, we've gotta check our filter.
            val messageFilter = filters?.jid?.let { filterJID ->
                if (filterJID.isGroup) {
                    // just include group id from jid in query. if its a forbidden jid, they wont get messages for it.
                    val isAllowed = GroupParasites.doesParasiteHaveAccess(parasite, filterJID.id)
                    Messages.destinations eq (if (isAllowed) arrayOf(filterJID.toString()) else arrayOf())
                } else if (filterJID == parasiteJID) {
                    // filtering only to messages between them and themself
                    Messages.destinations eq arrayOf(parasiteJID.toString())
                } else if (filterJID.isParasite) {
                    // filter only to messages that are between them and the other parasite
                    Messages.destinations eq arrayOf(parasiteJID.toString(), filterJID.toString())
                } else {
                    // filter to nothing.
                    Op.FALSE
                }
            } ?: let {
                // if no filter, use the parasite's current groups + any destinations that include their id.
                val groupJIDs = GroupParasites.slice(GroupParasites.group).select {
                    (GroupParasites.parasite eq parasite.id) and (GroupParasites.role neq GroupRoles.NONE)
                }.map { JID(DestinationType.GROUP, it[GroupParasites.group].value, jidDomain) }
                Messages.destinations overlaps (groupJIDs + parasiteJID).map { it.toString() }.toTypedArray()
            }

            Messages.slice(Messages.id, Messages.data, Messages.created, Messages.destinations)
                .select { messageFilter }
                .orderBy(Messages.created, SortOrder.DESC)
                .limit(100)
                .toList()
        }.map {
            // 1. db decrypt
            val (iv, content) = mapper.readValue<MessageContent>(it[Messages.data])
            val decrypted = encryption.decryptDB(content, iv)
            val msg = mapper.readValue<MessageData>(decrypted)
            msg.id = it[Messages.id].value
            msg.time = msg.time ?: it[Messages.created]  // to support older messages in beta
            val dests = it[Messages.destinations].map { jid -> JID.parseJID(jid) }
            val messageToEncrypt = adjustDestination(dests, parasiteJID, msg)

            // 2. pub key encrypt, serialize
            encryptToSend(publicKey, mapper.writeValueAsBytes(messageToEncrypt))
        }
    }

    fun processNewMessage(message: MessageContent, publicKey: PublicKey, parasite: Parasite): MessageContent {
        // 1. decrypt message
        val processedMessage = readMessageContent<MessageData>(message, mapper, publicKey, encryption::decryptEC)
        val destJID = processedMessage.destination
        if (destJID.domain != jidDomain) {
            throw IllegalArgumentException("Bad destination JID")
        }
        val destinationsToSave = when {
            destJID.isGroup -> {
                val allowed = transaction { GroupParasites.doesParasiteHaveAccess(parasite, destJID.id) }
                if (allowed) listOf(destJID) else throw AuthorizationException()
            }
            destJID.isParasite -> {
                // for now, anyone can send private messages to anyone else, so no allowed check
                if (destJID == parasite.getJID(jidDomain)) {
                    listOf(destJID)  // sending message to self
                } else {
                    listOf(destJID, parasite.getJID(jidDomain))
                }
            }
            else -> throw AuthorizationException()
        }

        val now = Instant.now()
        processedMessage.time = now

        val (nonce, encrypted) = encryption.encryptDB(mapper.writeValueAsBytes(processedMessage))
        processedMessage.id = transaction {
            Messages.insertAndGetId {
                it[destinations] = destinationsToSave.map { it.toString() }.toTypedArray()
                it[data] = mapper.writeValueAsString(
                    MessageContent(nonce, encrypted)
                )
                it[created] = now
                it[updated] = now
            }
        }.value

        // 3. for every appropriate socket, encrypt and send
        val recipientJids = transaction {
            if (destJID.isGroup) {
                GroupParasites.innerJoin(Parasites).slice(Parasites.jid).select {
                    (GroupParasites.group eq destJID.id) and (GroupParasites.role neq GroupRoles.NONE)
                }.map {
                    JID(DestinationType.PARASITE, it[Parasites.jid], jidDomain)
                }
            } else {
                listOf(parasite.getJID(jidDomain), destJID)
            }
        }

        synchronized(currentSocketConnections) {
            // Must be in the synchronized block
            val i: Iterator<ChatSocketConnection> = currentSocketConnections
                .filter { it.isParasiteSet() && recipientJids.contains(it.parasite.getJID(jidDomain)) }
                .iterator()
            i.forEach {
                sendEncryptedToConnection(
                    it,
                    ServerMethodTypes.NEW_MESSAGE,
                    // we need to adjust for private messages with multiple destinations
                    adjustDestination(destinationsToSave, it.parasite.getJID(jidDomain), processedMessage)
                )
            }
        }
        return mapper.convertValue(encryptToSend(publicKey, mapper.writeValueAsBytes(processedMessage)))
    }

    private fun getParasites(): List<ParasiteObject> {
        // parasite list is not encrypted
        return transaction {
            Parasites.slice(Parasites.jid, Parasites.displayName)
                .select { Parasites.active eq true }
                .map {
                    val parasiteJid = JID(DestinationType.PARASITE, it[Parasites.jid], jidDomain)
                    ParasiteObject(parasiteJid, it[Parasites.displayName])
                }
        }
    }

    private fun getGroups(parasite: Parasite): List<GroupObject> {
        // parasite list is not encrypted
        val parasiteJidsList: ExpressionAlias<Array<Int>> = Parasites.jid.intArrayAgg().alias("jids")
        val parasiteJidsQuery = GroupParasites.innerJoin(Parasites, { GroupParasites.parasite }, { Parasites.id })
            .slice(GroupParasites.group, parasiteJidsList)
            .select { GroupParasites.role neq GroupRoles.NONE }
            .andWhere { Parasites.active eq true }
            .groupBy(GroupParasites.group)
            .alias("p")

        return transaction {
            Groups.innerJoin(parasiteJidsQuery, { id }, { parasiteJidsQuery[GroupParasites.group] })
                .slice(Groups.id, Groups.name, parasiteJidsQuery[parasiteJidsList])
                .selectAll()
                .andWhere { parasiteJidsQuery[parasiteJidsList] any intParam(parasite.jidInt) }
                .map {
                    val groupJID = JID(DestinationType.GROUP, it[Groups.id].value, jidDomain)
                    val jidList: Array<Int> = it[parasiteJidsQuery[parasiteJidsList]]
                    GroupObject(
                        groupJID,
                        it[Groups.name],
                        jidList.map { jid ->
                            JID(DestinationType.PARASITE, jid, jidDomain)
                        })
                }
        }
    }

    fun getDestinations(publicKey: PublicKey, parasite: Parasite): MessageContent {
        val parasites = getParasites()
        val groups = getGroups(parasite)

        return mapper.convertValue(
            encryptToSend(
                publicKey,
                mapper.writeValueAsBytes(mapOf("parasites" to parasites, "groups" to groups))
            )
        )
    }

    fun createGroup(message: MessageContent, publicKey: PublicKey, parasite: Parasite): MessageContent {
        // 1. make the group
        val groupData = readMessageContent<CreateGroupData>(message, mapper, publicKey, encryption::decryptEC)
        transaction {
            val id = Groups.insertAndGetId {
                it[name] = groupData.name
            }
            GroupParasites.insert {
                it[group] = id
                it[GroupParasites.parasite] = parasite.id
                it[role] = GroupRoles.ADMIN
            }
        }
        // any update requires sending the entire updated list - no partial updates
        val newGroupList = getGroups(parasite)

        synchronized(currentSocketConnections) {
            // Must be in the synchronized block
            val i: Iterator<ChatSocketConnection> = currentSocketConnections
                .filter { it.isParasiteSet() && it.parasite.id == parasite.id }
                .iterator()
            i.forEach {
                sendEncryptedToConnection(it, ServerMethodTypes.UPDATE_DESTINATIONS, mapOf("groups" to newGroupList))
            }
        }
        // we even return the fully updated group list to the caller.
        return mapper.convertValue(encryptToSend(publicKey, mapper.writeValueAsBytes(newGroupList)))
    }

    fun updateGroup(message: MessageContent, publicKey: PublicKey, parasite: Parasite): MessageContent {
        val updateData = readMessageContent<UpdateGroupData>(message, mapper, publicKey, encryption::decryptEC)
        if (
        // you're not allowed to make group updates on something thats not a group
            !updateData.jid.isGroup
            // you're also not allowed to make group updates if your member updates aren't parasites
            || !updateData.updates.fold(true) { acc, groupMemberData -> acc && groupMemberData.jid.isParasite }
        ) {
            throw AuthorizationException()
        }
        // if changing name or granting permissions, must be admin. otherwise, must be mod - they can add/remove members only.
        val adminOnly = updateData.name.isNotBlank()
                || updateData.updates.fold(false) { acc, update -> acc || update.role > GroupRoles.MEMBER }
        val isAllowed = transaction {
            // this checks for a real group - if it doesnt exist, they wont have access
            GroupParasites.doesParasiteHaveAccess(
                parasite,
                updateData.jid.id,
                if (adminOnly) GroupRoles.ADMIN else GroupRoles.MOD
            )
        }
        if (!isAllowed) {
            throw AuthorizationException()
        }

        if (updateData.name.isBlank() && updateData.updates.isEmpty()) {
            // if there's no updates, just return the current group list to the caller.
            return mapper.convertValue(
                encryptToSend(
                    publicKey,
                    mapper.writeValueAsBytes(mapOf("groups" to getGroups(parasite)))
                )
            )
        }

        val parasitesAffected = transaction {
            val updateTime = Instant.now()
            if (updateData.updates.isNotEmpty()) {
                // UH OH: if you are trying to downgrade an admin, there has to be another admin ALREADY THERE.
                val currentAdmins = GroupParasites.getAdmins(updateData.jid.id).map { it.getJID(jidDomain) }
                val downgradingAdmins = updateData.updates
                    .filter { update -> currentAdmins.contains(update.jid) && update.role < GroupRoles.ADMIN }
                if (downgradingAdmins.size == currentAdmins.size) {
                    val errorResponse =
                        encryptToSend(publicKey, "Unable to remove all admins from group.".toByteArray())
                    throw InvalidEncryptedRequestException(errorResponse)
                }

                // now the updates are ok, so lets do it
                updateData.updates.forEach { update ->
                    // can't upsert with select... ?
                    GroupParasites.upsert(
                        Parasites.slice(
                            intLiteral(updateData.jid.id),
                            Parasites.id,
                            stringLiteral(update.role.toString()),
                            timestampLiteral(updateTime)
                        ).select { Parasites.jid eq update.jid.id },
                        columns = listOf(
                            GroupParasites.group,
                            GroupParasites.parasite,
                            GroupParasites.role,
                            GroupParasites.updated
                        )
                    )
                }
            }
            if (updateData.name.isNotBlank()) {
                Groups.update({ Groups.id eq updateData.jid.id }) {
                    it[name] = updateData.name
                    it[updated] = updateTime
                }
            }

            // get whatever the member list is now
            val newMembers = Groups.innerJoin(GroupParasites).innerJoin(Parasites).slice(Parasites.jid)
                .selectAll()
                .andWhere { GroupParasites.group eq updateData.jid.id }
                .andWhere { GroupParasites.role neq GroupRoles.NONE }
                .map { JID(DestinationType.PARASITE, it[Parasites.jid], jidDomain) }
            // we need to update not just the new members, but also any that were removed.
            (newMembers + updateData.updates.map { it.jid }).toSet()
        }

        // update all the sockets
        synchronized(currentSocketConnections) {
            // Must be in the synchronized block
            currentSocketConnections.filter { it.isParasiteSet() && it.parasite.getJID(jidDomain) in parasitesAffected }
                .groupBy { it.parasite }
                .forEach { theParasite, connections ->
                    val updatedGroups = mapOf("groups" to getGroups(theParasite))
                    connections.forEach {
                        sendEncryptedToConnection(it, ServerMethodTypes.UPDATE_DESTINATIONS, updatedGroups)
                    }
                }
        }

        return mapper.convertValue(
            encryptToSend(
                publicKey,
                mapper.writeValueAsBytes(mapOf("groups" to getGroups(parasite)))
            )
        )
    }

    fun updateSettings(message: MessageContent, publicKey: PublicKey, parasite: Parasite): MessageContent {
        // 1. get decrypted request data
        val updateRequest = readMessageContent<UpdateParasiteData>(message, mapper, publicKey, encryption::decryptEC)
        // 2. update given fields. displayName is directly on the parasite, anything else is in the settings table.
        if (!updateRequest.displayName.isNullOrBlank()) {
            transaction {
                parasite.displayName = updateRequest.displayName
            }
        }
        val responseParasite = ParasiteObject(parasite.getJID(jidDomain), parasite.displayName)

        // 3. send update to all sockets
        synchronized(currentSocketConnections) {
            // Must be in the synchronized block
            currentSocketConnections.forEach {
                sendEncryptedToConnection(
                    it,
                    ServerMethodTypes.UPDATE_DESTINATIONS,
                    mapOf("parasites" to listOf(responseParasite))
                )
            }
        }
        // 4. return updated settings to requester
        return getSettings(publicKey, parasite)
    }

    fun getSettings(publicKey: PublicKey, parasite: Parasite): MessageContent {
        val settings = mapOf(
            "displayName" to parasite.displayName,
            "jid" to parasite.getJID(jidDomain)
        )
        return mapper.convertValue(encryptToSend(publicKey, mapper.writeValueAsBytes(settings)))
    }

    companion object Feature : BaseApplicationPlugin<Application, PluginConfiguration, ChatHandler> {
        override val key = AttributeKey<ChatHandler>("chatHandler")

        override fun install(pipeline: Application, configure: PluginConfiguration.() -> Unit): ChatHandler {
            val configuration = PluginConfiguration().apply(configure)
            val encryption = pipeline.encryption
            val jacksonMapper = pipeline.jacksonMapper
            val log = pipeline.log
            val becAuth = pipeline.environment.becAuth
                    ?: throw ApplicationConfigurationException("Security must be configured before chat handler")

            val chatHandler = ChatHandler(configuration, encryption, jacksonMapper, becAuth)

            pipeline.environment.monitor.subscribe(ApplicationStopPreparing) {
                log.debug("Closing socket connections...")
                synchronized(chatHandler.currentSocketConnections) {
                    chatHandler.currentSocketConnections.forEach {
                        runBlocking {
                            log.debug("Closing socket ${it.name}")
                            it.session.close(CloseReason(CloseReason.Codes.GOING_AWAY, "Server shutting down"))
                            log.debug("Socket ${it.name} closed")
                        }
                    }
                }
                log.debug("Sockets closed.")
            }

            return chatHandler
        }
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
private data class MessageData(
    val destination: JID,
    val sender: JID,
    val type: String,
    val message: Any,
    var time: Instant?,
    var id: UUID? = null
)

private data class UpdateParasiteData(
    val displayName: String?
)

private data class CreateGroupData(
    val name: String
)

private data class GroupMemberData(
    val jid: JID,
    val role: GroupRoles = GroupRoles.MEMBER  // default is add
)

private data class UpdateGroupData(
    val jid: JID,
    val name: String = "",
    val updates: List<GroupMemberData> = listOf()
)

fun Application.configureChatHandler() {
    install(ChatHandler) {
        jidDomain = environment.config.property("bec.jid_domain").getString()
    }
}

val Application.chatHandler: ChatHandler
    get() = this.plugin(ChatHandler)
