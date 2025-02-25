package net.chatalina.chat

import io.ktor.server.application.*
import io.ktor.server.config.*
import io.pebbletemplates.pebble.PebbleEngine
import io.pebbletemplates.pebble.loader.ClasspathLoader
import net.chatalina.database.ParasitePermissions
import net.chatalina.database.Parasites
import net.chatalina.isProduction
import net.chatalina.siteName
import org.apache.commons.mail.EmailException
import org.apache.commons.mail.ImageHtmlEmail
import org.apache.commons.mail.resolver.DataSourceClassPathResolver
import org.apache.commons.validator.routines.EmailValidator
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.StringWriter

enum class EmailTypes(
    val template: String,
    val subject: String,
    val preview: String
) {
    ForgotPassword(
        "forgot-password.html",
        "Maybe you got amnesia?",
        "Someone has requested a password reset for your account."
    ) {
        override fun compileTextBody(args: Map<String, String>) = """
            Password reset requested
            
            Well, someone has requested a password reset for ${args["parasite_id"]}.
            
            You'd better hurry, this link will only be good for 24 hours:
            ${args["reset_link"]}
            
            If you did not request a password reset, you should probably change your password.
            """
    },
    ChangedPassword(
        "changed-password.html",
        "Your password has been changed!",
        "Your password for your account was changed."
    ) {
        override fun compileTextBody(args: Map<String, String>) = """
            Your password for ${args["parasite_id"]} was changed.
            
            If you did not do this, let the admin know!
        """
    },
    ReactivationRequest(
        "reactivation.html",
        "Parasite reactivation requested",
        "A parasite is requesting reactivation of their account."
    ) {
        override fun compileTextBody(args: Map<String, String>) = """
            A parasite is requesting reactivation of their account.
             
             Parasite id: ${args["parasite_id"]}
             Parasite email: ${args["parasite_email"]}
            
            To grant this request, use the admin tools inside the chat.
            Otherwise, just ignore this email. Nobody's gonna know. How would they know?
        """
    },
    CriticalError("critical-error.html", "CRITICAL ERROR", "There was a critical error logged!") {
        override fun compileTextBody(args: Map<String, String>) = """
                Critical error logged in ${args["site_name"]}
                
                ${args["error"]}
            """
    };

    protected abstract fun compileTextBody(args: Map<String, String>): String
    internal fun getTextBody(args: Map<String, String>) =
        (this.compileTextBody(args) + "\n\n-- The ${args["site_name"]} Server <3").trimIndent()

    internal fun getHtmlBody(pebbleEngine: PebbleEngine, args: Map<String, String>): String {
        val templateWriter = StringWriter()
        val foundTemplate = pebbleEngine.getTemplate(this.template)
        foundTemplate.evaluate(templateWriter, args + ("preview" to this.preview))
        return templateWriter.toString()
    }
}

fun Application.sendEmail(
    type: EmailTypes,
    recipient: Parasites.ParasiteObject,
    args: Map<String, String> = emptyMap()
) {
    if (this.isProduction) {
        EmailHandler.sendEmail(type, siteName, buildMap {
            putAll(args)
            put("parasite_id", recipient.id.toString())
        }, recipient)
    } else {
        log.debug(
            "Would have sent email (type: {}, recipient: {} ({}), args: {})",
            type.name,
            recipient.name,
            recipient.email,
            args
        )
    }
}

fun Application.sendAdminEmail(type: EmailTypes, args: Map<String, String> = emptyMap()) {
    val adminParasites = Parasites.DAO.list(active = true, permissionFilter = ParasitePermissions.Admin)

    if (this.isProduction) {
        EmailHandler.sendEmail(type, siteName, args, *adminParasites.toTypedArray())
    } else {
        log.debug(
            "Would have sent email to admins (type: {}, admins: {}, args: {})",
            type.name,
            adminParasites.joinToString { "${it.name} - ${it.email}" },
            args
        )
    }
}

fun Application.sendErrorEmail(error: Any?) {
    val errorToInclude = when (error) {
        is Throwable -> """
            ${error.message}
            
            ${error.stackTraceToString()}
        """
        else -> error.toString()
    }
    val adminParasites = Parasites.DAO.list(active = true, permissionFilter = ParasitePermissions.Admin)

    if (this.isProduction) {
        EmailHandler.sendEmail(
            EmailTypes.CriticalError,
            siteName,
            mapOf("error" to errorToInclude),
            *adminParasites.toTypedArray()
        )
    } else {
        log.debug(
            "Would have sent error email to admins ({})",
            adminParasites.joinToString { "${it.name} - ${it.email}" }
        )
        log.debug(errorToInclude)
    }
}

object EmailHandler {
    private val logger: Logger = LoggerFactory.getLogger("EMAIL")

    lateinit var smtpFromAddress: String
    lateinit var smtpHost: String
    lateinit var smtpPort: String
    var smtpTls: Boolean = false
    var smtpUser: String? = null
    var smtpPass: String? = null

    private val pebbleForEmails =
        PebbleEngine.Builder().loader(ClasspathLoader().apply {
            prefix = "templates/email"
            charset = "UTF-8"
        }).build()

    fun configure(
        fromAddress: String,
        host: String,
        port: String,
        tls: Boolean? = null,
        user: String? = null,
        pass: String? = null
    ) {
        logger.debug("Initializing email handler...")
        smtpFromAddress = fromAddress
        smtpHost = host
        smtpPort = port
        tls?.let { smtpTls = it }
        if (user.isNullOrBlank() != pass.isNullOrBlank()) {
            throw ApplicationConfigurationException("User and password must both be set to work.")
        }
        user?.let { smtpUser = it }
        pass?.let { smtpPass = it }

        logger.info("Email handler initialized.")
    }

    internal fun sendEmail(
        type: EmailTypes,
        siteName: String,
        args: Map<String, String>,
        vararg recipients: Parasites.ParasiteObject
    ) {
        logger.debug("Sending email")

        if (recipients.isEmpty()) logger.error("Unable to send email to no recipients (type: {})", type.name)

        try {
            val email = ImageHtmlEmail()
            email.hostName = smtpHost
            email.setSmtpPort(smtpPort.toInt())
            if (smtpTls) {
                logger.info("sending email with tls")
                if (!smtpUser.isNullOrBlank() && !smtpPass.isNullOrBlank()) {
                    email.setAuthentication(smtpUser, smtpPass)
                }
                email.setSSLOnConnect(smtpTls)
                email.isSSLCheckServerIdentity = smtpTls
                email.isStartTLSEnabled = smtpTls
                email.isStartTLSRequired = smtpTls
            } else {
                logger.info("sending email without tls")
            }
            email.setFrom(smtpFromAddress, "The $siteName Server <3")

            email.dataSourceResolver = DataSourceClassPathResolver("/static/images", true)

            email.subject = type.subject
            val emailArgs = args + ("site_name" to siteName)
            email.setHtmlMsg(type.getHtmlBody(pebbleForEmails, emailArgs))
            email.setTextMsg(type.getTextBody(emailArgs))

            recipients.forEach { recipient ->
                if (!EmailValidator.getInstance().isValid(recipient.email)) {
                    logger.error(
                        "Unable to send email - parasite has not provided a valid one (type: {}, recipient: {} ({}))",
                        type.name,
                        recipient.name,
                        recipient.email
                    )
                } else {
                    email.addTo(recipient.email, recipient.name)
                }
            }

            email.send()

            logger.debug("Email sent")
        } catch (e: EmailException) {
            logger.error("Error sending email:")
            throw e
        }
    }
}