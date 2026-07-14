package io.github.vrcmteam.vrcm.presentation.screens.user

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.*
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
import io.github.vrcmteam.vrcm.presentation.screens.user.FriendNetworkScreen
import io.github.vrcmteam.vrcm.presentation.screens.user.MutualFriendsScreen
import io.github.vrcmteam.vrcm.presentation.screens.world.WorldProfileScreen
import io.github.vrcmteam.vrcm.presentation.screens.world.components.FavoriteGroupBottomSheet
import io.github.vrcmteam.vrcm.presentation.screens.world.data.WorldProfileVo
import io.github.vrcmteam.vrcm.presentation.settings.locale.strings
import io.github.vrcmteam.vrcm.presentation.supports.AppIcons
import io.github.vrcmteam.vrcm.presentation.supports.LanguageIcons
import io.github.vrcmteam.vrcm.presentation.supports.WebIcons
import io.github.vrcmteam.vrcm.network.api.users.data.LimitedUserGroup
import io.github.vrcmteam.vrcm.network.api.worlds.data.WorldData
import io.github.vrcmteam.vrcm.network.api.worlds.data.FavoritedWorld
import io.github.vrcmteam.vrcm.network.api.avatars.data.AvatarData
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
        var bottomSheetIsVisible by remember { mutableStateOf(false) }
        val sheetState = rememberModalBottomSheetState()
        var openAlertDialog by remember { mutableStateOf(false) }
        var openEditProfileDialog by remember { mutableStateOf(false) }
        var openEditNoteDialog by remember { mutableStateOf(false) }
        // Control showing favorite group management for Friend type
        var showFriendFavoriteSheet by remember { mutableStateOf(false) }
        CompositionLocalProvider(LocalSharedSuffixKey provides sharedSuffixKey) {
            ProfileScaffold(
                imageModifier = Modifier.sharedBoundsBy("${userProfileVO.id}UserIcon"),
                profileImageUrl = currentUser.profileImageUrl,
                iconUrl = currentUser.iconUrl,
                onReturn = { currentNavigator.pop() },
                onMenu = { bottomSheetIsVisible = true },
            ) { ratio, contentMinHeight ->
                ProfileContent(
                    currentUser = currentUser,
                    friendLocation = userProfileScreenModel.friendLocation,
                    userGroups = userGroups,
                    createdWorlds = userProfileScreenModel.createdWorlds,
                    createdAvatars = userProfileScreenModel.createdAvatars,
                    favoritedWorlds = userProfileScreenModel.favoritedWorlds,
                    contentMinHeight = contentMinHeight,
                    onLoadWorlds = { userProfileScreenModel.loadCreatedWorlds(userProfileVO.id) },
                    onLoadAvatars = { userProfileScreenModel.loadCreatedAvatars() },
                    onLoadFavoritedWorlds = { userProfileScreenModel.loadFavoritedWorlds(userProfileVO.id) },
                    ratio = ratio,
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
                openEditProfileDialog = { openEditProfileDialog = true },
                onManageFriendFavorite = { showFriendFavoriteSheet = true },
                openEditNoteDialog = { openEditNoteDialog = true }
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
        // 编辑资料底部弹窗
        val editSuccessMsg = strings.editProfileUpdateSuccess
        EditProfileSheet(
            isVisible = openEditProfileDialog,
            currentUser = currentUser,
            onDismiss = { openEditProfileDialog = false },
            onStatusSave = { status, statusDescription ->
                userProfileScreenModel.updateUserProfile(status = status, statusDescription = statusDescription, successMessage = editSuccessMsg)
            },
            onLanguageSave = { languages ->
                userProfileScreenModel.updateUserProfile(languages = languages, successMessage = editSuccessMsg)
            },
            onPronounsSave = { pronouns ->
                userProfileScreenModel.updateUserProfile(pronouns = pronouns, successMessage = editSuccessMsg)
            },
            onBioSave = { bio ->
                userProfileScreenModel.updateUserProfile(bio = bio, successMessage = editSuccessMsg)
            },
        )
        // 编辑备注弹窗
        val noteSavedMsg = strings.userNoteSaved
        EditNoteDialog(
            isVisible = openEditNoteDialog,
            initialNote = currentUser.note,
            onDismiss = { openEditNoteDialog = false },
            onSave = { note ->
                userProfileScreenModel.saveUserNote(note, noteSavedMsg)
                openEditNoteDialog = false
            }
        )
    }

}

@Composable
private fun ColumnScope.SheetItems(
    currentUser: UserProfileVo,
    userProfileScreenModel: UserProfileScreenModel,
    hideSheet: suspend () -> Unit,
    onHideCompletion: () -> Unit,
    openAlertDialog: () -> Unit,
    openEditProfileDialog: () -> Unit,
    onManageFriendFavorite: () -> Unit,
    openEditNoteDialog: () -> Unit,
) {
    val navigator = LocalNavigator.currentOrThrow
    val localeStrings = strings
    val scope = rememberCoroutineScope()
    // 只有当是自己的个人资料时才显示
    if (currentUser.isSelf) {

        SheetButtonItem(text = localeStrings.profileEditProfile, onClick = {
            scope.launch { hideSheet() }.invokeOnCompletion {
                onHideCompletion()
                openEditProfileDialog()
            }
        })

        SheetButtonItem(text = localeStrings.profileViewGallery, onClick = {
            scope.launch { hideSheet() }.invokeOnCompletion {
                onHideCompletion()
                navigator.push(GalleryScreen)
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
        SheetButtonItem(text = localeStrings.userNoteEditTitle, onClick = {
            scope.launch { hideSheet() }.invokeOnCompletion {
                onHideCompletion()
                openEditNoteDialog()
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
private fun ColumnScope.ProfileContent(
    currentUser: UserProfileVo?,
    friendLocation: FriendLocation?,
    userGroups: List<LimitedUserGroup>,
    createdWorlds: List<WorldData>,
    createdAvatars: List<AvatarData>,
    favoritedWorlds: List<Pair<String, List<FavoritedWorld>>>,
    contentMinHeight: Dp,
    onLoadWorlds: () -> Unit,
    onLoadAvatars: () -> Unit,
    onLoadFavoritedWorlds: () -> Unit,
    ratio: Float,
) {
    if (currentUser == null) return
    val sharedSuffixKey = LocalSharedSuffixKey.current
    val navigator = currentNavigator

    // 加载创建的世界和模型
    LaunchedEffect(currentUser.id, currentUser.isSelf) {
        onLoadWorlds()
        onLoadFavoritedWorlds()
        if (currentUser.isSelf) {
            onLoadAvatars()
        }
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

    UserGroupsSection(
        groups = userGroups,
        onGroupClick = { group ->
            navigator push GroupProfileScreen(
                groupProfileVo = GroupProfileVo(
                    groupId = group.groupId,
                    name = group.name,
                    shortCode = group.shortCode,
                    iconUrl = group.iconUrl,
                    bannerUrl = group.bannerUrl,
                    memberCount = group.memberCount,
                ),
                sharedSuffixKey = sharedSuffixKey
            )
        }
    )

    // 个人简介
    BottomCardTab(
        bioMinHeight = contentMinHeight,
        userProfileVO = currentUser
    )

    // 创建的世界（在个人简介下方）
    UserCreatedWorldsSection(
        worlds = createdWorlds,
        onWorldClick = { world ->
            navigator push WorldProfileScreen(
                worldProfileVO = WorldProfileVo(world),
                sharedSuffixKey = sharedSuffixKey
            )
        }
    )

    // 创建的模型（仅自己可见，在个人简介下方）
    if (currentUser.isSelf) {
        UserCreatedAvatarsSection(
            avatars = createdAvatars,
        )
    }

    // 收藏的世界（在创建的模型下方）
    UserFavoritedWorldsSection(
        groupedWorlds = favoritedWorlds,
        onWorldClick = { world ->
            navigator push WorldProfileScreen(
                worldProfileVO = WorldProfileVo(worldId = world.id, worldName = world.name, worldImageUrl = world.imageUrl, thumbnailImageUrl = world.thumbnailImageUrl, authorName = world.authorName),
                sharedSuffixKey = sharedSuffixKey
            )
        }
    )
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun UserGroupsSection(
    groups: List<LimitedUserGroup>,
    onGroupClick: (LimitedUserGroup) -> Unit,
) {
    if (groups.isEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = strings.groups,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(groups, key = { it.groupId }) { group ->
                Surface(
                    modifier = Modifier
                        .width(180.dp)
                        .height(88.dp)
                        .clip(MaterialTheme.shapes.large)
                        .clickable { onGroupClick(group) },
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            GroupIcon(
                                iconUrl = group.iconUrl,
                                size = 36.dp,
                                modifier = Modifier.sharedBoundsBy("${group.groupId}GroupIcon")
                            )
                            Column(
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    modifier = Modifier.sharedBoundsBy("${group.groupId}GroupName"),
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
                            }
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

/**
 * 区域标题（标题 + 可选计数）
 */
@Composable
private fun SectionHeader(
    title: String,
    countText: String? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        if (countText != null) {
            Text(
                text = countText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 通用横向卡片列表（180×88，左侧图片 + 右侧标题/副标题）
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun <T> HorizontalCardRow(
    items: List<T>,
    key: (T) -> Any,
    imageUrl: (T) -> String?,
    title: (T) -> String,
    subtitle: (T) -> String,
    imageModifier: @Composable (T, Modifier) -> Modifier = { _, m -> m },
    onClick: ((T) -> Unit)? = null,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(items, key = key) { item ->
            Surface(
                modifier = Modifier
                    .width(180.dp)
                    .height(88.dp)
                    .clip(MaterialTheme.shapes.large)
                    .then(if (onClick != null) Modifier.clickable { onClick(item) } else Modifier),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AImage(
                        modifier = imageModifier(
                            item,
                            Modifier
                                .size(48.dp)
                                .clip(MaterialTheme.shapes.medium)
                        ),
                        imageData = imageUrl(item),
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = title(item),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = subtitle(item),
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

/**
 * 用户创建的世界列表组件
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun UserCreatedWorldsSection(
    worlds: List<WorldData>,
    onWorldClick: (WorldData) -> Unit,
) {
    if (worlds.isEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SectionHeader(
            title = strings.userCreatedWorlds,
            countText = strings.userCreatedWorldsCount.replace("%d", "${worlds.size}")
        )
        HorizontalCardRow(
            items = worlds,
            key = { it.id },
            imageUrl = { it.thumbnailImageUrl ?: it.imageUrl },
            title = { it.name },
            subtitle = { "${it.publicOccupants ?: 0} 👤" },
            imageModifier = { item, modifier -> modifier.sharedBoundsBy("${item.id}WorldImage") },
            onClick = onWorldClick,
        )
    }
}

/**
 * 用户创建的模型列表组件
 */
@Composable
private fun UserCreatedAvatarsSection(
    avatars: List<AvatarData>,
) {
    if (avatars.isEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SectionHeader(
            title = strings.userCreatedAvatars,
            countText = strings.userCreatedAvatarsCount.replace("%d", "${avatars.size}")
        )
        HorizontalCardRow(
            items = avatars,
            key = { it.id },
            imageUrl = { it.thumbnailImageUrl ?: it.imageUrl },
            title = { it.name },
            subtitle = { it.authorName },
        )
    }
}

/**
 * 用户收藏的世界列表组件（按分组显示）
 */
@Composable
private fun UserFavoritedWorldsSection(
    groupedWorlds: List<Pair<String, List<FavoritedWorld>>>,
    onWorldClick: (FavoritedWorld) -> Unit,
) {
    if (groupedWorlds.isEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionHeader(title = strings.userFavoritedWorlds)
        for ((groupName, worlds) in groupedWorlds) {
            if (worlds.isEmpty()) continue
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = groupName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalCardRow(
                    items = worlds,
                    key = { it.id },
                    imageUrl = { it.thumbnailImageUrl ?: it.imageUrl },
                    title = { it.name },
                    subtitle = { "${it.occupants ?: 0} 👤" },
                    onClick = onWorldClick,
                )
            }
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
@OptIn(ExperimentalMaterial3Api::class)
private fun BottomCardTab(
    bioMinHeight: Dp = 0.dp,
    userProfileVO: UserProfileVo,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        var state by remember { mutableStateOf(0) }
        AnimatedContent(targetState = state) {
            when (it) {
                0 -> {
                    Surface(
                        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = bioMinHeight),
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        contentColor = MaterialTheme.colorScheme.primary,
                        shape = MaterialTheme.shapes.extraLarge
                    ) {
                        SelectionContainer {
                            Text(
                                modifier = Modifier.padding(12.dp),
                                text = userProfileVO.bio
                            )
                        }
                    }
                }

                else -> {
                    // TODO: 未来实现 Worlds/Groups 标签页
                }
            }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditNoteDialog(
    isVisible: Boolean,
    initialNote: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    if (!isVisible) return
    val localeStrings = strings
    var noteText by remember { mutableStateOf(initialNote) }
    val maxLen = 256

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            EditHeader(localeStrings.userNoteEditTitle, onDismiss)
            OutlinedTextField(
                value = noteText,
                onValueChange = { if (it.length <= maxLen) noteText = it },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 8,
                placeholder = {
                    Text(
                        localeStrings.userNoteEditTitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                },
                supportingText = { Text("${noteText.length}/$maxLen") },
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onSave(noteText) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(localeStrings.editProfileSave)
            }
        }
    }
}
