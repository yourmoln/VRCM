package io.github.vrcmteam.vrcm.network.api.avatars

import io.github.vrcmteam.vrcm.network.api.attributes.AVATARS_API_PREFIX
import io.github.vrcmteam.vrcm.network.api.avatars.data.AvatarData
import io.github.vrcmteam.vrcm.network.extensions.checkSuccess
import io.ktor.client.*
import io.ktor.client.request.*

class AvatarsApi(private val client: HttpClient) {

    suspend fun getAvatarById(avatarId: String): AvatarData =
        client.get("$AVATARS_API_PREFIX/$avatarId").checkSuccess()

    suspend fun getFavoritedAvatars(
        n: Int = 50,
        offset: Int = 0,
        tag: String? = null,
        search: String? = null,
        sort: String? = null,
        order: String? = null,
        featured: Boolean? = null,
    ): List<AvatarData> =
        client.get("$AVATARS_API_PREFIX/favorites") {
            parameter("n", n)
            parameter("offset", offset)
            search?.let { parameter("search", it) }
            tag?.let { parameter("tag", it) }
            sort?.let { parameter("sort", it) }
            order?.let { parameter("order", it) }
            featured?.let { parameter("featured", it) }
        }.checkSuccess()
}
