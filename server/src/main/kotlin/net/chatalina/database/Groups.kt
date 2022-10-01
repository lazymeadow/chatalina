package net.chatalina.database

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll

object Groups : IntIdTable() {
    val name = text("name")
    val created = timestamp("created").defaultExpression(CurrentTimestamp())
    val updated = timestamp("updated").defaultExpression(CurrentTimestamp())
}

enum class GroupRoles {
    NONE,  // no group access, has been removed
    MEMBER,  // member can just chat or leave
    MOD,  // group moderator can manage but not delete
    ADMIN  // group admin can delete, first user always admin
}

object GroupParasites : Table("group_parasites") {
    val group = reference("group_id", Groups)
    val parasite = reference("parasite_id", Parasites)
    val role = enumerationByName<GroupRoles>("role", 16)  // length because varchar
    val created = timestamp("created").defaultExpression(CurrentTimestamp())
    val updated = timestamp("updated").defaultExpression(CurrentTimestamp())

    override val primaryKey = PrimaryKey(group, parasite)

    fun doesParasiteHaveAccess(parasite: Parasite, groupID: Int, roleToCheck: GroupRoles? = null): Boolean {
        // parasite should have access or higher, because all permissions are cumulative
        val foundRole = GroupParasites.slice(role).selectAll()
            .andWhere { GroupParasites.parasite eq parasite.id }
            .andWhere { group eq groupID }
            .singleOrNull()?.getOrNull(role)  // if we managed to get more rows for this something is so so wrong
            ?: return false

        return roleToCheck?.let { foundRole >= it } ?: (foundRole != GroupRoles.NONE)
    }

    fun getAdmins(groupID: Int): List<Parasite> {
        val query = GroupParasites.innerJoin(Parasites)
            .slice(Parasites.fields)
            .select { group eq groupID }.andWhere { role eq GroupRoles.ADMIN }
        return Parasite.wrapRows(query).toList()
    }
}