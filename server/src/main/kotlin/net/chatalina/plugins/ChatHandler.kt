package net.chatalina.plugins

import com.fasterxml.jackson.databind.json.JsonMapper
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
import net.chatalina.chat.ChatSocketConnection
import net.chatalina.chat.MessageContent
import net.chatalina.chat.MessageTypes
import net.chatalina.chat.ResponseBody
import net.chatalina.database.*
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.select
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

    fun getMessages(publicKey: PublicKey, parasite: Parasite): List<ResponseBody> {
        return transaction {
            // find groups that this user is allowed to see messages
            val parasiteJID = JID(DestinationType.PARASITE, parasite.jid, jidDomain)
            val groupJIDs = GroupParasites.slice(GroupParasites.group).select {
                (GroupParasites.parasite eq parasite.id) and (GroupParasites.role neq GroupRoles.NONE)
            }.map { JID(DestinationType.GROUP, it[GroupParasites.group].value, jidDomain) }
            // TODO: when we have 1-1 messages, how are we going to get the messages sent by this parasite?
            Messages.select { Messages.destination inList (groupJIDs + parasiteJID).map { it.toString() } }
                .orderBy(Messages.created, SortOrder.DESC)
                .limit(100)
                .map {
                    // 1. db decrypt
                    val (iv, content) = mapper.readValue<MessageContent>(it[Messages.data])
                    val decrypted = encryption.decryptDB(content, iv)
                    // 2. pub key encrypt, serialize
                    val (nonce, encrypted) = encryption.encryptEC(decrypted, publicKey)
                    ResponseBody(
                        it[Messages.id].value, MessageTypes.NEW_MESSAGE, MessageContent(
                            Base64.getEncoder().encodeToString(nonce), Base64.getEncoder()
                                .encodeToString(encrypted)
                        ), it[Messages.created]
                    )
                }
        }
    }

    private fun encryptToSend(messageId: UUID, publicKey: PublicKey, decrypted: ByteArray, time: Instant): String {
        val (nonce, encrypted) = encryption.encryptEC(
            decrypted,
            publicKey
        )
        return serializeToSend(
            ResponseBody(
                messageId,
                MessageTypes.NEW_MESSAGE,
                MessageContent(
                    Base64.getEncoder().encodeToString(nonce), Base64.getEncoder()
                        .encodeToString(encrypted)
                ),
                time
            )
        )
    }

    suspend fun processNewMessage(message: MessageContent, publicKey: PublicKey, parasite: Parasite): ResponseBody {
        // 1. decrypt message
        val (iv, content) = message
        val decrypted = encryption.decryptEC(content, iv, publicKey)

        // 2. encrypt message for db & insert
        // 2.a. we need the destination for indexing and lookups, but it was encrypted
        val dest = mapper.readValue<MessageData>(decrypted).destination
        val jid = JID.parseJid(dest, jidDomain)
        val allowed = when (jid.type) {
            DestinationType.PARASITE -> {
                true  // for now, anyone can send private messages to anyone else
            }
            DestinationType.GROUP -> {
                transaction {
                    GroupParasites.slice(GroupParasites.group).select {
                        (GroupParasites.parasite eq parasite.id) and (GroupParasites.group eq jid.id) and (GroupParasites.role neq GroupRoles.NONE)
                    }.count() > 0
                }
            }
        }
        if (!allowed) {
            throw AuthorizationException()
        }
        val (nonce, encrypted) = encryption.encryptDB(decrypted)
        val now = Instant.now()
        val messageId = transaction {
            Messages.insertAndGetId {
                it[destination] = dest
                it[data] = mapper.writeValueAsString(
                    MessageContent(
                        Base64.getEncoder().encodeToString(nonce),
                        Base64.getEncoder().encodeToString(encrypted)
                    )
                )
                it[created] = now
                it[updated] = now
            }
        }.value

        // 3. for every appropriate socket, encrypt and send
        val recipientJids = transaction {
            if (jid.type == DestinationType.GROUP) {
                GroupParasites.innerJoin(Parasites).slice(Parasites.jid).select {
                    (GroupParasites.group eq jid.id) and (GroupParasites.role neq GroupRoles.NONE)
                }.map {
                    it[Parasites.jid]
                }
            } else {
                listOf(parasite.jid, jid.id)
            }
        }
        synchronized(currentSocketConnections) {
            // Must be in the synchronized block
            val i: Iterator<ChatSocketConnection> = currentSocketConnections.filter { recipientJids.contains(it.parasite.jid) }.iterator()
            i.forEach {
                it.publicKey?.let { pubKey ->
                    log.debug("sending new message to ${it.name}")
                    // socket sending is a suspend function. send it off, but keep processing our synchronized list of connections
                    CoroutineScope(Dispatchers.Default).launch {
                        // then we need to re-encrypt it for sending to any relevant connections
                        val serialized = encryptToSend(messageId, pubKey, decrypted, now)
                        log.debug("encrypted and sending to ${it.name}")
                        it.send(serialized)
                    }
                } ?: log.debug("not sending to ${it.name} because public key is missing")
            }
        }
        return ResponseBody(messageId, MessageTypes.NEW_MESSAGE, message, now)
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

            pipeline.environment.monitor.subscribe(ApplicationStopPreparing) { call ->
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

private data class MessageData(
    val destination: String,
    val sender: String,
    val type: String,
    val message: Any
)

fun Application.configureChatHandler() {
    install(ChatHandler) {
        jidDomain = environment.config.property("bec.jid_domain").getString()
    }
}

val Application.chatHandler: ChatHandler
    get() = this.plugin(ChatHandler)
