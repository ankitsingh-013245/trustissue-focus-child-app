package com.trustissue.child

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat

/**
 * Compatibility-mode foreground observer.
 *
 * It reads only Android's app-usage transition log (package names and times).
 * It never reads another app's screen, text, controls, notifications, or data.
 */
class FocusUsageMonitorService :
    Service(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private data class Rules(
        val active: Boolean = false,
        val policy: String = "allowlist",
        val allowedPackages: Set<String> = emptySet(),
        val blockedPackages: Set<String> = emptySet(),
        val dailyAppLimits: Map<String, Int> = emptyMap()
    )

    private data class ForegroundObservation(
        val packageName: String,
        val hadLifecycleEvents: Boolean
    )

    companion object {
        private const val flutterPrefsName = "FlutterSharedPreferences"
        private const val notificationChannelId = "focus_compatible_protection"
        private const val notificationId = 47011
        private const val initialEventLookbackMs = 24 * 60 * 60 * 1000L
        private const val gateThrottleMs = 1_100L
        private const val contextualHelperAccessMs = 2 * 60 * 1000L

        private val fixedSafetyPackages = setOf(
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

        fun start(context: Context): Boolean {
            if (!ProtectionAccess.compatibleModeReady(context)) return false
            return runCatching {
                context.startForegroundService(
                    Intent(context, FocusUsageMonitorService::class.java)
                )
                true
            }.getOrDefault(false)
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, FocusUsageMonitorService::class.java))
            }
        }
    }

    private val workerThread = HandlerThread("FocusUsageMonitor")
    private lateinit var worker: Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    private var flutterPrefs: SharedPreferences? = null
    @Volatile
    private var rules = Rules()
    private var homePackages: Set<String> = emptySet()
    private var inputMethodPackages: Set<String> = emptySet()
    private var dialAndMessagePackages: Set<String> = emptySet()
    @Volatile
    private var currentForegroundPackage = ""
    private var previousForegroundPackage = ""
    private var lastGatePackage = ""
    private var lastGateAtMs = 0L
    private var lastLimitCheckAtMs = 0L
    private var unchangedPollCount = 0
    @Volatile
    private var lastUsageEventAtMs = 0L
    private var lastToolGatePackage = ""
    private var lastToolGateAtMs = 0L
    private var lastPrimaryStudyPackage = ""
    private var activeHelperPackage = ""
    private var activeHelperSourcePackage = ""
    private var activeHelperUntilElapsedMs = 0L
    @Volatile
    private var screenInteractive = true
    private var advancedModeWasActive = false
    private var receiverRegistered = false
    private val studyToolResolver by lazy { StudyToolResolver(this) }
    private val mediaController by lazy { BlockedMediaController(this) }
    private val blockOverlay by lazy { FocusBlockOverlayController(this) }
    private val focusPill by lazy {
        FocusPillController(
            this,
            onQuickToolEnded = ::returnFromQuickStudyTool,
            windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        )
    }
    private val helperTimeout = Runnable {
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
                returnToStudySource(
                    helperPackage,
                    sourcePackage,
                    "helper_timeout"
                )
            }
        }
    }
    private val poll = object : Runnable {
        override fun run() {
            val nextDelay = runCatching { pollOnce() }
                .onFailure {
                    debug("USAGE_POLL_FAILED error=${it.javaClass.simpleName}")
                }
                .getOrDefault(UsagePollingPolicy.activePollIntervalMs)
            if (::worker.isInitialized) worker.postDelayed(this, nextDelay)
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenInteractive = false
                    unchangedPollCount = 0
                    TrackerConfig.pauseStudyTimerForIdle(this@FocusUsageMonitorService)
                    focusPill.hide("screen_off")
                    blockOverlay.hide()
                    clearActiveHelper()
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    screenInteractive = true
                    currentForegroundPackage = ""
                    lastUsageEventAtMs = 0L
                    unchangedPollCount = 0
                    worker.removeCallbacks(poll)
                    worker.post(poll)
                }
                FocusGateActivity.ACTION_GATE_VISIBLE -> {
                    // Remove the emergency overlay only after our Activity
                    // actually owns the window, so there is never a flash of
                    // the blocked app and the gate animation stays visible.
                    blockOverlay.hide()
                }
                FocusBreakTimeoutReceiver.ACTION_FOCUS_BREAK_ENDED,
                FocusSessionTimeoutReceiver.ACTION_FOCUS_SESSION_ENDED,
                WebProtectionConfig.actionStateChanged -> {
                    reevaluateCurrent()
                }
                StudyToolTimeoutReceiver.ACTION_QUICK_TOOL_ENDED -> {
                    val targetPackage = intent.getStringExtra(
                        StudyToolTimeoutReceiver.EXTRA_TARGET_PACKAGE
                    ).orEmpty()
                    val sourcePackage = intent.getStringExtra(
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
                        reevaluateCurrent()
                    }
                }
                WebProtectionConfig.actionDomainBlocked -> {
                    val domain = intent.getStringExtra(
                        WebProtectionConfig.extraDomain
                    ).orEmpty()
                    if (
                        rules.active &&
                        WebProtectionConfig.isBrowserPackage(
                            this@FocusUsageMonitorService,
                            currentForegroundPackage
                        )
                    ) {
                        showWebBlock(
                            domain,
                            intent.getStringExtra(
                                WebProtectionConfig.extraCategory
                            ).orEmpty()
                        )
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(notificationId, buildNotification())
        workerThread.start()
        worker = Handler(workerThread.looper)
        homePackages = loadHomePackages()
        inputMethodPackages = loadInputMethodPackages()
        dialAndMessagePackages = loadDialAndMessagePackages()
        screenInteractive =
            (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        flutterPrefs = getSharedPreferences(flutterPrefsName, Context.MODE_PRIVATE).also {
            rules = loadRules(it)
            it.registerOnSharedPreferenceChangeListener(this)
        }
        registerStateReceiver()
        worker.post(poll)
        debug("USAGE_MONITOR_STARTED policyVersion=${FocusPolicyEngine.policyVersion}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (
            !TrackerConfig.isStudyModeEnabled(this) &&
            !TrackerConfig.hasDailyAppLimits(this)
        ) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!ProtectionAccess.compatibleModeReady(this)) {
            debug("USAGE_MONITOR_STOP missing_special_access=true")
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun pollOnce(): Long {
        val focusActive = rules.active && TrackerConfig.isStudyModeEnabled(this)
        if (!focusActive && rules.dailyAppLimits.isEmpty()) {
            mainHandler.post { focusPill.destroy("session_inactive") }
            stopSelf()
            return UsagePollingPolicy.standbyPollIntervalMs
        }
        if (!ProtectionAccess.compatibleModeReady(this)) {
            mainHandler.post { focusPill.destroy("permission_missing") }
            TrackerConfig.pauseStudyTimerForIdle(this)
            stopSelf()
            return UsagePollingPolicy.standbyPollIntervalMs
        }
        if (!screenInteractive) return UsagePollingPolicy.screenOffPollIntervalMs

        // Accessibility is optional advanced mode. When enabled it owns
        // enforcement because its window events arrive faster. This monitor
        // remains warm so it can take over if Accessibility is later disabled.
        if (
            ProtectionAccess.isAccessibilityOperational(this) &&
            rules.dailyAppLimits.isEmpty()
        ) {
            if (!advancedModeWasActive) {
                advancedModeWasActive = true
                mainHandler.post { focusPill.destroy("advanced_mode_active") }
            }
            unchangedPollCount = 0
            return UsagePollingPolicy.standbyPollIntervalMs
        }
        if (advancedModeWasActive) {
            advancedModeWasActive = false
            previousForegroundPackage = ""
            currentForegroundPackage = ""
            lastUsageEventAtMs = 0L
            unchangedPollCount = 0
        }

        val observation = latestForegroundPackage()
        val observed = observation.packageName
        if (observed.isBlank()) {
            val foregroundChanged = currentForegroundPackage.isNotBlank()
            if (currentForegroundPackage.isNotBlank()) {
                previousForegroundPackage = currentForegroundPackage
                currentForegroundPackage = ""
            }
            return nextAdaptivePollDelay(
                foregroundChanged = foregroundChanged,
                hadLifecycleEvents = observation.hadLifecycleEvents
            )
        }
        if (
            FocusForegroundEventPolicy.isTransientSystemWindowPackage(observed) &&
            currentForegroundPackage.isNotBlank()
        ) {
            return nextAdaptivePollDelay(
                foregroundChanged = false,
                hadLifecycleEvents = observation.hadLifecycleEvents
            )
        }
        if (
            inputMethodPackages.contains(observed) &&
            currentForegroundPackage.isNotBlank()
        ) {
            return nextAdaptivePollDelay(
                foregroundChanged = false,
                hadLifecycleEvents = observation.hadLifecycleEvents
            )
        }
        val foregroundChanged = observed != currentForegroundPackage
        if (foregroundChanged) {
            previousForegroundPackage = currentForegroundPackage
            currentForegroundPackage = observed
            lastLimitCheckAtMs = SystemClock.elapsedRealtime()
            val previous = previousForegroundPackage
            mainHandler.post { evaluate(observed, previous) }
        } else if (rules.dailyAppLimits.containsKey(observed)) {
            val nowElapsed = SystemClock.elapsedRealtime()
            if (nowElapsed - lastLimitCheckAtMs >= 2_500L) {
                lastLimitCheckAtMs = nowElapsed
                val previous = previousForegroundPackage
                mainHandler.post { evaluate(observed, previous) }
            }
        }
        return nextAdaptivePollDelay(
            foregroundChanged = foregroundChanged,
            hadLifecycleEvents = observation.hadLifecycleEvents
        )
    }

    private fun nextAdaptivePollDelay(
        foregroundChanged: Boolean,
        hadLifecycleEvents: Boolean
    ): Long {
        unchangedPollCount = if (foregroundChanged || hadLifecycleEvents) {
            0
        } else {
            (unchangedPollCount + 1).coerceAtMost(Int.MAX_VALUE)
        }
        return UsagePollingPolicy.nextDelay(
            foregroundChanged = foregroundChanged,
            hadLifecycleEvents = hadLifecycleEvents,
            unchangedPollCount = unchangedPollCount,
            hasDailyLimits = rules.dailyAppLimits.isNotEmpty()
        )
    }

    private fun latestForegroundPackage(): ForegroundObservation {
        val usage = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        if (lastUsageEventAtMs > now) lastUsageEventAtMs = 0L
        val queryStart = if (lastUsageEventAtMs > 0L) {
            (lastUsageEventAtMs + 1L).coerceAtMost(now)
        } else {
            (now - initialEventLookbackMs).coerceAtLeast(0L)
        }
        val events = usage.queryEvents(queryStart, now)
        val event = UsageEvents.Event()
        val lifecycleEvents = mutableListOf<FocusUsageLifecycleEvent>()
        var latestAt = lastUsageEventAtMs
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            latestAt = maxOf(latestAt, event.timeStamp)
            val eventPackage = event.packageName.orEmpty().trim()
            if (
                eventPackage.isNotEmpty() &&
                !FocusForegroundEventPolicy.isTransientSystemWindowPackage(eventPackage) &&
                !inputMethodPackages.contains(eventPackage)
            ) {
                lifecycleEvents += FocusUsageLifecycleEvent(
                    packageName = eventPackage,
                    eventType = event.eventType
                )
            }
        }
        lastUsageEventAtMs = latestAt
        return ForegroundObservation(
            packageName = FocusUsageEventPolicy.resolve(
                stablePackage = currentForegroundPackage,
                events = lifecycleEvents
            ),
            hadLifecycleEvents = lifecycleEvents.isNotEmpty()
        )
    }

    private fun evaluate(packageName: String, previousPackage: String) {
        if (!ProtectionAccess.compatibleModeReady(this)) {
            focusPill.destroy("stale_observation")
            blockOverlay.hide()
            return
        }
        val focusActive = rules.active && TrackerConfig.isStudyModeEnabled(this)
        val systemRequired = isSystemRequired(packageName)
        val safetyApp = isSafetyApp(packageName)
        val appLimitReached = !systemRequired &&
            !safetyApp &&
            packageName != this.packageName &&
            rules.dailyAppLimits.containsKey(packageName) &&
            TrackerConfig.isDailyAppLimitReached(this, packageName)
        if (appLimitReached) {
            focusPill.hide("daily_limit_reached")
            block(packageName, appLimitReached = true)
            return
        }
        if (!focusActive || ProtectionAccess.isAccessibilityOperational(this)) {
            if (!focusActive) focusPill.destroy("focus_inactive_limits_ready")
            blockOverlay.hide()
            return
        }
        if (TrackerConfig.completeStudySessionIfFinished(this)) {
            rules = rules.copy(active = false)
            focusPill.destroy("session_complete")
            if (rules.dailyAppLimits.isEmpty()) stopSelf()
            return
        }

        val appStudyAllowlist =
            TrackerConfig.isAppStudyMode(this) &&
                rules.policy == "allowlist"
        val primaryStudyApp =
            appStudyAllowlist && rules.allowedPackages.contains(packageName)
        val previousPrimaryStudyApp =
            appStudyAllowlist && rules.allowedPackages.contains(previousPackage)

        val quickTarget = TrackerConfig.quickStudyToolPackage(this)
        if (
            quickTarget.isNotBlank() &&
            TrackerConfig.quickStudyToolRemainingMs(this) <= 0L
        ) {
            val sourcePackage = TrackerConfig.quickStudyToolSourcePackage(this)
            TrackerConfig.endQuickStudyToolAccess(this, "foreground_expired")
            if (packageName == quickTarget) {
                returnFromQuickStudyTool(quickTarget, sourcePackage)
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
            activeHelperSourcePackage.isNotBlank() &&
            SystemClock.elapsedRealtime() < activeHelperUntilElapsedMs
        ) {
            activeHelperSourcePackage
        } else {
            ""
        }
        val handoffSourcePackage = when {
            previousPrimaryStudyApp -> previousPackage
            helperChainSource.isNotBlank() &&
                rules.allowedPackages.contains(helperChainSource) ->
                helperChainSource
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
            activeHelperSourcePackage =
                if (continuingHelper) helperChainSource else previousPackage
            if (!continuingHelper || activeHelperUntilElapsedMs <= 0L) {
                activeHelperUntilElapsedMs =
                    SystemClock.elapsedRealtime() + contextualHelperAccessMs
            }
            mainHandler.removeCallbacks(helperTimeout)
            mainHandler.postDelayed(
                helperTimeout,
                (activeHelperUntilElapsedMs - SystemClock.elapsedRealtime())
                    .coerceAtLeast(1L)
            )
            TrackerConfig.pauseStudyTimerForIdle(this)
            blockOverlay.hide()
            val pillSource =
                activeHelperSourcePackage.ifBlank { previousPackage }
            if (pillSource.isNotBlank()) {
                focusPill.showFor(
                    pillSource,
                    TrackerConfig.appName(this, pillSource),
                    global = false
                )
            }
            debug(
                "USAGE_STUDY_HELPER package=$packageName " +
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
            showStudyToolGate(
                packageName,
                handoffSourcePackage,
                toolKind
            )
            return
        }
        if (packageName != activeHelperPackage) clearActiveHelper()

        blockOverlay.hide()
        val selectedBreakApp =
            TrackerConfig.isPackageOnFocusBreak(this, packageName)
        val breakActive = TrackerConfig.isFocusBreakActive(this)
        if (
            breakActive &&
            !selectedBreakApp &&
            !systemRequired &&
            !safetyApp &&
            packageName != this.packageName
        ) {
            TrackerConfig.pauseStudyTimerForBreak(this)
            focusPill.hide("break_app_not_selected")
            block(packageName, breakRestricted = true)
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
        val decision = when {
            browserFailClosed -> FocusPolicyEngine.Decision(
                allowed = false,
                reason = FocusPolicyEngine.Reason.WEB_PROTECTION_UNAVAILABLE
            )
            sessionTool || quickTool -> FocusPolicyEngine.Decision(
                allowed = true,
                reason = if (sessionTool) {
                    FocusPolicyEngine.Reason.SESSION_STUDY_TOOL
                } else {
                    FocusPolicyEngine.Reason.QUICK_STUDY_TOOL
                }
            )
            else -> FocusPolicyEngine.decide(
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
            "USAGE_POLICY package=$packageName previous=$previousPackage " +
                "allowed=${decision.allowed} reason=${decision.reason}"
        )

        if (!decision.allowed) {
            TrackerConfig.pauseStudyTimerForIdle(this)
            focusPill.hide("blocked_app")
            if (
                decision.reason ==
                FocusPolicyEngine.Reason.WEB_PROTECTION_UNAVAILABLE
            ) {
                showWebBlock(
                    "protection.trustissue.local",
                    "protection_unavailable"
                )
            } else {
                block(packageName)
            }
            return
        }

        if (selectedBreakApp) {
            TrackerConfig.pauseStudyTimerForBreak(this)
            updateFocusPill(packageName, systemRequired, safetyApp)
            return
        }
        if (quickTool || !TrackerConfig.isAppStudyMode(this)) {
            TrackerConfig.pauseStudyTimerForIdle(this)
            updateFocusPill(packageName, systemRequired, safetyApp)
            return
        }
        if (
            !systemRequired &&
            !safetyApp &&
            packageName != this.packageName &&
            (primaryStudyApp || sessionTool)
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
            focusPill.hide("visibility_policy")
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
        blockOverlay.hide()
        val overlayShown = false
        runCatching {
            startActivity(
                Intent(this, StudyToolGateActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                    putExtra(
                        StudyToolGateActivity.EXTRA_TARGET_PACKAGE,
                        targetPackage
                    )
                    putExtra(
                        StudyToolGateActivity.EXTRA_TARGET_APP_NAME,
                        targetAppName
                    )
                    putExtra(
                        StudyToolGateActivity.EXTRA_SOURCE_PACKAGE,
                        sourcePackage
                    )
                    putExtra(
                        StudyToolGateActivity.EXTRA_SOURCE_APP_NAME,
                        sourceAppName
                    )
                    putExtra(
                        StudyToolGateActivity.EXTRA_TOOL_KIND,
                        toolKind.wireName
                    )
                }
            )
        }.onSuccess {
            mainHandler.postDelayed({ blockOverlay.hide() }, 300L)
            debug(
                "USAGE_TOOL_GATE target=$targetPackage source=$sourcePackage " +
                    "kind=${toolKind.wireName} overlayShown=$overlayShown"
            )
        }.onFailure {
            debug(
                "USAGE_TOOL_GATE_FAILED target=$targetPackage " +
                    "error=${it.javaClass.simpleName}"
            )
            if (!overlayShown) block(targetPackage)
        }
    }

    private fun returnFromQuickStudyTool(
        targetPackage: String,
        sourcePackage: String
    ) {
        focusPill.hide("quick_tool_end")
        TrackerConfig.pauseStudyTimerForIdle(this)
        if (
            targetPackage.isBlank() ||
            currentForegroundPackage != targetPackage
        ) {
            return
        }
        returnToStudySource(targetPackage, sourcePackage, "quick_tool_end")
    }

    private fun returnToStudySource(
        targetPackage: String,
        sourcePackage: String,
        reason: String
    ) {
        blockOverlay.hide()
        focusPill.hide("return_to_study")
        TrackerConfig.pauseStudyTimerForIdle(this)
        val launch = packageManager.getLaunchIntentForPackage(sourcePackage)
            ?.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        val openedSource =
            launch != null && runCatching { startActivity(launch) }.isSuccess
        if (!openedSource) {
            val homeIntent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { startActivity(homeIntent) }
        }
        debug(
            "USAGE_STUDY_RETURN reason=$reason target=$targetPackage " +
                "source=$sourcePackage openedSource=$openedSource"
        )
    }

    private fun block(
        packageName: String,
        breakRestricted: Boolean = false,
        appLimitReached: Boolean = false
    ) {
        val now = SystemClock.elapsedRealtime()
        if (
            packageName == lastGatePackage &&
            now - lastGateAtMs < gateThrottleMs
        ) {
            return
        }
        lastGatePackage = packageName
        lastGateAtMs = now
        val appName = TrackerConfig.appName(this, packageName)
        if (TrackerConfig.isStudyModeEnabled(this) && !appLimitReached) {
            TrackerConfig.recordBlockedAttempt(this, packageName, appName)
        }
        val motivationIndex = GateMotivation.indexFor(packageName, now)
        val overlayShown = blockOverlay.show(
            appName = appName,
            appPackage = packageName,
            appLimitReached = appLimitReached,
            motivationIndex = motivationIndex,
            onReturnToFocus = ::returnToSafeDestination
        )
        val audioFallbackScheduled =
            mediaController.scheduleAudioFocusFallback()
        runCatching {
            val gateIntent = Intent(this, FocusGateActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                putExtra(FocusGateActivity.EXTRA_PACKAGE_NAME, packageName)
                putExtra(FocusGateActivity.EXTRA_APP_NAME, appName)
                putExtra(
                    FocusGateActivity.EXTRA_SAFE_RETURN_PACKAGE,
                    lastPrimaryStudyPackage
                )
                putExtra(
                    FocusGateActivity.EXTRA_BREAK_RESTRICTED,
                    breakRestricted
                )
                putExtra(
                    FocusGateActivity.EXTRA_APP_LIMIT_REACHED,
                    appLimitReached
                )
                putExtra(
                    FocusGateActivity.EXTRA_MOTIVATION_INDEX,
                    motivationIndex
                )
            }
            if (overlayShown) {
                startActivity(
                    gateIntent,
                    ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle()
                )
            } else {
                startActivity(gateIntent)
            }
        }.onSuccess {
            debug(
                "USAGE_BLOCK_GATE package=$packageName " +
                    "overlayShown=$overlayShown " +
                    "audioFallbackScheduled=$audioFallbackScheduled"
            )
        }.onFailure {
            debug(
                "USAGE_BLOCK_GATE_FAILED package=$packageName " +
                    "error=${it.javaClass.simpleName}"
            )
            if (!overlayShown) returnToSafeDestination()
        }
    }

    private fun returnToSafeDestination() {
        val sourceIntent = lastPrimaryStudyPackage
            .takeIf(String::isNotBlank)
            ?.let(packageManager::getLaunchIntentForPackage)
            ?.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        if (sourceIntent != null && runCatching { startActivity(sourceIntent) }.isSuccess) {
            return
        }
        val homeIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(homeIntent) }
    }

    private fun returnToTrustIssue() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        if (runCatching { startActivity(intent) }.isFailure) {
            returnToSafeDestination()
        }
    }

    private fun showWebBlock(domain: String, category: String) {
        val normalized = WebProtectionConfig.normalizeDomain(domain) ?: return
        mediaController.scheduleAudioFocusFallback()
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
            debug("USAGE_WEB_GATE_FAILED error=${it.javaClass.simpleName}")
        }
    }

    override fun onSharedPreferenceChanged(
        sharedPreferences: SharedPreferences?,
        key: String?
    ) {
        if (
            sharedPreferences == null ||
            key == null ||
            !key.startsWith("flutter.study") &&
            key != "flutter.dailyAppLimitsJson"
        ) {
            return
        }
        rules = loadRules(sharedPreferences)
        unchangedPollCount = 0
        if (!rules.active && rules.dailyAppLimits.isEmpty()) {
            mainHandler.post { blockOverlay.hide() }
            mainHandler.post { focusPill.destroy("session_disabled") }
            stopSelf()
        } else if (::worker.isInitialized) {
            worker.removeCallbacks(poll)
            worker.post(poll)
        }
    }

    private fun loadRules(prefs: SharedPreferences): Rules = Rules(
        active = prefs.getBoolean("flutter.studyModeEnabled", false),
        policy = prefs.getString("flutter.studyModePolicy", "allowlist")
            ?: "allowlist",
        allowedPackages = TrackerConfig.studyAllowedPackages(this),
        blockedPackages = TrackerConfig.studyBlockedPackages(this),
        dailyAppLimits = TrackerConfig.dailyAppLimits(this)
    )

    private fun isSystemRequired(packageName: String): Boolean =
        packageName == this.packageName ||
            homePackages.contains(packageName) ||
            inputMethodPackages.contains(packageName) ||
            packageName == "android" ||
            packageName == "com.android.systemui" ||
            packageName.contains("permissioncontroller")

    private fun isSafetyApp(packageName: String): Boolean =
        fixedSafetyPackages.contains(packageName) ||
            dialAndMessagePackages.contains(packageName)

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
        mainHandler.removeCallbacks(helperTimeout)
        activeHelperPackage = ""
        activeHelperSourcePackage = ""
        activeHelperUntilElapsedMs = 0L
    }

    private fun reevaluateCurrent() {
        val foreground = currentForegroundPackage
        if (foreground.isBlank()) {
            worker.removeCallbacks(poll)
            worker.post(poll)
            return
        }
        val previous = previousForegroundPackage
        mainHandler.post { evaluate(foreground, previous) }
    }

    private fun loadHomePackages(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return runCatching {
            packageManager.queryIntentActivities(intent, 0)
                .mapNotNull { it.activityInfo?.packageName }
                .toSet()
        }.getOrDefault(emptySet())
    }

    private fun loadInputMethodPackages(): Set<String> {
        val manager =
            getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                ?: return emptySet()
        return runCatching {
            manager.enabledInputMethodList.mapNotNull {
                it.packageName.trim().takeIf(String::isNotEmpty)
            }.toSet()
        }.getOrDefault(emptySet())
    }

    private fun loadDialAndMessagePackages(): Set<String> {
        val packages = linkedSetOf<String>()
        runCatching {
            val telecom = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecom.defaultDialerPackage
                ?.takeIf(String::isNotBlank)
                ?.let(packages::add)
        }
        runCatching {
            Telephony.Sms.getDefaultSmsPackage(this)
                ?.takeIf(String::isNotBlank)
                ?.let(packages::add)
        }
        return packages
    }

    private fun registerStateReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(FocusGateActivity.ACTION_GATE_VISIBLE)
            addAction(FocusBreakTimeoutReceiver.ACTION_FOCUS_BREAK_ENDED)
            addAction(FocusSessionTimeoutReceiver.ACTION_FOCUS_SESSION_ENDED)
            addAction(StudyToolTimeoutReceiver.ACTION_QUICK_TOOL_ENDED)
            addAction(WebProtectionConfig.actionStateChanged)
            addAction(WebProtectionConfig.actionDomainBlocked)
        }
        ContextCompat.registerReceiver(
            this,
            stateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    private fun createNotificationChannel() {
        val manager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                notificationChannelId,
                "Focus and app-limit protection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Focus sessions and daily app limits reliable."
                enableLights(false)
                enableVibration(false)
            }
        )
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val focusActive = TrackerConfig.isStudyModeEnabled(this)
        return Notification.Builder(this, notificationChannelId)
            .setSmallIcon(R.drawable.ic_focus_expand)
            .setColor(Color.rgb(181, 255, 71))
            .setContentTitle(
                if (focusActive) "Focus protection is active" else "Daily app limits are active"
            )
            .setContentText("Watching package changes without reading screen content")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        if (::worker.isInitialized) worker.removeCallbacksAndMessages(null)
        mainHandler.removeCallbacks(helperTimeout)
        clearActiveHelper()
        runCatching { focusPill.destroy("usage_service_destroyed") }
        runCatching { blockOverlay.destroy() }
        runCatching { mediaController.release() }
        runCatching {
            flutterPrefs?.unregisterOnSharedPreferenceChangeListener(this)
        }
        if (receiverRegistered) runCatching { unregisterReceiver(stateReceiver) }
        receiverRegistered = false
        flutterPrefs = null
        if (workerThread.isAlive) workerThread.quitSafely()
        super.onDestroy()
    }

    private fun debug(message: String) {
        TrustIssueDebugLog.append(this, "FocusUsage", "I", message.take(500))
    }
}

internal object UsagePollingPolicy {
    const val fastPollIntervalMs = 350L
    const val activePollIntervalMs = 900L
    const val dailyLimitPollIntervalMs = 1_250L
    const val stablePollIntervalMs = 1_750L
    const val standbyPollIntervalMs = 5_000L
    const val screenOffPollIntervalMs = 30_000L
    private const val activePollCount = 4

    fun nextDelay(
        foregroundChanged: Boolean,
        hadLifecycleEvents: Boolean,
        unchangedPollCount: Int,
        hasDailyLimits: Boolean
    ): Long {
        if (foregroundChanged || hadLifecycleEvents) return fastPollIntervalMs
        if (unchangedPollCount < activePollCount) return activePollIntervalMs
        if (hasDailyLimits) return dailyLimitPollIntervalMs
        return stablePollIntervalMs
    }
}
