package io.github.vrcmteam.vrcm.network.api.github

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
actual fun githubAuthToken(): String? =
    getenv("GITHUB_TOKEN")?.toKString()
        ?: getenv("GH_TOKEN")?.toKString()
