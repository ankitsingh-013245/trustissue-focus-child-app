package com.trustissue.child

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class FocusBreakTimeoutReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        runCatching {
            StartupStateSanitizer.sanitize(context)
            TrackerConfig.finishFocusBreak(context, "timeout")
            TrackerConfig.pauseStudyTimerForIdle(context)
            context.sendBroadcast(
                Intent(ACTION_FOCUS_BREAK_ENDED).setPackage(context.packageName)
            )
        }.onFailure {
            TrustIssueDebugLog.append(
                context,
                "FocusBreakTimeout",
                "E",
                "RECEIVER_FAILED error=${it.javaClass.simpleName}"
            )
        }
    }

    companion object {
        const val ACTION_FOCUS_BREAK_ENDED =
            "com.trustissue.child.action.FOCUS_BREAK_ENDED"
    }
}
