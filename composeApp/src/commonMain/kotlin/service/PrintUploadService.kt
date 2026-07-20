package io.github.vrcmteam.vrcm.service

import io.github.vrcmteam.vrcm.network.api.prints.PrintsApi
import io.github.vrcmteam.vrcm.network.api.prints.data.PrintData

interface PrintUploader {
    suspend fun upload(imageBytes: ByteArray, fileName: String): Result<PrintData>
}

class PrintUploadService(
    private val authService: AuthService,
    private val printsApi: PrintsApi,
) : PrintUploader {
    override suspend fun upload(imageBytes: ByteArray, fileName: String): Result<PrintData> =
        authService.reTryAuthCatching {
            printsApi.uploadPrint(imageBytes, fileName)
        }
}
