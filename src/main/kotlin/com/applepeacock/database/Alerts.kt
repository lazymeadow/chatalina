package com.applepeacock.database

import com.applepeacock.plugins.defaultMapper
import com.fasterxml.jackson.module.kotlin.convertValue
import kotlinx.datetime.Instant
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*


enum class AlertTypes {
    Fade,
    Dismiss,
    Permanent;

    override fun toString(): String {
        return super.toString().lowercase()
    }
}

class AlertData internal constructor(val message: String, val type: AlertTypes = AlertTypes.Fade, val dismissText: String? = null) {
    companion object {
        fun AlertData.toMap(): Map<String, String> = defaultMapper.convertValue(this)

        fun fade(message: String) = AlertData(message, AlertTypes.Fade)
        fun dismiss(message: String, dismissText: String) = AlertData(message, AlertTypes.Dismiss, dismissText)
        fun permanent(message: String) = AlertData(message, AlertTypes.Permanent)
    }
}

object Alerts : UUIDTable("alerts"), ChatTable {
    val parasite = reference("parasite_id", Parasites)
    val data = jsonb<AlertData>("data")
    val created = systemTimestamp("created")

    data class AlertObject(
        val id: EntityID<UUID>,
        val parasite: EntityID<String>,
        val data: AlertData,
        val created: Instant
    ) : ChatTable.ObjectModel()

    object DAO : ChatTable.DAO() {
        override fun resultRowToObject(row: ResultRow): AlertObject {
            return AlertObject(row[Alerts.id], row[parasite], row[data], row[created])
        }

        fun delete(alertId: UUID, parasiteId: EntityID<String>) = transaction {
            Alerts.deleteWhere { (Alerts.id eq alertId) and (parasite eq parasiteId) }
        }

        fun list(forParasite: String): List<AlertObject> = transaction {
            Alerts.select(Alerts.id, parasite, data, created)
                .where { parasite eq forParasite }
                .orderBy(created)
                .map { resultRowToObject(it) }
        }
    }
}