package com.trustissue.child

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.SystemClock
import org.json.JSONArray
import java.net.IDN
import java.util.Locale

/**
 * Single source of truth for global and conditional Focus Web Protection.
 *
 * Global mode protects every installed browser. During Focus, allowed-browser
 * policy and focus-specific domain rules are added without exposing a silent
 * per-session bypass.
 */
object WebProtectionConfig {
    private const val flutterPrefsName = "FlutterSharedPreferences"
    private const val localPrefsName = "trustissue_web_protection"
    private const val stateKey = "state"
    private const val stateChangedAtKey = "stateChangedAtMs"
    private const val lastErrorKey = "lastError"
    private const val protectedBrowsersKey = "protectedBrowsers"
    private const val externalVpnCompatibilityKey = "externalVpnCompatibility"
    private const val customDomainsKey = "flutter.webProtectionCustomDomainsJson"
    private const val globalEnabledKey = "flutter.globalWebProtectionEnabled"
    private const val globalCustomDomainsKey =
        "flutter.globalWebProtectionCustomDomainsJson"
    private const val browserCacheDurationMs = 15_000L

    private val browserCacheLock = Any()
    @Volatile
    private var cachedBrowserPackages: Set<String>? = null
    @Volatile
    private var browserCacheUpdatedAtMs = 0L

    const val stateOff = "off"
    const val stateStopping = "stopping"
    const val stateStarting = "starting"
    const val stateActive = "active"
    const val stateReconnecting = "reconnecting"
    const val stateDegraded = "degraded"
    const val stateExternalVpn = "external_vpn"

    const val actionStateChanged =
        "com.trustissue.child.action.WEB_PROTECTION_STATE_CHANGED"
    const val actionDomainBlocked =
        "com.trustissue.child.action.WEB_DOMAIN_BLOCKED"
    const val extraDomain = "blockedDomain"
    const val extraCategory = "blockedCategory"

    private data class ServiceRule(
        val packages: Set<String>,
        val domains: Set<String>
    )

    data class RuleCounts(
        val mandatory: Int,
        val globalSaved: Int,
        val focusSaved: Int,
        val globalActive: Int,
        val focusActive: Int,
        val appDerived: Int,
        val totalActive: Int
    ) {
        fun logFields(): String =
            "mandatory=$mandatory globalSaved=$globalSaved focusSaved=$focusSaved " +
                "globalActive=$globalActive focusActive=$focusActive " +
                "appDerived=$appDerived total=$totalActive"
    }

    private val serviceRules = listOf(
        ServiceRule(
            setOf("com.google.android.youtube"),
            setOf("youtube.com", "youtu.be", "youtube-nocookie.com")
        ),
        ServiceRule(
            setOf("com.instagram.android", "com.instagram.lite"),
            setOf("instagram.com", "cdninstagram.com", "threads.net")
        ),
        ServiceRule(
            setOf("com.facebook.katana", "com.facebook.lite"),
            setOf("facebook.com", "fb.com", "fbcdn.net")
        ),
        ServiceRule(
            setOf("com.facebook.orca"),
            setOf("messenger.com")
        ),
        ServiceRule(
            setOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
            setOf("tiktok.com", "tiktokcdn.com")
        ),
        ServiceRule(
            setOf("com.twitter.android"),
            setOf("x.com", "twitter.com")
        ),
        ServiceRule(setOf("com.reddit.frontpage"), setOf("reddit.com")),
        ServiceRule(setOf("com.snapchat.android"), setOf("snapchat.com")),
        ServiceRule(setOf("com.pinterest"), setOf("pinterest.com")),
        ServiceRule(setOf("tv.twitch.android.app"), setOf("twitch.tv")),
        ServiceRule(setOf("com.netflix.mediaclient"), setOf("netflix.com")),
        ServiceRule(setOf("in.startv.hotstar"), setOf("hotstar.com"))
    )

    /**
     * Small bundled safety floor. The encrypted upstream family resolver
     * provides broad adult/malware categorisation; these entries protect common
     * bypass services before a network request leaves the device.
     */
    private val mandatoryDomains = mapOf(
        "adult" to setOf(
            "pornhub.com",
            "xvideos.com",
            "xnxx.com",
            "redtube.com",
            "youporn.com"
        ),
        "proxy" to setOf(
            "hide.me",
            "proxysite.com",
            "kproxy.com",
            "croxyproxy.com",
            "hidemyass.com"
        ),
        "secure_dns" to setOf(
            "dns.google",
            "cloudflare-dns.com",
            "one.one.one.one",
            "dns.quad9.net",
            "dns.adguard-dns.com",
            "doh.opendns.com"
        )
    )

    fun browserPackages(context: Context): Set<String> {
        val now = SystemClock.elapsedRealtime()
        cachedBrowserPackages?.let { cached ->
            if (now - browserCacheUpdatedAtMs < browserCacheDurationMs) {
                return cached
            }
        }
        return synchronized(browserCacheLock) {
            val current = cachedBrowserPackages
            val refreshedAt = browserCacheUpdatedAtMs
            if (current != null && now - refreshedAt < browserCacheDurationMs) {
                current
            } else {
                discoverBrowserPackages(context).also {
                    cachedBrowserPackages = it
                    browserCacheUpdatedAtMs = now
                }
            }
        }
    }

    private fun discoverBrowserPackages(context: Context): Set<String> {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        @Suppress("DEPRECATION")
        return runCatching {
            context.packageManager.queryIntentActivities(intent, 0)
                .mapNotNull { it.activityInfo?.packageName }
                .filter {
                    it.isNotBlank() &&
                        it != context.packageName &&
                        it != "android"
                }
                .toSet()
        }.getOrDefault(emptySet())
    }

    fun invalidateBrowserCache() {
        synchronized(browserCacheLock) {
            cachedBrowserPackages = null
            browserCacheUpdatedAtMs = 0L
        }
    }

    fun isBrowserPackage(context: Context, packageName: String): Boolean {
        if (packageName.isBlank() || packageName == context.packageName) return false
        return browserPackages(context).contains(packageName)
    }

    fun allowedBrowserPackages(context: Context): Set<String> {
        val browsers = browserPackages(context)
        if (browsers.isEmpty()) return emptySet()
        val policy = TrackerConfig.studyPolicy(context)
        return if (policy == "blocklist") {
            val blockedPackages = TrackerConfig.studyBlockedPackages(context)
            browsers.filterTo(linkedSetOf()) {
                !blockedPackages.contains(it)
            }
        } else {
            val allowedPackages = TrackerConfig.studyAllowedPackages(context)
            browsers.filterTo(linkedSetOf()) {
                allowedPackages.contains(it)
            }
        }
    }

    fun runtimeBrowserPackages(context: Context): Set<String> {
        return if (isGlobalProtectionEnabled(context)) {
            browserPackages(context)
        } else if (TrackerConfig.isStudyModeEnabled(context)) {
            allowedBrowserPackages(context)
        } else {
            emptySet()
        }
    }

    fun isRequired(context: Context): Boolean {
        return allowedBrowserPackages(context).isNotEmpty()
    }

    fun isRuntimeRequired(context: Context): Boolean {
        return runtimeBrowserPackages(context).isNotEmpty()
    }

    fun shouldRun(context: Context): Boolean {
        if (!isRuntimeRequired(context)) return false
        return !(
            isExternalVpnCompatibilityEnabled(context) &&
                VpnConflictDetector.isExternalVpnActive(context)
            )
    }

    fun permissionGranted(context: Context): Boolean {
        return runCatching { VpnService.prepare(context) == null }
            .getOrDefault(false)
    }

    fun storedState(context: Context): String {
        return context.getSharedPreferences(localPrefsName, Context.MODE_PRIVATE)
            .getString(stateKey, stateOff)
            .orEmpty()
            .ifBlank { stateOff }
    }

    fun isHealthy(context: Context): Boolean {
        if (!FocusWebProtectionService.isRunningInProcess()) return false
        return storedState(context) == stateActive
    }

    fun requiresLocalVpn(context: Context): Boolean {
        if (!isRequired(context)) return false
        return !(
            isExternalVpnCompatibilityEnabled(context) &&
                VpnConflictDetector.isExternalVpnActive(context)
            )
    }

    fun isExternalVpnCompatibilityEnabled(context: Context): Boolean {
        return context.getSharedPreferences(localPrefsName, Context.MODE_PRIVATE)
            .getBoolean(externalVpnCompatibilityKey, false)
    }

    fun setExternalVpnCompatibility(context: Context, enabled: Boolean) {
        val preferences =
            context.getSharedPreferences(localPrefsName, Context.MODE_PRIVATE)
        if (preferences.getBoolean(externalVpnCompatibilityKey, false) == enabled) {
            return
        }
        preferences.edit()
            .putBoolean(externalVpnCompatibilityKey, enabled)
            .apply()
        TrustIssueDebugLog.append(
            context,
            "WebProtectionConfig",
            "I",
            "EXTERNAL_VPN_COMPATIBILITY enabled=$enabled"
        )
        context.sendBroadcast(
            Intent(actionStateChanged).setPackage(context.packageName)
        )
    }

    fun setProtectedBrowserPackages(context: Context, packages: Set<String>) {
        context.getSharedPreferences(localPrefsName, Context.MODE_PRIVATE)
            .edit()
            .putString(
                protectedBrowsersKey,
                JSONArray().apply {
                    packages.filter { it.isNotBlank() }.sorted().forEach(::put)
                }.toString()
            )
            .apply()
    }

    fun isBrowserCovered(context: Context, packageName: String): Boolean {
        return protectedBrowserPackages(context).contains(packageName)
    }

    fun protectedBrowserPackages(context: Context): Set<String> {
        val raw = context.getSharedPreferences(localPrefsName, Context.MODE_PRIVATE)
            .getString(protectedBrowsersKey, "[]")
        val packages = runCatching { JSONArray(raw ?: "[]") }
            .getOrDefault(JSONArray())
        val result = linkedSetOf<String>()
        for (index in 0 until packages.length()) {
            packages.optString(index).trim()
                .takeIf { it.isNotEmpty() }
                ?.let(result::add)
        }
        return result
    }

    fun stateMap(context: Context): Map<String, Any> {
        val globalEnabled = isGlobalProtectionEnabled(context)
        val globalRequired = globalEnabled && browserPackages(context).isNotEmpty()
        val required = isRequired(context) || globalRequired
        val sessionActive = TrackerConfig.isStudyModeEnabled(context)
        val protectionActive = sessionActive || globalRequired
        val compatibility = isExternalVpnCompatibilityEnabled(context)
        val externalVpnActive = VpnConflictDetector.isExternalVpnActive(context)
        val stored = storedState(context)
        val liveStored = when {
            protectionActive && stored == stateOff -> stateDegraded
            protectionActive &&
                stored in setOf(stateActive, stateReconnecting) &&
                !FocusWebProtectionService.isRunningInProcess() -> stateDegraded
            else -> stored
        }
        val permission = if (
            protectionActive &&
            liveStored in setOf(stateStarting, stateActive, stateReconnecting)
        ) {
            true
        } else {
            permissionGranted(context)
        }
        val effectiveState = when {
            stored == stateStopping -> stateStopping
            !required -> "not_required"
            protectionActive && compatibility && externalVpnActive -> stateExternalVpn
            protectionActive && compatibility -> stateDegraded
            protectionActive -> liveStored
            permission -> "ready"
            else -> "permission_required"
        }
        val message = when (effectiveState) {
            "not_required" -> "All installed browsers are blocked; Web Protection stays off."
            "permission_required" -> "Browser access requires Android Web Protection consent."
            "ready" -> "Web Protection will start automatically with Focus."
            stateStopping -> "Disconnecting Website Protection..."
            stateStarting -> "Securing browser access..."
            stateActive -> "Adult and distracting websites are filtered."
            stateReconnecting -> "Securing the new network connection..."
            stateDegraded -> "Web Protection is unavailable; browsers are blocked."
            stateExternalVpn ->
                "Your existing VPN is active. Focus website filtering is paused."
            else -> "Web Protection is off."
        }
        val counts = ruleCounts(context)
        return mapOf(
            "required" to required,
            "state" to effectiveState,
            "permissionGranted" to permission,
            "externalVpnActive" to externalVpnActive,
            "externalVpnCompatibility" to compatibility,
            "globalEnabled" to globalEnabled,
            "globalCustomCount" to counts.globalSaved,
            "focusCustomCount" to counts.focusSaved,
            "activeRuleCount" to counts.totalActive,
            "protectedBrowserCount" to protectedBrowserPackages(context).size,
            "message" to message
        )
    }

    fun isGlobalProtectionEnabled(context: Context): Boolean {
        return context.getSharedPreferences(flutterPrefsName, Context.MODE_PRIVATE)
            .getBoolean(globalEnabledKey, false)
    }

    fun customDomains(context: Context): Set<String> {
        val raw = context.getSharedPreferences(flutterPrefsName, Context.MODE_PRIVATE)
            .getString(customDomainsKey, "[]")
        val array = runCatching { JSONArray(raw ?: "[]") }.getOrDefault(JSONArray())
        val result = linkedSetOf<String>()
        for (index in 0 until array.length()) {
            normalizeDomain(array.optString(index))?.let(result::add)
        }
        return result
    }

    fun globalCustomDomains(context: Context): Set<String> {
        val raw = context.getSharedPreferences(flutterPrefsName, Context.MODE_PRIVATE)
            .getString(globalCustomDomainsKey, "[]")
        val array = runCatching { JSONArray(raw ?: "[]") }.getOrDefault(JSONArray())
        val result = linkedSetOf<String>()
        for (index in 0 until array.length()) {
            normalizeDomain(array.optString(index))?.let(result::add)
        }
        return result
    }

    fun blockedDomainRules(context: Context): Map<String, String> {
        val rules = linkedMapOf<String, String>()
        for ((category, domains) in mandatoryDomains) {
            domains.forEach { rules[it] = category }
        }
        if (isGlobalProtectionEnabled(context)) {
            globalCustomDomains(context).forEach { rules[it] = "global_custom" }
        }
        if (!TrackerConfig.isStudyModeEnabled(context)) return rules

        customDomains(context).forEach { rules[it] = "focus_custom" }

        val policy = TrackerConfig.studyPolicy(context)
        val selectedPackages = if (policy == "blocklist") {
            TrackerConfig.studyBlockedPackages(context)
        } else {
            TrackerConfig.studyAllowedPackages(context)
        }
        for (serviceRule in serviceRules) {
            val installedPackages = serviceRule.packages.filter {
                isPackageInstalled(context, it)
            }
            val shouldBlock = if (policy == "blocklist") {
                installedPackages.any(selectedPackages::contains)
            } else {
                installedPackages.isNotEmpty() &&
                    installedPackages.none(selectedPackages::contains)
            }
            if (shouldBlock) {
                serviceRule.domains.forEach { rules[it] = "blocked_app" }
            }
        }
        return rules
    }

    fun ruleCounts(context: Context): RuleCounts {
        val mandatoryRules = mandatoryDomains.values.flatten().toSet()
        val globalSavedRules = globalCustomDomains(context)
        val focusSavedRules = customDomains(context)
        val globalActiveRules =
            if (isGlobalProtectionEnabled(context)) globalSavedRules else emptySet()
        val focusActiveRules =
            if (TrackerConfig.isStudyModeEnabled(context)) focusSavedRules else emptySet()
        val activeRules = blockedDomainRules(context)
        val configuredRules =
            mandatoryRules + globalActiveRules + focusActiveRules
        return RuleCounts(
            mandatory = mandatoryRules.size,
            globalSaved = globalSavedRules.size,
            focusSaved = focusSavedRules.size,
            globalActive = globalActiveRules.size,
            focusActive = focusActiveRules.size,
            appDerived = activeRules.keys.count { it !in configuredRules },
            totalActive = activeRules.size
        )
    }

    fun logRuleConfiguration(context: Context, source: String) {
        TrustIssueDebugLog.append(
            context,
            "WebProtectionConfig",
            "I",
            "DOMAIN_RULE_CONFIG source=${source.take(32)} " +
                ruleCounts(context).logFields()
        )
    }

    fun matchBlockedDomain(
        rawDomain: String,
        rules: Map<String, String>
    ): String? {
        val domain = normalizeDomain(rawDomain) ?: return "invalid"
        for ((rule, category) in rules) {
            if (domain == rule || domain.endsWith(".$rule")) return category
        }
        return null
    }

    fun normalizeDomain(raw: String): String? {
        var value = raw.trim().lowercase(Locale.US)
        if (value.isBlank()) return null
        value = value
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringBefore(':')
            .trim('.')
        if (value.isBlank() || value.length > 253) return null
        val ascii = runCatching { IDN.toASCII(value) }.getOrNull()
            ?.lowercase(Locale.US)
            ?: return null
        if (
            ascii.isBlank() ||
            ascii.contains("..") ||
            !ascii.contains('.') ||
            ascii.any { !(it.isLetterOrDigit() || it == '.' || it == '-') }
        ) {
            return null
        }
        if (ascii.split('.').any { it.isBlank() || it.startsWith('-') || it.endsWith('-') }) {
            return null
        }
        return ascii
    }

    fun setState(context: Context, state: String, error: String = "") {
        val preferences =
            context.getSharedPreferences(localPrefsName, Context.MODE_PRIVATE)
        val previousState = preferences.getString(stateKey, stateOff).orEmpty()
        val previousError = preferences.getString(lastErrorKey, "").orEmpty()
        preferences
            .edit()
            .putString(stateKey, state)
            .putLong(stateChangedAtKey, System.currentTimeMillis())
            .putString(lastErrorKey, error.take(120))
            .apply()
        if (state != previousState || error != previousError) {
            TrustIssueDebugLog.append(
                context,
                "WebProtectionConfig",
                if (state == stateDegraded) "W" else "I",
                "STATE_CHANGED from=$previousState to=$state " +
                    "error=${error.ifBlank { "none" }}"
            )
        }
        context.sendBroadcast(
            Intent(actionStateChanged).setPackage(context.packageName)
        )
    }

    fun startIfRequired(context: Context): Boolean {
        if (!shouldRun(context)) {
            stopService(context)
            return true
        }
        if (!permissionGranted(context)) {
            setState(context, stateDegraded, "vpn_permission_missing")
            return false
        }
        if (FocusWebProtectionService.isRunningInProcess()) {
            return runCatching {
                val intent = Intent(context, FocusWebProtectionService::class.java)
                    .setAction(FocusWebProtectionService.actionRefreshRules)
                context.startService(intent)
                true
            }.getOrDefault(false)
        }
        setState(context, stateStarting)
        return runCatching {
            val intent = Intent(context, FocusWebProtectionService::class.java)
                .setAction(FocusWebProtectionService.actionStart)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            true
        }.onFailure {
            setState(context, stateDegraded, it.javaClass.simpleName)
        }.getOrDefault(false)
    }

    fun stop(context: Context) {
        stopService(context)
        setExternalVpnCompatibility(context, false)
    }

    fun reconcile(context: Context): Boolean {
        return if (shouldRun(context)) {
            startIfRequired(context)
        } else {
            stop(context)
            true
        }
    }

    private fun stopService(context: Context) {
        if (FocusWebProtectionService.isRunningInProcess()) {
            setState(context, stateStopping)
            val stopRequested = runCatching {
                context.startService(
                    Intent(context, FocusWebProtectionService::class.java)
                        .setAction(FocusWebProtectionService.actionStop)
                )
                true
            }.getOrDefault(false)
            if (stopRequested) return
        }
        if (storedState(context) != stateOff) {
            setState(context, stateStopping)
        }
        val stopped = runCatching {
            context.stopService(Intent(context, FocusWebProtectionService::class.java))
        }.getOrDefault(false)
        // A successful stop request is completed asynchronously. Keep the
        // public state at "stopping" until VpnService.onDestroy closes the TUN
        // and acknowledges shutdown through markServiceStopped().
        if (!stopped) {
            markServiceStopped(context)
        }
    }

    fun markServiceStopped(context: Context) {
        setProtectedBrowserPackages(context, emptySet())
        setState(context, stateOff)
    }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean {
        @Suppress("DEPRECATION")
        return runCatching {
            context.packageManager.getApplicationInfo(packageName, 0)
        }.isSuccess
    }
}
