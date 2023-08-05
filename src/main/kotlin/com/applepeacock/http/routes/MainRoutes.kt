package com.applepeacock.http.routes

import com.applepeacock.database.Parasites
import com.applepeacock.http.RedirectException
import com.applepeacock.http.getPebbleContent
import com.applepeacock.plugins.ParasiteSession
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.mainRoutes() {
    getMain()
    getMobile()
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
        call.response.cookies.append("permission", sessionParasite.settings.permission)
        call.response.cookies.append("soundSet", sessionParasite.settings.soundSet)
        call.response.cookies.append("id", sessionParasite.id.value)

        call.respond(application.getPebbleContent("chat.html"))
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

        call.respond(application.getPebbleContent("mobile.html"))
    }
}
