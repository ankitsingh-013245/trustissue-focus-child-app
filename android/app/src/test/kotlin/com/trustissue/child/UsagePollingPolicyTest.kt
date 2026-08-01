package com.trustissue.child

import org.junit.Assert.assertEquals
import org.junit.Test

class UsagePollingPolicyTest {
    @Test
    fun `foreground transitions use fast polling`() {
        assertEquals(
            UsagePollingPolicy.fastPollIntervalMs,
            UsagePollingPolicy.nextDelay(
                foregroundChanged = true,
                hadLifecycleEvents = false,
                unchangedPollCount = 0,
                hasDailyLimits = false
            )
        )
    }

    @Test
    fun `recent lifecycle activity keeps polling fast`() {
        assertEquals(
            UsagePollingPolicy.fastPollIntervalMs,
            UsagePollingPolicy.nextDelay(
                foregroundChanged = false,
                hadLifecycleEvents = true,
                unchangedPollCount = 0,
                hasDailyLimits = false
            )
        )
    }

    @Test
    fun `stable foreground backs off without delaying daily limit checks too far`() {
        assertEquals(
            UsagePollingPolicy.stablePollIntervalMs,
            UsagePollingPolicy.nextDelay(
                foregroundChanged = false,
                hadLifecycleEvents = false,
                unchangedPollCount = 10,
                hasDailyLimits = false
            )
        )
        assertEquals(
            UsagePollingPolicy.dailyLimitPollIntervalMs,
            UsagePollingPolicy.nextDelay(
                foregroundChanged = false,
                hadLifecycleEvents = false,
                unchangedPollCount = 10,
                hasDailyLimits = true
            )
        )
    }
}
