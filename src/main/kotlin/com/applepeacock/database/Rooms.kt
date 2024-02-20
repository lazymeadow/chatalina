package com.applepeacock.database

import com.applepeacock.database.Messages.DAO.withRoomMessageHistory
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
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
        var history: List<Map<String, Any?>> = emptyList()
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

        fun list(forParasite: String) = list(EntityID(forParasite, Parasites))
        fun list(forParasite: EntityID<String>) = transaction {
            val query = Rooms.innerJoin(roomAccessQuery, { Rooms.id }, { roomAccessQuery[RoomAccess.room] })
                .select(
                    Rooms.id,
                    name,
                    owner,
                    roomAccessQuery[membersCol]
                )
                .where { roomAccessQuery[membersCol] any stringParam(forParasite.value) }
            val historyQuery = query.withRoomMessageHistory()

            query.toList()
                .groupBy { it[Rooms.id] }
                .map { (_, rows) ->
                    val msgs = rows.mapNotNull { m ->
                        m.getOrNull(historyQuery.alias[Messages.id])?.let {
                            Messages.DAO.resultRowToObject(m, historyQuery).toMessageBody()
                        }
                    }
                    resultRowToObject(rows.first()).apply { history = msgs }
                }
        }

        fun find(roomId: UUID) = find(EntityID(roomId, Rooms))
        fun find(roomId: EntityID<UUID>) = transaction {
            Rooms.innerJoin(roomAccessQuery, { Rooms.id }, { roomAccessQuery[RoomAccess.room] })
                .select(Rooms.id, name, owner, roomAccessQuery[membersCol])
                .where { Rooms.id eq roomId }
                .groupBy(Rooms.id, name, owner, roomAccessQuery[membersCol])
                .firstOrNull()?.let { resultRowToObject(it) }
        }

        fun addMember(roomId: EntityID<UUID>, parasiteId: EntityID<String>): RoomObject? = transaction {
            RoomAccess.upsert {
                it[room] = roomId
                it[parasite] = parasiteId
                it[inRoom] = true
                it[updated] = Clock.System.now()
            }
            RoomInvitations.DAO.deleteAll(roomId, parasiteId)
            find(roomId)
        }

        fun removeMember(roomId: EntityID<UUID>, parasiteId: EntityID<String>): RoomObject? = transaction {
            RoomAccess.upsert {
                it[room] = roomId
                it[parasite] = parasiteId
                it[inRoom] = false
                it[updated] = Clock.System.now()
            }
            RoomInvitations.DAO.deleteAll(roomId, parasiteId)
            find(roomId)
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

    override val primaryKey = PrimaryKey(room, invitee, sender)

    data class RoomInvitationObject(
        val room: EntityID<UUID>,
        val sender: EntityID<String>,
        val invitee: EntityID<String>,
        val created: Instant
    ) : ChatTable.ObjectModel() {
        val roomObject by lazy { Rooms.DAO.find(room.value) }
        val senderObject by lazy { Parasites.DAO.find(sender.value) }

        val senderName = senderObject?.name ?: sender.value
        val roomName = roomObject?.name ?: room.value
    }

    object DAO : ChatTable.DAO() {
        override fun resultRowToObject(row: ResultRow): RoomInvitationObject {
            return RoomInvitationObject(
                row[room],
                row[sender],
                row[invitee],
                row[created]
            )
        }

        fun list(parasiteId: EntityID<String>, roomId: EntityID<UUID>? = null): List<RoomInvitationObject> =
            transaction {
                RoomInvitations.innerJoin(Rooms).innerJoin(Parasites, { sender }, { id })
                    .selectAll()
                    .where { invitee eq parasiteId }
                    .also { query -> roomId?.let { query.andWhere { room eq roomId } } }
                    .map { resultRowToObject(it) }
            }

        fun create(
            roomId: EntityID<UUID>,
            senderId: EntityID<String>,
            parasiteId: EntityID<String>
        ): RoomInvitationObject? = transaction {
            RoomInvitations.upsert {
                it[room] = roomId
                it[sender] = senderId
                it[invitee] = parasiteId
                it[created] = Clock.System.now()
            }.resultedValues?.singleOrNull()?.let { resultRowToObject(it) }
        }

        fun deleteAll(roomId: EntityID<UUID>, parasiteId: EntityID<String>) = transaction {
            RoomInvitations.deleteWhere { (room eq roomId) and (invitee eq parasiteId) }
        }
    }
}
