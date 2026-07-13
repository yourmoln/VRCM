package io.github.vrcmteam.vrcm.presentation.screens.group

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import io.github.vrcmteam.vrcm.core.shared.SharedFlowCentre
import io.github.vrcmteam.vrcm.network.api.groups.GroupsApi
import io.github.vrcmteam.vrcm.network.api.groups.data.GroupGalleryImage
import io.github.vrcmteam.vrcm.network.api.groups.data.GroupMember
import io.github.vrcmteam.vrcm.network.api.groups.data.GroupPost
import io.github.vrcmteam.vrcm.network.api.instances.data.InstanceData
import io.github.vrcmteam.vrcm.network.api.users.UsersApi
import io.github.vrcmteam.vrcm.network.api.users.data.UserData
import io.github.vrcmteam.vrcm.presentation.compoments.ToastText
import io.github.vrcmteam.vrcm.presentation.screens.group.data.GroupProfileVo
import io.github.vrcmteam.vrcm.service.AuthService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.core.logger.Logger

class GroupProfileScreenModel(
    private val groupsApi: GroupsApi,
    private val usersApi: UsersApi,
    private val authService: AuthService,
    private val logger: Logger,
) : ScreenModel {

    private val _groupProfileState = MutableStateFlow<GroupProfileVo?>(null)
    val groupProfileState: StateFlow<GroupProfileVo?> = _groupProfileState.asStateFlow()

    private val _members = MutableStateFlow<List<GroupMember>>(emptyList())
    val members: StateFlow<List<GroupMember>> = _members.asStateFlow()

    private val _owner = MutableStateFlow<UserData?>(null)
    val owner: StateFlow<UserData?> = _owner.asStateFlow()

    private val _galleryImages = MutableStateFlow<Map<String, List<GroupGalleryImage>>>(emptyMap())
    val galleryImages: StateFlow<Map<String, List<GroupGalleryImage>>> = _galleryImages.asStateFlow()

    private val _posts = MutableStateFlow<List<GroupPost>>(emptyList())
    val posts: StateFlow<List<GroupPost>> = _posts.asStateFlow()

    private val _postAuthors = MutableStateFlow<Map<String, String>>(emptyMap())
    val postAuthors: StateFlow<Map<String, String>> = _postAuthors.asStateFlow()

    private val _postsLoading = MutableStateFlow(false)
    val postsLoading: StateFlow<Boolean> = _postsLoading.asStateFlow()

    private val _groupInstances = MutableStateFlow<List<InstanceData>>(emptyList())
    val groupInstances: StateFlow<List<InstanceData>> = _groupInstances.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isActionLoading = MutableStateFlow(false)
    val isActionLoading: StateFlow<Boolean> = _isActionLoading.asStateFlow()

    fun refreshGroupData(groupProfileVo: GroupProfileVo) {
        _groupProfileState.value = groupProfileVo
        _members.value = emptyList()
        _owner.value = null
        _galleryImages.value = emptyMap()
        _posts.value = emptyList()
        _postAuthors.value = emptyMap()
        _postsLoading.value = false
        _groupInstances.value = emptyList()
        val groupId = groupProfileVo.groupId
        if (_isLoading.value || groupId.isBlank()) return
        _isLoading.value = true
        screenModelScope.launch(Dispatchers.IO) {
            authService.reTryAuthCatching {
                groupsApi.fetchGroup(groupId, includeRoles = true)
            }.onSuccess {
                _groupProfileState.value = GroupProfileVo(it)
            }.onFailure {
                handleError("GroupProfile", it)
            }
            val group = _groupProfileState.value
            if (group?.ownerId != null) {
                loadOwner(group.ownerId)
            }
            if (group != null) {
                loadMembers(groupId)
                loadPosts(groupId)
                loadGroupInstances(groupId)
                loadGalleryImages(groupId, group.galleries)
            }
            _isLoading.value = false
        }
    }

    fun joinGroup() {
        val groupId = _groupProfileState.value?.groupId ?: return
        if (_isActionLoading.value) return
        _isActionLoading.value = true
        screenModelScope.launch(Dispatchers.IO) {
            authService.reTryAuthCatching {
                groupsApi.joinGroup(groupId)
            }.onFailure {
                handleError("GroupJoin", it)
            }.onSuccess {
                _groupProfileState.value?.let { refreshGroupData(it) }
            }
            _isActionLoading.value = false
        }
    }

    fun leaveGroup() {
        val groupId = _groupProfileState.value?.groupId ?: return
        if (_isActionLoading.value) return
        _isActionLoading.value = true
        screenModelScope.launch(Dispatchers.IO) {
            authService.reTryAuthCatching {
                groupsApi.leaveGroup(groupId)
            }.onFailure {
                handleError("GroupLeave", it)
            }.onSuccess {
                _groupProfileState.value?.let { refreshGroupData(it) }
            }
            _isActionLoading.value = false
        }
    }

    private suspend fun loadMembers(groupId: String) {
        authService.reTryAuthCatching {
            groupsApi.getGroupMembers(groupId = groupId, n = 24, offset = 0)
        }.onSuccess {
            _members.value = it
        }.onFailure {
            logger.error(it.message.orEmpty())
        }
    }

    private suspend fun loadOwner(ownerId: String) {
        authService.reTryAuthCatching {
            usersApi.fetchUser(ownerId)
        }.onSuccess {
            _owner.value = it
        }.onFailure {
            logger.error(it.message.orEmpty())
        }
    }

    private suspend fun loadPosts(groupId: String) {
        _postsLoading.value = true
        authService.reTryAuthCatching {
            groupsApi.getGroupPosts(groupId = groupId, n = 100, offset = 0)
        }.onSuccess { postData ->
            _posts.value = postData.posts
            val authorIds = postData.posts.mapNotNull { it.authorId.takeIf { id -> id.isNotBlank() } }.distinct()
            val authorMap = mutableMapOf<String, String>()
            authorIds.forEach { userId ->
                authService.reTryAuthCatching {
                    usersApi.fetchUser(userId)
                }.onSuccess { user ->
                    authorMap[userId] = user.displayName
                }.onFailure {
                    logger.error(it.message.orEmpty())
                }
            }
            _postAuthors.value = authorMap
        }.onFailure {
            logger.error(it.message.orEmpty())
        }
        _postsLoading.value = false
    }

    private suspend fun loadGroupInstances(groupId: String) {
        val userId = authService.currentUser().id
        if (userId.isBlank()) return
        authService.reTryAuthCatching {
            groupsApi.getGroupInstances(userId = userId, groupId = groupId)
        }.onSuccess {
            _groupInstances.value = it.instances
        }.onFailure {
            logger.error(it.message.orEmpty())
        }
    }

    private suspend fun loadGalleryImages(groupId: String, galleries: List<io.github.vrcmteam.vrcm.network.api.groups.data.Gallery>) {
        if (galleries.isEmpty()) return
        val imagesMap = mutableMapOf<String, List<GroupGalleryImage>>()
        galleries.forEach { gallery ->
            authService.reTryAuthCatching {
                groupsApi.getGroupGalleryImages(groupId = groupId, groupGalleryId = gallery.id, n = 30, offset = 0)
            }.onSuccess {
                imagesMap[gallery.id] = it
            }.onFailure {
                logger.error(it.message.orEmpty())
            }
        }
        _galleryImages.value = imagesMap
    }

    private suspend fun handleError(tag: String, error: Throwable) {
        logger.error("$tag: ${error.message}")
        SharedFlowCentre.toastText.emit(ToastText.Error(error.message ?: "Unknown error"))
    }
}
