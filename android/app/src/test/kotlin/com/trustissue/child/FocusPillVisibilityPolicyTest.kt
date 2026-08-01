package com.trustissue.child

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusPillVisibilityPolicyTest {
    @Test
    fun `book study shows globally while session is active`() {
        assertTrue(
            FocusPillVisibilityPolicy.shouldShow(
                sessionActive = true,
                appStudy = false,
                allowlistPolicy = false,
                selectedApp = false,
                systemRequired = true,
                safetyApp = true,
                ownApp = true
            )
        )
    }

    @Test
    fun `app study shows only inside selected non-system app`() {
        assertTrue(
            FocusPillVisibilityPolicy.shouldShow(
                sessionActive = true,
                appStudy = true,
                allowlistPolicy = true,
                selectedApp = true,
                systemRequired = false,
                safetyApp = false,
                ownApp = false
            )
        )
        assertFalse(
            FocusPillVisibilityPolicy.shouldShow(
                sessionActive = true,
                appStudy = true,
                allowlistPolicy = true,
                selectedApp = false,
                systemRequired = false,
                safetyApp = false,
                ownApp = false
            )
        )
    }

    @Test
    fun `inactive session never shows pill`() {
        assertFalse(
            FocusPillVisibilityPolicy.shouldShow(
                sessionActive = false,
                appStudy = false,
                allowlistPolicy = true,
                selectedApp = true,
                systemRequired = false,
                safetyApp = false,
                ownApp = false
            )
        )
    }
}
