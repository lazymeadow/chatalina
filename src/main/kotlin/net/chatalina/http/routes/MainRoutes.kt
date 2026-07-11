package net.chatalina.http.routes

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.pebble.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import net.chatalina.emoji.EmojiManager
import net.chatalina.http.RedirectException
import net.chatalina.http.getPebbleContent

fun Route.mainRoutes() {
    authenticate("obei", optional = true) {
        getMain()
        getMobile()
    }
    authenticate("obei") {
        emojiSearch()
    }
}

private suspend fun ApplicationCall.handleMainRoute(getTemplateContent: () -> PebbleContent) {
//    val principal = this.principal<JWTPrincipal>()
//    if (principal == null) {
        respond(getTemplateContent())
//    } else {
        // set up session
//    }
}

private fun Route.getMain() {
    get("/") {
        // NOTE: This will only work if the http/proxy server is attaching the query param on mobile user agent detection.
        val mobile = call.request.queryParameters["mobile"].toBoolean()
        if (mobile) {
            throw RedirectException("/m")
        }

        call.handleMainRoute { application.getPebbleContent("chat.html", "emojiList" to EmojiManager.curatedEmojis) }
    }
}

private fun Route.getMobile() {
    get("/m") {
        call.handleMainRoute { application.getPebbleContent("mobile.html") }
    }
}

private fun Route.emojiSearch() {
    get("/emoji_search") {
        val search = call.request.queryParameters["search"] ?: ""
        val result = if (search.isBlank()) {
            EmojiManager.curatedEmojis
        } else {
            EmojiManager.search(search.trim())
        }
        call.respond(mapOf("search" to search, "result" to result))
    }
}
