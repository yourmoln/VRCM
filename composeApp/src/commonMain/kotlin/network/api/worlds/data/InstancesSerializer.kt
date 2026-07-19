package io.github.vrcmteam.vrcm.network.api.worlds.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

/**
 * VRChat instances 字段的自定义反序列化器
 * API 返回的 instances 是混合类型数组
 */
object InstancesSerializer : KSerializer<List<List<String>>> {
    private val delegate = ListSerializer(ListSerializer(String.serializer()))
    override val descriptor: SerialDescriptor = delegate.descriptor

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

    override fun serialize(encoder: Encoder, value: List<List<String>>) =
        delegate.serialize(encoder, value)

}
