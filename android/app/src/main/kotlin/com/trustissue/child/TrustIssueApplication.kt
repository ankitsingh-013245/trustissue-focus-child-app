package com.trustissue.child

import android.app.Application

class TrustIssueApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching { StartupStateSanitizer.sanitize(this) }
            .onFailure {
                TrustIssueDebugLog.append(
                    this,
                    "TrustIssueApplication",
                    "E",
                    "STARTUP_STATE_SANITIZE_FAILED error=${it.javaClass.simpleName}"
                )
            }
        runCatching {
            TrackerConfig.completeStudySessionIfFinished(this)
            StrictFocusDndController.reconcile(this)
            if (
                !WebProtectionConfig.isRuntimeRequired(this) &&
                !FocusWebProtectionService.isRunningInProcess()
            ) {
                WebProtectionConfig.markServiceStopped(this)
            }
        }
            .onFailure {
                TrustIssueDebugLog.append(
                    this,
                    "TrustIssueApplication",
                    "E",
                    "STARTUP_RECONCILE_FAILED error=${it.javaClass.simpleName}"
                )
            }
    }
}
