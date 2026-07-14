package io.github.vrcmteam.vrcm.presentation.screens.avatar

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import io.github.vrcmteam.vrcm.core.extensions.toLocalDate
import io.github.vrcmteam.vrcm.presentation.compoments.ATooltipBox
import io.github.vrcmteam.vrcm.presentation.compoments.ProfileScaffold
import io.github.vrcmteam.vrcm.presentation.compoments.sharedBoundsBy
import io.github.vrcmteam.vrcm.presentation.extensions.currentNavigator
import io.github.vrcmteam.vrcm.presentation.extensions.simpleClickable
import io.github.vrcmteam.vrcm.presentation.extensions.simpleFormat
import io.github.vrcmteam.vrcm.presentation.screens.avatar.data.AvatarPlatformInfo
import io.github.vrcmteam.vrcm.presentation.screens.avatar.data.AvatarProfileVo
import io.github.vrcmteam.vrcm.presentation.screens.user.UserProfileScreen
import io.github.vrcmteam.vrcm.presentation.screens.user.data.UserProfileVo
import io.github.vrcmteam.vrcm.presentation.settings.locale.strings
import io.github.vrcmteam.vrcm.presentation.supports.AppIcons

class AvatarProfileScreen(
    private val avatarProfileVo: AvatarProfileVo,
    private val sharedSuffixKey: String = "",
) : Screen {

    @OptIn(ExperimentalSharedTransitionApi::class)
    @Composable
    override fun Content() {
        val navigator = currentNavigator

        ProfileScaffold(
            imageModifier = Modifier.sharedBoundsBy("${avatarProfileVo.avatarId}AvatarImage"),
            profileImageUrl = avatarProfileVo.avatarImageUrl,
            iconUrl = null,
            onReturn = { navigator.pop() },
        ) { ratio, contentMinHeight ->
            AvatarProfileContent(
                avatarProfileVo = avatarProfileVo,
                contentMinHeight = contentMinHeight,
            )
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun AvatarProfileContent(
    avatarProfileVo: AvatarProfileVo,
    contentMinHeight: Dp,
) {
    val navigator = currentNavigator

    // 名称
    SelectionContainer {
        Text(
            text = avatarProfileVo.avatarName,
            color = MaterialTheme.colorScheme.secondary,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }

    // 作者
    if (avatarProfileVo.authorName.isNotBlank()) {
        Text(
            text = avatarProfileVo.authorName,
            color = MaterialTheme.colorScheme.secondary,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.simpleClickable {
                navigator.push(
                    UserProfileScreen(
                        userProfileVO = UserProfileVo(id = avatarProfileVo.authorId)
                    )
                )
            }
        )
    }

    // 描述
    if (avatarProfileVo.avatarDescription.isNotBlank()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            shape = MaterialTheme.shapes.extraLarge
        ) {
            SelectionContainer {
                Text(
                    modifier = Modifier.padding(12.dp),
                    text = avatarProfileVo.avatarDescription,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    // 属性信息卡片
    AvatarInfoCards(avatarProfileVo)

    Spacer(modifier = Modifier.height(12.dp))

    // 平台信息（隐藏Unknown平台和Unknown评级）
    val knownPlatforms = avatarProfileVo.platformInfos.filter {
        it.platform != "Unknown" && !it.performanceRating.isNullOrEmpty() && it.performanceRating.lowercase() != "unknown"
    }
    if (knownPlatforms.isNotEmpty()) {
        AvatarPlatformSection(knownPlatforms)
    }
}

@Composable
private fun AvatarInfoCards(avatarProfileVo: AvatarProfileVo) {
    val infoCards = mutableListOf<Triple<ImageVector, String, String>>()

    // 模型评级 - 取所有平台中最低的评级
    val worstRating = avatarProfileVo.platformInfos
        .mapNotNull { it.performanceRating }
        .minByOrNull { ratingOrder(it) }
    if (worstRating != null) {
        infoCards.add(Triple(AppIcons.Hot, worstRating.replaceFirstChar { it.uppercase() }, strings.avatarProfileRating))
    }

    // 版本
    avatarProfileVo.version?.let {
        infoCards.add(Triple(AppIcons.Update, "v$it", strings.avatarProfileVersion))
    }

    // 发布状态
    infoCards.add(Triple(
        AppIcons.Visibility,
        avatarProfileVo.releaseStatus.replaceFirstChar { it.uppercase() },
        strings.avatarProfileStatus
    ))

    // 创建时间
    avatarProfileVo.createdAt?.takeIf { it.isNotEmpty() }?.toLocalDate()?.simpleFormat?.let {
        infoCards.add(Triple(AppIcons.Publish, it, strings.avatarProfileCreated))
    }

    // 更新时间
    avatarProfileVo.updatedAt?.takeIf { it.isNotEmpty() }?.toLocalDate()?.simpleFormat?.let {
        infoCards.add(Triple(AppIcons.DateRange, it, strings.avatarProfileUpdated))
    }

    val itemSize = DpSize(width = 80.dp, height = 68.dp)
    val cardsPerRow = 4
    val rows = (infoCards.size + cardsPerRow - 1) / cardsPerRow

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (rowIndex in 0 until rows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (colIndex in 0 until cardsPerRow) {
                    val cardIndex = rowIndex * cardsPerRow + colIndex
                    if (cardIndex < infoCards.size) {
                        val (icon, label, description) = infoCards[cardIndex]
                        AvatarInfoItemBlock(
                            size = itemSize,
                            icon = icon,
                            label = label,
                            description = description
                        )
                    } else {
                        Spacer(modifier = Modifier.size(itemSize))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AvatarInfoItemBlock(
    size: DpSize,
    icon: ImageVector,
    label: String,
    description: String,
) {
    ATooltipBox(
        tooltip = {
            Text(text = description, style = MaterialTheme.typography.labelSmall)
        }
    ) {
        val bgColor = when {
            description == strings.avatarProfileRating -> ratingColor(label)
            else -> MaterialTheme.colorScheme.tertiary
        }
        Column(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(12.dp))
                .background(bgColor),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                tint = MaterialTheme.colorScheme.onPrimary,
                contentDescription = description,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun AvatarPlatformSection(platformInfos: List<AvatarPlatformInfo>) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = strings.avatarProfilePlatforms,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            shape = MaterialTheme.shapes.extraLarge
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                platformInfos.forEach { info ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = info.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = info.ratingDisplay,
                            style = MaterialTheme.typography.labelMedium,
                            color = ratingColor(info.performanceRating)
                        )
                    }
                    if (info != platformInfos.last()) {
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 评级颜色
 */
@Composable
private fun ratingColor(rating: String?): androidx.compose.ui.graphics.Color {
    return when (rating?.lowercase()) {
        "excellent" -> androidx.compose.ui.graphics.Color(0xFF51E57E)
        "good" -> androidx.compose.ui.graphics.Color(0xFF51E57E)
        "medium" -> androidx.compose.ui.graphics.Color(0xFFFFD24C)
        "poor" -> MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
        "verypoor" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }
}

/**
 * 评级排序权重
 */
private fun ratingOrder(rating: String): Int = when (rating.lowercase()) {
    "excellent" -> 0
    "good" -> 1
    "medium" -> 2
    "poor" -> 3
    "verypoor" -> 4
    else -> 5
}
