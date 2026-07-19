package io.github.vrcmteam.vrcm.presentation.screens.avatar

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import io.github.vrcmteam.vrcm.core.extensions.toLocalDateTime
import io.github.vrcmteam.vrcm.network.api.files.data.PlatformType
import io.github.vrcmteam.vrcm.presentation.compoments.AImage
import io.github.vrcmteam.vrcm.presentation.compoments.LocalSharedSuffixKey
import io.github.vrcmteam.vrcm.presentation.compoments.TextChip
import io.github.vrcmteam.vrcm.presentation.compoments.sharedBoundsBy
import io.github.vrcmteam.vrcm.presentation.extensions.currentNavigator
import io.github.vrcmteam.vrcm.presentation.extensions.getInsetPadding
import io.github.vrcmteam.vrcm.presentation.extensions.ignoredFormat
import io.github.vrcmteam.vrcm.presentation.screens.avatar.data.AvatarProfileVo
import io.github.vrcmteam.vrcm.presentation.screens.user.UserProfileScreen
import io.github.vrcmteam.vrcm.presentation.screens.user.data.UserProfileVo
import io.github.vrcmteam.vrcm.presentation.settings.locale.strings
import io.github.vrcmteam.vrcm.presentation.supports.AppIcons
import io.github.vrcmteam.vrcm.service.platformPackages
import presentation.compoments.TopMenuBar

class AvatarProfileScreen(
    private val avatarProfileVo: AvatarProfileVo,
    private val sharedSuffixKey: String = "",
) : Screen {

    @OptIn(ExperimentalSharedTransitionApi::class)
    @Composable
    override fun Content() {
        val currentNavigator = currentNavigator
        val screenModel: AvatarProfileScreenModel = koinScreenModel()
        val avatarState by screenModel.avatarProfileState.collectAsState()

        LaunchedEffect(avatarProfileVo.avatarId) {
            screenModel.refreshAvatarData(avatarProfileVo)
        }

        val avatar = avatarState ?: avatarProfileVo
        val scrollState = rememberScrollState()

        androidx.compose.runtime.CompositionLocalProvider(
            LocalSharedSuffixKey provides sharedSuffixKey
        ) {
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize()
            ) {
                val bannerHeight = maxWidth * 9f / 16f
                val offsetDp = with(LocalDensity.current) { scrollState.value.toDp() }
                val remainingDistance = bannerHeight - offsetDp
                val ratio = ((remainingDistance / bannerHeight).coerceIn(0f, 1f)).let {
                    FastOutSlowInEasing.transform(it)
                }
                val topBarHeight = 64.dp
                val sysTopPadding = getInsetPadding(WindowInsets::getTop)

                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                    ) {
                        // Banner
                        AvatarBanner(
                            avatar = avatar,
                            bannerHeight = bannerHeight
                        )
                        // Header info
                        AvatarHeaderInfo(avatar = avatar)
                        // Details
                        AvatarDetailsContent(avatar = avatar)
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                    // Top bar
                    TopMenuBar(
                        topBarHeight = topBarHeight,
                        sysTopPadding = sysTopPadding,
                        offsetDp = 0.dp,
                        ratio = ratio,
                        onReturn = { currentNavigator.pop() },
                        onMenu = null
                    )
                    // Collapsing title
                    CollapsingAvatarTitle(
                        avatar = avatar,
                        scrollPx = scrollState.value.toFloat(),
                        bannerHeight = bannerHeight,
                        topBarHeight = topBarHeight,
                        sysTopPadding = sysTopPadding
                    )
                }
            }
        }
    }
}

@Composable
private fun AvatarBanner(
    avatar: AvatarProfileVo,
    bannerHeight: Dp,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(bannerHeight)
    ) {
        val heroImage = avatar.imageUrl
        if (!heroImage.isNullOrBlank()) {
            AImage(
                modifier = Modifier.fillMaxSize(),
                imageData = heroImage,
                contentDescription = "AvatarBanner"
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.surfaceVariant,
                                MaterialTheme.colorScheme.surface
                            )
                        )
                    )
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                    )
                )
        )
    }
}

@Composable
private fun AvatarHeaderInfo(avatar: AvatarProfileVo) {
    val currentNavigator = currentNavigator
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Platform icons
        val platforms = remember(avatar.unityPackages) {
            avatar.unityPackages.platformPackages.keys.sortedBy { it.name }
        }
        if (platforms.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = strings.avatarPlatforms,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                platforms.forEach { platform ->
                    val icon = when (platform) {
                        PlatformType.Android -> AppIcons.Android
                        PlatformType.Ios -> AppIcons.Apple
                        PlatformType.Windows -> AppIcons.Windows
                    }
                    Icon(
                        imageVector = icon,
                        contentDescription = platform.name,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // Author row - clickable to navigate to user profile
        if (avatar.authorName.isNotBlank()) {
            Surface(
                modifier = Modifier
                    .clip(MaterialTheme.shapes.medium)
                    .clickable {
                        if (avatar.authorId.isNotBlank()) {
                            currentNavigator push UserProfileScreen(
                                userProfileVO = UserProfileVo(
                                    id = avatar.authorId,
                                    displayName = avatar.authorName
                                )
                            )
                        }
                    },
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                shape = MaterialTheme.shapes.medium
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = AppIcons.Person,
                        contentDescription = strings.avatarAuthor,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = avatar.authorName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun AvatarDetailsContent(avatar: AvatarProfileVo) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Description
        if (avatar.description.isNotBlank()) {
            TextSection(
                title = strings.avatarDescription,
                text = avatar.description
            )
        }

        // Performance
        avatar.performance?.let { perf ->
            val performanceItems = listOfNotNull(
                perf.standalonewindows?.let { "Windows" to it },
                perf.android?.let { "Android" to it },
                perf.ios?.let { "iOS" to it },
            )
            if (performanceItems.isNotEmpty()) {
                SectionCard(title = strings.avatarPerformance) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        performanceItems.forEach { (platform, rating) ->
                            KeyValueChip(
                                label = platform,
                                value = rating,
                                modifier = Modifier.widthIn(min = 100.dp)
                            )
                        }
                    }
                }
            }
        }

        // Details
        val detailItems = listOfNotNull(
            strings.avatarVersion to avatar.version.toString(),
            avatar.releaseStatus.takeIf { it.isNotBlank() }?.let {
                "Release Status" to it.replaceFirstChar { c ->
                    if (c.isLowerCase()) c.titlecase() else c.toString()
                }
            },
            formatLocalTime(avatar.createdAt)?.let { strings.avatarCreatedAt to it },
            formatLocalTime(avatar.updatedAt)?.let { strings.avatarUpdatedAt to it },
        )
        if (detailItems.isNotEmpty()) {
            SectionCard(title = strings.avatarVersion) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    detailItems.forEach { (label, value) ->
                        KeyValueChip(
                            label = label,
                            value = value,
                            modifier = Modifier.widthIn(min = 140.dp)
                        )
                    }
                }
            }
        }

        // Tags
        if (avatar.tags.isNotEmpty()) {
            SectionCard(title = strings.avatarTags) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    avatar.tags.take(18).forEach { tag ->
                        TextChip(text = tag)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun CollapsingAvatarTitle(
    avatar: AvatarProfileVo,
    scrollPx: Float,
    bannerHeight: Dp,
    topBarHeight: Dp,
    sysTopPadding: Dp,
) {
    val density = LocalDensity.current
    val startIconSize = 64.dp
    val endIconSize = 28.dp
    val startYPx = with(density) { (bannerHeight - 16.dp - startIconSize).toPx() }
    val endYPx = with(density) { (sysTopPadding + (topBarHeight - endIconSize) / 2).toPx() }
    val travelPx = (startYPx - endYPx).coerceAtLeast(1f)
    val progress = (scrollPx / travelPx).coerceIn(0f, 1f)
    val iconSize = lerp(startIconSize, endIconSize, progress)
    val nameScale = lerp(1f, 0.8f, progress)
    val xShift = lerp(0.dp, 50.dp, progress)
    val rowSpacing = lerp(12.dp, 8.dp, progress)
    val yPx = (startYPx - scrollPx).coerceAtLeast(endYPx)
    val statusAlpha = 1f - progress

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(bannerHeight)
    ) {
        Row(
            modifier = Modifier
                .padding(start = 16.dp)
                .graphicsLayer {
                    translationX = with(density) { xShift.toPx() }
                    translationY = yPx
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(rowSpacing)
        ) {
            AImage(
                modifier = Modifier
                    .sharedBoundsBy("${avatar.avatarId}AvatarImage")
                    .size(iconSize)
                    .clip(MaterialTheme.shapes.medium),
                imageData = avatar.thumbnailImageUrl ?: "",
                contentDescription = "AvatarIcon"
            )
            Column(
                modifier = Modifier.graphicsLayer {
                    scaleX = nameScale
                    scaleY = nameScale
                },
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = avatar.name.ifBlank { strings.unknown },
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (statusAlpha > 0f) {
                    Text(
                        modifier = Modifier.graphicsLayer { alpha = statusAlpha },
                        text = avatar.authorName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// ======== Utility composables ========

@Composable
private fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            content()
        }
    }
}

@Composable
private fun TextSection(
    title: String,
    text: String,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = title, modifier = modifier) {
        SelectionContainer {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun KeyValueChip(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
) {
    val valueText = value?.takeIf { it.isNotBlank() } ?: strings.unknown
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

private fun lerp(start: Float, end: Float, fraction: Float): Float =
    start + (end - start) * fraction

private fun lerp(start: Dp, end: Dp, fraction: Float): Dp =
    Dp(lerp(start.value, end.value, fraction))

private fun formatLocalTime(value: String?): String? {
    val raw = value?.trim().orEmpty()
    if (raw.isBlank()) return null
    return raw.toLocalDateTime()?.ignoredFormat ?: raw
}
