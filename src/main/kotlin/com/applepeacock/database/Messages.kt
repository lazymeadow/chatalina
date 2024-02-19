package com.applepeacock.database

import com.applepeacock.historyLimit
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.toJavaInstant
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.rowNumber
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
    val data = jsonb<Map<String, Any?>>("data")
    val sent = systemTimestamp("sent")

    data class MessageObject(
        val id: EntityID<UUID>,
        val sender: EntityID<String>,
        val destination: MessageDestination,
        val data: Map<String, Any?>,
        val sent: Instant
    ) : ChatTable.ObjectModel() {
        fun toMessageBody() = buildMap {
            put("id", id)
            put("time", sent.toJavaInstant())
            when (this@MessageObject.destination.type) {
                MessageDestinationTypes.Parasite -> {
                    put("sender id", sender)
                    put("recipient id", destination.id)
                }
                MessageDestinationTypes.Room -> {
                    put("room id", destination.id)
                }
            }
            putAll(data)
        }
    }


    object DAO : ChatTable.DAO() {
        override fun resultRowToObject(row: ResultRow): MessageObject {
            return resultRowToObject(row, null)
        }

        internal fun resultRowToObject(row: ResultRow, subQuery: MessageHistorySubQuery? = null): MessageObject {
            fun <T : Any?> getVal(col: Column<T>) = subQuery?.let { row[it.alias[col]] } ?: row[col]
            return MessageObject(
                getVal(id),
                getVal(sender),
                MessageDestination(getVal(destination), getVal(destinationType)),
                getVal(data),
                getVal(sent)
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
            Messages.update({ Messages.id eq messageId }) {
                it[data] = newData
            }
        }

        fun list(parasiteId: String): List<Map<String, Any>> = transaction {
            val recipientCol = Case().When((destination eq parasiteId), sender.castTo<String>(VarCharColumnType()))
                .Else(destination).alias("r")

            val subQuery = selectPrivateMessageHistory(recipientCol, parasiteId)

            Select(subQuery.alias, subQuery.alias.fields).selectAll()
                .where { subQuery.getHistoryLengthCondition() }
                .toList()
                .groupBy(
                    { it[recipientCol.aliasOnlyExpression()] },
                    { resultRowToObject(it, subQuery).toMessageBody() })
                .map { (r, m) -> mapOf("recipient id" to r, "messages" to m) }
        }

        /** FOR MESSAGE HISTORY **/

        private fun selectPrivateMessageHistory(countOver: Expression<*>, parasiteId: String) =
            PrivateMessageHistorySubQuery(countOver, parasiteId)

        internal fun Query.withRoomMessageHistory(): MessageHistorySubQuery {
            val subQuery = RoomMessageHistorySubQuery()

            this.adjustColumnSet {
                leftJoin(
                    subQuery.alias,
                    { Rooms.id.castTo<String>(VarCharColumnType()) },
                    { subQuery.alias[destination] })
            }
                .adjustSelect { select(it.fields + subQuery.alias.fields) }
                .andWhere { subQuery.getHistoryLengthCondition() }
            return subQuery
        }

        private class PrivateMessageHistorySubQuery(
            countOver: Expression<*>,
            parasiteId: String
        ) : MessageHistorySubQuery(
            MessageDestinationTypes.Parasite,
            countOver,
            (destination eq parasiteId) or (sender eq parasiteId)
        ) {
            init {
                adjustSelect { select(it.fields + countOver) }
            }
        }

        internal class RoomMessageHistorySubQuery() : MessageHistorySubQuery(MessageDestinationTypes.Room, destination)

        internal abstract class MessageHistorySubQuery(
            destType: MessageDestinationTypes,
            countOver: Expression<*>,
            where: Op<Boolean>? = null
        ) : Query(Messages, (destinationType eq destType)) {
            private val numCol: ExpressionAlias<Long> =
                rowNumber().over().partitionBy(
                    when (countOver) {
                        is ExpressionAlias<*> -> countOver.delegate
                        else -> countOver
                    }
                ).orderBy(sent, SortOrder.DESC).alias("n")

            private fun alias() = this.alias("m")

            val alias: QueryAlias by lazy { alias() }
            fun getHistoryLengthCondition() =
                this.alias[numCol].isNull() or (this.alias[numCol] lessEq longLiteral(historyLimit.toLong()))

            init {
                adjustSelect { select(it.fields + numCol) }
                where?.let { andWhere { where } }
                orderBy(sent, SortOrder.ASC)
            }
        }

    }
}