package io.github.vrcmteam.vrcm.presentation.screens.user

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import io.github.vrcmteam.vrcm.network.api.users.data.MutualFriendData
import io.github.vrcmteam.vrcm.presentation.compoments.ABottomSheet
import io.github.vrcmteam.vrcm.presentation.compoments.UserStateIcon
import io.github.vrcmteam.vrcm.presentation.screens.user.data.UserProfileVo
import io.github.vrcmteam.vrcm.presentation.settings.locale.strings
import io.github.vrcmteam.vrcm.presentation.supports.AppIcons
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object FriendNetworkScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model: FriendNetworkScreenModel = koinScreenModel()
        val state = model.uiState
        var selectedId by remember { mutableStateOf<String?>(null) }
        var highlightId by remember { mutableStateOf<String?>(null) }
        var showSheet by remember { mutableStateOf(false) }
        val sheetState = rememberModalBottomSheetState()

        LaunchedEffect(Unit) {
            model.loadCache()
        }

        val nodeMap = remember(state.nodes) { state.nodes.associateBy { it.id } }
        val selectedNode = selectedId?.let { nodeMap[it] }
        val mutualIds = selectedId?.let { state.edges[it].orEmpty() }.orEmpty()
        val highlightIds = highlightId?.let { id ->
            setOf(id) + state.edges[id].orEmpty()
        }.orEmpty()

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            text = strings.friendNetworkTitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(
                                painter = rememberVectorPainter(AppIcons.ArrowBackIosNew),
                                tint = MaterialTheme.colorScheme.primary,
                                contentDescription = "back"
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            enabled = !state.isLoading,
                            onClick = { model.refresh() }
                        ) {
                            Icon(
                                painter = rememberVectorPainter(AppIcons.Update),
                                tint = MaterialTheme.colorScheme.primary,
                                contentDescription = "refresh"
                            )
                        }
                    }
                )
            },
            contentColor = MaterialTheme.colorScheme.primary
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                FriendNetworkHeader(
                    updatedAt = state.updatedAt,
                    isFromCache = state.isFromCache,
                    progress = state.progress,
                    isLoading = state.isLoading
                )
                Box(modifier = Modifier.fillMaxSize()) {
                    if (state.nodes.isEmpty() && !state.isLoading) {
                        Text(
                            modifier = Modifier.align(Alignment.Center),
                            text = strings.friendNetworkEmpty,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    } else {
                        FriendNetworkGraph(
                            nodes = state.nodes,
                            edges = state.edges,
                            nodeColors = state.nodeColors,
                            selfId = null,
                            highlightIds = highlightIds,
                            selectedId = selectedId,
                            highlightId = highlightId,
                            onHighlightChange = { highlightId = it },
                            onNodeClick = { nodeId ->
                                selectedId = nodeId
                                showSheet = true
                            }
                        )
                    }
                    if (state.isLoading && state.nodes.isEmpty()) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                }
            }
        }

        ABottomSheet(
            isVisible = showSheet && selectedNode != null,
            sheetState = sheetState,
            onDismissRequest = {
                showSheet = false
                selectedId = null
            }
        ) {
            val node = selectedNode ?: return@ABottomSheet
            val mutualUsers = mutualIds.mapNotNull { nodeMap[it] }
                .sortedBy { it.displayName.lowercase() }
            FriendNetworkSheet(
                node = node,
                mutualUsers = mutualUsers,
                onUserClick = {
                    navigator.push(UserProfileScreen(UserProfileVo(it)))
                    showSheet = false
                    selectedId = null
                }
            )
        }
    }
}

@Composable
private fun FriendNetworkHeader(
    updatedAt: Long?,
    isFromCache: Boolean,
    progress: FriendNetworkProgress?,
    isLoading: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        updatedAt?.let {
            Text(
                text = strings.friendNetworkLastUpdated.replace("%s", formatTimestamp(it)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
        if (isFromCache) {
            Text(
                text = strings.friendNetworkCacheHint,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
        if (isLoading) {
            val progressText = progress?.let { "${it.current}/${it.total}" }.orEmpty()
            Text(
                text = strings.friendNetworkBuilding.replace("%s", progressText),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}

@Composable
private fun FriendNetworkGraph(
    nodes: List<MutualFriendData>,
    edges: Map<String, List<String>>,
    nodeColors: Map<String, Color>,
    selfId: String?,
    highlightIds: Set<String>,
    selectedId: String?,
    highlightId: String?,
    onHighlightChange: (String?) -> Unit,
    onNodeClick: (String) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val baseNodeSizePx = 43f
        val maxExtraSizePx = 34f
        val nodeSizePx = baseNodeSizePx + maxExtraSizePx // 布局计算用的固定参考值
        val labelWidth = 88.dp
        val labelWidthPx = with(density) { labelWidth.toPx() }
        val viewWidthPx = with(density) { maxWidth.toPx() }
        val viewHeightPx = with(density) { maxHeight.toPx() }
        val desiredSpacing = nodeSizePx * 1.3f
        val ringSpacing = nodeSizePx * 1.1f
        val maxRadius = estimateMaxRadius(
            nodeCount = nodes.size - if (selfId == null) 0 else 1,
            desiredSpacing = desiredSpacing,
            ringSpacing = ringSpacing
        )
        val scaleFactor = maxOf(1f, sqrt(nodes.size.toFloat().coerceAtLeast(1f)) / 10f)
        val requiredDiameter = maxRadius * 2f + desiredSpacing
        val layoutWidthPx = maxOf(viewWidthPx * scaleFactor, requiredDiameter)
        val layoutHeightPx = maxOf(viewHeightPx * scaleFactor, requiredDiameter)
        val minScale = 0.35f
        val maxScale = 3.5f
        val initialScale = maxOf(
            viewWidthPx / layoutWidthPx,
            viewHeightPx / layoutHeightPx
        ).coerceIn(minScale, 1.25f)
        var scale by remember(nodes.size, viewWidthPx, viewHeightPx) { mutableStateOf(initialScale) }
        var offset by remember(nodes.size, viewWidthPx, viewHeightPx) { mutableStateOf(Offset.Zero) }
        var hasUserInteracted by remember(nodes.size, selfId) { mutableStateOf(false) }
        val edgeList = remember(edges) { buildEdgeList(edges) }
        // 计算每个节点的度数（连接数）
        val nodeDegree = remember(edges) { nodes.associate { it.id to edges[it.id].orEmpty().size } }
        val maxDegree = remember(nodeDegree) { nodeDegree.values.maxOrNull() ?: 1 }
        val positions = remember(
            nodes,
            layoutWidthPx,
            layoutHeightPx,
            selfId,
            edgeList,
            edges,
        ) {
            computeNodePositions(
                nodes = nodes,
                selfId = selfId,
                layoutWidthPx = layoutWidthPx,
                layoutHeightPx = layoutHeightPx,
                mutualCounts = nodeDegree,
                desiredSpacing = desiredSpacing,
                ringSpacing = ringSpacing,
                edges = edges,
            )
        }
        val viewCenter = Offset(viewWidthPx / 2f, viewHeightPx / 2f)
        val layoutCenter = Offset(layoutWidthPx / 2f, layoutHeightPx / 2f)
        val centeredOffset = run {
            val selfPos = selfId?.let { positions[it] } ?: layoutCenter
            viewCenter - (selfPos * initialScale)
        }
        val renderScale = if (hasUserInteracted) scale else initialScale
        val renderOffset = if (hasUserInteracted) offset else centeredOffset
        val defaultColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
        val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(nodes.size, initialScale) {
                    detectTapGestures(
                        onDoubleTap = {
                            scale = initialScale
                            offset = centeredOffset
                            hasUserInteracted = false
                        }
                    )
                }
                .pointerInput(nodes.size) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val currentScale = if (hasUserInteracted) scale else initialScale
                        val currentOffset = if (hasUserInteracted) offset else centeredOffset
                        if (!hasUserInteracted && (pan != Offset.Zero || zoom != 1f)) {
                            hasUserInteracted = true
                            scale = currentScale
                            offset = currentOffset
                        }
                        val newScale = (currentScale * zoom).coerceIn(minScale, maxScale)
                        val layoutPoint = (centroid - currentOffset) / currentScale
                        val nextOffset = centroid - (layoutPoint * newScale)
                offset = nextOffset + pan
                        scale = newScale
                    }
                }
        ) {
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        translationX = renderOffset.x
                        translationY = renderOffset.y
                        scaleX = renderScale
                        scaleY = renderScale
                    }
                    .width(with(density) { layoutWidthPx.toDp() })
                    .height(with(density) { layoutHeightPx.toDp() })
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    edgeList.forEach { (from, to) ->
                        val fromPos = positions[from] ?: return@forEach
                        val toPos = positions[to] ?: return@forEach
                        val isHighlighted = highlightId != null &&
                            ((from == highlightId && highlightIds.contains(to)) ||
                                (to == highlightId && highlightIds.contains(from)))
                        // 使用社区颜色作为边的颜色
                        val communityColor = nodeColors[from]?.copy(alpha = 0.4f) ?: defaultColor
                        drawLine(
                            color = if (isHighlighted) highlightColor else communityColor,
                            start = fromPos,
                            end = toPos,
                            strokeWidth = if (isHighlighted) 3f else 1.5f
                        )
                    }
                }

                nodes.forEach { node ->
                    val pos = positions[node.id] ?: return@forEach
                    // 根据连接数计算头像大小
                    val degree = nodeDegree[node.id] ?: 0
                    val sizeRatio = if (maxDegree > 0) degree.toFloat() / maxDegree else 0f
                    val nodeSizeDp = with(density) { (baseNodeSizePx + maxExtraSizePx * sizeRatio).toDp() }
                    val nodeSizePxLocal = baseNodeSizePx + maxExtraSizePx * sizeRatio
                    val offset = IntOffset(
                        x = (pos.x - labelWidthPx / 2).roundToInt(),
                        y = (pos.y - nodeSizePxLocal / 2).roundToInt()
                    )
                    val isHighlighted = highlightIds.isEmpty() || highlightIds.contains(node.id)
                    val alpha = if (isHighlighted) 1f else 0.35f
                    Box(
                        modifier = Modifier
                            .offset { offset }
                            .width(labelWidth)
                            .alpha(alpha)
                            .pointerInput(node.id, highlightId) {
                                detectTapGestures(
                                    onTap = { onNodeClick(node.id) },
                                    onLongPress = { onHighlightChange(node.id) },
                                    onPress = {
                                        tryAwaitRelease()
                                        if (highlightId == node.id) {
                                            onHighlightChange(null)
                                        }
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        FriendNetworkNode(
                            node = node,
                            size = nodeSizeDp,
                            isSelected = node.id == selectedId,
                            communityColor = nodeColors[node.id],
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FriendNetworkNode(
    node: MutualFriendData,
    size: androidx.compose.ui.unit.Dp,
    isSelected: Boolean,
    communityColor: Color? = null,
) {
    val borderColor = when {
        isSelected -> MaterialTheme.colorScheme.primary
        communityColor != null -> communityColor
        else -> Color.Transparent
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(size)
                .border(width = if (communityColor != null || isSelected) 3.dp else 2.dp, color = borderColor, shape = CircleShape)
                .background(MaterialTheme.colorScheme.surface, CircleShape)
        ) {
            UserStateIcon(
                modifier = Modifier.fillMaxSize(),
                iconUrl = node.iconUrl,
                userStatus = node.status
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        if (node.displayName.isNotBlank()) {
            Text(
                text = node.displayName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun FriendNetworkSheet(
    node: MutualFriendData,
    mutualUsers: List<MutualFriendData>,
    onUserClick: (MutualFriendData) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onUserClick(node) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            UserStateIcon(
                modifier = Modifier.size(48.dp),
                iconUrl = node.iconUrl,
                userStatus = node.status
            )
            Spacer(modifier = Modifier.width(12.dp))
            val displayName = node.displayName.ifBlank { strings.users }
            Column {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = node.statusDescription.ifBlank { node.status.value },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))
        val visibleMutualUsers = mutualUsers.filter { it.id != HIDDEN_MUTUAL_USER_ID }
        val hiddenCount = mutualUsers.size - visibleMutualUsers.size
        val titleText = if (hiddenCount > 0) {
            strings.mutualFriendsCountWithHidden
                .replace("%total%", mutualUsers.size.toString())
                .replace("%hidden%", hiddenCount.toString())
        } else {
            strings.mutualFriendsCount.replace("%total%", mutualUsers.size.toString())
        }
        Text(
            text = titleText,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (mutualUsers.isEmpty()) {
            Text(
                text = strings.mutualFriendsEmpty.replace("%s", node.displayName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().height(280.dp)) {
                itemsIndexed(
                    items = visibleMutualUsers,
                    key = { index, user -> "${user.id}#$index" }
                ) { _, user ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .clickable { onUserClick(user) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        UserStateIcon(
                            modifier = Modifier.size(32.dp),
                            iconUrl = user.iconUrl,
                            userStatus = user.status
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = user.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

private const val HIDDEN_MUTUAL_USER_ID = "usr_00000000-0000-0000-0000-000000000000"

private fun buildEdgeList(edges: Map<String, List<String>>): List<Pair<String, String>> {
    val seen = HashSet<String>()
    val list = mutableListOf<Pair<String, String>>()
    edges.forEach { (from, tos) ->
        tos.forEach { to ->
            if (from == to) return@forEach
            val key = if (from < to) "$from|$to" else "$to|$from"
            if (seen.add(key)) {
                list.add(from to to)
            }
        }
    }
    return list
}

private fun estimateMaxRadius(
    nodeCount: Int,
    desiredSpacing: Float,
    ringSpacing: Float,
): Float {
    // 力导向布局不再需要预估半径，返回一个足够大的值
    return desiredSpacing * nodeCount.coerceAtLeast(1).toFloat().let { sqrt(it) * 2f }
}

/**
 * 力导向布局算法（简化版 ForceAtlas2）
 */
private fun computeNodePositions(
    nodes: List<MutualFriendData>,
    selfId: String?,
    layoutWidthPx: Float,
    layoutHeightPx: Float,
    mutualCounts: Map<String, Int>,
    desiredSpacing: Float,
    ringSpacing: Float,
    edges: Map<String, List<String>> = emptyMap(),
): Map<String, Offset> {
    if (nodes.isEmpty()) return emptyMap()
    val center = Offset(layoutWidthPx / 2f, layoutHeightPx / 2f)
    val n = nodes.size
    val nodeIds = nodes.map { it.id }
    val idx = nodeIds.mapIndexed { i, id -> id to i }.toMap()

    // 构建邻接表
    val adjacency = mutableMapOf<Int, MutableSet<Int>>()
    for (node in nodes) {
        val i = idx[node.id] ?: continue
        adjacency.getOrPut(i) { mutableSetOf() }
    }

    // 初始化位置：随机散布在中心附近（紧凑）
    val x = FloatArray(n)
    val y = FloatArray(n)
    val rng = java.util.Random(42)
    for (i in 0 until n) {
        val angle = rng.nextFloat() * 2f * PI.toFloat()
        val r = rng.nextFloat() * desiredSpacing * 0.3f
        x[i] = center.x + r * cos(angle)
        y[i] = center.y + r * sin(angle)
    }

    // 力导向迭代
    val iterations = 500
    val k = desiredSpacing * 1.5f // 理想距离（紧凑）
    val gravity = 1.6f
    val speed = 1f
    val fx = FloatArray(n)
    val fy = FloatArray(n)

    for (iter in 0 until iterations) {
        // 清零力
        fx.fill(0f)
        fy.fill(0f)

        // 斥力：所有节点对之间
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                var dx = x[i] - x[j]
                var dy = y[i] - y[j]
                val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                val force = (k * k) / dist // 斥力与距离平方成反比
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
                if (i >= j) continue // 避免重复计算
                var dx = x[j] - x[i]
                var dy = y[j] - y[i]
                val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                val force = (dist * dist) / k // 引力与距离平方成正比
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
        val maxDisplacement = k * speed * (1f - iter.toFloat() / iterations)
        for (i in 0 until n) {
            val fMag = sqrt(fx[i] * fx[i] + fy[i] * fy[i]).coerceAtLeast(0.01f)
            val disp = min(fMag, maxDisplacement)
            x[i] += (fx[i] / fMag) * disp
            y[i] += (fy[i] / fMag) * disp
        }
    }

    return nodeIds.mapIndexed { i, id -> id to Offset(x[i], y[i]) }.toMap()
}

private fun formatTimestamp(epochMillis: Long): String {
    val local = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault())
    val hour = local.hour.toString().padStart(2, '0')
    val minute = local.minute.toString().padStart(2, '0')
    return "${local.date} $hour:$minute"
}
