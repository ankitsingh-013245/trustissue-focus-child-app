package com.trustissue.child

import android.app.usage.UsageEvents
import org.junit.Assert.assertEquals
import org.junit.Test

class FocusUsageEventPolicyTest {
    @Test
    fun `no new event retains the foreground app`() {
        assertEquals(
            "com.example.video",
            FocusUsageEventPolicy.resolve(
                stablePackage = "com.example.video",
                events = emptyList()
            )
        )
    }

    @Test
    fun `pause alone retains app behind a translucent window`() {
        assertEquals(
            "com.example.reader",
            FocusUsageEventPolicy.resolve(
                stablePackage = "com.example.reader",
                events = listOf(
                    FocusUsageLifecycleEvent(
                        "com.example.reader",
                        UsageEvents.Event.ACTIVITY_PAUSED
                    )
                )
            )
        )
    }

    @Test
    fun `stop retains the current foreground app`() {
        assertEquals(
            "com.example.video",
            FocusUsageEventPolicy.resolve(
                stablePackage = "com.example.video",
                events = listOf(
                    FocusUsageLifecycleEvent(
                        "com.example.video",
                        UsageEvents.Event.ACTIVITY_STOPPED
                    )
                )
            )
        )
    }

    @Test
    fun `another package resume after stop selects the new app`() {
        assertEquals(
            "com.example.notes",
            FocusUsageEventPolicy.resolve(
                stablePackage = "com.example.video",
                events = listOf(
                    FocusUsageLifecycleEvent(
                        "com.example.video",
                        UsageEvents.Event.ACTIVITY_STOPPED
                    ),
                    FocusUsageLifecycleEvent(
                        "com.example.notes",
                        UsageEvents.Event.ACTIVITY_RESUMED
                    )
                )
            )
        )
    }

    @Test
    fun `same package activity stop after resume does not clear foreground`() {
        assertEquals(
            "com.example.reader",
            FocusUsageEventPolicy.resolve(
                stablePackage = "com.example.reader",
                events = listOf(
                    FocusUsageLifecycleEvent(
                        "com.example.reader",
                        UsageEvents.Event.ACTIVITY_RESUMED
                    ),
                    FocusUsageLifecycleEvent(
                        "com.example.reader",
                        UsageEvents.Event.ACTIVITY_STOPPED
                    )
                )
            )
        )
    }
}
