package io.github.vrcmteam.vrcm.storage

import com.russhwolf.settings.Settings
import io.github.vrcmteam.vrcm.storage.data.FriendNetworkCache
import kotlinx.serialization.json.Json

class FriendNetworkCacheDao(
    private val settings: Settings,
) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private fun key(userId: String) = "${DaoKeys.FriendNetwork.KEY_PREFIX}.$userId"

    fun load(userId: String): FriendNetworkCache? {
        val raw = settings.getStringOrNull(key(userId)) ?: return null
        return runCatching { json.decodeFromString<FriendNetworkCache>(raw) }.getOrNull()
    }

    fun save(cache: FriendNetworkCache) {
        val raw = json.encodeToString(FriendNetworkCache.serializer(), cache)
        settings.putString(key(cache.userId), raw)
    }

    fun clear(userId: String) {
        settings.remove(key(userId))
    }
}
