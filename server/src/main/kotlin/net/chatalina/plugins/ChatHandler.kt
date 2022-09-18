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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.chatalina.chat.*
import net.chatalina.database.*
import net.chatalina.jsonrpc.Request
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.Logger
import java.security.PublicKey
import java.time.Instant
import java.util.*

class ChatHandler(
    configuration: PluginConfiguration,
    private val encryption: Encryption,
    private val mapper: JsonMapper,
    private val becAuth: BecAuthentication,
    private val log: Logger
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

    fun validateToken(token: String?): JWTPrincipal? {
        return token?.let { becAuth.validateJwt(it) }
    }

    private fun adjustDestination(dests: Array<String>, parasiteJID: JID, msg: MessageData): MessageData {
        val dest = if (dests.size == 2) {
            dests.firstOrNull { it != parasiteJID.toString() }
        } else {
            null
        }
        return if (dest != null) {
            MessageData(dest, msg.sender, msg.type, msg.message, msg.time, msg.id)
        } else {
            msg
        }
    }

    private fun adjustDestination(dests: Array<JID>, parasiteJID: JID, msg: MessageData): MessageData {
        return adjustDestination(dests.map { it.toString() }.toTypedArray(), parasiteJID, msg)
    }

    fun getMessages(publicKey: PublicKey, parasite: Parasite): List<MessageContent> {
        val parasiteJID = JID(DestinationType.PARASITE, parasite.jid, jidDomain)
        return transaction {
            // find groups that this user is allowed to see messages
            val groupJIDs = GroupParasites.slice(GroupParasites.group).select {
                (GroupParasites.parasite eq parasite.id) and (GroupParasites.role neq GroupRoles.NONE)
            }.map { JID(DestinationType.GROUP, it[GroupParasites.group].value, jidDomain) }
            Messages.slice(Messages.id, Messages.data, Messages.created, Messages.destinations).select {
                Messages.destinations overlaps (groupJIDs + parasiteJID).map { it.toString() }.toTypedArray()
            }
                .orderBy(Messages.created, SortOrder.DESC)
                .limit(100)
                .toList()
        }.map {
            // 1. db decrypt
            val (iv, content) = mapper.readValue<MessageContent>(it[Messages.data])
            val decrypted = encryption.decryptDB(content, iv)
            val message = mapper.readValue<MessageData>(decrypted)
            message.id = it[Messages.id].value
            message.time = message.time ?: it[Messages.created]  // to support older messages in beta
            val messageToEncrypt = adjustDestination(it[Messages.destinations], parasiteJID, message)

            // 2. pub key encrypt, serialize
            encryptToSend(publicKey, mapper.writeValueAsBytes(messageToEncrypt))
        }
    }

    private fun encryptToSend(publicKey: PublicKey, decrypted: ByteArray): MessageContent {
        val (nonce, encrypted) = encryption.encryptEC(decrypted, publicKey)

        return MessageContent(
            Base64.getEncoder().encodeToString(nonce),
            Base64.getEncoder().encodeToString(encrypted)
        )
    }

    suspend fun processNewMessage(message: MessageContent, publicKey: PublicKey, parasite: Parasite): MessageContent {
        // 1. decrypt message
        val (iv, content) = message
        val decrypted = encryption.decryptEC(content, iv, publicKey)

        // 2. encrypt message for db & insert
        // 2.a. we need the destination for indexing and lookups, but it was encrypted
        val processedMessage = mapper.readValue<MessageData>(decrypted)
        val destJID = JID.parseJid(processedMessage.destination, jidDomain)
        val allowed = when (destJID.type) {
            DestinationType.PARASITE -> {
                true  // for now, anyone can send private messages to anyone else
            }

            DestinationType.GROUP -> {
                transaction {
                    GroupParasites.slice(GroupParasites.group).select {
                        (GroupParasites.parasite eq parasite.id) and (GroupParasites.group eq destJID.id) and (GroupParasites.role neq GroupRoles.NONE)
                    }.count() > 0
                }
            }
        }
        if (!allowed) {
            throw AuthorizationException()
        }
        val destinationsToSave = when (destJID.type) {
            DestinationType.GROUP -> {
                arrayOf(destJID)
            }

            DestinationType.PARASITE -> {
                if (destJID.id == parasite.jid) {
                    arrayOf(destJID)  // sending message to self
                } else {
                    arrayOf(destJID, JID(DestinationType.PARASITE, parasite.jid, jidDomain))
                }
            }
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
            if (destJID.type == DestinationType.GROUP) {
                GroupParasites.innerJoin(Parasites).slice(Parasites.jid).select {
                    (GroupParasites.group eq destJID.id) and (GroupParasites.role neq GroupRoles.NONE)
                }.map {
                    it[Parasites.jid]
                }
            } else {
                listOf(parasite.jid, destJID.id)
            }
        }
        synchronized(currentSocketConnections) {
            // Must be in the synchronized block
            val i: Iterator<ChatSocketConnection> = currentSocketConnections
                .filter { recipientJids.contains(it.parasite.jid) }
                .iterator()
            i.forEach {
                it.publicKey?.let { pubKey ->
                    log.debug("sending new message to ${it.name}")
                    // socket sending is a suspend function. send it off, but keep processing our synchronized list of connections
                    CoroutineScope(Dispatchers.Default).launch {
                        // we need to adjust for private messages with multiple destinations
                        val parasiteJID = JID(DestinationType.PARASITE, it.parasite.jid, jidDomain)
                        val messageToEncrypt = adjustDestination(destinationsToSave, parasiteJID, processedMessage)
                        // then we need to re-encrypt it for sending to any relevant connections
                        val serialized = serializeToSend(
                            Request(
                                "2.0",
                                null,
                                ServerMethodTypes.NEW_MESSAGE.toString(),
                                mapper.convertValue(encryptToSend(pubKey, mapper.writeValueAsBytes(messageToEncrypt)))
                            )
                        )
                        log.debug("encrypted and sending to ${it.name}")
                        it.send(serialized)
                    }
                } ?: log.debug("not sending to ${it.name} because public key is missing")
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
                    ParasiteObject(parasiteJid.toString(), it[Parasites.displayName])
                }
        }
    }

    private fun getGroups(parasite: Parasite): List<GroupObject> {
        // parasite list is not encrypted
        val parasiteJidsList: ExpressionAlias<Array<Int>> = Parasites.jid.intArrayAgg().alias("jids")
        val parasiteJidsQuery = GroupParasites.innerJoin(Parasites, { GroupParasites.parasite }, { Parasites.id })
            .slice(GroupParasites.group, parasiteJidsList)
            .selectAll()
            .andWhere { Parasites.active eq true }
            .groupBy(GroupParasites.group)
            .alias("p")

        return transaction {
            Groups.innerJoin(parasiteJidsQuery, { id }, { parasiteJidsQuery[GroupParasites.group] })
                .slice(Groups.id, Groups.name, parasiteJidsQuery[parasiteJidsList])
                .selectAll()
                .andWhere { parasiteJidsQuery[parasiteJidsList] any intParam(parasite.jid) }
                .map {
                    val groupJID = JID(DestinationType.GROUP, it[Groups.id].value, jidDomain)
                    val jidList: Array<Int> = it[parasiteJidsQuery[parasiteJidsList]]
                    GroupObject(
                        groupJID.toString(),
                        it[Groups.name],
                        jidList.map { jid ->
                            JID(DestinationType.PARASITE, jid, jidDomain).toString()
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

    fun createGroup(): Boolean {
        return false
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

            val chatHandler = ChatHandler(configuration, encryption, jacksonMapper, becAuth, log)

            pipeline.environment.monitor.subscribe(ApplicationStopPreparing) {
                log.debug("Closing socket connections...")
                synchronized(chatHandler.currentSocketConnections) {
                    chatHandler.currentSocketConnections.forEach {
                        log.debug("Closing socket ${it.name}")
                        runBlocking {
                            it.session.close(CloseReason(CloseReason.Codes.GOING_AWAY, "Server shutting down"))
                        }
                    }
                }
                log.debug("Sockets closed.")
            }

            return chatHandler
        }
    }
}


private enum class DestinationType() {
    PARASITE,
    GROUP
}

private class JID(val type: DestinationType, val id: Int, val domain: String) {
    companion object {
        fun parseJid(destination: String, domain: String): JID {
            /* allowed formats (to user or to group only):
                1@bec -> direct to a user
                bec/1 -> a whole group
               YES this is misusing the JID standard, NO i do not care. you're not my real dad
             */
            // split the string on the regex
            val parts = Regex("[@/]").split(destination)
            // now check how many parts we have. we only allow 2
            if (parts.size == 2) {
                // if 2 parts, we need to determine if its going to a user or a group
                if (parts.first() == domain && destination.contains("/")) {
                    val id = parts.last().toIntOrNull() ?: throw IllegalArgumentException("Bad destination JID")
                    return JID(DestinationType.GROUP, id, domain)
                } else if (parts.last() == domain && destination.contains("@")) {
                    val id = parts.first().toIntOrNull() ?: throw IllegalArgumentException("Bad destination JID")
                    return JID(DestinationType.PARASITE, id, domain)
                }
            }
            // anything else is wrong
            throw IllegalArgumentException("Bad destination JID")
        }
    }

    override fun toString(): String {
        return when (type) {
            DestinationType.PARASITE -> "${id}@${domain}"
            DestinationType.GROUP -> "${domain}/${id}"
        }
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
private data class MessageData(
    val destination: String,
    val sender: String,
    val type: String,
    val message: Any,
    var time: Instant?,
    var id: UUID? = null
)

fun Application.configureChatHandler() {
    install(ChatHandler) {
        jidDomain = environment.config.property("bec.jid_domain").getString()
    }
}

val Application.chatHandler: ChatHandler
    get() = this.plugin(ChatHandler)
