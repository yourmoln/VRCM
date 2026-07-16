package io.github.vrcmteam.vrcm.network.api.avatars.data

import cafe.adriel.voyager.core.lifecycle.JavaSerializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AvatarData(
    val id: String,
    val name: String,
    val description: String? = null,
    val authorId: String,
    val authorName: String,
    val imageUrl: String,
    val thumbnailImageUrl: String? = null,
    val releaseStatus: String,
    val tags: List<String> = emptyList(),
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("updated_at")
    val updatedAt: String? = null,
    val version: Int? = null,
    val unityPackages: List<AvatarUnityPackage> = emptyList(),
) : JavaSerializable

@Serializable
data class AvatarUnityPackage(
    val platform: String? = null,
    val unityVersion: String? = null,
    val performanceRating: String? = null,
) : JavaSerializable
