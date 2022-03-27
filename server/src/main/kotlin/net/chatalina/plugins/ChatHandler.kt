package net.chatalina.plugins

import com.fasterxml.jackson.databind.json.JsonMapper
import io.ktor.application.*
import io.ktor.auth.jwt.*
import io.ktor.config.*
import io.ktor.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.chatalina.chat.*
import org.slf4j.Logger
import java.security.PublicKey
import java.util.*

class ChatHandler(
    configuration: PluginConfiguration,
    private val encryption: Encryption,
    private val mapper: JsonMapper,
    private val becAuth: BecAuthentication,
    private val log: Logger
) {
    //    val chatHandler: ChatHandler = ChatHandler(encryption, jacksonMapper, log)
    val currentSocketConnections: MutableSet<ChatSocketConnection> = Collections.synchronizedSet(LinkedHashSet())

    class PluginConfiguration {

    }

    private fun serializeToSend(valToSend: Any): String {
        return mapper.writeValueAsString(valToSend)
    }

    suspend fun sendToConnection(connection: ChatSocketConnection, data: Any) {
        connection.send(serializeToSend(data))
    }

    fun validateToken(token: String?): JWTPrincipal? {
        return token?.let {becAuth.validateJwt(it)}
    }

    suspend fun processNewMessage(message: MessageContent, publicKey: PublicKey): ResponseBody {
        // 1. decrypt message
        val (iv, content) = message
        val decrypted =
            encryption.decrypt(
                content,
                iv,
                publicKey
            )

        // 2. encrypt message for db & insert
        val messageId = UUID.randomUUID()

        // 3. for every appropriate socket, encrypt and send
        synchronized(currentSocketConnections) {
            val i: Iterator<ChatSocketConnection> =
                currentSocketConnections.iterator() // Must be in the synchronized block
            i.forEach {
                it.publicKey?.let { pubKey ->
                    log.debug("sending new message to ${it.name}")
                    // socket sending is a suspend function. send it off, but keep processing our synchronized list of connections
                    CoroutineScope(Dispatchers.Default).launch {
                        // then we need to re-encrypt it for sending to any relevant connections
                        val (nonce, encrypted) = encryption.encrypt(
                            decrypted,
                            pubKey
                        )
                        val serialized = serializeToSend(
                            mapOf(
                                "type" to MessageTypes.NEW_MESSAGE, "content" to
                                        mapOf(
                                            "id" to messageId,
                                            "iv" to nonce,
                                            "content" to Base64.getEncoder()
                                                .encodeToString(encrypted)
                                        )
                            )
                        )

                        log.debug("encrypted and sending to ${it.name}")
                        it.send(serialized)
                    }
                } ?: log.debug("not sending to ${it.name} because public key is missing")
            }
        }
        return ResponseBody(messageId, MessageTypes.NEW_MESSAGE, message)
    }

    companion object Feature : ApplicationFeature<Application, PluginConfiguration, ChatHandler> {
        override val key = AttributeKey<ChatHandler>("chatHandler")

        override fun install(pipeline: Application, configure: PluginConfiguration.() -> Unit): ChatHandler {
            val configuration = PluginConfiguration().apply(configure)
            val encryption = pipeline.feature(Encryption)
            val jacksonMapper = pipeline.jacksonMapper
            val log = pipeline.log
            val becAuth = pipeline.environment.becAuth ?: throw ApplicationConfigurationException("Security must be configured before chat handler")
            return ChatHandler(configuration, encryption, jacksonMapper, becAuth, log)
        }
    }
}

fun Application.configureChatHandler() {
    install(ChatHandler) {

    }
}