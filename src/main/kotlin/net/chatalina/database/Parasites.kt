package net.chatalina.database

import at.favre.lib.crypto.bcrypt.BCrypt
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import net.chatalina.chat.tokenEncrypt
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.coalesce
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestamp
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty1


enum class ParasitePermissions {
    Admin,
    Mod,
    User;

    override fun toString() = super.toString().lowercase()

    companion object {
        fun permissionLevelAccess(accessLevel: ParasitePermissions, permission: ParasitePermissions): Boolean {
            fun checkPermissionAccess(vararg allowed: ParasitePermissions) = allowed.any { it == accessLevel }
            return when (permission) {
                Admin -> checkPermissionAccess(Admin, Mod, User)
                Mod -> checkPermissionAccess(Mod, User)
                User -> checkPermissionAccess(User)
            }
        }
    }
}

data class ParasiteSettings(
    var displayName: String? = null,
    var color: String = "#555555",
    var volume: String = "100",
    var soundSet: String = "AIM",
    var faction: String = "rebel",
    var permission: ParasitePermissions = ParasitePermissions.User
)

inline fun <reified T : Any?> ParasiteSettings.setProperty(prop: KProperty1<ParasiteSettings, T>, newValue: T) {
    (prop as KMutableProperty1<ParasiteSettings, T>).set(this, newValue)
}

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
    ) : ChatTable.ObjectModel() {
        val name
            get() = this.settings.displayName ?: this.id.value
    }

    object DAO : ChatTable.DAO() {
        private val permissionCol =
            coalesce(settings.extract<String>("permission"), stringLiteral(ParasitePermissions.User.toString()))

        override fun resultRowToObject(row: ResultRow): ParasiteObject {
            val time = Clock.System.now()
            return ParasiteObject(
                row[id],
                row[email],
                row[active],
                row.getOrNull(lastActive),
                row.getOrNull(settings) ?: ParasiteSettings(),
                // on initial insert, exposed doesn't give us these values back. just use now.
                row.getOrNull(created) ?: time,
                row.getOrNull(updated) ?: time
            )
        }

        fun list(active: Boolean = true, permissionFilter: ParasitePermissions? = null): List<ParasiteObject> =
            transaction {
                val query = Parasites.selectAll().where { Parasites.active eq active }
                permissionFilter?.also { query.andWhere { permissionCol eq stringParam(permissionFilter.toString()) } }
                query.orderBy(coalesce(settings.extract("displayName"), Parasites.id))
                    .map { resultRowToObject(it) }
            }

        fun listWithoutPermissions(
            excludeParasiteId: String,
            vararg permissions: ParasitePermissions
        ): List<Map<String, String>> = listForPermissions(
            excludeParasiteId,
            false,
            permissions.map { it.toString() }
        )

        fun listWithPermissions(
            excludeParasiteId: String,
            vararg permissions: ParasitePermissions
        ): List<Map<String, String>> = listForPermissions(
            excludeParasiteId,
            true,
            permissions.map { it.toString() }
        )

        private fun listForPermissions(
            excludeParasiteId: String,
            hasPermission: Boolean,
            permissionStrings: List<String>
        ): List<Map<String, String>> = transaction {
            Parasites.select(Parasites.id, settings)
                .where { Parasites.id neq excludeParasiteId }
                .also {
                    if (hasPermission) {
                        it.andWhere { permissionCol inList permissionStrings }
                    } else {
                        it.andWhere { permissionCol notInList permissionStrings }
                    }
                }.map { row ->
                    val rowSettings = row.getOrNull(settings) ?: ParasiteSettings()
                    mapOf(
                        "username" to (
                                rowSettings.displayName?.let { "${it} (${row[Parasites.id]})" }
                                        ?: row[Parasites.id].value
                                ),
                        "id" to row[Parasites.id].value
                    )
                }
        }

        fun find(parasiteId: String): ParasiteObject? = transaction {
            Parasites.selectAll().where { Parasites.id eq parasiteId }.singleOrNull()?.let { resultRowToObject(it) }
        }

        fun find(vararg parasiteIds: String): List<ParasiteObject> = transaction {
            Parasites.selectAll().where { Parasites.id inList parasiteIds.toList() }.map { resultRowToObject(it) }
        }

        fun checkPassword(parasiteId: String, password: String): Boolean = transaction {
            val hashedPassword =
                ParasitePasswords.select(ParasitePasswords.password).where { ParasitePasswords.parasite eq parasiteId }
                    .singleOrNull()?.get(ParasitePasswords.password)
            hashedPassword?.let {
                val verifyResult =
                    BCrypt.verifyer(BCrypt.Version.VERSION_2B)
                        .verify(password.toByteArray(), hashedPassword.toByteArray())
                verifyResult.verified
            } ?: false
        }

        fun newPasswordResetToken(parasiteId: String) = transaction {
            tokenEncrypt(parasiteId).also { token ->
                ParasitePasswords.update({ ParasitePasswords.parasite eq parasiteId }) {
                    it[resetToken] = token
                    it[updated] = CurrentTimestamp
                }
            }
        }

        fun checkToken(parasiteId: EntityID<String>, token: String): Boolean = transaction {
            ParasitePasswords.select(ParasitePasswords.resetToken).where { ParasitePasswords.parasite eq parasiteId }
                .singleOrNull()?.getOrNull(ParasitePasswords.resetToken) == token
        }

        fun updatePassword(parasiteId: EntityID<String>, hashedPassword: ByteArray): Boolean = transaction {
            ParasitePasswords.upsert {
                it[parasite] = parasiteId
                it[password] = hashedPassword.decodeToString()
                it[resetToken] = null
                it[updated] = CurrentTimestamp
            }.insertedCount > 0
        }

        fun isValidUsername(newUserName: String): Boolean = transaction {
            Parasites.selectAll().where { Parasites.id eq newUserName }
                .orWhere { settings.extract<String>("username") eq newUserName }
                .count() == 0L
        }

        fun exists(parasiteId: String): Boolean = transaction {
            Parasites.selectAll().where { Parasites.id eq parasiteId }.count() > 0
        }

        fun isActive(parasiteId: String): Boolean = transaction {
            Parasites.select(active).where{Parasites.id eq parasiteId}.firstOrNull()?.get(active) ?: false
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

        fun update(parasite: ParasiteObject, newEmail: String? = null, newSettings: ParasiteSettings? = null) =
            transaction {
                if (!newEmail.isNullOrBlank() || newSettings != null) {
                    Parasites.update({ Parasites.id eq parasite.id }) {
                        newEmail?.let { e -> it[email] = e }
                        newSettings?.let { s -> it[settings] = s }
                        it[updated] = CurrentTimestamp
                    }
                }
            }

        fun setLastActive(parasiteId: EntityID<String>) = transaction {
            Parasites.update({ Parasites.id eq parasiteId }) {
                it[lastActive] = CurrentTimestamp
                it[updated] = CurrentTimestamp
            }
        }

        fun updatePermission(parasiteId: EntityID<String>, newPermission: ParasitePermissions) = transaction {
            Parasites.update({ Parasites.id eq parasiteId }) {
                it[settings] = settings.setJsonbValue(
                    ParasiteSettings::permission.name,
                    newValue = newPermission.toString(),
                    createIfMissing = true
                )
                it[updated] = CurrentTimestamp
            }
        }

        fun setActive(parasiteId: EntityID<String>, isActive: Boolean) = transaction {
            Parasites.update({ Parasites.id eq parasiteId }) {
                if (!isActive) {
                    it[settings] = null
                }
                it[active] = isActive
                it[updated] = CurrentTimestamp
            }
        }
    }
}

// keeping the passwords separate so we can remove them entirely someday
object ParasitePasswords : Table("parasite_passwords"), ChatTable {
    val parasite = reference("parasite_id", Parasites)
    val password = text("password")
    val resetToken = text("reset_token").nullable()
    val updated = systemTimestamp("updated")

    override val primaryKey = PrimaryKey(parasite)
}
