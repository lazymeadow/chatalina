package com.applepeacock.database

import org.jetbrains.exposed.dao.id.UUIDTable


data class AlertData(val type: String, val message: String, val dismissText: String)

object Alerts : UUIDTable("alerts"), ChatTable {
    val parasite = reference("parasite_id", Parasites)
    val data = jsonb<AlertData>("data")
    val created = systemTimestamp("created")
}