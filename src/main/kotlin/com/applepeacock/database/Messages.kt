package com.applepeacock.database

import com.applepeacock.plugins.defaultMapper
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonTypeRef
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.toJavaInstant
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.kotlin.datetime.KotlinOffsetDateTimeColumnType
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.OffsetDateTime
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
    val data = jsonb<Map<String, Any?>>("data")
    val sent = systemTimestamp("sent")

    data class MessageObject(
        val id: EntityID<UUID>,
        val sender: EntityID<String>,
        val destination: MessageDestination,
        val data: Map<String, Any?>,
        val sent: Instant
    ) : ChatTable.ObjectModel()

    val messagesCol =
        jsonbAgg(
            jsonBuildObject(
                "id" to Messages.id, "data" to data, "sent" to sent.castTo<OffsetDateTime>(
                    KotlinOffsetDateTimeColumnType()
                ), "sender" to sender, "destination" to destination
            ),
            Array::class.java,
            orderByCol = sent
        ).alias("messages")
    val messageHistoryQuery =  // TODO: limit # messages returned
        Messages.slice(destination, destinationType, messagesCol).selectAll()
            .groupBy(destination, destinationType).alias("history")

    fun parseMessagesCol(msgs: Any?) =
        defaultMapper.convertValue<Array<Map<String, Any>>>(msgs, jacksonTypeRef())?.mapNotNull {
            val dataVal = it["data"] ?: return@mapNotNull null
            val idVal = it["id"] ?: return@mapNotNull null
            val sentVal = it["sent"] ?: return@mapNotNull null
            defaultMapper.convertValue<Map<String, Any>>(dataVal).plus("id" to idVal)
                .plus("time" to Instant.parse(sentVal.toString()).toJavaInstant())
        }?.toTypedArray() ?: emptyArray()

    fun parsePrivateMessagesCol(msgs: Any?) =
        defaultMapper.convertValue<Array<Map<String, Any>>>(msgs, jacksonTypeRef())?.mapNotNull {
            val dataVal = it["data"] ?: return@mapNotNull null
            val idVal = it["id"] ?: return@mapNotNull null
            val sentVal = it["sent"] ?: return@mapNotNull null
            val senderVal = it["sender"] ?: return@mapNotNull null
            val recipientVal = it["destination"] ?: return@mapNotNull null
            defaultMapper.convertValue<Map<String, Any>>(dataVal).plus("id" to idVal)
                .plus("time" to Instant.parse(sentVal.toString()).toJavaInstant()).plus("sender id" to senderVal)
                .plus("recipient id" to recipientVal)
        }?.toTypedArray() ?: emptyArray()

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
            messageData: Map<String, Any?>,
            callback: (MessageObject) -> Unit = {}
        ): MessageObject? = transaction {
            Messages.insert {
                it[sender] = senderId
                it[destination] = destinationInfo.id
                it[destinationType] = destinationInfo.type
                it[data] = messageData
                it[sent] = Clock.System.now()
            }.resultedValues?.singleOrNull()?.let { resultRowToObject(it) }?.also(callback)
        }

        fun update(messageId: EntityID<UUID>, newData: Map<String, Any?>) = transaction {
            Messages.update ({ Messages.id eq messageId }) {
                it[data] = newData
            }
        }
        fun list(parasiteId: String): List<Map<String, Any>> = transaction {
            // TODO: limit # messages returned
            val recipientCol: ExpressionAlias<String> =
                Case().When((destination eq parasiteId), sender.castTo<String>(VarCharColumnType())).Else(destination)
                    .alias("recipient")
            Messages.slice(messagesCol, recipientCol).selectAll()
                .andWhere { destinationType eq MessageDestinationTypes.Parasite }
                .andWhere { (destination eq parasiteId) or (sender eq parasiteId) }
                .groupBy(recipientCol)
                .map {
                    mapOf("recipient id" to it[recipientCol], "messages" to parsePrivateMessagesCol(it[messagesCol]))
                }
        }
    }
}