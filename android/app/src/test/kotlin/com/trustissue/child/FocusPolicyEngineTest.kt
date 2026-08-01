package com.trustissue.child

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusPolicyEngineTest {
    @Test
    fun inactiveSessionAllowsApps() {
        val decision = decide(active = false, packageName = "example.blocked")
        assertTrue(decision.allowed)
        assertEquals(FocusPolicyEngine.Reason.SESSION_INACTIVE, decision.reason)
    }

    @Test
    fun systemAndSafetyAppsTakePriority() {
        val snapshot = snapshot(active = true, policy = "allowlist")
        assertTrue(
            FocusPolicyEngine.decide(
                "android",
                snapshot,
                systemRequired = true,
                safetyApp = false,
                selectedBreakApp = false
            ).allowed
        )
        assertTrue(
            FocusPolicyEngine.decide(
                "example.phone",
                snapshot,
                systemRequired = false,
                safetyApp = true,
                selectedBreakApp = false
            ).allowed
        )
    }

    @Test
    fun breakAllowsOnlySelectedPackage() {
        val snapshot = snapshot(active = true, policy = "allowlist")
        val selected = FocusPolicyEngine.decide(
            "example.break",
            snapshot,
            systemRequired = false,
            safetyApp = false,
            selectedBreakApp = true
        )
        val other = FocusPolicyEngine.decide(
            "example.other",
            snapshot,
            systemRequired = false,
            safetyApp = false,
            selectedBreakApp = false
        )
        assertTrue(selected.allowed)
        assertFalse(other.allowed)
    }

    @Test
    fun allowlistAndBlocklistAreDeterministic() {
        assertTrue(decide(policy = "allowlist", packageName = "example.allowed").allowed)
        assertFalse(decide(policy = "allowlist", packageName = "example.blocked").allowed)
        assertFalse(decide(policy = "blocklist", packageName = "example.blocked").allowed)
        assertTrue(decide(policy = "blocklist", packageName = "example.allowed").allowed)
    }

    private fun decide(
        active: Boolean = true,
        policy: String = "allowlist",
        packageName: String
    ): FocusPolicyEngine.Decision {
        return FocusPolicyEngine.decide(
            packageName = packageName,
            snapshot = snapshot(active, policy),
            systemRequired = false,
            safetyApp = false,
            selectedBreakApp = false
        )
    }

    private fun snapshot(
        active: Boolean,
        policy: String
    ): FocusPolicyEngine.Snapshot {
        return FocusPolicyEngine.Snapshot(
            active = active,
            policy = policy,
            allowedPackages = setOf("example.allowed"),
            blockedPackages = setOf("example.blocked")
        )
    }
}
