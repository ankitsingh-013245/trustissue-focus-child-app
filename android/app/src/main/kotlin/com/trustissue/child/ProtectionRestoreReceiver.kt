package com.trustissue.child

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restores user-configured protection after reboot, update, or clock changes. */
class ProtectionRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action !in supportedActions) return
        runCatching {
            StartupStateSanitizer.sanitize(context)
            UsageStatsReader.invalidate()
            TrackerConfig.completeStudySessionIfFinished(context)
            StrictFocusDndController.reconcile(context)
            if (
                (TrackerConfig.isStudyModeEnabled(context) ||
                    TrackerConfig.hasDailyAppLimits(context)) &&
                ProtectionAccess.compatibleModeReady(context)
            ) {
                FocusUsageMonitorService.start(context)
            }
            if (
                WebProtectionConfig.permissionGranted(context) &&
                WebProtectionConfig.shouldRun(context)
            ) {
                WebProtectionConfig.startIfRequired(context)
            }
        }.onFailure {
            TrustIssueDebugLog.append(
                context,
                "ProtectionRestore",
                "E",
                "RESTORE_FAILED error=${it.javaClass.simpleName}"
            )
        }
    }

    private companion object {
        val supportedActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED
        )
    }
}
