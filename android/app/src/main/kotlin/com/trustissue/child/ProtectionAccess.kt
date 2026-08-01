package com.trustissue.child

import android.app.AppOpsManager
import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.provider.Settings

object ProtectionAccess {
    private const val accessibilityHeartbeatMaxAgeMs = 10_000L

    @Volatile
    private var accessibilityReady = false

    @Volatile
    private var lastAccessibilityHeartbeatElapsedMs = 0L

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    fun hasOverlayAccess(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun compatibleModeReady(context: Context): Boolean =
        hasUsageAccess(context) && hasOverlayAccess(context)

    fun hasAccessibilityAccess(context: Context): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices
            .split(':')
            .any {
                it.contains(context.packageName, ignoreCase = true) &&
                    it.contains("SelfControlAccessibilityService", ignoreCase = true)
            }
    }

    /**
     * Accessibility only becomes an enforcement owner after its complete
     * service initialization has succeeded. A framework event by itself is not
     * enough to prove that rules, receivers, and preferences are ready.
     */
    fun markAccessibilityReady() {
        lastAccessibilityHeartbeatElapsedMs = SystemClock.elapsedRealtime()
        accessibilityReady = true
    }

    fun markAccessibilityOperational(): Boolean {
        if (!accessibilityReady) return false
        lastAccessibilityHeartbeatElapsedMs = SystemClock.elapsedRealtime()
        return true
    }

    fun clearAccessibilityOperational() {
        accessibilityReady = false
        lastAccessibilityHeartbeatElapsedMs = 0L
    }

    /**
     * Android Settings can still report Accessibility as enabled briefly when
     * its service is reconnecting or has stopped unexpectedly. Focus ownership
     * moves to Accessibility only while the service is actively heartbeating.
     */
    fun isAccessibilityOperational(context: Context): Boolean {
        if (!hasAccessibilityAccess(context)) return false
        val heartbeat = lastAccessibilityHeartbeatElapsedMs
        return AccessibilityOwnershipPolicy.isOperational(
            ready = accessibilityReady,
            heartbeatElapsedMs = heartbeat,
            nowElapsedMs = SystemClock.elapsedRealtime(),
            maxHeartbeatAgeMs = accessibilityHeartbeatMaxAgeMs
        )
    }
}

internal object AccessibilityOwnershipPolicy {
    fun isOperational(
        ready: Boolean,
        heartbeatElapsedMs: Long,
        nowElapsedMs: Long,
        maxHeartbeatAgeMs: Long
    ): Boolean {
        if (!ready || heartbeatElapsedMs <= 0L) return false
        val age = nowElapsedMs - heartbeatElapsedMs
        return age in 0..maxHeartbeatAgeMs
    }
}
