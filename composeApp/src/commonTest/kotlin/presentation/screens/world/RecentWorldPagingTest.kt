package io.github.vrcmteam.vrcm.presentation.screens.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecentWorldPagingTest {
    @Test
    fun fullPageAdvancesOffsetAndDeduplicatesItems() {
        val current = RecentWorldPagingState(
            items = listOf("wrld_1", "wrld_2"),
            nextOffset = 2,
        )

        val result = appendRecentWorldPage(
            current = current,
            page = listOf("wrld_2", "wrld_3"),
            pageSize = 2,
            keySelector = { it },
        )

        assertEquals(listOf("wrld_1", "wrld_2", "wrld_3"), result.items)
        assertEquals(4, result.nextOffset)
        assertFalse(result.endReached)
    }

    @Test
    fun shortPageMarksTheEnd() {
        val result = appendRecentWorldPage(
            current = RecentWorldPagingState<String>(),
            page = listOf("wrld_1"),
            pageSize = 2,
            keySelector = { it },
        )

        assertEquals(1, result.nextOffset)
        assertTrue(result.endReached)
    }

    @Test
    fun loadMoreOnlyTriggersNearTheEndOfANonEmptyList() {
        assertFalse(shouldLoadNextRecentWorldPage(lastVisibleIndex = -1, totalItemsCount = 0))
        assertFalse(shouldLoadNextRecentWorldPage(lastVisibleIndex = 3, totalItemsCount = 10))
        assertTrue(shouldLoadNextRecentWorldPage(lastVisibleIndex = 5, totalItemsCount = 10))
    }

    @Test
    fun failedOffsetCannotAutoLoadAgainUntilExplicitRetry() {
        val failed = markRecentWorldPageFailed(
            RecentWorldPagingState<String>(nextOffset = 50)
        )

        assertEquals(50, failed.nextOffset)
        assertFalse(failed.canAutoLoadNextPage())

        val retrying = prepareRecentWorldPageRetry(failed)

        assertEquals(50, retrying.nextOffset)
        assertTrue(retrying.canAutoLoadNextPage())
    }
}
