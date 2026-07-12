package io.github.vrcmteam.vrcm.presentation.screens.user

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import io.github.vrcmteam.vrcm.core.shared.SharedFlowCentre
import io.github.vrcmteam.vrcm.network.api.auth.data.CurrentUserData
import io.github.vrcmteam.vrcm.network.api.friends.date.FriendData
import io.github.vrcmteam.vrcm.network.api.users.UsersApi
import io.github.vrcmteam.vrcm.network.api.users.data.MutualFriendData
import io.github.vrcmteam.vrcm.presentation.compoments.ToastText
import io.github.vrcmteam.vrcm.service.AuthService
import io.github.vrcmteam.vrcm.service.FriendService
import io.github.vrcmteam.vrcm.storage.FriendNetworkCacheDao
import io.github.vrcmteam.vrcm.storage.data.FriendNetworkCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.koin.core.logger.Logger
import kotlin.time.Clock.System.now
import kotlin.time.ExperimentalTime

data class FriendNetworkProgress(
    val current: Int,
    val total: Int,
)

data class FriendNetworkUiState(
    val selfId: String? = null,
    val nodes: List<MutualFriendData> = emptyList(),
    val edges: Map<String, List<String>> = emptyMap(),
    val updatedAt: Long? = null,
    val isFromCache: Boolean = false,
    val isLoading: Boolean = false,
    val progress: FriendNetworkProgress? = null,
)

class FriendNetworkScreenModel(
    private val authService: AuthService,
    private val usersApi: UsersApi,
    private val friendService: FriendService,
    private val cacheDao: FriendNetworkCacheDao,
    private val logger: Logger,
) : ScreenModel {

    var uiState by mutableStateOf(FriendNetworkUiState())
        private set

    fun loadCache() {
        screenModelScope.launch(Dispatchers.IO) {
            runCatching { authService.currentUser() }
                .onSuccess { currentUser ->
                    val cache = cacheDao.load(currentUser.id) ?: return@onSuccess
                    uiState = uiState.copy(
                        selfId = cache.userId,
                        nodes = cache.nodes,
                        edges = cache.edges,
                        updatedAt = cache.updatedAt,
                        isFromCache = true,
                    )
                }
                .onFailure {
                    logger.error(it.message.orEmpty())
                }
        }
    }

    @OptIn(ExperimentalTime::class)
    fun refresh() {
        if (uiState.isLoading) return
        screenModelScope.launch(Dispatchers.IO) {
            uiState = uiState.copy(isLoading = true, progress = FriendNetworkProgress(0, 0))
            try {
                val currentUser = authService.currentUser()
                val friendList = loadFriends()
                val friendIds = friendList.map { it.id }.toSet()
                val total = friendList.size
                uiState = uiState.copy(progress = FriendNetworkProgress(0, total))

                val edges = mutableMapOf<String, MutableSet<String>>()
                val selfNode = currentUser.toMutualFriendData(isFriend = false)
                edges[selfNode.id] = friendIds.toMutableSet()

                val nodes = mutableListOf<MutualFriendData>()
                nodes.add(selfNode)
                nodes.addAll(friendList.map { it.toMutualFriendData() })

                var processed = 0
                val chunkSize = 4
                friendList.chunked(chunkSize).forEach { chunk ->
                    coroutineScope {
                        val deferred = chunk.map { friend ->
                            async {
                                friend.id to fetchAllMutualFriends(friend.id)
                            }
                        }
                        deferred.forEach { task ->
                            val (friendId, mutuals) = task.await()
                            val mutualIds = mutuals.asSequence()
                                .map { it.id }
                                .filter { it in friendIds }
                                .toSet()
                            edges.getOrPut(friendId) { mutableSetOf() }.addAll(mutualIds)
                            mutualIds.forEach { mutualId ->
                                edges.getOrPut(mutualId) { mutableSetOf() }.add(friendId)
                            }
                            processed += 1
                            uiState = uiState.copy(progress = FriendNetworkProgress(processed, total))
                        }
                    }
                }

                val finalEdges = edges.mapValues { it.value.toList() }
                val cache = FriendNetworkCache(
                    userId = currentUser.id,
                    updatedAt = now().toEpochMilliseconds(),
                    nodes = nodes,
                    edges = finalEdges
                )
                cacheDao.save(cache)
                uiState = FriendNetworkUiState(
                    selfId = currentUser.id,
                    nodes = nodes,
                    edges = finalEdges,
                    updatedAt = cache.updatedAt,
                    isFromCache = false,
                    isLoading = false,
                    progress = null
                )
            } catch (e: Exception) {
                logger.error(e.message.orEmpty())
                SharedFlowCentre.toastText.emit(ToastText.Error(e.message.orEmpty()))
                uiState = uiState.copy(isLoading = false, progress = null)
            }
        }
    }

    private suspend fun loadFriends(): List<FriendData> {
        friendService.refreshFriendList()
        return friendService.friendMap.values.sortedBy { it.displayName.lowercase() }
    }

    private suspend fun fetchAllMutualFriends(userId: String): List<MutualFriendData> {
        val all = mutableListOf<MutualFriendData>()
        var offset = 0
        val limit = 100
        while (true) {
            val pageResult = authService.reTryAuthCatching {
                usersApi.getMutualFriends(userId, n = limit, offset = offset)
            }
            if (pageResult.isFailure) {
                val message = pageResult.exceptionOrNull()?.message.orEmpty()
                logger.error(message)
                SharedFlowCentre.toastText.emit(ToastText.Error(message))
                break
            }
            val page = pageResult.getOrDefault(emptyList())
            all.addAll(page)
            if (page.size < limit) break
            offset += limit
        }
        return all
    }

    private fun FriendData.toMutualFriendData() = MutualFriendData(
        id = id,
        displayName = displayName,
        status = status,
        statusDescription = statusDescription,
        bio = bio,
        bioLinks = bioLinks,
        tags = tags,
        currentAvatarImageUrl = currentAvatarImageUrl,
        currentAvatarThumbnailImageUrl = currentAvatarThumbnailImageUrl,
        currentAvatarTags = currentAvatarTags,
        imageUrl = imageUrl,
        profilePicOverride = profilePicOverride,
        userIcon = userIcon,
        isFriend = isFriend,
        lastLogin = lastLogin,
        lastPlatform = lastPlatform,
        developerType = developerType,
        pronouns = pronouns,
    )

    private fun CurrentUserData.toMutualFriendData(isFriend: Boolean) = MutualFriendData(
        id = id,
        displayName = displayName,
        status = status,
        statusDescription = statusDescription,
        bio = bio,
        bioLinks = bioLinks,
        tags = tags,
        currentAvatarImageUrl = currentAvatarImageUrl,
        currentAvatarThumbnailImageUrl = currentAvatarThumbnailImageUrl,
        currentAvatarTags = currentAvatarTags,
        imageUrl = "",
        profilePicOverride = profilePicOverride,
        userIcon = userIcon,
        isFriend = isFriend,
        lastLogin = lastLogin,
        lastPlatform = lastPlatform,
        developerType = developerType,
        pronouns = pronouns,
    )
}
