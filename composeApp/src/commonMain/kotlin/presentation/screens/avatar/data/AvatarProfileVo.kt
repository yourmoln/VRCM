package io.github.vrcmteam.vrcm.presentation.screens.avatar.data

import cafe.adriel.voyager.core.lifecycle.JavaSerializable
import io.github.vrcmteam.vrcm.network.api.avatars.data.AvatarData
import io.github.vrcmteam.vrcm.network.api.avatars.data.AvatarPerformance
import io.github.vrcmteam.vrcm.network.api.worlds.data.UnityPackage

data class AvatarProfileVo(
    val avatarId: String,
    val name: String = "",
    val description: String = "",
    val imageUrl: String? = null,
    val thumbnailImageUrl: String? = null,
    val authorId: String = "",
    val authorName: String = "",
    val tags: List<String> = emptyList(),
    val releaseStatus: String = "",
    val version: Int = 0,
    val featured: Boolean = false,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val unityPackages: List<UnityPackage> = emptyList(),
    val performance: AvatarPerformance? = null,
) : JavaSerializable {

    constructor(avatar: AvatarData) : this(
        avatarId = avatar.id,
        name = avatar.name,
        description = avatar.description,
        imageUrl = avatar.imageUrl,
        thumbnailImageUrl = avatar.thumbnailImageUrl,
        authorId = avatar.authorId,
        authorName = avatar.authorName,
        tags = avatar.tags.filter { it.startsWith("author_tag_") }
            .map { it.substringAfter("author_tag_") },
        releaseStatus = avatar.releaseStatus,
        version = avatar.version,
        featured = avatar.featured,
        createdAt = avatar.createdAt,
        updatedAt = avatar.updatedAt,
        unityPackages = avatar.unityPackages,
        performance = avatar.performance,
    )
}
