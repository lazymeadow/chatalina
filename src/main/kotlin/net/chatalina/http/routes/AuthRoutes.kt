package net.chatalina.http.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import net.chatalina.database.Parasites
import net.chatalina.http.AuthenticationException
import net.chatalina.http.AuthorizationException
import net.chatalina.http.RedirectException
import net.chatalina.http.getPebbleContent
import net.chatalina.plugins.ParasiteSession
import net.chatalina.plugins.PreAuthSession

fun Route.authenticationRoutes() {
    route("/login") {
        getLogin()
        authenticate("obei") {
            postLogin()
        }
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
                throw AuthorizationException()
            } else {
                call.sessions.set(ParasiteSession(parasite.id))
                call.respond(HttpStatusCode.OK)
            }
        }
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
        throw RedirectException("/")
    }
}
