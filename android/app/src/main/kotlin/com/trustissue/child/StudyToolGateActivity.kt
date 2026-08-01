package com.trustissue.child

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

/**
 * A short, session-scoped handoff gate for tools opened from a primary study app.
 *
 * Trust is based only on the source/target package transition and Android's
 * verified intent handlers. The activity never reads document or screen content.
 */
class StudyToolGateActivity : ComponentActivity() {
    private val resolver by lazy { StudyToolResolver(this) }

    private var targetPackage = ""
    private var targetAppName = ""
    private var sourcePackage = ""
    private var sourceAppName = ""
    private var toolKind: StudyToolResolver.ToolKind? = null
    private var allowForSession = false
    private var errorText: TextView? = null
    private var primaryAction: Button? = null
    private var sessionCard: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    returnToSource()
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
        targetPackage = source.getStringExtra(EXTRA_TARGET_PACKAGE).orEmpty()
        sourcePackage = source.getStringExtra(EXTRA_SOURCE_PACKAGE).orEmpty()
        targetAppName = source.getStringExtra(EXTRA_TARGET_APP_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: TrackerConfig.appName(this, targetPackage)
        sourceAppName = source.getStringExtra(EXTRA_SOURCE_APP_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: TrackerConfig.appName(this, sourcePackage)
        toolKind = resolver.classify(targetPackage)
        allowForSession = false

        if (!isValidRequest()) {
            returnToSource()
            return
        }
        setContentView(buildContent())
    }

    private fun isValidRequest(): Boolean {
        val kind = toolKind ?: return false
        val requestedKind = intent.getStringExtra(EXTRA_TOOL_KIND).orEmpty()
        return TrackerConfig.isStudyModeEnabled(this) &&
            TrackerConfig.isAppStudyMode(this) &&
            TrackerConfig.studyPolicy(this) == "allowlist" &&
            sourcePackage.isNotBlank() &&
            TrackerConfig.studyAllowedPackages(this).contains(sourcePackage) &&
            targetPackage.isNotBlank() &&
            targetPackage != sourcePackage &&
            !TrackerConfig.studyAllowedPackages(this).contains(targetPackage) &&
            !TrackerConfig.isSessionStudyTool(this, targetPackage) &&
            requestedKind == kind.wireName
    }

    private fun buildContent(): FrameLayout {
        val kind = requireNotNull(toolKind)
        val sessionEligible = canAllowForSession()
        val quickAlreadyUsed = TrackerConfig.hasUsedQuickStudyTool(this, targetPackage)
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(26), dp(18), dp(26))
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(20))
            background = roundedRect(CARD, dp(26), CARD_BORDER, dp(1))
            elevation = dp(18).toFloat()
        }

        card.addView(
            TextView(this).apply {
                text = "STUDY TOOL HANDOFF"
                setTextColor(LIME)
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.14f
                includeFontPadding = false
            }
        )
        card.addView(
            appHeader(kind),
            marginParams(topMargin = dp(16), bottomMargin = dp(16))
        )
        card.addView(
            TextView(this).apply {
                text = "Use $targetAppName for this study task?"
                setTextColor(TEXT_PRIMARY)
                textSize = 23f
                typeface = Typeface.DEFAULT_BOLD
                setLineSpacing(0f, 1.06f)
            }
        )
        card.addView(
            TextView(this).apply {
                text = "$sourceAppName opened this ${kind.title.lowercase()}. " +
                    "Choose temporary access or return to your study app."
                setTextColor(TEXT_SECONDARY)
                textSize = 14f
                setLineSpacing(0f, 1.18f)
            },
            marginParams(topMargin = dp(8), bottomMargin = dp(18))
        )

        card.addView(
            quickAccessCard(quickAlreadyUsed),
            marginParams(bottomMargin = if (sessionEligible) dp(10) else dp(14))
        )

        if (sessionEligible) {
            card.addView(
                sessionAccessCard(),
                marginParams(bottomMargin = dp(14))
            )
        } else {
            val limitedAccessMessage = when (kind) {
                StudyToolResolver.ToolKind.PDF_READER ->
                    "$targetAppName is not your selected default PDF reader. " +
                        "It can use one 2-minute pass; choose it from Focus Home " +
                        "before the next session for full-session access."
                StudyToolResolver.ToolKind.GALLERY ->
                    "Gallery access stays temporary; Android's system picker " +
                        "is handled automatically."
            }
            card.addView(
                infoStrip(limitedAccessMessage),
                marginParams(bottomMargin = dp(14))
            )
        }

        card.addView(
            infoStrip(
                "No chaining: only $sourceAppName can request another study tool. " +
                    "$targetAppName cannot unlock other apps."
            ),
            marginParams(bottomMargin = dp(4))
        )

        errorText = TextView(this).apply {
            visibility = View.GONE
            setTextColor(DANGER)
            textSize = 12f
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.1f)
        }.also {
            card.addView(it, marginParams(topMargin = dp(10)))
        }

        primaryAction = primaryButton("").apply {
            setOnClickListener { grantSelectedAccess() }
        }.also {
            card.addView(it)
        }
        card.addView(
            ghostButton("Back to $sourceAppName") {
                returnToSource()
            }
        )
        card.addView(
            TextView(this).apply {
                text = if (TrackerConfig.isStrictFocusModeEnabled(this@StudyToolGateActivity)) {
                    "Strict Focus remains active. This permission ends automatically with the session."
                } else {
                    "This permission is local to the current Focus session."
                }
                setTextColor(TEXT_MUTED)
                textSize = 11f
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.1f)
            },
            marginParams(topMargin = dp(12))
        )

        refreshSelection(quickAlreadyUsed)

        val cardWidth = minOf(
            resources.displayMetrics.widthPixels - dp(36),
            dp(440)
        ).coerceAtLeast(dp(280))
        page.addView(
            card,
            LinearLayout.LayoutParams(cardWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
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
        return root
    }

    private fun appHeader(kind: StudyToolResolver.ToolKind): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(appIcon())
            addView(
                LinearLayout(this@StudyToolGateActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        TextView(this@StudyToolGateActivity).apply {
                            text = targetAppName
                            setTextColor(TEXT_PRIMARY)
                            textSize = 16f
                            typeface = Typeface.DEFAULT_BOLD
                            maxLines = 1
                        }
                    )
                    addView(
                        TextView(this@StudyToolGateActivity).apply {
                            text = kind.title
                            setTextColor(TEXT_MUTED)
                            textSize = 12f
                        }
                    )
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = dp(12) }
            )
            addView(
                TextView(this@StudyToolGateActivity).apply {
                    text = "FROM STUDY"
                    setTextColor(EMERALD)
                    textSize = 9f
                    typeface = Typeface.DEFAULT_BOLD
                    letterSpacing = 0.08f
                    gravity = Gravity.CENTER
                    setPadding(dp(9), dp(6), dp(9), dp(6))
                    background = roundedRect(EMERALD_TINT, dp(14), EMERALD_BORDER, dp(1))
                }
            )
        }
    }

    private fun appIcon(): View {
        val icon = runCatching {
            packageManager.getApplicationIcon(targetPackage)
        }.getOrNull()
        return if (icon != null) {
            ImageView(this).apply {
                setImageDrawable(icon)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(5), dp(5), dp(5), dp(5))
                background = roundedRect(SURFACE, dp(14), CARD_BORDER, dp(1))
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            }
        } else {
            TextView(this).apply {
                text = initialsFor(targetAppName)
                gravity = Gravity.CENTER
                setTextColor(LIME_TEXT)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                background = roundedRect(LIME, dp(14))
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            }
        }
    }

    private fun quickAccessCard(alreadyUsed: Boolean): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = roundedRect(SURFACE, dp(18), CARD_BORDER, dp(1))
            alpha = if (alreadyUsed) 0.55f else 1f
            addView(
                TextView(this@StudyToolGateActivity).apply {
                    text = if (alreadyUsed) "USED" else "2\nMIN"
                    gravity = Gravity.CENTER
                    setTextColor(if (alreadyUsed) TEXT_MUTED else LIME_TEXT)
                    textSize = if (alreadyUsed) 10f else 13f
                    typeface = Typeface.DEFAULT_BOLD
                    background = roundedRect(
                        if (alreadyUsed) GHOST else LIME,
                        dp(14)
                    )
                },
                LinearLayout.LayoutParams(dp(48), dp(48))
            )
            addView(
                LinearLayout(this@StudyToolGateActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        TextView(this@StudyToolGateActivity).apply {
                            text = if (alreadyUsed) {
                                "Quick access already used"
                            } else {
                                "Quick access"
                            }
                            setTextColor(TEXT_PRIMARY)
                            textSize = 14f
                            typeface = Typeface.DEFAULT_BOLD
                        }
                    )
                    addView(
                        TextView(this@StudyToolGateActivity).apply {
                            text = if (alreadyUsed) {
                                "Available once per tool in each session"
                            } else {
                                "Study timer pauses • ends automatically"
                            }
                            setTextColor(TEXT_MUTED)
                            textSize = 12f
                        }
                    )
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = dp(12) }
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun sessionAccessCard(): LinearLayout {
        val toggle = Switch(this).apply {
            isChecked = false
            showText = false
            contentDescription =
                "Allow $targetAppName as a Study App for this Focus session"
            thumbTintList = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf()
                ),
                intArrayOf(LIME, TEXT_MUTED)
            )
            trackTintList = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf()
                ),
                intArrayOf(LIME_TRACK, GHOST)
            )
        }
        return LinearLayout(this).apply {
            sessionCard = this
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(13), dp(10), dp(13))
            background = roundedRect(SURFACE, dp(18), CARD_BORDER, dp(1))
            addView(
                LinearLayout(this@StudyToolGateActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        TextView(this@StudyToolGateActivity).apply {
                            text = "Allow as Study App"
                            setTextColor(TEXT_PRIMARY)
                            textSize = 14f
                            typeface = Typeface.DEFAULT_BOLD
                        }
                    )
                    addView(
                        TextView(this@StudyToolGateActivity).apply {
                            text = "This session only • study timer counts"
                            setTextColor(TEXT_MUTED)
                            textSize = 12f
                        }
                    )
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(toggle)
            isClickable = true
            isFocusable = true
            setOnClickListener { toggle.isChecked = !toggle.isChecked }
            toggle.setOnCheckedChangeListener { _, checked ->
                allowForSession = checked
                refreshSelection(
                    TrackerConfig.hasUsedQuickStudyTool(
                        this@StudyToolGateActivity,
                        targetPackage
                    )
                )
            }
        }
    }

    private fun infoStrip(message: String): TextView {
        return TextView(this).apply {
            text = message
            setTextColor(TEXT_SECONDARY)
            textSize = 12f
            setLineSpacing(0f, 1.15f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedRect(INFO_SURFACE, dp(14), INFO_BORDER, dp(1))
        }
    }

    private fun refreshSelection(quickAlreadyUsed: Boolean) {
        sessionCard?.background = roundedRect(
            if (allowForSession) LIME_TINT else SURFACE,
            dp(18),
            if (allowForSession) LIME_BORDER else CARD_BORDER,
            dp(1)
        )
        primaryAction?.apply {
            isEnabled = allowForSession || !quickAlreadyUsed
            alpha = if (isEnabled) 1f else 0.48f
            text = when {
                allowForSession -> "Allow for this Focus session"
                quickAlreadyUsed -> "2-minute access already used"
                else -> "Use for 2 minutes"
            }
            background = roundedRect(if (isEnabled) LIME else GHOST, dp(27))
            setTextColor(if (isEnabled) LIME_TEXT else TEXT_MUTED)
        }
    }

    private fun grantSelectedAccess() {
        if (!isValidRequest()) {
            showError("This handoff is no longer available. Return to your study app.")
            return
        }
        val granted = if (allowForSession && canAllowForSession()) {
            TrackerConfig.grantSessionStudyTool(this, targetPackage)
        } else {
            TrackerConfig.grantQuickStudyToolAccess(
                this,
                targetPackage,
                targetAppName,
                sourcePackage
            )
        }
        if (!granted) {
            showError(
                if (allowForSession) {
                    "Session tool limit reached. Return to your study app and choose an existing tool."
                } else {
                    "Quick access was already used for this tool in the current session."
                }
            )
            refreshSelection(TrackerConfig.hasUsedQuickStudyTool(this, targetPackage))
            return
        }
        finishGateTask()
    }

    private fun canAllowForSession(): Boolean {
        return toolKind?.sessionEligible == true &&
            resolver.isSelectedDefaultPdfReader(targetPackage)
    }

    private fun showError(message: String) {
        errorText?.apply {
            text = message
            visibility = View.VISIBLE
        }
    }

    private fun returnToSource() {
        val launch = packageManager.getLaunchIntentForPackage(sourcePackage)?.apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        if (launch != null) {
            runCatching { startActivity(launch) }
        } else {
            runCatching {
                startActivity(
                    Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                )
            }
        }
        finishGateTask()
    }

    private fun finishGateTask() {
        runCatching { finishAndRemoveTask() }
            .onFailure { finish() }
    }

    private fun primaryButton(label: String): Button {
        return baseButton(label).apply {
            background = roundedRect(LIME, dp(27))
            setTextColor(LIME_TEXT)
        }
    }

    private fun ghostButton(label: String, onClick: () -> Unit): Button {
        return baseButton(label).apply {
            background = roundedRect(GHOST, dp(27), CARD_BORDER, dp(1))
            setTextColor(TEXT_PRIMARY)
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

    private fun initialsFor(name: String): String {
        val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return when {
            parts.size >= 2 -> "${parts[0].first()}${parts[1].first()}".uppercase()
            parts.isNotEmpty() -> parts.first().take(1).uppercase()
            else -> "?"
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        const val EXTRA_TARGET_PACKAGE = "studyToolTargetPackage"
        const val EXTRA_TARGET_APP_NAME = "studyToolTargetAppName"
        const val EXTRA_SOURCE_PACKAGE = "studyToolSourcePackage"
        const val EXTRA_SOURCE_APP_NAME = "studyToolSourceAppName"
        const val EXTRA_TOOL_KIND = "studyToolKind"

        private val NIGHT = 0xFF05100C.toInt()
        private val CARD = 0xFA0A150F.toInt()
        private val SURFACE = 0x14FFFFFF.toInt()
        private val CARD_BORDER = 0x2AFFFFFF.toInt()
        private val GHOST = 0x1FFFFFFF.toInt()
        private val LIME = 0xFFB8E62E.toInt()
        private val LIME_TEXT = 0xFF0C1A05.toInt()
        private val LIME_TINT = 0x1FB8E62E.toInt()
        private val LIME_BORDER = 0x80B8E62E.toInt()
        private val LIME_TRACK = 0x66B8E62E.toInt()
        private val EMERALD = 0xFF39D98A.toInt()
        private val EMERALD_TINT = 0x1939D98A.toInt()
        private val EMERALD_BORDER = 0x5539D98A.toInt()
        private val INFO_SURFACE = 0x102D6A55.toInt()
        private val INFO_BORDER = 0x3939D98A.toInt()
        private val DANGER = 0xFFFF6B70.toInt()
        private val TEXT_PRIMARY = 0xFFF3F7F2.toInt()
        private val TEXT_SECONDARY = 0xFFBCC8B9.toInt()
        private val TEXT_MUTED = 0xFF869184.toInt()
    }
}
