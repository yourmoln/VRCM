package io.github.vrcmteam.vrcm.network.api.prints

import io.github.vrcmteam.vrcm.network.api.attributes.PRINTS_API_PREFIX
import io.github.vrcmteam.vrcm.network.api.files.data.PrintData
import io.github.vrcmteam.vrcm.network.extensions.checkSuccess
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class PrintsApi(private val client: HttpClient) {

    suspend fun getUserPrints(userId: String): List<PrintData> =
        client.get("$PRINTS_API_PREFIX/user/$userId").checkSuccess()

    suspend fun getPrint(printId: String): PrintData =
        client.get("$PRINTS_API_PREFIX/$printId").checkSuccess()

    suspend fun uploadPrint(
        imageBytes: ByteArray,
        fileName: String,
        note: String = "",
        worldId: String? = null,
        worldName: String? = null,
    ): PrintData =
        client.submitFormWithBinaryData(
            url = PRINTS_API_PREFIX,
            formData = formData {
                append("image", imageBytes, Headers.build {
                    append(HttpHeaders.ContentType, imageMimeType(fileName))
                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                })
                append("note", note)
                append("timestamp", Clock.System.now().toString())
                worldId?.let { append("worldId", it) }
                worldName?.let { append("worldName", it) }
            }
        ).checkSuccess()

    suspend fun editPrint(
        printId: String,
        imageBytes: ByteArray? = null,
        fileName: String? = null,
        note: String? = null,
    ): PrintData =
        client.submitFormWithBinaryData(
            url = "$PRINTS_API_PREFIX/$printId",
            formData = formData {
                if (imageBytes != null && fileName != null) {
                    append("image", imageBytes, Headers.build {
                        append(HttpHeaders.ContentType, imageMimeType(fileName))
                        append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                    })
                }
                note?.let { append("note", it) }
            }
        ).checkSuccess()

    suspend fun deletePrint(printId: String) =
        client.delete("$PRINTS_API_PREFIX/$printId").checkSuccess<Unit>()

    private fun imageMimeType(fileName: String): String = when {
        fileName.endsWith(".jpg", ignoreCase = true) ||
                fileName.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
        fileName.endsWith(".gif", ignoreCase = true) -> "image/gif"
        fileName.endsWith(".bmp", ignoreCase = true) -> "image/bmp"
        else -> "image/png"
    }
}
