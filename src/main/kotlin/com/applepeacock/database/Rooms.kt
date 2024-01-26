package com.applepeacock.database

import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
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
        val members: List<String>,
        var history: List<Map<String, Any>> = emptyList()
    ) : ChatTable.ObjectModel()

    object DAO : ChatTable.DAO() {
        private val membersCol = RoomAccess.parasite.arrayAgg().alias("members")
        private val roomAccessQuery = RoomAccess.select(RoomAccess.room, membersCol)
            .where { RoomAccess.inRoom eq true }
            .groupBy(RoomAccess.room)
            .alias("access")

        override fun resultRowToObject(row: ResultRow): RoomObject {
            return RoomObject(
                row[id],
                row[name],
                row[owner],
                row[roomAccessQuery[membersCol]].toList()
            )
        }


        fun sparseList(forParasite: String? = null, withMembers: Boolean = false, onlyEmpty: Boolean = false) =
            transaction {
                Rooms.innerJoin(roomAccessQuery, { Rooms.id }, { roomAccessQuery[RoomAccess.room] })
                    .select(Rooms.id, name, roomAccessQuery[membersCol])
                    .also { q ->
                        forParasite?.let { p ->
                            q.where { roomAccessQuery[membersCol] any stringParam(p) }
                        }
                        if (onlyEmpty) {
                            q.where {
                                CustomFunction<Int>(
                                    "CARDINALITY",
                                    IntegerColumnType(),
                                    roomAccessQuery[membersCol]
                                ) eq intLiteral(0)
                            }
                        }
                    }.map {
                        buildMap {
                            put("id", it[Rooms.id])
                            put("name", it[name])
                            if (withMembers) {
                                put("members", it[roomAccessQuery[membersCol]])
                            }
                        }
                    }
            }

        fun list(forParasite: String) = transaction {
            Rooms.innerJoin(roomAccessQuery, { Rooms.id }, { roomAccessQuery[RoomAccess.room] })
                .leftJoin(
                    Messages.messageHistoryQuery,
                    { Rooms.id.castTo<String>(VarCharColumnType()) },
                    { Messages.messageHistoryQuery[Messages.destination] },
                    { Messages.messageHistoryQuery[Messages.destinationType] eq MessageDestinationTypes.Room })
                .select(
                    Rooms.id,
                    name,
                    owner,
                    roomAccessQuery[membersCol],
                    Messages.messageHistoryQuery[Messages.messagesCol]
                )
                .where { roomAccessQuery[membersCol] any stringParam(forParasite) }
                .map {
                    resultRowToObject(it).also { room ->
                        room.history = Messages.parseMessagesCol(
                            it.getOrNull(Messages.messageHistoryQuery[Messages.messagesCol])
                        )
                    }
                }
        }

        fun get(roomId: UUID) = transaction {
            Rooms.innerJoin(roomAccessQuery, { Rooms.id }, { roomAccessQuery[RoomAccess.room] })
                .select(Rooms.id, name, owner, roomAccessQuery[membersCol])
                .where { Rooms.id eq roomId }
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
