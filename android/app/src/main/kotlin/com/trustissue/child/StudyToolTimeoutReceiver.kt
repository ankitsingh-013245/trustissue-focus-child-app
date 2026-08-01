package com.trustissue.child

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class StudyToolTimeoutReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        runCatching {
            StartupStateSanitizer.sanitize(context)
            val targetPackage = TrackerConfig.quickStudyToolPackage(context)
            val sourcePackage = TrackerConfig.quickStudyToolSourcePackage(context)
            TrackerConfig.endQuickStudyToolAccess(context, "timeout")
            context.sendBroadcast(
                Intent(ACTION_QUICK_TOOL_ENDED)
                    .setPackage(context.packageName)
                    .putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
                    .putExtra(EXTRA_SOURCE_PACKAGE, sourcePackage)
            )
        }.onFailure {
            TrustIssueDebugLog.append(
                context,
                "StudyToolTimeout",
                "E",
                "RECEIVER_FAILED error=${it.javaClass.simpleName}"
            )
        }
    }

    companion object {
        const val ACTION_QUICK_TOOL_ENDED =
            "com.trustissue.child.action.QUICK_TOOL_ENDED"
        const val EXTRA_TARGET_PACKAGE = "quickToolTargetPackage"
        const val EXTRA_SOURCE_PACKAGE = "quickToolSourcePackage"
    }
}
