package net.chatalina.http.routes

import at.favre.lib.crypto.bcrypt.BCrypt
import net.chatalina.chat.EmailTypes
import net.chatalina.chat.tokenDecrypt
import net.chatalina.chat.tokenEncrypt
import net.chatalina.chat.sendEmail
import net.chatalina.database.Parasites
import net.chatalina.database.Rooms
import net.chatalina.hostUrl
import net.chatalina.http.AuthenticationException
import net.chatalina.http.RedirectException
import net.chatalina.http.getPebbleContent
import net.chatalina.isProduction
import net.chatalina.plugins.CLIENT_VERSION
import net.chatalina.plugins.ParasiteSession
import net.chatalina.siteName
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.pebble.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
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


private fun Route.getLogin() {
    get {
        call.sessions.get<ParasiteSession>()?.let {
            throw RedirectException("/")
        } ?: let {
            val message = call.request.queryParameters["message"] ?: ""
            val parasite = call.request.queryParameters["parasite"] ?: ""
            call.respond(application.getPebbleContent("login.html", "message" to message, "username" to parasite))
            call.respond(
                PebbleContent(
                    "login.html",
                    mapOf(
                        "prod" to application.isProduction,
                        "siteTitle" to "${application.siteName} $CLIENT_VERSION",
                        "message" to message
                    )
                )
            )
        }
    }
}

private fun Route.postLogin() {
    post {
        data class LoginRequest(val parasite: String, val password: String)

        val body = call.receive<LoginRequest>()
        if (Parasites.DAO.checkPassword(body.parasite, body.password)) {
            call.sessions.set(ParasiteSession(body.parasite))
            call.respond(HttpStatusCode.OK)
        } else {
            throw AuthenticationException()
        }
    }
}

private fun Route.getLogout() {
    get {
        call.sessions.clear<ParasiteSession>()
        throw RedirectException("/login")
    }
}

private fun Route.postLogout() {
    post {
        call.sessions.clear<ParasiteSession>()
        throw RedirectException("/login")
    }
}

private fun Route.getRegister() {
    get {
        call.respond(application.getPebbleContent("register.html"))
    }
}

private fun Route.postRegister() {
    post {
        data class RegisterRequest(
            val parasite: String,
            val password: String,
            val password2: String,
            val email: String
        )

        val body = call.receive<RegisterRequest>()
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

private fun Route.getForgotPassword() {
    get {
        call.respond(application.getPebbleContent("forgot-password.html"))
    }
}

private fun Route.postForgotPassword() {
    post {
        data class ForgotPasswordRequest(val parasite: String)

        val body = call.receive<ForgotPasswordRequest>()
        val foundParasite = Parasites.DAO.find(body.parasite)
        if (foundParasite != null) {
            val newResetToken = tokenEncrypt(body.parasite)
            Parasites.DAO.newPasswordResetToken(body.parasite, newResetToken)

            val resetLink = "${application.hostUrl}/reset-password?token=${newResetToken}"
            application.sendEmail(EmailTypes.ForgotPassword, foundParasite, mapOf("reset_link" to resetLink))
        } else {
            application.log.debug("Password reset request failed for parasite id: ${body.parasite}")
        }
        call.respond(HttpStatusCode.Accepted)
    }
}

fun checkTokenForParasite(token: String): Parasites.ParasiteObject {
    val parasiteId = tokenDecrypt(token)
    return Parasites.DAO.find(parasiteId)?.also { parasite ->
        if (!Parasites.DAO.checkToken(parasite.id, token)) {
            throw BadRequestException("Invalid password reset link.")
        }
    } ?: throw BadRequestException("Invalid password reset link.")
}

private fun Route.getResetPassword() {
    get {
        val token = call.request.queryParameters["token"] ?: ""
        val parasite = checkTokenForParasite(token)

        call.respond(application.getPebbleContent("reset-password.html", "token" to token, "username" to parasite.id))
    }
}

private fun Route.postResetPassword() {
    post {
        data class ResetPasswordRequest(val token: String, val password: String, val password2: String)

        val body = call.receive<ResetPasswordRequest>()

        try {
            val parasite = checkTokenForParasite(body.token)
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