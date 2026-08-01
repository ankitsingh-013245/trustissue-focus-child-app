package com.trustissue.child

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * An in-place short-form-content gate that keeps the media app's Activity in
 * the foreground.
 *
 * Launching a TrustIssue Activity causes the media app to lose foreground and
 * can trigger automatic picture-in-picture behavior. An accessibility overlay
 * avoids that task switch: the current item remains underneath the opaque gate
 * and is revealed again only after the user grants a one-item pass.
 */
class YouTubeShortsOverlayController(
    private val service: AccessibilityService
) {
    private val windowManager = service.getSystemService(WindowManager::class.java)

    private var root: View? = null
    private var visibleFingerprint = ""
    private var lastMutationAtElapsedMs = 0L

    fun show(
        fingerprint: String,
        surfaceName: String = "YouTube Shorts",
        itemName: String = "Short",
        leaveLabel: String = "Leave Shorts",
        onWatchOnlyThis: () -> Unit,
        onLeaveSurface: () -> Unit
    ): Boolean {
        val normalizedFingerprint = fingerprint.trim()
        if (normalizedFingerprint.isEmpty()) return false
        if (root != null && visibleFingerprint == normalizedFingerprint) return true

        hide()
        val compactHeight = service.resources.configuration.screenHeightDp < 700
        val overlay = FrameLayout(service).apply {
            setBackgroundColor(NIGHT)
            isClickable = true
            setOnClickListener { }
        }
        val content = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(30), dp(28), dp(32))
        }

        content.addView(
            TextView(service).apply {
                text = "TrustIssue"
                setTextColor(TEXT_PRIMARY)
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
        )
        content.addView(weightedSpacer(if (compactHeight) 0.30f else 0.48f))
        content.addView(
            TextView(service).apply {
                text = "\u25B6"
                setTextColor(LIME)
                textSize = 32f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = oval(withAlpha(LIME, 0x24), withAlpha(LIME, 0x99))
            },
            LinearLayout.LayoutParams(dp(72), dp(72))
        )
        content.addView(
            TextView(service).apply {
                text = "$surfaceName paused"
                setTextColor(TEXT_PRIMARY)
                textSize = if (compactHeight) 27f else 30f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                gravity = Gravity.CENTER
                includeFontPadding = false
            },
            marginParams(topMargin = dp(if (compactHeight) 18 else 24))
        )
        content.addView(
            TextView(service).apply {
                text =
                    "You can watch the $itemName already open. " +
                        "Moving to the next $itemName will pause $surfaceName again."
                setTextColor(TEXT_SECONDARY)
                textSize = if (compactHeight) 14f else 15.5f
                gravity = Gravity.CENTER
                includeFontPadding = false
                setLineSpacing(0f, 1.14f)
            },
            marginParams(topMargin = dp(12))
        )
        content.addView(weightedSpacer(if (compactHeight) 0.42f else 0.70f))
        content.addView(
            actionButton(
                label = "Watch only this",
                fillColor = LIME,
                textColor = LIME_TEXT,
                strokeColor = null,
                onClick = onWatchOnlyThis
            )
        )
        content.addView(
            actionButton(
                label = leaveLabel,
                fillColor = GHOST,
                textColor = TEXT_PRIMARY,
                strokeColor = CARD_BORDER,
                onClick = onLeaveSurface
            )
        )
        overlay.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        )
        return runCatching {
            windowManager.addView(overlay, params)
            root = overlay
            visibleFingerprint = normalizedFingerprint
            lastMutationAtElapsedMs = SystemClock.elapsedRealtime()
            true
        }.onFailure {
            TrustIssueDebugLog.append(
                service,
                "FocusAccessibility",
                "E",
                "SHORT_FORM_OVERLAY_FAILED surface=${surfaceName.take(40)} " +
                    "error=${it.javaClass.simpleName}"
            )
        }.getOrDefault(false)
    }

    fun hide() {
        val view = root ?: return
        root = null
        visibleFingerprint = ""
        lastMutationAtElapsedMs = SystemClock.elapsedRealtime()
        runCatching { windowManager.removeView(view) }
    }

    fun isVisible(): Boolean = root != null

    fun isRecentMutation(): Boolean {
        return isVisible() ||
            SystemClock.elapsedRealtime() - lastMutationAtElapsedMs < mutationGraceMs
    }

    private fun actionButton(
        label: String,
        fillColor: Int,
        textColor: Int,
        strokeColor: Int?,
        onClick: () -> Unit
    ): Button {
        return Button(service).apply {
            text = label
            isAllCaps = false
            setTextColor(textColor)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            minHeight = 0
            minimumHeight = 0
            stateListAnimator = null
            setPadding(dp(14), 0, dp(14), 0)
            background = roundedRect(fillColor, dp(14), strokeColor, dp(1))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(54)
            ).apply { topMargin = dp(10) }
        }
    }

    private fun weightedSpacer(weight: Float): View {
        return View(service).apply {
            layoutParams = LinearLayout.LayoutParams(1, 0, weight)
        }
    }

    private fun marginParams(topMargin: Int = 0): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            this.topMargin = topMargin
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

    private fun oval(color: Int, strokeColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(2), strokeColor)
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return (alpha shl 24) or (color and 0x00FFFFFF)
    }

    private fun dp(value: Int): Int {
        return (value * service.resources.displayMetrics.density).toInt()
    }

    private companion object {
        const val mutationGraceMs = 900L

        val NIGHT = Color.rgb(9, 11, 10)
        val GHOST = Color.rgb(27, 31, 28)
        val CARD_BORDER = Color.rgb(42, 47, 43)
        val LIME = Color.rgb(184, 230, 46)
        val LIME_TEXT = Color.rgb(12, 26, 5)
        val TEXT_PRIMARY = Color.rgb(243, 247, 242)
        val TEXT_SECONDARY = Color.rgb(188, 200, 185)
    }
}
