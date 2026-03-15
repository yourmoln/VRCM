package io.github.vrcmteam.vrcm.network.api.groups.data

import kotlinx.serialization.Serializable

@Serializable
data class Role(
    val createdAt: String = "",
    val defaultRole: Boolean = false,
    val description: String = "",
    val groupId: String = "",
    val id: String = "",
    val isAddedOnJoin: Boolean = false,
    val isManagementRole: Boolean = false,
    val isSelfAssignable: Boolean = false,
    val name: String = "",
    val order: Int = 0,
    val permissions: List<String> = emptyList(),
    val requiresPurchase: Boolean = false,
    val requiresTwoFactor: Boolean = false,
    val updatedAt: String? = null,
)
