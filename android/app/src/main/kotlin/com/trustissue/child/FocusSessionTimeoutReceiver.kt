package com.trustissue.child

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class FocusSessionTimeoutReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        runCatching {
            StartupStateSanitizer.sanitize(context)
            TrackerConfig.completeStudySessionIfFinished(context)
            context.sendBroadcast(
                Intent(ACTION_FOCUS_SESSION_ENDED).setPackage(context.packageName)
            )
        }.onFailure {
            TrustIssueDebugLog.append(
                context,
                "FocusSessionTimeout",
                "E",
                "RECEIVER_FAILED error=${it.javaClass.simpleName}"
            )
        }
    }

    companion object {
        const val ACTION_FOCUS_SESSION_ENDED =
            "com.trustissue.child.action.FOCUS_SESSION_ENDED"
    }
}
