package com.trustissue.child

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.util.ArrayDeque

/**
 * Focus observer with optional, tightly-scoped YouTube Shorts and Instagram
 * Reels detectors.
 *
 * Normal Focus behavior uses package transitions only. Accessibility node
 * retrieval is used exclusively while a user-enabled short-form blocker is on,
 * the foreground window belongs to its supported app, and a relevant event is
 * received. It never interacts with YouTube or Instagram controls.
 */
class SelfControlAccessibilityService :
    AccessibilityService(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private companion object {
        const val flutterPrefsName = "FlutterSharedPreferences"
        const val gateThrottleMs = 1_200L
        const val shortsGateThrottleMs = 650L
        const val contextualHelperAccessMs = 2 * 60 * 1000L
        const val youtubePackage = "com.google.android.youtube"
        const val ignoredScrollDiagnosticThrottleMs = 1_500L
        const val maxShortsSnapshotNodes = 360
        val instagramPackages = setOf(
            "com.instagram.android",
            "com.instagram.lite"
        )

        val shortsEventTypes = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED
        )

        val fixedSafetyPackages = setOf(
            "android",
            "com.android.settings",
            "com.android.systemui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.dialer",
            "com.google.android.dialer",
            "com.samsung.android.dialer",
            "com.samsung.android.incallui",
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging"
        )
    }

    private data class Rules(
        val active: Boolean = false,
        val policy: String = "allowlist",
        val allowedPackages: Set<String> = emptySet(),
        val blockedPackages: Set<String> = emptySet(),
        val youtubeShortsBlocking: Boolean = false,
        val instagramReelsBlocking: Boolean = false
    )

    private data class InstagramSnapshotEntry(
        val node: AccessibilityNodeInfo,
        val viewId: String,
        val className: String,
        val scrollable: Boolean,
        val selected: Boolean,
        val ancestorViewIds: String
    )

    private var flutterPrefs: SharedPreferences? = null
    private var rules = Rules()
    private var currentForegroundPackage = ""
    private var homePackages: Set<String> = emptySet()
    private var dialAndMessagePackages: Set<String> = emptySet()
    private var inputMethodPackages: Set<String> = emptySet()
    private var lastGatePackage = ""
    private var lastGateAtMs = 0L
    private var lastShortsGateFingerprint = ""
    private var lastShortsGateAtMs = 0L
    private var shortsItemSequence = 0L
    private var shortsEvaluatedSequence = 0L
    private var shortsInspectionWindowStartedAtUptimeMs = 0L
    private var shortsInspectionScheduledAtUptimeMs = 0L
    private var shortsScrollInspectionPriority = false
    private var shortsSurfaceActive = false
    private var consecutiveNonShortsSnapshots = 0
    private var lastShortsIgnoredScrollLogAtMs = 0L
    private var lastReelsGateFingerprint = ""
    private var lastReelsGateAtMs = 0L
    private var reelsItemSequence = 0L
    private var reelsEvaluatedSequence = 0L
    private var reelsInspectionWindowStartedAtUptimeMs = 0L
    private var reelsInspectionScheduledAtUptimeMs = 0L
    private var reelsScrollInspectionPriority = false
    private var reelsSurfaceActive = false
    private var consecutiveNonReelsSnapshots = 0
    private var lastReelsIgnoredScrollLogAtMs = 0L
    private var lastToolGatePackage = ""
    private var lastToolGateAtMs = 0L
    private var lastWebBlockDomain = ""
    private var lastWebBlockAtMs = 0L
    private var activeHelperPackage = ""
    private var activeHelperSourcePackage = ""
    private var activeHelperUntilElapsedMs = 0L
    private var lastPrimaryStudyPackage = ""
    private var screenReceiverRegistered = false
    private var screenInteractive = true
    private var fallbackMonitorRequested = false
    private val shortFormDiagnosticSession =
        SystemClock.elapsedRealtime().toString(36)
    private val handler = Handler(Looper.getMainLooper())
    private val shortsInspection = Runnable {
        val now = SystemClock.uptimeMillis()
        val queuedMs = shortsInspectionWindowStartedAtUptimeMs
            .takeIf { it > 0L }
            ?.let { now - it }
            ?: 0L
        shortsInspectionWindowStartedAtUptimeMs = 0L
        shortsInspectionScheduledAtUptimeMs = 0L
        shortsScrollInspectionPriority = false
        debug(
            "SHORTS_INSPECTION_STARTED session=$shortFormDiagnosticSession " +
                "queuedMs=$queuedMs sequence=$shortsItemSequence " +
                "evaluated=$shortsEvaluatedSequence " +
                "pending=${shortsItemSequence != shortsEvaluatedSequence}"
        )
        runCatching { inspectYouTubeShorts() }
            .onFailure {
                debug(
                    "SHORTS_INSPECTION_FAILED session=$shortFormDiagnosticSession " +
                        "error=${it.javaClass.simpleName} sequence=$shortsItemSequence " +
                        "evaluated=$shortsEvaluatedSequence"
                )
            }
    }
    private val reelsInspection = Runnable {
        val now = SystemClock.uptimeMillis()
        val queuedMs = reelsInspectionWindowStartedAtUptimeMs
            .takeIf { it > 0L }
            ?.let { now - it }
            ?: 0L
        reelsInspectionWindowStartedAtUptimeMs = 0L
        reelsInspectionScheduledAtUptimeMs = 0L
        reelsScrollInspectionPriority = false
        debug(
            "REELS_INSPECTION_STARTED session=$shortFormDiagnosticSession " +
                "queuedMs=$queuedMs sequence=$reelsItemSequence " +
                "evaluated=$reelsEvaluatedSequence " +
                "pending=${reelsItemSequence != reelsEvaluatedSequence}"
        )
        runCatching { inspectInstagramReels() }
            .onFailure {
                debug(
                    "REELS_INSPECTION_FAILED session=$shortFormDiagnosticSession " +
                        "error=${it.javaClass.simpleName} sequence=$reelsItemSequence " +
                        "evaluated=$reelsEvaluatedSequence"
                )
            }
    }
    private val accessibilityHeartbeat = object : Runnable {
        override fun run() {
            if (ProtectionAccess.markAccessibilityOperational()) {
                handler.postDelayed(this, 3_000L)
            }
        }
    }
    private val studyToolResolver by lazy { StudyToolResolver(this) }
    private val mediaController by lazy { BlockedMediaController(this) }
    private val shortsOverlay by lazy { YouTubeShortsOverlayController(this) }
    private val reelsOverlay by lazy { YouTubeShortsOverlayController(this) }
    private val focusPill by lazy {
        FocusPillController(this, ::returnFromQuickStudyTool)
    }
    private val helperTimeout = Runnable {
        runCatching {
            val helperPackage = activeHelperPackage
            val sourcePackage = activeHelperSourcePackage
            if (
                helperPackage.isNotBlank() &&
                SystemClock.elapsedRealtime() >= activeHelperUntilElapsedMs
            ) {
                clearActiveHelper()
                TrackerConfig.pauseStudyTimerForIdle(this)
                focusPill.hide("helper_timeout")
                if (currentForegroundPackage == helperPackage) {
                    returnToStudySource(helperPackage, sourcePackage, "helper_timeout")
                }
            }
        }.onFailure {
            debug("HELPER_TIMEOUT_FAILED error=${it.javaClass.simpleName}")
            clearActiveHelper()
            runCatching { focusPill.hide("helper_timeout_failure") }
        }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val receivedIntent = intent ?: return
            val action = receivedIntent.action
            if (action == Intent.ACTION_SCREEN_OFF) {
                screenInteractive = false
                clearYouTubeShortsPass("screen_off")
                clearInstagramReelsPass("screen_off")
                TrackerConfig.endQuickStudyToolAccess(
                    this@SelfControlAccessibilityService,
                    "screen_off"
                )
                if (TrackerConfig.isAppStudyMode(this@SelfControlAccessibilityService)) {
                    TrackerConfig.pauseStudyTimerForIdle(
                        this@SelfControlAccessibilityService
                    )
                }
                focusPill.hide("screen_off")
                clearActiveHelper()
                debug("SCREEN_OFF stablePackage=$currentForegroundPackage")
            } else if (action == Intent.ACTION_SHUTDOWN) {
                screenInteractive = false
                TrackerConfig.endQuickStudyToolAccess(
                    this@SelfControlAccessibilityService,
                    "shutdown"
                )
                TrackerConfig.pauseStudyTimerForIdle(this@SelfControlAccessibilityService)
                focusPill.destroy("shutdown")
                clearActiveHelper()
            } else if (
                action == Intent.ACTION_SCREEN_ON ||
                action == Intent.ACTION_USER_PRESENT
            ) {
                screenInteractive = true
                restorePillAfterScreenOn(
                    if (action == Intent.ACTION_USER_PRESENT) "user_present" else "screen_on"
                )
            } else if (action == StudyToolTimeoutReceiver.ACTION_QUICK_TOOL_ENDED) {
                val targetPackage = receivedIntent.getStringExtra(
                    StudyToolTimeoutReceiver.EXTRA_TARGET_PACKAGE
                ).orEmpty()
                val sourcePackage = receivedIntent.getStringExtra(
                    StudyToolTimeoutReceiver.EXTRA_SOURCE_PACKAGE
                ).orEmpty()
                if (
                    targetPackage.isNotBlank() &&
                    currentForegroundPackage == targetPackage
                ) {
                    returnFromQuickStudyTool(targetPackage, sourcePackage)
                } else if (
                    activeHelperPackage.isNotBlank() &&
                    currentForegroundPackage == activeHelperPackage
                ) {
                    val helperPackage = activeHelperPackage
                    clearActiveHelper()
                    returnToStudySource(
                        helperPackage,
                        sourcePackage,
                        "quick_tool_expired_in_helper"
                    )
                } else {
                    val foreground = currentForegroundPackage
                    if (foreground.isNotEmpty()) evaluate(foreground)
                }
            } else if (action == WebProtectionConfig.actionStateChanged) {
                val foreground = currentForegroundPackage
                if (foreground.isNotEmpty()) evaluate(foreground)
            } else if (action == WebProtectionConfig.actionDomainBlocked) {
                val domain = receivedIntent.getStringExtra(
                    WebProtectionConfig.extraDomain
                ).orEmpty()
                val category = receivedIntent.getStringExtra(
                    WebProtectionConfig.extraCategory
                ).orEmpty()
                if (
                    (
                        rules.active ||
                            WebProtectionConfig.isGlobalProtectionEnabled(
                                this@SelfControlAccessibilityService
                            )
                        ) &&
                    WebProtectionConfig.isBrowserPackage(
                        this@SelfControlAccessibilityService,
                        currentForegroundPackage
                    )
                ) {
                    showWebBlock(domain, category)
                }
            } else if (
                action == FocusBreakTimeoutReceiver.ACTION_FOCUS_BREAK_ENDED
                    || action == FocusSessionTimeoutReceiver.ACTION_FOCUS_SESSION_ENDED
            ) {
                val foreground = currentForegroundPackage
                if (foreground.isNotEmpty()) evaluate(foreground)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        fallbackMonitorRequested = false
        handler.removeCallbacks(accessibilityHeartbeat)
        ProtectionAccess.clearAccessibilityOperational()
        runCatching {
            flutterPrefs?.unregisterOnSharedPreferenceChangeListener(this)
        }
        flutterPrefs = null
        unregisterScreenReceiver()
        runCatching {
            StartupStateSanitizer.sanitize(this)
            homePackages = loadHomePackages()
            dialAndMessagePackages = loadDialAndMessagePackages()
            inputMethodPackages = loadInputMethodPackages()
            screenInteractive =
                (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
            flutterPrefs = getSharedPreferences(flutterPrefsName, Context.MODE_PRIVATE).also {
                rules = loadRules(it)
                it.registerOnSharedPreferenceChangeListener(this)
            }
            // A pass belongs to one live accessibility-service session. If the
            // process was killed before onDestroy, never carry that stale pass
            // into a new service instance.
            YouTubeShortsPassStore.clear(this)
            InstagramReelsPassStore.clear(this)
            shortsItemSequence = 0L
            shortsEvaluatedSequence = 0L
            reelsItemSequence = 0L
            reelsEvaluatedSequence = 0L
            registerScreenReceiver()
            if (
                rules.active ||
                TrackerConfig.hasDailyAppLimits(this)
            ) {
                // Keep package-only compatible protection warm as a fallback.
                fallbackMonitorRequested = FocusUsageMonitorService.start(this)
            }
            debug(
                "ACCESSIBILITY_CONNECTED policyVersion=${FocusPolicyEngine.policyVersion} " +
                    "shortFormSession=$shortFormDiagnosticSession " +
                    "screenInteractive=$screenInteractive " +
                    "shortsBlocking=${rules.youtubeShortsBlocking} " +
                    "reelsBlocking=${rules.instagramReelsBlocking}"
            )
            if (screenInteractive && rules.active) {
                restorePillAfterScreenOn("service_connected")
            }
            ProtectionAccess.markAccessibilityReady()
            accessibilityHeartbeat.run()
        }.onFailure {
            // Accessibility runs in the app process. Never let stale beta state
            // or an OEM service error close the Flutter activity.
            rules = Rules()
            handler.removeCallbacks(accessibilityHeartbeat)
            ProtectionAccess.clearAccessibilityOperational()
            runCatching {
                flutterPrefs?.unregisterOnSharedPreferenceChangeListener(this)
            }
            flutterPrefs = null
            unregisterScreenReceiver()
            if (
                (TrackerConfig.isStudyModeEnabled(this) ||
                    TrackerConfig.hasDailyAppLimits(this)) &&
                ProtectionAccess.compatibleModeReady(this)
            ) {
                fallbackMonitorRequested = FocusUsageMonitorService.start(this)
            }
            debug("ACCESSIBILITY_CONNECT_FAILED error=${it.javaClass.simpleName}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!ProtectionAccess.markAccessibilityOperational()) {
            if (
                !fallbackMonitorRequested &&
                (TrackerConfig.isStudyModeEnabled(this) ||
                    TrackerConfig.hasDailyAppLimits(this)) &&
                ProtectionAccess.compatibleModeReady(this)
            ) {
                fallbackMonitorRequested = FocusUsageMonitorService.start(this)
            }
            return
        }
        val stablePackageBeforeEvent = currentForegroundPackage
        runCatching { handleAccessibilityEvent(event) }
            .onFailure {
                currentForegroundPackage = stablePackageBeforeEvent
                debug(
                    "ACCESSIBILITY_EVENT_FAILED error=${it.javaClass.simpleName} " +
                        "stablePackage=$currentForegroundPackage pillPreserved=true"
                )
            }
    }

    private fun handleAccessibilityEvent(event: AccessibilityEvent?) {
        val receivedEvent = event ?: return
        if (!screenInteractive) return
        val packageName = receivedEvent.packageName?.toString()?.trim().orEmpty()
        if (packageName.isEmpty()) return
        if (receivedEvent.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            handleForegroundWindowEvent(receivedEvent, packageName)
        }
        if (
            rules.youtubeShortsBlocking &&
            packageName == youtubePackage &&
            receivedEvent.eventType in shortsEventTypes
        ) {
            var confirmedPagerScroll = false
            if (receivedEvent.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
                val pendingBefore = shortsItemSequence != shortsEvaluatedSequence
                val scrollDecision = classifyShortsPagerScroll(receivedEvent)
                if (scrollDecision.isPagerScroll) {
                    confirmedPagerScroll = true
                    if (!pendingBefore) {
                        advanceShortsItemSequence(scrollDecision)
                    }
                }
                logShortFormScrollDecision(
                    prefix = "SHORTS",
                    event = receivedEvent,
                    decision = scrollDecision,
                    surfaceActive = shortsSurfaceActive,
                    pendingBefore = pendingBefore,
                    itemSequence = shortsItemSequence,
                    evaluatedSequence = shortsEvaluatedSequence
                )
            }
            scheduleShortsInspection(
                pagerScroll = confirmedPagerScroll,
                eventType = receivedEvent.eventType
            )
        }
        if (
            rules.instagramReelsBlocking &&
            packageName in instagramPackages &&
            receivedEvent.eventType in shortsEventTypes
        ) {
            var confirmedPagerScroll = false
            if (receivedEvent.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
                val pendingBefore = reelsItemSequence != reelsEvaluatedSequence
                val scrollDecision = classifyInstagramReelsPagerScroll(receivedEvent)
                if (scrollDecision.isPagerScroll) {
                    confirmedPagerScroll = true
                    if (!pendingBefore) {
                        advanceReelsItemSequence(scrollDecision)
                    }
                }
                logShortFormScrollDecision(
                    prefix = "REELS",
                    event = receivedEvent,
                    decision = scrollDecision,
                    surfaceActive = reelsSurfaceActive,
                    pendingBefore = pendingBefore,
                    itemSequence = reelsItemSequence,
                    evaluatedSequence = reelsEvaluatedSequence
                )
            }
            scheduleReelsInspection(
                pagerScroll = confirmedPagerScroll,
                eventType = receivedEvent.eventType
            )
        }
    }

    private fun advanceShortsItemSequence(
        scrollDecision: ShortFormScrollPolicy.Decision
    ) {
        val previousSequence = shortsItemSequence
        shortsItemSequence = nextItemSequence(previousSequence)
        val now = SystemClock.elapsedRealtime()
        val pass = YouTubeShortsPassStore.read(this, now)
        debug(
            "SHORTS_SEQUENCE_ADVANCED session=$shortFormDiagnosticSession " +
                "from=$previousSequence to=$shortsItemSequence " +
                "evaluated=$shortsEvaluatedSequence classifier=${scrollDecision.reason} " +
                "pass=${pass != null} passSequence=${pass?.grantedAtItemSequence ?: -1L}"
        )
        if (pass == null) return

        val decision = YouTubeShortsPassPolicy.evaluate(
            currentFingerprint = null,
            pass = pass,
            shortsPagerScrolled = true,
            nowElapsedMs = now,
            currentItemSequence = shortsItemSequence
        )
        YouTubeShortsPassStore.clear(this)
        debug(
            "SHORTS_PASS_INVALIDATED_IMMEDIATE reason=${decision.reason} " +
                "sequence=$shortsItemSequence passSequence=${pass.grantedAtItemSequence} " +
                "passAgeMs=${decision.passAgeMs ?: -1L}"
        )
        showYouTubeShortsGate(YouTubeShortsPassPolicy.scrollOnlyFingerprint)
    }

    private fun advanceReelsItemSequence(
        scrollDecision: ShortFormScrollPolicy.Decision
    ) {
        val previousSequence = reelsItemSequence
        reelsItemSequence = nextItemSequence(previousSequence)
        val now = SystemClock.elapsedRealtime()
        val pass = InstagramReelsPassStore.read(this, now)
        debug(
            "REELS_SEQUENCE_ADVANCED session=$shortFormDiagnosticSession " +
                "from=$previousSequence to=$reelsItemSequence " +
                "evaluated=$reelsEvaluatedSequence classifier=${scrollDecision.reason} " +
                "pass=${pass != null} passSequence=${pass?.grantedAtItemSequence ?: -1L}"
        )
        if (pass == null) return

        val decision = YouTubeShortsPassPolicy.evaluate(
            currentFingerprint = null,
            pass = pass,
            shortsPagerScrolled = true,
            nowElapsedMs = now,
            currentItemSequence = reelsItemSequence
        )
        InstagramReelsPassStore.clear(this)
        debug(
            "REELS_PASS_INVALIDATED_IMMEDIATE reason=${decision.reason} " +
                "sequence=$reelsItemSequence passSequence=${pass.grantedAtItemSequence} " +
                "passAgeMs=${decision.passAgeMs ?: -1L}"
        )
        showInstagramReelsGate(YouTubeShortsPassPolicy.scrollOnlyFingerprint)
    }

    private fun nextItemSequence(current: Long): Long {
        return if (current == Long.MAX_VALUE) 1L else current + 1L
    }

    private fun scheduleShortsInspection(
        pagerScroll: Boolean,
        eventType: Int
    ) {
        val now = SystemClock.uptimeMillis()
        val firstInWindow = shortsInspectionWindowStartedAtUptimeMs <= 0L
        if (firstInWindow) {
            shortsInspectionWindowStartedAtUptimeMs = now
        }
        val previousScheduledAt = shortsInspectionScheduledAtUptimeMs
        val decision = ShortFormInspectionSchedulePolicy.next(
            nowUptimeMs = now,
            windowStartedAtUptimeMs = shortsInspectionWindowStartedAtUptimeMs,
            currentlyScheduledAtUptimeMs = previousScheduledAt,
            scrollPriority = shortsScrollInspectionPriority,
            pagerScroll = pagerScroll
        )
        shortsScrollInspectionPriority = decision.scrollPriority
        val scheduleChanged = previousScheduledAt != decision.dueAtUptimeMs
        if (scheduleChanged) {
            handler.removeCallbacks(shortsInspection)
            shortsInspectionScheduledAtUptimeMs = decision.dueAtUptimeMs
            handler.postAtTime(shortsInspection, decision.dueAtUptimeMs)
        }
        if (
            firstInWindow ||
            pagerScroll ||
            (decision.deadlineReached && scheduleChanged)
        ) {
            debug(
                "SHORTS_INSPECTION_SCHEDULED eventType=$eventType " +
                    "pager=$pagerScroll delayMs=" +
                    "${(decision.dueAtUptimeMs - now).coerceAtLeast(0L)} " +
                    "deadline=${decision.deadlineReached} " +
                    "priority=${decision.scrollPriority} sequence=$shortsItemSequence " +
                    "evaluated=$shortsEvaluatedSequence"
            )
        }
    }

    private fun scheduleReelsInspection(
        pagerScroll: Boolean,
        eventType: Int
    ) {
        val now = SystemClock.uptimeMillis()
        val firstInWindow = reelsInspectionWindowStartedAtUptimeMs <= 0L
        if (firstInWindow) {
            reelsInspectionWindowStartedAtUptimeMs = now
        }
        val previousScheduledAt = reelsInspectionScheduledAtUptimeMs
        val decision = ShortFormInspectionSchedulePolicy.next(
            nowUptimeMs = now,
            windowStartedAtUptimeMs = reelsInspectionWindowStartedAtUptimeMs,
            currentlyScheduledAtUptimeMs = previousScheduledAt,
            scrollPriority = reelsScrollInspectionPriority,
            pagerScroll = pagerScroll
        )
        reelsScrollInspectionPriority = decision.scrollPriority
        val scheduleChanged = previousScheduledAt != decision.dueAtUptimeMs
        if (scheduleChanged) {
            handler.removeCallbacks(reelsInspection)
            reelsInspectionScheduledAtUptimeMs = decision.dueAtUptimeMs
            handler.postAtTime(reelsInspection, decision.dueAtUptimeMs)
        }
        if (
            firstInWindow ||
            pagerScroll ||
            (decision.deadlineReached && scheduleChanged)
        ) {
            debug(
                "REELS_INSPECTION_SCHEDULED eventType=$eventType " +
                    "pager=$pagerScroll delayMs=" +
                    "${(decision.dueAtUptimeMs - now).coerceAtLeast(0L)} " +
                    "deadline=${decision.deadlineReached} " +
                    "priority=${decision.scrollPriority} sequence=$reelsItemSequence " +
                    "evaluated=$reelsEvaluatedSequence"
            )
        }
    }

    private fun handleForegroundWindowEvent(
        event: AccessibilityEvent,
        packageName: String
    ) {
        val className = event.className?.toString()?.trim().orEmpty()
        val inputMethodWindow = inputMethodPackages.contains(packageName)
        val transientSystemWindow =
            FocusForegroundEventPolicy.isTransientSystemWindowPackage(packageName)
        val ownOverlayMutationRecent =
            focusPill.isRecentOverlayMutation() ||
                shortsOverlay.isRecentMutation() ||
                reelsOverlay.isRecentMutation()
        val keepStableForeground = FocusForegroundEventPolicy.shouldKeepStableForeground(
            observedPackage = packageName,
            observedClassName = className,
            stablePackage = currentForegroundPackage,
            ownPackage = this.packageName,
            inputMethodWindow = inputMethodWindow,
            transientSystemWindow = transientSystemWindow,
            ownOverlayMutationRecent = ownOverlayMutationRecent
        )
        if (keepStableForeground) {
            debug(
                "FOREGROUND_WINDOW_IGNORED observed=$packageName " +
                    "class=${className.take(100)} stable=$currentForegroundPackage " +
                    "keyboard=$inputMethodWindow transient=$transientSystemWindow " +
                    "pillAttached=${focusPill.isAttached()} " +
                    "pillMutationRecent=$ownOverlayMutationRecent"
            )
            return
        }
        val previousPackage = currentForegroundPackage
        currentForegroundPackage = packageName
        if (previousPackage == youtubePackage && packageName != youtubePackage) {
            clearYouTubeShortsPass("left_youtube")
        }
        if (
            previousPackage in instagramPackages &&
            packageName != previousPackage
        ) {
            clearInstagramReelsPass("left_instagram")
        }
        evaluate(packageName, previousPackage)
    }

    private fun inspectYouTubeShorts() {
        val observedSequence = shortsItemSequence
        val scrolled = observedSequence != shortsEvaluatedSequence
        if (!rules.youtubeShortsBlocking) {
            debug(
                "SHORTS_INSPECTION_SKIPPED reason=blocking_disabled scroll=$scrolled " +
                    "sequence=$observedSequence evaluated=$shortsEvaluatedSequence"
            )
            return
        }
        if (!screenInteractive) {
            debug(
                "SHORTS_INSPECTION_SKIPPED reason=screen_off scroll=$scrolled " +
                    "sequence=$observedSequence evaluated=$shortsEvaluatedSequence"
            )
            return
        }

        val root = rootInActiveWindow
        if (root == null) {
            debug(
                "SHORTS_INSPECTION_SKIPPED reason=root_missing scroll=$scrolled " +
                    "surfaceActive=$shortsSurfaceActive sequence=$observedSequence " +
                    "evaluated=$shortsEvaluatedSequence stickyRetained=$scrolled"
            )
            return
        }
        val rootPackage = root.packageName?.toString().orEmpty()
        if (rootPackage != youtubePackage) {
            debug(
                "SHORTS_INSPECTION_SKIPPED reason=wrong_root_package " +
                    "rootPackage=$rootPackage scroll=$scrolled " +
                    "sequence=$observedSequence evaluated=$shortsEvaluatedSequence " +
                    "stickyRetained=$scrolled"
            )
            return
        }
        val snapshot = shortsSnapshot(root)
        val detection = YouTubeShortsDetector.detect(snapshot)
        val signals = detection.signals
        debug(
            "SHORTS_SNAPSHOT detected=${detection.isShorts} confidence=${detection.confidence} " +
                "nodes=${signals.nodeCount} ids=${signals.viewIdCount} " +
                "scrollable=${signals.scrollableCount} strong=${signals.strongPlayer} " +
                "components=${signals.componentCount} genericIds=${signals.genericIdCount} " +
                "label=${signals.shortsLabel} actions=${signals.actionCount} " +
                "pager=${signals.verticalPager} identity=${detection.fingerprint != null} " +
                "scroll=$scrolled sequence=$observedSequence " +
                "evaluated=$shortsEvaluatedSequence " +
                "snapshotCapped=${signals.nodeCount >= maxShortsSnapshotNodes}"
        )
        if (!detection.isShorts) {
            consecutiveNonShortsSnapshots += 1
            debug(
                "SHORTS_SURFACE_MISS streak=$consecutiveNonShortsSnapshots " +
                    "previouslyActive=$shortsSurfaceActive scroll=$scrolled " +
                    "sequence=$observedSequence evaluated=$shortsEvaluatedSequence " +
                    "stickyRetained=$scrolled"
            )
            if (consecutiveNonShortsSnapshots >= 2) {
                clearYouTubeShortsPass("youtube_non_shorts")
            }
            return
        }

        shortsSurfaceActive = true
        consecutiveNonShortsSnapshots = 0
        val now = SystemClock.elapsedRealtime()
        val pass = YouTubeShortsPassStore.read(this, now)
        val decision = YouTubeShortsPassPolicy.evaluate(
            currentFingerprint = detection.fingerprint,
            pass = pass,
            shortsPagerScrolled = scrolled,
            nowElapsedMs = now,
            currentItemSequence = observedSequence
        )
        shortsEvaluatedSequence = observedSequence
        debug(
            "SHORTS_DECISION block=${decision.shouldBlock} reason=${decision.reason} " +
                "pass=${pass != null} passAgeMs=${decision.passAgeMs ?: -1L} " +
                "passSequence=${pass?.grantedAtItemSequence ?: -1L} " +
                "identity=${detection.fingerprint != null} " +
                "identityRelation=${decision.identityRelation} scroll=$scrolled " +
                "sequence=$observedSequence evaluated=$shortsEvaluatedSequence " +
                "overlay=${shortsOverlay.isVisible()}"
        )
        if (!decision.shouldBlock) return

        if (pass != null) {
            YouTubeShortsPassStore.clear(this)
            debug("SHORTS_PASS_INVALIDATED reason=${decision.reason}")
        }
        showYouTubeShortsGate(
            detection.fingerprint ?: YouTubeShortsPassPolicy.scrollOnlyFingerprint
        )
    }

    private fun classifyShortsPagerScroll(
        event: AccessibilityEvent
    ): ShortFormScrollPolicy.Decision {
        val source = event.source
        return ShortFormScrollPolicy.evaluatePagerScroll(
            surfaceActive = shortsSurfaceActive,
            sourceViewId = source?.viewIdResourceName.orEmpty(),
            sourceClassName = source?.className?.toString().orEmpty(),
            eventClassName = event.className?.toString().orEmpty(),
            sourceScrollable = source?.isScrollable == true
        )
    }

    private fun shortsSnapshot(root: AccessibilityNodeInfo): List<YouTubeShortsDetector.Node> {
        val nodes = ArrayList<YouTubeShortsDetector.Node>(96)
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, String>>()
        queue.add(root to "")
        while (queue.isNotEmpty() && nodes.size < maxShortsSnapshotNodes) {
            val (node, ancestorViewIds) = queue.removeFirst()
            val viewId = node.viewIdResourceName.orEmpty()
            nodes += YouTubeShortsDetector.Node(
                viewId = viewId,
                text = node.text?.toString().orEmpty(),
                contentDescription = node.contentDescription?.toString().orEmpty(),
                className = node.className?.toString().orEmpty(),
                scrollable = node.isScrollable,
                ancestorViewIds = ancestorViewIds
            )
            val childAncestorViewIds = "$ancestorViewIds $viewId".takeLast(480)
            val childCount = node.childCount.coerceAtMost(40)
            for (index in 0 until childCount) {
                node.getChild(index)?.let {
                    queue.addLast(it to childAncestorViewIds)
                }
            }
        }
        return nodes
    }

    private fun clearYouTubeShortsPass(reason: String) {
        val pendingBefore = shortsItemSequence != shortsEvaluatedSequence
        val surfaceBefore = shortsSurfaceActive
        val overlayBefore = shortsOverlay.isVisible()
        handler.removeCallbacks(shortsInspection)
        shortsInspectionWindowStartedAtUptimeMs = 0L
        shortsInspectionScheduledAtUptimeMs = 0L
        shortsScrollInspectionPriority = false
        shortsEvaluatedSequence = shortsItemSequence
        shortsSurfaceActive = false
        consecutiveNonShortsSnapshots = 0
        YouTubeShortsPassStore.clear(this)
        if (shortsOverlay.isVisible()) {
            shortsOverlay.hide()
            mediaController.release()
        }
        debug(
            "SHORTS_STATE_CLEARED reason=$reason surfaceBefore=$surfaceBefore " +
                "pendingScrollBefore=$pendingBefore overlayBefore=$overlayBefore " +
                "sequence=$shortsItemSequence evaluated=$shortsEvaluatedSequence"
        )
    }

    private fun inspectInstagramReels() {
        val observedSequence = reelsItemSequence
        val scrolled = observedSequence != reelsEvaluatedSequence
        if (!rules.instagramReelsBlocking) {
            debug(
                "REELS_INSPECTION_SKIPPED reason=blocking_disabled scroll=$scrolled " +
                    "sequence=$observedSequence evaluated=$reelsEvaluatedSequence"
            )
            return
        }
        if (!screenInteractive) {
            debug(
                "REELS_INSPECTION_SKIPPED reason=screen_off scroll=$scrolled " +
                    "sequence=$observedSequence evaluated=$reelsEvaluatedSequence"
            )
            return
        }

        val root = rootInActiveWindow
        if (root == null) {
            debug(
                "REELS_INSPECTION_SKIPPED reason=root_missing scroll=$scrolled " +
                    "surfaceActive=$reelsSurfaceActive sequence=$observedSequence " +
                    "evaluated=$reelsEvaluatedSequence stickyRetained=$scrolled"
            )
            return
        }
        val rootPackage = root.packageName?.toString().orEmpty()
        if (rootPackage !in instagramPackages) {
            debug(
                "REELS_INSPECTION_SKIPPED reason=wrong_root_package " +
                    "rootPackage=$rootPackage scroll=$scrolled " +
                    "sequence=$observedSequence evaluated=$reelsEvaluatedSequence " +
                    "stickyRetained=$scrolled"
            )
            return
        }
        val snapshotEntries = reelsSnapshotEntries(root)
        val structuralDetection = InstagramReelsDetector.detect(
            reelsDetectorNodes(snapshotEntries, includeContentLabels = false),
            structuralOnly = true
        )
        val structuralSignals = structuralDetection.signals
        debug(
            "REELS_STRUCTURE detected=${structuralDetection.isReels} " +
                "confidence=${structuralDetection.confidence} " +
                "nodes=${structuralSignals.nodeCount} ids=${structuralSignals.viewIdCount} " +
                "scrollable=${structuralSignals.scrollableCount} " +
                "selected=${structuralSignals.selectedCount} " +
                "strong=${structuralSignals.strongPlayer} " +
                "components=${structuralSignals.componentCount} " +
                "genericIds=${structuralSignals.genericIdCount} " +
                "label=${structuralSignals.reelsLabel} " +
                "selectedLabel=${structuralSignals.selectedReelsLabel} " +
                "pager=${structuralSignals.verticalPager} scroll=$scrolled " +
                "sequence=$observedSequence evaluated=$reelsEvaluatedSequence " +
                "snapshotCapped=${structuralSignals.nodeCount >= maxShortsSnapshotNodes}"
        )
        if (!structuralDetection.isReels) {
            consecutiveNonReelsSnapshots += 1
            debug(
                "REELS_SURFACE_MISS streak=$consecutiveNonReelsSnapshots " +
                    "previouslyActive=$reelsSurfaceActive scroll=$scrolled " +
                    "sequence=$observedSequence evaluated=$reelsEvaluatedSequence " +
                    "stickyRetained=$scrolled"
            )
            if (consecutiveNonReelsSnapshots >= 2) {
                clearInstagramReelsPass("instagram_non_reels")
            }
            return
        }

        val detection = InstagramReelsDetector.detect(
            reelsDetectorNodes(snapshotEntries, includeContentLabels = true),
            structuralOnly = true
        )
        val signals = detection.signals
        debug(
            "REELS_SNAPSHOT detected=${detection.isReels} confidence=${detection.confidence} " +
                "nodes=${signals.nodeCount} ids=${signals.viewIdCount} " +
                "scrollable=${signals.scrollableCount} selected=${signals.selectedCount} " +
                "strong=${signals.strongPlayer} components=${signals.componentCount} " +
                "genericIds=${signals.genericIdCount} label=${signals.reelsLabel} " +
                "selectedLabel=${signals.selectedReelsLabel} actions=${signals.actionCount} " +
                "pager=${signals.verticalPager} identity=${detection.fingerprint != null} " +
                "scroll=$scrolled sequence=$observedSequence " +
                "evaluated=$reelsEvaluatedSequence " +
                "snapshotCapped=${signals.nodeCount >= maxShortsSnapshotNodes}"
        )
        reelsSurfaceActive = true
        consecutiveNonReelsSnapshots = 0
        val now = SystemClock.elapsedRealtime()
        val pass = InstagramReelsPassStore.read(this, now)
        val decision = YouTubeShortsPassPolicy.evaluate(
            currentFingerprint = detection.fingerprint,
            pass = pass,
            shortsPagerScrolled = scrolled,
            nowElapsedMs = now,
            currentItemSequence = observedSequence
        )
        reelsEvaluatedSequence = observedSequence
        debug(
            "REELS_DECISION block=${decision.shouldBlock} reason=${decision.reason} " +
                "pass=${pass != null} passAgeMs=${decision.passAgeMs ?: -1L} " +
                "passSequence=${pass?.grantedAtItemSequence ?: -1L} " +
                "identity=${detection.fingerprint != null} " +
                "identityRelation=${decision.identityRelation} scroll=$scrolled " +
                "sequence=$observedSequence evaluated=$reelsEvaluatedSequence " +
                "overlay=${reelsOverlay.isVisible()}"
        )
        if (!decision.shouldBlock) return

        if (pass != null) {
            InstagramReelsPassStore.clear(this)
            debug("REELS_PASS_INVALIDATED reason=${decision.reason}")
        }
        showInstagramReelsGate(
            detection.fingerprint ?: YouTubeShortsPassPolicy.scrollOnlyFingerprint
        )
    }

    private fun classifyInstagramReelsPagerScroll(
        event: AccessibilityEvent
    ): ShortFormScrollPolicy.Decision {
        val source = event.source
        return ShortFormScrollPolicy.evaluatePagerScroll(
            surfaceActive = reelsSurfaceActive,
            sourceViewId = source?.viewIdResourceName.orEmpty(),
            sourceClassName = source?.className?.toString().orEmpty(),
            eventClassName = event.className?.toString().orEmpty(),
            sourceScrollable = source?.isScrollable == true
        )
    }

    private fun logShortFormScrollDecision(
        prefix: String,
        event: AccessibilityEvent,
        decision: ShortFormScrollPolicy.Decision,
        surfaceActive: Boolean,
        pendingBefore: Boolean,
        itemSequence: Long,
        evaluatedSequence: Long
    ) {
        val now = SystemClock.elapsedRealtime()
        if (decision.isPagerScroll && pendingBefore) return
        if (!decision.isPagerScroll) {
            val lastLoggedAt = if (prefix == "SHORTS") {
                lastShortsIgnoredScrollLogAtMs
            } else {
                lastReelsIgnoredScrollLogAtMs
            }
            if (now - lastLoggedAt < ignoredScrollDiagnosticThrottleMs) return
            if (prefix == "SHORTS") {
                lastShortsIgnoredScrollLogAtMs = now
            } else {
                lastReelsIgnoredScrollLogAtMs = now
            }
        }

        val source = event.source
        val sourceClass = source?.className
            ?.toString()
            .orEmpty()
            .substringAfterLast('.')
            .take(60)
            .ifBlank { "none" }
        val eventClass = event.className
            ?.toString()
            .orEmpty()
            .substringAfterLast('.')
            .take(60)
            .ifBlank { "none" }
        debug(
            "${prefix}_SCROLL_CLASSIFIED pager=${decision.isPagerScroll} " +
                "reason=${decision.reason} surfaceActive=$surfaceActive " +
                "pendingBefore=$pendingBefore sequence=$itemSequence " +
                "evaluated=$evaluatedSequence " +
                "sourceId=${if (source?.viewIdResourceName.isNullOrBlank()) "missing" else "present"} " +
                "sourceClass=$sourceClass eventClass=$eventClass " +
                "scrollable=${source?.isScrollable == true} " +
                "range=${event.fromIndex}:${event.toIndex} count=${event.itemCount}"
        )
    }

    private fun reelsSnapshotEntries(
        root: AccessibilityNodeInfo
    ): List<InstagramSnapshotEntry> {
        val entries = ArrayList<InstagramSnapshotEntry>(96)
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, String>>()
        queue.add(root to "")
        while (queue.isNotEmpty() && entries.size < maxShortsSnapshotNodes) {
            val (node, ancestorViewIds) = queue.removeFirst()
            val viewId = node.viewIdResourceName.orEmpty()
            entries += InstagramSnapshotEntry(
                node = node,
                viewId = viewId,
                className = node.className?.toString().orEmpty(),
                scrollable = node.isScrollable,
                selected = node.isSelected,
                ancestorViewIds = ancestorViewIds
            )
            val childAncestorViewIds = "$ancestorViewIds $viewId".takeLast(480)
            val childCount = node.childCount.coerceAtMost(40)
            for (index in 0 until childCount) {
                node.getChild(index)?.let {
                    queue.addLast(it to childAncestorViewIds)
                }
            }
        }
        return entries
    }

    private fun reelsDetectorNodes(
        entries: List<InstagramSnapshotEntry>,
        includeContentLabels: Boolean
    ): List<InstagramReelsDetector.Node> {
        return entries.map { entry ->
            val normalizedViewId = entry.viewId.lowercase()
            val safeNavigationLabel =
                entry.selected ||
                    normalizedViewId.contains("tab") ||
                    normalizedViewId.contains("navigation") ||
                    normalizedViewId.contains("pivot")
            InstagramReelsDetector.Node(
                viewId = entry.viewId,
                text = if (includeContentLabels) {
                    entry.node.text?.toString().orEmpty()
                } else {
                    ""
                },
                contentDescription = if (
                    includeContentLabels || safeNavigationLabel
                ) {
                    entry.node.contentDescription?.toString().orEmpty()
                } else {
                    ""
                },
                className = entry.className,
                scrollable = entry.scrollable,
                selected = entry.selected,
                ancestorViewIds = entry.ancestorViewIds
            )
        }
    }

    private fun clearInstagramReelsPass(reason: String) {
        val pendingBefore = reelsItemSequence != reelsEvaluatedSequence
        val surfaceBefore = reelsSurfaceActive
        val overlayBefore = reelsOverlay.isVisible()
        handler.removeCallbacks(reelsInspection)
        reelsInspectionWindowStartedAtUptimeMs = 0L
        reelsInspectionScheduledAtUptimeMs = 0L
        reelsScrollInspectionPriority = false
        reelsEvaluatedSequence = reelsItemSequence
        reelsSurfaceActive = false
        consecutiveNonReelsSnapshots = 0
        InstagramReelsPassStore.clear(this)
        if (reelsOverlay.isVisible()) {
            reelsOverlay.hide()
            mediaController.release()
        }
        debug(
            "REELS_STATE_CLEARED reason=$reason surfaceBefore=$surfaceBefore " +
                "pendingScrollBefore=$pendingBefore overlayBefore=$overlayBefore " +
                "sequence=$reelsItemSequence evaluated=$reelsEvaluatedSequence"
        )
    }

    private fun restorePillAfterScreenOn(reason: String) {
        if (!rules.active) {
            focusPill.destroy("screen_on_without_session")
            return
        }

        val appStudy = TrackerConfig.isAppStudyMode(this)
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (keyguard.isKeyguardLocked) {
            if (appStudy) {
                focusPill.hide("device_locked")
            } else {
                val stablePackage = currentForegroundPackage.ifBlank { "android" }
                focusPill.showFor(
                    stablePackage,
                    TrackerConfig.appName(this, stablePackage),
                    global = true
                )
            }
            debug("PILL_RESTORE_DEFERRED reason=$reason locked=true appStudy=$appStudy")
            return
        }

        val stablePackage = currentForegroundPackage
        if (stablePackage.isNotBlank()) {
            evaluate(stablePackage, stablePackage)
        } else if (!appStudy) {
            updateFocusPill(
                "android",
                systemRequired = false,
                safetyApp = false
            )
        }
        debug("PILL_RESTORE reason=$reason stablePackage=$stablePackage appStudy=$appStudy")
    }

    private fun evaluate(packageName: String, previousPackage: String = "") {
        if (rules.active && TrackerConfig.completeStudySessionIfFinished(this)) {
            rules = rules.copy(active = false)
            focusPill.destroy("session_complete")
            return
        }

        val appStudyAllowlist =
            rules.active &&
                TrackerConfig.isAppStudyMode(this) &&
                rules.policy == "allowlist"
        val primaryStudyApp =
            appStudyAllowlist && rules.allowedPackages.contains(packageName)
        val previousPrimaryStudyApp =
            appStudyAllowlist && rules.allowedPackages.contains(previousPackage)
        val quickTarget = TrackerConfig.quickStudyToolPackage(this)
        val quickRemainingMs = TrackerConfig.quickStudyToolRemainingMs(this)
        if (quickTarget.isNotBlank() && quickRemainingMs <= 0L) {
            val sourcePackage = TrackerConfig.quickStudyToolSourcePackage(this)
            TrackerConfig.endQuickStudyToolAccess(this, "foreground_expired")
            if (packageName == quickTarget) {
                returnFromQuickStudyTool(quickTarget, sourcePackage)
                return
            } else if (
                packageName == activeHelperPackage &&
                activeHelperSourcePackage == quickTarget
            ) {
                val helperPackage = activeHelperPackage
                clearActiveHelper()
                returnToStudySource(
                    helperPackage,
                    sourcePackage,
                    "quick_tool_expired_in_helper"
                )
                return
            }
        }
        if (homePackages.contains(packageName) && quickTarget.isNotBlank()) {
            TrackerConfig.endQuickStudyToolAccess(this, "home_opened")
        }

        val quickTool =
            packageName == TrackerConfig.quickStudyToolPackage(this) &&
                TrackerConfig.quickStudyToolRemainingMs(this) > 0L
        val previousQuickTool =
            previousPackage == TrackerConfig.quickStudyToolPackage(this) &&
                TrackerConfig.quickStudyToolRemainingMs(this) > 0L
        val sessionTool = TrackerConfig.isSessionStudyTool(this, packageName)
        val previousSessionTool =
            TrackerConfig.isSessionStudyTool(this, previousPackage)
        val helperChainSource = if (
            previousPackage == activeHelperPackage &&
            activeHelperSourcePackage.isNotBlank()
        ) {
            activeHelperSourcePackage
        } else {
            ""
        }
        val handoffSourcePackage = when {
            previousPrimaryStudyApp -> previousPackage
            helperChainSource.isNotBlank() &&
                rules.allowedPackages.contains(helperChainSource) -> helperChainSource
            else -> ""
        }
        val contextualHelper =
            appStudyAllowlist &&
                isContextualSystemHelper(packageName) &&
                (
                    previousPrimaryStudyApp ||
                        previousSessionTool ||
                        previousQuickTool ||
                        helperChainSource.isNotBlank()
                    )
        if (contextualHelper) {
            val continuingHelper = helperChainSource.isNotBlank()
            activeHelperPackage = packageName
            activeHelperSourcePackage = when {
                continuingHelper -> helperChainSource
                else -> previousPackage
            }
            if (!continuingHelper || activeHelperUntilElapsedMs <= 0L) {
                activeHelperUntilElapsedMs =
                    SystemClock.elapsedRealtime() + contextualHelperAccessMs
            }
            handler.removeCallbacks(helperTimeout)
            handler.postDelayed(
                helperTimeout,
                (activeHelperUntilElapsedMs - SystemClock.elapsedRealtime())
                    .coerceAtLeast(1L)
            )
            if (packageName != "com.android.systemui") {
                TrackerConfig.pauseStudyTimerForIdle(this)
            }
            val pillSource = activeHelperSourcePackage.ifBlank { previousPackage }
            if (pillSource.isNotBlank()) {
                focusPill.showFor(
                    pillSource,
                    TrackerConfig.appName(this, pillSource),
                    global = false
                )
            }
            debug(
                "STUDY_HELPER_ALLOWED package=$packageName " +
                    "source=$activeHelperSourcePackage"
            )
            return
        }

        val toolKind = if (
            appStudyAllowlist &&
            handoffSourcePackage.isNotBlank() &&
            packageName != this.packageName &&
            !primaryStudyApp &&
            !sessionTool &&
            !quickTool
        ) {
            studyToolResolver.classify(packageName)
        } else {
            null
        }
        if (toolKind != null) {
            clearActiveHelper()
            TrackerConfig.pauseStudyTimerForIdle(this)
            focusPill.hide("study_tool_gate")
            showStudyToolGate(packageName, handoffSourcePackage, toolKind)
            return
        }

        if (packageName != activeHelperPackage) clearActiveHelper()

        val systemRequired = isSystemRequired(packageName)
        val safetyApp = isSafetyApp(packageName)
        val breakActive = TrackerConfig.isFocusBreakActive(this)
        val selectedBreakApp = TrackerConfig.isPackageOnFocusBreak(this, packageName)
        val toolAllowed = sessionTool || quickTool
        if (
            breakActive &&
            !selectedBreakApp &&
            !systemRequired &&
            !safetyApp &&
            packageName != this.packageName
        ) {
            TrackerConfig.pauseStudyTimerForBreak(this)
            if (TrackerConfig.isAppStudyMode(this)) {
                focusPill.hide("break_app_not_selected")
            } else {
                updateFocusPill(packageName, systemRequired, safetyApp)
            }
            stopBlockedAppPlayback(packageName)
            showFocusGate(
                packageName,
                lastPrimaryStudyPackage,
                breakRestricted = true
            )
            return
        }
        val browserFailClosed =
            rules.active &&
                WebProtectionConfig.requiresLocalVpn(this) &&
                WebProtectionConfig.isBrowserPackage(this, packageName) &&
                (
                    !WebProtectionConfig.isHealthy(this) ||
                        !WebProtectionConfig.isBrowserCovered(this, packageName)
                    )
        val decision = if (browserFailClosed) {
            FocusPolicyEngine.Decision(
                allowed = false,
                reason = FocusPolicyEngine.Reason.WEB_PROTECTION_UNAVAILABLE,
                policyVersion = FocusPolicyEngine.policyVersion
            )
        } else if (toolAllowed) {
            FocusPolicyEngine.Decision(
                allowed = true,
                reason = if (sessionTool) {
                    FocusPolicyEngine.Reason.SESSION_STUDY_TOOL
                } else {
                    FocusPolicyEngine.Reason.QUICK_STUDY_TOOL
                },
                policyVersion = FocusPolicyEngine.policyVersion
            )
        } else {
            FocusPolicyEngine.decide(
                packageName = packageName,
                snapshot = FocusPolicyEngine.Snapshot(
                    active = rules.active,
                    policy = rules.policy,
                    allowedPackages = rules.allowedPackages,
                    blockedPackages = rules.blockedPackages
                ),
                systemRequired = systemRequired,
                safetyApp = safetyApp,
                selectedBreakApp = selectedBreakApp
            )
        }

        debug(
            "POLICY_DECISION package=$packageName allowed=${decision.allowed} " +
                "reason=${decision.reason} policyVersion=${decision.policyVersion}"
        )

        if (!decision.allowed) {
            TrackerConfig.pauseStudyTimerForIdle(this)
            if (TrackerConfig.isAppStudyMode(this)) {
                focusPill.hide("blocked_app")
            } else {
                updateFocusPill(packageName, systemRequired, safetyApp)
            }
            if (
                decision.reason ==
                FocusPolicyEngine.Reason.WEB_PROTECTION_UNAVAILABLE
            ) {
                showWebBlock(
                    "protection.trustissue.local",
                    "protection_unavailable"
                )
                return
            }
            stopBlockedAppPlayback(packageName)
            showFocusGate(packageName, lastPrimaryStudyPackage)
            return
        }

        if (selectedBreakApp) {
            TrackerConfig.pauseStudyTimerForBreak(this)
            updateFocusPill(packageName, systemRequired, safetyApp)
            return
        }

        if (quickTool) {
            TrackerConfig.pauseStudyTimerForIdle(this)
            updateFocusPill(packageName, systemRequired, safetyApp)
            return
        }

        if (!TrackerConfig.isAppStudyMode(this)) {
            TrackerConfig.pauseStudyTimerForIdle(this)
            updateFocusPill(packageName, systemRequired, safetyApp)
            return
        }

        if (
            rules.active &&
            !systemRequired &&
            !safetyApp &&
            packageName != this.packageName &&
            (!appStudyAllowlist || primaryStudyApp || sessionTool)
        ) {
            if (primaryStudyApp) {
                lastPrimaryStudyPackage = packageName
            }
            TrackerConfig.resumeStudyTimerIfNeeded(
                this,
                packageName,
                TrackerConfig.appName(this, packageName)
            )
            updateFocusPill(packageName, systemRequired, safetyApp)
        } else {
            TrackerConfig.pauseStudyTimerForIdle(this)
            focusPill.hide("outside_app_study_scope")
        }
    }

    private fun updateFocusPill(
        packageName: String,
        systemRequired: Boolean,
        safetyApp: Boolean
    ) {
        val appStudy = TrackerConfig.isAppStudyMode(this)
        val selectedApp =
            rules.allowedPackages.contains(packageName) ||
                TrackerConfig.isSessionStudyTool(this, packageName) ||
                TrackerConfig.isPackageOnQuickStudyToolAccess(this, packageName) ||
                TrackerConfig.isPackageOnFocusBreak(this, packageName)
        val shouldShow = FocusPillVisibilityPolicy.shouldShow(
            sessionActive = rules.active,
            appStudy = appStudy,
            allowlistPolicy = rules.policy == "allowlist",
            selectedApp = selectedApp,
            systemRequired = systemRequired,
            safetyApp = safetyApp,
            ownApp = packageName == this.packageName
        )
        if (shouldShow) {
            focusPill.showFor(
                packageName,
                TrackerConfig.appName(this, packageName),
                global = !appStudy
            )
        } else {
            if (rules.active) {
                focusPill.hide("visibility_policy")
            } else {
                focusPill.destroy("session_inactive")
            }
        }
    }

    private fun showStudyToolGate(
        targetPackage: String,
        sourcePackage: String,
        toolKind: StudyToolResolver.ToolKind
    ) {
        val now = SystemClock.elapsedRealtime()
        if (
            targetPackage == lastToolGatePackage &&
            now - lastToolGateAtMs < gateThrottleMs
        ) {
            return
        }
        lastToolGatePackage = targetPackage
        lastToolGateAtMs = now

        val targetAppName = TrackerConfig.appName(this, targetPackage)
        val sourceAppName = TrackerConfig.appName(this, sourcePackage)
        runCatching {
            startActivity(
                Intent(this, StudyToolGateActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                    putExtra(StudyToolGateActivity.EXTRA_TARGET_PACKAGE, targetPackage)
                    putExtra(StudyToolGateActivity.EXTRA_TARGET_APP_NAME, targetAppName)
                    putExtra(StudyToolGateActivity.EXTRA_SOURCE_PACKAGE, sourcePackage)
                    putExtra(StudyToolGateActivity.EXTRA_SOURCE_APP_NAME, sourceAppName)
                    putExtra(StudyToolGateActivity.EXTRA_TOOL_KIND, toolKind.wireName)
                }
            )
        }.onSuccess {
            debug(
                "STUDY_TOOL_GATE_SHOWN target=$targetPackage source=$sourcePackage " +
                    "kind=${toolKind.wireName}"
            )
        }.onFailure {
            debug(
                "STUDY_TOOL_GATE_FAILED target=$targetPackage " +
                    "error=${it.javaClass.simpleName}"
            )
            stopBlockedAppPlayback(targetPackage)
            showFocusGate(targetPackage)
        }
    }

    private fun showWebBlock(domain: String, category: String) {
        val normalized = WebProtectionConfig.normalizeDomain(domain) ?: return
        val now = SystemClock.elapsedRealtime()
        if (
            normalized == lastWebBlockDomain &&
            now - lastWebBlockAtMs < 1_500L
        ) {
            return
        }
        lastWebBlockDomain = normalized
        lastWebBlockAtMs = now
        scheduleAudioFocusFallback()
        performGlobalAction(GLOBAL_ACTION_BACK)
        runCatching {
            startActivity(
                Intent(this, WebBlockActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                    putExtra(WebBlockActivity.EXTRA_DOMAIN, normalized)
                    putExtra(WebBlockActivity.EXTRA_CATEGORY, category)
                }
            )
        }.onFailure {
            debug(
                "WEB_BLOCK_GATE_FAILED category=${category.take(40)} " +
                    "error=${it.javaClass.simpleName}"
            )
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun showFocusGate(
        packageName: String,
        safeReturnPackage: String = "",
        breakRestricted: Boolean = false
    ) {
        val now = SystemClock.elapsedRealtime()
        if (packageName == lastGatePackage && now - lastGateAtMs < gateThrottleMs) return
        lastGatePackage = packageName
        lastGateAtMs = now

        val appName = TrackerConfig.appName(this, packageName)
        TrackerConfig.recordBlockedAttempt(this, packageName, appName)
        runCatching {
            startActivity(
                Intent(this, FocusGateActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                    putExtra(FocusGateActivity.EXTRA_PACKAGE_NAME, packageName)
                    putExtra(FocusGateActivity.EXTRA_APP_NAME, appName)
                    putExtra(
                        FocusGateActivity.EXTRA_SAFE_RETURN_PACKAGE,
                        safeReturnPackage
                    )
                    putExtra(
                        FocusGateActivity.EXTRA_BREAK_RESTRICTED,
                        breakRestricted
                    )
                }
            )
        }.onFailure {
            debug(
                "FOCUS_GATE_FAILED package=$packageName " +
                    "error=${it.javaClass.simpleName}"
            )
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun showYouTubeShortsGate(fingerprint: String) {
        if (shortsOverlay.isVisible()) return
        val now = SystemClock.elapsedRealtime()
        val identityMode =
            if (fingerprint == YouTubeShortsPassPolicy.scrollOnlyFingerprint) {
                "scroll_only"
            } else {
                "content"
            }
        if (
            fingerprint == lastShortsGateFingerprint &&
            now - lastShortsGateAtMs < shortsGateThrottleMs
        ) {
            debug(
                "SHORTS_GATE_SKIPPED reason=throttled " +
                    "ageMs=${now - lastShortsGateAtMs} identityMode=$identityMode"
            )
            return
        }
        lastShortsGateFingerprint = fingerprint
        lastShortsGateAtMs = now

        val shown = shortsOverlay.show(
            fingerprint = fingerprint,
            onWatchOnlyThis = {
                if (
                    YouTubeShortsPassStore.grant(
                        context = this,
                        fingerprint = fingerprint,
                        itemSequence = shortsItemSequence
                    )
                ) {
                    shortsEvaluatedSequence = shortsItemSequence
                    lastShortsGateFingerprint = ""
                    lastShortsGateAtMs = 0L
                    shortsOverlay.hide()
                    mediaController.release()
                    debug(
                        "SHORTS_PASS_GRANTED identityMode=$identityMode " +
                            "surfaceActive=$shortsSurfaceActive " +
                            "passSequence=$shortsItemSequence " +
                            "session=$shortFormDiagnosticSession"
                    )
                } else {
                    debug("SHORTS_PASS_GRANT_FAILED identityMode=$identityMode")
                    Toast.makeText(
                        this,
                        "This Short could not be verified. Please leave Shorts.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onLeaveSurface = {
                clearYouTubeShortsPass("user_left_shorts")
                val leftShorts = performGlobalAction(GLOBAL_ACTION_BACK)
                if (!leftShorts) {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
                debug("SHORTS_LEAVE_REQUESTED back=$leftShorts")
            }
        )
        if (shown) {
            mediaController.holdAudioFocus()
            debug(
                "SHORTS_OVERLAY_SHOWN identityMode=$identityMode " +
                    "surfaceActive=$shortsSurfaceActive"
            )
        } else {
            debug("SHORTS_OVERLAY_UNAVAILABLE")
            val leftShorts = performGlobalAction(GLOBAL_ACTION_BACK)
            if (!leftShorts) {
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }
    }

    private fun showInstagramReelsGate(fingerprint: String) {
        if (reelsOverlay.isVisible()) return
        val now = SystemClock.elapsedRealtime()
        val identityMode =
            if (fingerprint == YouTubeShortsPassPolicy.scrollOnlyFingerprint) {
                "scroll_only"
            } else {
                "content"
            }
        if (
            fingerprint == lastReelsGateFingerprint &&
            now - lastReelsGateAtMs < shortsGateThrottleMs
        ) {
            debug(
                "REELS_GATE_SKIPPED reason=throttled " +
                    "ageMs=${now - lastReelsGateAtMs} identityMode=$identityMode"
            )
            return
        }
        lastReelsGateFingerprint = fingerprint
        lastReelsGateAtMs = now

        val shown = reelsOverlay.show(
            fingerprint = fingerprint,
            surfaceName = "Instagram Reels",
            itemName = "Reel",
            leaveLabel = "Leave Reels",
            onWatchOnlyThis = {
                if (
                    InstagramReelsPassStore.grant(
                        context = this,
                        fingerprint = fingerprint,
                        itemSequence = reelsItemSequence
                    )
                ) {
                    reelsEvaluatedSequence = reelsItemSequence
                    lastReelsGateFingerprint = ""
                    lastReelsGateAtMs = 0L
                    reelsOverlay.hide()
                    mediaController.release()
                    debug(
                        "REELS_PASS_GRANTED identityMode=$identityMode " +
                            "surfaceActive=$reelsSurfaceActive " +
                            "passSequence=$reelsItemSequence " +
                            "session=$shortFormDiagnosticSession"
                    )
                } else {
                    debug("REELS_PASS_GRANT_FAILED identityMode=$identityMode")
                    Toast.makeText(
                        this,
                        "This Reel could not be verified. Please leave Reels.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onLeaveSurface = {
                clearInstagramReelsPass("user_left_reels")
                val leftReels = performGlobalAction(GLOBAL_ACTION_BACK)
                if (!leftReels) {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
                debug("REELS_LEAVE_REQUESTED back=$leftReels")
            }
        )
        if (shown) {
            mediaController.holdAudioFocus()
            debug(
                "REELS_OVERLAY_SHOWN identityMode=$identityMode " +
                    "surfaceActive=$reelsSurfaceActive"
            )
        } else {
            debug("REELS_OVERLAY_UNAVAILABLE")
            val leftReels = performGlobalAction(GLOBAL_ACTION_BACK)
            if (!leftReels) {
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }
    }

    private fun returnFromQuickStudyTool(
        targetPackage: String,
        sourcePackage: String
    ) {
        focusPill.hide("quick_tool_end")
        TrackerConfig.pauseStudyTimerForIdle(this)
        if (targetPackage.isBlank() || currentForegroundPackage != targetPackage) return
        returnToStudySource(targetPackage, sourcePackage, "quick_tool_end")
    }

    private fun returnToStudySource(
        targetPackage: String,
        sourcePackage: String,
        reason: String
    ) {
        focusPill.hide("return_to_study")
        TrackerConfig.pauseStudyTimerForIdle(this)
        val sentHome = performGlobalAction(GLOBAL_ACTION_HOME)
        val launch = packageManager.getLaunchIntentForPackage(sourcePackage)?.apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        val openedSource = launch != null &&
            runCatching { startActivity(launch) }.isSuccess
        debug(
            "STUDY_RETURN reason=$reason target=$targetPackage source=$sourcePackage " +
                "sentHome=$sentHome openedSource=$openedSource"
        )
    }

    /**
     * Give the blocked app time to pause naturally after the opaque Focus Gate
     * replaces it. If playback remains active, briefly request audio focus.
     * No global media-key command is sent, so unrelated music is not left
     * permanently paused.
     */
    private fun stopBlockedAppPlayback(packageName: String) {
        val fallbackScheduled = scheduleAudioFocusFallback()
        debug(
            "BLOCK_ENFORCED package=$packageName directGate=true " +
                "audioFocusFallbackScheduled=$fallbackScheduled"
        )
    }

    private fun scheduleAudioFocusFallback(): Boolean {
        return mediaController.scheduleAudioFocusFallback()
    }

    override fun onSharedPreferenceChanged(
        sharedPreferences: SharedPreferences?,
        key: String?
    ) {
        if (
            sharedPreferences == null ||
            key == null ||
            (
                !key.startsWith("flutter.study") &&
                    key != "flutter.youtubeShortsBlockingEnabled" &&
                    key != "flutter.instagramReelsBlockingEnabled"
                )
        ) {
            return
        }
        val previousRules = rules
        runCatching {
            rules = loadRules(sharedPreferences)
            debug(
                "RULES_REFRESH key=$key focusActive=${rules.active} " +
                    "shorts=${previousRules.youtubeShortsBlocking}->" +
                    "${rules.youtubeShortsBlocking} " +
                    "reels=${previousRules.instagramReelsBlocking}->" +
                    "${rules.instagramReelsBlocking} " +
                    "foreground=$currentForegroundPackage"
            )
            if (!rules.youtubeShortsBlocking) {
                clearYouTubeShortsPass("setting_disabled")
            } else if (
                key == "flutter.youtubeShortsBlockingEnabled" &&
                currentForegroundPackage == youtubePackage
            ) {
                scheduleShortsInspection(
                    pagerScroll = false,
                    eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                )
            }
            if (!rules.instagramReelsBlocking) {
                clearInstagramReelsPass("setting_disabled")
            } else if (
                key == "flutter.instagramReelsBlockingEnabled" &&
                currentForegroundPackage in instagramPackages
            ) {
                scheduleReelsInspection(
                    pagerScroll = false,
                    eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                )
            }
            if (!rules.active) {
                lastPrimaryStudyPackage = ""
                focusPill.destroy("session_disabled")
                return@runCatching
            }
            val foreground = currentForegroundPackage
            if (foreground.isNotEmpty()) evaluate(foreground)
        }.onFailure {
            rules = previousRules
            debug(
                "PREFERENCE_REFRESH_FAILED error=${it.javaClass.simpleName} " +
                    "previousRulesPreserved=true"
            )
        }
    }

    override fun onInterrupt() {
        // Android may call this repeatedly for temporary feedback/window
        // interruptions. It is not a session or foreground-app transition.
        if (!ProtectionAccess.markAccessibilityOperational()) return
        debug("ACCESSIBILITY_INTERRUPT_IGNORED stablePackage=$currentForegroundPackage")
    }

    override fun onDestroy() {
        handler.removeCallbacks(accessibilityHeartbeat)
        ProtectionAccess.clearAccessibilityOperational()
        runCatching { TrackerConfig.pauseStudyTimerForIdle(this) }
        runCatching { focusPill.destroy("service_destroyed") }
        handler.removeCallbacks(helperTimeout)
        runCatching { mediaController.release() }
        runCatching {
            flutterPrefs?.unregisterOnSharedPreferenceChangeListener(this)
        }
        flutterPrefs = null
        clearYouTubeShortsPass("service_destroyed")
        clearInstagramReelsPass("service_destroyed")
        unregisterScreenReceiver()
        if (
            (TrackerConfig.isStudyModeEnabled(this) ||
                TrackerConfig.hasDailyAppLimits(this)) &&
            ProtectionAccess.compatibleModeReady(this)
        ) {
            FocusUsageMonitorService.start(this)
        }
        super.onDestroy()
    }

    private fun loadRules(prefs: SharedPreferences): Rules {
        return Rules(
            active = prefs.getBoolean("flutter.studyModeEnabled", false),
            policy = prefs.getString("flutter.studyModePolicy", "allowlist") ?: "allowlist",
            allowedPackages = TrackerConfig.studyAllowedPackages(this),
            blockedPackages = TrackerConfig.studyBlockedPackages(this),
            youtubeShortsBlocking = prefs.getBoolean(
                "flutter.youtubeShortsBlockingEnabled",
                false
            ),
            instagramReelsBlocking = prefs.getBoolean(
                "flutter.instagramReelsBlockingEnabled",
                false
            )
        )
    }

    private fun isSystemRequired(packageName: String): Boolean {
        if (packageName == this.packageName) return true
        if (homePackages.contains(packageName)) return true
        if (inputMethodPackages.contains(packageName)) return true
        return packageName == "android" ||
            packageName == "com.android.systemui" ||
            packageName.contains("permissioncontroller")
    }

    private fun isSafetyApp(packageName: String): Boolean {
        return fixedSafetyPackages.contains(packageName) ||
            dialAndMessagePackages.contains(packageName)
    }

    private fun isContextualSystemHelper(packageName: String): Boolean {
        if (homePackages.contains(packageName)) return false
        if (inputMethodPackages.contains(packageName)) return false
        return studyToolResolver.isTrustedSystemHelper(packageName) ||
            packageName == "android" ||
            packageName == "com.android.systemui" ||
            packageName.contains("permissioncontroller") ||
            packageName.contains("credentialmanager")
    }

    private fun clearActiveHelper() {
        handler.removeCallbacks(helperTimeout)
        activeHelperPackage = ""
        activeHelperSourcePackage = ""
        activeHelperUntilElapsedMs = 0L
    }

    private fun loadHomePackages(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return runCatching {
            packageManager.queryIntentActivities(intent, 0)
                .mapNotNull { it.activityInfo?.packageName }
                .toSet()
        }.getOrDefault(emptySet())
    }

    private fun loadDialAndMessagePackages(): Set<String> {
        val packages = linkedSetOf<String>()
        runCatching {
            val telecom = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecom.defaultDialerPackage?.takeIf(String::isNotBlank)?.let(packages::add)
        }
        runCatching {
            Telephony.Sms.getDefaultSmsPackage(this)
                ?.takeIf(String::isNotBlank)
                ?.let(packages::add)
        }
        return packages
    }

    private fun loadInputMethodPackages(): Set<String> {
        val inputMethodManager =
            getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                ?: return emptySet()
        return runCatching {
            inputMethodManager.enabledInputMethodList
                .mapNotNull { inputMethod ->
                    inputMethod.packageName.trim().takeIf { it.isNotEmpty() }
                }
                .toSet()
        }.onFailure {
            // Keyboard discovery is optional. An OEM failure must never make
            // Accessibility or the focus blocker inactive.
            debug("INPUT_METHOD_DISCOVERY_FAILED error=${it.javaClass.simpleName}")
        }.getOrDefault(emptySet())
    }

    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SHUTDOWN)
            addAction(FocusBreakTimeoutReceiver.ACTION_FOCUS_BREAK_ENDED)
            addAction(FocusSessionTimeoutReceiver.ACTION_FOCUS_SESSION_ENDED)
            addAction(StudyToolTimeoutReceiver.ACTION_QUICK_TOOL_ENDED)
            addAction(WebProtectionConfig.actionStateChanged)
            addAction(WebProtectionConfig.actionDomainBlocked)
        }
        runCatching {
            ContextCompat.registerReceiver(
                this,
                screenReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            screenReceiverRegistered = true
        }.onFailure {
            debug("SCREEN_RECEIVER_REGISTER_FAILED error=${it.javaClass.simpleName}")
        }
    }

    private fun unregisterScreenReceiver() {
        if (!screenReceiverRegistered) return
        runCatching { unregisterReceiver(screenReceiver) }
        screenReceiverRegistered = false
    }

    private fun debug(message: String) {
        val tag = if (
            message.startsWith("SHORTS_") ||
            message.startsWith("REELS_")
        ) {
            "ShortFormDiagnostics"
        } else {
            "FocusAccessibility"
        }
        TrustIssueDebugLog.append(this, tag, "I", message.take(700))
    }
}
