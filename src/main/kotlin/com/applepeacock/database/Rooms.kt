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
import org.jetbrains.exposed.sql.kotlin.datetime.CustomTimeStampFunction
import org.jetbrains.exposed.sql.kotlin.datetime.KotlinInstantColumnType
import org.jetbrains.exposed.sql.kotlin.datetime.KotlinOffsetDateTimeColumnType
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.*

object Rooms : UUIDTable("rooms"), ChatTable {
    val owner = reference("owner_id", Parasites)
    val name = text("name")
    val created = systemTimestamp("created")
    val updated = systemTimestamp("updated")

    data class RoomObject(
        val id: EntityID<UUID>,
        val name: String,
        val owner: EntityID<String>,
        val members: Array<String>,
        val history: Array<Map<String, Any>> = emptyArray()
    ) : ChatTable.ObjectModel()

    object DAO : ChatTable.DAO() {
        private val membersCol = RoomAccess.parasite.arrayAgg().alias("members")
        private val roomAccessQuery = RoomAccess.slice(RoomAccess.room, membersCol)
            .select { RoomAccess.inRoom eq true }
            .groupBy(RoomAccess.room)
            .alias("access")

        /*
        select destination_id,
                           destination_type,
                           json_agg(json_build_object('id', id, 'data', data, 'sent', sent) order by sent) as message
                    from messages
                    group by destination_id, destination_type
         */
        private val messagesCol =
            jsonbAgg(
                jsonBuildObject("id" to Messages.id, "data" to Messages.data, "sent" to Messages.sent.castTo<OffsetDateTime>(
                    KotlinOffsetDateTimeColumnType()
                )),
                Array::class.java,
                orderByCol = Messages.sent
            ).alias("messages")
        private val messageHistoryQuery =
            Messages.slice(Messages.destination, Messages.destinationType, messagesCol).selectAll()
                .groupBy(Messages.destination, Messages.destinationType).alias("history")

        override fun resultRowToObject(row: ResultRow): RoomObject {
            return RoomObject(
                row[id],
                row[name],
                row[owner],
                row[roomAccessQuery[membersCol]],
                defaultMapper.convertValue<Array<Map<String, Any>>>(row.getOrNull(messageHistoryQuery[messagesCol]), jacksonTypeRef())?.mapNotNull {
                    val dataVal = it["data"] ?: return@mapNotNull null
                    val idVal = it["id"] ?: return@mapNotNull null
                    val sentVal= it["sent"] ?: return@mapNotNull null
                    defaultMapper.convertValue<Map<String, Any>>(dataVal).plus("id" to idVal).plus("time" to Instant.parse(sentVal.toString()).toJavaInstant())
                }?.toTypedArray()
                        ?: emptyArray()
            )
        }

        fun list(forParasite: String) = transaction {
            Rooms.innerJoin(roomAccessQuery, { Rooms.id }, { roomAccessQuery[RoomAccess.room] })
                .leftJoin(
                    messageHistoryQuery,
                    { Rooms.id.castTo<String>(VarCharColumnType()) },
                    { messageHistoryQuery[Messages.destination] },
                    { messageHistoryQuery[Messages.destinationType] eq MessageDestinationTypes.Room })
                .slice(Rooms.id, name, owner, roomAccessQuery[membersCol], messageHistoryQuery[messagesCol])
                .select { roomAccessQuery[membersCol] any stringParam(forParasite) }
                .map { resultRowToObject(it) }
        }

        fun get(roomId: UUID) = transaction {
            Rooms.innerJoin(roomAccessQuery, { Rooms.id }, { roomAccessQuery[RoomAccess.room] })
                .slice(Rooms.id, name, owner, roomAccessQuery[membersCol])
                .select { Rooms.id eq roomId }
                .groupBy(Rooms.id, name, owner, roomAccessQuery[membersCol])
                .firstOrNull()?.let { resultRowToObject(it) }
        }
    }
}

object RoomAccess : Table("room_access"), ChatTable {
    val parasite = reference("parasite_id", Parasites)
    val room = reference("room_id", Rooms)
    val inRoom = bool("in_room")
    val updated = systemTimestamp("updated")

    override val primaryKey = PrimaryKey(parasite, room)
}

object RoomInvitations : Table("room_invitations"), ChatTable {
    val room = reference("room_id", Rooms)
    val invitee = reference("invitee_id", Parasites)
    val sender = reference("sender_id", Parasites)
    val created = systemTimestamp("created")
}
