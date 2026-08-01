package com.trustissue.child

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

class FocusGateActivity : ComponentActivity() {
    private data class BreakAppOption(
        val packageName: String,
        val appName: String
    )

    private var packageNameToBlock: String = ""
    private var blockedAppName: String = ""
    private var safeReturnPackage: String = ""
    private var startedAtMs: Long = 0L
    private var exitTimer: CountDownTimer? = null
    private var displayTimer: CountDownTimer? = null
    private val gateClockHandler = Handler(Looper.getMainLooper())
    private var gateClockRunnable: Runnable? = null
    private var lastBreakTapAtMs: Long = 0L
    private var showingEmergencyExit: Boolean = false
    private var emergencyExitResolved: Boolean = false
    private var gateVisibleReported: Boolean = false
    private var gateMotivationIndex: Int = 0
    private val gateVisibleRunnable = Runnable {
        if (!isFinishing && !isDestroyed) {
            sendBroadcast(
                Intent(ACTION_GATE_VISIBLE).setPackage(packageName)
            )
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handleBackNavigation()
                }
            }
        )
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        packageNameToBlock = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        blockedAppName = intent.getStringExtra(EXTRA_APP_NAME)
            ?: TrackerConfig.appName(this, packageNameToBlock)
        safeReturnPackage = intent.getStringExtra(EXTRA_SAFE_RETURN_PACKAGE)
            .orEmpty()
        startedAtMs = System.currentTimeMillis()
        gateMotivationIndex = intent.getIntExtra(
            EXTRA_MOTIVATION_INDEX,
            GateMotivation.indexFor(packageNameToBlock, startedAtMs)
        )
        val appLimitBlock = intent.getBooleanExtra(EXTRA_APP_LIMIT_REACHED, false) &&
            TrackerConfig.isDailyAppLimitReached(this, packageNameToBlock)
        val shortsBlock = intent.getBooleanExtra(EXTRA_YOUTUBE_SHORTS_BLOCK, false)
        if (shortsBlock && !youtubeShortsBlockingEnabled()) {
            finish()
            return
        }
        if (!shortsBlock && !appLimitBlock && (
                TrackerConfig.completeStudySessionIfFinished(this) ||
                    !TrackerConfig.isStudyModeEnabled(this)
                )) {
            goHome()
            return
        }
        setFinishOnTouchOutside(false)
        setContentView(requestedContent(intent))
    }

    @Suppress("DEPRECATION")
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        gateClockHandler.removeCallbacks(gateVisibleRunnable)
        gateVisibleReported = false
        setIntent(intent)
        packageNameToBlock = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        blockedAppName = intent.getStringExtra(EXTRA_APP_NAME)
            ?: TrackerConfig.appName(this, packageNameToBlock)
        safeReturnPackage = intent.getStringExtra(EXTRA_SAFE_RETURN_PACKAGE)
            .orEmpty()
        startedAtMs = System.currentTimeMillis()
        gateMotivationIndex = intent.getIntExtra(
            EXTRA_MOTIVATION_INDEX,
            GateMotivation.indexFor(packageNameToBlock, startedAtMs)
        )
        val appLimitBlock = intent.getBooleanExtra(EXTRA_APP_LIMIT_REACHED, false) &&
            TrackerConfig.isDailyAppLimitReached(this, packageNameToBlock)
        val shortsBlock = intent.getBooleanExtra(EXTRA_YOUTUBE_SHORTS_BLOCK, false)
        if (shortsBlock && !youtubeShortsBlockingEnabled()) {
            finish()
            return
        }
        if (!shortsBlock && !appLimitBlock && (
                TrackerConfig.completeStudySessionIfFinished(this) ||
                    !TrackerConfig.isStudyModeEnabled(this)
                )) {
            goHome()
            return
        }
        setContentView(requestedContent(intent))
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !gateVisibleReported) {
            gateVisibleReported = true
            gateClockHandler.postDelayed(gateVisibleRunnable, 180L)
        }
    }

    override fun onStop() {
        super.onStop()
        recordGateDuration()
    }

    override fun onDestroy() {
        exitTimer?.cancel()
        displayTimer?.cancel()
        gateClockHandler.removeCallbacks(gateVisibleRunnable)
        stopGateClock()
        super.onDestroy()
    }

    private fun handleBackNavigation() {
        if (intent.getBooleanExtra(EXTRA_YOUTUBE_SHORTS_BLOCK, false)) {
            YouTubeShortsPassStore.clear(this)
            recordGateDuration()
            goHome()
            return
        }
        if (intent.getBooleanExtra(EXTRA_APP_LIMIT_REACHED, false)) {
            recordGateDuration()
            goHome()
            return
        }
        if (showingEmergencyExit && !emergencyExitResolved) {
            TrackerConfig.recordEmergencyExitCancelled(this, "focus_gate")
            emergencyExitResolved = true
        }
        TrackerConfig.recordStudyHandled(this, packageNameToBlock, blockedAppName)
        TrackerConfig.pauseStudyTimerForIdle(this)
        recordGateDuration()
        if (intent.getBooleanExtra(EXTRA_BREAK_RESTRICTED, false)) {
            openFirstBreakApp()
            return
        }
        returnToFocusDestination()
    }

    private fun requestedContent(source: Intent): FrameLayout {
        displayTimer?.cancel()
        stopGateClock()
        return when {
            source.getBooleanExtra(EXTRA_YOUTUBE_SHORTS_BLOCK, false) ->
                buildYouTubeShortsContent()
            source.getBooleanExtra(EXTRA_APP_LIMIT_REACHED, false) ->
                buildAppLimitContent()
            source.getBooleanExtra(EXTRA_BREAK_RESTRICTED, false) ->
                buildBreakRestrictedContent()
            source.getBooleanExtra(EXTRA_SHOW_BREAK_PICKER, false) ->
                buildBreakPickerContent(TrackerConfig.nextStudyBreakDurationMs(this))
            source.getBooleanExtra(EXTRA_SHOW_EMERGENCY_EXIT, false) ->
                if (TrackerConfig.isLockedStrictFocusModeEnabled(this)) {
                    buildLockedStrictNoticeContent()
                } else {
                    buildEmergencyExitContent()
                }
            source.getBooleanExtra(EXTRA_SHOW_END_CONFIRMATION, false) ->
                if (TrackerConfig.isLockedStrictFocusModeEnabled(this)) {
                    buildLockedStrictNoticeContent()
                } else {
                    buildEndFocusContent()
                }
            else -> buildContent()
        }
    }

    private fun showPage(page: View) {
        page.alpha = 0f
        page.translationY = dp(24).toFloat()
        setContentView(page)
        page.post {
            page.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(240L)
                .setInterpolator(DecelerateInterpolator(1.35f))
                .start()
        }
    }

    // -- Blocked-app screen -------------------------------------------------

    private fun buildContent(): FrameLayout {
        displayTimer?.cancel()
        showingEmergencyExit = false
        emergencyExitResolved = false
        val breaksLeft = TrackerConfig.remainingStudyBreaks(this)
        val nextBreakMs = TrackerConfig.nextStudyBreakDurationMs(this)
        val remainingStudyMs = TrackerConfig.remainingStudySessionMs(this)
        val stopwatch = TrackerConfig.isStopwatchSession(this)
        val displayedStudyMs = if (stopwatch) {
            (TrackerConfig.configuredStudyDurationMs(this) - remainingStudyMs)
                .coerceAtLeast(0L)
        } else {
            remainingStudyMs
        }
        val root = screenRoot()
        val content = minimalGatePage()
        val compactHeight = resources.configuration.screenHeightDp < 700

        content.addView(gateBrand())
        content.addView(weightedSpacer(if (compactHeight) 0.35f else 0.55f))
        content.addView(blockedAppHero())
        content.addView(
            TextView(this).apply {
                text = "$blockedAppName is blocked\nduring Focus"
                setTextColor(TEXT_PRIMARY)
                textSize = if (compactHeight) 27f else 29f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                includeFontPadding = false
                maxLines = 3
            },
            marginParams(topMargin = dp(if (compactHeight) 18 else 24))
        )
        content.addView(
            focusCountdown(displayedStudyMs, stopwatch),
            marginParams(topMargin = dp(if (compactHeight) 14 else 20))
        )
        content.addView(
            TextView(this).apply {
                text = "\u201c${GateMotivation.quote(gateMotivationIndex)}\u201d"
                setTextColor(TEXT_SECONDARY)
                textSize = if (compactHeight) 14f else 15.5f
                typeface = Typeface.create("sans-serif", Typeface.ITALIC)
                gravity = Gravity.CENTER
                includeFontPadding = false
                maxLines = 2
                setLineSpacing(0f, 1.12f)
            },
            marginParams(topMargin = dp(if (compactHeight) 10 else 14))
        )
        content.addView(weightedSpacer(if (compactHeight) 0.45f else 0.7f))

        if (nextBreakMs > 0L) {
            content.addView(ghostButton("Take a ${formatBreakRequestDuration(nextBreakMs)} break  |  $breaksLeft left") {
                val now = System.currentTimeMillis()
                if (now - lastBreakTapAtMs < 800L) return@ghostButton
                lastBreakTapAtMs = now
                stopGateClock()
                showPage(buildBreakPickerContent(nextBreakMs))
            })
        }

        content.addView(
            primaryButton("Stay in Focus") {
                TrackerConfig.recordStudyHandled(this, packageNameToBlock, blockedAppName)
                TrackerConfig.pauseStudyTimerForIdle(this)
                recordGateDuration()
                returnToFocusDestination()
            }
        )

        val lockedStrict = TrackerConfig.isLockedStrictFocusModeEnabled(this)
        if (!lockedStrict) {
            val strictMode = TrackerConfig.isStrictFocusModeEnabled(this)
            content.addView(tertiaryButton(if (strictMode) "Emergency Exit" else "End Focus") {
                if (strictMode) {
                    stopGateClock()
                    showPage(buildEmergencyExitContent())
                } else {
                    stopGateClock()
                    showPage(buildEndFocusContent())
                }
            })
        } else {
            content.addView(
                TextView(this).apply {
                    text = "Locked Strict  |  Ends when the timer finishes"
                    setTextColor(TEXT_MUTED)
                    textSize = 12f
                    gravity = Gravity.CENTER
                },
                marginParams(topMargin = dp(12))
            )
        }

        root.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        animateGateEntrance(root, content)
        return root
    }

    private fun buildAppLimitContent(): FrameLayout {
        displayTimer?.cancel()
        showingEmergencyExit = false
        emergencyExitResolved = false
        val limitMinutes = TrackerConfig.dailyAppLimits(this)[packageNameToBlock] ?: 0
        val usedMs = UsageStatsReader.usageTodayMs(this, packageNameToBlock)
        val root = screenRoot()
        val content = minimalGatePage()
        val compactHeight = resources.configuration.screenHeightDp < 700
        content.addView(gateBrand())
        content.addView(weightedSpacer(if (compactHeight) 0.35f else 0.55f))
        content.addView(blockedAppHero())
        content.addView(
            TextView(this).apply {
                text = "$blockedAppName reached its\ndaily limit"
                setTextColor(TEXT_PRIMARY)
                textSize = if (compactHeight) 27f else 29f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                gravity = Gravity.CENTER
                includeFontPadding = false
                maxLines = 3
            },
            marginParams(topMargin = dp(if (compactHeight) 18 else 24))
        )
        content.addView(
            TextView(this).apply {
                text = formatUsageDuration(usedMs)
                setTextColor(TEXT_PRIMARY)
                textSize = if (compactHeight) 36f else 42f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                gravity = Gravity.CENTER
                includeFontPadding = false
            },
            marginParams(topMargin = dp(if (compactHeight) 14 else 20))
        )
        content.addView(
            TextView(this).apply {
                text = "USED TODAY  \u00b7  ${formatUsageDuration(limitMinutes * 60_000L)} LIMIT"
                setTextColor(TEXT_MUTED)
                textSize = 12f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                letterSpacing = 0.08f
                gravity = Gravity.CENTER
                includeFontPadding = false
            },
            marginParams(topMargin = dp(7))
        )
        content.addView(
            TextView(this).apply {
                text = "\u201c${GateMotivation.quote(gateMotivationIndex)}\u201d"
                setTextColor(TEXT_SECONDARY)
                textSize = if (compactHeight) 14f else 15.5f
                typeface = Typeface.create("sans-serif", Typeface.ITALIC)
                gravity = Gravity.CENTER
                includeFontPadding = false
                maxLines = 2
                setLineSpacing(0f, 1.12f)
            },
            marginParams(topMargin = dp(if (compactHeight) 12 else 16))
        )
        content.addView(weightedSpacer(if (compactHeight) 0.45f else 0.7f))
        content.addView(
            primaryButton("Stay away for today") {
                recordGateDuration()
                goHome()
            }
        )
        root.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        animateGateEntrance(root, content)
        return root
    }

    private fun buildYouTubeShortsContent(): FrameLayout {
        displayTimer?.cancel()
        showingEmergencyExit = false
        emergencyExitResolved = false
        val root = screenRoot()
        val content = minimalGatePage()
        val compactHeight = resources.configuration.screenHeightDp < 700

        content.addView(gateBrand())
        content.addView(weightedSpacer(if (compactHeight) 0.32f else 0.5f))
        content.addView(blockedAppHero())
        content.addView(
            TextView(this).apply {
                text = "YouTube Shorts paused"
                setTextColor(TEXT_PRIMARY)
                textSize = if (compactHeight) 27f else 30f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                gravity = Gravity.CENTER
                includeFontPadding = false
            },
            marginParams(topMargin = dp(if (compactHeight) 18 else 24))
        )
        content.addView(
            TextView(this).apply {
                text =
                    "Watch the Short already on screen if you choose. " +
                        "Swipe to another Short and this gate returns."
                setTextColor(TEXT_SECONDARY)
                textSize = if (compactHeight) 14f else 15.5f
                gravity = Gravity.CENTER
                includeFontPadding = false
                setLineSpacing(0f, 1.14f)
            },
            marginParams(topMargin = dp(12))
        )
        content.addView(weightedSpacer(if (compactHeight) 0.42f else 0.7f))
        content.addView(
            primaryButton("Watch only this") {
                val fingerprint = intent.getStringExtra(EXTRA_SHORT_FINGERPRINT)
                    .orEmpty()
                if (YouTubeShortsPassStore.grant(this, fingerprint)) {
                    startedAtMs = 0L
                    finish()
                } else {
                    Toast.makeText(
                        this,
                        "This Short could not be verified. Please leave Shorts.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
        content.addView(
            ghostButton("Leave Shorts") {
                YouTubeShortsPassStore.clear(this)
                recordGateDuration()
                goHome()
            }
        )
        root.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        animateGateEntrance(root, content)
        return root
    }

    private fun minimalGatePage(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(28), dp(28), dp(30))
        }
    }

    private fun weightedSpacer(weight: Float): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, 0, weight)
        }
    }

    private fun gateBrand(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(appIcon(packageName, "TrustIssue"))
            addView(
                TextView(this@FocusGateActivity).apply {
                    text = "TrustIssue"
                    setTextColor(TEXT_PRIMARY)
                    textSize = 18f
                    typeface = Typeface.DEFAULT_BOLD
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(9) }
            )
        }
    }

    private fun blockedAppHero(): FrameLayout {
        val heroSize = dp(108)
        val iconSize = dp(88)
        val drawable = runCatching {
            packageManager.getApplicationIcon(packageNameToBlock)
        }.getOrNull()
        return FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(heroSize, heroSize)
            val icon = if (drawable != null) {
                ImageView(this@FocusGateActivity).apply {
                    setImageDrawable(drawable)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    background = roundedRect(0xFFF4F6F3.toInt(), dp(26))
                    contentDescription = blockedAppName
                }
            } else {
                TextView(this@FocusGateActivity).apply {
                    text = initialsFor(blockedAppName)
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    textSize = 28f
                    typeface = Typeface.DEFAULT_BOLD
                    background = roundedRect(0xFF35506B.toInt(), dp(26))
                }
            }
            addView(
                icon,
                FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
            )
            addView(
                ImageView(this@FocusGateActivity).apply {
                    setImageResource(android.R.drawable.ic_lock_lock)
                    imageTintList = ColorStateList.valueOf(NIGHT)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dp(9), dp(9), dp(9), dp(9))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(WARNING)
                        setStroke(dp(2), NIGHT)
                    }
                    contentDescription = "Blocked"
                },
                FrameLayout.LayoutParams(dp(36), dp(36), Gravity.END or Gravity.BOTTOM)
            )
        }
    }

    private fun focusCountdown(
        displayedStudyMs: Long,
        stopwatch: Boolean
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val clockText = TextView(this@FocusGateActivity).apply {
                text = formatClock(displayedStudyMs)
                setTextColor(TEXT_PRIMARY)
                textSize = if (resources.configuration.screenHeightDp < 700) 43f else 50f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                fontFeatureSettings = "tnum"
                letterSpacing = 0.035f
                gravity = Gravity.CENTER
                includeFontPadding = false
                contentDescription =
                    if (stopwatch) "Focus time elapsed" else "Focus time remaining"
            }
            addView(clockText)
            startGateClock(clockText, stopwatch)
        }
    }

    private fun startGateClock(target: TextView, stopwatch: Boolean) {
        stopGateClock()
        val updater = object : Runnable {
            override fun run() {
                if (!TrackerConfig.isStudyModeEnabled(this@FocusGateActivity)) return
                val remainingMs = TrackerConfig.remainingStudySessionMs(
                    this@FocusGateActivity
                )
                if (
                    remainingMs <= 0L &&
                    TrackerConfig.completeStudySessionIfFinished(
                        this@FocusGateActivity
                    )
                ) {
                    gateClockRunnable = null
                    goHome()
                    return
                }
                val displayedMs = if (stopwatch) {
                    (TrackerConfig.configuredStudyDurationMs(
                        this@FocusGateActivity
                    ) - remainingMs).coerceAtLeast(0L)
                } else {
                    remainingMs
                }
                target.text = formatClock(displayedMs)
                val nextSecond = 1_020L - (System.currentTimeMillis() % 1_000L)
                gateClockHandler.postDelayed(this, nextSecond)
            }
        }
        gateClockRunnable = updater
        updater.run()
    }

    private fun stopGateClock() {
        gateClockRunnable?.let(gateClockHandler::removeCallbacks)
        gateClockRunnable = null
    }

    private fun animateGateEntrance(root: FrameLayout, content: View) {
        content.alpha = 0f
        content.translationY = dp(64).toFloat()
        root.post {
            content.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(420L)
                .setInterpolator(DecelerateInterpolator(1.45f))
                .start()
        }
    }

    private fun timerPanel(displayedStudyMs: Long, stopwatch: Boolean = false): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = roundedRect(SURFACE, dp(18), CARD_BORDER, dp(1))
            addView(
                TextView(this@FocusGateActivity).apply {
                    text = "Focus Session"
                    setTextColor(TEXT_SECONDARY)
                    textSize = 13f
                    gravity = Gravity.CENTER
                }
            )
            addView(
                TextView(this@FocusGateActivity).apply {
                    text = formatClock(displayedStudyMs)
                    setTextColor(EMERALD)
                    textSize = 34f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                },
                marginParams(topMargin = dp(4))
            )
            addView(
                TextView(this@FocusGateActivity).apply {
                    text = if (stopwatch) "Elapsed Focus" else "Time Left"
                    setTextColor(TEXT_MUTED)
                    textSize = 12f
                    gravity = Gravity.CENTER
                }
            )
        }
    }

    private fun blockedAppPanel(subtitle: String = "Blocked during focus"): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedRect(SURFACE, dp(16), CARD_BORDER, dp(1))
            addView(blockedAppIcon())
            addView(
                LinearLayout(this@FocusGateActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        TextView(this@FocusGateActivity).apply {
                            text = blockedAppName
                            setTextColor(TEXT_PRIMARY)
                            textSize = 15f
                            typeface = Typeface.DEFAULT_BOLD
                        }
                    )
                    addView(
                        TextView(this@FocusGateActivity).apply {
                            text = subtitle
                            setTextColor(TEXT_MUTED)
                            textSize = 12f
                        }
                    )
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { leftMargin = dp(12) }
            )
        }
    }

    private fun blockedAppIcon(): View {
        val size = dp(40)
        val drawable = runCatching {
            packageManager.getApplicationIcon(packageNameToBlock)
        }.getOrNull()

        if (drawable != null) {
            return ImageView(this).apply {
                setImageDrawable(drawable)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(4), dp(4), dp(4), dp(4))
                background = roundedRect(SURFACE, dp(12), CARD_BORDER, dp(1))
                layoutParams = LinearLayout.LayoutParams(size, size)
            }
        }

        return TextView(this).apply {
            text = initialsFor(blockedAppName)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            background = roundedRect(0xFF35506B.toInt(), dp(12))
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
    }

    private fun buildBreakPickerContent(
        durationMs: Long,
        selectedPackages: Set<String>? = null,
        searchQuery: String = ""
    ): FrameLayout {
        displayTimer?.cancel()
        showingEmergencyExit = false
        emergencyExitResolved = false
        val options = breakAppOptions()
        val availablePackages = options.mapTo(linkedSetOf()) { it.packageName }
        val initialSelection = selectedPackages
            ?: setOf(packageNameToBlock).filterTo(linkedSetOf()) {
                availablePackages.contains(it)
            }
        val selected = initialSelection
            .filterTo(linkedSetOf()) { availablePackages.contains(it) }
            .take(MAX_BREAK_APPS)
            .toSet()
        val selectedOptions = options.filter { selected.contains(it.packageName) }

        val root = screenRoot()
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(16), dp(16), dp(14))
            background = roundedRect(CARD, dp(18), CARD_BORDER, dp(1))
        }
        header.addView(
            TextView(this).apply {
                text = "BREAK"
                setTextColor(EMERALD)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                letterSpacing = 0.12f
            }
        )
        header.addView(titleText("Choose break apps"), marginParams(topMargin = dp(6)))
        header.addView(
            bodyText("Select up to 3 apps. During the break, every other distracting app stays blocked."),
            marginParams(topMargin = dp(6), bottomMargin = dp(10))
        )
        header.addView(
            TextView(this).apply {
                text = "${selected.size}/$MAX_BREAK_APPS selected  ·  ${formatBreakRequestDuration(durationMs)}"
                setTextColor(if (selected.isEmpty()) TEXT_MUTED else EMERALD)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.START
            },
            marginParams(bottomMargin = dp(8)).apply { gravity = Gravity.START }
        )

        val selectedStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        repeat(MAX_BREAK_APPS) { index ->
            val option = selectedOptions.getOrNull(index)
            val slot = if (option != null) {
                selectedBreakAppChip(option) {
                    val updated = selected.toMutableSet()
                    updated.remove(option.packageName)
                    showPage(
                        buildBreakPickerContent(durationMs, updated, searchQuery)
                    )
                }
            } else {
                emptyBreakAppSlot()
            }
            selectedStrip.addView(
                slot,
                LinearLayout.LayoutParams(0, dp(70), 1f).apply {
                    if (index > 0) leftMargin = dp(6)
                }
            )
        }
        header.addView(
            selectedStrip,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(70)
            ).apply { bottomMargin = dp(8) }
        )

        val startButton = primaryButton(
            if (selected.isEmpty()) "Select an app" else "Save & start  ·  ${selected.size}"
        ) {
            val chosen = selectedOptions
            val primary = chosen.firstOrNull { it.packageName == packageNameToBlock }
                ?: chosen.firstOrNull()
                ?: return@primaryButton
            val started = TrackerConfig.startFocusBreak(
                this,
                primary.packageName,
                primary.appName,
                durationMs,
                selected
            )
            if (started) {
                recordGateDuration()
                openPackage(primary.packageName)
            } else {
                cancelBreakPicker()
            }
        }.apply {
            isEnabled = selected.isNotEmpty()
            alpha = if (isEnabled) 1f else 0.45f
        }
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                ghostButton("Exit") { cancelBreakPicker() },
                LinearLayout.LayoutParams(0, dp(50), 1f)
            )
            addView(
                startButton,
                LinearLayout.LayoutParams(0, dp(50), 2f).apply {
                    leftMargin = dp(8)
                }
            )
        }
        header.addView(
            actionRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(50)
            ).apply { bottomMargin = dp(10) }
        )

        val searchInput = EditText(this).apply {
            hint = "Search apps"
            setSingleLine(true)
            setText(searchQuery)
            setSelection(text?.length ?: 0)
            setTextColor(TEXT_PRIMARY)
            setHintTextColor(TEXT_MUTED)
            textSize = 14f
            setPadding(dp(14), 0, dp(14), 0)
            background = roundedRect(SURFACE, dp(12), CARD_BORDER, dp(1))
        }
        header.addView(
            searchInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            )
        )
        page.addView(
            header,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val appList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(4))
            background = roundedRect(CARD, dp(16), CARD_BORDER, dp(1))
        }
        options.filterNot { selected.contains(it.packageName) }.forEach { option ->
            val row = breakAppSelectionRow(option, false) {
                if (selected.size >= MAX_BREAK_APPS) {
                    Toast.makeText(
                        this,
                        "You can choose up to $MAX_BREAK_APPS apps",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@breakAppSelectionRow
                }
                val updated = selected.toMutableSet().apply { add(option.packageName) }
                showPage(
                    buildBreakPickerContent(
                        durationMs,
                        updated,
                        searchInput.text?.toString().orEmpty()
                    )
                )
            }.apply { tag = option }
            appList.addView(
                row,
                marginParams(bottomMargin = dp(7)).also { params ->
                    params.width = LinearLayout.LayoutParams.MATCH_PARENT
                }
            )
        }
        fun applySearch(query: String) {
            val normalized = query.trim().lowercase()
            for (index in 0 until appList.childCount) {
                val row = appList.getChildAt(index)
                val option = row.tag as? BreakAppOption
                row.visibility = if (
                    option == null ||
                    normalized.isEmpty() ||
                    option.appName.lowercase().contains(normalized)
                ) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            }
        }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applySearch(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        applySearch(searchQuery)

        val appScroll = ScrollView(this).apply {
            clipToPadding = false
            addView(
                appList,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        page.addView(
            appScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply { topMargin = dp(10) }
        )
        root.addView(
            page,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        return root
    }

    private fun selectedBreakAppChip(
        option: BreakAppOption,
        onRemove: () -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = roundedRect(withAlpha(EMERALD, 0x18), dp(10), withAlpha(EMERALD, 0x88), dp(1))
            addView(
                appIcon(option.packageName, option.appName),
                LinearLayout.LayoutParams(dp(32), dp(32))
            )
            addView(
                TextView(this@FocusGateActivity).apply {
                    text = "${option.appName}  ×"
                    setTextColor(TEXT_PRIMARY)
                    textSize = 10f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(3) }
            )
            contentDescription = "${option.appName}, selected. Tap to remove"
            setOnClickListener { onRemove() }
        }
    }

    private fun emptyBreakAppSlot(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = roundedRect(SURFACE, dp(10), CARD_BORDER, dp(1))
            addView(TextView(this@FocusGateActivity).apply {
                text = "+"
                setTextColor(TEXT_MUTED)
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            })
            addView(TextView(this@FocusGateActivity).apply {
                text = "App slot"
                setTextColor(TEXT_MUTED)
                textSize = 10f
                gravity = Gravity.CENTER
            })
        }
    }

    private fun buildBreakRestrictedContent(): FrameLayout {
        showingEmergencyExit = false
        emergencyExitResolved = false
        val breakPackages = TrackerConfig.focusBreakSelectedPackages(this)
        val remainingMs = TrackerConfig.remainingFocusBreakMs(this)
        if (remainingMs <= 0L || breakPackages.isEmpty()) {
            TrackerConfig.finishFocusBreak(this, "expired_gate")
            return buildContent()
        }

        val root = screenRoot()
        val content = cardLayout()
        content.addView(
            TextView(this).apply {
                text = "BREAK IN PROGRESS"
                setTextColor(WARNING)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                letterSpacing = 0.1f
            }
        )
        content.addView(titleText("Break apps only"), marginParams(topMargin = dp(8)))
        content.addView(
            bodyText("$blockedAppName isn’t selected for this break. You can use only the apps below until the timer ends."),
            marginParams(topMargin = dp(8), bottomMargin = dp(16))
        )
        content.addView(breakTimerPanel(remainingMs))
        content.addView(
            TextView(this).apply {
                text = "Allowed for this break"
                setTextColor(TEXT_PRIMARY)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.START
            },
            marginParams(topMargin = dp(16), bottomMargin = dp(8)).apply {
                gravity = Gravity.START
            }
        )
        breakPackages.forEach { allowedPackage ->
            val option = BreakAppOption(
                allowedPackage,
                TrackerConfig.appName(this, allowedPackage)
            )
            content.addView(
                breakAppSelectionRow(option, true) { openPackage(allowedPackage) },
                marginParams(bottomMargin = dp(8))
            )
        }
        content.addView(primaryButton("Open a break app") { openFirstBreakApp() })
        content.addView(ghostButton("End break early") {
            TrackerConfig.finishFocusBreak(this, "manual_gate_end")
            TrackerConfig.pauseStudyTimerForIdle(this)
            returnToFocusDestination()
        })

        addScrollableCard(root, content)
        return root
    }

    private fun breakTimerPanel(remainingMs: Long): LinearLayout {
        val timeText = TextView(this).apply {
            text = formatClock(remainingMs)
            setTextColor(WARNING)
            textSize = 23f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
        }
        displayTimer?.cancel()
        displayTimer = object : CountDownTimer(remainingMs.coerceAtLeast(1L), 1_000L) {
            override fun onTick(millisUntilFinished: Long) {
                timeText.text = formatClock(millisUntilFinished)
            }

            override fun onFinish() {
                TrackerConfig.finishFocusBreak(this@FocusGateActivity, "expired_gate")
                TrackerConfig.pauseStudyTimerForIdle(this@FocusGateActivity)
                returnToFocusDestination()
            }
        }.start()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = roundedRect(SURFACE, dp(12), CARD_BORDER, dp(1))
            addView(
                TextView(this@FocusGateActivity).apply {
                    text = "Break time left"
                    setTextColor(TEXT_SECONDARY)
                    textSize = 13f
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(timeText)
        }
    }

    @Suppress("DEPRECATION")
    private fun breakAppOptions(): List<BreakAppOption> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        return packageManager.queryIntentActivities(launcherIntent, 0)
            .asSequence()
            .mapNotNull { info ->
                val appPackage = info.activityInfo?.packageName.orEmpty()
                if (appPackage.isBlank() || appPackage == packageName) return@mapNotNull null
                BreakAppOption(
                    packageName = appPackage,
                    appName = info.loadLabel(packageManager)?.toString()
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?: TrackerConfig.appName(this, appPackage)
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.appName.lowercase() }
            .toList()
    }

    private fun breakAppSelectionRow(
        option: BreakAppOption,
        selected: Boolean,
        onClick: () -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedRect(
                if (selected) withAlpha(EMERALD, 0x18) else SURFACE,
                dp(12),
                if (selected) withAlpha(EMERALD, 0x88) else CARD_BORDER,
                dp(1)
            )
            addView(appIcon(option.packageName, option.appName))
            addView(
                TextView(this@FocusGateActivity).apply {
                    text = option.appName
                    setTextColor(TEXT_PRIMARY)
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { leftMargin = dp(12) }
            )
            addView(
                TextView(this@FocusGateActivity).apply {
                    text = if (selected) "✓" else "+"
                    gravity = Gravity.CENTER
                    setTextColor(if (selected) NIGHT else TEXT_SECONDARY)
                    textSize = 17f
                    typeface = Typeface.DEFAULT_BOLD
                    background = roundedRect(if (selected) EMERALD else GHOST, dp(10))
                    layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
                }
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun appIcon(appPackage: String, appName: String): View {
        val size = dp(38)
        val drawable = runCatching { packageManager.getApplicationIcon(appPackage) }.getOrNull()
        return if (drawable != null) {
            ImageView(this).apply {
                setImageDrawable(drawable)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(3), dp(3), dp(3), dp(3))
                layoutParams = LinearLayout.LayoutParams(size, size)
            }
        } else {
            TextView(this).apply {
                text = initialsFor(appName)
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                background = roundedRect(0xFF35506B.toInt(), dp(10))
                layoutParams = LinearLayout.LayoutParams(size, size)
            }
        }
    }

    private fun quoteBlock(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(
                TextView(this@FocusGateActivity).apply {
                    text = "“Distraction is the enemy of focus.”"
                    setTextColor(TEXT_SECONDARY)
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setTypeface(typeface, Typeface.ITALIC)
                }
            )
            addView(
                TextView(this@FocusGateActivity).apply {
                    text = "Stay strong!"
                    setTextColor(EMERALD)
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                },
                marginParams(topMargin = dp(4))
            )
        }
    }

    private fun buildEndFocusContent(): FrameLayout {
        displayTimer?.cancel()
        showingEmergencyExit = false
        emergencyExitResolved = false
        val root = screenRoot()
        val content = cardLayout()

        content.addView(circleBadge("■", DANGER, Color.WHITE))
        content.addView(
            titleText("End Focus?"),
            marginParams(topMargin = dp(16))
        )
        content.addView(
            bodyText("Your completed focus time will stay in the local report."),
            marginParams(topMargin = dp(8), bottomMargin = dp(18))
        )
        content.addView(
            solidButton("End Focus", DANGER, Color.WHITE) {
                TrackerConfig.setStudyModeEnabled(this, false)
                recordGateDuration()
                goHome()
            }
        )
        content.addView(
            ghostButton("Keep focusing") {
                TrackerConfig.recordStudyHandled(this, packageNameToBlock, blockedAppName)
                recordGateDuration()
                returnToFocusDestination()
            }
        )

        addScrollableCard(root, content)
        return root
    }

    // -- Emergency-exit screen ---------------------------------------------

    private fun buildLockedStrictNoticeContent(): FrameLayout {
        displayTimer?.cancel()
        showingEmergencyExit = false
        emergencyExitResolved = false
        val root = screenRoot()
        val content = cardLayout()

        content.addView(circleBadge("3h", WARNING, Color.WHITE))
        content.addView(
            titleText("Locked Strict is active"),
            marginParams(topMargin = dp(16))
        )
        content.addView(
            bodyText(
                "Emergency Exit is disabled for this session. Timed breaks and Android safety apps remain available."
            ),
            marginParams(topMargin = dp(8), bottomMargin = dp(16))
        )
        content.addView(timerPanel(TrackerConfig.remainingStudySessionMs(this)))
        content.addView(
            primaryButton("Back to Focus") {
                TrackerConfig.pauseStudyTimerForIdle(this)
                recordGateDuration()
                returnToFocusDestination()
            }
        )

        addScrollableCard(root, content)
        return root
    }

    private fun buildEmergencyExitContent(): FrameLayout {
        if (TrackerConfig.isLockedStrictFocusModeEnabled(this)) {
            return buildLockedStrictNoticeContent()
        }
        displayTimer?.cancel()
        showingEmergencyExit = true
        emergencyExitResolved = false
        TrackerConfig.recordEmergencyExitAttempt(this, "focus_gate")
        exitTimer?.cancel()

        val root = screenRoot()
        val content = cardLayout()

        content.addView(circleBadge("!", WARNING, Color.WHITE))
        content.addView(
            TextView(this).apply {
                text = "STRICT FOCUS"
                setTextColor(WARNING)
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.14f
                gravity = Gravity.CENTER
            },
            marginParams(topMargin = dp(14))
        )
        content.addView(
            titleText("Emergency Exit"),
            marginParams(topMargin = dp(5))
        )
        content.addView(
            bodyText("Pause for a moment before ending the session early."),
            marginParams(topMargin = dp(8), bottomMargin = dp(14))
        )

        content.addView(
            reflectionRow(
                "1",
                "Is it necessary?",
                "Check whether this needs action right now."
            )
        )
        content.addView(
            reflectionRow(
                "2",
                "Would a short break help?",
                "A timed break keeps the session protected."
            ),
            marginParams(topMargin = dp(8), bottomMargin = dp(16))
        )

        val reasonInput = EditText(this).apply {
            hint = "Type your reason here..."
            minLines = 3
            maxLines = 4
            gravity = Gravity.TOP or Gravity.START
            setTextColor(TEXT_PRIMARY)
            setHintTextColor(TEXT_MUTED)
            textSize = 15f
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedRect(SURFACE, dp(12), CARD_BORDER, dp(1))
        }
        content.addView(
            TextView(this).apply {
                text = "Please tell us the reason"
                setTextColor(TEXT_PRIMARY)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.START
            },
            marginParams(topMargin = dp(2), bottomMargin = dp(6)).apply {
                gravity = Gravity.START
            }
        )
        content.addView(
            TextView(this).apply {
                text = "Write at least $MIN_WORDS words. This stays only in your local report."
                setTextColor(TEXT_MUTED)
                textSize = 11.5f
                gravity = Gravity.START
            },
            marginParams(bottomMargin = dp(9)).apply { gravity = Gravity.START }
        )
        content.addView(
            reasonInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(104)
            ).apply { bottomMargin = dp(6) }
        )

        val wordCountText = TextView(this).apply {
            text = "0/$MIN_WORDS words"
            setTextColor(TEXT_MUTED)
            textSize = 12f
            gravity = Gravity.END
        }
        content.addView(
            wordCountText,
            marginParams(bottomMargin = dp(14)).apply { gravity = Gravity.END }
        )

        val countdownText = TextView(this).apply {
            text = "${EXIT_COUNTDOWN}s"
            setTextColor(EMERALD)
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        content.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = roundedRect(SURFACE, dp(12), CARD_BORDER, dp(1))
                addView(
                    TextView(this@FocusGateActivity).apply {
                        text = "EXIT UNLOCKS IN"
                        setTextColor(TEXT_MUTED)
                        textSize = 9.5f
                        typeface = Typeface.DEFAULT_BOLD
                        letterSpacing = 0.10f
                        gravity = Gravity.CENTER
                    }
                )
                addView(countdownText, marginParams(topMargin = dp(2)))
            },
            marginParams(bottomMargin = dp(6))
        )

        var countdownDone = false
        fun refreshExitState(button: Button, input: EditText) {
            val enough = wordCount(input.text?.toString()) >= MIN_WORDS
            val canExit = countdownDone && enough
            button.isEnabled = canExit
            if (canExit) {
                styleButton(button, DANGER, Color.WHITE)
            } else {
                styleButton(button, GHOST, TEXT_MUTED)
            }
        }

        val exitButton = solidButton("Exit Now (Available in ${EXIT_COUNTDOWN}s)", GHOST, TEXT_MUTED) {}
        exitButton.isEnabled = false
        reasonInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val words = wordCount(reasonInput.text?.toString())
                wordCountText.text = "$words/$MIN_WORDS words"
                wordCountText.setTextColor(if (words >= MIN_WORDS) EMERALD else TEXT_MUTED)
                refreshExitState(exitButton, reasonInput)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        exitButton.setOnClickListener {
            val reason = reasonInput.text?.toString()?.trim().orEmpty()
            if (wordCount(reason) < MIN_WORDS) {
                reasonInput.error = "Write at least $MIN_WORDS words"
                return@setOnClickListener
            }
            TrackerConfig.recordEmergencyExit(
                this,
                reason,
                "focus_gate",
                packageNameToBlock,
                blockedAppName
            )
            emergencyExitResolved = true
            recordGateDuration()
            openBlockedApp()
        }
        content.addView(exitButton)

        content.addView(
            ghostButton("←  Back to Focus") {
                TrackerConfig.recordStudyHandled(this, packageNameToBlock, blockedAppName)
                TrackerConfig.pauseStudyTimerForIdle(this)
                recordGateDuration()
                returnToFocusDestination()
            }
        )

        var secondsLeft = EXIT_COUNTDOWN
        exitTimer = object : CountDownTimer(EXIT_COUNTDOWN * 1000L, 1_000L) {
            override fun onTick(millisUntilFinished: Long) {
                secondsLeft = ((millisUntilFinished + 999L) / 1000L).toInt()
                countdownText.text = "${secondsLeft}s"
                exitButton.text = "Exit Now (Available in ${secondsLeft}s)"
            }

            override fun onFinish() {
                countdownDone = true
                countdownText.text = "Ready"
                exitButton.text = "Exit Now"
                refreshExitState(exitButton, reasonInput)
            }
        }.start()

        addScrollableCard(root, content)
        return root
    }

    private fun reflectionRow(marker: String, title: String, detail: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedRect(SURFACE, dp(12), CARD_BORDER, dp(1))
            addView(
                TextView(this@FocusGateActivity).apply {
                    text = marker
                    gravity = Gravity.CENTER
                    setTextColor(EMERALD)
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    background = ovalWithGlow(EMERALD)
                },
                LinearLayout.LayoutParams(dp(34), dp(34))
            )
            addView(
                LinearLayout(this@FocusGateActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        TextView(this@FocusGateActivity).apply {
                            text = title
                            setTextColor(TEXT_PRIMARY)
                            textSize = 13.5f
                            typeface = Typeface.DEFAULT_BOLD
                        }
                    )
                    addView(
                        TextView(this@FocusGateActivity).apply {
                            text = detail
                            setTextColor(TEXT_MUTED)
                            textSize = 11.5f
                        }
                    )
                },
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply { marginStart = dp(10) }
            )
        }
    }

    // -- Shared UI builders -------------------------------------------------

    private fun screenRoot(): FrameLayout {
        return FrameLayout(this).apply {
            setBackgroundColor(NIGHT)
        }
    }

    private fun cardLayout(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(24), dp(22), dp(22))
            background = roundedRect(CARD, dp(18), CARD_BORDER, dp(1))
        }
    }

    private fun addScrollableCard(root: FrameLayout, card: View) {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(24), dp(18), dp(24))
        }
        page.addView(
            card,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(
                page,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        root.addView(
            scroll,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun circleBadge(glyph: String, badgeColor: Int, textColor: Int): TextView {
        return TextView(this).apply {
            text = glyph
            gravity = Gravity.CENTER
            setTextColor(textColor)
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
            background = ovalWithGlow(badgeColor)
        }
    }

    private fun titleText(value: String): TextView {
        return TextView(this).apply {
            text = value
            setTextColor(TEXT_PRIMARY)
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
    }

    private fun bodyText(value: String): TextView {
        return TextView(this).apply {
            text = value
            setTextColor(TEXT_SECONDARY)
            textSize = 14f
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
        }
    }

    private fun primaryButton(label: String, onClick: () -> Unit): Button =
        solidButton(label, LIME, LIME_TEXT, onClick)

    private fun ghostButton(label: String, onClick: () -> Unit): Button {
        return baseButton(label).apply {
            setTextColor(TEXT_PRIMARY)
            background = roundedRect(GHOST, dp(12), CARD_BORDER, dp(1))
            setOnClickListener { onClick() }
        }
    }

    private fun tertiaryButton(label: String, onClick: () -> Unit): Button {
        return baseButton(label).apply {
            textSize = 14f
            setTextColor(TEXT_SECONDARY)
            background = null
            setOnClickListener { onClick() }
            (layoutParams as LinearLayout.LayoutParams).apply {
                height = dp(44)
                topMargin = dp(4)
            }
        }
    }

    private fun solidButton(
        label: String,
        backgroundColor: Int,
        textColor: Int,
        onClick: () -> Unit
    ): Button {
        return baseButton(label).apply {
            styleButton(this, backgroundColor, textColor)
            setOnClickListener { onClick() }
        }
    }

    private fun baseButton(label: String): Button {
        return Button(this).apply {
            text = label
            textSize = 15f
            setAllCaps(false)
            typeface = Typeface.DEFAULT_BOLD
            minHeight = 0
            minimumHeight = 0
            stateListAnimator = null
            setPadding(dp(12), 0, dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(54)
            ).apply { topMargin = dp(10) }
        }
    }

    private fun styleButton(button: Button, backgroundColor: Int, textColor: Int) {
        button.setTextColor(textColor)
        button.background = roundedRect(backgroundColor, dp(12))
    }

    private fun marginParams(
        topMargin: Int = 0,
        bottomMargin: Int = 0
    ): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            this.topMargin = topMargin
            this.bottomMargin = bottomMargin
        }
    }

    private fun roundedRect(
        color: Int,
        radius: Int,
        strokeColor: Int? = null,
        strokeWidth: Int = 0
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (strokeColor != null && strokeWidth > 0) {
                setStroke(strokeWidth, strokeColor)
            }
        }
    }

    private fun ovalWithGlow(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(withAlpha(color, 0x30))
            setStroke(dp(2), withAlpha(color, 0x99))
        }
    }

    // -- Helpers ------------------------------------------------------------

    private fun recordGateDuration() {
        if (startedAtMs <= 0L) return
        if (showingEmergencyExit && !emergencyExitResolved) {
            TrackerConfig.recordEmergencyExitCancelled(this, "focus_gate")
            emergencyExitResolved = true
        }
        if (
            intent.getBooleanExtra(EXTRA_RETURN_TO_PACKAGE, false) ||
            intent.getBooleanExtra(EXTRA_APP_LIMIT_REACHED, false) ||
            intent.getBooleanExtra(EXTRA_YOUTUBE_SHORTS_BLOCK, false)
        ) {
            startedAtMs = 0L
            return
        }
        TrackerConfig.recordBlockedDuration(
            this,
            packageNameToBlock,
            blockedAppName.ifBlank { packageNameToBlock },
            System.currentTimeMillis() - startedAtMs
        )
        startedAtMs = 0L
    }

    // "Home" from the focus gate means OUR focus home screen, not the phone
    // launcher -- leaving a blocked app should keep the user inside the app.
    private fun goHome() {
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        val opened = if (launch != null) {
            runCatching { startActivity(launch) }.isSuccess
        } else {
            false
        }
        if (!opened) {
            runCatching {
                startActivity(
                    Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                )
            }
        }
        finish()
    }

    private fun returnToFocusDestination() {
        if (
            intent.getBooleanExtra(EXTRA_BREAK_RESTRICTED, false) &&
            TrackerConfig.isFocusBreakActive(this)
        ) {
            openFirstBreakApp()
            return
        }
        if (
            intent.getBooleanExtra(EXTRA_RETURN_TO_PACKAGE, false) &&
            packageNameToBlock.isNotBlank()
        ) {
            openBlockedApp()
        } else if (openSafeStudyApp()) {
            finish()
        } else {
            goHome()
        }
    }

    private fun openSafeStudyApp(): Boolean {
        if (
            safeReturnPackage.isBlank() ||
            safeReturnPackage == packageNameToBlock ||
            !TrackerConfig.isStudyModeEnabled(this) ||
            !TrackerConfig.studyAllowedPackages(this).contains(safeReturnPackage)
        ) {
            return false
        }
        val launch = packageManager.getLaunchIntentForPackage(safeReturnPackage)
            ?: return false
        launch.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
        return runCatching { startActivity(launch) }.isSuccess
    }

    private fun openBlockedApp() {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageNameToBlock)
        val opened = runCatching {
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
            } else {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageNameToBlock")
                    )
                )
            }
        }.isSuccess
        if (!opened) {
            goHome()
            return
        }
        finish()
    }

    private fun openFirstBreakApp() {
        val opened = TrackerConfig.focusBreakSelectedPackages(this).any { openPackage(it) }
        if (!opened) goHome()
    }

    private fun cancelBreakPicker() {
        if (intent.getBooleanExtra(EXTRA_RETURN_TO_PACKAGE, false)) {
            recordGateDuration()
            openBlockedApp()
        } else {
            setContentView(buildContent())
        }
    }

    private fun openPackage(targetPackage: String): Boolean {
        if (targetPackage.isBlank()) return false
        val launch = packageManager.getLaunchIntentForPackage(targetPackage) ?: return false
        launch.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
        val opened = runCatching { startActivity(launch) }.isSuccess
        if (opened) finish()
        return opened
    }

    private fun wordCount(value: String?): Int {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return 0
        return trimmed.split(Regex("\\s+")).size
    }

    private fun initialsFor(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "?"
        val parts = trimmed.split(Regex("\\s+"))
        return if (parts.size >= 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) {
            "${parts[0][0]}${parts[1][0]}".uppercase()
        } else {
            trimmed.take(1).uppercase()
        }
    }

    private fun formatClock(milliseconds: Long): String {
        val totalSeconds = (milliseconds / 1000L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d".format(minutes, seconds)
    }

    private fun formatBreakRequestDuration(milliseconds: Long): String {
        val totalMinutes = ((milliseconds + 59_999L) / 60_000L).coerceAtLeast(1L)
        return if (totalMinutes == 1L) "1 min" else "$totalMinutes min"
    }

    private fun formatUsageDuration(milliseconds: Long): String {
        val minutes = (milliseconds / 60_000L).coerceAtLeast(0L)
        val hours = minutes / 60L
        val remainder = minutes % 60L
        return when {
            hours <= 0L -> "$remainder min"
            remainder == 0L -> "$hours hr"
            else -> "$hours hr $remainder min"
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return (alpha shl 24) or (color and 0x00FFFFFF)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun youtubeShortsBlockingEnabled(): Boolean {
        return getSharedPreferences(
            "FlutterSharedPreferences",
            MODE_PRIVATE
        ).getBoolean("flutter.youtubeShortsBlockingEnabled", false)
    }

    companion object {
        const val EXTRA_PACKAGE_NAME = "packageName"
        const val EXTRA_APP_NAME = "appName"
        const val EXTRA_SHOW_EMERGENCY_EXIT = "showEmergencyExit"
        const val EXTRA_SHOW_END_CONFIRMATION = "showEndConfirmation"
        const val EXTRA_RETURN_TO_PACKAGE = "returnToPackage"
        const val EXTRA_SAFE_RETURN_PACKAGE = "safeReturnPackage"
        const val EXTRA_BREAK_RESTRICTED = "breakRestricted"
        const val EXTRA_SHOW_BREAK_PICKER = "showBreakPicker"
        const val EXTRA_APP_LIMIT_REACHED = "appLimitReached"
        const val EXTRA_MOTIVATION_INDEX = "motivationIndex"
        const val EXTRA_YOUTUBE_SHORTS_BLOCK = "youtubeShortsBlock"
        const val EXTRA_SHORT_FINGERPRINT = "shortFingerprint"
        const val ACTION_GATE_VISIBLE =
            "com.trustissue.child.action.FOCUS_GATE_VISIBLE"

        private const val MIN_WORDS = 10
        private const val EXIT_COUNTDOWN = 60
        private const val MAX_BREAK_APPS = 3

        private val NIGHT = 0xFF090B0A.toInt()
        private val CARD = 0xFF151816.toInt()
        private val SURFACE = 0xFF1B1F1C.toInt()
        private val CARD_BORDER = 0xFF2A2F2B.toInt()
        private val GHOST = 0xFF1B1F1C.toInt()
        private val LIME = 0xFFB8E62E.toInt()
        private val LIME_TEXT = 0xFF0C1A05.toInt()
        private val EMERALD = 0xFF39D98A.toInt()
        private val WARNING = 0xFFFFC24B.toInt()
        private val DANGER = 0xFFFF5A5F.toInt()
        private val TEXT_PRIMARY = 0xFFF3F7F2.toInt()
        private val TEXT_SECONDARY = 0xFFBCC8B9.toInt()
        private val TEXT_MUTED = 0xFF869184.toInt()
    }
}
