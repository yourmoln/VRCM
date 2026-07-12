package io.github.vrcmteam.vrcm.network.api.github

actual fun githubAuthToken(): String? =
    System.getenv("GITHUB_TOKEN")
        ?: System.getenv("GH_TOKEN")
