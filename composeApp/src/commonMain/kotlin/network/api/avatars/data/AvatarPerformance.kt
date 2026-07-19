package io.github.vrcmteam.vrcm.network.api.avatars.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AvatarPerformance(
    val android: String? = null,
    @SerialName("android-sort")
    val androidSort: Int? = null,
    val ios: String? = null,
    @SerialName("ios-sort")
    val iosSort: Int? = null,
    val standalonewindows: String? = null,
    @SerialName("standalonewindows-sort")
    val standalonewindowsSort: Int? = null,
)
