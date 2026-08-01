package com.trustissue.child

/**
 * Keeps short-lived windows from replacing the last real foreground app.
 *
 * Accessibility overlays, keyboards and System UI can all emit
 * TYPE_WINDOW_STATE_CHANGED even though the user did not leave the app below
 * them. Treating those events as an app switch creates an overlay feedback
 * loop: showing our pill reports our own package, which then hides the pill.
 */
object FocusForegroundEventPolicy {
    private val userFacingOwnActivities = setOf(
        "MainActivity",
        "FocusGateActivity",
        "StudyToolGateActivity",
        "WebBlockActivity"
    )

    fun shouldKeepStableForeground(
        observedPackage: String,
        observedClassName: String,
        stablePackage: String,
        ownPackage: String,
        inputMethodWindow: Boolean,
        transientSystemWindow: Boolean,
        ownOverlayMutationRecent: Boolean
    ): Boolean {
        if (inputMethodWindow || transientSystemWindow) return true
        if (observedPackage != ownPackage || !ownOverlayMutationRecent) return false
        if (isUserFacingOwnActivity(observedClassName, ownPackage)) return false

        // A non-Activity window from our package while the pill exists is the
        // accessibility overlay itself, not a real switch into TrustIssue.
        return stablePackage != ownPackage
    }

    fun isTransientSystemWindowPackage(packageName: String): Boolean {
        return packageName == "android" ||
            packageName == "com.android.systemui" ||
            packageName == "com.android.intentresolver" ||
            packageName == "com.samsung.android.app.smartcapture" ||
            packageName == "com.samsung.android.biometrics.app.setting" ||
            packageName.contains("permissioncontroller") ||
            packageName.contains("credentialmanager")
    }

    private fun isUserFacingOwnActivity(className: String, ownPackage: String): Boolean {
        if (className.isBlank()) return false
        val simpleName = className.substringAfterLast('.')
        return className.startsWith("$ownPackage.") &&
            (simpleName in userFacingOwnActivities || simpleName.endsWith("Activity"))
    }
}
