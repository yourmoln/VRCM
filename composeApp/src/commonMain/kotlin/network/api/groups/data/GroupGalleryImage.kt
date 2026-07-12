package io.github.vrcmteam.vrcm.network.api.groups.data

import kotlinx.serialization.Serializable

@Serializable
data class GroupGalleryImage(
    val id: String = "",
    val groupId: String = "",
    val galleryId: String = "",
    val fileId: String = "",
    val imageUrl: String = "",
    val approved: Boolean = false,
    val approvedAt: String? = null,
    val approvedByUserId: String? = null,
    val submittedByUserId: String? = null,
    val createdAt: String? = null,
)
