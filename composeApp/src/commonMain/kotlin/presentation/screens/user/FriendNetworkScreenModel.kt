package io.github.vrcmteam.vrcm.presentation.screens.user

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.withContext
import org.koin.core.logger.Logger
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Clock.System.now
import kotlin.time.ExperimentalTime

data class FriendNetworkProgress(
    val current: Int,
    val total: Int,
)

data class FriendNetworkLayoutResult(
    val positions: Map<String, Offset>,
    val layoutWidthPx: Float,
    val layoutHeightPx: Float,
)

data class FriendNetworkUiState(
    val selfId: String? = null,
    val nodes: List<MutualFriendData> = emptyList(),
    val edges: Map<String, List<String>> = emptyMap(),
    val nodeColors: Map<String, Color> = emptyMap(),
    val layout: FriendNetworkLayoutResult? = null,
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

    companion object {
        // 调色板：按社区 ID 分配颜色
        private val COLORS_PALETTE = listOf(
            Color(0xFF5470C6),
            Color(0xFF91CC75),
            Color(0xFFFAC858),
            Color(0xFFEE6666),
            Color(0xFF73C0DE),
            Color(0xFF3BA272),
            Color(0xFFFC8452),
            Color(0xFF9A60B4),
            Color(0xFFEA7CCC),
        )

        /**
         * Louvain 社区检测 + 颜色分配
         * 按连接密度分组，而非简单连通分量
         */
        fun assignCommunityColors(
            nodes: List<MutualFriendData>,
            edges: Map<String, List<String>>,
            selfId: String? = null
        ): Map<String, Color> {
            if (nodes.isEmpty()) return emptyMap()
            val adjacency = edges
                .filterKeys { it != selfId }
                .mapValues { (_, neighbors) -> neighbors.filter { it != selfId }.toSet() }
                .filterValues { it.isNotEmpty() }

            // Louvain 社区检测
            val communityMap = louvainDetect(adjacency)

            // 按社区大小降序排列，大的社区先分配颜色
            val grouped = communityMap.entries.groupBy { it.value }
            val sorted = grouped.entries.sortedByDescending { it.value.size }
            val colorMap = mutableMapOf<String, Color>()
            sorted.forEachIndexed { index, (_, members) ->
                val color = COLORS_PALETTE[index % COLORS_PALETTE.size]
                members.forEach { colorMap[it.key] = color }
            }
            // 孤立节点（没有边的）单独分配颜色
            for (node in nodes) {
                if (node.id != selfId && node.id !in colorMap) {
                    colorMap[node.id] = COLORS_PALETTE[colorMap.size % COLORS_PALETTE.size]
                }
            }
            return colorMap
        }

        private const val BASE_NODE_SIZE_PX = 43f
        private const val MAX_EXTRA_SIZE_PX = 34f
        private const val FORCE_ITERATIONS = 500
        private const val CONVERGENCE_THRESHOLD = 0.5f
        private const val MIN_ITERATIONS = 30

        /**
         * 力导向布局算法（简化版 ForceAtlas2）
         * 在 Dispatchers.Default 上异步执行，含提前收敛退出
         */
        fun computeNodePositions(
            nodes: List<MutualFriendData>,
            selfId: String?,
            edges: Map<String, List<String>>,
        ): FriendNetworkLayoutResult {
            if (nodes.isEmpty()) return FriendNetworkLayoutResult(emptyMap(), 0f, 0f)

            val nodeSizePx = BASE_NODE_SIZE_PX + MAX_EXTRA_SIZE_PX
            val desiredSpacing = nodeSizePx * 1.3f
            val n = nodes.size
            val nodeIds = nodes.map { it.id }
            val idx = nodeIds.mapIndexed { i, id -> id to i }.toMap()

            // 计算画布尺寸
            val maxRadius = desiredSpacing * sqrt(n.coerceAtLeast(1).toFloat()) * 2f
            val requiredDiameter = maxRadius * 2f + desiredSpacing
            val scaleFactor = maxOf(1f, sqrt(n.toFloat().coerceAtLeast(1f)) / 10f)
            val referenceSize = 1000f * scaleFactor
            val layoutWidthPx = maxOf(referenceSize, requiredDiameter)
            val layoutHeightPx = maxOf(referenceSize, requiredDiameter)
            val center = Offset(layoutWidthPx / 2f, layoutHeightPx / 2f)

            // 初始化位置：随机散布在中心附近
            val x = FloatArray(n)
            val y = FloatArray(n)
            val rng = kotlin.random.Random(42)
            for (i in 0 until n) {
                val angle = rng.nextFloat() * 2f * PI.toFloat()
                val r = rng.nextFloat() * desiredSpacing * 0.3f
                x[i] = center.x + r * cos(angle)
                y[i] = center.y + r * sin(angle)
            }

            // 力导向迭代
            val k = desiredSpacing * 1.5f
            val gravity = 1.6f
            val speed = 1f
            val fx = FloatArray(n)
            val fy = FloatArray(n)

            for (iter in 0 until FORCE_ITERATIONS) {
                fx.fill(0f)
                fy.fill(0f)

                // 斥力：所有节点对之间
                for (i in 0 until n) {
                    for (j in i + 1 until n) {
                        var dx = x[i] - x[j]
                        var dy = y[i] - y[j]
                        val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                        val force = (k * k) / dist
                        val fxij = (dx / dist) * force
                        val fyij = (dy / dist) * force
                        fx[i] += fxij; fy[i] += fyij
                        fx[j] -= fxij; fy[j] -= fyij
                    }
                }

                // 引力：连接的节点之间
                for (i in 0 until n) {
                    val nodeId = nodeIds[i]
                    val neighbors = edges[nodeId].orEmpty()
                    for (neighborId in neighbors) {
                        val j = idx[neighborId] ?: continue
                        if (i >= j) continue
                        var dx = x[j] - x[i]
                        var dy = y[j] - y[i]
                        val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                        val force = (dist * dist) / k
                        val fxij = (dx / dist) * force
                        val fyij = (dy / dist) * force
                        fx[i] += fxij; fy[i] += fyij
                        fx[j] -= fxij; fy[j] -= fyij
                    }
                }

                // 重力：向中心吸引
                for (i in 0 until n) {
                    val dx = center.x - x[i]
                    val dy = center.y - y[i]
                    fx[i] += dx * gravity
                    fy[i] += dy * gravity
                }

                // 更新位置（带速度限制）
                val maxDisplacement = k * speed * (1f - iter.toFloat() / FORCE_ITERATIONS)
                var maxMove = 0f
                for (i in 0 until n) {
                    val fMag = sqrt(fx[i] * fx[i] + fy[i] * fy[i]).coerceAtLeast(0.01f)
                    val disp = min(fMag, maxDisplacement)
                    val moveX = (fx[i] / fMag) * disp
                    val moveY = (fy[i] / fMag) * disp
                    x[i] += moveX
                    y[i] += moveY
                    val moveMag = sqrt(moveX * moveX + moveY * moveY)
                    if (moveMag > maxMove) maxMove = moveMag
                }

                // 提前收敛检查：跳过前几轮不稳定振荡
                if (iter >= MIN_ITERATIONS && maxMove < CONVERGENCE_THRESHOLD) break
            }

            val positions = nodeIds.mapIndexed { i, id -> id to Offset(x[i], y[i]) }.toMap()
            return FriendNetworkLayoutResult(positions, layoutWidthPx, layoutHeightPx)
        }

        /**
         * Louvain 社区检测算法
         * 通过迭代优化模块度来发现密集连接的子群
         */
        private fun louvainDetect(adjacency: Map<String, Set<String>>): Map<String, Int> {
            val nodeIds = adjacency.keys.toList()
            val n = nodeIds.size
            if (n == 0) return emptyMap()
            val m = adjacency.values.sumOf { it.size } / 2
            if (m == 0) return nodeIds.mapIndexed { i, id -> id to i }.toMap()

            val idx = nodeIds.mapIndexed { i, id -> id to i }.toMap()
            val degree = IntArray(n) { adjacency[nodeIds[it]]?.size ?: 0 }
            val comm = IntArray(n) { it } // 每个节点初始为独立社区
            val commTot = IntArray(n) { degree[it] } // 每个社区的度数总和

            // 计算节点到指定社区的边数
            fun edgesToComm(nodeIdx: Int, targetComm: Int): Int {
                var count = 0
                for (neighbor in adjacency[nodeIds[nodeIdx]].orEmpty()) {
                    val j = idx[neighbor] ?: continue
                    if (comm[j] == targetComm) count++
                }
                return count
            }

            // 迭代优化模块度
            var improved = true
            while (improved) {
                improved = false
                for (i in 0 until n) {
                    val currentComm = comm[i]
                    val ki = degree[i]
                    if (ki == 0) continue

                    // 收集邻居社区
                    val neighborComms = mutableSetOf<Int>()
                    for (neighbor in adjacency[nodeIds[i]].orEmpty()) {
                        val j = idx[neighbor] ?: continue
                        neighborComms.add(comm[j])
                    }

                    // 从当前社区移除
                    commTot[currentComm] -= ki

                    var bestComm = currentComm
                    var bestGain = 0.0

                    for (targetComm in neighborComms) {
                        val kiIn = edgesToComm(i, targetComm)
                        // 模块度增益公式
                        val gain = kiIn.toDouble() / m -
                            (ki.toLong() * commTot[targetComm]).toDouble() / (2.0 * m * m)
                        if (gain > bestGain) {
                            bestGain = gain
                            bestComm = targetComm
                        }
                    }

                    // 移动到最优社区
                    comm[i] = bestComm
                    commTot[bestComm] += ki

                    if (bestComm != currentComm) improved = true
                }
            }

            // 重新编号为连续整数
            val renumber = mutableMapOf<Int, Int>()
            var nextId = 0
            val result = mutableMapOf<String, Int>()
            for (i in 0 until n) {
                val c = comm[i]
                if (c !in renumber) renumber[c] = nextId++
                result[nodeIds[i]] = renumber[c]!!
            }
            return result
        }
    }

    var uiState by mutableStateOf(FriendNetworkUiState())
        private set

    fun loadCache() {
        screenModelScope.launch(Dispatchers.IO) {
            runCatching { authService.currentUser() }
                .onSuccess { currentUser ->
                    val cache = cacheDao.load(currentUser.id) ?: return@onSuccess
                    val selfId = cache.userId
                    val filteredNodes = cache.nodes.filter { it.id != selfId }
                    val filteredEdges = cache.edges
                        .filterKeys { it != selfId }
                        .mapValues { (_, v) -> v.filter { it != selfId } }
                        .filterValues { it.isNotEmpty() }
                    val nodeColors = assignCommunityColors(cache.nodes, cache.edges, selfId)
                    val layout = computeLayout(filteredNodes, selfId, filteredEdges)
                    uiState = uiState.copy(
                        selfId = selfId,
                        nodes = filteredNodes,
                        edges = filteredEdges,
                        nodeColors = nodeColors,
                        layout = layout,
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
                val selfId = currentUser.id
                val filteredNodes = nodes.filter { it.id != selfId }
                val filteredEdges = finalEdges
                    .filterKeys { it != selfId }
                    .mapValues { (_, v) -> v.filter { it != selfId } }
                    .filterValues { it.isNotEmpty() }
                val nodeColors = assignCommunityColors(nodes, finalEdges, selfId)
                val layout = computeLayout(filteredNodes, selfId, filteredEdges)
                uiState = FriendNetworkUiState(
                    selfId = selfId,
                    nodes = filteredNodes,
                    edges = filteredEdges,
                    nodeColors = nodeColors,
                    layout = layout,
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

    private suspend fun computeLayout(
        nodes: List<MutualFriendData>,
        selfId: String?,
        edges: Map<String, List<String>>,
    ): FriendNetworkLayoutResult = withContext(Dispatchers.Default) {
        computeNodePositions(nodes, selfId, edges)
    }

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
