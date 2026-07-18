package net.chatalina.http.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import net.chatalina.database.Parasites
import net.chatalina.emoji.EmojiManager
import net.chatalina.http.AuthenticationException
import net.chatalina.http.AuthorizationException
import net.chatalina.http.RedirectException
import net.chatalina.http.getPebbleContent

fun Route.mainRoutes() {
    authenticate("obei", optional = true) {
        getMain()
        getMobile()
    }
    authenticate("obei") {
        getParasite()
        emojiSearch()
    }
}

private fun Route.getMain() {
    get("/") {
        // NOTE: This will only work if the http/proxy server is attaching the query param on mobile user agent detection.
        val mobile = call.request.queryParameters["mobile"].toBoolean()
        if (mobile) {
            throw RedirectException("/m")
        }

        call.respond(application.getPebbleContent("chat.html", "emojiList" to EmojiManager.curatedEmojis))
    }
}

private fun Route.getMobile() {
    get("/m") {
        call.respond(application.getPebbleContent("mobile.html"))
    }
}

private fun Route.getParasite() {
    get("/me") {
        val principal = call.principal<JWTPrincipal>() ?: throw AuthenticationException()

        val parasite = Parasites.DAO.findByAuthId(principal.subject)
        if (parasite == null) {
            throw AuthenticationException()
        } else if (!parasite.active) {
            throw AuthorizationException()
        } else {
            call.respond(HttpStatusCode.OK, parasite)
        }
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
