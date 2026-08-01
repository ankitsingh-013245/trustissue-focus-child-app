package com.trustissue.child

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramReelsDetectorTest {
    @Test
    fun `lone Reels navigation tab does not trigger blocker`() {
        val result = InstagramReelsDetector.detect(
            listOf(
                InstagramReelsDetector.Node(
                    viewId = "com.instagram.android:id/tab_bar",
                    contentDescription = "Reels"
                ),
                InstagramReelsDetector.Node(
                    viewId = "com.instagram.android:id/feed_recycler",
                    className = "androidx.recyclerview.widget.RecyclerView",
                    scrollable = true
                ),
                InstagramReelsDetector.Node(
                    contentDescription = "Like"
                ),
                InstagramReelsDetector.Node(
                    contentDescription = "Comment"
                ),
                InstagramReelsDetector.Node(
                    contentDescription = "Share"
                )
            )
        )

        assertFalse(result.isReels)
        assertNull(result.fingerprint)
    }

    @Test
    fun `Instagram Story reel identifiers do not trigger Reels blocker`() {
        val result = InstagramReelsDetector.detect(
            listOf(
                InstagramReelsDetector.Node(
                    viewId = "com.instagram.android:id/reel_viewer",
                    className = "androidx.viewpager.widget.ViewPager",
                    scrollable = true
                ),
                InstagramReelsDetector.Node(contentDescription = "Like"),
                InstagramReelsDetector.Node(contentDescription = "Comment"),
                InstagramReelsDetector.Node(contentDescription = "Share")
            )
        )

        assertFalse(result.isReels)
        assertNull(result.fingerprint)
    }

    @Test
    fun `clips viewer with controls is classified as Reels`() {
        val result = InstagramReelsDetector.detect(reelsNodes("A useful Reel"))

        assertTrue(result.isReels)
        assertTrue(result.confidence >= 6)
        assertNotNull(result.fingerprint)
        assertTrue(result.signals.strongPlayer)
        assertTrue(result.signals.actionCount >= 3)
        assertTrue(result.signals.viewIdCount >= 3)
    }

    @Test
    fun `selected Reels tab plus viewer controls detects an obfuscated pager`() {
        val result = InstagramReelsDetector.detect(
            listOf(
                InstagramReelsDetector.Node(
                    contentDescription = "Reels",
                    selected = true
                ),
                InstagramReelsDetector.Node(
                    className = "androidx.recyclerview.widget.RecyclerView",
                    scrollable = true
                ),
                InstagramReelsDetector.Node(contentDescription = "Like"),
                InstagramReelsDetector.Node(contentDescription = "Comment"),
                InstagramReelsDetector.Node(contentDescription = "Share")
            )
        )

        assertTrue(result.isReels)
    }

    @Test
    fun `selected Reels tab and pager are enough for structural detection`() {
        val result = InstagramReelsDetector.detect(
            listOf(
                InstagramReelsDetector.Node(
                    contentDescription = "Reels",
                    selected = true
                ),
                InstagramReelsDetector.Node(
                    className = "androidx.recyclerview.widget.RecyclerView",
                    scrollable = true
                )
            )
        )

        assertTrue(result.isReels)
    }

    @Test
    fun `multiple clips components support label-free structural detection`() {
        val nodes = listOf(
            InstagramReelsDetector.Node(
                viewId = "com.instagram.android:id/clips_caption"
            ),
            InstagramReelsDetector.Node(
                viewId = "com.instagram.android:id/clips_audio"
            )
        )

        assertFalse(InstagramReelsDetector.detect(nodes).isReels)
        assertTrue(
            InstagramReelsDetector.detect(
                nodes,
                structuralOnly = true
            ).isReels
        )
    }

    @Test
    fun `Reel identity changes with its caption`() {
        val first = InstagramReelsDetector.detect(reelsNodes("First Reel"))
        val second = InstagramReelsDetector.detect(reelsNodes("Second Reel"))

        assertTrue(first.isReels)
        assertTrue(second.isReels)
        assertTrue(first.fingerprint != second.fingerprint)
    }

    @Test
    fun `nested caption text provides a Reel identity`() {
        val result = InstagramReelsDetector.detect(
            listOf(
                InstagramReelsDetector.Node(
                    viewId = "com.instagram.android:id/clips_viewer",
                    className = "androidx.recyclerview.widget.RecyclerView",
                    scrollable = true
                ),
                InstagramReelsDetector.Node(
                    text = "A nested Reel caption",
                    ancestorViewIds = "com.instagram.android:id/clips_caption"
                )
            )
        )

        assertTrue(result.isReels)
        assertNotNull(result.fingerprint)
    }

    private fun reelsNodes(caption: String): List<InstagramReelsDetector.Node> {
        return listOf(
            InstagramReelsDetector.Node(
                viewId = "com.instagram.android:id/clips_viewer",
                className = "androidx.recyclerview.widget.RecyclerView",
                scrollable = true
            ),
            InstagramReelsDetector.Node(
                viewId = "com.instagram.android:id/clips_caption",
                text = caption
            ),
            InstagramReelsDetector.Node(
                viewId = "com.instagram.android:id/clips_author",
                text = "Creator"
            ),
            InstagramReelsDetector.Node(
                viewId = "com.instagram.android:id/clips_like",
                contentDescription = "Like"
            ),
            InstagramReelsDetector.Node(
                viewId = "com.instagram.android:id/clips_comment",
                contentDescription = "Comment"
            ),
            InstagramReelsDetector.Node(
                viewId = "com.instagram.android:id/clips_share",
                contentDescription = "Share"
            )
        )
    }
}
