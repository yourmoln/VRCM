package io.github.vrcmteam.vrcm.network.api.groups.data

import io.github.vrcmteam.vrcm.network.api.instances.data.InstanceData
import kotlinx.serialization.Serializable

@Serializable
data class GroupInstancesResponse(
    val instances: List<InstanceData> = emptyList(),
)
