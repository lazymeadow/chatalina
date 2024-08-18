package net.chatalina.http.routes

import at.favre.lib.crypto.bcrypt.BCrypt
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import net.chatalina.chat.EmailTypes
import net.chatalina.chat.sendEmail
import net.chatalina.chat.tokenDecrypt
import net.chatalina.database.Parasites
import net.chatalina.database.Rooms
import net.chatalina.hostUrl
import net.chatalina.http.AuthenticationException
import net.chatalina.http.AuthorizationException
import net.chatalina.http.RedirectException
import net.chatalina.http.getPebbleContent
import net.chatalina.plugins.ParasiteSession
import net.chatalina.plugins.PreAuthSession
import javax.crypto.BadPaddingException
import javax.crypto.IllegalBlockSizeException

fun Route.authenticationRoutes() {
    route("/login") {
        getLogin()
        postLogin()
    }
    route("/logout") {
        getLogout()
        postLogout()
    }
    route("/register") {
        getRegister()
        postRegister()
    }
    route("/forgot-password") {
        getForgotPassword()
        postForgotPassword()
    }
    route("/reset-password") {
        getResetPassword()
        postResetPassword()
    }
    route("/reactivate") {
        getReactivate()
        postReactivate()
    }
    // I don't think this endpoint is used at all
    // post("/validate_username") {}
}

private open class PreAuthRequestBody(open val t: String? = null) {
    fun getVal() = t?.let {
        try {
            tokenDecrypt(it)
        } catch (e: BadPaddingException) {
            null
        } catch (e: IllegalBlockSizeException) {
            null
        }
    }
}

private suspend fun ApplicationCall.withPreAuthSession(block: suspend (PreAuthSession) -> Unit) {
    val session = sessions.get<PreAuthSession>() ?: PreAuthSession().also { sessions.set<PreAuthSession>(it) }
    block(session)
}

private suspend fun ApplicationCall.requirePreAuthSession(block: suspend (PreAuthSession) -> Unit) {
    val session = sessions.get<PreAuthSession>() ?: throw IllegalStateException("no session")
    if (session.t.isBlank()) throw IllegalStateException("bad session")
    block(session)
}

private fun ApplicationCall.redirectIfLoggedIn() {
    sessions.get<ParasiteSession>()?.let {
        throw RedirectException("/")
    }
}

private fun Route.getLogin() {
    get {
        call.redirectIfLoggedIn()
        call.withPreAuthSession { session ->
            val message = call.request.queryParameters["message"] ?: ""
            val parasite = call.request.queryParameters["parasite"] ?: ""
            call.respond(
                application.getPebbleContent(
                    "login.html",
                    "message" to message,
                    "username" to parasite,
                    "t" to session.t
                )
            )
        }
    }
}

private fun Route.postLogin() {
    post {
        call.requirePreAuthSession { session ->
            data class LoginRequest(val parasite: String, val password: String, override val t: String? = null) :
                PreAuthRequestBody(t)

            val body = call.receive<LoginRequest>()

            if (body.getVal() == session.getVal() && Parasites.DAO.checkPassword(body.parasite, body.password)) {
                call.sessions.set(ParasiteSession(body.parasite))
                call.respond(HttpStatusCode.OK)
            } else {
                throw AuthenticationException()
            }
        }
    }
}

private fun Route.getLogout() {
    get {
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
        call.redirectIfLoggedIn()
        call.withPreAuthSession { session ->
            call.respond(application.getPebbleContent("register.html", "t" to session.t))
        }
    }
}

private fun Route.postRegister() {
    post {
        call.requirePreAuthSession { session ->
            data class RegisterRequest(
                val parasite: String,
                val password: String,
                val password2: String,
                val email: String,
                override val t: String? = null
            ) : PreAuthRequestBody(t)

            val body = call.receive<RegisterRequest>()
            if (body.getVal() != session.getVal()) {
                throw BadRequestException("Invalid state")
            }

            val error = if (body.parasite.isBlank() || Parasites.DAO.exists(body.parasite)) {
                "Invalid username."
            } else if (body.password.isBlank()) {
                "Password is required."
            } else if (body.password != body.password2) {
                "Password entries must match."
            } else if (body.email.isBlank()) {
                "Email is required."
            } else {
                null
            }
            error?.let {
                throw BadRequestException(error)
            }
            val hashed = BCrypt.with(BCrypt.Version.VERSION_2B).hash(12, body.password.toByteArray())
            val newParasite = Parasites.DAO.create(body.parasite, body.email, hashed)
                    ?: throw BadRequestException("Failed to create user")
            Rooms.DAO.addMember(newParasite.id)  // add new parasite to "general" room
            call.respond(HttpStatusCode.Created, newParasite)
        }
    }
}

private fun Route.getForgotPassword() {
    get {
        call.redirectIfLoggedIn()
        call.withPreAuthSession { session ->
            call.respond(application.getPebbleContent("forgot-password.html", "t" to session.t))
        }
    }
}

private fun Route.postForgotPassword() {
    post {
        call.requirePreAuthSession { session ->
            data class ForgotPasswordRequest(val parasite: String, override val t: String? = null) :
                PreAuthRequestBody(t)

            val body = call.receive<ForgotPasswordRequest>()
            if (body.getVal() != session.getVal()) {
                application.log.debug("Password reset request failed due to mismatched token for parasite id: ${body.parasite}")
            } else {
                call.sessions.get<ParasiteSession>()?.let {
                    if (body.parasite != it.id) {
                        throw AuthorizationException()
                    }
                }

                val foundParasite = Parasites.DAO.find(body.parasite)
                if (foundParasite != null) {
                    val newResetToken = Parasites.DAO.newPasswordResetToken(body.parasite)
                    val resetLink =
                        "${application.hostUrl}/reset-password?token=${newResetToken.encodeURLQueryComponent(encodeFull = true)}"
                    application.sendEmail(EmailTypes.ForgotPassword, foundParasite, mapOf("reset_link" to resetLink))
                } else {
                    application.log.debug("Password reset request failed for parasite id: ${body.parasite}")
                }
            }
            call.respond(HttpStatusCode.Accepted)
        }
    }
}

private fun checkResetTokenForParasite(token: String): Parasites.ParasiteObject {
    val parasiteId = tokenDecrypt(token)
    return Parasites.DAO.find(parasiteId)?.also { parasite ->
        if (!Parasites.DAO.checkToken(parasite.id, token)) {
            throw BadRequestException("Invalid password reset link.")
        }
    } ?: throw BadRequestException("Invalid password reset link.")
}

private fun Route.getResetPassword() {
    get {
        call.redirectIfLoggedIn()
        call.withPreAuthSession { session ->
            val token = call.request.queryParameters["token"] ?: ""
            val parasite = checkResetTokenForParasite(token)

            call.respond(
                application.getPebbleContent(
                    "reset-password.html",
                    "token" to token,
                    "username" to parasite.id,
                    "t" to session.t
                )
            )
        }
    }
}

private fun Route.postResetPassword() {
    post {
        call.requirePreAuthSession { session ->
            data class ResetPasswordRequest(
                val token: String,
                val password: String,
                val password2: String,
                override val t: String? = null
            ) : PreAuthRequestBody(t)

            val body = call.receive<ResetPasswordRequest>()

            if (body.getVal() != session.getVal()) {
                throw BadRequestException("Invalid state")
            }

            try {
                val parasite = checkResetTokenForParasite(body.token)
                call.sessions.get<ParasiteSession>()?.let {
                    if (parasite.id.value != it.id) {
                        throw AuthorizationException()
                    }
                }
                val error = if (body.password.isBlank()) {
                    "Password is required."
                } else if (body.password != body.password2) {
                    "Password entries must match."
                } else {
                    null
                }
                error?.let {
                    throw BadRequestException(error)
                }
                val hashed = BCrypt.with(BCrypt.Version.VERSION_2B).hash(12, body.password.toByteArray())
                val success = Parasites.DAO.updatePassword(parasite.id, hashed)
                if (success) {
                    call.respond(HttpStatusCode.Accepted)
                    application.sendEmail(EmailTypes.ChangedPassword, parasite)
                } else {
                    throw BadRequestException("Password update failed.")
                }
            } catch (e: IllegalBlockSizeException) {
                throw BadRequestException("Invalid password reset link.")
            }
        }
    }
}

private fun Route.getReactivate() {
    get {
        TODO()
    }
}

private fun Route.postReactivate() {
    post {
        TODO()
    }
}