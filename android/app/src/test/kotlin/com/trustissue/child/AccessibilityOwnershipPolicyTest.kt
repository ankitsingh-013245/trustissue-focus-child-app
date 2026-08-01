package com.trustissue.child

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityOwnershipPolicyTest {
    @Test
    fun `event heartbeat cannot claim ownership before initialization`() {
        assertFalse(
            AccessibilityOwnershipPolicy.isOperational(
                ready = false,
                heartbeatElapsedMs = 9_900L,
                nowElapsedMs = 10_000L,
                maxHeartbeatAgeMs = 10_000L
            )
        )
    }

    @Test
    fun `ready service with a fresh heartbeat owns enforcement`() {
        assertTrue(
            AccessibilityOwnershipPolicy.isOperational(
                ready = true,
                heartbeatElapsedMs = 9_900L,
                nowElapsedMs = 10_000L,
                maxHeartbeatAgeMs = 10_000L
            )
        )
    }

    @Test
    fun `stale or future heartbeat cannot own enforcement`() {
        assertFalse(
            AccessibilityOwnershipPolicy.isOperational(
                ready = true,
                heartbeatElapsedMs = 1L,
                nowElapsedMs = 10_002L,
                maxHeartbeatAgeMs = 10_000L
            )
        )
        assertFalse(
            AccessibilityOwnershipPolicy.isOperational(
                ready = true,
                heartbeatElapsedMs = 10_001L,
                nowElapsedMs = 10_000L,
                maxHeartbeatAgeMs = 10_000L
            )
        )
    }
}
