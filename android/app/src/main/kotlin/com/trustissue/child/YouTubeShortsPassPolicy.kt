package com.trustissue.child

/**
 * One-Short allowance policy kept separate from Android APIs for unit tests.
 */
object YouTubeShortsPassPolicy {
    const val scrollOnlyFingerprint = "scroll-only"
    const val scrollGraceMs = 900L
    const val maximumPassMs = 15 * 60 * 1000L

    data class Pass(
        val fingerprint: String,
        val grantedAtElapsedMs: Long,
        val grantedAtItemSequence: Long = 0L
    )

    data class Decision(
        val shouldBlock: Boolean,
        val reason: String,
        val passAgeMs: Long?,
        val identityRelation: String
    )

    fun evaluate(
        currentFingerprint: String?,
        pass: Pass?,
        shortsPagerScrolled: Boolean,
        nowElapsedMs: Long,
        currentItemSequence: Long = pass?.grantedAtItemSequence ?: 0L
    ): Decision {
        if (pass == null) {
            return Decision(
                shouldBlock = true,
                reason = "no_pass",
                passAgeMs = null,
                identityRelation = "none"
            )
        }
        if (pass.fingerprint.isBlank()) {
            return Decision(
                shouldBlock = true,
                reason = "blank_pass",
                passAgeMs = null,
                identityRelation = "invalid"
            )
        }

        val ageMs = nowElapsedMs - pass.grantedAtElapsedMs
        val identityRelation = when {
            pass.fingerprint == scrollOnlyFingerprint -> "scroll_only"
            currentFingerprint == null -> "unavailable"
            currentFingerprint == pass.fingerprint -> "same"
            else -> "changed"
        }
        if (ageMs < 0L) {
            return Decision(true, "clock_reset", ageMs, identityRelation)
        }
        if (ageMs > maximumPassMs) {
            return Decision(true, "pass_expired", ageMs, identityRelation)
        }
        if (currentItemSequence != pass.grantedAtItemSequence) {
            val reason = if (currentItemSequence > pass.grantedAtItemSequence) {
                "item_sequence_advanced"
            } else {
                "item_sequence_reset"
            }
            return Decision(true, reason, ageMs, identityRelation)
        }
        if (shortsPagerScrolled) {
            return Decision(true, "pager_scroll", ageMs, identityRelation)
        }
        if (ageMs < scrollGraceMs) {
            return Decision(false, "scroll_grace", ageMs, identityRelation)
        }
        if (pass.fingerprint == scrollOnlyFingerprint) {
            return Decision(false, "awaiting_pager_scroll", ageMs, identityRelation)
        }
        if (currentFingerprint == null) {
            return Decision(false, "identity_unavailable", ageMs, identityRelation)
        }
        if (currentFingerprint != pass.fingerprint) {
            return Decision(true, "identity_changed", ageMs, identityRelation)
        }
        return Decision(false, "same_identity", ageMs, identityRelation)
    }

    fun shouldBlock(
        currentFingerprint: String?,
        pass: Pass?,
        shortsPagerScrolled: Boolean,
        nowElapsedMs: Long,
        currentItemSequence: Long = pass?.grantedAtItemSequence ?: 0L
    ): Boolean {
        return evaluate(
            currentFingerprint = currentFingerprint,
            pass = pass,
            shortsPagerScrolled = shortsPagerScrolled,
            nowElapsedMs = nowElapsedMs,
            currentItemSequence = currentItemSequence
        ).shouldBlock
    }
}

/**
 * Keeps accessibility-tree inspection responsive without letting continuous
 * content-change events postpone it forever.
 */
object ShortFormInspectionSchedulePolicy {
    const val regularDelayMs = 120L
    const val pagerScrollDelayMs = 40L
    const val maximumWaitMs = 350L

    data class Decision(
        val dueAtUptimeMs: Long,
        val scrollPriority: Boolean,
        val deadlineReached: Boolean
    )

    fun next(
        nowUptimeMs: Long,
        windowStartedAtUptimeMs: Long,
        currentlyScheduledAtUptimeMs: Long,
        scrollPriority: Boolean,
        pagerScroll: Boolean
    ): Decision {
        val deadline = windowStartedAtUptimeMs + maximumWaitMs
        val nextScrollPriority = scrollPriority || pagerScroll
        val dueAt = if (nextScrollPriority) {
            val existing = currentlyScheduledAtUptimeMs
                .takeIf { it > 0L }
                ?: Long.MAX_VALUE
            minOf(existing, nowUptimeMs + pagerScrollDelayMs, deadline)
        } else {
            minOf(nowUptimeMs + regularDelayMs, deadline)
        }
        return Decision(
            dueAtUptimeMs = dueAt,
            scrollPriority = nextScrollPriority,
            deadlineReached = dueAt >= deadline
        )
    }
}

/**
 * Classifies TYPE_VIEW_SCROLLED events without depending on one exact private
 * app view hierarchy. YouTube and Instagram frequently change or obfuscate
 * pager view IDs, so an otherwise-unclassified scroll is treated as a page
 * change once a supported short-form surface has already been confirmed.
 */
object ShortFormScrollPolicy {
    data class Decision(
        val isPagerScroll: Boolean,
        val reason: String
    )

    private val nestedSurfaceMarkers = listOf(
        "comment",
        "live_chat",
        "livechat",
        "description",
        "transcript",
        "reply",
        "bottom_sheet",
        "direct",
        "inbox",
        "thread",
        "story"
    )

    private val shortsContainerMarkers = listOf(
        "reel",
        "shorts",
        "clips",
        "pivot_pager"
    )

    fun evaluatePagerScroll(
        surfaceActive: Boolean,
        sourceViewId: String,
        sourceClassName: String,
        eventClassName: String,
        sourceScrollable: Boolean
    ): Decision {
        val normalizedViewId = sourceViewId.lowercase()
        val normalizedClassName = "$sourceClassName $eventClassName".lowercase()
        val descriptor = "$normalizedViewId $normalizedClassName"

        val nestedMarker = nestedSurfaceMarkers.firstOrNull { descriptor.contains(it) }
        if (nestedMarker != null) {
            return Decision(false, "nested_$nestedMarker")
        }

        val containerMarker =
            shortsContainerMarkers.firstOrNull { normalizedViewId.contains(it) }
        val knownPager =
            normalizedClassName.contains("viewpager") ||
                normalizedClassName.contains("recyclerview")
        if (containerMarker != null && (sourceScrollable || knownPager)) {
            return Decision(true, "known_${containerMarker}_container")
        }

        // A confirmed short-form surface is the important context. Requiring
        // the event source to retain a private pager ID caused real swipes to
        // be missed and left a "Watch only this" pass active for later items.
        // Unknown or source-less scroll events therefore fail closed.
        return if (surfaceActive) {
            Decision(true, "confirmed_surface_fallback")
        } else {
            Decision(false, "surface_unconfirmed")
        }
    }

    fun isPagerScroll(
        surfaceActive: Boolean,
        sourceViewId: String,
        sourceClassName: String,
        eventClassName: String,
        sourceScrollable: Boolean
    ): Boolean {
        return evaluatePagerScroll(
            surfaceActive = surfaceActive,
            sourceViewId = sourceViewId,
            sourceClassName = sourceClassName,
            eventClassName = eventClassName,
            sourceScrollable = sourceScrollable
        ).isPagerScroll
    }
}
