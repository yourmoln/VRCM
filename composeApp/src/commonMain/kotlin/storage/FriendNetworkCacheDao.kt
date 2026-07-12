package io.github.vrcmteam.vrcm.storage

import com.russhwolf.settings.Settings
import io.github.vrcmteam.vrcm.storage.data.FriendNetworkCache
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
class FriendNetworkCacheDao(
    private val settings: Settings,
) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private fun key(userId: String) = "${DaoKeys.FriendNetwork.KEY_PREFIX}.$userId"

    fun load(userId: String): FriendNetworkCache? {
        val raw = settings.getStringOrNull(key(userId)) ?: return null
        val decoded = Base64.decode(raw).decodeToString()
        return runCatching { json.decodeFromString<FriendNetworkCache>(decoded) }.getOrNull()
    }

    fun save(cache: FriendNetworkCache) {
        val raw = json.encodeToString(FriendNetworkCache.serializer(), cache)
        settings.putString(key(cache.userId), Base64.encode(raw.encodeToByteArray()))
    }

    fun clear(userId: String) {
        settings.remove(key(userId))
    }
}
