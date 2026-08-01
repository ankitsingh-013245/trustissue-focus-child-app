package com.trustissue.child

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.net.Uri
import android.net.VpnService
import android.util.Log
import android.util.LruCache
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val channelName = "trustissue/native"
    private val tag = "TrustIssueMainActivity"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val backgroundExecutor: ExecutorService = ThreadPoolExecutor(
        2,
        3,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(64),
        ThreadPoolExecutor.AbortPolicy()
    )
    private val appIconCache = object : LruCache<String, ByteArray>(appIconCacheBytes) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }
    private val missingAppIcons = ConcurrentHashMap.newKeySet<String>()
    private var nativeChannel: MethodChannel? = null
    private var pendingWebProtectionResult: MethodChannel.Result? = null
    private var pendingNotificationResult: MethodChannel.Result? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        @Suppress("DEPRECATION")
        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull().orEmpty().ifBlank { "unknown" }
        TrustIssueDebugLog.append(
            this,
            tag,
            "I",
            "APP_OPENED version=$versionName sdk=${Build.VERSION.SDK_INT}"
        )
    }

    override fun onResume() {
        super.onResume()
        runCatching {
            TrackerConfig.completeStudySessionIfFinished(this)
            StrictFocusDndController.reconcile(this)
            if (
                (TrackerConfig.isStudyModeEnabled(this) ||
                    TrackerConfig.hasDailyAppLimits(this)) &&
                ProtectionAccess.compatibleModeReady(this)
            ) {
                FocusUsageMonitorService.start(this)
            }
            val webProtectionRequired = WebProtectionConfig.shouldRun(this)
            if (
                WebProtectionConfig.permissionGranted(this) &&
                webProtectionRequired
            ) {
                WebProtectionConfig.startIfRequired(this)
            } else if (!WebProtectionConfig.isRuntimeRequired(this)) {
                WebProtectionConfig.stop(this)
            }
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        nativeChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            channelName
        ).also { channel ->
            channel.setMethodCallHandler { call, result ->
            try {
                when (call.method) {
                    "hasAccessibilityAccess" -> result.success(hasAccessibilityAccess())
                    "hasUsageAccess" ->
                        result.success(ProtectionAccess.hasUsageAccess(this))
                    "hasOverlayAccess" ->
                        result.success(ProtectionAccess.hasOverlayAccess(this))
                    "hasBatteryOptimizationExemption" -> {
                        val power =
                            getSystemService(Context.POWER_SERVICE) as PowerManager
                        result.success(
                            power.isIgnoringBatteryOptimizations(packageName)
                        )
                    }
                    "requestNotificationPermission" ->
                        requestNotificationPermission(result)
                    "hasDndPolicyAccess" ->
                        result.success(StrictFocusDndController.hasPolicyAccess(this))
                    "openDndPolicyAccessSettings" -> {
                        val dndSettings = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                        val destination = if (dndSettings.resolveActivity(packageManager) != null) {
                            dndSettings
                        } else {
                            Intent(Settings.ACTION_SETTINGS)
                        }
                        startActivity(destination)
                        result.success(null)
                    }
                    "openAccessibilitySettings" -> {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        result.success(null)
                    }
                    "openUsageAccessSettings" -> {
                        val usageSettings = Intent(
                            Settings.ACTION_USAGE_ACCESS_SETTINGS,
                            Uri.parse("package:$packageName")
                        )
                        val destination =
                            if (usageSettings.resolveActivity(packageManager) != null) {
                                usageSettings
                            } else {
                                Intent(Settings.ACTION_SETTINGS)
                            }
                        startActivity(destination)
                        result.success(null)
                    }
                    "openOverlaySettings" -> {
                        val overlaySettings = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        val destination =
                            if (overlaySettings.resolveActivity(packageManager) != null) {
                                overlaySettings
                            } else {
                                Intent(Settings.ACTION_SETTINGS)
                            }
                        startActivity(destination)
                        result.success(null)
                    }
                    "openBatteryOptimizationSettings" -> {
                        val batterySettings =
                            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        val destination =
                            if (batterySettings.resolveActivity(packageManager) != null) {
                                batterySettings
                            } else {
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:$packageName")
                                )
                            }
                        startActivity(destination)
                        result.success(null)
                    }
                    "openAppSettings" -> {
                        startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:$packageName")
                            )
                        )
                        result.success(null)
                    }
                    "exportDebugLog" -> {
                        runBackground(call.method, result) {
                            TrustIssueDebugLog.exportToDownloads(this)
                        }
                    }
                    "clearDebugLog" -> {
                        runBackground(call.method, result) {
                            TrustIssueDebugLog.clear(this)
                            null
                        }
                    }
                    "purgeLegacyPrivateData" -> {
                        runBackground(call.method, result) {
                            TrackerConfig.purgeLegacyPrivateData(this)
                            null
                        }
                    }
                    "clearLocalAnalytics" -> {
                        runBackground(call.method, result) {
                            TrackerConfig.clearLocalAnalytics(this)
                            null
                        }
                    }
                    "getInstalledApps" -> runBackground(call.method, result) {
                        installedLaunchableApps()
                    }
                    "getAppIcon" -> {
                        val requestedPackage = call.argument<String>("packageName")
                        runBackground(call.method, result) {
                            appIconPng(requestedPackage)
                        }
                    }
                    "getBrowserPackages" -> {
                        runBackground(call.method, result) {
                            WebProtectionConfig.invalidateBrowserCache()
                            WebProtectionConfig.browserPackages(this).toList()
                        }
                    }
                    "getPdfReaderApps" -> runBackground(call.method, result) {
                        eligiblePdfReaderApps()
                    }
                    "loadTodayUsage" -> runBackground(call.method, result) {
                        UsageStatsReader.summaryMap(this)
                    }
                    "syncAppLimitProtection" -> {
                        UsageStatsReader.invalidate()
                        val shouldRun = TrackerConfig.isStudyModeEnabled(this) ||
                            TrackerConfig.hasDailyAppLimits(this)
                        val synced = if (shouldRun) {
                            FocusUsageMonitorService.start(this)
                        } else {
                            FocusUsageMonitorService.stop(this)
                            true
                        }
                        result.success(synced)
                    }
                    "loadWebProtectionState" ->
                        runBackground(call.method, result) {
                            WebProtectionConfig.stateMap(this)
                        }
                    "prepareWebProtection" -> prepareWebProtection(
                        replaceExistingVpn =
                            call.argument<Boolean>("replaceExistingVpn") == true,
                        forceRequired =
                            call.argument<Boolean>("forceRequired") == true,
                        result = result
                    )
                    "syncWebProtection" -> syncWebProtection(result)
                    "setExternalVpnCompatibility" -> {
                        val enabled = call.argument<Boolean>("enabled") == true
                        val accepted =
                            !enabled || VpnConflictDetector.isExternalVpnActive(this)
                        if (accepted) {
                            WebProtectionConfig.setExternalVpnCompatibility(
                                this,
                                enabled
                            )
                        }
                        result.success(accepted)
                    }
                    "loadFocusBreakState" -> {
                        result.success(
                            mapOf(
                                "active" to TrackerConfig.isFocusBreakActive(this),
                                "remainingMs" to TrackerConfig.remainingFocusBreakMs(this)
                            )
                        )
                    }
                    "loadFocusSessionState" -> result.success(TrackerConfig.focusSessionStateMap(this))
                    "endFocusBreak" -> {
                        result.success(TrackerConfig.finishFocusBreak(this, "manual_end"))
                    }
                    "getAlwaysAllowedPackages" -> runBackground(call.method, result) {
                        alwaysAllowedPackages()
                    }
                    "recordStudyModeChanged" -> {
                        val requestedEnabled = call.argument<Boolean>("enabled") == true
                        val failure = when {
                            !requestedEnabled &&
                                TrackerConfig.isStudyModeEnabled(this) &&
                                TrackerConfig.isLockedStrictFocusModeEnabled(this) -> Pair(
                                "locked_strict",
                                "Locked Strict ends only when its timer finishes."
                            )
                            !requestedEnabled -> null
                            !ProtectionAccess.hasUsageAccess(this) -> Pair(
                                "usage_access_missing",
                                "Usage Access is required for compatible app blocking."
                            )
                            !ProtectionAccess.hasOverlayAccess(this) -> Pair(
                                "overlay_access_missing",
                                "Display over other apps is required for the block screen and timer."
                            )
                            TrackerConfig.isStrictFocusModeEnabled(this) &&
                                !StrictFocusDndController.hasPolicyAccess(this) -> Pair(
                                "dnd_access_missing",
                                "Allow Do Not Disturb access before starting Strict Focus."
                            )
                            !TrackerConfig.hasValidStudySetup(this) -> Pair(
                                "apps_missing",
                                if (TrackerConfig.isAppStudyMode(this)) {
                                    "App Study requires Allowlist and at least one study app."
                                } else {
                                    "Choose at least one app before starting focus."
                                }
                            )
                            !TrackerConfig.hasStudyDuration(this) -> Pair(
                                "duration_missing",
                                "Choose a focus duration before starting."
                            )
                            !TrackerConfig.isStudyDurationWithinLimit(this) -> Pair(
                                "duration_too_long",
                                when {
                                    TrackerConfig.isLockedStrictFocusModeEnabled(this) ->
                                        "Locked Strict is limited to 3 hours for safety."
                                    TrackerConfig.isStrictFocusModeEnabled(this) ->
                                        "Strict Focus is limited to 8 hours."
                                    else -> "Focus is limited to 24 hours."
                                }
                            )
                            WebProtectionConfig.isExternalVpnCompatibilityEnabled(this) &&
                                !VpnConflictDetector.isExternalVpnActive(this) -> Pair(
                                "external_vpn_missing",
                                "The selected VPN is no longer active. Review browser protection and try again."
                            )
                            WebProtectionConfig.requiresLocalVpn(this) &&
                                !WebProtectionConfig.permissionGranted(this) -> Pair(
                                "web_permission_missing",
                                "Allowed browsers require Focus Web Protection consent."
                            )
                            else -> null
                        }
                        if (failure != null) {
                            if (requestedEnabled) {
                                TrackerConfig.setStudyModeEnabled(this, false)
                                WebProtectionConfig.setExternalVpnCompatibility(
                                    this,
                                    false
                                )
                            }
                            result.success(
                                mapOf(
                                    "success" to false,
                                    "code" to failure.first,
                                    "message" to failure.second
                                )
                            )
                        } else {
                            val focusStateStored =
                                TrackerConfig.setStudyModeEnabled(this, requestedEnabled)
                            if (!focusStateStored) {
                                WebProtectionConfig.setExternalVpnCompatibility(
                                    this,
                                    false
                                )
                                result.success(
                                    mapOf(
                                        "success" to false,
                                        "code" to "focus_state_not_saved",
                                        "message" to "Focus state could not be saved safely."
                                    )
                                )
                                return@setMethodCallHandler
                            }
                            val compatibleProtectionStarted =
                                if (
                                    requestedEnabled ||
                                    TrackerConfig.hasDailyAppLimits(this)
                                ) {
                                    FocusUsageMonitorService.start(this)
                                } else {
                                    FocusUsageMonitorService.stop(this)
                                    true
                                }
                            if (requestedEnabled && !compatibleProtectionStarted) {
                                TrackerConfig.setStudyModeEnabled(this, false)
                                WebProtectionConfig.setExternalVpnCompatibility(
                                    this,
                                    false
                                )
                                result.success(
                                    mapOf(
                                        "success" to false,
                                        "code" to "compatible_protection_failed",
                                        "message" to
                                            "Android could not start compatible Focus protection."
                                    )
                                )
                                return@setMethodCallHandler
                            }
                            val webStarted = if (requestedEnabled) {
                                WebProtectionConfig.startIfRequired(this)
                            } else {
                                // TrackerConfig.setStudyModeEnabled(false)
                                // already reconciles Web Protection after the
                                // persisted Focus flag is cleared.
                                WebProtectionConfig.setExternalVpnCompatibility(
                                    this,
                                    false
                                )
                                true
                            }
                            if (requestedEnabled && !webStarted) {
                                TrackerConfig.setStudyModeEnabled(this, false)
                                WebProtectionConfig.setExternalVpnCompatibility(
                                    this,
                                    false
                                )
                                if (TrackerConfig.hasDailyAppLimits(this)) {
                                    FocusUsageMonitorService.start(this)
                                } else {
                                    FocusUsageMonitorService.stop(this)
                                }
                                result.success(
                                    mapOf(
                                        "success" to false,
                                        "code" to "web_protection_failed",
                                        "message" to
                                            "Website Protection could not start. Focus was not started."
                                    )
                                )
                                return@setMethodCallHandler
                            }
                            if (
                                !requestedEnabled &&
                                !WebProtectionConfig.shouldRun(this)
                            ) {
                                awaitWebProtectionStopped { stopped ->
                                    result.success(
                                        mapOf(
                                            "success" to true,
                                            "code" to if (stopped) {
                                                "stopped"
                                            } else {
                                                "stopped_web_pending"
                                            },
                                            "message" to if (stopped) {
                                                "Focus session stopped."
                                            } else {
                                                "Focus stopped, but Website Protection is still disconnecting."
                                            }
                                        )
                                    )
                                }
                            } else {
                                result.success(
                                    mapOf(
                                        "success" to true,
                                        "code" to if (requestedEnabled) {
                                            "started"
                                        } else {
                                            "stopped"
                                        },
                                        "message" to if (requestedEnabled) {
                                            "Focus session started."
                                        } else {
                                            "Focus session stopped."
                                        }
                                    )
                                )
                            }
                        }
                    }
                    "openEmergencyExit" -> {
                        when {
                            !TrackerConfig.isStudyModeEnabled(this) ->
                                result.error(
                                    "focus_inactive",
                                    "There is no active Focus session.",
                                    null
                                )
                            !TrackerConfig.isStrictFocusModeEnabled(this) ->
                                result.error(
                                    "strict_inactive",
                                    "Emergency Exit is only used by Strict Focus.",
                                    null
                                )
                            TrackerConfig.isLockedStrictFocusModeEnabled(this) ->
                                result.error(
                                    "locked_strict",
                                    "Locked Strict has no emergency exit.",
                                    null
                                )
                            else -> {
                                startActivity(
                                    Intent(this, FocusGateActivity::class.java).apply {
                                        addFlags(
                                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                                        )
                                        putExtra(
                                            FocusGateActivity.EXTRA_PACKAGE_NAME,
                                            packageName
                                        )
                                        putExtra(
                                            FocusGateActivity.EXTRA_APP_NAME,
                                            TrackerConfig.appName(this@MainActivity, packageName)
                                        )
                                        putExtra(
                                            FocusGateActivity.EXTRA_RETURN_TO_PACKAGE,
                                            true
                                        )
                                        putExtra(
                                            FocusGateActivity.EXTRA_SHOW_EMERGENCY_EXIT,
                                            true
                                        )
                                    }
                                )
                                result.success(null)
                            }
                        }
                    }
                    "recordEmergencyExit" -> {
                        TrackerConfig.recordEmergencyExit(
                            this,
                            call.argument<String>("reason").orEmpty(),
                            call.argument<String>("source").orEmpty()
                        )
                        result.success(null)
                    }
                    "recordEmergencyExitAttempt" -> {
                        TrackerConfig.recordEmergencyExitAttempt(
                            this,
                            call.argument<String>("source").orEmpty()
                        )
                        result.success(null)
                    }
                    "recordEmergencyExitCancelled" -> {
                        TrackerConfig.recordEmergencyExitCancelled(
                            this,
                            call.argument<String>("source").orEmpty()
                        )
                        result.success(null)
                    }
                    "loadSelfControlAnalytics" -> {
                        val days = (call.argument<Number>("days"))?.toInt() ?: 30
                        runBackground(call.method, result) {
                            TrackerConfig.selfControlAnalytics(this, days)
                        }
                    }
                    else -> result.notImplemented()
                }
            } catch (error: RuntimeException) {
                Log.e(tag, "Native method failed: ${call.method}", error)
                TrustIssueDebugLog.append(
                    this,
                    tag,
                    "E",
                    "NATIVE_METHOD_FAILED method=${call.method} error=${error.localizedMessage.orEmpty()}"
                )
                result.error("native_error", error.localizedMessage, null)
            }
            }
        }
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        nativeChannel?.setMethodCallHandler(null)
        nativeChannel = null
        backgroundExecutor.shutdownNow()
        appIconCache.evictAll()
        missingAppIcons.clear()
        super.cleanUpFlutterEngine(flutterEngine)
    }

    private fun <T> runBackground(
        method: String,
        result: MethodChannel.Result,
        operation: () -> T
    ) {
        try {
            backgroundExecutor.execute {
                try {
                    val value = operation()
                    mainHandler.post { result.success(value) }
                } catch (error: Exception) {
                    Log.e(tag, "Background native method failed: $method", error)
                    TrustIssueDebugLog.append(
                        this,
                        tag,
                        "E",
                        "NATIVE_METHOD_FAILED method=$method " +
                            "error=${error.localizedMessage.orEmpty()}"
                    )
                    mainHandler.post {
                        result.error("native_error", error.localizedMessage, null)
                    }
                }
            }
        } catch (error: RuntimeException) {
            Log.e(tag, "Could not schedule native method: $method", error)
            result.error("native_unavailable", error.localizedMessage, null)
        }
    }

    @Deprecated("Uses the platform VPN consent activity result.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != webProtectionRequestCode) return
        val pending = pendingWebProtectionResult ?: return
        pendingWebProtectionResult = null
        val granted =
            resultCode == Activity.RESULT_OK &&
                WebProtectionConfig.permissionGranted(this)
        if (granted) {
            WebProtectionConfig.setExternalVpnCompatibility(this, false)
        }
        pending.success(
            mapOf(
                "success" to granted,
                "code" to if (granted) "granted" else "denied",
                "message" to if (granted) {
                    "Focus Web Protection is ready."
                } else {
                    "Website Protection was not allowed. No settings were changed."
                }
            )
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != notificationPermissionRequestCode) return
        val pending = pendingNotificationResult ?: return
        pendingNotificationResult = null
        pending.success(
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        )
    }

    private fun requestNotificationPermission(result: MethodChannel.Result) {
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            result.success(true)
            return
        }
        if (pendingNotificationResult != null) {
            result.success(false)
            return
        }
        pendingNotificationResult = result
        requestPermissions(
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            notificationPermissionRequestCode
        )
    }

    private fun prepareWebProtection(
        replaceExistingVpn: Boolean,
        forceRequired: Boolean,
        result: MethodChannel.Result
    ) {
        if (!forceRequired && !WebProtectionConfig.isRequired(this)) {
            result.success(
                mapOf(
                    "success" to true,
                    "code" to "not_required",
                    "message" to "All browsers are blocked; Web Protection is not required."
                )
            )
            return
        }
        val externalVpnActive = VpnConflictDetector.isExternalVpnActive(this)
        if (externalVpnActive && !replaceExistingVpn) {
            result.success(
                mapOf(
                    "success" to false,
                    "code" to "vpn_conflict",
                    "message" to
                        "Another VPN is active. Keep it without Focus website filtering, or switch to TrustIssue protection."
                )
            )
            return
        }
        if (!externalVpnActive) {
            WebProtectionConfig.setExternalVpnCompatibility(this, false)
        }
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent == null) {
            result.success(
                mapOf(
                    "success" to true,
                    "code" to "already_granted",
                    "message" to "Focus Web Protection is ready."
                )
            )
            return
        }
        if (pendingWebProtectionResult != null) {
            result.success(
                mapOf(
                    "success" to false,
                    "code" to "request_in_progress",
                    "message" to "Web Protection permission is already being requested."
                )
            )
            return
        }
        pendingWebProtectionResult = result
        runCatching {
            startActivityForResult(prepareIntent, webProtectionRequestCode)
        }.onFailure {
            pendingWebProtectionResult = null
            result.success(
                mapOf(
                    "success" to false,
                    "code" to "request_failed",
                    "message" to "Android could not open the Web Protection consent screen."
                )
            )
        }
    }

    private fun syncWebProtection(result: MethodChannel.Result) {
        WebProtectionConfig.logRuleConfiguration(this, "flutter_sync")
        val shouldRun = WebProtectionConfig.shouldRun(this)
        val reconciled = WebProtectionConfig.reconcile(this)
        if (!reconciled || shouldRun) {
            result.success(reconciled)
            return
        }

        awaitWebProtectionStopped { stopped -> result.success(stopped) }
    }

    private fun awaitWebProtectionStopped(onComplete: (Boolean) -> Unit) {
        val deadline = SystemClock.elapsedRealtime() + webProtectionStopTimeoutMs
        val stopCheck = object : Runnable {
            override fun run() {
                val stopped =
                    !FocusWebProtectionService.isRunningInProcess() &&
                        WebProtectionConfig.storedState(this@MainActivity) ==
                        WebProtectionConfig.stateOff
                if (stopped) {
                    onComplete(true)
                } else if (SystemClock.elapsedRealtime() >= deadline) {
                    TrustIssueDebugLog.append(
                        this@MainActivity,
                        tag,
                        "W",
                        "WEB_PROTECTION_STOP_TIMEOUT state=" +
                            WebProtectionConfig.storedState(this@MainActivity)
                    )
                    onComplete(false)
                } else {
                    mainHandler.postDelayed(this, webProtectionStopPollMs)
                }
            }
        }
        mainHandler.post(stopCheck)
    }

    private fun hasAccessibilityAccess(): Boolean {
        return ProtectionAccess.hasAccessibilityAccess(this)
    }

    // Only true emergency/system defaults stay reachable. Apps that merely
    // advertise dial/SMS handling must remain user-selectable and blockable.
    private fun alwaysAllowedPackages(): List<String> {
        val packages = linkedSetOf(
            "android",
            "com.android.systemui",
            "com.android.dialer",
            "com.google.android.dialer",
            "com.samsung.android.dialer",
            "com.android.phone",
            "com.android.server.telecom",
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.android.settings"
        )
        runCatching {
            val telecom = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecom.defaultDialerPackage?.takeIf(String::isNotBlank)?.let(packages::add)
        }
        runCatching {
            Telephony.Sms.getDefaultSmsPackage(this)
                ?.takeIf(String::isNotBlank)
                ?.let(packages::add)
        }
        return packages.toList()
    }

    private fun installedLaunchableApps(): List<Map<String, String>> {
        val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, 0)
        }

        return activities
            .mapNotNull { resolveInfo ->
                val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
                val appPackageName = activityInfo.packageName ?: return@mapNotNull null
                if (appPackageName == packageName) return@mapNotNull null
                val label = resolveInfo.loadLabel(packageManager)?.toString()?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: appPackageName
                mapOf("appName" to label, "packageName" to appPackageName)
            }
            .distinctBy { it["packageName"] }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it["appName"].orEmpty() })
    }

    private fun eligiblePdfReaderApps(): List<Map<String, String>> {
        val resolver = StudyToolResolver(this)
        val readers = resolver.defaultPdfReaderPackages()
            .map { packageName ->
                mapOf(
                    "appName" to TrackerConfig.appName(this, packageName),
                    "packageName" to packageName
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it["appName"].orEmpty() })
        TrustIssueDebugLog.append(
            this,
            tag,
            "I",
            "PDF_READERS_DISCOVERED count=${readers.size}"
        )
        return readers
    }

    /// Loads the real launcher icon for [packageName] and returns it as PNG
    /// bytes. Flutter renders these directly; returns null when the icon can't
    /// be resolved so the UI can fall back to a lettered tile.
    private fun appIconPng(packageName: String?): ByteArray? {
        val pkg = packageName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        appIconCache.get(pkg)?.let { return it }
        if (missingAppIcons.contains(pkg)) return null
        return try {
            val drawable = packageManager.getApplicationIcon(pkg)
            val target = (48 * resources.displayMetrics.density).toInt().coerceIn(96, 192)
            val bitmap = drawableToBitmap(drawable, target)
            ByteArrayOutputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                stream.toByteArray().also { appIconCache.put(pkg, it) }
            }
        } catch (error: Exception) {
            Log.w(tag, "Icon load failed for $pkg", error)
            missingAppIcons.add(pkg)
            null
        }
    }

    private fun drawableToBitmap(drawable: Drawable, size: Int): Bitmap {
        if (drawable is BitmapDrawable) {
            drawable.bitmap?.let { return Bitmap.createScaledBitmap(it, size, size, true) }
        }
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private companion object {
        const val webProtectionRequestCode = 84062
        const val notificationPermissionRequestCode = 84063
        const val webProtectionStopTimeoutMs = 3_000L
        const val webProtectionStopPollMs = 50L
        const val appIconCacheBytes = 4 * 1024 * 1024
    }
}
