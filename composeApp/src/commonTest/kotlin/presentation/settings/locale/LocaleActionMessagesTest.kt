package io.github.vrcmteam.vrcm.presentation.settings.locale

import kotlin.test.Test
import kotlin.test.assertTrue

class LocaleActionMessagesTest {
    @Test
    fun printBoopAndInviteMessagesArePresentInEveryLocale() {
        val locales = listOf(
            LocaleStringsEn,
            LocaleStringsJa,
            LocaleStringsZhHans,
            LocaleStringsZhHant,
        )

        locales.forEach { locale ->
            val messages = listOf(
                locale.galleryPrintUploading,
                locale.galleryPrintUploaded,
                locale.galleryPrintUploadFailed,
                locale.recentWorldsRetry,
                locale.profileBoopSuccess,
                locale.profileInviteSent,
                locale.profileInviteNotInInstance,
            )
            assertTrue(messages.all { it.isNotBlank() })
        }
    }
}
