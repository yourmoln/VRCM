package io.github.vrcmteam.vrcm.presentation.screens.user

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import io.github.vrcmteam.vrcm.core.shared.SharedFlowCentre
import io.github.vrcmteam.vrcm.getAppPlatform
import io.github.vrcmteam.vrcm.network.api.attributes.FavoriteType
import io.github.vrcmteam.vrcm.network.api.attributes.FriendRequestStatus.*
import io.github.vrcmteam.vrcm.network.api.users.data.LimitedUserGroup
import cafe.adriel.voyager.navigator.Navigator
import io.github.vrcmteam.vrcm.presentation.compoments.*
import io.github.vrcmteam.vrcm.presentation.extensions.currentNavigator
import io.github.vrcmteam.vrcm.presentation.extensions.enableIf
import io.github.vrcmteam.vrcm.presentation.extensions.openUrl
import io.github.vrcmteam.vrcm.presentation.screens.auth.AuthAnimeScreen
import io.github.vrcmteam.vrcm.presentation.screens.gallery.GalleryScreen
import io.github.vrcmteam.vrcm.presentation.screens.home.data.FriendLocation
import io.github.vrcmteam.vrcm.presentation.screens.group.GroupProfileScreen
import io.github.vrcmteam.vrcm.presentation.screens.group.data.GroupProfileVo
import io.github.vrcmteam.vrcm.presentation.screens.user.data.UserProfileVo
import io.github.vrcmteam.vrcm.presentation.screens.world.RecentWorldsScreen
import io.github.vrcmteam.vrcm.presentation.screens.world.WorldProfileScreen
import io.github.vrcmteam.vrcm.presentation.screens.world.components.FavoriteGroupBottomSheet
import io.github.vrcmteam.vrcm.presentation.screens.world.data.WorldProfileVo
import io.github.vrcmteam.vrcm.presentation.settings.locale.strings
import io.github.vrcmteam.vrcm.presentation.supports.AppIcons
import io.github.vrcmteam.vrcm.presentation.supports.LanguageIcons
import io.github.vrcmteam.vrcm.presentation.supports.WebIcons
import kotlinx.coroutines.launch
import org.koin.core.parameter.parametersOf

data class UserProfileScreen(
    private val userProfileVO: UserProfileVo,
    private val sharedSuffixKey: String = "",
) : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @ExperimentalSharedTransitionApi
    @Composable
    override fun Content() {
        val currentNavigator = currentNavigator
        val userProfileScreenModel: UserProfileScreenModel = koinScreenModel { parametersOf(userProfileVO) }

        LaunchedEffect(userProfileVO.id) {
            userProfileScreenModel.refreshUser(userProfileVO.id)
        }

        LaunchedEffect(Unit) {
            SharedFlowCentre.logout.collect {
                currentNavigator replaceAll AuthAnimeScreen(false)
            }
        }

        val currentUser = userProfileScreenModel.userState
        val userGroups = userProfileScreenModel.userGroups
        val mutualGroups = userProfileScreenModel.mutualGroups
        val userNote = userProfileScreenModel.userNote
        var bottomSheetIsVisible by remember { mutableStateOf(false) }
        val sheetState = rememberModalBottomSheetState()
        var openAlertDialog by remember { mutableStateOf(false) }
        // Control showing favorite group management for Friend type
        var showFriendFavoriteSheet by remember { mutableStateOf(false) }
        CompositionLocalProvider(LocalSharedSuffixKey provides sharedSuffixKey) {
            ProfileScaffold(
                imageModifier = Modifier.sharedBoundsBy("${userProfileVO.id}UserIcon"),
                profileImageUrl = currentUser.profileImageUrl,
                iconUrl = currentUser.iconUrl,
                onReturn = { currentNavigator.pop() },
                onMenu = { bottomSheetIsVisible = true },
            ) { ratio ->
                ProfileContent(
                    currentUser = currentUser,
                    friendLocation = userProfileScreenModel.friendLocation,
                    userGroups = userGroups,
                    mutualGroups = mutualGroups,
                    userNote = userNote,
                    onSaveNote = { note -> userProfileScreenModel.saveUserNote(currentUser.id, note) },
                    ratio = ratio
                )
            }
        }
        ABottomSheet(
            isVisible = bottomSheetIsVisible,
            sheetState = sheetState,
            onDismissRequest = { bottomSheetIsVisible = false }
        ) {
            SheetItems(
                currentUser = currentUser,
                userProfileScreenModel = userProfileScreenModel,
                hideSheet = { sheetState.hide() },
                onHideCompletion = {
                    if (!sheetState.isVisible) bottomSheetIsVisible = false
                },
                openAlertDialog = { openAlertDialog = true },
                onManageFriendFavorite = { showFriendFavoriteSheet = true }
            )
        }
        // Friend FavoriteType group management bottom sheet
        FavoriteGroupBottomSheet(
            isVisible = showFriendFavoriteSheet,
            favoriteId = currentUser.id,
            favoriteType = FavoriteType.Friend,
            onDismiss = { showFriendFavoriteSheet = false }
        )
        JsonAlertDialog(
            openAlertDialog = openAlertDialog,
            onDismissRequest = { openAlertDialog = false }
        ) {
            Text(text = userProfileScreenModel.userJson)
        }
    }

}

@Composable
private fun ColumnScope.SheetItems(
    currentUser: UserProfileVo,
    userProfileScreenModel: UserProfileScreenModel,
    hideSheet: suspend () -> Unit,
    onHideCompletion: () -> Unit,
    openAlertDialog: () -> Unit,
    onManageFriendFavorite: () -> Unit,
) {
    val navigator = LocalNavigator.currentOrThrow
    val localeStrings = strings
    val scope = rememberCoroutineScope()
    // 添加媒体库选项，只有当是自己的个人资料且是支持者时才显示
    if (currentUser.isSelf && currentUser.isSupporter) {

        SheetButtonItem(text = localeStrings.profileViewGallery, onClick = {
            scope.launch { hideSheet() }.invokeOnCompletion {
                onHideCompletion()
                navigator.push(GalleryScreen)
            }
        })

    }

    // 最近世界 - 仅自己可见
    if (currentUser.isSelf) {
        SheetButtonItem(text = localeStrings.recentWorldsTitle, onClick = {
            scope.launch { hideSheet() }.invokeOnCompletion {
                onHideCompletion()
                navigator.push(RecentWorldsScreen)
            }
        })
    }

    // 管理好友收藏分组，仅当不是自己且是好友时显示
    if (!currentUser.isSelf) {
        SheetButtonItem(text = localeStrings.selectFavoriteGroup, onClick = {
            scope.launch { hideSheet() }.invokeOnCompletion {
                onHideCompletion()
                onManageFriendFavorite()
            }
        })
    }

    SheetButtonItem(
        text = if (currentUser.isSelf) localeStrings.profileViewFriendNetwork else localeStrings.profileViewMutualFriends,
        onClick = {
            scope.launch { hideSheet() }.invokeOnCompletion {
                onHideCompletion()
                if (currentUser.isSelf) {
                    navigator.push(FriendNetworkScreen)
                } else {
                    navigator.push(MutualFriendsScreen(currentUser.id, currentUser.displayName))
                }
            }
        }
    )

    // Boop 按钮 - 仅好友可见
    if (!currentUser.isSelf && currentUser.isFriend) {
        SheetButtonItem(text = localeStrings.profileBoop, onClick = {
            scope.launch { hideSheet() }.invokeOnCompletion {
                onHideCompletion()
                userProfileScreenModel.boop(currentUser.id)
            }
        })
    }

    // 邀请来我的实例 - 仅好友可见
    if (!currentUser.isSelf && currentUser.isFriend) {
        SheetButtonItem(text = localeStrings.profileInviteToMyInstance, onClick = {
            scope.launch { hideSheet() }.invokeOnCompletion {
                onHideCompletion()
                userProfileScreenModel.inviteToMyInstance(currentUser.id)
            }
        })
    }

    FriendRequestSheetItem(
        currentUser,
        userProfileScreenModel,
        hideSheet,
        onHideCompletion,
    )
    SheetButtonItem(localeStrings.profileViewJsonData, onClick = {
        scope.launch { hideSheet() }.invokeOnCompletion {
            onHideCompletion()
            openAlertDialog()
        }
    })

}

@Composable
private fun ColumnScope.FriendRequestSheetItem(
    currentUser: UserProfileVo,
    userProfileScreenModel: UserProfileScreenModel,
    hideSheet: suspend () -> Unit,
    onHideCompletion: () -> Unit,
) {
    val localeStrings = strings
    val action: Pair<String, suspend () -> Boolean>? = when {
        // 当前用户不是朋友且不是自己
        !currentUser.isFriend && !currentUser.isSelf -> {
            when (currentUser.friendRequestStatus) {
                // 状态为Null,则发送好友请求
                Null -> localeStrings.profileSendFriendRequest to {
                    userProfileScreenModel.sendFriendRequest(currentUser.id, localeStrings.profileFriendRequestSent)
                }
                // 状态为Outgoing,则取消发送好友请求
                Outgoing -> localeStrings.profileDeleteFriendRequest to {
                    userProfileScreenModel.deleteFriendRequest(
                        currentUser.id,
                        localeStrings.profileFriendRequestDeleted
                    )
                }

                // 状态为Incoming,则接受好友请求
                Incoming -> localeStrings.profileAcceptFriendRequest to {
                    userProfileScreenModel.acceptFriendRequest(
                        currentUser.id,
                        localeStrings.profileFriendRequestAccepted
                    )
                }

                else -> null
            }
        }
        // 状态为Completed,则删除好友
        // TODO: 加一个弹窗提示是否删除好友
        currentUser.isFriend && currentUser.friendRequestStatus == Completed ->
            localeStrings.profileUnfriend to {
                userProfileScreenModel.unfriend(currentUser.id, localeStrings.profileUnfriended)
            }

        else -> null
    }

    if (action == null) return

    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(true) }
    SheetButtonItem(action.first, onClick = {
        scope.launch { hideSheet() }.invokeOnCompletion {
            scope.launch {
                enabled = false
                when (action.second()) {
                    true -> hideSheet()
                    false -> enabled = true
                }
            }.invokeOnCompletion {
                onHideCompletion()
            }
        }
    })
}

@Composable
private fun ColumnScope.SheetButtonItem(
    text: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable RowScope.(String) -> Unit = { Text(text = it) },
) {
    TextButton(
        modifier = Modifier
            .align(Alignment.CenterHorizontally)
            .fillMaxWidth()
            .padding(vertical = 2.dp, horizontal = 24.dp),
        enabled = enabled,
        onClick = onClick
    ) {
        content(text.orEmpty())
    }
}

@Composable
private fun JsonAlertDialog(
    openAlertDialog: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    if (openAlertDialog) {
        AlertDialog(
            icon = {
                Icon(AppIcons.Person, contentDescription = "AlertDialogIcon")
            },
            text = {
                Box(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                ) {
                    SelectionContainer {
                        content()
                    }
                }
            },
            onDismissRequest = onDismissRequest,
            confirmButton = {
                TextButton(
                    onClick = onDismissRequest
                ) {
                    Text("Back")
                }
            }
        )
    }
}

@Composable
private fun ProfileContent(
    currentUser: UserProfileVo?,
    friendLocation: FriendLocation?,
    userGroups: List<LimitedUserGroup>,
    mutualGroups: List<LimitedUserGroup>,
    userNote: String,
    onSaveNote: (String) -> Unit,
    ratio: Float,
) {
    if (currentUser == null) return
    val sharedSuffixKey = LocalSharedSuffixKey.current
    val inverseRatio = 1 - ratio
    val navigator = currentNavigator
    // 当上方图片完整显示时子内容自动滚动到顶部
    if (inverseRatio == 0f) {
        LaunchedEffect(Unit) {}
    }
    // TrustRank + UserName + VRC+
    UserInfoRow(user = currentUser, canCopy = true)
    UserPronouns(pronouns = currentUser.pronouns)
    // status
    UserStatusRow(canCopy = true, user = currentUser,)
    // LanguagesRow && LinksRow
    LangAndLinkRow(currentUser)

    var isSelected by remember { mutableStateOf(false) }
    // LocationCard: show the room of this user and friends in the same room
    friendLocation?.let { loc ->
        val navigator = currentNavigator
        val locationSharedSuffixKey = "UER:$sharedSuffixKey"
        // 创建临时的 WorldProfileVo
        val onClickWorldImage = {
            val homeInstanceVo = friendLocation.instants.value
            val tempWorldProfileVo = WorldProfileVo(homeInstanceVo)
            navigator push WorldProfileScreen(
                worldProfileVO = tempWorldProfileVo,
                location = friendLocation.location,
                sharedSuffixKey = locationSharedSuffixKey
            )
        }

        // 防止当前用户的共享元素冲突
        CompositionLocalProvider(LocalSharedSuffixKey provides locationSharedSuffixKey) {
            LocationCard(
                location = loc,
                isSelected = isSelected,
                onClickWorldImage = onClickWorldImage,
                onClickLocationCard = { isSelected = !isSelected },
            ) { friends ->
                UserIconsRow(
                    modifier = Modifier.fillMaxWidth(),
                    instanceId = loc.location,
                    friends = friends,
                    onClickUserIcon = { user ->
                        navigator replace UserProfileScreen(
                            userProfileVO = UserProfileVo(user),
                            sharedSuffixKey = sharedSuffixKey
                        )
                    }
                )
            }
        }
    }

    // Bio + Groups 可滚动区域
    val scrollState = rememberScrollState()
    if (inverseRatio == 0f) {
        LaunchedEffect(Unit) {
            scrollState.animateScrollTo(0)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // User Note (not for self)
        if (!currentUser.isSelf) {
            UserNoteSection(
                note = userNote,
                onSaveNote = onSaveNote,
            )
        }

        // Bio
        if (currentUser.bio.isNotBlank()) {
            SectionCard(
                title = strings.userTabBio,
                modifier = Modifier.fillMaxWidth()
            ) {
                SelectionContainer {
                    Text(
                        text = currentUser.bio,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        // Mutual Groups
        if (mutualGroups.isNotEmpty() && !currentUser.isSelf) {
            GroupListSection(
                title = strings.userTabMutualGroups,
                groups = mutualGroups,
                navigator = navigator,
            )
        }

        // Groups
        if (userGroups.isNotEmpty()) {
            GroupListSection(
                title = strings.userTabGroups,
                groups = userGroups,
                navigator = navigator,
            )
        }
    }
}

@Composable
fun UserPronouns(pronouns: String) {
    if (pronouns.isNotEmpty()) {
        Text(
            text = "(${pronouns})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        )
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
private inline fun LangAndLinkRow(userProfileVO: UserProfileVo) {
    val speakLanguages = userProfileVO.speakLanguages
    val bioLinks = userProfileVO.bioLinks
    val width = 32.dp
    val rowSpaced = 6.dp
    if (speakLanguages.isNotEmpty() && bioLinks.isNotEmpty()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(rowSpaced),
        ) {
            // speakLanguages 和 bioLinks 最大大小为3，填充下让分割线居中
            repeat(3 - speakLanguages.size) {
                Spacer(modifier = Modifier.width((width)))
            }
            // speakLanguages
            LanguagesRow(speakLanguages, width)
            VerticalDivider(
                modifier = Modifier.height(width).padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.primary,
                thickness = 1.dp,
            )
            // bioLinks
            LinksRow(bioLinks, width)
            repeat(3 - bioLinks.size) {
                Spacer(modifier = Modifier.width((width)))
            }
        }
    } else if (speakLanguages.isNotEmpty()) {
        LanguagesRow(speakLanguages, width)
    } else if (bioLinks.isNotEmpty()) {
        LinksRow(bioLinks, width)
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LanguagesRow(
    speakLanguages: List<String>,
    width: Dp = 32.dp,
) {
    if (speakLanguages.isEmpty()) {
        return
    }
    Row(
        modifier = Modifier.height(width),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        speakLanguages.forEach { language ->
            val imageVector = LanguageIcons.getFlag(language)
            ATooltipBox(
                tooltip = { Text(text = language) }
            ) {
                if (imageVector == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(width)
                            .padding(vertical = 3.dp)
                            .background(MaterialTheme.colorScheme.inversePrimary, MaterialTheme.shapes.extraSmall)
                    ) {
                        Icon(
                            modifier = Modifier.align(Alignment.Center),
                            imageVector = AppIcons.QuestionMark,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            contentDescription = "NotKnownLanguageIcon",
                        )
                    }
                } else {
                    Image(
                        imageVector = imageVector,
                        contentDescription = "LanguageIcon",
                        modifier = Modifier
                            .fillMaxHeight()
                            .align(Alignment.CenterVertically)
                            .clip(MaterialTheme.shapes.extraSmall)
                            .width(width),
                        contentScale = ContentScale.FillWidth
                    )
                }
            }
        }
    }
}

@Composable
private fun UserNoteSection(
    note: String,
    onSaveNote: (String) -> Unit,
) {
    var isEditing by remember { mutableStateOf(false) }
    var editText by remember(note) { mutableStateOf(note) }

    SectionCard(
        title = strings.userTabNote,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (isEditing) {
            OutlinedTextField(
                value = editText,
                onValueChange = { editText = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium,
                minLines = 2,
                maxLines = 5,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = {
                    editText = note
                    isEditing = false
                }) {
                    Text(strings.cancel)
                }
                TextButton(onClick = {
                    onSaveNote(editText)
                    isEditing = false
                }) {
                    Text(strings.confirm)
                }
            }
        } else {
            Text(
                text = note.ifEmpty { strings.userTabNoteHint },
                style = MaterialTheme.typography.bodyMedium,
                color = if (note.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth().clickable { isEditing = true },
            )
        }
    }
}

@Composable
private fun GroupListSection(
    title: String,
    groups: List<LimitedUserGroup>,
    navigator: Navigator,
) {
    SectionCard(
        title = title,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            groups.forEach { group ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.large)
                        .clickable {
                            navigator push GroupProfileScreen(
                                groupProfileVo = GroupProfileVo(
                                    groupId = group.groupId,
                                    name = group.name,
                                    shortCode = group.shortCode,
                                    iconUrl = group.iconUrl,
                                    bannerUrl = group.bannerUrl,
                                    memberCount = group.memberCount,
                                ),
                            )
                        },
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        GroupIcon(
                            iconUrl = group.iconUrl,
                            size = 48.dp,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = group.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (group.shortCode.isNotBlank()) {
                                Text(
                                    text = "#${group.shortCode}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                            Text(
                                text = "${group.memberCount} ${strings.groupMembers}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinksRow(
    bioLinks: List<String>,
    width: Dp = 32.dp,
) {
    if (bioLinks.isEmpty()) {
        return
    }
    Row(
        modifier = Modifier.height(width),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val appPlatform = getAppPlatform()
        bioLinks.forEach { link ->
            val webIconVector = WebIcons.selectIcon(link)
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = {
                    PlainTooltip {
                        Text(text = link)
                    }
                },
                state = rememberTooltipState()
            ) {
                FilledIconButton(
                    modifier = Modifier.size(width),
                    onClick = { appPlatform.openUrl(link) },
                ) {
                    Icon(
                        modifier = Modifier
                            .padding(6.dp)
                            .enableIf(webIconVector == null) { rotate(-45F) },
                        imageVector = webIconVector ?: AppIcons.Link,
                        contentDescription = "BioLinkIcon"
                    )
                }
            }
        }
    }
}
