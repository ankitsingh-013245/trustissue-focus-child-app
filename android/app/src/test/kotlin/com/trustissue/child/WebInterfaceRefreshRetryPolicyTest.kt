package com.trustissue.child

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebInterfaceRefreshRetryPolicyTest {
    @Test
    fun `interface refresh retry uses capped exponential backoff`() {
        assertEquals(1_000L, WebInterfaceRefreshRetryPolicy.retryDelayMs(1))
        assertEquals(2_000L, WebInterfaceRefreshRetryPolicy.retryDelayMs(2))
        assertEquals(16_000L, WebInterfaceRefreshRetryPolicy.retryDelayMs(5))
        assertEquals(30_000L, WebInterfaceRefreshRetryPolicy.retryDelayMs(20))
    }

    @Test
    fun `transient empty browser discovery has a bounded retry window`() {
        assertTrue(
            WebInterfaceRefreshRetryPolicy.shouldRetryEmptyBrowserSet(1)
        )
        assertFalse(
            WebInterfaceRefreshRetryPolicy.shouldRetryEmptyBrowserSet(5)
        )
    }
}
