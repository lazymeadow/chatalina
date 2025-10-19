package net.chatalina.chat

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.jackson.*
import io.ktor.server.application.*
import io.ktor.server.config.*
import io.pebbletemplates.pebble.PebbleEngine
import io.pebbletemplates.pebble.loader.ClasspathLoader
import net.chatalina.database.ParasitePermissions
import net.chatalina.database.Parasites
import net.chatalina.isProduction
import net.chatalina.siteName
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

suspend fun Application.sendEmail(
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

suspend fun Application.sendAdminEmail(type: EmailTypes, args: Map<String, String> = emptyMap()) {
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

suspend fun Application.sendErrorEmail(error: Any?) {
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

    lateinit var emailFromAddress: String
    lateinit var emailApi: String
    lateinit var emailUser: String
    lateinit var emailPass: String

    private lateinit var ktorClient: HttpClient

    private val pebbleForEmails =
        PebbleEngine.Builder().loader(ClasspathLoader().apply {
            prefix = "templates/email"
            charset = "UTF-8"
        }).build()

    fun configure(
        fromAddress: String,
        api: String,
        user: String,
        pass: String,
    ) {
        logger.debug("Initializing email handler...")
        emailFromAddress = fromAddress
        emailApi = api
        if (user.isBlank() != pass.isBlank()) {
            throw ApplicationConfigurationException("User and password must both be set to work.")
        }
        emailUser = user
        emailPass = pass

        logger.info("Email handler initialized.")

        ktorClient = HttpClient(OkHttp) {
            install(ContentNegotiation) {
                jackson()
            }
        }
    }

    internal suspend fun sendEmail(
        type: EmailTypes,
        siteName: String,
        args: Map<String, String>,
        vararg recipients: Parasites.ParasiteObject
    ) {
        logger.debug("Sending email")

        if (recipients.isEmpty()) logger.error("Unable to send email to no recipients (type: {})", type.name)

        try {
            val emailArgs = args + ("site_name" to siteName)
            val htmlBody = type.getHtmlBody(pebbleForEmails, emailArgs)
            val textBody = type.getTextBody(emailArgs)

            val message = buildMap<String, Any> {
                put("From", mapOf("Email" to emailFromAddress, "Name" to "The $siteName Server <3"))
                put("To", recipients.mapNotNull { recipient ->
                    recipient.takeIf { EmailValidator.getInstance().isValid(it.email) }?.let {
                        mapOf("Email" to it.email, "Name" to it.name)
                    } ?: also {
                        logger.error(
                            "Unable to send email - parasite has not provided a valid one (type: {}, recipient: {} ({}))",
                            type.name,
                            recipient.name,
                            recipient.email
                        )
                    }
                })
                put("subject", type.subject)
                put("HTMLPart", htmlBody)
                put("TextPart", textBody)
            }
            println(message)

            val r = ktorClient.post(emailApi) {
                setBody(mapOf("Messages" to listOf(message)))
                contentType(ContentType.Application.Json)
                basicAuth(emailUser, emailPass)
            }
            if (r.status == HttpStatusCode.OK) {
                logger.info("Sent message: {}", r.bodyAsText())
            } else {
                logger.error("Failed to send email: {}", r.bodyAsText())
            }
        } catch (e: Exception) {
            logger.error("Error sending email:")
            throw e
        }
    }
}