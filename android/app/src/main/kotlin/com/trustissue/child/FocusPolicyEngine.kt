package com.trustissue.child

/**
 * Pure, deterministic focus decisions. UI and accessibility code provide
 * observations; this class is the single place that decides allow vs block.
 */
object FocusPolicyEngine {
    const val policyVersion = 1

    enum class Reason {
        SESSION_INACTIVE,
        SYSTEM_REQUIRED,
        SAFETY_APP,
        SELECTED_BREAK_APP,
        SESSION_STUDY_TOOL,
        QUICK_STUDY_TOOL,
        WEB_PROTECTION_UNAVAILABLE,
        ALLOWLIST_MATCH,
        ALLOWLIST_BLOCK,
        BLOCKLIST_MATCH,
        BLOCKLIST_ALLOW
    }

    data class Snapshot(
        val active: Boolean,
        val policy: String,
        val allowedPackages: Set<String>,
        val blockedPackages: Set<String>
    )

    data class Decision(
        val allowed: Boolean,
        val reason: Reason,
        val policyVersion: Int = FocusPolicyEngine.policyVersion
    )

    fun decide(
        packageName: String,
        snapshot: Snapshot,
        systemRequired: Boolean,
        safetyApp: Boolean,
        selectedBreakApp: Boolean
    ): Decision {
        if (!snapshot.active) return Decision(true, Reason.SESSION_INACTIVE)
        if (systemRequired) return Decision(true, Reason.SYSTEM_REQUIRED)
        if (safetyApp) return Decision(true, Reason.SAFETY_APP)
        if (selectedBreakApp) return Decision(true, Reason.SELECTED_BREAK_APP)

        return if (snapshot.policy == "blocklist") {
            if (snapshot.blockedPackages.contains(packageName)) {
                Decision(false, Reason.BLOCKLIST_MATCH)
            } else {
                Decision(true, Reason.BLOCKLIST_ALLOW)
            }
        } else {
            if (snapshot.allowedPackages.contains(packageName)) {
                Decision(true, Reason.ALLOWLIST_MATCH)
            } else {
                Decision(false, Reason.ALLOWLIST_BLOCK)
            }
        }
    }
}
