package net.chatalina.database

import net.chatalina.chat.EncryptedData
import net.chatalina.chat.dbDecrypt
import net.chatalina.chat.dbEncrypt
import net.chatalina.historyLimit
import net.chatalina.plugins.defaultMapper
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.rowNumber
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestamp
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*
import javax.crypto.BadPaddingException

enum class MessageDestinationTypes {
    Room,
    Parasite;
}

data class MessageDestination(
    val id: String, val type: MessageDestinationTypes
)


private fun messageDecrypt(message: EncryptedData): MessageData {
    return defaultMapper.readValue(dbDecrypt(message))
}

private fun messageEncrypt(content: MessageData): EncryptedData {
    return dbEncrypt(defaultMapper.writeValueAsString(content))
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class MessageData(
    val username: String,
    val color: String,
    val message: String? = null,
    @JsonProperty("image url") val url: String? = null,
    @JsonProperty("image src url") val src: String? = null,
    @JsonProperty("nsfw flag") val nsfw: Boolean? = null,
    @JsonProperty("track link") val trackLink: String? = null
) {
    fun toMap(): Map<String, Any?> = defaultMapper.convertValue(this)

    companion object {
        fun ChatMessageData(username: String, color: String, message: String) =
            MessageData(username, color, message = message)

        fun ImageMessageData(username: String, color: String, nsfw: Boolean, url: String?, src: String? = null) =
            MessageData(username, color, nsfw = nsfw, url = url, src = src ?: url)

        fun GorillaGrooveMessageData(username: String, color: String, trackLink: String) =
            MessageData(username, color, trackLink = trackLink)
    }
}

object Messages : UUIDTable("messages"), ChatTable {
    val sender = reference("sender_id", Parasites)
    val destination = text("destination_id")
    val destinationType = enumerationByName<MessageDestinationTypes>("destination_type", 48)
    val data = jsonb<EncryptedData>("data")
    val sent = systemTimestamp("sent")

    data class MessageObject(
        val id: EntityID<UUID>,
        val sender: EntityID<String>,
        val destination: MessageDestination,
        val data: MessageData,
        val sent: Instant
    ) : ChatTable.ObjectModel() {
        @JsonValue
        fun toMessageBody() = buildMap {
            put("id", id)
            put("time", sent)
            when (destination.type) {
                MessageDestinationTypes.Parasite -> {
                    put("sender id", sender)
                    put("recipient id", destination.id)
                }
                MessageDestinationTypes.Room -> {
                    put("room id", destination.id)
                }
            }
            putAll(data.toMap())
        }
    }


    object DAO : ChatTable.DAO() {
        override fun resultRowToObject(row: ResultRow): MessageObject {
            return resultRowToObject(row, null)
        }

        internal fun resultRowToObject(row: ResultRow, subQuery: MessageHistorySubQuery? = null): MessageObject {
            fun <T : Any?> getVal(col: Column<T>) = subQuery?.let { row[it.alias[col]] } ?: row[col]
            val messageData = try {
                messageDecrypt(getVal(data))
            } catch (e: BadPaddingException) {
                MessageData(getVal(sender).value, "", "Message could not be decrypted.")
            }
            return MessageObject(
                getVal(id),
                getVal(sender),
                MessageDestination(getVal(destination), getVal(destinationType)),
                messageData,
                getVal(sent)
            )
        }

        fun create(
            senderId: EntityID<String>,
            destinationInfo: MessageDestination,
            messageData: MessageData,
            callback: (MessageObject) -> Unit = {}
        ): MessageObject? = transaction {
            Messages.insert {
                it[sender] = senderId
                it[destination] = destinationInfo.id
                it[destinationType] = destinationInfo.type
                it[data] = messageEncrypt(messageData)
                it[sent] = Clock.System.now()
            }.resultedValues?.singleOrNull()?.let {
                try {
                    resultRowToObject(it)
                } catch (e: BadPaddingException) {
                    null
                }
            }?.also(callback)
        }

        fun list(parasiteId: EntityID<String>): List<Map<String, Any>> = transaction {
            val recipientCol =
                Case().When((destination eq parasiteId.value), sender.asString()).Else(destination).alias("r")

            val subQuery = selectPrivateMessageHistory(recipientCol, parasiteId)

            Select(subQuery.alias, subQuery.alias.fields).selectAll()
                .where { subQuery.getHistoryLengthCondition() }
                .toList()
                .groupBy(
                    { it[recipientCol.aliasOnlyExpression()] },
                    { resultRowToObject(it, subQuery) })
                .map { (r, m) -> mapOf("recipient id" to r, "messages" to m.sortedBy { it.sent }) }
        }

        /** FOR MESSAGE HISTORY **/

        private fun selectPrivateMessageHistory(countOver: Expression<*>, parasiteId: EntityID<String>) =
            PrivateMessageHistorySubQuery(countOver, parasiteId)

        internal fun Query.withRoomMessageHistory(): MessageHistorySubQuery {
            val subQuery = RoomMessageHistorySubQuery()

            this.adjustColumnSet {
                leftJoin(subQuery.alias, { Rooms.id.asString() }, { subQuery.alias[destination] })
            }
                .adjustSelect { select(it.fields + subQuery.alias.fields) }
                .andWhere { subQuery.getHistoryLengthCondition() }
            return subQuery
        }

        private class PrivateMessageHistorySubQuery(
            countOver: Expression<*>,
            parasiteId: EntityID<String>
        ) : MessageHistorySubQuery(
            MessageDestinationTypes.Parasite,
            countOver,
            (destination eq parasiteId.value) or (sender eq parasiteId)
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
            }
        }

    }
}