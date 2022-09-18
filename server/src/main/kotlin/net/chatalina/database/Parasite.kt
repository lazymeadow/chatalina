package net.chatalina.database

import net.chatalina.chat.DestinationType
import net.chatalina.chat.JID
import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestamp
import java.util.*

object Parasites : UUIDTable() {
    val jid = integer("jid").autoIncrement()
    val displayName = varchar("display_name", 64)
    val active = bool("active").default(true)
    val lastActive = timestamp("last_active").defaultExpression(CurrentTimestamp())
    val created = timestamp("created").defaultExpression(CurrentTimestamp())
    val updated = timestamp("updated").defaultExpression(CurrentTimestamp())
}

class Parasite(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<Parasite>(Parasites)

    var jid by Parasites.jid
    var displayName by Parasites.displayName
    var active by Parasites.active
    var lastActive by Parasites.lastActive
    var created by Parasites.created
    var updated by Parasites.updated

    fun getJID(jidDomain: String): JID = JID(DestinationType.PARASITE, jid, jidDomain)
}

object Settings : Table() {
    val parasiteId = reference("parasite_id", Parasites)
    val settingName = varchar("name", 32)
    val settingValue = varchar("value", 128)
    val created = timestamp("created").defaultExpression(CurrentTimestamp())
    val updated = timestamp("updated").defaultExpression(CurrentTimestamp())
}


