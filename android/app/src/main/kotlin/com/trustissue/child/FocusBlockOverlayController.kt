package com.trustissue.child

import android.app.Service
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Full-screen compatible-mode safety net.
 *
 * Its opaque, animation-free shield is shown before FocusGateActivity is
 * launched. The richer fallback panel is revealed only if the Activity has not
 * taken over quickly, avoiding a double-render flash during normal blocking.
 * If an OEM rejects the background Activity launch, the overlay remains in
 * place so the blocked app is never left usable.
 */
class FocusBlockOverlayController(private val service: Service) {
    private val windowManager =
        service.getSystemService(Service.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var root: View? = null
    private var fallbackPromotion: Runnable? = null
    private var visiblePackage = ""
    private var visibleAppLimitReached = false

    fun show(
        appName: String,
        appPackage: String = "",
        appLimitReached: Boolean = false,
        motivationIndex: Int = GateMotivation.indexFor(
            appPackage,
            System.currentTimeMillis()
        ),
        onReturnToFocus: () -> Unit
    ): Boolean {
        val normalizedPackage = appPackage.trim()
        if (
            root != null &&
            visiblePackage == normalizedPackage &&
            visibleAppLimitReached == appLimitReached
        ) {
            return true
        }
        destroy()
        val compactHeight = service.resources.configuration.screenHeightDp < 700
        val remainingMs = TrackerConfig.remainingStudySessionMs(service)
        val stopwatch = TrackerConfig.isStopwatchSession(service)
        val displayedMs = if (stopwatch) {
            (TrackerConfig.configuredStudyDurationMs(service) - remainingMs)
                .coerceAtLeast(0L)
        } else {
            remainingMs
        }
        val buildFallbackPanel = {
            LinearLayout(service).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(28), dp(28), dp(28), dp(30))
                addView(
                    TextView(service).apply {
                        text = "TrustIssue"
                        setTextColor(Color.WHITE)
                        textSize = 18f
                        typeface = Typeface.DEFAULT_BOLD
                        gravity = Gravity.CENTER
                    }
                )
                addView(
                    TextView(service).apply {
                        text = formatClock(displayedMs)
                        setTextColor(Color.WHITE)
                        textSize = if (compactHeight) 43f else 50f
                        typeface = Typeface.create("sans-serif", Typeface.BOLD)
                        fontFeatureSettings = "tnum"
                        letterSpacing = 0.035f
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                        setPadding(0, dp(if (compactHeight) 8 else 14), 0, 0)
                    }
                )
                if (!TrackerConfig.isAppStudyMode(service)) {
                    addView(
                        ImageView(service).apply {
                            setImageResource(R.drawable.ic_focus_book)
                            imageTintList = ColorStateList.valueOf(EMERALD)
                            setPadding(dp(5), dp(5), dp(5), dp(5))
                            background = roundedRect(
                                withAlpha(EMERALD, 0x18),
                                dp(10),
                                withAlpha(EMERALD, 0x55),
                                dp(1)
                            )
                            contentDescription = "Book focus"
                        },
                        LinearLayout.LayoutParams(dp(30), dp(30)).apply {
                            topMargin = dp(8)
                        }
                    )
                }
                addView(
                    View(service),
                    LinearLayout.LayoutParams(
                        1,
                        0,
                        if (compactHeight) 0.18f else 0.35f
                    )
                )
                addView(motivationIllustration(compactHeight))
                addView(
                    TextView(service).apply {
                        text = "\u201c${GateMotivation.quote(motivationIndex)}\u201d"
                        setTextColor(TEXT_SECONDARY)
                        textSize = if (compactHeight) 14.5f else 16f
                        typeface = Typeface.create("sans-serif", Typeface.ITALIC)
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                        maxLines = 2
                        setLineSpacing(0f, 1.12f)
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dp(if (compactHeight) 10 else 14)
                    }
                )
                addView(
                    View(service),
                    LinearLayout.LayoutParams(
                        1,
                        0,
                        if (compactHeight) 0.22f else 0.42f
                    )
                )
                addView(blockedAppSummary(appName, appPackage, appLimitReached))
                addView(
                    Button(service).apply {
                        text =
                            if (appLimitReached) "Stay away for today" else "Stay in Focus"
                        isAllCaps = false
                        setTextColor(Color.rgb(9, 11, 10))
                        textSize = 15f
                        typeface = Typeface.DEFAULT_BOLD
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = dp(14).toFloat()
                            setColor(Color.rgb(181, 255, 71))
                        }
                        setOnClickListener {
                            onReturnToFocus()
                            destroy()
                        }
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(52)
                    ).apply { topMargin = dp(10) }
                )
            }
        }
        val overlay = FrameLayout(service).apply {
            setBackgroundColor(Color.rgb(9, 11, 10))
            isClickable = true
            isFocusable = false
            setOnClickListener { }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        )
        return runCatching {
            windowManager.addView(overlay, params)
            root = overlay
            visiblePackage = normalizedPackage
            visibleAppLimitReached = appLimitReached
            val promoteFallback = Runnable {
                if (root === overlay) {
                    val panel = buildFallbackPanel()
                    panel.alpha = 0f
                    overlay.addView(
                        panel,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    )
                    panel.animate()
                        .alpha(1f)
                        .setDuration(140L)
                        .start()
                }
            }
            fallbackPromotion = promoteFallback
            mainHandler.postDelayed(promoteFallback, FALLBACK_PROMOTION_DELAY_MS)
            true
        }.onFailure {
            TrustIssueDebugLog.append(
                service,
                "FocusUsage",
                "W",
                "BLOCK_OVERLAY_FAILED error=${it.javaClass.simpleName}"
            )
        }.getOrDefault(false)
    }

    fun hide() {
        destroy()
    }

    fun destroy() {
        fallbackPromotion?.let(mainHandler::removeCallbacks)
        fallbackPromotion = null
        visiblePackage = ""
        visibleAppLimitReached = false
        val view = root ?: return
        root = null
        runCatching { windowManager.removeView(view) }
    }

    private fun motivationIllustration(compactHeight: Boolean): View {
        val size = dp(if (compactHeight) 112 else 142)
        val bitmap = runCatching {
            service.assets.open(GateMotivation.illustrationAsset)
                .use(BitmapFactory::decodeStream)
        }.getOrNull()
        return if (bitmap != null) {
            ImageView(service).apply {
                setImageBitmap(bitmap)
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = "A focused student studying"
                background = roundedRect(SURFACE, dp(24), CARD_BORDER, dp(1))
                clipToOutline = true
                layoutParams = LinearLayout.LayoutParams(size, size)
            }
        } else {
            ImageView(service).apply {
                setImageResource(R.drawable.ic_focus_book)
                imageTintList = ColorStateList.valueOf(EMERALD)
                setPadding(dp(34), dp(34), dp(34), dp(34))
                background = roundedRect(SURFACE, dp(24), CARD_BORDER, dp(1))
                contentDescription = "Focus"
                layoutParams = LinearLayout.LayoutParams(size, size)
            }
        }
    }

    private fun blockedAppSummary(
        appName: String,
        appPackage: String,
        appLimitReached: Boolean
    ): LinearLayout {
        val iconSize = dp(52)
        val drawable = appPackage.takeIf { it.isNotBlank() }?.let { packageName ->
            runCatching { service.packageManager.getApplicationIcon(packageName) }.getOrNull()
        }
        return LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedRect(CARD, dp(16), CARD_BORDER, dp(1))
            addView(
                if (drawable != null) {
                    ImageView(service).apply {
                        setImageDrawable(drawable)
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                        setPadding(dp(5), dp(5), dp(5), dp(5))
                        background = roundedRect(0xFFF4F6F3.toInt(), dp(15))
                        contentDescription = appName
                    }
                } else {
                    TextView(service).apply {
                        text = appName.trim().take(1).uppercase().ifBlank { "?" }
                        gravity = Gravity.CENTER
                        setTextColor(Color.WHITE)
                        textSize = 18f
                        typeface = Typeface.DEFAULT_BOLD
                        background = roundedRect(0xFF35506B.toInt(), dp(15))
                    }
                },
                LinearLayout.LayoutParams(iconSize, iconSize)
            )
            addView(
                TextView(service).apply {
                    text = if (appLimitReached) {
                        "$appName reached its\ndaily limit"
                    } else {
                        "$appName is blocked\nduring Focus"
                    }
                    setTextColor(Color.WHITE)
                    textSize = 17f
                    typeface = Typeface.DEFAULT_BOLD
                    includeFontPadding = false
                    maxLines = 2
                },
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply { marginStart = dp(13) }
            )
        }
    }

    private fun blockedAppHero(appName: String, appPackage: String): FrameLayout {
        val heroSize = dp(108)
        val iconSize = dp(88)
        val drawable = appPackage.takeIf { it.isNotBlank() }?.let { packageName ->
            runCatching { service.packageManager.getApplicationIcon(packageName) }.getOrNull()
        }
        return FrameLayout(service).apply {
            layoutParams = LinearLayout.LayoutParams(heroSize, heroSize)
            val icon = if (drawable != null) {
                ImageView(service).apply {
                    setImageDrawable(drawable)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    background = roundedRect(Color.rgb(244, 246, 243), dp(26))
                    contentDescription = appName
                }
            } else {
                TextView(service).apply {
                    text = appName.trim().take(1).uppercase().ifBlank { "?" }
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    textSize = 28f
                    typeface = Typeface.DEFAULT_BOLD
                    background = roundedRect(Color.rgb(53, 80, 107), dp(26))
                }
            }
            addView(icon, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))
            addView(
                ImageView(service).apply {
                    setImageResource(android.R.drawable.ic_lock_lock)
                    imageTintList = ColorStateList.valueOf(Color.rgb(9, 11, 10))
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dp(9), dp(9), dp(9), dp(9))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.rgb(255, 194, 75))
                        setStroke(dp(2), Color.rgb(9, 11, 10))
                    }
                    contentDescription = "Blocked"
                },
                FrameLayout.LayoutParams(dp(36), dp(36), Gravity.END or Gravity.BOTTOM)
            )
        }
    }

    private fun roundedRect(
        color: Int,
        radius: Int,
        strokeColor: Int? = null,
        strokeWidth: Int = 0
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(color)
            if (strokeColor != null && strokeWidth > 0) {
                setStroke(strokeWidth, strokeColor)
            }
        }
    }

    private fun formatClock(milliseconds: Long): String {
        val totalSeconds = (milliseconds / 1_000L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return "%02d:%02d".format(minutes, seconds)
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return (alpha shl 24) or (color and 0x00FFFFFF)
    }

    private fun dp(value: Int): Int =
        (value * service.resources.displayMetrics.density).toInt()

    private companion object {
        const val FALLBACK_PROMOTION_DELAY_MS = 600L
        val CARD = 0xFF151816.toInt()
        val SURFACE = 0xFF1B1F1C.toInt()
        val CARD_BORDER = 0xFF2A2F2B.toInt()
        val EMERALD = 0xFF39D98A.toInt()
        val TEXT_SECONDARY = 0xFFBCC8B9.toInt()
    }
}
