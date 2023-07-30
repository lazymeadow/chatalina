package com.applepeacock.database

import at.favre.lib.crypto.bcrypt.BCrypt
import kotlinx.datetime.Instant
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction


data class ParasiteSettings(
    val displayName: String? = null,
    val color: String? = null,
    val volume: String? = null,
    val soundSet: String? = null,
    val faction: String? = null,
    val permission: String? = null
)

object Parasites : IdTable<String>("parasites"), ChatTable {
    override val id = text("id").entityId()
    val email = text("email")
    val active = bool("active")
    val lastActive = systemTimestamp("last_active").nullable()
    val settings = jsonb<ParasiteSettings>("settings").nullable()
    val created = systemTimestamp("created")
    val updated = systemTimestamp("updated")

    override val primaryKey = PrimaryKey(id)

    data class ParasiteObject(
        val id: EntityID<String>,
        val email: String,
        val active: Boolean,
        val lastActive: Instant?,
        val settings: ParasiteSettings,
        val created: Instant,
        val updated: Instant
    ) : ChatTable.ObjectModel()

    object DAO : ChatTable.DAO() {
        override fun resultRowToObject(row: ResultRow): ParasiteObject {
            return ParasiteObject(
                row[id],
                row[email],
                row[active],
                row[lastActive],
                row.getOrNull(settings) ?: ParasiteSettings(),
                row[created],
                row[updated]
            )
        }

        fun list(): List<ParasiteObject> = transaction {
            Parasites.selectAll().map { resultRowToObject(it) }
        }

        fun find(parasiteId: String): ParasiteObject? = transaction {
            Parasites.select { Parasites.id eq parasiteId }.singleOrNull()?.let { resultRowToObject(it) }
        }

        fun checkPassword(parasiteId: String, password: String): Boolean = transaction {
            val hashedPassword =
                ParasitePasswords.slice(ParasitePasswords.password).select { ParasitePasswords.parasite eq parasiteId }
                    .singleOrNull()?.get(ParasitePasswords.password)
            hashedPassword?.let {
                val verifyResult =
                    BCrypt.verifyer(BCrypt.Version.VERSION_2B)
                        .verify(password.toByteArray(), hashedPassword.toByteArray())
                verifyResult.verified
            } ?: false
        }

        fun newPasswordResetToken(parasiteId: String, token: String) = transaction {
            ParasitePasswords.update({ ParasitePasswords.parasite eq parasiteId }) {
                it[resetToken] = token
            }
        }

        fun checkToken(parasiteId: String, token: String): Boolean = transaction {
            ParasitePasswords.slice(ParasitePasswords.resetToken).select { ParasitePasswords.parasite eq parasiteId }
                .singleOrNull()?.getOrNull(ParasitePasswords.resetToken) == token
        }

        fun updatePassword(parasiteId: String, hashedPassword: ByteArray): Boolean = transaction {
            ParasitePasswords.update {
                it[parasite] = parasiteId
                it[password] = hashedPassword.decodeToString()
                it[resetToken] = null
            } == 1
        }

        fun exists(parasiteId: String): Boolean = transaction {
            Parasites.select { Parasites.id eq parasiteId }.count() > 0
        }

        fun create(newUserName: String, newEmail: String, hashedPassword: ByteArray): ParasiteObject? = transaction {
            Parasites.insert {
                it[id] = newUserName
                it[email] = newEmail
                it[active] = true
            }.resultedValues?.singleOrNull()?.let { resultRowToObject(it) }?.also { newParasite ->
                ParasitePasswords.insert {
                    it[parasite] = newParasite.id
                    it[password] = hashedPassword.decodeToString()
                }
            }
        }
    }
}

// keeping the passwords separate so we can remove them entirely someday
object ParasitePasswords : Table("parasite_passwords"), ChatTable {
    val parasite = reference("parasite_id", Parasites)
    val password = text("password")
    val resetToken = text("reset_token").nullable()

    override val primaryKey = PrimaryKey(parasite)
}
