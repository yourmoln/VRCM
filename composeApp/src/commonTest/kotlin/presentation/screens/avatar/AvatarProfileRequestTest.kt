package io.github.vrcmteam.vrcm.presentation.screens.avatar

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AvatarProfileRequestTest {
    @Test
    fun onlyTheLatestRequestCanUpdateProfileState() {
        assertFalse(isLatestAvatarRequest(requestToken = 1, latestRequestToken = 2))
        assertTrue(isLatestAvatarRequest(requestToken = 2, latestRequestToken = 2))
    }
}
