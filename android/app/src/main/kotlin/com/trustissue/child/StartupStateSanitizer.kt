package com.trustissue.child

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Repairs persisted values before Flutter, Accessibility, or VpnService reads
 * them. Older beta builds used the same package id and may leave values with a
 * different type or an active transient session behind after an APK update.
 */
object StartupStateSanitizer {
    private const val flutterPrefsName = "FlutterSharedPreferences"
    private const val trackingPrefsName = "trustissue_local_tracking"
    private const val webPrefsName = "trustissue_web_protection"
    private const val schemaKey = "startupStateSchemaVersion"
    private const val currentSchema = 2

    private val flutterBooleanKeys = setOf(
        "flutter.studyModeEnabled",
        "flutter.studyStrictModeEnabled",
        "flutter.studyLockedStrictModeEnabled",
        "flutter.pdfReaderSetupComplete",
        "flutter.globalWebProtectionEnabled",
        "flutter.youtubeShortsBlockingEnabled",
        "flutter.instagramReelsBlockingEnabled",
        "flutter.onboardingComplete",
        "flutter.localOnlyMigrationV1Complete"
    )

    private val flutterStringKeys = setOf(
        "flutter.studyModePolicy",
        "flutter.studyTrackingMode",
        "flutter.studySessionType",
        "flutter.studyAllowedPackagesJson",
        "flutter.studyBlockedPackagesJson",
        "flutter.defaultPdfReaderPackage",
        "flutter.webProtectionCustomDomainsJson",
        "flutter.globalWebProtectionCustomDomainsJson",
        "flutter.dailyAppLimitsJson"
    )

    private val flutterJsonArrayKeys = setOf(
        "flutter.studyAllowedPackagesJson",
        "flutter.studyBlockedPackagesJson",
        "flutter.webProtectionCustomDomainsJson",
        "flutter.globalWebProtectionCustomDomainsJson"
    )

    private val flutterJsonObjectKeys = setOf(
        "flutter.dailyAppLimitsJson"
    )

    private val trackingStringKeys = setOf(
        "activeStudyPackageName",
        "activeStudyAppName",
        "focusBreakPackageName",
        "focusBreakAppName",
        "sessionStudyToolPackages",
        "quickToolPackage",
        "quickToolAppName",
        "quickToolSourcePackage",
        "quickToolUsedPackages",
        "selfControlDailyStats",
        "studyBlockEvents",
        "studyBlockDurationEvents",
        "studySessionCompleteEvents",
        "studyBreakEvents",
        "studyBreakEndEvents",
        "studyEmergencyExitEvents",
        "studyEmergencyExitAttemptEvents",
        "studyEmergencyExitCancelledEvents"
    )

    private val trackingLongKeys = setOf(
        "activeStudyStartMs",
        "activeStudyStartElapsedMs",
        "activeStudyBootWallMs",
        "studySessionStartedAtMs",
        "studySessionFocusedMs",
        "studySessionTargetMs",
        "bookStudyReportedMs",
        "studySessionEndsAtMs",
        "studySessionEndsAtElapsedMs",
        "studySessionBootWallMs",
        "studyBreakUsedMs",
        "focusBreakUntilMs",
        "focusBreakUntilElapsedMs",
        "focusBreakStartedAtMs",
        "focusBreakStartedElapsedMs",
        "focusBreakBootWallMs",
        "focusBreakAllottedMs",
        "quickToolUntilMs",
        "quickToolUntilElapsedMs",
        "quickToolBootWallMs"
    )

    private val trackingStringSetKeys = setOf(
        "focusBreakAllowedPackages"
    )

    private val transientTrackingKeys = setOf(
        "activeStudyStartMs",
        "activeStudyStartElapsedMs",
        "activeStudyBootWallMs",
        "studySessionActive",
        "studySessionStartedAtMs",
        "studySessionFocusedMs",
        "studySessionTargetMs",
        "bookStudyReportedMs",
        "studySessionEndsAtMs",
        "studySessionEndsAtElapsedMs",
        "studySessionBootWallMs",
        "studyBreaksUsed",
        "studyBreakUsedMs",
        "activeStudyPackageName",
        "activeStudyAppName",
        "focusBreakPackageName",
        "focusBreakAppName",
        "focusBreakAllowedPackages",
        "focusBreakUntilMs",
        "focusBreakUntilElapsedMs",
        "focusBreakStartedAtMs",
        "focusBreakStartedElapsedMs",
        "focusBreakBootWallMs",
        "focusBreakAllottedMs",
        "sessionStudyToolPackages",
        "quickToolPackage",
        "quickToolAppName",
        "quickToolSourcePackage",
        "quickToolUntilMs",
        "quickToolUntilElapsedMs",
        "quickToolBootWallMs",
        "quickToolUsedPackages"
    )

    /** Returns true when an older transient state was reset. */
    @Synchronized
    fun sanitize(context: Context): Boolean {
        val appContext = context.applicationContext
        val flutter = appContext.getSharedPreferences(
            flutterPrefsName,
            Context.MODE_PRIVATE
        )
        val tracking = appContext.getSharedPreferences(
            trackingPrefsName,
            Context.MODE_PRIVATE
        )
        val web = appContext.getSharedPreferences(webPrefsName, Context.MODE_PRIVATE)

        sanitizeFlutterTypes(flutter)
        sanitizeTrackingTypes(tracking)

        val storedSchema = tracking.all[schemaKey].asIntOrNull() ?: 0
        if (storedSchema >= currentSchema) return false

        // A focus session cannot be safely continued across beta schemas. Keep
        // the user's selections and analytics, but stop transient enforcement.
        flutter.edit()
            .putBoolean("flutter.studyModeEnabled", false)
            .apply()
        tracking.edit().apply {
            transientTrackingKeys.forEach(::remove)
            putInt(schemaKey, currentSchema)
        }.apply()
        web.edit()
            .putString("state", "off")
            .putString("lastError", "")
            .putString("protectedBrowsers", "[]")
            .apply()
        return true
    }

    private fun sanitizeFlutterTypes(prefs: SharedPreferences) {
        val values = runCatching { prefs.all }.getOrDefault(emptyMap())
        val editor = prefs.edit()
        var changed = false

        for (key in flutterBooleanKeys) {
            val value = values[key] ?: continue
            if (value !is Boolean) {
                editor.remove(key)
                changed = true
            }
        }
        if (values.containsKey("flutter.webProtectionDisclosureAccepted")) {
            editor.remove("flutter.webProtectionDisclosureAccepted")
            changed = true
        }
        for (key in flutterStringKeys) {
            val value = values[key] ?: continue
            if (value !is String) {
                editor.remove(key)
                changed = true
            }
        }
        values["flutter.studyDurationMinutes"]?.let { value ->
            val number = value as? Number
            if (number == null) {
                editor.remove("flutter.studyDurationMinutes")
                changed = true
            } else {
                val normalized = number.toLong().coerceIn(1L, 24L * 60L)
                if (value !is Long || value != normalized) {
                    editor.putLong("flutter.studyDurationMinutes", normalized)
                    changed = true
                }
            }
        }
        values["flutter.webProtectionDisclosureVersion"]?.let { value ->
            val number = value as? Number
            if (number == null) {
                editor.remove("flutter.webProtectionDisclosureVersion")
                changed = true
            } else {
                val normalized = number.toLong().coerceAtLeast(0L)
                if (value !is Long || value != normalized) {
                    editor.putLong("flutter.webProtectionDisclosureVersion", normalized)
                    changed = true
                }
            }
        }
        values["flutter.youtubeShortsDisclosureVersion"]?.let { value ->
            val number = value as? Number
            if (number == null) {
                editor.remove("flutter.youtubeShortsDisclosureVersion")
                changed = true
            } else {
                val normalized = number.toLong().coerceAtLeast(0L)
                if (value !is Long || value != normalized) {
                    editor.putLong(
                        "flutter.youtubeShortsDisclosureVersion",
                        normalized
                    )
                    changed = true
                }
            }
        }
        values["flutter.instagramReelsDisclosureVersion"]?.let { value ->
            val number = value as? Number
            if (number == null) {
                editor.remove("flutter.instagramReelsDisclosureVersion")
                changed = true
            } else {
                val normalized = number.toLong().coerceAtLeast(0L)
                if (value !is Long || value != normalized) {
                    editor.putLong(
                        "flutter.instagramReelsDisclosureVersion",
                        normalized
                    )
                    changed = true
                }
            }
        }
        for (key in flutterJsonArrayKeys) {
            val raw = values[key] as? String ?: continue
            if (runCatching { JSONArray(raw) }.isFailure) {
                editor.putString(key, "[]")
                changed = true
            }
        }
        for (key in flutterJsonObjectKeys) {
            val raw = values[key] as? String ?: continue
            if (runCatching { JSONObject(raw) }.isFailure) {
                editor.putString(key, "{}")
                changed = true
            }
        }
        val policy = values["flutter.studyModePolicy"] as? String
        if (policy != null && policy !in setOf("allowlist", "blocklist")) {
            editor.putString("flutter.studyModePolicy", "allowlist")
            changed = true
        }
        val trackingMode = values["flutter.studyTrackingMode"] as? String
        if (trackingMode != null && trackingMode !in setOf("app", "book")) {
            editor.putString("flutter.studyTrackingMode", "book")
            changed = true
        }
        val sessionType = values["flutter.studySessionType"] as? String
        if (sessionType != null && sessionType !in setOf("timer", "stopwatch")) {
            editor.putString("flutter.studySessionType", "timer")
            changed = true
        }
        if (changed) editor.apply()
    }

    private fun sanitizeTrackingTypes(prefs: SharedPreferences) {
        val values = runCatching { prefs.all }.getOrDefault(emptyMap())
        val editor = prefs.edit()
        var changed = false

        values["studySessionActive"]?.let {
            if (it !is Boolean) {
                editor.remove("studySessionActive")
                changed = true
            }
        }
        values["studyBreaksUsed"]?.let { value ->
            val number = value as? Number
            if (number == null) {
                editor.remove("studyBreaksUsed")
                changed = true
            } else {
                val normalized = number.toInt().coerceAtLeast(0)
                if (value !is Int || value != normalized) {
                    editor.putInt("studyBreaksUsed", normalized)
                    changed = true
                }
            }
        }
        for (key in trackingStringKeys) {
            val value = values[key] ?: continue
            if (value !is String) {
                editor.remove(key)
                changed = true
            }
        }
        for (key in trackingLongKeys) {
            val value = values[key] ?: continue
            val number = value as? Number
            if (number == null) {
                editor.remove(key)
                changed = true
            } else {
                val normalized = number.toLong()
                if (value !is Long || value != normalized) {
                    editor.putLong(key, normalized)
                    changed = true
                }
            }
        }
        for (key in trackingStringSetKeys) {
            val value = values[key] ?: continue
            if (value !is Set<*> || value.any { it !is String }) {
                editor.remove(key)
                changed = true
            }
        }
        if (changed) editor.apply()
    }

    private fun Any?.asIntOrNull(): Int? = (this as? Number)?.toInt()
}
