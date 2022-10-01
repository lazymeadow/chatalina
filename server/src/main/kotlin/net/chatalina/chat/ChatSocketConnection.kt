package net.chatalina.chat

import io.ktor.server.auth.jwt.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.launch
import net.chatalina.database.Parasite
import org.slf4j.LoggerFactory
import java.security.PublicKey
import java.util.concurrent.atomic.AtomicInteger


class ChatSocketConnection(val session: DefaultWebSocketServerSession) {
    companion object {
        var lastId = AtomicInteger(0)
    }

    val name = "WS-${lastId.getAndIncrement()}"
    val log = LoggerFactory.getLogger(name)
    lateinit var parasite: Parasite

    // we need to know if the auth is valid
    var publicKey: PublicKey? = null

    // we'll need a public key to do encryption, but there's no headers with browser based WebSockets -_-
    var principal: JWTPrincipal? = null

    fun isParasiteSet(): Boolean {
        return this::parasite.isInitialized
    }

    fun launchForSocket(block: suspend () -> Unit) {
        log.debug("launching job on socket")
        this.session.launch(CoroutineName(name)) {
            block()
        }
    }

    suspend fun send(data: String) {
        try {
            this.session.send(data)
        } catch (e: ClosedSendChannelException) {
            log.debug("Attempted to send message to closed session $name")
        }
    }

    override fun toString(): String {
        return this.name
    }
}