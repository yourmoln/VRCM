package io.github.vrcmteam.vrcm.network.api.worlds.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

/**
 * VRChat instances 字段的自定义反序列化器
 * API 返回的 instances 是混合类型数组
 */
object InstancesSerializer : KSerializer<List<List<String>>> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Instances")

    override fun deserialize(decoder: Decoder): List<List<String>> {
        val json = decoder as? JsonDecoder
            ?: throw SerializationException("This serializer only works with JSON")
        val jsonArray = json.decodeJsonElement().jsonArray

        return jsonArray.map { innerArray ->
            innerArray.jsonArray.mapNotNull { element ->
                when (element) {
                    is JsonPrimitive -> element.content
                    else -> null
                }
            }
        }
    }

    override fun serialize(encoder: Encoder, value: List<List<String>>) {
        // 序列化时直接使用默认行为
        throw SerializationException("Serialization not supported")
    }
}
