package net.chatalina.plugins

import com.fasterxml.jackson.databind.json.JsonMapper
import io.ktor.application.*
import io.ktor.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.chatalina.chat.*
import org.slf4j.Logger
import java.util.*

class ChatHandlerPlugin(
    configuration: PluginConfiguration,
    private val encryption: Encryption,
    private val mapper: JsonMapper,
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

    suspend fun processNewMessage(message: MessageContent, currentConnection: ChatSocketConnection): ResponseBody {
        // 1. decrypt message
        val (iv, content) = message
        val decrypted =
            encryption.decrypt(
                content,
                iv,
                currentConnection.publicKey!!
            )
        log.debug("received from ${currentConnection.name}")

        // 2. encrypt message for db & insert
        val messageId = UUID.randomUUID()

        // 3. for every appropriate socket, encrypt and send
        synchronized(currentSocketConnections) {
            val i: Iterator<ChatSocketConnection> =
                currentSocketConnections.iterator() // Must be in the synchronized block
            i.forEach {
                log.debug("sending new message to ${it.name}")
                // socket sending is a suspend function. send it off, but keep processing our synchronized list of connections
                CoroutineScope(Dispatchers.Default).launch {
                    // then we need to re-encrypt it for sending to any relevant connections
                    val (nonce, encrypted) = encryption.encrypt(
                        decrypted,
                        it.publicKey!!
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

            }
        }
        return ResponseBody(messageId, MessageTypes.NEW_MESSAGE, message)
    }

    companion object Feature : ApplicationFeature<Application, PluginConfiguration, ChatHandlerPlugin> {
        override val key = AttributeKey<ChatHandlerPlugin>("chatHandler")

        override fun install(pipeline: Application, configure: PluginConfiguration.() -> Unit): ChatHandlerPlugin {
            val configuration = PluginConfiguration().apply(configure)
            val encryption = pipeline.feature(Encryption)
            val jacksonMapper = pipeline.jacksonMapper
            val log = pipeline.log
            return ChatHandlerPlugin(configuration, encryption, jacksonMapper, log)
        }
    }
}

fun Application.configureChatHandler() {
    install(ChatHandlerPlugin) {

    }
}