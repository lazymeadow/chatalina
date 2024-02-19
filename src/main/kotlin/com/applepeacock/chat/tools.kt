package com.applepeacock.chat

import com.applepeacock.database.AlertData
import com.applepeacock.database.ParasitePermissions
import com.applepeacock.database.Parasites
import com.applepeacock.database.Rooms
import com.applepeacock.plugins.defaultMapper
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import org.jetbrains.exposed.dao.id.EntityID

enum class ToolTypes(val value: String? = null) {
    Grant,
    Room,
    RoomOwner("room owner"),
    Parasite,
    Data;

    override fun toString(): String {
        return this.value ?: this.name.lowercase()
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ToolDefinition<R, E : Any>(
    @JsonIgnore val id: String,
    @JsonProperty("perm level") val accessLevel: ParasitePermissions,
    @JsonProperty("tool type") val type: ToolTypes,
    @JsonProperty("display name") val displayName: String,
    @JsonProperty("tool text") val text: String,
    @JsonProperty("tool description") val description: String,
    @JsonIgnore val dataFunction: (Parasites.ParasiteObject) -> R,
    @JsonProperty("no data") val noDataMessage: String,
    @JsonIgnore val resultMessage: (E) -> String,
    @JsonIgnore val affectedAlert: AlertData? = null,
    @JsonProperty("data type") val dataType: String? = null,
    val grant: ParasitePermissions? = null,
    @JsonProperty("tool action") val action: String? = null,
    @JsonProperty("tool text 2") val text2: String? = null
)

val toolDefinitions = listOf<ToolDefinition<*, *>>(
    ToolDefinition<List<Map<*, *>>, Parasites.ParasiteObject>(
        "grant mod",
        ParasitePermissions.Admin,
        ToolTypes.Grant,
        "Grant moderator",
        "Choose a new moderator",
        "Gives chosen user moderator permissions.",
        { parasite ->
            Parasites.DAO.listWithoutPermissions(
                parasite.id.value,
                ParasitePermissions.Mod,
                ParasitePermissions.Admin
            )
        },
        "Everyone's already a moderator.",
        { parasite -> "${parasite.name} is now a moderator." },
        affectedAlert = AlertData.dismiss(
            "You are now a moderator. You have access to the moderator tools. For great justice.",
            "What you say !!"
        ),
        grant = ParasitePermissions.Mod
    ),
    ToolDefinition<List<Map<*, *>>, Parasites.ParasiteObject>(
        "revoke mod",
        ParasitePermissions.Admin,
        ToolTypes.Grant,
        "Revoke moderator",
        "Choose a moderator to remove",
        "Removes chosen user's moderator permissions, making them a normal parasite again.",
        { parasite ->
            Parasites.DAO.listWithPermissions(
                parasite.id.value,
                ParasitePermissions.Mod,
                ParasitePermissions.Admin
            )
        },
        "Nobody is a moderator.",
        { parasite -> "${parasite.name} is no longer a moderator." },
        affectedAlert = AlertData.dismiss("You are no longer a moderator.", "Oh no."),
        grant = ParasitePermissions.User
    ),
    ToolDefinition<List<Map<*, *>>, Parasites.ParasiteObject>(
        "grant admin",
        ParasitePermissions.Admin,
        ToolTypes.Grant,
        "Grant administrator",
        "Choose a new admin",
        "Gives chosen user administrator permissions.",
        { parasite -> Parasites.DAO.listWithoutPermissions(parasite.id.value, ParasitePermissions.Admin) },
        "Everyone's already an admin.",
        { parasite -> "${parasite.name} is now an admin." },
        affectedAlert = AlertData.dismiss(
            "You are now an admin. You have access to the admin and moderator tools.",
            "I accept."
        ),
        grant = ParasitePermissions.Admin
    ),
    ToolDefinition<List<Map<*, *>>, Parasites.ParasiteObject>(
        "revoke admin",
        ParasitePermissions.Admin,
        ToolTypes.Grant,
        "Revoke administrator",
        "Choose an admin to remove",
        "Removes chosen user's administrator permissions, making them a normal parasite again.",
        { parasite -> Parasites.DAO.listWithPermissions(parasite.id.value, ParasitePermissions.Admin) },
        "Nobody is an admin.",
        { parasite -> "${parasite.name} is no longer an admin." },
        affectedAlert = AlertData.dismiss("You are no longer an admin.", "Oh, fiddlesticks."),
        grant = ParasitePermissions.User
    ),
    ToolDefinition<List<Map<*, *>>, Rooms.RoomObject>(
        "empty room log",
        ParasitePermissions.Mod,
        ToolTypes.Room,
        "Empty room log",
        "Choose room to empty",
        "Clears the log for a given room. All connected clients are immediately updated. Mods can only use this tool for rooms they are in.",
        { parasite -> Rooms.DAO.sparseList(parasite.id.value) },
        "That's weird.",
        { room -> "${room.name} log is empty now." },
        action = "empty"
    ),
    ToolDefinition<List<Map<*, *>>, Rooms.RoomObject>(
        "delete empty room",
        ParasitePermissions.Admin,
        ToolTypes.Room,
        "Delete room with no members",
        "Choose room to delete",
        "Removes a room that no longer has any members (except the owner). ¡ATTN: This is a hard delete!",
        { parasite -> Rooms.DAO.sparseList(onlyEmpty = true) },
        "Wow, there aren't any empty rooms!",
        { room -> "${room.name} is gone forever!" },
        action = "delete"
    ),
    ToolDefinition<List<Map<*, *>>, Pair<Rooms.RoomObject, Parasites.ParasiteObject>>(
        "set new room owner (mod)",
        ParasitePermissions.Mod,
        ToolTypes.RoomOwner,
        "Set new room owner",
        "Select room",
        "Set the owner of a room to a different member of the room (they must be in the room).",
        { parasite -> Rooms.DAO.sparseList(parasite.id.value, withMembers = true) },
        "There aren't any rooms you walnut.",
        { (room, parasite) -> "The new owner of ${room.name} is ${parasite.id}." },
        text2 = "Select new owner"
    ),
    ToolDefinition<List<Map<*, *>>, Pair<Rooms.RoomObject, Parasites.ParasiteObject>>(
        "set new room owner (admin)",
        ParasitePermissions.Admin,
        ToolTypes.RoomOwner,
        "Set new room owner",
        "Select room",
        "Set the owner of a room to a different member of the room (they must be in the room).",
        { _: Any -> Rooms.DAO.sparseList(withMembers = true) },
        "This tool does nothing if there are no rooms to edit.",
        { (room, parasite) -> "The new owner of ${room.name} is ${parasite.id}." },
        text2 = "Select new owner"
    ),
    ToolDefinition<List<Map<String, EntityID<String>>>, Parasites.ParasiteObject>(
        "deactivate parasite",
        ParasitePermissions.Admin,
        ToolTypes.Parasite,
        "Deactivate parasite",
        "Go away",
        "Set the chosen parasite to inactive. Removes all alerts and invitations, removes them from all rooms, resets their display name to their id, and empties their reset token.  Inactive parasites are blocked from logging in, and must re-request access from an admin.",
        { _ -> Parasites.DAO.list(active = true).map { mapOf("id" to it.id) } },
        "I guess you\\'re the only one here.",
        { parasite -> "Deactivated parasite: ${parasite.id}." },
        action = "deactivate"
    ),
    ToolDefinition<List<Map<String, EntityID<String>>>, Parasites.ParasiteObject>(
        "reactivate parasite",
        ParasitePermissions.Admin,
        ToolTypes.Parasite,
        "Reactivate parasite",
        "Perform necromancy",
        "Sets a parasite back to active. Restores nothing. They can do that when they log in.",
        { _ -> Parasites.DAO.list(active = false).map { mapOf("id" to it.id) } },
        "No candidates for zombification.",
        { parasite -> "You've resurrected ${parasite.id}. Now you must live with that choice." },
        action = "reactivate",
        affectedAlert = AlertData.dismiss(
            "Your account was reactivated. All settings are back to default, and if you were in any rooms...  well, you aren't anymore. Welcome back!",
            "Sick"
        )
    ),
    ToolDefinition<List<Map<*, *>>, Parasites.ParasiteObject>(
        "view parasite data",
        ParasitePermissions.Admin,
        ToolTypes.Data,
        "View parasite data",
        "Plz",
        "View whatever a parasite's current data looks like.",
        { _ -> Parasites.DAO.list(true).map { mapOf("username" to it.name, "id" to it.id) } },
        "There is nobody to view. Where did you go?",
        { parasite -> defaultMapper.writeValueAsString(parasite) },
        dataType = "parasite"
    ),
    ToolDefinition<List<Map<*, *>>, Rooms.RoomObject>(
        "view room data",
        ParasitePermissions.Admin,
        ToolTypes.Data,
        "View room data",
        "Plz",
        "View whatever a room's current data looks like.",
        { _ -> Rooms.DAO.sparseList() },
        "There are no rooms to view. Sad.",
        { room -> defaultMapper.writeValueAsString(room) },
        dataType = "room"
    )
)

fun ParasitePermissions.getToolList() = toolDefinitions.filter { it.accessLevel == this }
    .map { mapOf("name" to it.displayName, "key" to it.id) }