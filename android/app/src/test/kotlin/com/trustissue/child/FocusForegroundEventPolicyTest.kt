package com.trustissue.child

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusForegroundEventPolicyTest {
    @Test
    fun `pill window cannot replace selected app with own package`() {
        assertTrue(
            FocusForegroundEventPolicy.shouldKeepStableForeground(
                observedPackage = "com.trustissue.child",
                observedClassName = "android.widget.FrameLayout",
                stablePackage = "com.google.android.youtube",
                ownPackage = "com.trustissue.child",
                inputMethodWindow = false,
                transientSystemWindow = false,
                ownOverlayMutationRecent = true
            )
        )
    }

    @Test
    fun `real TrustIssue activity is still treated as foreground`() {
        assertFalse(
            FocusForegroundEventPolicy.shouldKeepStableForeground(
                observedPackage = "com.trustissue.child",
                observedClassName = "com.trustissue.child.MainActivity",
                stablePackage = "com.google.android.youtube",
                ownPackage = "com.trustissue.child",
                inputMethodWindow = false,
                transientSystemWindow = false,
                ownOverlayMutationRecent = true
            )
        )
    }

    @Test
    fun `keyboard and System UI preserve underlying app`() {
        assertTrue(
            FocusForegroundEventPolicy.shouldKeepStableForeground(
                observedPackage = "com.samsung.android.honeyboard",
                observedClassName = "android.inputmethodservice.SoftInputWindow",
                stablePackage = "com.google.android.youtube",
                ownPackage = "com.trustissue.child",
                inputMethodWindow = true,
                transientSystemWindow = false,
                ownOverlayMutationRecent = false
            )
        )
        assertTrue(
            FocusForegroundEventPolicy.shouldKeepStableForeground(
                observedPackage = "com.android.systemui",
                observedClassName = "android.widget.FrameLayout",
                stablePackage = "com.google.android.youtube",
                ownPackage = "com.trustissue.child",
                inputMethodWindow = false,
                transientSystemWindow = true,
                ownOverlayMutationRecent = false
            )
        )
    }

    @Test
    fun `normal app switch updates stable foreground`() {
        assertFalse(
            FocusForegroundEventPolicy.shouldKeepStableForeground(
                observedPackage = "com.google.android.gm",
                observedClassName = "com.google.android.gm.ConversationListActivityGmail",
                stablePackage = "com.google.android.youtube",
                ownPackage = "com.trustissue.child",
                inputMethodWindow = false,
                transientSystemWindow = false,
                ownOverlayMutationRecent = false
            )
        )
    }
}
