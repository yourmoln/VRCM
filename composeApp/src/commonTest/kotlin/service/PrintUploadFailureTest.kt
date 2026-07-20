package io.github.vrcmteam.vrcm.service

import io.github.vrcmteam.vrcm.network.supports.VRCApiException
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertIs

class PrintUploadFailureTest {
    @Test
    fun apiStatusCodesMapToActionableFailures() {
        assertIs<PrintUploadFailure.Authentication>(apiFailure(401).toPrintUploadFailure())
        assertIs<PrintUploadFailure.Permission>(apiFailure(403).toPrintUploadFailure())
        assertIs<PrintUploadFailure.Server>(apiFailure(503).toPrintUploadFailure())
    }

    @Test
    fun ioAndUnexpectedFailuresRemainTyped() {
        assertIs<PrintUploadFailure.Network>(IOException("offline").toPrintUploadFailure())
        assertIs<PrintUploadFailure.Unknown>(IllegalStateException("unexpected").toPrintUploadFailure())
    }

    private fun apiFailure(status: Int) = VRCApiException(
        description = "status-$status",
        code = status,
        bodyText = "{\"error\":\"sensitive server response\"}",
    )
}
