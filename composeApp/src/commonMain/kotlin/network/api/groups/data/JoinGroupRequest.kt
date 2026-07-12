package io.github.vrcmteam.vrcm.network.api.groups.data

import kotlinx.serialization.Serializable

@Serializable
data class JoinGroupRequest(
    val inviteId: String? = null,
)
