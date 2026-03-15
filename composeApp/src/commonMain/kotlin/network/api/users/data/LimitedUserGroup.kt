package io.github.vrcmteam.vrcm.network.api.users.data

import kotlinx.serialization.Serializable

@Serializable
data class LimitedUserGroup(
    val id: String = "",
    val groupId: String = "",
    val name: String = "",
    val shortCode: String = "",
    val description: String = "",
    val iconUrl: String? = null,
    val bannerUrl: String? = null,
    val memberCount: Int = 0,
    val isRepresenting: Boolean = false,
    val mutualGroup: Boolean = false,
)
