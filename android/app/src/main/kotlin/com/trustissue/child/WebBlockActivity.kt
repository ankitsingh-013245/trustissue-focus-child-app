package com.trustissue.child

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

/**
 * Local explanation shown after the DNS policy rejects a browser navigation.
 * It receives only the hostname and a coarse category; no page content exists
 * in this activity or is persisted by Web Protection.
 */
class WebBlockActivity : ComponentActivity() {
    private var domain = ""
    private var category = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    finishGateTask()
                }
            }
        )
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = NIGHT
        setFinishOnTouchOutside(false)
        showRequest(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showRequest(intent)
    }

    private fun showRequest(source: Intent) {
        domain = WebProtectionConfig.normalizeDomain(
            source.getStringExtra(EXTRA_DOMAIN).orEmpty()
        ).orEmpty()
        category = source.getStringExtra(EXTRA_CATEGORY).orEmpty()
        val protectionEnabled =
            TrackerConfig.isStudyModeEnabled(this) ||
                WebProtectionConfig.isGlobalProtectionEnabled(this)
        if (
            domain.isBlank() ||
            !protectionEnabled ||
            !WebProtectionConfig.shouldRun(this)
        ) {
            finishGateTask()
            return
        }
        setContentView(buildContent())
    }

    private fun buildContent(): FrameLayout {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(28), dp(18), dp(28))
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(24), dp(22), dp(20))
            background = roundedRect(CARD, dp(26), CARD_BORDER, dp(1))
            elevation = dp(18).toFloat()
        }

        card.addView(
            TextView(this).apply {
                text = "WEB PROTECTION"
                setTextColor(LIME)
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.14f
                includeFontPadding = false
            }
        )
        card.addView(
            TextView(this).apply {
                text = "X"
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 31f
                typeface = Typeface.DEFAULT_BOLD
                background = oval(DANGER_TINT, DANGER_BORDER)
            },
            LinearLayout.LayoutParams(dp(70), dp(70)).apply {
                topMargin = dp(18)
            }
        )
        card.addView(
            TextView(this).apply {
                text = titleForCategory()
                setTextColor(TEXT_PRIMARY)
                textSize = 23f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            },
            marginParams(topMargin = dp(16))
        )
        card.addView(
            TextView(this).apply {
                text = detailForCategory()
                setTextColor(TEXT_SECONDARY)
                textSize = 14f
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.18f)
            },
            marginParams(topMargin = dp(8), bottomMargin = dp(16))
        )
        card.addView(domainPanel())
        card.addView(
            TextView(this).apply {
                text = "Your search text, page content and browser history were not read."
                setTextColor(TEXT_MUTED)
                textSize = 11.5f
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.12f)
                setPadding(dp(10), dp(10), dp(10), dp(10))
            },
            marginParams(topMargin = dp(8), bottomMargin = dp(4))
        )
        val unavailable = category == "protection_unavailable"
        card.addView(
            solidButton(
                if (unavailable) "Try browser again" else "Back to safe browsing"
            ) {
                finishGateTask()
            }
        )
        card.addView(
            ghostButton(
                if (unavailable) "Continue Focus without browser" else "Close browser"
            ) {
                runCatching {
                    startActivity(
                        Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                    )
                }
                finishGateTask()
            }
        )

        val width = minOf(
            resources.displayMetrics.widthPixels - dp(36),
            dp(430)
        ).coerceAtLeast(dp(280))
        page.addView(
            card,
            LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        val scroll = ScrollView(this).apply {
            isFillViewport = true
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
        return root
    }

    private fun domainPanel(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = roundedRect(SURFACE, dp(16), CARD_BORDER, dp(1))
            addView(
                TextView(this@WebBlockActivity).apply {
                    text = if (category == "protection_unavailable") {
                        "Browser access paused"
                    } else {
                        domain
                    }
                    setTextColor(TEXT_PRIMARY)
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    maxLines = 2
                }
            )
            addView(
                TextView(this@WebBlockActivity).apply {
                    text = labelForCategory()
                    setTextColor(DANGER)
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    letterSpacing = 0.06f
                    gravity = Gravity.CENTER
                },
                marginParams(topMargin = dp(4))
            )
        }
    }

    private fun titleForCategory(): String {
        return when (category) {
            "adult" -> "Adult content blocked"
            "family_filter" -> "Unsafe website blocked"
            "proxy", "secure_dns" -> "Bypass website blocked"
            "blocked_app" -> "Blocked app website"
            "custom", "focus_custom", "global_custom" -> "Website blocked by you"
            "protection_unavailable" -> "Browser paused for safety"
            else -> "Website blocked"
        }
    }

    private fun detailForCategory(): String {
        return when (category) {
            "adult" -> "18+ content stays protected whenever browser access is allowed."
            "family_filter" ->
                "Family-safe DNS identified this domain as adult or malicious content."
            "proxy", "secure_dns" ->
                "Proxy and alternate-DNS websites cannot bypass Website Protection."
            "blocked_app" ->
                "The matching app is blocked, so its web version is blocked too."
            "global_custom" ->
                "You added this domain to Global Website Protection."
            "custom", "focus_custom" ->
                "You added this domain to the current Focus web rules."
            "protection_unavailable" ->
                "Browser access will return automatically after secure filtering is healthy."
            else -> "This website is not available during the current Focus session."
        }
    }

    private fun labelForCategory(): String {
        return when (category) {
            "adult" -> "ALWAYS PROTECTED"
            "family_filter" -> "FAMILY-SAFE FILTER"
            "proxy", "secure_dns" -> "BYPASS PROTECTION"
            "blocked_app" -> "APP + WEB RULE"
            "custom", "focus_custom", "global_custom" -> "CUSTOM RULE"
            "protection_unavailable" -> "FAIL-SAFE ACTIVE"
            else -> "FOCUS RULE"
        }
    }

    private fun solidButton(label: String, onClick: () -> Unit): Button {
        return baseButton(label).apply {
            setTextColor(LIME_TEXT)
            background = roundedRect(LIME, dp(27))
            setOnClickListener { onClick() }
        }
    }

    private fun ghostButton(label: String, onClick: () -> Unit): Button {
        return baseButton(label).apply {
            setTextColor(TEXT_PRIMARY)
            background = roundedRect(GHOST, dp(27), CARD_BORDER, dp(1))
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
            setPadding(dp(14), 0, dp(14), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(54)
            ).apply { topMargin = dp(10) }
        }
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

    private fun oval(color: Int, strokeColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(2), strokeColor)
        }
    }

    private fun finishGateTask() {
        runCatching { finishAndRemoveTask() }
            .onFailure { finish() }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        const val EXTRA_DOMAIN = "webBlockedDomain"
        const val EXTRA_CATEGORY = "webBlockedCategory"

        private val NIGHT = 0xFF05100C.toInt()
        private val CARD = 0xFA0A150F.toInt()
        private val SURFACE = 0x14FFFFFF.toInt()
        private val CARD_BORDER = 0x2AFFFFFF.toInt()
        private val GHOST = 0x1FFFFFFF.toInt()
        private val LIME = 0xFFB8E62E.toInt()
        private val LIME_TEXT = 0xFF0C1A05.toInt()
        private val DANGER = 0xFFFF6B70.toInt()
        private val DANGER_TINT = 0x30FF5A5F.toInt()
        private val DANGER_BORDER = 0x99FF5A5F.toInt()
        private val TEXT_PRIMARY = 0xFFF3F7F2.toInt()
        private val TEXT_SECONDARY = 0xFFBCC8B9.toInt()
        private val TEXT_MUTED = 0xFF869184.toInt()
    }
}
