package net.chatalina.http.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import net.chatalina.chat.ChatManager
import net.chatalina.chat.EmailTypes
import net.chatalina.chat.ServerMessage
import net.chatalina.chat.sendAdminEmail
import net.chatalina.database.AlertData
import net.chatalina.database.Alerts
import net.chatalina.database.ParasitePermissions
import net.chatalina.database.Parasites
import net.chatalina.http.AuthenticationException
import net.chatalina.http.AuthorizationException
import net.chatalina.http.RedirectException
import net.chatalina.http.getPebbleContent
import net.chatalina.plugins.ParasiteSession
import net.chatalina.plugins.PreAuthSession
import kotlin.time.Duration.Companion.days

fun Route.authenticationRoutes() {
    route("/login") {
        getLogin()
        authenticate("beas") {
            postLogin()
        }
    }
    route("/no-user") {
        getNoUser()
    }
    route("/logout") {
        getLogout()
        postLogout()
    }
    route("/register") {
        getRegister()
    }
    route("/forgot-password") {
        getForgotPassword()
    }
    route("/reset-password") {
        getResetPassword()
    }
    route("/reactivate") {
        getReactivate()
        authenticate("beas") {
            postReactivate()
        }
    }
}

private fun Route.getLogin() {
    get {
        call.respond(application.getPebbleContent("login.html"))
    }
}

private fun Route.postLogin() {
    post {
        val existingSession = call.sessions.get<ParasiteSession>()
        if (existingSession != null) {
            call.respond(HttpStatusCode.OK)
        } else {
            val principal = call.principal<JWTPrincipal>() ?: throw AuthenticationException()

            val parasite = Parasites.DAO.findByAuthId(principal.subject)
            if (parasite == null) {
                throw AuthenticationException()
            } else if (!parasite.active) {
                throw AuthorizationException(mapOf("deactivated" to true))
            } else {
                call.sessions.set(ParasiteSession(parasite.id))
                call.respond(HttpStatusCode.OK, mapOf("success" to true))
            }
        }
    }
}

private fun Route.getNoUser() {
    get {
        call.respond(application.getPebbleContent("no-user.html"))
    }
}

private fun Route.getLogout() {
    get {
        application.log.debug("log out called directly")
        call.sessions.clear<ParasiteSession>()
        call.sessions.clear<PreAuthSession>()
        call.respond(application.getPebbleContent("logout.html"))
    }
}

private fun Route.postLogout() {
    post {
        call.sessions.clear<ParasiteSession>()
        call.sessions.clear<PreAuthSession>()
    }
}

private fun Route.getRegister() {
    get {
        throw RedirectException("/")
    }
}

private fun Route.getForgotPassword() {
    get {
        throw RedirectException("/")
    }
}

private fun Route.getResetPassword() {
    get {
        throw RedirectException("/")
    }
}

private fun Route.getReactivate() {
    get {
        application.log.debug("reactivate visited")
        call.respond(application.getPebbleContent("reactivate.html"))
    }
}

private fun Route.postReactivate() {
    post {
        val principal = call.principal<JWTPrincipal>() ?: throw AuthenticationException()

        val parasite = Parasites.DAO.findByAuthId(principal.subject)
        if (parasite == null) {
            application.log.debug(
                "Reactivation reset request failed for UNKNOWN parasite using auth id: {}",
                principal.subject
            )
        } else if (parasite.active) {
            application.log.debug("Reactivation request received for ACTIVE parasite id: {}", parasite.id)
            throw RedirectException("/login")
        } else {
            if (
                parasite.reactivationRequest == null
                || parasite.reactivationRequest < Clock.System.now().minus(3.days)
            ) {
                Parasites.DAO.setReactivationRequest(parasite.id)
                val alertData = AlertData.dismiss(
                    "Account reactivation requested! Apparently ${parasite.settings.displayName} (${parasite.id}) wants back in.",
                    "Oh..."
                )
                val adminParasites = Parasites.DAO.list(active = true, permissionFilter = ParasitePermissions.Admin)
                adminParasites.forEach {
                    Alerts.DAO.create(it.id, alertData).also { a ->
                        ChatManager.broadcastToParasite(it.id, ServerMessage(alertData, a?.id))
                    }
                }
                application.log.debug("Reactivation request received for parasite id: {}", parasite.id)
                launch {
                    application.sendAdminEmail(
                        EmailTypes.ReactivationRequest,
                        mapOf("parasite_id" to parasite.id.value, "parasite_email" to parasite.email)
                    )
                }
            } else {
                application.log.debug("Reactivation request received AGAIN for parasite id: {}", parasite.id)
            }
        }
        call.respond(HttpStatusCode.Accepted)
    }
}
