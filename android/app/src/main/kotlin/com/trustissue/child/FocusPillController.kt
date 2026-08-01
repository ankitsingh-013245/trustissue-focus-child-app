package com.trustissue.child

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Compact, package-safe focus HUD. Book Study keeps it global, while App
 * Study shows it only over the selected study/break app.
 *
 * Accessibility mode uses TYPE_ACCESSIBILITY_OVERLAY. Compatible mode supplies
 * TYPE_APPLICATION_OVERLAY and therefore uses Android's draw-over-apps access.
 * The controller never inspects the covered app's view hierarchy.
 */
class FocusPillController(
    private val service: Service,
    private val onQuickToolEnded: (targetPackage: String, sourcePackage: String) -> Unit =
        { _, _ -> },
    private val windowType: Int = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
) {
    private enum class DisplayState {
        PEEK,
        EXPANDED
    }

    private val windowManager =
        service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val positionPreferences =
        service.getSharedPreferences(POSITION_PREFS, Context.MODE_PRIVATE)
    private val touchSlop = ViewConfiguration.get(service).scaledTouchSlop
    private var root: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var state = DisplayState.EXPANDED
    private var packageName = ""
    private var appName = ""
    private var timerText: TextView? = null
    private var statusText: TextView? = null
    private var lastBreakActive = false
    private var globalAcrossApps = false
    private var lastLoggedTimerBucket = Long.MIN_VALUE
    private var lastOverlayMutationAtElapsedMs = 0L
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartWindowX = 0
    private var dragStartWindowY = 0
    private var dragging = false

    private val tick = object : Runnable {
        override fun run() {
            runCatching { runTick() }
                .onFailure {
                    TrustIssueDebugLog.append(
                        service,
                        "FocusPill",
                        "E",
                        "PILL_TICK_FAILED error=${it.javaClass.simpleName}"
                    )
                    if (isAttached() && isVisible()) {
                        handler.postDelayed(this, TICK_MS)
                    }
                }
        }

        private fun runTick() {
            if (
                !TrackerConfig.isStudyModeEnabled(service)
            ) {
                destroy("session_inactive")
                return
            }
            val quickTarget = TrackerConfig.quickStudyToolPackage(service)
            if (
                quickTarget == packageName &&
                TrackerConfig.quickStudyToolRemainingMs(service) <= 0L
            ) {
                val sourcePackage = TrackerConfig.quickStudyToolSourcePackage(service)
                TrackerConfig.endQuickStudyToolAccess(service, "pill_expired")
                val expiredTarget = packageName
                hide("quick_tool_expired")
                onQuickToolEnded(expiredTarget, sourcePackage)
                return
            }
            if (TrackerConfig.completeStudySessionIfFinished(service)) {
                destroy("session_complete")
                return
            }
            val breakActive = TrackerConfig.isFocusBreakActive(service)
            if (lastBreakActive && !breakActive) {
                TrackerConfig.resumeStudyTimerIfNeeded(service, packageName, appName)
            }
            if (breakActive != lastBreakActive && state == DisplayState.EXPANDED) {
                render()
            } else {
                updateTimer()
            }
            lastBreakActive = breakActive
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val autoHide = Runnable {
        runCatching {
            when (state) {
                DisplayState.EXPANDED -> {
                    state = DisplayState.PEEK
                    render()
                    log("PILL_COLLAPSE_TO_HANDLE")
                }
                DisplayState.PEEK -> Unit
            }
        }.onFailure {
            TrustIssueDebugLog.append(
                service,
                "FocusPill",
                "E",
                "PILL_AUTO_HIDE_FAILED error=${it.javaClass.simpleName}"
            )
        }
    }

    fun showFor(
        foregroundPackage: String,
        foregroundAppName: String,
        global: Boolean = false
    ) {
        discardDetachedOverlayIfNeeded()
        val packageChanged = packageName != foregroundPackage
        val wasTemporarilyHidden = root?.visibility != View.VISIBLE
        packageName = foregroundPackage
        appName = foregroundAppName.ifBlank { foregroundPackage }
        globalAcrossApps = global
        if (root == null) {
            state = DisplayState.EXPANDED
            if (createOverlay()) {
                scheduleAutoHide(EXPANDED_VISIBLE_MS)
                log("PILL_SHOW mode=${if (global) "book_global" else "app_scoped"} package=$packageName")
            }
        } else if (wasTemporarilyHidden || (packageChanged && !globalAcrossApps)) {
            lastOverlayMutationAtElapsedMs = SystemClock.elapsedRealtime()
            root?.visibility = View.VISIBLE
            state = DisplayState.EXPANDED
            render()
            scheduleAutoHide(EXPANDED_VISIBLE_MS)
            log("PILL_RESUME mode=${if (global) "book_global" else "app_scoped"} package=$packageName")
        } else {
            root?.visibility = View.VISIBLE
            if (packageChanged) log("PILL_CONTEXT package=$packageName")
            updateTimer()
        }
        handler.removeCallbacks(tick)
        if (isAttached() && isVisible()) handler.post(tick)
    }

    fun hide(reason: String = "out_of_scope") {
        val view = root ?: return
        val wasVisible = view.visibility == View.VISIBLE
        handler.removeCallbacks(tick)
        handler.removeCallbacks(autoHide)
        lastOverlayMutationAtElapsedMs = SystemClock.elapsedRealtime()
        view.visibility = View.GONE
        if (wasVisible) log("PILL_SUSPEND reason=$reason")
    }

    fun destroy(reason: String = "terminal") {
        val wasAttached = root != null
        handler.removeCallbacks(tick)
        handler.removeCallbacks(autoHide)
        lastOverlayMutationAtElapsedMs = SystemClock.elapsedRealtime()
        timerText = null
        statusText = null
        root?.let { view -> runCatching { windowManager.removeView(view) } }
        root = null
        params = null
        packageName = ""
        appName = ""
        lastBreakActive = false
        globalAcrossApps = false
        lastLoggedTimerBucket = Long.MIN_VALUE
        if (wasAttached) log("PILL_DESTROY reason=$reason")
    }

    fun isAttached(): Boolean = root?.parent != null

    fun isVisible(): Boolean = isAttached() && root?.visibility == View.VISIBLE

    fun isRecentOverlayMutation(windowMs: Long = OWN_WINDOW_EVENT_GUARD_MS): Boolean {
        val mutationAt = lastOverlayMutationAtElapsedMs
        return mutationAt > 0L &&
            SystemClock.elapsedRealtime() - mutationAt in 0L..windowMs
    }

    private fun createOverlay(): Boolean {
        val overlayRoot = FrameLayout(service)
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = positionPreferences.getInt(POSITION_X_KEY, 0)
            y = positionPreferences.getInt(POSITION_Y_KEY, 0)
        }
        root = overlayRoot
        params = layoutParams
        render()
        return runCatching {
            windowManager.addView(overlayRoot, layoutParams)
            lastOverlayMutationAtElapsedMs = SystemClock.elapsedRealtime()
            overlayRoot.post { restoreAndClampPosition() }
            true
        }.onFailure {
            root = null
            params = null
            TrustIssueDebugLog.append(
                service,
                "FocusPill",
                "W",
                "PILL_ADD_FAILED error=${it.javaClass.simpleName}"
            )
        }.getOrDefault(false)
    }

    private fun discardDetachedOverlayIfNeeded() {
        val view = root ?: return
        if (view.parent != null) return
        handler.removeCallbacks(tick)
        handler.removeCallbacks(autoHide)
        root = null
        params = null
        timerText = null
        statusText = null
        lastBreakActive = false
        lastLoggedTimerBucket = Long.MIN_VALUE
        log("PILL_RECREATE reason=detached_window")
    }

    private fun render() {
        val container = root ?: return
        lastOverlayMutationAtElapsedMs = SystemClock.elapsedRealtime()
        container.removeAllViews()
        timerText = null
        statusText = null
        container.setPadding(0, 0, 0, 0)

        when (state) {
            DisplayState.PEEK -> container.addView(buildPeek())
            DisplayState.EXPANDED -> container.addView(buildExpandedPanel())
        }
        updateTimer()
        params?.let { layoutParams ->
            runCatching { windowManager.updateViewLayout(container, layoutParams) }
        }
        container.post { clampPosition(params?.x ?: 0, params?.y ?: 0) }
    }

    private fun buildPeek(): View {
        return LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            contentDescription = "Open floating Focus timer. Drag to move."
            setPadding(dp(10), dp(8), dp(12), dp(8))
            background = roundedRect(CARD, dp(10), BORDER, dp(1))
            elevation = dp(8).toFloat()
            addView(
                ImageButton(service).apply {
                    setImageResource(R.drawable.ic_focus_expand)
                    setColorFilter(LIME)
                    background = null
                    contentDescription = "Open floating Focus timer"
                    setPadding(dp(1), dp(1), dp(1), dp(1))
                    setOnClickListener { expand() }
                },
                LinearLayout.LayoutParams(dp(21), dp(21))
            )
            addView(
                TextView(service).also { timerText = it }.apply {
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    fontFeatureSettings = "tnum"
                    letterSpacing = 0.025f
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    setSingleLine(true)
                    minWidth = dp(52)
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(21)
                ).apply { marginStart = dp(7) }
            )
            setOnClickListener { expand() }
            enableDragging(this)
        }
    }

    private fun buildTimerPill(): View {
        return LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(13), dp(10), dp(11), dp(10))
            background = roundedRect(CARD, dp(10), BORDER, dp(1))
            elevation = dp(8).toFloat()
            addView(
                View(service).apply { background = oval(LIME) },
                LinearLayout.LayoutParams(dp(8), dp(8))
            )
            addView(
                TextView(service).also { timerText = it }.apply {
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    typeface = Typeface.MONOSPACE
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    setSingleLine(true)
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(7) }
            )
            contentDescription = "Focus time remaining"
            setOnClickListener { expand() }
        }
    }

    private fun buildExpandedPanel(): View {
        val breakActive = TrackerConfig.isFocusBreakActive(service)
        val quickToolActive =
            TrackerConfig.quickStudyToolPackage(service) == packageName &&
                TrackerConfig.quickStudyToolRemainingMs(service) > 0L
        lastBreakActive = breakActive
        return LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            contentDescription = "Floating Focus controls. Drag to move."
            setPadding(dp(12), dp(9), dp(9), dp(9))
            background = roundedRect(CARD, dp(10), BORDER, dp(1))
            elevation = dp(10).toFloat()

            addView(
                LinearLayout(service).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(
                        TextView(service).also { timerText = it }.apply {
                            setTextColor(Color.WHITE)
                            textSize = 18f
                            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                            fontFeatureSettings = "tnum"
                            letterSpacing = 0.025f
                            includeFontPadding = false
                            gravity = Gravity.CENTER
                            setSingleLine(true)
                        }
                    )
                    val initialStatus = currentStatus()
                    addView(
                        TextView(service).also { statusText = it }.apply {
                            text = initialStatus
                            setTextColor(TEXT_MUTED)
                            textSize = 8.5f
                            typeface = Typeface.DEFAULT_BOLD
                            letterSpacing = 0.09f
                            includeFontPadding = false
                            gravity = Gravity.CENTER
                            setSingleLine(true)
                        }
                    )
                },
                LinearLayout.LayoutParams(dp(86), LinearLayout.LayoutParams.WRAP_CONTENT)
            )

            addView(actionButton(
                icon = if (quickToolActive) {
                    R.drawable.ic_focus_return
                } else {
                    R.drawable.ic_focus_break
                },
                tint = if (quickToolActive || breakActive) LIME else AMBER,
                label = when {
                    quickToolActive -> "End tool access and return to study"
                    breakActive -> "End break early"
                    else -> "Take a temporary break"
                },
                enabled = quickToolActive ||
                    breakActive ||
                    TrackerConfig.nextStudyBreakDurationMs(service) > 0L,
                onClick = ::toggleBreak
            ))

            val lockedStrict = TrackerConfig.isLockedStrictFocusModeEnabled(service)
            if (!lockedStrict) {
                val strict = TrackerConfig.isStrictFocusModeEnabled(service)
                addView(actionButton(
                    icon = if (strict) {
                        R.drawable.ic_focus_emergency
                    } else {
                        R.drawable.ic_focus_stop
                    },
                    tint = if (strict) AMBER else DANGER,
                    label = if (strict) "Emergency exit" else "End Focus",
                    enabled = true,
                    onClick = ::requestExit
                ))
            }

            addView(actionButton(
                icon = R.drawable.ic_focus_collapse,
                tint = TEXT_MUTED,
                label = "Collapse Focus controls",
                enabled = true,
                onClick = {
                    state = DisplayState.PEEK
                    render()
                }
            ))
            enableDragging(this)
        }
    }

    private fun actionButton(
        icon: Int,
        tint: Int,
        label: String,
        enabled: Boolean,
        onClick: () -> Unit
    ): ImageButton {
        return ImageButton(service).apply {
            setImageResource(icon)
            setColorFilter(tint)
            background = oval(if (enabled) ACTION_SURFACE else ACTION_DISABLED)
            contentDescription = label
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.38f
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener {
                scheduleAutoHide(EXPANDED_VISIBLE_MS)
                onClick()
            }
            setOnLongClickListener {
                Toast.makeText(service, label, Toast.LENGTH_SHORT).show()
                true
            }
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                marginStart = dp(5)
            }
        }
    }

    private fun enableDragging(view: View) {
        view.setOnTouchListener { target, event ->
            val layoutParams = params ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartRawX = event.rawX
                    dragStartRawY = event.rawY
                    dragStartWindowX = layoutParams.x
                    dragStartWindowY = layoutParams.y
                    dragging = false
                    handler.removeCallbacks(autoHide)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - dragStartRawX
                    val deltaY = event.rawY - dragStartRawY
                    if (!dragging &&
                        (kotlin.math.abs(deltaX) > touchSlop ||
                            kotlin.math.abs(deltaY) > touchSlop)
                    ) {
                        dragging = true
                    }
                    if (dragging) {
                        clampPosition(
                            dragStartWindowX + deltaX.toInt(),
                            dragStartWindowY + deltaY.toInt()
                        )
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        savePosition()
                    } else {
                        target.performClick()
                    }
                    dragging = false
                    if (state == DisplayState.EXPANDED) {
                        scheduleAutoHide(EXPANDED_VISIBLE_MS)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) savePosition()
                    dragging = false
                    if (state == DisplayState.EXPANDED) {
                        scheduleAutoHide(EXPANDED_VISIBLE_MS)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun restoreAndClampPosition() {
        val view = root ?: return
        if (view.width <= 0 || view.height <= 0) return
        val hasSavedPosition =
            positionPreferences.contains(POSITION_X_KEY) &&
                positionPreferences.contains(POSITION_Y_KEY)
        val metrics = service.resources.displayMetrics
        val targetX = if (hasSavedPosition) {
            positionPreferences.getInt(POSITION_X_KEY, 0)
        } else {
            metrics.widthPixels - view.width - dp(8)
        }
        val targetY = if (hasSavedPosition) {
            positionPreferences.getInt(POSITION_Y_KEY, 0)
        } else {
            (metrics.heightPixels - view.height) / 2
        }
        clampPosition(targetX, targetY)
    }

    private fun clampPosition(requestedX: Int, requestedY: Int) {
        val view = root ?: return
        val layoutParams = params ?: return
        val metrics = service.resources.displayMetrics
        val edge = dp(6)
        val maximumX = (metrics.widthPixels - view.width - edge).coerceAtLeast(0)
        val maximumY = (metrics.heightPixels - view.height - edge).coerceAtLeast(0)
        val minimumX = if (maximumX >= edge) edge else 0
        val minimumY = if (maximumY >= edge) edge else 0
        layoutParams.x = requestedX.coerceIn(minimumX, maximumX)
        layoutParams.y = requestedY.coerceIn(minimumY, maximumY)
        lastOverlayMutationAtElapsedMs = SystemClock.elapsedRealtime()
        runCatching { windowManager.updateViewLayout(view, layoutParams) }
    }

    private fun savePosition() {
        val layoutParams = params ?: return
        positionPreferences.edit()
            .putInt(POSITION_X_KEY, layoutParams.x)
            .putInt(POSITION_Y_KEY, layoutParams.y)
            .apply()
    }

    private fun expand() {
        state = DisplayState.EXPANDED
        render()
        scheduleAutoHide(EXPANDED_VISIBLE_MS)
    }

    private fun toggleBreak() {
        if (
            TrackerConfig.quickStudyToolPackage(service) == packageName &&
            TrackerConfig.quickStudyToolRemainingMs(service) > 0L
        ) {
            val targetPackage = packageName
            val sourcePackage = TrackerConfig.quickStudyToolSourcePackage(service)
            TrackerConfig.endQuickStudyToolAccess(service, "manual_end")
            hide("quick_tool_manual_end")
            onQuickToolEnded(targetPackage, sourcePackage)
            return
        }
        if (TrackerConfig.isFocusBreakActive(service)) {
            TrackerConfig.finishFocusBreak(service, "manual_end")
            TrackerConfig.resumeStudyTimerIfNeeded(service, packageName, appName)
            render()
            return
        }
        val durationMs = TrackerConfig.nextStudyBreakDurationMs(service)
        if (durationMs <= 0L) return
        val returnPackage = packageName
        val returnAppName = appName
        hide("break_picker")
        runCatching {
            service.startActivity(
                Intent(service, FocusGateActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                    putExtra(FocusGateActivity.EXTRA_PACKAGE_NAME, returnPackage)
                    putExtra(FocusGateActivity.EXTRA_APP_NAME, returnAppName)
                    putExtra(FocusGateActivity.EXTRA_RETURN_TO_PACKAGE, true)
                    putExtra(FocusGateActivity.EXTRA_SHOW_BREAK_PICKER, true)
                }
            )
        }
    }

    private fun requestExit() {
        if (TrackerConfig.isLockedStrictFocusModeEnabled(service)) {
            Toast.makeText(
                service,
                "Locked Strict ends only when its timer finishes.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val returnPackage = packageName
        val returnAppName = appName
        val strictMode = TrackerConfig.isStrictFocusModeEnabled(service)
        hide("exit_confirmation")
        runCatching {
            service.startActivity(
                Intent(service, FocusGateActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                    putExtra(FocusGateActivity.EXTRA_PACKAGE_NAME, returnPackage)
                    putExtra(FocusGateActivity.EXTRA_APP_NAME, returnAppName)
                    putExtra(FocusGateActivity.EXTRA_RETURN_TO_PACKAGE, true)
                    putExtra(
                        if (strictMode) {
                            FocusGateActivity.EXTRA_SHOW_EMERGENCY_EXIT
                        } else {
                            FocusGateActivity.EXTRA_SHOW_END_CONFIRMATION
                        },
                        true
                    )
                }
            )
        }
    }

    private fun updateTimer() {
        val quickToolActive =
            TrackerConfig.quickStudyToolPackage(service) == packageName &&
                TrackerConfig.quickStudyToolRemainingMs(service) > 0L
        val breakActive = TrackerConfig.isFocusBreakActive(service)
        val remainingMs = when {
            quickToolActive -> TrackerConfig.quickStudyToolRemainingMs(service)
            breakActive -> TrackerConfig.remainingFocusBreakMs(service)
            else -> TrackerConfig.remainingStudySessionMs(service)
        }
        val displayedMs = if (
            !quickToolActive &&
            !breakActive &&
            TrackerConfig.isStopwatchSession(service)
        ) {
            (TrackerConfig.configuredStudyDurationMs(service) - remainingMs)
                .coerceAtLeast(0L)
        } else {
            remainingMs
        }
        timerText?.text = formatClock(displayedMs)
        val status = currentStatus()
        statusText?.text = status
        val timerBucket = displayedMs.coerceAtLeast(0L) / TIMER_LOG_INTERVAL_MS
        if (timerBucket != lastLoggedTimerBucket) {
            lastLoggedTimerBucket = timerBucket
            log("PILL_TICK status=$status displayedMs=$displayedMs package=$packageName")
        }
    }

    private fun currentStatus(): String {
        val quickToolActive =
            TrackerConfig.quickStudyToolPackage(service) == packageName &&
                TrackerConfig.quickStudyToolRemainingMs(service) > 0L
        return when {
            quickToolActive -> "QUICK TOOL"
            TrackerConfig.isFocusBreakActive(service) -> "BREAK"
            TrackerConfig.isLockedStrictFocusModeEnabled(service) -> "LOCKED"
            TrackerConfig.isStopwatchSession(service) -> "STOPWATCH"
            TrackerConfig.isSessionStudyTool(service, packageName) -> "STUDY TOOL"
            !TrackerConfig.isAppStudyMode(service) -> "BOOK"
            else -> "FOCUS"
        }
    }

    private fun scheduleAutoHide(delayMs: Long) {
        handler.removeCallbacks(autoHide)
        handler.postDelayed(autoHide, delayMs)
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

    private fun oval(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    private fun formatClock(milliseconds: Long): String {
        val totalSeconds = ((milliseconds + 999L) / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            "%02d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    private fun dp(value: Int): Int {
        return (value * service.resources.displayMetrics.density).toInt()
    }

    private fun log(message: String) {
        TrustIssueDebugLog.append(service, "FocusPill", "I", message.take(300))
    }

    private companion object {
        const val TICK_MS = 1_000L
        const val EXPANDED_VISIBLE_MS = 5_000L
        const val TIMER_LOG_INTERVAL_MS = 15_000L
        const val OWN_WINDOW_EVENT_GUARD_MS = 2_000L
        const val POSITION_PREFS = "focus_pill_position"
        const val POSITION_X_KEY = "x"
        const val POSITION_Y_KEY = "y"

        val CARD = 0xFF151816.toInt()
        val BORDER = 0xFF272B28.toInt()
        val ACTION_SURFACE = 0xFF202421.toInt()
        val ACTION_DISABLED = 0xFF191C1A.toInt()
        val LIME = 0xFFB8E62E.toInt()
        val AMBER = 0xFFFFC24B.toInt()
        val DANGER = 0xFFFF5A5F.toInt()
        val TEXT_MUTED = 0xFF9AA797.toInt()
    }
}
