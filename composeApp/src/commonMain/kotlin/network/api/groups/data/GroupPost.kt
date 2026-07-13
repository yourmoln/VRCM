package io.github.vrcmteam.vrcm.network.api.groups.data

import kotlinx.serialization.Serializable

@Serializable
data class GroupPost(
    val id: String = "",
    val groupId: String = "",
    val authorId: String = "",
    val editorId: String? = null,
    val visibility: String = "",
    val roleIds: List<String> = emptyList(),
    val title: String = "",
    val text: String = "",
    val imageId: String? = null,
    val imageUrl: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class GroupPostData(
    val posts: List<GroupPost> = emptyList(),
    val total: Int = 0,
)
