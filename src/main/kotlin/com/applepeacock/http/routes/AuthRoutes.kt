package com.applepeacock.http.routes

import at.favre.lib.crypto.bcrypt.BCrypt
import com.applepeacock.database.Parasites
import com.applepeacock.hostUrl
import com.applepeacock.http.AuthenticationException
import com.applepeacock.http.RedirectException
import com.applepeacock.http.getPebbleContent
import com.applepeacock.isProduction
import com.applepeacock.plugins.CLIENT_VERSION
import com.applepeacock.plugins.ParasiteSession
import com.applepeacock.secretKey
import com.applepeacock.siteName
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.pebble.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.util.*
import javax.crypto.Cipher

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
            call.respond(application.getPebbleContent("login.html", "message" to message))
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
        if (Parasites.DAO.exists(body.parasite)) {
            val cipher = Cipher.getInstance("AES")
            cipher.init(Cipher.ENCRYPT_MODE, application.secretKey)
            val newResetToken = cipher.doFinal(body.parasite.toByteArray()).encodeBase64()

            Parasites.DAO.newPasswordResetToken(body.parasite, newResetToken)

            if (application.isProduction) {
                TODO("send an email")
            } else {
                application.log.debug("Password reset link for parasite ${body.parasite}: ${application.hostUrl}/reset-password?token=${newResetToken}")
            }
        } else {
            application.log.debug("Password reset request failed for parasite id: ${body.parasite}")
        }
        call.respond(HttpStatusCode.Accepted)
    }
}

private fun Route.getResetPassword() {
    get {
        val token = call.request.queryParameters["token"] ?: ""

        call.respond(application.getPebbleContent("reset-password.html", "token" to token))
    }
}

private fun Route.postResetPassword() {
    post {
        data class ResetPasswordRequest(val token: String, val password: String, val password2: String)
        val body = call.receive<ResetPasswordRequest>()

        val cipher = Cipher.getInstance("AES")
        cipher.init(Cipher.DECRYPT_MODE, application.secretKey)
        val parasiteId = cipher.doFinal(body.token.decodeBase64Bytes()).decodeToString()
        if (!Parasites.DAO.checkToken(parasiteId, body.token)) {
            throw BadRequestException("Invalid password reset link.")
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
        val success = Parasites.DAO.updatePassword(parasiteId, hashed)
        if (success) {
            call.respond(HttpStatusCode.Accepted)
        } else {
            throw BadRequestException("Password update failed.")
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