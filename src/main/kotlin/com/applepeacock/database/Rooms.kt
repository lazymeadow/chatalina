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
        val members: Array<String>,
        val history: Array<Map<String, Any>> = emptyArray()
    ) : ChatTable.ObjectModel()

    object DAO : ChatTable.DAO() {
        private val membersCol = RoomAccess.parasite.arrayAgg().alias("members")
        private val roomAccessQuery = RoomAccess.slice(RoomAccess.room, membersCol)
            .select { RoomAccess.inRoom eq true }
            .groupBy(RoomAccess.room)
            .alias("access")

        override fun resultRowToObject(row: ResultRow): RoomObject {
            return RoomObject(row[id], row[name], row[owner], row[roomAccessQuery[membersCol]])
        }

        fun list(forParasite: String) = transaction {
            Rooms.innerJoin(roomAccessQuery, { Rooms.id }, { roomAccessQuery[RoomAccess.room] })
                .slice(Rooms.id, name, owner, roomAccessQuery[membersCol])
                .select { roomAccessQuery[membersCol] any stringParam(forParasite) }
                .groupBy(Rooms.id, name, owner, roomAccessQuery[membersCol])
                .map { resultRowToObject(it) }
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
