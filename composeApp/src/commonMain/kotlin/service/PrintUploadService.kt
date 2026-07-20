package io.github.vrcmteam.vrcm.service

import io.github.vrcmteam.vrcm.network.api.prints.PrintsApi
import io.github.vrcmteam.vrcm.network.api.prints.data.PrintData
import io.github.vrcmteam.vrcm.network.supports.VRCApiException
import kotlinx.io.IOException

interface PrintUploader {
    suspend fun upload(imageBytes: ByteArray, fileName: String): Result<PrintData>
}

sealed class PrintUploadFailure(
    message: String,
    cause: Throwable,
) : Exception(message, cause) {
    class Authentication(cause: Throwable) : PrintUploadFailure("Authentication required", cause)
    class Permission(cause: Throwable) : PrintUploadFailure("Print upload permission denied", cause)
    class Network(cause: Throwable) : PrintUploadFailure("Network error", cause)
    class Server(val statusCode: Int, cause: Throwable) :
        PrintUploadFailure("VRChat Print service error", cause)

    class Unknown(cause: Throwable) : PrintUploadFailure("Print upload failed", cause)
}

class PrintUploadService(
    private val authService: AuthService,
    private val printsApi: PrintsApi,
) : PrintUploader {
    override suspend fun upload(imageBytes: ByteArray, fileName: String): Result<PrintData> =
        authService.reTryAuthCatching {
            printsApi.uploadPrint(imageBytes, fileName)
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(it.toPrintUploadFailure()) },
        )
}

internal fun Throwable.toPrintUploadFailure(): PrintUploadFailure = when (this) {
    is PrintUploadFailure -> this
    is VRCApiException -> when {
        code == 401 -> PrintUploadFailure.Authentication(this)
        code == 403 -> PrintUploadFailure.Permission(this)
        code >= 500 -> PrintUploadFailure.Server(code, this)
        else -> PrintUploadFailure.Unknown(this)
    }
    is IOException -> PrintUploadFailure.Network(this)
    else -> PrintUploadFailure.Unknown(this)
}
