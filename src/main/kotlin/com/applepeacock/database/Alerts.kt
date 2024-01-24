package com.applepeacock.database

import com.applepeacock.plugins.defaultMapper
import com.fasterxml.jackson.module.kotlin.convertValue
import org.jetbrains.exposed.dao.id.UUIDTable


data class AlertData(val type: String, val message: String, val dismissText: String) {
    companion object {
        fun AlertData.toMap(): Map<String, String> = defaultMapper.convertValue(this)
    }
}

object Alerts : UUIDTable("alerts"), ChatTable {
    val parasite = reference("parasite_id", Parasites)
    val data = jsonb<AlertData>("data")
    val created = systemTimestamp("created")
}