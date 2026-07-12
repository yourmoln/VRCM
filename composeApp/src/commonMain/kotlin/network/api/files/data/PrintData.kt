package io.github.vrcmteam.vrcm.network.api.files.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * VRChat 拍立得(Print)数据模型
 * API: GET /prints/user/{userId}
 */
@Serializable
data class PrintData(
    val id: String,
    val files: PrintFiles? = null,
    val note: String? = null,
    @SerialName("worldId")
    val worldId: String? = null,
    @SerialName("worldName")
    val worldName: String? = null,
    @SerialName("authorId")
    val authorId: String? = null,
    @SerialName("authorName")
    val authorName: String? = null,
    @SerialName("created_at")
    val createdAt: String? = null,
    val timestamp: String? = null,
)

@Serializable
data class PrintFiles(
    val image: String? = null,
)
