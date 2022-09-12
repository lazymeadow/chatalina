package net.chatalina.database

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestamp

object Groups : IntIdTable() {
    val name = text("name")
    val created = timestamp("created").defaultExpression(CurrentTimestamp())
    val updated = timestamp("updated").defaultExpression(CurrentTimestamp())
}

enum class GroupRoles {
    NONE,  // no group access, has been removed
    MEMBER,  // member can just chat or leave
    MOD,  // group moderator can manage but not delete
    OWNER  // group owner can delete
}

object GroupParasites : Table("group_parasites") {
    val group = reference("group_id", Groups)
    val parasite = reference("parasite_id", Parasites)
    val role = enumerationByName<GroupRoles>("role", 16)  // length because varchar
    val created = timestamp("created").defaultExpression(CurrentTimestamp())
    val updated = timestamp("updated").defaultExpression(CurrentTimestamp())

    override val primaryKey = PrimaryKey(group, parasite)
}