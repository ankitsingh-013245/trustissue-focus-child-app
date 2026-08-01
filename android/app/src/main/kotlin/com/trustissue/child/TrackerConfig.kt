package com.trustissue.child

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object TrackerConfig {
    private const val prefsName = "FlutterSharedPreferences"
    private const val localPrefsName = "trustissue_local_tracking"
    private const val selfControlDailyStatsKey = "selfControlDailyStats"
    private const val activeStudyStartKey = "activeStudyStartMs"
    private const val activeStudyStartElapsedKey = "activeStudyStartElapsedMs"
    private const val activeStudyBootWallKey = "activeStudyBootWallMs"
    private const val studySessionActiveKey = "studySessionActive"
    private const val studySessionStartedAtKey = "studySessionStartedAtMs"
    private const val studySessionFocusedMsKey = "studySessionFocusedMs"
    private const val studySessionTargetMsKey = "studySessionTargetMs"
    private const val bookStudyReportedMsKey = "bookStudyReportedMs"
    private const val studySessionEndsAtKey = "studySessionEndsAtMs"
    private const val studySessionEndsAtElapsedKey = "studySessionEndsAtElapsedMs"
    private const val studySessionBootWallKey = "studySessionBootWallMs"
    private const val studyBreaksUsedKey = "studyBreaksUsed"
    private const val studyBreakUsedMsKey = "studyBreakUsedMs"
    private const val activeStudyPackageKey = "activeStudyPackageName"
    private const val activeStudyAppNameKey = "activeStudyAppName"
    private const val focusBreakPackageKey = "focusBreakPackageName"
    private const val focusBreakAppNameKey = "focusBreakAppName"
    private const val focusBreakAllowedPackagesKey = "focusBreakAllowedPackages"
    private const val focusBreakUntilKey = "focusBreakUntilMs"
    private const val focusBreakUntilElapsedKey = "focusBreakUntilElapsedMs"
    private const val focusBreakStartedAtKey = "focusBreakStartedAtMs"
    private const val focusBreakStartedElapsedKey = "focusBreakStartedElapsedMs"
    private const val focusBreakBootWallKey = "focusBreakBootWallMs"
    private const val focusBreakAllottedMsKey = "focusBreakAllottedMs"
    private const val sessionStudyToolPackagesKey = "sessionStudyToolPackages"
    private const val quickToolPackageKey = "quickToolPackage"
    private const val quickToolAppNameKey = "quickToolAppName"
    private const val quickToolSourcePackageKey = "quickToolSourcePackage"
    private const val quickToolUntilKey = "quickToolUntilMs"
    private const val quickToolUntilElapsedKey = "quickToolUntilElapsedMs"
    private const val quickToolBootWallKey = "quickToolBootWallMs"
    private const val quickToolUsedPackagesKey = "quickToolUsedPackages"
    private const val minutesPerStudyBreak = 30
    private const val focusBreakSliceMs = 5 * 60 * 1000L
    const val quickStudyToolAccessMs = 2 * 60 * 1000L
    private const val maxSessionStudyTools = 3
    private const val maxStudyDurationMinutes = 24 * 60
    private const val maxStrictStudyDurationMinutes = 8 * 60
    private const val maxLockedStrictStudyDurationMinutes = 3 * 60
    private const val focusBreakAlarmRequestCode = 84051
    private const val focusSessionAlarmRequestCode = 84052
    private const val quickToolAlarmRequestCode = 84053
    private const val sameBootToleranceMs = 2 * 60_000L
    private const val trackingModeApp = "app"
    private const val trackingModeBook = "book"
    private const val sessionTypeTimer = "timer"
    private const val sessionTypeStopwatch = "stopwatch"
    private const val chromiumWebApkPrefix = "org.chromium.webapk."
    private const val chromePackage = "com.android.chrome"

    private fun debugLog(context: Context, level: String, event: String, detail: String = "") {
        val message = if (detail.isBlank()) event else "$event $detail"
        TrustIssueDebugLog.append(context, "TrackerConfig", level, message)
    }

    fun isStudyModeEnabled(context: Context): Boolean {
        return context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .getBoolean("flutter.studyModeEnabled", false)
    }

    fun isStrictFocusModeEnabled(context: Context): Boolean {
        return context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .getBoolean("flutter.studyStrictModeEnabled", false)
    }

    fun isLockedStrictFocusModeEnabled(context: Context): Boolean {
        if (!isStrictFocusModeEnabled(context) || isStopwatchSession(context)) return false
        return context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .getBoolean("flutter.studyLockedStrictModeEnabled", false)
    }

    @Synchronized
    fun setStudyModeEnabled(context: Context, enabled: Boolean): Boolean {
        val effectiveEnabled = enabled &&
            hasValidStudySetup(context) &&
            hasStudyDuration(context) &&
            isStudyDurationWithinLimit(context)
        val settings = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

        if (effectiveEnabled) {
            val strictFocus = isStrictFocusModeEnabled(context)
            if (strictFocus && !StrictFocusDndController.activate(context)) {
                debugLog(context, "E", "STRICT_FOCUS_START_DENIED", "dndUnavailable=true")
                return false
            }
            if (!strictFocus) {
                StrictFocusDndController.release(context)
            }
            var stored = false
            try {
                // Prepare the native session first. The accessibility preference
                // listener can only observe enabled=true after deadlines and
                // counters are ready.
                recordStudyModeChanged(context, true)
                stored = settings.edit()
                    .putBoolean("flutter.studyModeEnabled", true)
                    .commit()
                if (!stored) recordStudyModeChanged(context, false)
                return stored
            } finally {
                // A failed preference write or unexpected native exception must
                // never leave Strict DND active without a Focus session.
                if (strictFocus && !stored) StrictFocusDndController.release(context)
            }
        }

        val stored = settings.edit()
            .putBoolean("flutter.studyModeEnabled", false)
            .commit()
        recordStudyModeChanged(context, false)
        return !enabled && stored
    }

    fun studyPolicy(context: Context): String {
        return context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .getString("flutter.studyModePolicy", "allowlist")
            ?: "allowlist"
    }

    fun studyTrackingMode(context: Context): String {
        val stored = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .getString("flutter.studyTrackingMode", trackingModeBook)
        return if (stored == trackingModeApp) trackingModeApp else trackingModeBook
    }

    fun isAppStudyMode(context: Context): Boolean {
        return studyTrackingMode(context) == trackingModeApp
    }

    fun studySessionType(context: Context): String {
        val stored = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .getString("flutter.studySessionType", sessionTypeTimer)
        return if (stored == sessionTypeStopwatch) sessionTypeStopwatch else sessionTypeTimer
    }

    fun isStopwatchSession(context: Context): Boolean =
        studySessionType(context) == sessionTypeStopwatch

    fun hasStudyDuration(context: Context): Boolean {
        return isStopwatchSession(context) || studyDurationMinutes(context) > 0
    }

    fun isStudyDurationWithinLimit(context: Context): Boolean {
        if (isStopwatchSession(context)) return !isLockedStrictFocusModeEnabled(context)
        val minutes = studyDurationMinutes(context)
        if (minutes <= 0) return false
        return minutes <= maximumStudyDurationMinutes(context)
    }

    fun maximumStudyDurationMinutes(context: Context): Int {
        return when {
            isLockedStrictFocusModeEnabled(context) ->
                maxLockedStrictStudyDurationMinutes
            isStrictFocusModeEnabled(context) -> maxStrictStudyDurationMinutes
            else -> maxStudyDurationMinutes
        }
    }

    fun configuredStudyDurationMs(context: Context): Long {
        if (isStopwatchSession(context)) {
            // Stopwatch counts upward but still has a native safety deadline.
            // Strict stops at 8 hours; Normal stops at 24 hours.
            return maximumStudyDurationMinutes(context) * 60_000L
        }
        val minutes = studyDurationMinutes(context).takeIf { it > 0 }
            ?.coerceIn(1, maximumStudyDurationMinutes(context))
            ?: return 0L
        return minutes * 60_000L
    }

    // Flutter's shared_preferences stores every Dart int as a Long on Android,
    // so reading this key with getInt() throws "Long cannot be cast to Integer".
    // Read it defensively as Long, falling back to Int for older data.
    private fun studyDurationMinutes(context: Context): Int {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        return try {
            prefs.getLong("flutter.studyDurationMinutes", 0L).toInt()
        } catch (_: ClassCastException) {
            try {
                prefs.getInt("flutter.studyDurationMinutes", 0)
            } catch (_: ClassCastException) {
                0
            }
        }
    }

    fun allowedStudyBreaks(context: Context): Int {
        val prefs = context.getSharedPreferences(localPrefsName, Context.MODE_PRIVATE)
        val targetMs = prefs.getLong(studySessionTargetMsKey, 0L)
            .takeIf { it > 0L }
            ?: configuredStudyDurationMs(context)
        val earnedFromMs = if (isStopwatchSession(context)) {
            val remainingMs = if (prefs.getBoolean(studySessionActiveKey, false)) {
                remainingStudySessionMs(context)
            } else {
                targetMs
            }
            (targetMs - remainingMs).coerceIn(0L, targetMs)
        } else {
            targetMs
        }
        val earnedMinutes = (earnedFromMs / 60_000L).toInt().coerceAtLeast(0)
        return (earnedMinutes / minutesPerStudyBreak).coerceAtLeast(0)
    }

    fun dailyAppLimits(context: Context): Map<String, Int> {
        val raw = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .getString("flutter.dailyAppLimitsJson", "{}")
            .orEmpty()
        val source = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        val result = linkedMapOf<String, Int>()
        val keys = source.keys()
        while (keys.hasNext()) {
            val packageName = keys.next().trim()
            val minutes = source.optInt(packageName, 0).coerceIn(0, maxStudyDurationMinutes)
            if (packageName.isNotEmpty() && minutes > 0) result[packageName] = minutes
        }
        return result
    }

    fun hasDailyAppLimits(context: Context): Boolean = dailyAppLimits(context).isNotEmpty()

    fun isDailyAppLimitReached(context: Context, packageName: String): Boolean {
        val limitMinutes = dailyAppLimits(context)[packageName] ?: return false
        return UsageStatsReader.usageTodayMs(context, packageName) >= limitMinutes * 60_000L
    }

    private fun allowedStudyBreakBudgetMs(context: Context): Long {
        return allowedStudyBreaks(context) * focusBreakSliceMs
    }

    fun studyAllowedPackages(context: Context): Set<String> {
        return decodePackageSet(
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                .getString("flutter.studyAllowedPackagesJson", "[]")
        )
    }

    fun studyBlockedPackages(context: Context): Set<String> {
        return decodePackageSet(
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                .getString("flutter.studyBlockedPackagesJson", "[]")
        )
    }

    fun defaultPdfReaderPackage(context: Context): String {
        return context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .getString("flutter.defaultPdfReaderPackage", "")
            .orEmpty()
            .trim()
    }

    // In allowlist mode the session needs at least one allowed app; in
    // blocklist mode it needs at least one blocked app. Checking only the
    // allowlist made blocklist sessions impossible to start.
    fun hasStudyApps(context: Context): Boolean {
        return if (studyPolicy(context) == "blocklist") {
            studyBlockedPackages(context).isNotEmpty()
        } else {
            studyAllowedPackages(context).isNotEmpty()
        }
    }

    fun hasValidStudySetup(context: Context): Boolean {
        if (!hasStudyApps(context)) return false
        return !isAppStudyMode(context) || studyPolicy(context) == "allowlist"
    }

    fun sessionStudyToolPackages(context: Context): Set<String> {
        return decodePackageSet(
            context.getSharedPreferences(localPrefsName, Context.MODE_PRIVATE)
                .getString(sessionStudyToolPackagesKey, "[]")
        )
    }

    fun isSessionStudyTool(context: Context, packageName: String): Boolean {
        return packageName.isNotBlank() &&
            sessionStudyToolPackages(context).contains(packageName)
    }

    @Synchronized
    fun grantSessionStudyTool(
        context: Context,
        packageName: String
    ): Boolean {
        if (
            !isStudyModeEnabled(context) ||
            !isAppStudyMode(context) ||
            packageName.isBlank() ||
            !StudyToolResolver(context).isSelectedDefaultPdfReader(packageName)
        ) {
            return false
        }
        val prefs = context.getSharedPreferences(localPrefsName, Context.MODE_PRIVATE)
        val tools = sessionStudyToolPackages(context).toMutableSet()
        if (!tools.contains(packageName) && tools.size >= maxSessionStudyTools) {
            return false
        }
        tools.add(packageName)
        endQuickStudyToolAccess(context, "promoted_to_session")
        prefs.edit()
            .putString(sessionStudyToolPackagesKey, encodePackageSet(tools))
            .apply()
        debugLog(
            context,
            "I",
            "SESSION_STUDY_TOOL_GRANTED",
            "package=$packageName count=${tools.size}"
        )
        return true
    }

    fun hasUsedQuickStudyTool(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return true
        return decodePackageSet(
            context.getSharedPreferences(localPrefsName, Context.MODE_PRIVATE)
                .getString(quickToolUsedPackagesKey, "[]")
        ).contains(packageName)
    }

    fun isPackageOnQuickStudyToolAccess(
        context: Context,
        packageName: String
    ): Boolean {
        if (packageName.isBlank()) return false
        val prefs = context.getSharedPreferences(localPrefsName, Context.MODE_PRIVATE)
        val grantedPackage = prefs.getString(quickToolPackageKey, "").orEmpty()
        val remainingMs = quickStudyToolRemainingMs(context)
        if (grantedPackage.isNotBlank() && remainingMs <= 0L) {
            endQuickStudyToolAccess(context, "expired")
            return false
        }
        return packageName == grantedPackage && remainingMs > 0L
    }

    fun quickStudyToolRemainingMs(context: Context): Long {
        val prefs = context.getSharedPreferences(localPrefsName, Context.MODE_PRIVATE)
        if (prefs.getString(quickToolPackageKey, "").orEmpty().isBlank()) return 0L
        val wallNow = System.currentTimeMillis()
        val elapsedNow = SystemClock.elapsedRealtime()
        val currentBootWall = wallNow - elapsedNow
        val storedBootWall = prefs.getLong(quickToolBootWallKey, Long.MIN_VALUE)
        val elapsedEnd = prefs.getLong(quickToolUntilElapsedKey, 0L)
        val sameBoot = storedBootWall != Long.MIN_VALUE &&
            kotlin.math.abs(currentBootWall - storedBootWall) <= sameBootToleranceMs
        return if (sameBoot && elapsedEnd > 0L) {
            (elapsedEnd - elapsedNow).coerceAtLeast(0L)
        } else {
            (prefs.getLong(quickToolUntilKey, 0L) - wallNow).coerceAtLeast(0L)
        }
    }

    fun quickStudyToolSourcePackage(context: Context): String {
        return context.getSharedPreferences(localPrefsName, Context.MODE_PRIVATE)
            .getString(quickToolSourcePackageKey, "")
            .orEmpty()
    }

    fun quickStudyToolPackage(context: Context): String {
        return context.getSharedPreferences(localPrefsName, Context.MODE_PRIVATE)
            .getString(quickToolPackageKey, "")
            .orEmpty()
    }

    fun quickStudyToolAppName(context: Context): String {
        return context.getSharedPreferences(localPrefsName, Context.MODE_PRIVATE)
            .getString(quickToolAppNameKey, "")
            .orEmpty()
    }

    @Synchronized
    fun grantQuickStudyToolAccess(
        context: Context,
        packageName: String,
        appName: String,
        sourcePackage: String,
        durationMs: Long = quickStudyToolAccessMs
    ): Boolean {
        if (
            !isStudyModeEnabled(context) ||
            !isAppStudyMode(context) ||
            packageName.isBlank() ||
            sourcePackage.isBlank() ||
            !studyAllowedPackages(context).contains(sourcePackage) ||
            StudyToolResolver(context).classify(packageName) == null ||
            hasUsedQuickStudyTool(context, packageName)
        ) {
            return false
        }
        pauseStudyTimer(context)
        endQuickStudyToolAccess(context, "replaced")
        val prefs = context.getSharedPreferences(localPrefsName, Context.MODE_PRIVATE)
        val safeDuration = durationMs.coerceIn(30_000L, quickStudyToolAccessMs)
        val wallNow = System.currentTimeMillis()
        val elapsedNow = SystemClock.elapsedRealtime()
        val usedPackages = decodePackageSet(
            prefs.getString(quickToolUsedPackagesKey, "[]")
        ).toMutableSet().apply { add(packageName) }
        prefs.edit()
            .putString(quickToolPackageKey, packageName)
            .putString(quickToolAppNameKey, appName.take(80))
            .putString(quickToolSourcePackageKey, sourcePackage)
            .putLong(quickToolUntilKey, wallNow + safeDuration)
            .putLong(quickToolUntilElapsedKey, elapsedNow + safeDuration)
            .putLong(quickToolBootWallKey, wallNow - elapsedNow)
            .putString(quickToolUsedPackagesKey, encodePackageSet(usedPackages))
            .apply()
        scheduleQuickStudyToolTimeout(context, elapsedNow + safeDuration)
        debugLog(
            context,
            "I",
            "QUICK_STUDY_TOOL_GRANTED",
            "package=$packageName source=$sourcePackage durationMs=$safeDuration"
        )
        return true
    }

    @Synchronized
    fun endQuickStudyToolAccess(context: Context, reason: String): Boolean {
        val prefs = context.getSharedPreferences(localPrefsName, Context.MODE_PRIVATE)
        val packageName = prefs.getString(quickToolPackageKey, "").orEmpty()
        if (packageName.isBlank()) return false
        prefs.edit()
            .remove(quickToolPackageKey)
            .remove(quickToolAppNameKey)
            .remove(quickToolSourcePackageKey)
            .remove(quickToolUntilKey)
            .remove(quickToolUntilElapsedKey)
            .remove(quickToolBootWallKey)
            .apply()
        if (reason != "timeout") cancelQuickStudyToolTimeout(context)
        debugLog(
            context,
            "I",
            "QUICK_STUDY_TOOL_ENDED",
            "package=$packageName reason=${reason.take(40)}"
        )
        return true
    }

    @Synchronized
    fun clearSessionStudyToolAccess(context: Context) {
        cancelQuickStudyToolTimeout(context)
        context.getSharedPreferences(localPrefsName, Context.MODE_PRIVATE)
            .edit()
            .remove(sessionStudyToolPackagesKey)
            .remove(quickToolPackageKey)
            .remove(quickToolAppNameKey)
            .remove(quickToolSourcePackageKey)
            .remove(quickToolUntilKey)
            .remove(quickToolUntilElapsedKey)
            .remove(quickToolBootWallKey)
            .remove(quickToolUsedPackagesKey)
            .apply()
    }

    fun isPackageOnFocusBreak(context: Context, packageName: String): Boolean {
        if (!isFocusBreakActive(context)) return false
        return packageName.isNotBlank() && focusBreakPackages(context).contains(packageName)
    }

    fun focusBreakSelectedPackages(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(localPrefsName, Context.MODE_PRIVATE)
        val selected = prefs.getStringSet(focusBreakAllowedPackagesKey, emptySet())
            .orEmpty()
            .filter(String::isNotBlank)
            .take(3)
            .toSet()
        if (selected.isNotEmpty()) return selected
        return setOfNotNull(prefs.getString(focusBreakPackageKey, "")?.takeIf(String::isNotBlank))
    }

    fun focusBreakPackages(context: Context): Set<String> {
        val selected = focusBreakSelectedPackages(context)
        return buildSet {
            addAll(selected)
            if (selected.any { it.startsWith(chromiumWebApkPrefix) }) {
                add(chromePackage)
            }
        }
    }

    fun isFocusBreakActive(context: Context): Boolean {
        val prefs = context.getSharedPreferences(localPrefsName, Context.MODE_PRIVATE)
        val remaining = focusBreakDeadlineRemainingMs(prefs)
        val hasBreak = prefs.getString(focusBreakPackageKey, "").orEmpty().isNotBlank()
        if (hasBreak && remaining <= 0L) {
            finishFocusBreak(context, "expired")
            return false
        }
        return hasBreak && remaining > 0L
    }

    fun remainingFocusBreakMs(context: Context, packageName: String): Long {
        return if (isPackageOnFocusBreak(context, packageName)) {
            remainingFocusBreakMs(context)
        } else {
            0L
        }
    }

    fun remainingFocusBreakMs(context: Context): Long {
        if (!isFocusBreakActive(context)) return 0L
        val prefs = context.getSharedPreferences(localPrefsName, Context.MODE_PRIVATE)
        return focusBreakDeadlineRemainingMs(prefs)
    }

    private fun focusBreakDeadlineRemainingMs(
        prefs: android.content.SharedPreferences
    ): Long {
        val wallNow = System.currentTimeMillis()
        val elapsedNow = SystemClock.elapsedRealtime()
        val currentBootWall = wallNow - elapsedNow
        val storedBootWall = prefs.getLong(focusBreakBootWallKey, Long.MIN_VALUE)
        val elapsedEnd = prefs.getLong(focusBreakUntilElapsedKey, 0L)
        val sameBoot = storedBootWall != Long.MIN_VALUE &&
            kotlin.math.abs(currentBootWall - storedBootWall) <= sameBootToleranceMs
        if (sameBoot && elapsedEnd > 0L) {
            return (elapsedEnd - elapsedNow).coerceAtLeast(0L)
        }
        return (prefs.getLong(focusBreakUntilKey, 0L) - wallNow).coerceAtLeast(0L)
    }

    fun remainingStudyBreaks(context: Context): Int {
        val remainingMs = remainingStudyBreakBudgetMs(context)
        return ((remainingMs + focusBreakSliceMs - 1L) / focusBreakSliceMs).toInt().coerceAtLeast(0)
    }

    fun nextStudyBreakDurationMs(context: Context): Long {
        return minOf(focusBreakSliceMs, remainingStudyBreakBudgetMs(context)).coerceAtLeast(0L)
    }

    private fun remainingStudyBreakBudgetMs(context: Context): Long {
        val prefs = context.getSharedPreferences(localPrefsName, Context.MODE_PRIVATE)
        val usedMs = prefs.getLong(studyBreakUsedMsKey, Long.MIN_VALUE).let { stored ->
            if (stored != Long.MIN_VALUE) {
                stored.coerceAtLeast(0L)
            } else {
                prefs.getInt(studyBreaksUsedKey, 0).coerceAtLeast(0) * focusBreakSliceMs
            }
        }
        return (allowedStudyBreakBudgetMs(context) - usedMs).coerceAtLeast(0L)
    }

    fun remainingStudySessionMs(context: Context): Long {
        val prefs = context.getSharedPreferences(localPrefsName, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(studySessionActiveKey, false)) {
            return configuredStudyDurationMs(context)
        }
        return if (isAppStudyMode(context)) {
            activeUseRemainingMs(context, prefs)
        } else {
            sessionDeadlineRemainingMs(context, prefs)
        }
    }

    /**
     * App Study is an active-use quota, not a wall-clock deadline. The persisted
     * focused total is combined with the current foreground span so the value
     * stays accurate without writing storage every second.
     */
    private fun activeUseRemainingMs(
        context: Context,
        prefs: android.content.SharedPreferences
    ): Long {
        val targetMs = prefs.getLong(studySessionTargetMsKey, configuredStudyDurationMs(context))
            .takeIf { it > 0L }
            ?: configuredStudyDurationMs(context)
        val focusedMs = prefs.getLong(studySessionFocusedMsKey, 0L)
            .coerceIn(0L, targetMs)
        val activeStartElapsed = prefs.getLong(activeStudyStartElapsedKey, 0L)
        val activeBootWall = prefs.getLong(activeStudyBootWallKey, Long.MIN_VALUE)
        val wallNow = System.currentTimeMillis()
        val elapsedNow = SystemClock.elapsedRealtime()
        val currentBootWall = wallNow - elapsedNow
        val sameBoot = activeBootWall != Long.MIN_VALUE &&
            kotlin.math.abs(currentBootWall - activeBootWall) <= sameBootToleranceMs
        val activeDeltaMs = if (
            sameBoot &&
            activeStartElapsed > 0L &&
            elapsedNow > activeStartElapsed
        ) {
            elapsedNow - activeStartElapsed
        } else {
            0L
        }
        return (targetMs - focusedMs - activeDeltaMs).coerceIn(0L, targetMs)
    }

    // elapsedRealtime is immune to user clock changes. A wall-clock deadline is
    // retained only as a recovery anchor after a reboot, where elapsedRealtime
    // starts from zero again.
    private fun sessionDeadlineRemainingMs(
        context: Context,
        prefs: android.content.SharedPreferences
    ): Long {
        val targetMs = prefs.getLong(studySessionTargetMsKey, configuredStudyDurationMs(context))
            .takeIf { it > 0L }
            ?: configuredStudyDurationMs(context)
        val wallNow = System.currentTimeMillis()
        val elapsedNow = SystemClock.elapsedRealtime()
        val currentBootWall = wallNow - elapsedNow
        val storedBootWall = prefs.getLong(studySessionBootWallKey, Long.MIN_VALUE)
        val elapsedEnd = prefs.getLong(studySessionEndsAtElapsedKey, 0L)
        val sameBoot = storedBootWall != Long.MIN_VALUE &&
            kotlin.math.abs(currentBootWall - storedBootWall) <= sameBootToleranceMs
        if (sameBoot && elapsedEnd > 0L) {
            return (elapsedEnd - elapsedNow).coerceIn(0L, targetMs)
        }

        val wallEnd = studySessionEndsAtMs(context, prefs)
        return (wallEnd - wallNow).coerceIn(0L, targetMs)
    }

    // Book Study counts down in wall-clock time toward a fixed deadline.
    // Sessions saved before this key existed get the deadline derived from
    // startedAt + target.
    private fun studySessionEndsAtMs(
        context: Context,
        prefs: android.content.SharedPreferences
    ): Long {
        val stored = prefs.getLong(studySessionEndsAtKey, 0L)
        if (stored > 0L) return stored
        val startedAt = prefs.getLong(studySessionStartedAtKey, 0L)
        val targetMs = prefs.getLong(studySessionTargetMsKey, 0L)
            .takeIf { it > 0L }
            ?: configuredStudyDurationMs(context)
        if (startedAt <= 0L || targetMs <= 0L) return 0L
        val endsAt = startedAt + targetMs
        prefs.edit().putLong(studySessionEndsAtKey, endsAt).apply()
        return endsAt
    }

    private fun bookStudyFocusedMs(
        context: Context,
        prefs: android.content.SharedPreferences
    ): Long {
        val targetMs = prefs.getLong(studySessionTargetMsKey, configuredStudyDurationMs(context))
            .takeIf { it > 0L }
            ?: configuredStudyDurationMs(context)
        if (targetMs <= 0L) return 0L
        return (targetMs - sessionDeadlineRemainingMs(context, prefs))
            .coerceIn(0L, targetMs)
    }

    /**
     * Book Study is credited from its continuous session clock rather than
     * foreground app spans. Break start accounts all progress completed before
     * the break, so time inside an active break is never credited as focus.
     */
    private fun accountBookStudyProgress(
        context: Context,
        now: Long = System.currentTimeMillis()
    ): Long {
        if (isAppStudyMode(context)) return 0L
        val prefs = context.getSharedPreferences(localPrefsName, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(studySessionActiveKey, false)) return 0L
        val previouslyReported = prefs.getLong(bookStudyReportedMsKey, 0L)
            .coerceAtLeast(0L)
        if (prefs.getString(focusBreakPackageKey, "").orEmpty().isNotBlank()) {
            return previouslyReported
        }
        val focusedMs = bookStudyFocusedMs(context, prefs)
        val unreportedMs = (focusedMs - previouslyReported).coerceAtLeast(0L)
        if (unreportedMs > 0L) {
            incrementDailyDurationAcrossDates(
                context = context,
                field = "studyDurationMs",
                durationMs = unreportedMs,
                endedAtMs = now
            )
        }
        prefs.edit()
            .putLong(bookStudyReportedMsKey, maxOf(previouslyReported, focusedMs))
            .apply()
        return focusedMs
    }

    @Synchronized
    fun focusSessionStateMap(context: Context): Map<String, Any> {
        completeStudySessionIfFinished(context)
        val prefs = context.getSharedPreferences(localPrefsName, Context.MODE_PRIVATE)
        val enabled = isStudyModeEnabled(context)
        val sessionActive = prefs.getBoolean(studySessionActiveKey, false)
        val targetMs = prefs.getLong(studySessionTargetMsKey, configuredStudyDurationMs(context))
            .takeIf { it > 0L }
            ?: configuredStudyDurationMs(context)
        val remainingMs = if (enabled && sessionActive) {
            remainingStudySessionMs(context)
        } else {
            0L
        }
        val appStudy = isAppStudyMode(context)
        val activeUseRunning = prefs.getLong(activeStudyStartElapsedKey, 0L) > 0L
        val activelyCounting =
            enabled &&
                sessionActive &&
                remainingMs > 0L &&
                (!appStudy || activeUseRunning)
        val elapsedSessionMs = (targetMs - remainingMs).coerceIn(0L, targetMs)
        return mapOf(
            "active" to (enabled && sessionActive && remainingMs > 0L),
            "targetMs" to targetMs,
            "remainingMs" to remainingMs,
            "focusedMs" to elapsedSessionMs,
            "sessionType" to studySessionType(context),
            "trackingMode" to studyTrackingMode(context),
            "activelyCounting" to activelyCounting,
            "breaksLeft" to remainingStudyBreaks(context),
            "breakBudgetMs" to allowedStudyBreakBudgetMs(context),
            "breakRemainingMs" to remainingStudyBreakBudgetMs(context)
        )
    }

    fun resumeStudyTimerIfNeeded(
        context: Context,
        packageName: String = "",
        appName: String = ""
    ) {
        if (!isStudyModeEnabled(context)) return
        val prefs = context.getSharedPreferences(localPrefsName, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(studySessionActiveKey, false)) return
        if (completeStudySessionIfFinished(context)) return
        val now = System.currentTimeMillis()
        val elapsedNow = SystemClock.elapsedRealtime()
        val activeStart = prefs.getLong(activeStudyStartKey, 0L)
        val activePackageName = prefs.getString(activeStudyPackageKey, "") ?: ""
        val nextPackageName = packageName.ifBlank { activePackageName }
        val nextAppName = appName.ifBlank {
            nextPackageName.takeIf { it.isNotBlank() }?.let { appName(context, it) }.orEmpty()
        }

        if (activeStart > 0L) {
            val activeBootWall = prefs.getLong(activeStudyBootWallKey, Long.MIN_VALUE)
            val currentBootWall = now - elapsedNow
            val sameBoot = activeBootWall != Long.MIN_VALUE &&
                kotlin.math.abs(currentBootWall - activeBootWall) <= sameBootToleranceMs
            if (!sameBoot) {
                pauseStudyTimer(context, now, elapsedNow)
            } else {
                if (nextPackageName.isBlank() || activePackageName == nextPackageName) return
                pauseStudyTimer(context, now, elapsedNow)
            }
        }

        prefs.edit()
            .putLong(activeStudyStartKey, now)
            .putLong(activeStudyStartElapsedKey, elapsedNow)
            .putLong(activeStudyBootWallKey, now - elapsedNow)
            .putString(activeStudyPackageKey, nextPackageName)
            .putString(activeStudyAppNameKey, nextAppName)
            .apply()
        if (isAppStudyMode(context)) {
            val remainingMs = activeUseRemainingMs(context, prefs)
            if (remainingMs > 0L) {
                scheduleStudySessionTimeout(context, elapsedNow + remainingMs)
            }
        }
    }

    fun pauseStudyTimerForBreak(context: Context) {
        pauseStudyTimer(context)
    }

    fun pauseStudyTimerForIdle(context: Context) {
        pauseStudyTimer(context)
    }

    @Synchronized
    fun finishFocusBreak(
        context: Context,
        reason: String,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        val prefs = context.getSharedPreferences(localPrefsName, Context.MODE_PRIVATE)
        val breakPackage = prefs.getString(focusBreakPackageKey, "") ?: ""
        val breakUntil = prefs.getLong(focusBreakUntilKey, 0L)
        val breakUntilElapsed = prefs.getLong(focusBreakUntilElapsedKey, 0L)
        val startedAt = prefs.getLong(focusBreakStartedAtKey, 0L)
        val startedAtElapsed = prefs.getLong(focusBreakStartedElapsedKey, 0L)
        val storedBootWall = prefs.getLong(focusBreakBootWallKey, Long.MIN_VALUE)
        val allottedMs = prefs.getLong(focusBreakAllottedMsKey, 0L)
        if (breakPackage.isBlank() || startedAt <= 0L || allottedMs <= 0L) {
            prefs.edit()
                .remove(focusBreakPackageKey)
                .remove(focusBreakAllowedPackagesKey)
                .remove(focusBreakAppNameKey)
                .remove(focusBreakUntilKey)
                .remove(focusBreakUntilElapsedKey)
                .remove(focusBreakStartedAtKey)
                .remove(focusBreakStartedElapsedKey)
                .remove(focusBreakBootWallKey)
                .remove(focusBreakAllottedMsKey)
                .apply()
            cancelFocusBreakTimeout(context)
            return false
        }

        val elapsedNow = SystemClock.elapsedRealtime()
        val currentBootWall = now - elapsedNow
        val sameBoot = storedBootWall != Long.MIN_VALUE &&
            kotlin.math.abs(currentBootWall - storedBootWall) <= sameBootToleranceMs
        val actualMs = if (
            sameBoot && startedAtElapsed > 0L && breakUntilElapsed > 0L
        ) {
            (minOf(elapsedNow, breakUntilElapsed) - startedAtElapsed)
                .coerceIn(0L, allottedMs)
        } else {
            val cappedEnd = minOf(now, breakUntil.takeIf { it > 0L } ?: now)
            (cappedEnd - startedAt).coerceIn(0L, allottedMs)
        }
        if (actualMs > 0L) {
            incrementDailyMetric(context, "breakActualDurationMs", actualMs)
            val usedBreakMs = prefs.getLong(studyBreakUsedMsKey, 0L).coerceAtLeast(0L)
            prefs.edit().putLong(studyBreakUsedMsKey, usedBreakMs + actualMs).apply()
            incrementDailyAppDuration(
                context,
                "breakApps",
                breakPackage,
                prefs.getString(focusBreakAppNameKey, "") ?: "",
                actualMs,
                "actualMs"
            )
        }
        val unusedBreakMs = allottedMs - actualMs
        if (unusedBreakMs > 0L) {
            incrementDailyMetric(context, "breakUnusedReturnedMs", unusedBreakMs)
        }
        when (reason) {
            "manual_end" -> {
                incrementDailyMetric(context, "breakManualEndCount", 1)
                incrementDailyMetric(context, "breakReturnedCount", 1)
            }
            "timeout",
            "expired" -> incrementDailyMetric(context, "breakExpiredCount", 1)
        }
        debugLog(
            context,
            "I",
            "FOCUS_BREAK_ENDED",
            "package=$breakPackage reason=${reason.take(40)} " +
                "actualMs=$actualMs unusedMs=$unusedBreakMs"
        )
        val sessionEndsAt = prefs.getLong(studySessionEndsAtKey, 0L)
        val sessionEndsAtElapsed = prefs.getLong(studySessionEndsAtElapsedKey, 0L)
        if (unusedBreakMs > 0L) {
            val editor = prefs.edit()
            if (sessionEndsAt > 0L) {
                editor.putLong(studySessionEndsAtKey, sessionEndsAt - unusedBreakMs)
            }
            if (sessionEndsAtElapsed > 0L) {
                editor.putLong(
                    studySessionEndsAtElapsedKey,
                    sessionEndsAtElapsed - unusedBreakMs
                )
            }
            editor.apply()
        }
        appendLocalJson(
            context,
            "studyBreakEndEvents",
            JSONObject().apply {
                put("packageName", breakPackage)
                put("appName", prefs.getString(focusBreakAppNameKey, "") ?: "")
                put("startedAt", startedAt)
                put("endedAt", now)
                put("allottedMs", allottedMs)
                put("actualMs", actualMs)
                put("reason", reason.take(40))
            },
            80
        )

        prefs.edit()
            .remove(focusBreakPackageKey)
            .remove(focusBreakAllowedPackagesKey)
            .remove(focusBreakAppNameKey)
            .remove(focusBreakUntilKey)
            .remove(focusBreakUntilElapsedKey)
            .remove(focusBreakStartedAtKey)
            .remove(focusBreakStartedElapsedKey)
            .remove(focusBreakBootWallKey)
            .remove(focusBreakAllottedMsKey)
            .apply()
        if (reason != "timeout") {
            cancelFocusBreakTimeout(context)
        }
        if (
            !isAppStudyMode(context) &&
            isStudyModeEnabled(context) &&
            prefs.getBoolean(studySessionActiveKey, false)
        ) {
            val remainingMs = remainingStudySessionMs(context)
            if (remainingMs > 0L) {
                scheduleStudySessionTimeout(
                    context,
                    SystemClock.elapsedRealtime() + remainingMs
                )
            }
        }
        return true
    }

    @Synchronized
    fun completeStudySessionIfFinished(context: Context): Boolean {
        if (!isStudyModeEnabled(context)) return false

        val prefs = context.getSharedPreferences(localPrefsName, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(studySessionActiveKey, false)) return false

        val now = System.currentTimeMillis()
        val targetMs = prefs.getLong(studySessionTargetMsKey, configuredStudyDurationMs(context))
            .takeIf { it > 0L }
            ?: configuredStudyDurationMs(context)
        val remainingMs = remainingStudySessionMs(context)
        if (remainingMs > 0L) {
            val shouldSchedule =
                !isAppStudyMode(context) ||
                    prefs.getLong(activeStudyStartElapsedKey, 0L) > 0L
            if (shouldSchedule) {
                scheduleStudySessionTimeout(
                    context,
                    SystemClock.elapsedRealtime() + remainingMs
                )
            }
            return false
        }

        val completedFocusedMs = if (isAppStudyMode(context)) {
            pauseStudyTimer(context, now)
            prefs.getLong(studySessionFocusedMsKey, 0L)
        } else {
            accountBookStudyProgress(context, now)
        }
        incrementDailyMetric(context, "studyCompletedCount", 1)
        debugLog(
            context,
            "I",
            "STUDY_SESSION_COMPLETED",
            "mode=${studyTrackingMode(context)} targetMs=$targetMs focusedMs=$completedFocusedMs"
        )
        appendLocalJson(
            context,
            "studySessionCompleteEvents",
            JSONObject().apply {
                put("startedAt", prefs.getLong(studySessionStartedAtKey, 0L))
                put("completedAt", now)
                put("targetMs", targetMs)
                put("focusedMs", completedFocusedMs)
                put("breaksUsed", prefs.getInt(studyBreaksUsedKey, 0))
                put("breakUsedMs", prefs.getLong(studyBreakUsedMsKey, 0L))
            },
            80
        )

        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("flutter.studyModeEnabled", false)
            .apply()
        recordStudyModeChanged(context, false)
        return true
    }

    private fun pauseStudyTimer(
        context: Context,
        now: Long = System.currentTimeMillis(),
        elapsedNow: Long = SystemClock.elapsedRealtime()
    ) {
        if (isAppStudyMode(context)) {
            cancelStudySessionTimeout(context)
        }
        val prefs = context.getSharedPreferences(localPrefsName, Context.MODE_PRIVATE)
        val activeStart = prefs.getLong(activeStudyStartKey, 0L)
        val activeStartElapsed = prefs.getLong(activeStudyStartElapsedKey, 0L)
        val activeBootWall = prefs.getLong(activeStudyBootWallKey, Long.MIN_VALUE)
        val activePackageName = prefs.getString(activeStudyPackageKey, "") ?: ""
        val activeAppName = prefs.getString(activeStudyAppNameKey, "") ?: ""
        if (activeStart > 0L) {
            val currentBootWall = now - elapsedNow
            val sameBoot = activeBootWall != Long.MIN_VALUE &&
                kotlin.math.abs(currentBootWall - activeBootWall) <= sameBootToleranceMs
            val elapsedMs = if (
                sameBoot && activeStartElapsed > 0L && elapsedNow > activeStartElapsed
            ) {
                elapsedNow - activeStartElapsed
            } else {
                0L
            }
            val sessionActive = prefs.getBoolean(studySessionActiveKey, false)
            val previousFocusedMs = prefs.getLong(studySessionFocusedMsKey, 0L)
            val targetMs = prefs.getLong(studySessionTargetMsKey, configuredStudyDurationMs(context))
            val countedMs = if (sessionActive && targetMs > 0L) {
                elapsedMs.coerceAtMost((targetMs - previousFocusedMs).coerceAtLeast(0L))
            } else {
                elapsedMs
            }

            if (countedMs > 0L) {
                if (isAppStudyMode(context)) {
                    incrementDailyAppStudyDurationAcrossDates(
                        context,
                        startedAtMs = activeStart,
                        durationMs = countedMs,
                        packageName = activePackageName,
                        appName = activeAppName.ifBlank {
                            appName(context, activePackageName)
                        }
                    )
                }
            }
            val editor = prefs.edit()
                .remove(activeStudyStartKey)
                .remove(activeStudyStartElapsedKey)
                .remove(activeStudyBootWallKey)
                .remove(activeStudyPackageKey)
                .remove(activeStudyAppNameKey)
            if (sessionActive && isAppStudyMode(context)) {
                editor.putLong(studySessionFocusedMsKey, previousFocusedMs + countedMs)
            }
            editor.apply()
            return
        }
        prefs.edit()
            .remove(activeStudyStartKey)
            .remove(activeStudyStartElapsedKey)
            .remove(activeStudyBootWallKey)
            .remove(activeStudyPackageKey)
            .remove(activeStudyAppNameKey)
            .apply()
    }

    private fun decodePackageSet(raw: String?): Set<String> {
        val array = runCatching { JSONArray(raw ?: "[]") }.getOrDefault(JSONArray())
        val packages = mutableSetOf<String>()
        for (index in 0 until array.length()) {
            val value = array.optString(index).trim()
            if (value.isNotEmpty()) packages.add(value)
        }
        return packages
    }

    private fun encodePackageSet(packages: Set<String>): String {
        val array = JSONArray()
        packages.filter { it.isNotBlank() }
            .sorted()
            .forEach(array::put)
        return array.toString()
    }

    fun appName(context: Context, packageName: String): String {
        return try {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        } catch (_: RuntimeException) {
            packageName
        }
    }

    @Synchronized
    fun recordStudyModeChanged(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(localPrefsName, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val activeStart = prefs.getLong(activeStudyStartKey, 0L)
        val sessionActive = prefs.getBoolean(studySessionActiveKey, false)
        val breakActive = prefs.getString(focusBreakPackageKey, "").orEmpty().isNotBlank()

        debugLog(
            context,
            "I",
            "STUDY_MODE_CHANGE",
            "enabled=$enabled sessionActive=$sessionActive strict=${isStrictFocusModeEnabled(context)}"
        )

        if (!enabled) {
            StrictFocusDndController.release(context)
            cancelStudySessionTimeout(context)
            clearSessionStudyToolAccess(context)
            WebProtectionConfig.setExternalVpnCompatibility(context, false)
            WebProtectionConfig.reconcile(context)
        }

        if (
            enabled &&
            (!hasValidStudySetup(context) ||
                !hasStudyDuration(context) ||
                !isStudyDurationWithinLimit(context))
        ) {
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                .edit()
                .putBoolean("flutter.studyModeEnabled", false)
                .apply()
            if (sessionActive || activeStart > 0L) {
                if (!isAppStudyMode(context)) {
                    accountBookStudyProgress(context, now)
                }
                pauseStudyTimer(context, now)
            }
            if (prefs.getString(focusBreakPackageKey, "").orEmpty().isNotBlank()) {
                finishFocusBreak(context, "invalid_study_setup", now)
            }
            prefs.edit()
                .remove(activeStudyStartKey)
                .remove(activeStudyStartElapsedKey)
                .remove(activeStudyBootWallKey)
                .remove(activeStudyPackageKey)
                .remove(activeStudyAppNameKey)
                .remove(studySessionActiveKey)
                .remove(studySessionStartedAtKey)
                .remove(studySessionEndsAtKey)
                .remove(studySessionEndsAtElapsedKey)
                .remove(studySessionBootWallKey)
                .remove(studySessionFocusedMsKey)
                .remove(studySessionTargetMsKey)
                .remove(bookStudyReportedMsKey)
                .remove(studyBreaksUsedKey)
                .remove(studyBreakUsedMsKey)
                .apply()
            StrictFocusDndController.release(context)
            return
        }

        if (enabled && !sessionActive) {
            cancelFocusBreakTimeout(context)
            clearSessionStudyToolAccess(context)
            val targetMs = configuredStudyDurationMs(context)
            val elapsedNow = SystemClock.elapsedRealtime()
            val editor = prefs.edit()
                .putBoolean(studySessionActiveKey, true)
                .putLong(studySessionStartedAtKey, now)
                .remove(activeStudyStartKey)
                .remove(activeStudyStartElapsedKey)
                .remove(activeStudyBootWallKey)
                .remove(activeStudyPackageKey)
                .remove(activeStudyAppNameKey)
                .putLong(studySessionFocusedMsKey, 0L)
                .putLong(studySessionTargetMsKey, targetMs)
                .putLong(bookStudyReportedMsKey, 0L)
                .putInt(studyBreaksUsedKey, 0)
                .putLong(studyBreakUsedMsKey, 0L)
                .remove(focusBreakPackageKey)
                .remove(focusBreakAllowedPackagesKey)
                .remove(focusBreakAppNameKey)
                .remove(focusBreakUntilKey)
                .remove(focusBreakUntilElapsedKey)
                .remove(focusBreakStartedAtKey)
                .remove(focusBreakStartedElapsedKey)
                .remove(focusBreakBootWallKey)
                .remove(focusBreakAllottedMsKey)
            if (isAppStudyMode(context)) {
                editor
                    .remove(studySessionEndsAtKey)
                    .remove(studySessionEndsAtElapsedKey)
                    .remove(studySessionBootWallKey)
            } else {
                editor
                    .putLong(studySessionEndsAtKey, now + targetMs)
                    .putLong(studySessionEndsAtElapsedKey, elapsedNow + targetMs)
                    .putLong(studySessionBootWallKey, now - elapsedNow)
            }
            editor.apply()
            incrementDailyMetric(context, "studyStarts", 1)
        } else if (!enabled && (sessionActive || activeStart > 0L || breakActive)) {
            if (breakActive) {
                finishFocusBreak(context, "study_disabled", now)
            }
            if (!isAppStudyMode(context)) {
                accountBookStudyProgress(context, now)
            }
            pauseStudyTimer(context, now)
            prefs.edit()
                .remove(activeStudyStartKey)
                .remove(activeStudyStartElapsedKey)
                .remove(activeStudyBootWallKey)
                .remove(activeStudyPackageKey)
                .remove(activeStudyAppNameKey)
                .remove(studySessionActiveKey)
                .remove(studySessionStartedAtKey)
                .remove(studySessionEndsAtKey)
                .remove(studySessionEndsAtElapsedKey)
                .remove(studySessionBootWallKey)
                .remove(studySessionFocusedMsKey)
                .remove(studySessionTargetMsKey)
                .remove(bookStudyReportedMsKey)
                .remove(studyBreaksUsedKey)
                .remove(studyBreakUsedMsKey)
                .remove(focusBreakPackageKey)
                .remove(focusBreakAllowedPackagesKey)
                .remove(focusBreakAppNameKey)
                .remove(focusBreakUntilKey)
                .remove(focusBreakUntilElapsedKey)
                .remove(focusBreakStartedAtKey)
                .remove(focusBreakStartedElapsedKey)
                .remove(focusBreakBootWallKey)
                .remove(focusBreakAllottedMsKey)
                .apply()
        }

        if (enabled) {
            if (isStrictFocusModeEnabled(context)) {
                StrictFocusDndController.activate(context)
            } else {
                StrictFocusDndController.release(context)
            }
            val remainingMs = remainingStudySessionMs(context)
            val activeUseRunning =
                prefs.getLong(activeStudyStartElapsedKey, 0L) > 0L
            if (
                remainingMs > 0L &&
                (!isAppStudyMode(context) || activeUseRunning)
            ) {
                scheduleStudySessionTimeout(
                    context,
                    SystemClock.elapsedRealtime() + remainingMs
                )
            }
        }
    }

    @Synchronized
    fun syncStudyModeStateFromSettings(context: Context) {
        recordStudyModeChanged(context, isStudyModeEnabled(context))
        completeStudySessionIfFinished(context)
    }

    @Synchronized
    fun recordBlockedAttempt(context: Context, packageName: String, appName: String) {
        incrementDailyMetric(context, "blockedAttempts", 1)
        incrementDailyAppCount(context, "blockedApps", packageName, appName, "attempts", 1)
        appendLocalJson(
            context,
            "studyBlockEvents",
            JSONObject().apply {
                put("packageName", packageName)
                put("appName", appName)
                put("at", System.currentTimeMillis())
            },
            120
        )
    }

    @Synchronized
    fun recordBlockedDuration(context: Context, durationMs: Long) {
        if (durationMs > 0) {
            incrementDailyMetric(context, "blockedDurationMs", durationMs)
        }
    }

    @Synchronized
    fun recordBlockedDuration(
        context: Context,
        packageName: String,
        appName: String,
        durationMs: Long
    ) {
        if (durationMs <= 0) return
        incrementDailyMetric(context, "blockedDurationMs", durationMs)
        if (packageName.isNotBlank()) {
            incrementDailyAppDuration(
                context,
                "blockedApps",
                packageName,
                appName.ifBlank { packageName },
                durationMs
            )
        }
    }

    @Synchronized
    fun recordStudyHandled(
        context: Context,
        packageName: String = "",
        appName: String = ""
    ) {
        incrementDailyMetric(context, "handledCount", 1)
        if (packageName.isNotBlank()) {
            incrementDailyAppCount(
                context,
                "blockedApps",
                packageName,
                appName,
                "returnedCount",
                1
            )
        }
    }

    @Synchronized
    fun recordStudyGiveUp(
        context: Context,
        packageName: String = "",
        appName: String = ""
    ) {
        incrementDailyMetric(context, "giveUpCount", 1)
        incrementDailyMetric(context, "exitSuccessCount", 1)
        if (packageName.isNotBlank()) {
            incrementDailyAppCount(
                context,
                "blockedApps",
                packageName,
                appName,
                "gaveUpCount",
                1
            )
        }
        setStudyModeEnabled(context, false)
    }

    @Synchronized
    fun recordEmergencyExitAttempt(context: Context, source: String) {
        if (isStudyModeEnabled(context) && isLockedStrictFocusModeEnabled(context)) {
            debugLog(context, "W", "LOCKED_STRICT_EXIT_DENIED", "source=${source.take(40)}")
            return
        }
        incrementDailyMetric(context, "exitAttemptCount", 1)
        debugLog(
            context,
            "I",
            "EMERGENCY_EXIT_ATTEMPT",
            "source=${source.take(40)}"
        )
        appendLocalJson(
            context,
            "studyEmergencyExitAttemptEvents",
            JSONObject().apply {
                put("source", source.take(40))
                put("at", System.currentTimeMillis())
            },
            80
        )
    }

    @Synchronized
    fun recordEmergencyExitCancelled(context: Context, source: String) {
        if (isStudyModeEnabled(context) && isLockedStrictFocusModeEnabled(context)) {
            return
        }
        incrementDailyMetric(context, "exitCancelledCount", 1)
        debugLog(
            context,
            "I",
            "EMERGENCY_EXIT_CANCELLED",
            "source=${source.take(40)}"
        )
        appendLocalJson(
            context,
            "studyEmergencyExitCancelEvents",
            JSONObject().apply {
                put("source", source.take(40))
                put("at", System.currentTimeMillis())
            },
            80
        )
    }

    @Synchronized
    @SuppressLint("ApplySharedPref")
    fun recordEmergencyExit(
        context: Context,
        reason: String,
        source: String,
        packageName: String = "",
        appName: String = ""
    ) {
        if (isStudyModeEnabled(context) && isLockedStrictFocusModeEnabled(context)) {
            debugLog(context, "W", "LOCKED_STRICT_EXIT_DENIED", "source=${source.take(40)}")
            return
        }
        val cleanedReason = reason.replace(Regex("\\s+"), " ").trim().take(180)
        incrementDailyMetric(context, "giveUpCount", 1)
        incrementDailyMetric(context, "exitSuccessCount", 1)
        if (packageName.isNotBlank()) {
            incrementDailyAppCount(
                context,
                "blockedApps",
                packageName,
                appName,
                "gaveUpCount",
                1
            )
        }
        debugLog(
            context,
            "I",
            "EMERGENCY_EXIT_COMPLETED",
            "source=${source.take(40)} reasonWords=${
                cleanedReason.split(Regex("\\s+")).count { it.isNotBlank() }
            }"
        )
        appendLocalJson(
            context,
            "studyEmergencyExitEvents",
            JSONObject().apply {
                put("reason", cleanedReason)
                put("source", source.take(40))
                put("at", System.currentTimeMillis())
            },
            80
        )
        // The disabled flag must be visible on disk before cleanup services
        // reconcile the session, so this safety transition is intentionally synchronous.
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("flutter.studyModeEnabled", false)
            .commit()
        recordStudyModeChanged(context, false)
    }

    @Synchronized
    fun startFocusBreak(
        context: Context,
        packageName: String,
        appName: String,
        durationMs: Long,
        allowedPackages: Set<String> = setOf(packageName)
    ): Boolean {
        if (!isStudyModeEnabled(context)) return false
        if (completeStudySessionIfFinished(context)) return false

        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(localPrefsName, Context.MODE_PRIVATE)
        val activeBreakUntil = prefs.getLong(focusBreakUntilKey, 0L)
        if (activeBreakUntil > now) return false
        if (activeBreakUntil > 0L) {
            finishFocusBreak(context, "expired", now)
        }
        val remainingBreakBudgetMs = remainingStudyBreakBudgetMs(context)
        if (remainingBreakBudgetMs <= 0L) return false

        if (!isAppStudyMode(context)) {
            accountBookStudyProgress(context, now)
        }
        pauseStudyTimer(context, now)
        val usedBreaks = prefs.getInt(studyBreaksUsedKey, 0).coerceAtLeast(0)
        val safeDuration = durationMs.coerceIn(60_000L, 30 * 60_000L)
            .coerceAtMost(remainingBreakBudgetMs)
            .coerceAtLeast(0L)
        if (safeDuration <= 0L) return false
        val selectedPackages = allowedPackages
            .filter(String::isNotBlank)
            .take(3)
            .toMutableSet()
            .apply {
                if (isEmpty() && packageName.isNotBlank()) add(packageName)
            }
        if (selectedPackages.isEmpty()) return false
        val breakUntil = now + safeDuration
        val elapsedNow = SystemClock.elapsedRealtime()
        // Book Study uses a wall-clock deadline, so push it out by the break
        // length. App Study already pauses when its selected app span is closed.
        if (!isAppStudyMode(context)) {
            val sessionEndsAt = studySessionEndsAtMs(context, prefs)
            val sessionEndsAtElapsed = prefs.getLong(studySessionEndsAtElapsedKey, 0L)
            if (sessionEndsAt > 0L || sessionEndsAtElapsed > 0L) {
                val editor = prefs.edit()
                if (sessionEndsAt > 0L) {
                    editor.putLong(studySessionEndsAtKey, sessionEndsAt + safeDuration)
                }
                if (sessionEndsAtElapsed > 0L) {
                    editor.putLong(
                        studySessionEndsAtElapsedKey,
                        sessionEndsAtElapsed + safeDuration
                    )
                }
                editor.apply()
            }
        }
        prefs
            .edit()
            .putString(focusBreakPackageKey, packageName)
            .putString(focusBreakAppNameKey, appName)
            .putStringSet(focusBreakAllowedPackagesKey, selectedPackages)
            .putLong(focusBreakUntilKey, breakUntil)
            .putLong(focusBreakUntilElapsedKey, elapsedNow + safeDuration)
            .putLong(focusBreakStartedAtKey, now)
            .putLong(focusBreakStartedElapsedKey, elapsedNow)
            .putLong(focusBreakBootWallKey, now - elapsedNow)
            .putLong(focusBreakAllottedMsKey, safeDuration)
            .putInt(studyBreaksUsedKey, usedBreaks + 1)
            .apply()
        scheduleFocusBreakTimeout(
            context,
            packageName,
            appName,
            elapsedNow + safeDuration
        )
        incrementDailyMetric(context, "breakCount", 1)
        incrementDailyMetric(context, "breakDurationMs", safeDuration)
        incrementDailyAppCount(context, "breakApps", packageName, appName, "breakCount", 1)
        incrementDailyAppDuration(context, "breakApps", packageName, appName, safeDuration, "allottedMs")
        debugLog(
            context,
            "I",
            "FOCUS_BREAK_STARTED",
            "package=$packageName selected=${selectedPackages.size} allottedMs=$safeDuration"
        )
        appendLocalJson(
            context,
            "studyBreakEvents",
            JSONObject().apply {
                put("packageName", packageName)
                put("appName", appName)
                put("selectedPackages", JSONArray(selectedPackages))
                put("durationMs", safeDuration)
                put("at", now)
            },
            80
        )
        if (!isAppStudyMode(context)) {
            scheduleStudySessionTimeout(
                context,
                SystemClock.elapsedRealtime() + remainingStudySessionMs(context)
            )
        }
        return true
    }

    private fun scheduleFocusBreakTimeout(
        context: Context,
        packageName: String,
        appName: String,
        triggerAtElapsedMs: Long
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = focusBreakTimeoutPendingIntent(
            context,
            packageName,
            appName,
            PendingIntent.FLAG_UPDATE_CURRENT
        ) ?: return
        val scheduled = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtElapsedMs,
                    pendingIntent
                )
            } else {
                alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtElapsedMs,
                    pendingIntent
                )
            }
        }.isSuccess

        if (!scheduled) {
            runCatching {
                val delayMs =
                    (triggerAtElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + delayMs,
                    pendingIntent
                )
            }
        }
    }

    private fun scheduleQuickStudyToolTimeout(
        context: Context,
        triggerAtElapsedMs: Long
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = quickStudyToolTimeoutPendingIntent(
            context,
            PendingIntent.FLAG_UPDATE_CURRENT
        ) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtElapsedMs,
                    pendingIntent
                )
            } else {
                alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtElapsedMs,
                    pendingIntent
                )
            }
        }.onFailure {
            debugLog(
                context,
                "W",
                "QUICK_TOOL_TIMEOUT_SCHEDULE_FAILED",
                "error=${it.javaClass.simpleName}"
            )
        }
    }

    private fun cancelQuickStudyToolTimeout(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = quickStudyToolTimeoutPendingIntent(
            context,
            PendingIntent.FLAG_NO_CREATE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    private fun quickStudyToolTimeoutPendingIntent(
        context: Context,
        updateFlag: Int
    ): PendingIntent? {
        val flags = updateFlag or PendingIntent.FLAG_IMMUTABLE
        val intent = Intent(context, StudyToolTimeoutReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            quickToolAlarmRequestCode,
            intent,
            flags
        )
    }

    private fun scheduleStudySessionTimeout(context: Context, triggerAtElapsedMs: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = focusSessionTimeoutPendingIntent(
            context,
            PendingIntent.FLAG_UPDATE_CURRENT
        ) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtElapsedMs,
                    pendingIntent
                )
            } else {
                alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtElapsedMs,
                    pendingIntent
                )
            }
        }.onFailure {
            debugLog(
                context,
                "W",
                "SESSION_TIMEOUT_SCHEDULE_FAILED",
                "error=${it.javaClass.simpleName}"
            )
        }
    }

    private fun cancelStudySessionTimeout(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = focusSessionTimeoutPendingIntent(
            context,
            PendingIntent.FLAG_NO_CREATE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    private fun focusSessionTimeoutPendingIntent(
        context: Context,
        updateFlag: Int
    ): PendingIntent? {
        val flags = updateFlag or PendingIntent.FLAG_IMMUTABLE
        val intent = Intent(context, FocusSessionTimeoutReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            focusSessionAlarmRequestCode,
            intent,
            flags
        )
    }

    private fun cancelFocusBreakTimeout(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = focusBreakTimeoutPendingIntent(
            context,
            "",
            "",
            PendingIntent.FLAG_NO_CREATE
        )
            ?: return
        alarmManager.cancel(pendingIntent)
    }

    private fun focusBreakTimeoutPendingIntent(
        context: Context,
        packageName: String,
        appName: String,
        updateFlag: Int
    ): PendingIntent? {
        val flags = updateFlag or PendingIntent.FLAG_IMMUTABLE
        val intent = Intent(context, FocusBreakTimeoutReceiver::class.java).apply {
            putExtra(FocusGateActivity.EXTRA_PACKAGE_NAME, packageName)
            putExtra(FocusGateActivity.EXTRA_APP_NAME, appName)
        }
        return PendingIntent.getBroadcast(
            context,
            focusBreakAlarmRequestCode,
            intent,
            flags
        )
    }

    @Synchronized
    fun selfControlAnalytics(context: Context, days: Int = 30): List<Map<String, Any>> {
        syncStudyModeStateFromSettings(context)
        accountBookStudyProgress(context)
        val prefs = context.getSharedPreferences(localPrefsName, Context.MODE_PRIVATE)
        val raw = dailyStats(prefs)
        val activeStart = prefs.getLong(activeStudyStartKey, 0L)
        val activeStartElapsed = prefs.getLong(activeStudyStartElapsedKey, 0L)
        val activeBootWall = prefs.getLong(activeStudyBootWallKey, Long.MIN_VALUE)
        val sessionActive = prefs.getBoolean(studySessionActiveKey, false)
        val sessionFocusedMs = prefs.getLong(studySessionFocusedMsKey, 0L)
        val sessionTargetMs = prefs.getLong(
            studySessionTargetMsKey,
            configuredStudyDurationMs(context)
        )
        val activePackageName = prefs.getString(activeStudyPackageKey, "") ?: ""
        val activeAppName = prefs.getString(activeStudyAppNameKey, "") ?: ""
        val now = System.currentTimeMillis()
        val elapsedNow = SystemClock.elapsedRealtime()
        val activeDeltaMs = if (activeStart > 0L && isAppStudyMode(context)) {
            val currentBootWall = now - elapsedNow
            val sameBoot = activeBootWall != Long.MIN_VALUE &&
                kotlin.math.abs(currentBootWall - activeBootWall) <= sameBootToleranceMs
            val rawActiveDeltaMs =
                if (sameBoot && activeStartElapsed > 0L && elapsedNow > activeStartElapsed) {
                    elapsedNow - activeStartElapsed
                } else {
                    0L
                }
            if (sessionActive && sessionTargetMs > 0L) {
                rawActiveDeltaMs.coerceAtMost(
                    (sessionTargetMs - sessionFocusedMs).coerceAtLeast(0L)
                )
            } else {
                rawActiveDeltaMs
            }
        } else {
            0L
        }
        val activeDurationByDate = DailyDurationSplitter.fromStart(
            startedAtMs = activeStart,
            durationMs = activeDeltaMs
        )

        return lastDateStrings(days.coerceIn(1, 31)).map { date ->
            val day = (raw.optJSONObject(date) ?: defaultDailyStats(date)).ensureDailyFields(date)
            val adjusted = JSONObject(day.toString())
            val activeDurationForDate = activeDurationByDate[date] ?: 0L
            if (activeDurationForDate > 0L) {
                adjusted.put(
                    "studyDurationMs",
                    adjusted.optLong("studyDurationMs") + activeDurationForDate
                )
                if (activePackageName.isNotBlank()) {
                    addDurationToAppMap(
                        adjusted,
                        "focusApps",
                        activePackageName,
                        activeAppName.ifBlank { appName(context, activePackageName) },
                        activeDurationForDate
                    )
                }
            }
            adjusted.toDayMap()
        }
    }

    private fun incrementDailyDurationAcrossDates(
        context: Context,
        field: String,
        durationMs: Long,
        endedAtMs: Long
    ) {
        if (durationMs <= 0L) return
        val prefs = context.getSharedPreferences(localPrefsName, Context.MODE_PRIVATE)
        val allStats = dailyStats(prefs)
        for ((date, chunkMs) in DailyDurationSplitter.endingAt(endedAtMs, durationMs)) {
            val day = (allStats.optJSONObject(date) ?: defaultDailyStats(date))
                .ensureDailyFields(date)
            day.put(field, day.optLong(field) + chunkMs)
            allStats.put(date, day)
        }
        pruneDailyStats(allStats)
        prefs.edit().putString(selfControlDailyStatsKey, allStats.toString()).apply()
    }

    private fun incrementDailyAppStudyDurationAcrossDates(
        context: Context,
        startedAtMs: Long,
        durationMs: Long,
        packageName: String,
        appName: String
    ) {
        if (durationMs <= 0L) return
        val prefs = context.getSharedPreferences(localPrefsName, Context.MODE_PRIVATE)
        val allStats = dailyStats(prefs)
        for ((date, chunkMs) in DailyDurationSplitter.fromStart(startedAtMs, durationMs)) {
            val day = (allStats.optJSONObject(date) ?: defaultDailyStats(date))
                .ensureDailyFields(date)
            day.put("studyDurationMs", day.optLong("studyDurationMs") + chunkMs)
            if (packageName.isNotBlank()) {
                addDurationToAppMap(
                    day,
                    "focusApps",
                    packageName,
                    appName.ifBlank { packageName },
                    chunkMs
                )
            }
            allStats.put(date, day)
        }
        pruneDailyStats(allStats)
        prefs.edit().putString(selfControlDailyStatsKey, allStats.toString()).apply()
    }

    private fun incrementDailyMetric(context: Context, field: String, delta: Number) {
        val prefs = context.getSharedPreferences(localPrefsName, Context.MODE_PRIVATE)
        val allStats = dailyStats(prefs)
        val date = todayString()
        val day = (allStats.optJSONObject(date) ?: defaultDailyStats(date)).ensureDailyFields(date)
        val existing = day.optLong(field)
        day.put(field, existing + delta.toLong())
        allStats.put(date, day)
        pruneDailyStats(allStats)
        prefs.edit().putString(selfControlDailyStatsKey, allStats.toString()).apply()
    }

    private fun incrementDailyAppDuration(
        context: Context,
        field: String,
        packageName: String,
        appName: String,
        deltaMs: Long,
        durationField: String = "durationMs"
    ) {
        if (packageName.isBlank() || deltaMs <= 0L) return
        val prefs = context.getSharedPreferences(localPrefsName, Context.MODE_PRIVATE)
        val allStats = dailyStats(prefs)
        val date = todayString()
        val day = (allStats.optJSONObject(date) ?: defaultDailyStats(date)).ensureDailyFields(date)
        addDurationToAppMap(day, field, packageName, appName, deltaMs, durationField)
        allStats.put(date, day)
        pruneDailyStats(allStats)
        prefs.edit().putString(selfControlDailyStatsKey, allStats.toString()).apply()
    }

    private fun incrementDailyAppCount(
        context: Context,
        field: String,
        packageName: String,
        appName: String,
        countField: String,
        delta: Int
    ) {
        if (packageName.isBlank() || delta <= 0) return
        val prefs = context.getSharedPreferences(localPrefsName, Context.MODE_PRIVATE)
        val allStats = dailyStats(prefs)
        val date = todayString()
        val day = (allStats.optJSONObject(date) ?: defaultDailyStats(date)).ensureDailyFields(date)
        val apps = day.optJSONObject(field) ?: JSONObject()
        val app = apps.optJSONObject(packageName) ?: JSONObject().apply {
            put("packageName", packageName)
        }
        app.put("appName", appName.ifBlank { packageName })
        app.put(countField, app.optInt(countField) + delta)
        apps.put(packageName, app)
        day.put(field, apps)
        allStats.put(date, day)
        pruneDailyStats(allStats)
        prefs.edit().putString(selfControlDailyStatsKey, allStats.toString()).apply()
    }

    private fun addDurationToAppMap(
        day: JSONObject,
        field: String,
        packageName: String,
        appName: String,
        deltaMs: Long,
        durationField: String = "durationMs"
    ) {
        if (packageName.isBlank() || deltaMs <= 0L) return
        val apps = day.optJSONObject(field) ?: JSONObject()
        val app = apps.optJSONObject(packageName) ?: JSONObject().apply {
            put("packageName", packageName)
        }
        app.put("appName", appName.ifBlank { packageName })
        app.put(durationField, app.optLong(durationField) + deltaMs)
        apps.put(packageName, app)
        day.put(field, apps)
    }

    private fun dailyStats(prefs: android.content.SharedPreferences): JSONObject {
        return runCatching {
            JSONObject(prefs.getString(selfControlDailyStatsKey, "{}") ?: "{}")
        }.getOrDefault(JSONObject())
    }

    private fun pruneDailyStats(stats: JSONObject, keepDays: Int = 31) {
        val keys = mutableListOf<String>()
        val iterator = stats.keys()
        while (iterator.hasNext()) keys.add(iterator.next())
        keys.sortedDescending()
            .drop(keepDays.coerceIn(1, 366))
            .forEach(stats::remove)
    }

    private fun defaultDailyStats(date: String): JSONObject {
        return JSONObject().apply {
            put("date", date)
            put("studyStarts", 0)
            put("studyCompletedCount", 0)
            put("studyDurationMs", 0L)
            put("blockedAttempts", 0)
            put("blockedDurationMs", 0L)
            put("handledCount", 0)
            put("giveUpCount", 0)
            put("exitAttemptCount", 0)
            put("exitCancelledCount", 0)
            put("exitSuccessCount", 0)
            put("breakCount", 0)
            put("breakManualEndCount", 0)
            put("breakExpiredCount", 0)
            put("breakReturnedCount", 0)
            put("breakDurationMs", 0L)
            put("breakActualDurationMs", 0L)
            put("breakUnusedReturnedMs", 0L)
            put("focusApps", JSONObject())
            put("blockedApps", JSONObject())
            put("breakApps", JSONObject())
        }
    }

    private fun JSONObject.ensureDailyFields(date: String): JSONObject {
        if (!has("date")) put("date", date)
        listOf(
            "studyStarts",
            "studyCompletedCount",
            "blockedAttempts",
            "handledCount",
            "giveUpCount",
            "exitAttemptCount",
            "exitCancelledCount",
            "exitSuccessCount",
            "breakCount",
            "breakManualEndCount",
            "breakExpiredCount",
            "breakReturnedCount"
        ).forEach { field ->
            if (!has(field)) put(field, 0)
        }
        listOf(
            "studyDurationMs",
            "blockedDurationMs",
            "breakDurationMs",
            "breakActualDurationMs",
            "breakUnusedReturnedMs"
        ).forEach { field ->
            if (!has(field)) put(field, 0L)
        }
        listOf("focusApps", "blockedApps", "breakApps").forEach { field ->
            if (!has(field) || optJSONObject(field) == null) put(field, JSONObject())
        }
        return this
    }

    private fun JSONObject.toDayMap(): Map<String, Any> {
        return mapOf(
            "date" to optString("date"),
            "studyStarts" to optInt("studyStarts"),
            "studyCompletedCount" to optInt("studyCompletedCount"),
            "studyDurationMs" to optLong("studyDurationMs"),
            "blockedAttempts" to optInt("blockedAttempts"),
            "blockedDurationMs" to optLong("blockedDurationMs"),
            "handledCount" to optInt("handledCount"),
            "giveUpCount" to optInt("giveUpCount"),
            "exitAttemptCount" to optInt("exitAttemptCount"),
            "exitCancelledCount" to optInt("exitCancelledCount"),
            "exitSuccessCount" to optInt("exitSuccessCount"),
            "breakCount" to optInt("breakCount"),
            "breakManualEndCount" to optInt("breakManualEndCount"),
            "breakExpiredCount" to optInt("breakExpiredCount"),
            "breakReturnedCount" to optInt("breakReturnedCount"),
            "breakDurationMs" to optLong("breakDurationMs"),
            "breakActualDurationMs" to optLong("breakActualDurationMs"),
            "breakUnusedReturnedMs" to optLong("breakUnusedReturnedMs"),
            "focusApps" to appMetrics(optJSONObject("focusApps")),
            "blockedApps" to appMetrics(optJSONObject("blockedApps")),
            "breakApps" to appMetrics(optJSONObject("breakApps"))
        )
    }

    private fun appMetrics(raw: JSONObject?): List<Map<String, Any>> {
        val source = raw ?: return emptyList()
        val items = mutableListOf<Map<String, Any>>()
        val keys = source.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val item = source.optJSONObject(key) ?: continue
            items.add(
                mapOf(
                    "packageName" to item.optString("packageName", key),
                    "appName" to item.optString("appName", key),
                    "durationMs" to item.optLong("durationMs"),
                    "attempts" to item.optInt("attempts"),
                    "notifications" to item.optInt("notifications"),
                    "breakCount" to item.optInt("breakCount"),
                    "allottedMs" to item.optLong("allottedMs"),
                    "actualMs" to item.optLong("actualMs"),
                    "returnedCount" to item.optInt("returnedCount"),
                    "gaveUpCount" to item.optInt("gaveUpCount")
                )
            )
        }
        return items.sortedWith(
            compareByDescending<Map<String, Any>> {
                (it["durationMs"] as? Long)
                    ?: (it["actualMs"] as? Long)
                    ?: (it["allottedMs"] as? Long)
                    ?: 0L
            }.thenByDescending {
                (it["attempts"] as? Int) ?: (it["breakCount"] as? Int) ?: 0
            }
        )
    }

    private fun lastDateStrings(days: Int): List<String> {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, -(days - 1))
        return (0 until days).map {
            val value = format.format(calendar.time)
            calendar.add(Calendar.DAY_OF_YEAR, 1)
            value
        }
    }

    fun todayString(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }

    private fun appendLocalJson(context: Context, key: String, json: JSONObject, maxItems: Int) {
        val prefs = context.getSharedPreferences(localPrefsName, Context.MODE_PRIVATE)
        val existing = runCatching { JSONArray(prefs.getString(key, "[]")) }.getOrDefault(JSONArray())
        val updated = JSONArray().put(json)
        val limit = minOf(existing.length(), maxItems.coerceAtLeast(1) - 1)
        for (index in 0 until limit) {
            existing.optJSONObject(index)?.let { updated.put(it) }
        }
        prefs.edit().putString(key, updated.toString()).apply()
    }

    fun purgeLegacyPrivateData(context: Context) {
        context.getSharedPreferences(localPrefsName, Context.MODE_PRIVATE)
            .edit()
            .remove("notifications")
            .remove("currentStatus")
            .remove("usageCounts")
            .remove("usageSessions")
            .remove("focusSnoozedNotificationKeys")
            .apply()
    }

    fun clearLocalAnalytics(context: Context) {
        context.getSharedPreferences(localPrefsName, Context.MODE_PRIVATE)
            .edit()
            .remove(selfControlDailyStatsKey)
            .remove("shortVideoStats")
            .remove("studyBlockEvents")
            .remove("studyBlockDurationEvents")
            .remove("studySessionCompleteEvents")
            .remove("studyBreakEvents")
            .remove("studyBreakEndEvents")
            .remove("studyEmergencyExitEvents")
            .remove("studyEmergencyExitAttemptEvents")
            .remove("studyEmergencyExitCancelledEvents")
            .remove("notifications")
            .remove("currentStatus")
            .remove("usageCounts")
            .remove("usageSessions")
            .apply()
    }

}
