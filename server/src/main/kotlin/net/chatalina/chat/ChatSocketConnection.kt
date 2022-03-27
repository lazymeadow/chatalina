package net.chatalina.chat

import io.ktor.application.*
import io.ktor.auth.jwt.*
import io.ktor.http.cio.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ClosedSendChannelException
import java.util.concurrent.atomic.AtomicInteger


class ChatSocketConnection(val session: DefaultWebSocketServerSession) {
    companion object {
        var lastId = AtomicInteger(0)
    }

    val name = "ws-${lastId.getAndIncrement()}"

    // we need to know if the auth is valid
    var publicKey: String? = null

    // we'll need a public key to do encryption, but there's no headers with browser based WebSockets -_-
    var principal: JWTPrincipal? = null

    suspend fun send(data: String) {
        try {
            this.session.send(data)
        } catch (e: ClosedSendChannelException) {
            this.session.application.log.debug("Attempted to send message to closed session $name")
        }
    }
}