package com.applepeacock.database

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

enum class MessageDestinationTypes {
    Room,
    Parasite;
}

data class MessageDestination(
    val id: String, val type: MessageDestinationTypes
)

object Messages : UUIDTable("messages"), ChatTable {
    val sender = reference("sender_id", Parasites)
    val destination = text("destination_id")
    val destinationType = enumerationByName<MessageDestinationTypes>("destination_type", 48)
    val data = jsonb<Map<*, *>>("data")
    val sent = systemTimestamp("sent")

    data class MessageObject(
        val id: EntityID<UUID>,
        val sender: EntityID<String>,
        val destination: MessageDestination,
        val data: Map<*, *>,
        val sent: Instant
    ) : ChatTable.ObjectModel()

    object DAO : ChatTable.DAO() {
        override fun resultRowToObject(row: ResultRow): MessageObject {
            return MessageObject(
                row[id],
                row[sender],
                MessageDestination(row[destination], row[destinationType]),
                row[data],
                row[sent]
            )
        }

        fun create(
            senderId: EntityID<String>,
            destinationInfo: MessageDestination,
            messageData: Map<*, *>
        ): MessageObject? = transaction {
            Messages.insert {
                it[sender] = senderId
                it[destination] = destinationInfo.id
                it[destinationType] = destinationInfo.type
                it[data] = messageData
                it[sent] = Clock.System.now()
            }.resultedValues?.singleOrNull()?.let { resultRowToObject(it) }
        }


    }
}