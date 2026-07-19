package io.github.vrcmteam.vrcm.network.api.users.data

import kotlinx.serialization.Serializable

@Serializable
data class UserNoteData(
    val id: String = "",
    val targetUserId: String = "",
    val note: String = "",
    val userId: String = "",
    val createdAt: String = "",
)
