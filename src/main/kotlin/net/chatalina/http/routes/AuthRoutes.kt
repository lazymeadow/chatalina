package net.chatalina.http.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import net.chatalina.database.ParasiteSettings
import net.chatalina.database.Parasites
import net.chatalina.http.AuthenticationException
import net.chatalina.http.AuthorizationException
import net.chatalina.http.RedirectException
import net.chatalina.http.getPebbleContent
import net.chatalina.plugins.ParasiteSession
import net.chatalina.plugins.PreAuthSession
import kotlin.reflect.KProperty1
import kotlin.reflect.full.declaredMemberProperties

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
        fun setParasiteResponseCookies(sessionParasite: Parasites.ParasiteObject) {
            call.response.cookies.append("id", sessionParasite.id.value)
            call.response.cookies.append("email", sessionParasite.email)
            ParasiteSettings::class.declaredMemberProperties.forEach { prop: KProperty1<ParasiteSettings, *> ->
                val propValue = if (ParasiteSettings::displayName == prop) {
                    prop.get(sessionParasite.settings)?.toString() ?: sessionParasite.id.value
                } else {
                    prop.get(sessionParasite.settings).toString()
                }
                call.response.cookies.append(prop.name, propValue)
            }
        }

        val existingSession = call.sessions.get<ParasiteSession>()
        if (existingSession != null) {
            val sessionParasite = Parasites.DAO.find(existingSession.id)?.takeIf { it.active } ?: throw AuthenticationException()
            setParasiteResponseCookies(sessionParasite)
            call.respond(HttpStatusCode.OK)
        } else {
            data class LoginBody(val subject: String)

            val body = call.receive<LoginBody>()

            val parasite = Parasites.DAO.findByAuthId(body.subject)
            if (parasite == null) {
                throw AuthenticationException()
            } else if (!parasite.active) {
                throw AuthorizationException()
            } else {
                call.sessions.set(ParasiteSession(parasite.id))
                setParasiteResponseCookies(parasite)
                call.respond(HttpStatusCode.OK)
            }
        }
    }
}

private fun Route.getLogout() {
    get {
        application.log.debug("log out called")
        call.sessions.clear<ParasiteSession>()
        call.sessions.clear<PreAuthSession>()
        throw RedirectException("/login")
    }
}

private fun Route.postLogout() {
    post {
        call.sessions.clear<ParasiteSession>()
        call.sessions.clear<PreAuthSession>()
        throw RedirectException("/login")
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
