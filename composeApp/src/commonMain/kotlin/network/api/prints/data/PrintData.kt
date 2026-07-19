package io.github.vrcmteam.vrcm.network.api.prints.data

import kotlinx.serialization.Serializable

@Serializable
data class PrintData(
    val id: String = "",
    val authorId: String = "",
    val authorName: String = "",
    val ownerId: String = "",
    val note: String = "",
    val worldId: String = "",
    val worldName: String = "",
    val timestamp: String = "",
    val createdAt: String = "",
    val files: PrintFiles = PrintFiles(),
)

@Serializable
data class PrintFiles(
    val fileId: String = "",
    val image: String = "",
)
