package com.trustissue.child

import android.app.usage.UsageEvents

internal data class FocusUsageLifecycleEvent(
    val packageName: String,
    val eventType: Int
)

/**
 * Reduces package lifecycle events to a stable foreground package.
 *
 * The existing foreground is intentionally retained until a different package
 * reports a foreground/resume event. Pause and stop events are ignored because
 * they can describe an Activity transition inside the same package, and older
 * UsageEvents constants alias MOVE_TO_BACKGROUND to ACTIVITY_PAUSED.
 */
internal object FocusUsageEventPolicy {
    fun resolve(
        stablePackage: String,
        events: Iterable<FocusUsageLifecycleEvent>
    ): String {
        var foreground = stablePackage.trim()
        for (event in events) {
            val packageName = event.packageName.trim()
            if (packageName.isEmpty()) continue
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED,
                UsageEvents.Event.MOVE_TO_FOREGROUND -> foreground = packageName
            }
        }
        return foreground
    }
}
