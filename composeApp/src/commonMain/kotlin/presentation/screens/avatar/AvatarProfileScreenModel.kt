package io.github.vrcmteam.vrcm.presentation.screens.avatar

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import io.github.vrcmteam.vrcm.core.shared.SharedFlowCentre
import io.github.vrcmteam.vrcm.network.api.avatars.AvatarsApi
import io.github.vrcmteam.vrcm.presentation.compoments.ToastText
import io.github.vrcmteam.vrcm.presentation.screens.avatar.data.AvatarProfileVo
import io.github.vrcmteam.vrcm.service.AuthService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AvatarProfileScreenModel(
    private val avatarsApi: AvatarsApi,
    private val authService: AuthService,
) : ScreenModel {

    private val _avatarProfileState = MutableStateFlow<AvatarProfileVo?>(null)
    val avatarProfileState: StateFlow<AvatarProfileVo?> = _avatarProfileState.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun refreshAvatarData(avatarProfileVo: AvatarProfileVo) {
        _avatarProfileState.value = avatarProfileVo
        val avatarId = avatarProfileVo.avatarId
        if (_isLoading.value || avatarId.isBlank()) return
        _isLoading.value = true
        screenModelScope.launch(Dispatchers.IO) {
            authService.reTryAuthCatching {
                avatarsApi.getAvatarById(avatarId)
            }.onSuccess { avatarData ->
                _avatarProfileState.value = AvatarProfileVo(avatarData)
            }.onFailure {
                SharedFlowCentre.toastText.emit(
                    ToastText.Error(it.message ?: "Failed to load avatar data")
                )
            }
            _isLoading.value = false
        }
    }
}
