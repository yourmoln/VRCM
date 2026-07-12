package io.github.vrcmteam.vrcm.presentation.screens.group

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import io.github.vrcmteam.vrcm.core.shared.SharedFlowCentre
import io.github.vrcmteam.vrcm.network.api.groups.GroupsApi
import io.github.vrcmteam.vrcm.network.api.groups.data.GroupGalleryImage
import io.github.vrcmteam.vrcm.network.api.groups.data.GroupMember
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

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isActionLoading = MutableStateFlow(false)
    val isActionLoading: StateFlow<Boolean> = _isActionLoading.asStateFlow()

    fun refreshGroupData(groupProfileVo: GroupProfileVo) {
        _groupProfileState.value = groupProfileVo
        _members.value = emptyList()
        _owner.value = null
        _galleryImages.value = emptyMap()
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
