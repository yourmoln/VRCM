package io.github.vrcmteam.vrcm.network.api.avatars.data

import io.github.vrcmteam.vrcm.network.api.worlds.data.UnityPackage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*

/**
 * VRChat API 在模型某些字段无数据时会返回空字符串 "" 而非正确的类型（空数组 [] 或 null），
 * 以下序列化器将空字符串转换为对应的空值以避免反序列化失败。
 */
internal object TagsSerializer : JsonTransformingSerializer<List<String>>(
    ListSerializer(String.serializer())
) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonPrimitive && element.isString) JsonArray(emptyList()) else element
}

internal object UnityPackagesSerializer : JsonTransformingSerializer<List<UnityPackage>>(
    ListSerializer(UnityPackage.serializer())
) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonPrimitive && element.isString) JsonArray(emptyList()) else element
}

internal object PerformanceSerializer : JsonTransformingSerializer<AvatarPerformance>(
    AvatarPerformance.serializer()
) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonPrimitive && element.isString) JsonObject(emptyMap()) else element
}

@Serializable
data class AvatarData(
    val id: String,
    val name: String,
    val description: String = "",
    val authorId: String = "",
    val authorName: String = "",
    val imageUrl: String = "",
    val thumbnailImageUrl: String = "",
    @Serializable(with = TagsSerializer::class)
    val tags: List<String> = emptyList(),
    val releaseStatus: String = "",
    val version: Int = 0,
    val featured: Boolean = false,
    @SerialName("created_at")
    val createdAt: String = "",
    @SerialName("updated_at")
    val updatedAt: String = "",
    @Serializable(with = UnityPackagesSerializer::class)
    val unityPackages: List<UnityPackage> = emptyList(),
    @Serializable(with = PerformanceSerializer::class)
    val performance: AvatarPerformance? = null,
)
