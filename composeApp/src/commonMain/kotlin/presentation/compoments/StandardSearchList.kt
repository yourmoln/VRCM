package io.github.vrcmteam.vrcm.presentation.compoments

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import io.github.vrcmteam.vrcm.network.api.attributes.IUser
import io.github.vrcmteam.vrcm.network.api.avatars.data.AvatarData
import io.github.vrcmteam.vrcm.network.api.groups.data.LimitedGroup
import io.github.vrcmteam.vrcm.network.api.worlds.data.WorldData
import io.github.vrcmteam.vrcm.presentation.extensions.currentNavigator
import io.github.vrcmteam.vrcm.presentation.screens.avatar.AvatarProfileScreen
import io.github.vrcmteam.vrcm.presentation.screens.avatar.data.AvatarProfileVo
import io.github.vrcmteam.vrcm.presentation.screens.group.GroupProfileScreen
import io.github.vrcmteam.vrcm.presentation.screens.group.data.GroupProfileVo
import io.github.vrcmteam.vrcm.presentation.screens.user.UserProfileScreen
import io.github.vrcmteam.vrcm.presentation.screens.user.data.UserProfileVo
import io.github.vrcmteam.vrcm.presentation.screens.world.WorldProfileScreen
import io.github.vrcmteam.vrcm.presentation.screens.world.data.WorldProfileVo
import io.github.vrcmteam.vrcm.presentation.settings.locale.strings
import kotlinx.coroutines.launch

/**
 * 标准搜索列表组件
 * 封装GenericSearchList，固定tabs为用户、世界、模型，可选显示群组
 */
@Composable
fun StandardSearchList(
    key: String,
    searchText: String,
    updateSearchText: (String) -> Unit,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    isRefreshing: Boolean? = null,
    doRefresh: (suspend () -> Unit)? = null,
    headerContent: @Composable () -> Unit = {},
    advancedOptionsContent: @Composable ((SearchTabType) -> Unit)? = null,
    userList: List<IUser> = emptyList(),
    worldList: List<WorldData> = emptyList(),
    avatarList: List<AvatarData> = emptyList(),
    // 目前群组功能可能还没实现，所以这里暂时留空
    modelContentBuilder: (LazyListScope.() -> Unit)? = null,
    groupContentBuilder: (LazyListScope.() -> Unit)? = null
) {
    // 固定的标签页列表
    val tabs = listOf(strings.users, strings.worlds, strings.avatars)
    val coroutineScope = rememberCoroutineScope()
    val currentNavigator = currentNavigator
    val sharedSuffixKey = LocalSharedSuffixKey.current
    val onUserClick = { user: IUser ->
        // 处理用户点击，导航到用户资料页面
        if (currentNavigator.size <= 1) {
            coroutineScope.launch {
                currentNavigator push UserProfileScreen(
                    userProfileVO = UserProfileVo(user),
                    sharedSuffixKey = sharedSuffixKey
                )
            }
        }
    }
    val onWorldClick = { world: WorldData ->
        // 处理世界点击，导航到世界详情页面
        if (currentNavigator.size <= 1) {
            coroutineScope.launch {
                currentNavigator push WorldProfileScreen(
                    worldProfileVO = WorldProfileVo(world),
                    sharedSuffixKey = sharedSuffixKey
                )
            }
        }
    }
    val onAvatarClick = { avatar: AvatarData ->
        // 处理模型点击，导航到模型详情页面
        if (currentNavigator.size <= 1) {
            coroutineScope.launch {
                currentNavigator push AvatarProfileScreen(
                    avatarProfileVo = AvatarProfileVo(avatar),
                    sharedSuffixKey = sharedSuffixKey
                )
            }
        }
    }

    // 将索引转换为对应的SearchTabType
    val selectedTabType = SearchTabType.fromIndex(selectedTabIndex)

    GenericSearchList(
        key = key,
        searchText = searchText,
        updateSearchText = updateSearchText,
        tabs = tabs,
        selectedTabIndex = selectedTabIndex,
        onTabSelected = onTabSelected,
        isRefreshing = isRefreshing,
        doRefresh = doRefresh,
        headerContent = headerContent,
        advancedOptionsContent = {
            // 使用枚举类型调用高级选项内容
            advancedOptionsContent?.invoke(selectedTabType)
        }
    ) { tabIndex ->
        when (tabIndex) {
            SearchTabType.USER.index -> { // 用户标签页
                renderUserItems(
                    users = userList,
                    onUserClick = onUserClick
                )
            }
            SearchTabType.WORLD.index -> { // 世界标签页
                renderWorldItems(
                    worlds = worldList,
                    onWorldClick = onWorldClick
                )
            }
            SearchTabType.AVATAR.index -> { // 模型标签页
                renderAvatarItems(
                    avatars = avatarList,
                    onAvatarClick = onAvatarClick
                )
            }
            SearchTabType.GROUP.index -> { // 群组标签页
                groupContentBuilder?.invoke(this)
            }
        }
    }
}
