package io.github.vrcmteam.vrcm.presentation.screens.world

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil3.CoilImage
import io.github.vrcmteam.vrcm.core.shared.SharedFlowCentre
import io.github.vrcmteam.vrcm.network.api.worlds.WorldsApi
import io.github.vrcmteam.vrcm.network.api.worlds.data.WorldData
import io.github.vrcmteam.vrcm.presentation.compoments.ToastText
import io.github.vrcmteam.vrcm.presentation.screens.world.data.WorldProfileVo
import io.github.vrcmteam.vrcm.presentation.settings.locale.strings
import io.github.vrcmteam.vrcm.presentation.supports.AppIcons
import io.github.vrcmteam.vrcm.service.AuthService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

class RecentWorldsScreenModel(
    private val authService: AuthService,
    private val worldsApi: WorldsApi,
) : ScreenModel {

    private val _worlds = mutableStateOf<List<WorldData>>(emptyList())
    val worlds by _worlds

    private val _isLoading = mutableStateOf(true)
    val isLoading by _isLoading

    private val _isLoadingMore = mutableStateOf(false)
    val isLoadingMore by _isLoadingMore

    private val _endReached = mutableStateOf(false)
    val endReached by _endReached

    private var pagingState = RecentWorldPagingState<WorldData>()

    fun loadRecentWorlds() {
        loadPage(reset = true)
    }

    fun loadMoreRecentWorlds() {
        loadPage(reset = false)
    }

    private fun loadPage(reset: Boolean) {
        if (_isLoadingMore.value || (!reset && (_isLoading.value || _endReached.value))) return

        if (reset) {
            pagingState = RecentWorldPagingState()
            _worlds.value = emptyList()
            _endReached.value = false
            _isLoading.value = true
        } else {
            _isLoadingMore.value = true
        }

        screenModelScope.launch(Dispatchers.IO) {
            authService.reTryAuthCatching {
                worldsApi.getRecentWorlds(n = RECENT_WORLDS_PAGE_SIZE, offset = pagingState.nextOffset)
            }.onSuccess { page ->
                pagingState = appendRecentWorldPage(
                    current = pagingState,
                    page = page,
                    pageSize = RECENT_WORLDS_PAGE_SIZE,
                    keySelector = { it.id },
                )
                _worlds.value = pagingState.items
                _endReached.value = pagingState.endReached
            }.onFailure {
                SharedFlowCentre.toastText.emit(ToastText.Error(it.message.toString()))
            }
            _isLoading.value = false
            _isLoadingMore.value = false
        }
    }

    private companion object {
        const val RECENT_WORLDS_PAGE_SIZE = 50
    }
}

internal data class RecentWorldPagingState<T>(
    val items: List<T> = emptyList(),
    val nextOffset: Int = 0,
    val endReached: Boolean = false,
)

internal fun <T, K> appendRecentWorldPage(
    current: RecentWorldPagingState<T>,
    page: List<T>,
    pageSize: Int,
    keySelector: (T) -> K,
): RecentWorldPagingState<T> = RecentWorldPagingState(
    items = (current.items + page).distinctBy(keySelector),
    nextOffset = current.nextOffset + page.size,
    endReached = page.size < pageSize,
)

internal fun shouldLoadNextRecentWorldPage(
    lastVisibleIndex: Int,
    totalItemsCount: Int,
    preloadDistance: Int = 5,
): Boolean = totalItemsCount > 0 &&
        lastVisibleIndex >= (totalItemsCount - preloadDistance).coerceAtLeast(0)

object RecentWorldsScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model: RecentWorldsScreenModel = koinScreenModel()

        LaunchedEffect(Unit) {
            model.loadRecentWorlds()
        }

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            text = strings.recentWorldsTitle,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(AppIcons.ArrowBackIosNew, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { paddingValues ->
            if (model.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (model.worlds.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = strings.recentWorldsEmpty,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val listState = rememberLazyListState()
                LaunchedEffect(listState) {
                    snapshotFlow {
                        val layoutInfo = listState.layoutInfo
                        val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                        lastVisibleIndex to layoutInfo.totalItemsCount
                    }.distinctUntilChanged().collect { (lastVisibleIndex, totalItemsCount) ->
                        if (shouldLoadNextRecentWorldPage(lastVisibleIndex, totalItemsCount)) {
                            model.loadMoreRecentWorlds()
                        }
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(model.worlds, key = { it.id }) { world ->
                        RecentWorldItem(world) {
                            navigator.push(
                                WorldProfileScreen(
                                    worldProfileVO = WorldProfileVo(world),
                                )
                            )
                        }
                    }
                    if (model.isLoadingMore) {
                        item(key = "recent-worlds-loading") {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentWorldItem(world: WorldData, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoilImage(
                imageModel = { world.thumbnailImageUrl ?: world.imageUrl },
                imageOptions = ImageOptions(contentScale = ContentScale.Crop),
                imageLoader = { koinInject() },
                modifier = Modifier
                    .size(80.dp, 45.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = world.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = world.authorName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}
