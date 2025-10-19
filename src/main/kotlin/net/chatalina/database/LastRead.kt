package net.chatalina.database

import org.jetbrains.exposed.sql.Table

object LastRead : Table("last_read"), ChatTable {
    val parasite = reference("parasite_id", Parasites)
    val destination = text("destination_id")
    val message = reference("message_id", Messages)
    val updated = systemTimestamp("updated")

    override val primaryKey = PrimaryKey(parasite, destination)
}
