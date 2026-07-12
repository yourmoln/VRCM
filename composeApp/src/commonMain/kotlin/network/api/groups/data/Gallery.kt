package io.github.vrcmteam.vrcm.network.api.groups.data

import kotlinx.serialization.Serializable

@Serializable
data class Gallery(
    val createdAt: String,
    val description: String,
    val id: String,
    val membersOnly: Boolean,
    val name: String,
    val roleIdsToAutoApprove: List<String>? = null,
    val roleIdsToManage: List<String>? = null,
    val roleIdsToSubmit: List<String>? = null,
    val roleIdsToView: List<String>? = null,
    val updatedAt: String
)
