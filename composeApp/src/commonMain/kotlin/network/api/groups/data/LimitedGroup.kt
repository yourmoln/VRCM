package io.github.vrcmteam.vrcm.network.api.groups.data

import kotlinx.serialization.Serializable

@Serializable
data class LimitedGroup(
    val bannerId: String? = null,
    val bannerUrl: String? = null,
    val createdAt: String = "",
    val description: String = "",
    val discriminator: String = "",
    val galleries: List<Gallery> = emptyList(),
    val iconId: String? = null,
    val iconUrl: String? = null,
    val id: String = "",
    val isSearchable: Boolean = true,
    val memberCount: Int = 0,
    val membershipStatus: String = "",
    val name: String = "",
    val ownerId: String = "",
    val rules: String? = null,
    val shortCode: String = "",
    val tags: List<String> = emptyList(),
)
