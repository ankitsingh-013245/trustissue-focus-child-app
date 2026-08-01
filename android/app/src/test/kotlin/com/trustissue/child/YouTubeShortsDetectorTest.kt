package com.trustissue.child

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeShortsDetectorTest {
    @Test
    fun `lone Shorts navigation tab does not trigger blocker`() {
        val result = YouTubeShortsDetector.detect(
            listOf(
                YouTubeShortsDetector.Node(
                    viewId = "com.google.android.youtube:id/pivot_bar_item",
                    contentDescription = "Shorts"
                ),
                YouTubeShortsDetector.Node(
                    viewId = "com.google.android.youtube:id/home_feed",
                    className = "androidx.recyclerview.widget.RecyclerView",
                    scrollable = true
                )
            )
        )

        assertFalse(result.isShorts)
        assertNull(result.fingerprint)
    }

    @Test
    fun `reel player with controls is classified as Shorts`() {
        val result = YouTubeShortsDetector.detect(shortsNodes("A useful video"))

        assertTrue(result.isShorts)
        assertTrue(result.confidence >= 6)
        assertNotNull(result.fingerprint)
        assertTrue(result.signals.strongPlayer)
        assertTrue(result.signals.actionCount >= 3)
        assertTrue(result.signals.viewIdCount >= 3)
    }

    @Test
    fun `content identity changes when the Short changes`() {
        val first = YouTubeShortsDetector.detect(shortsNodes("First Short"))
        val second = YouTubeShortsDetector.detect(shortsNodes("Second Short"))

        assertTrue(first.isShorts)
        assertTrue(second.isShorts)
        assertTrue(first.fingerprint != second.fingerprint)
    }

    @Test
    fun `nested metadata text provides a Short identity`() {
        val result = YouTubeShortsDetector.detect(
            listOf(
                YouTubeShortsDetector.Node(
                    viewId = "com.google.android.youtube:id/reel_player_page_container",
                    className = "androidx.recyclerview.widget.RecyclerView",
                    scrollable = true
                ),
                YouTubeShortsDetector.Node(
                    text = "A nested video title",
                    ancestorViewIds =
                        "com.google.android.youtube:id/reel_video_metadata"
                ),
                YouTubeShortsDetector.Node(
                    viewId = "com.google.android.youtube:id/reel_like_button",
                    contentDescription = "Like"
                ),
                YouTubeShortsDetector.Node(
                    viewId = "com.google.android.youtube:id/reel_comments_button",
                    contentDescription = "Comments"
                ),
                YouTubeShortsDetector.Node(
                    viewId = "com.google.android.youtube:id/reel_share_button",
                    contentDescription = "Share"
                )
            )
        )

        assertTrue(result.isShorts)
        assertNotNull(result.fingerprint)
    }

    @Test
    fun `unknown scroll on a confirmed Shorts surface is a pager scroll`() {
        val decision = ShortFormScrollPolicy.evaluatePagerScroll(
            surfaceActive = true,
            sourceViewId = "",
            sourceClassName = "android.view.View",
            eventClassName = "android.view.View",
            sourceScrollable = false
        )

        assertTrue(decision.isPagerScroll)
        assertEquals("confirmed_surface_fallback", decision.reason)
    }

    @Test
    fun `unknown scroll outside a confirmed Shorts surface is ignored`() {
        assertFalse(
            ShortFormScrollPolicy.isPagerScroll(
                surfaceActive = false,
                sourceViewId = "",
                sourceClassName = "android.view.View",
                eventClassName = "android.view.View",
                sourceScrollable = false
            )
        )
    }

    @Test
    fun `nested comments scroll does not consume the one Short pass`() {
        val decision = ShortFormScrollPolicy.evaluatePagerScroll(
            surfaceActive = true,
            sourceViewId = "com.google.android.youtube:id/comments_recycler",
            sourceClassName = "androidx.recyclerview.widget.RecyclerView",
            eventClassName = "androidx.recyclerview.widget.RecyclerView",
            sourceScrollable = true
        )

        assertFalse(decision.isPagerScroll)
        assertEquals("nested_comment", decision.reason)
    }

    @Test
    fun `one Short pass allows current identity and blocks pager scroll`() {
        val pass = YouTubeShortsPassPolicy.Pass(
            fingerprint = "current",
            grantedAtElapsedMs = 1_000L
        )

        assertFalse(
            YouTubeShortsPassPolicy.shouldBlock(
                currentFingerprint = "current",
                pass = pass,
                shortsPagerScrolled = false,
                nowElapsedMs = 3_000L
            )
        )
        assertTrue(
            YouTubeShortsPassPolicy.shouldBlock(
                currentFingerprint = "current",
                pass = pass,
                shortsPagerScrolled = true,
                nowElapsedMs = 3_000L
            )
        )
    }

    @Test
    fun `one Short pass blocks a changed identity`() {
        val pass = YouTubeShortsPassPolicy.Pass(
            fingerprint = "first",
            grantedAtElapsedMs = 1_000L
        )

        assertTrue(
            YouTubeShortsPassPolicy.shouldBlock(
                currentFingerprint = "second",
                pass = pass,
                shortsPagerScrolled = false,
                nowElapsedMs = 3_000L
            )
        )
    }

    @Test
    fun `brief post-gate fingerprint motion stays inside grace period`() {
        val pass = YouTubeShortsPassPolicy.Pass(
            fingerprint = "current",
            grantedAtElapsedMs = 1_000L
        )

        assertEquals(
            false,
            YouTubeShortsPassPolicy.shouldBlock(
                currentFingerprint = "different",
                pass = pass,
                shortsPagerScrolled = false,
                nowElapsedMs = 1_500L
            )
        )
    }

    @Test
    fun `real pager scroll is blocked even immediately after granting pass`() {
        val pass = YouTubeShortsPassPolicy.Pass(
            fingerprint = "current",
            grantedAtElapsedMs = 1_000L
        )

        assertTrue(
            YouTubeShortsPassPolicy.shouldBlock(
                currentFingerprint = "current",
                pass = pass,
                shortsPagerScrolled = true,
                nowElapsedMs = 1_100L
            )
        )

        val decision = YouTubeShortsPassPolicy.evaluate(
            currentFingerprint = "current",
            pass = pass,
            shortsPagerScrolled = true,
            nowElapsedMs = 1_100L
        )
        assertEquals("pager_scroll", decision.reason)
        assertEquals("same", decision.identityRelation)
        assertEquals(100L, decision.passAgeMs)
    }

    @Test
    fun `advanced item sequence blocks even when inspection has no scroll flag`() {
        val pass = YouTubeShortsPassPolicy.Pass(
            fingerprint = YouTubeShortsPassPolicy.scrollOnlyFingerprint,
            grantedAtElapsedMs = 1_000L,
            grantedAtItemSequence = 7L
        )

        val decision = YouTubeShortsPassPolicy.evaluate(
            currentFingerprint = null,
            pass = pass,
            shortsPagerScrolled = false,
            nowElapsedMs = 2_000L,
            currentItemSequence = 8L
        )

        assertTrue(decision.shouldBlock)
        assertEquals("item_sequence_advanced", decision.reason)
    }

    @Test
    fun `content event debounce never moves past maximum wait`() {
        val first = ShortFormInspectionSchedulePolicy.next(
            nowUptimeMs = 1_000L,
            windowStartedAtUptimeMs = 1_000L,
            currentlyScheduledAtUptimeMs = 0L,
            scrollPriority = false,
            pagerScroll = false
        )
        val nearDeadline = ShortFormInspectionSchedulePolicy.next(
            nowUptimeMs = 1_300L,
            windowStartedAtUptimeMs = 1_000L,
            currentlyScheduledAtUptimeMs = first.dueAtUptimeMs,
            scrollPriority = first.scrollPriority,
            pagerScroll = false
        )

        assertEquals(1_350L, nearDeadline.dueAtUptimeMs)
        assertTrue(nearDeadline.deadlineReached)
    }

    @Test
    fun `pager scroll pulls inspection forward and content cannot postpone it`() {
        val content = ShortFormInspectionSchedulePolicy.next(
            nowUptimeMs = 1_000L,
            windowStartedAtUptimeMs = 1_000L,
            currentlyScheduledAtUptimeMs = 0L,
            scrollPriority = false,
            pagerScroll = false
        )
        val scroll = ShortFormInspectionSchedulePolicy.next(
            nowUptimeMs = 1_020L,
            windowStartedAtUptimeMs = 1_000L,
            currentlyScheduledAtUptimeMs = content.dueAtUptimeMs,
            scrollPriority = content.scrollPriority,
            pagerScroll = true
        )
        val laterContent = ShortFormInspectionSchedulePolicy.next(
            nowUptimeMs = 1_030L,
            windowStartedAtUptimeMs = 1_000L,
            currentlyScheduledAtUptimeMs = scroll.dueAtUptimeMs,
            scrollPriority = scroll.scrollPriority,
            pagerScroll = false
        )

        assertEquals(1_060L, scroll.dueAtUptimeMs)
        assertTrue(scroll.scrollPriority)
        assertEquals(scroll.dueAtUptimeMs, laterContent.dueAtUptimeMs)
    }

    private fun shortsNodes(title: String): List<YouTubeShortsDetector.Node> {
        return listOf(
            YouTubeShortsDetector.Node(
                viewId = "com.google.android.youtube:id/reel_player_page_container",
                className = "androidx.recyclerview.widget.RecyclerView",
                scrollable = true
            ),
            YouTubeShortsDetector.Node(
                viewId = "com.google.android.youtube:id/reel_video_metadata",
                text = title
            ),
            YouTubeShortsDetector.Node(
                viewId = "com.google.android.youtube:id/reel_channel_name",
                text = "Creator"
            ),
            YouTubeShortsDetector.Node(
                viewId = "com.google.android.youtube:id/reel_like_button",
                contentDescription = "Like"
            ),
            YouTubeShortsDetector.Node(
                viewId = "com.google.android.youtube:id/reel_comments_button",
                contentDescription = "Comments"
            ),
            YouTubeShortsDetector.Node(
                viewId = "com.google.android.youtube:id/reel_share_button",
                contentDescription = "Share"
            )
        )
    }
}
