package net.chatalina.plugins

import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.server.application.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.config.*
import io.ktor.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.chatalina.chat.ChatSocketConnection
import net.chatalina.chat.MessageContent
import net.chatalina.chat.MessageTypes
import net.chatalina.chat.ResponseBody
import net.chatalina.database.Messages
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

    fun getMessages(publicKey: PublicKey): List<ResponseBody> {
        return transaction {
            Messages.select { Messages.destination eq jidDomain }.orderBy(Messages.created).limit(100).map {
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
            ResponseBody( messageId,
                MessageTypes.NEW_MESSAGE,
                MessageContent(
                    Base64.getEncoder().encodeToString(nonce), Base64.getEncoder()
                        .encodeToString(encrypted)
                ),
                time
            )
        )
    }

    suspend fun processNewMessage(message: MessageContent, publicKey: PublicKey): ResponseBody {
        // 1. decrypt message
        val (iv, content) = message
        val decrypted =
            encryption.decryptEC(
                content,
                iv,
                publicKey
            )


        // 2. encrypt message for db & insert
        // 2.a. we need the destination for indexing and lookups, but it was encrypted
        val dest = mapper.readValue<MessageData>(decrypted).destination
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
        synchronized(currentSocketConnections) {
            // Must be in the synchronized block
            val i: Iterator<ChatSocketConnection> = currentSocketConnections.iterator()
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
            return ChatHandler(configuration, encryption, jacksonMapper, becAuth, log)
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
