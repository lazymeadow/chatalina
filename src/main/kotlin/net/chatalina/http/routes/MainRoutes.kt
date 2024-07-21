package net.chatalina.http.routes

import net.chatalina.database.Parasites
import net.chatalina.emoji.EmojiManager
import net.chatalina.http.RedirectException
import net.chatalina.http.getPebbleContent
import net.chatalina.plugins.ParasiteSession
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.mainRoutes() {
    getMain()
    getMobile()
    emojiSearch()
}

private fun Route.getMain() {
    get("/") {
        // NOTE: This will only work if the http/proxy server is attaching the query param on mobile user agent detection.
        val mobile = call.request.queryParameters["mobile"].toBoolean()
        if (mobile) {
            throw RedirectException("/m")
        }

        val session = call.principal<ParasiteSession>() ?: let {
            throw RedirectException("/logout")
        }
        val sessionParasite = Parasites.DAO.find(session.id) ?: let {
            throw RedirectException("/logout")
        }
        call.response.cookies.append("username", sessionParasite.settings.displayName ?: sessionParasite.id.value)
        call.response.cookies.append("color", sessionParasite.settings.color)
        call.response.cookies.append("volume", sessionParasite.settings.volume)
        call.response.cookies.append("email", sessionParasite.email)
        call.response.cookies.append("faction", sessionParasite.settings.faction)
        call.response.cookies.append("permission", sessionParasite.settings.permission.toString())
        call.response.cookies.append("soundSet", sessionParasite.settings.soundSet)
        call.response.cookies.append("id", sessionParasite.id.value)

        call.respond(application.getPebbleContent("chat.html", "emojiList" to EmojiManager.curatedEmojis))
    }
}

private fun Route.getMobile() {
    get("/m") {
        val session = call.principal<ParasiteSession>() ?: let {
            throw RedirectException("/logout")
        }
        val sessionParasite = Parasites.DAO.find(session.id) ?: let {
            throw RedirectException("/logout")
        }
        // it only sets the cookies for the items you can change :/ so weird
        call.response.cookies.append("volume", sessionParasite.settings.volume)
        call.response.cookies.append("soundSet", sessionParasite.settings.soundSet)
        call.response.cookies.append("id", sessionParasite.id.value)

        call.respond(application.getPebbleContent("mobile.html", "emojiList" to EmojiManager.curatedEmojis))
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
