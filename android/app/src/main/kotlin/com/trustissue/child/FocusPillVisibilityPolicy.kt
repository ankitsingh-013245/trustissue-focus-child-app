package com.trustissue.child

/** Pure visibility rules kept separate from timer accounting and overlay UI. */
object FocusPillVisibilityPolicy {
    fun shouldShow(
        sessionActive: Boolean,
        appStudy: Boolean,
        allowlistPolicy: Boolean,
        selectedApp: Boolean,
        systemRequired: Boolean,
        safetyApp: Boolean,
        ownApp: Boolean
    ): Boolean {
        if (!sessionActive) return false
        if (!appStudy) return true
        return allowlistPolicy &&
            selectedApp &&
            !systemRequired &&
            !safetyApp &&
            !ownApp
    }
}
