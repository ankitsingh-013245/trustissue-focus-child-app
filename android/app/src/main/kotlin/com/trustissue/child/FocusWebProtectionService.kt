package com.trustissue.child

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Base64
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight split-tunnel DNS protection for global and Focus browser rules.
 *
 * Only browser packages allowed by the active Focus policy join the VPN. It
 * captures their system DNS packets and direct requests to common public
 * resolver endpoints. Regular website HTTPS traffic stays on Android's normal
 * path, so the service never decrypts, stores, or scans page content. Encrypted
 * resolver traffic reaching a guarded resolver endpoint is failed closed so
 * the browser falls back to the protected system resolver.
 */
class FocusWebProtectionService : VpnService() {
    private data class CacheEntry(
        val response: ByteArray,
        val expiresAtElapsedMs: Long,
        val staleUntilElapsedMs: Long
    )

    private data class CacheLookup(
        val response: ByteArray
    )

    private val cache = object : LinkedHashMap<String, CacheEntry>(
        cacheLimit,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, CacheEntry>?
        ): Boolean = size > cacheLimit
    }
    private val cacheLock = Any()
    private val outputLock = Any()
    private val stateHandler = Handler(Looper.getMainLooper())
    private val diagnosticInstance =
        SystemClock.elapsedRealtime().toString(36)
    private val serviceCreatedAtElapsedMs = SystemClock.elapsedRealtime()
    private val packetCount = AtomicLong()
    private val dnsRequestCount = AtomicLong()
    private val droppedPacketCount = AtomicLong()
    private val cacheHitCount = AtomicLong()
    private val localBlockCount = AtomicLong()
    private val staleCacheHitCount = AtomicLong()
    private val upstreamSuccessCount = AtomicLong()
    private val upstreamFailureCount = AtomicLong()
    private val serverFailureCount = AtomicLong()
    private val queueRejectedCount = AtomicLong()
    private val tunWriteFailureCount = AtomicLong()
    private val lastTrafficSummaryAtElapsedMs = AtomicLong()
    private var dnsExecutor = createDnsExecutor()
    private val dohResolver = DnsOverHttpsResolver(
        endpoints = familyDohEndpoints,
        maximumResponseBytes = maxDnsMessageBytes
    )

    @Volatile
    private var running = false
    private var vpnInterface: ParcelFileDescriptor? = null
    private var packetThread: Thread? = null
    private var output: FileOutputStream? = null
    private var blockedRules: Map<String, String> = emptyMap()
    @Volatile
    private var upstreamFailureSinceElapsedMs = 0L
    @Volatile
    private var underlyingNetwork: Network? = null
    @Volatile
    private var lastResolverBypassLogAtMs = 0L
    @Volatile
    private var interfaceRefreshInProgress = false
    private var networkCallbackRegistered = false
    private var packageReceiverRegistered = false
    private val changedPackages = linkedSetOf<String>()
    private var interfaceRefreshRetryAttempt = 0
    private var interfaceRefreshRetryReason = ""
    private val degradeAfterGrace = Runnable {
        val failureSince = upstreamFailureSinceElapsedMs
        if (
            running &&
            failureSince > 0L &&
            SystemClock.elapsedRealtime() - failureSince >= reconnectGraceMs
        ) {
            setServiceState(WebProtectionConfig.stateDegraded)
        }
    }
    private val healthProbeQuery =
        requireNotNull(DnsPacketCodec.buildQuery("example.com"))
    private val healthProbe = Runnable {
        if (!running) return@Runnable
        try {
            dnsExecutor.execute {
                val healthy = queryFamilyResolver(
                    healthProbeQuery,
                    purpose = "health_probe"
                ) != null
                if (healthy) {
                    markUpstreamHealthy()
                } else {
                    beginReconnectWindow()
                    scheduleHealthProbe(healthProbeRetryMs)
                }
            }
        } catch (_: RejectedExecutionException) {
            scheduleHealthProbe(healthProbeRetryMs)
        }
    }
    private val browserSetRefresh = Runnable {
        refreshBrowserSetAfterPackageChange()
    }
    private val interfaceRefreshRetry = Runnable {
        retryInterfaceRefresh()
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (!running) return
            if (
                !VpnConflictDetector.isUsableUnderlyingNetwork(
                    this@FocusWebProtectionService,
                    network
                )
            ) {
                diagnostic(
                    "NETWORK_AVAILABLE_IGNORED network=${networkToken(network)} " +
                        "reason=unusable_underlying"
                )
                return
            }
            diagnostic(
                "NETWORK_AVAILABLE network=${networkToken(network)} " +
                    "previous=${networkToken(underlyingNetwork)}"
            )
            switchUnderlyingNetwork(network)
            beginReconnectWindow(resetGrace = true)
            scheduleHealthProbe(0L)
        }

        override fun onLost(network: Network) {
            if (!running || network != underlyingNetwork) return
            diagnostic("NETWORK_LOST network=${networkToken(network)}")
            switchUnderlyingNetwork(
                VpnConflictDetector.preferredUnderlyingNetwork(
                    this@FocusWebProtectionService
                )
            )
            beginReconnectWindow(resetGrace = true)
            scheduleHealthProbe(healthProbeRetryMs)
        }
    }
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            runCatching {
                intent?.data?.schemeSpecificPart
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let(changedPackages::add)
                WebProtectionConfig.invalidateBrowserCache()
                if (!running) return@runCatching
                stateHandler.removeCallbacks(browserSetRefresh)
                stateHandler.postDelayed(
                    browserSetRefresh,
                    WebInterfaceRefreshRetryPolicy.packageChangeDebounceMs
                )
            }.onFailure {
                TrustIssueDebugLog.append(
                    this@FocusWebProtectionService,
                    "FocusWebProtection",
                    "E",
                    "PACKAGE_RECEIVER_FAILED error=${it.javaClass.simpleName}"
                )
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        runCatching { StartupStateSanitizer.sanitize(this) }
        val alwaysOn =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isAlwaysOn
        val lockdown =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isLockdownEnabled
        diagnostic(
            "SERVICE_CREATED sdk=${Build.VERSION.SDK_INT} " +
                "alwaysOn=$alwaysOn lockdown=$lockdown"
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return runCatching {
            diagnostic(
                "START_COMMAND action=${intent?.action?.substringAfterLast('.') ?: "null"} " +
                    "flags=$flags startId=$startId running=$running " +
                    "state=${WebProtectionConfig.storedState(this)}"
            )
            if (intent?.action == actionStop) {
                shutdown(stateOff = true, requestStop = false)
                stopSelfResult(startId)
                return@runCatching START_NOT_STICKY
            }
            StartupStateSanitizer.sanitize(this)
            if (!WebProtectionConfig.shouldRun(this)) {
                shutdown(stateOff = true)
                START_NOT_STICKY
            } else {
                if (!running) startProtection()
                if (running && intent?.action == actionRefreshRules) {
                    val refreshedRules =
                        WebProtectionConfig.blockedDomainRules(this)
                    val rulesChanged = refreshedRules != blockedRules
                    val runtimeBrowsers =
                        WebProtectionConfig.runtimeBrowserPackages(this)
                    val browserSetChanged =
                        runtimeBrowsers != WebProtectionConfig.protectedBrowserPackages(this)
                    blockedRules = refreshedRules
                    synchronized(cacheLock) { cache.clear() }
                    val interfaceRefreshed =
                        (!rulesChanged && !browserSetChanged) ||
                            reestablishInterfaceForRuleChange()
                    if ((rulesChanged || browserSetChanged) && !interfaceRefreshed) {
                        handleInterfaceRefreshFailure("rule_interface_refresh_failed")
                    } else if ((rulesChanged || browserSetChanged) && interfaceRefreshed) {
                        completeInterfaceRefreshRecovery(
                            reason = "rules_changed",
                            browserCount = runtimeBrowsers.size
                        )
                    }
                    TrustIssueDebugLog.append(
                        this,
                        "FocusWebProtection",
                        "I",
                        "DNS_RULES_REFRESHED changed=$rulesChanged " +
                            "browserSetChanged=$browserSetChanged " +
                            "interfaceRefreshed=$interfaceRefreshed " +
                            WebProtectionConfig.ruleCounts(this).logFields()
                    )
                }
                if (running) START_STICKY else START_NOT_STICKY
            }
        }.getOrElse {
            TrustIssueDebugLog.append(
                this,
                "FocusWebProtection",
                "E",
                "SERVICE_START_FAILED error=${it.javaClass.simpleName}"
            )
            runCatching {
                WebProtectionConfig.setState(
                    this,
                    WebProtectionConfig.stateDegraded,
                    it.javaClass.simpleName
                )
            }
            runCatching { stopSelf() }
            START_NOT_STICKY
        }
    }

    override fun onRevoke() {
        diagnostic(
            "SERVICE_REVOKED running=$running " +
                "state=${WebProtectionConfig.storedState(this)}"
        )
        WebProtectionConfig.setState(this, WebProtectionConfig.stateDegraded, "vpn_revoked")
        shutdown(stateOff = false)
        super.onRevoke()
    }

    override fun onDestroy() {
        diagnostic(
            "SERVICE_DESTROY_REQUEST running=$running shouldRun=" +
                "${WebProtectionConfig.shouldRun(this)}"
        )
        runCatching {
            val shouldFailClosed = WebProtectionConfig.shouldRun(this)
            shutdown(stateOff = !shouldFailClosed, requestStop = false)
            if (shouldFailClosed) {
                WebProtectionConfig.setState(
                    this,
                    WebProtectionConfig.stateDegraded,
                    "service_destroyed"
                )
            }
        }
        super.onDestroy()
    }

    private fun startProtection() {
        val startedAt = SystemClock.elapsedRealtime()
        ensureDnsExecutor()
        WebProtectionConfig.setState(this, WebProtectionConfig.stateStarting)
        createNotificationChannel()
        val allowedBrowsers = WebProtectionConfig.runtimeBrowserPackages(this)
        diagnostic(
            "START_PHASE phase=browser_discovery elapsedMs=" +
                "${SystemClock.elapsedRealtime() - startedAt} " +
                "browserCount=${allowedBrowsers.size} " +
                "global=${WebProtectionConfig.isGlobalProtectionEnabled(this)} " +
                "focus=${TrackerConfig.isStudyModeEnabled(this)}"
        )
        if (allowedBrowsers.isEmpty()) {
            diagnostic("START_ABORTED reason=no_runtime_browsers")
            shutdown(stateOff = true)
            return
        }
        val initialNetwork =
            VpnConflictDetector.preferredUnderlyingNetwork(this)
        underlyingNetwork = initialNetwork
        dohResolver.updateNetwork(initialNetwork)
        val establishStartedAt = SystemClock.elapsedRealtime()
        diagnostic(
            "START_PHASE phase=establish_begin elapsedMs=" +
                "${establishStartedAt - startedAt} " +
                "network=${networkToken(initialNetwork)}"
        )
        val descriptor = runCatching {
            establishInterface(allowedBrowsers, initialNetwork)
        }
            .onFailure {
                WebProtectionConfig.setState(
                    this,
                    WebProtectionConfig.stateDegraded,
                    it.javaClass.simpleName
                )
            }
            .getOrNull()
        diagnostic(
            "START_PHASE phase=establish_end success=${descriptor != null} " +
                "establishMs=${SystemClock.elapsedRealtime() - establishStartedAt} " +
                "totalMs=${SystemClock.elapsedRealtime() - startedAt}"
        )
        if (descriptor == null) {
            WebProtectionConfig.setState(
                this,
                WebProtectionConfig.stateDegraded,
                "vpn_establish_failed"
            )
            stopSelf()
            return
        }

        vpnInterface = descriptor
        try {
            WebProtectionConfig.setProtectedBrowserPackages(this, allowedBrowsers)
            output = FileOutputStream(descriptor.fileDescriptor)
            running = true
            runningInProcess = true
            promoteToForeground()
            diagnostic(
                "START_PHASE phase=foreground_promoted " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
            )
            blockedRules = WebProtectionConfig.blockedDomainRules(this)
            WebProtectionConfig.logRuleConfiguration(this, "service_start")
            TrustIssueDebugLog.append(
                this,
                "FocusWebProtection",
                "I",
                "DNS_GUARD_READY ipv4Resolvers=${guardedResolverIpv4.size} " +
                    "ipv6Resolvers=${guardedResolverIpv6.size}"
            )
            registerNetworkCallback()
            registerPackageReceiver()
            diagnostic(
                "SERVICE_READY elapsedMs=${SystemClock.elapsedRealtime() - startedAt} " +
                    "rules=${blockedRules.size} browsers=${allowedBrowsers.size} " +
                    "network=${networkToken(underlyingNetwork)}"
            )
        } catch (error: Exception) {
            diagnostic(
                "START_ABORTED reason=post_establish_failure " +
                    "error=${error.javaClass.simpleName} " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
                level = "E"
            )
            WebProtectionConfig.setState(
                this,
                WebProtectionConfig.stateDegraded,
                error.javaClass.simpleName
            )
            shutdown(stateOff = false)
            return
        }
        if (underlyingNetwork != null) {
            scheduleHealthProbe(0L)
        } else {
            beginReconnectWindow()
            scheduleHealthProbe(healthProbeRetryMs)
        }
        startPacketThread(descriptor)
    }

    private fun establishInterface(
        allowedBrowsers: Set<String>,
        initialNetwork: Network?
    ): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("TrustIssue Focus Web Protection")
            .setMtu(1500)
            .addAddress(vpnClientAddress, 32)
            .addAddress(vpnClientAddressV6, 128)
            .addDnsServer(vpnDnsAddress)
            .addRoute(vpnDnsAddress, 32)
            .setBlocking(true)

        var ipv4RoutesAdded = 0
        var ipv4RouteFailures = 0
        guardedResolverIpv4.forEach { address ->
            runCatching { builder.addRoute(address, 32) }
                .onSuccess { ipv4RoutesAdded += 1 }
                .onFailure { ipv4RouteFailures += 1 }
        }
        var ipv6RoutesAdded = 0
        var ipv6RouteFailures = 0
        guardedResolverIpv6.forEach { address ->
            runCatching { builder.addRoute(address, 128) }
                .onSuccess { ipv6RoutesAdded += 1 }
                .onFailure { ipv6RouteFailures += 1 }
        }

        if (initialNetwork != null) {
            builder.setUnderlyingNetworks(arrayOf(initialNetwork))
        }

        var allowedCount = 0
        for (browserPackage in allowedBrowsers) {
            runCatching {
                builder.addAllowedApplication(browserPackage)
                allowedCount += 1
            }
        }
        diagnostic(
            "INTERFACE_CONFIG browsersRequested=${allowedBrowsers.size} " +
                "browsersAdded=$allowedCount network=${networkToken(initialNetwork)} " +
                "dnsRoute=$vpnDnsAddress ipv4Routes=$ipv4RoutesAdded " +
                "ipv4RouteFailures=$ipv4RouteFailures ipv6Routes=$ipv6RoutesAdded " +
                "ipv6RouteFailures=$ipv6RouteFailures mtu=1500"
        )
        if (allowedCount == 0) {
            diagnostic("INTERFACE_ESTABLISH_SKIPPED reason=no_allowed_applications", "E")
            return null
        }
        val descriptor = builder.establish()
        diagnostic("INTERFACE_ESTABLISHED success=${descriptor != null}")
        return descriptor
    }

    private fun reestablishInterfaceForRuleChange(): Boolean {
        val allowedBrowsers = WebProtectionConfig.runtimeBrowserPackages(this)
        if (allowedBrowsers.isEmpty()) return false
        val network = underlyingNetwork
            ?: VpnConflictDetector.preferredUnderlyingNetwork(this)
        interfaceRefreshInProgress = true
        return try {
            val replacement = runCatching {
                establishInterface(allowedBrowsers, network)
            }.getOrNull() ?: return false
            val replacementOutput = runCatching {
                FileOutputStream(replacement.fileDescriptor)
            }.getOrElse {
                runCatching { replacement.close() }
                return false
            }

            val previousInterface: ParcelFileDescriptor?
            val previousOutput: FileOutputStream?
            synchronized(outputLock) {
                previousInterface = vpnInterface
                previousOutput = output
                vpnInterface = replacement
                output = replacementOutput
            }
            WebProtectionConfig.setProtectedBrowserPackages(this, allowedBrowsers)
            startPacketThread(replacement)
            runCatching { previousOutput?.close() }
            runCatching { previousInterface?.close() }
            beginReconnectWindow(resetGrace = true)
            scheduleHealthProbe(0L)
            true
        } finally {
            interfaceRefreshInProgress = false
        }
    }

    private fun refreshBrowserSetAfterPackageChange() {
        if (!running) {
            changedPackages.clear()
            return
        }
        WebProtectionConfig.invalidateBrowserCache()
        val protected = WebProtectionConfig.protectedBrowserPackages(this)
        val allowed = WebProtectionConfig.runtimeBrowserPackages(this)
        val packages = changedPackages.toSet()
        changedPackages.clear()
        val affected =
            protected != allowed ||
                packages.any { protected.contains(it) || allowed.contains(it) }
        if (!affected) return

        blockedRules = WebProtectionConfig.blockedDomainRules(this)
        synchronized(cacheLock) { cache.clear() }
        if (allowed.isNotEmpty() && reestablishInterfaceForRuleChange()) {
            completeInterfaceRefreshRecovery(
                reason = "browser_set_changed",
                browserCount = allowed.size
            )
        } else {
            handleInterfaceRefreshFailure("browser_set_refresh_failed")
        }
    }

    private fun handleInterfaceRefreshFailure(reason: String) {
        WebProtectionConfig.setState(
            this,
            WebProtectionConfig.stateDegraded,
            reason
        )
        interfaceRefreshRetryReason = reason
        scheduleInterfaceRefreshRetry()
    }

    private fun scheduleInterfaceRefreshRetry() {
        if (!running) return
        interfaceRefreshRetryAttempt += 1
        val delay = WebInterfaceRefreshRetryPolicy.retryDelayMs(
            interfaceRefreshRetryAttempt
        )
        stateHandler.removeCallbacks(interfaceRefreshRetry)
        stateHandler.postDelayed(interfaceRefreshRetry, delay)
    }

    private fun retryInterfaceRefresh() {
        if (!running) return
        WebProtectionConfig.invalidateBrowserCache()
        val allowed = WebProtectionConfig.runtimeBrowserPackages(this)
        if (allowed.isEmpty()) {
            val protectionConfigured =
                WebProtectionConfig.isGlobalProtectionEnabled(this) ||
                    TrackerConfig.isStudyModeEnabled(this)
            if (
                protectionConfigured &&
                WebInterfaceRefreshRetryPolicy.shouldRetryEmptyBrowserSet(
                    interfaceRefreshRetryAttempt
                )
            ) {
                scheduleInterfaceRefreshRetry()
            } else {
                TrustIssueDebugLog.append(
                    this,
                    "FocusWebProtection",
                    "I",
                    "BROWSER_SET_EMPTY retryAttempt=$interfaceRefreshRetryAttempt"
                )
                shutdown(stateOff = true)
            }
            return
        }
        if (!WebProtectionConfig.shouldRun(this)) {
            shutdown(stateOff = true)
            return
        }

        blockedRules = WebProtectionConfig.blockedDomainRules(this)
        synchronized(cacheLock) { cache.clear() }
        if (reestablishInterfaceForRuleChange()) {
            completeInterfaceRefreshRecovery(
                reason = interfaceRefreshRetryReason.ifBlank {
                    "interface_refresh_retry"
                },
                browserCount = allowed.size
            )
        } else {
            WebProtectionConfig.setState(
                this,
                WebProtectionConfig.stateDegraded,
                interfaceRefreshRetryReason.ifBlank {
                    "interface_refresh_retry_failed"
                }
            )
            scheduleInterfaceRefreshRetry()
        }
    }

    private fun completeInterfaceRefreshRecovery(
        reason: String,
        browserCount: Int
    ) {
        stateHandler.removeCallbacks(interfaceRefreshRetry)
        interfaceRefreshRetryAttempt = 0
        interfaceRefreshRetryReason = ""
        TrustIssueDebugLog.append(
            this,
            "FocusWebProtection",
            "I",
            "INTERFACE_REFRESHED reason=$reason count=$browserCount"
        )
    }

    private fun startPacketThread(descriptor: ParcelFileDescriptor) {
        packetThread = Thread(
            { packetLoop(descriptor) },
            "TrustIssueWebDns"
        ).apply {
            priority = Thread.NORM_PRIORITY
            start()
        }
    }

    private fun promoteToForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            )
        } else {
            startForeground(notificationId, notification)
        }
    }

    private fun packetLoop(descriptor: ParcelFileDescriptor) {
        val input = FileInputStream(descriptor.fileDescriptor)
        val buffer = ByteArray(32767)
        try {
            while (running) {
                val length = input.read(buffer)
                if (length <= 0) continue
                packetCount.incrementAndGet()
                val packet = buffer.copyOf(length)
                val request = DnsPacketCodec.parseRequest(packet, length)
                if (request == null) {
                    droppedPacketCount.incrementAndGet()
                    logGuardedResolverBypass(packet)
                    maybeLogTrafficSummary("packet_drop")
                    continue
                }
                dnsRequestCount.incrementAndGet()
                try {
                    dnsExecutor.execute { handleDnsRequest(request) }
                } catch (_: RejectedExecutionException) {
                    queueRejectedCount.incrementAndGet()
                    TrustIssueDebugLog.append(
                        this,
                        "FocusWebProtection",
                        "W",
                        "DNS_QUEUE_SATURATED"
                    )
                    beginReconnectWindow()
                    writeDnsResponse(
                        request,
                        DnsPacketCodec.buildServerFailure(request.dnsPayload)
                    )
                }
                maybeLogTrafficSummary("packet_loop")
            }
        } catch (error: Exception) {
            if (
                running &&
                !interfaceRefreshInProgress &&
                vpnInterface === descriptor
            ) {
                diagnostic(
                    "PACKET_LOOP_FAILED error=${error.javaClass.simpleName} " +
                        "descriptorCurrent=${vpnInterface === descriptor}",
                    "E"
                )
                WebProtectionConfig.setState(
                    this,
                    WebProtectionConfig.stateDegraded,
                    error.javaClass.simpleName
                )
                stateHandler.post {
                    if (
                        running &&
                        !interfaceRefreshInProgress &&
                        vpnInterface === descriptor
                    ) {
                        handleInterfaceRefreshFailure("packet_loop_failed")
                    }
                }
            }
        } finally {
            diagnostic(
                "PACKET_LOOP_ENDED running=$running " +
                    "descriptorCurrent=${vpnInterface === descriptor}"
            )
            runCatching { input.close() }
        }
    }

    private fun logGuardedResolverBypass(packet: ByteArray) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastResolverBypassLogAtMs < resolverBypassLogIntervalMs) return
        lastResolverBypassLogAtMs = now
        val version = packet.firstOrNull()?.toInt()?.ushr(4)?.and(0x0F) ?: -1
        val protocol = when (version) {
            4 -> packet.getOrNull(9)?.toInt()?.and(0xFF) ?: -1
            6 -> packet.getOrNull(6)?.toInt()?.and(0xFF) ?: -1
            else -> -1
        }
        TrustIssueDebugLog.append(
            this,
            "FocusWebProtection",
            "I",
            "ENCRYPTED_OR_DIRECT_DNS_BLOCKED instance=$diagnosticInstance " +
                "ipVersion=$version protocol=$protocol bytes=${packet.size} " +
                "droppedTotal=${droppedPacketCount.get()}"
        )
    }

    private fun handleDnsRequest(request: DnsPacketCodec.Request) {
        val rawDomain = DnsPacketCodec.queryName(request.dnsPayload)
        val domain = rawDomain?.let(WebProtectionConfig::normalizeDomain)
        if (domain == null) {
            serverFailureCount.incrementAndGet()
            diagnostic(
                "DNS_REQUEST_REJECTED reason=invalid_query_name " +
                    "qtype=${DnsPacketCodec.queryType(request.dnsPayload) ?: -1} " +
                    "payloadBytes=${request.dnsPayload.size}",
                "W"
            )
            writeDnsResponse(
                request,
                DnsPacketCodec.buildServerFailure(request.dnsPayload)
            )
            return
        }

        val category = WebProtectionConfig.matchBlockedDomain(domain, blockedRules)
        if (category != null) {
            localBlockCount.incrementAndGet()
            notifyDomainBlocked(domain, category)
            // REFUSED expresses a local policy decision without teaching the
            // browser that the domain does not exist. Unlike NXDOMAIN, it is
            // not meant to survive after the user turns protection off.
            writeDnsResponse(request, DnsPacketCodec.buildRefused(request.dnsPayload))
            return
        }

        val cacheKey = cacheKeyForQuery(request.dnsPayload)
        cachedResponse(cacheKey, request.dnsPayload, allowStale = false)?.let {
            cacheHitCount.incrementAndGet()
            writeDnsResponse(request, it.response)
            maybeLogTrafficSummary("cache_hit")
            return
        }

        val upstreamResponse = queryFamilyResolver(
            request.dnsPayload,
            purpose = "browser_${domainToken(domain)}"
        )
        if (upstreamResponse != null) {
            if (DnsPacketCodec.hasZeroAddressAnswer(upstreamResponse)) {
                // Cloudflare Families represents a classified adult/malware
                // domain with 0.0.0.0 or ::. Turn that resolver-level signal
                // into the same deterministic local gate as explicit rules,
                // without treating an ordinary NXDOMAIN as blocked content.
                markUpstreamHealthy()
                notifyDomainBlocked(domain, "family_filter")
                writeDnsResponse(
                    request,
                    DnsPacketCodec.buildRefused(request.dnsPayload)
                )
                return
            }
            cacheResponse(cacheKey, upstreamResponse)
            markUpstreamHealthy()
            writeDnsResponse(request, upstreamResponse)
        } else {
            beginReconnectWindow()
            scheduleHealthProbe(healthProbeRetryMs)
            val stale = cachedResponse(
                cacheKey,
                request.dnsPayload,
                allowStale = true
            )
            if (stale != null) {
                staleCacheHitCount.incrementAndGet()
                TrustIssueDebugLog.append(
                    this,
                    "FocusWebProtection",
                    "W",
                    "DNS_STALE_CACHE_USED domain=${domainToken(domain)} " +
                        "qtype=${DnsPacketCodec.queryType(request.dnsPayload) ?: -1}"
                )
                writeDnsResponse(request, stale.response)
            } else {
                serverFailureCount.incrementAndGet()
                val failureSince = upstreamFailureSinceElapsedMs
                val failureAgeMs = if (failureSince > 0L) {
                    SystemClock.elapsedRealtime() - failureSince
                } else {
                    0L
                }
                diagnostic(
                    "DNS_SERVFAIL_NO_FALLBACK token=${domainToken(domain)} " +
                        "qtype=${DnsPacketCodec.queryType(request.dnsPayload) ?: -1} " +
                        "failureAgeMs=${failureAgeMs.coerceAtLeast(0L)} " +
                        "cacheEntries=${synchronized(cacheLock) { cache.size }}",
                    "W"
                )
                writeDnsResponse(
                    request,
                    DnsPacketCodec.buildServerFailure(request.dnsPayload)
                )
            }
        }
        maybeLogTrafficSummary("dns_complete")
    }

    private fun queryFamilyResolver(
        query: ByteArray,
        purpose: String
    ): ByteArray? {
        val result = dohResolver.query(query)
        val response = result.response
            ?.takeIf { DnsPacketCodec.isResponseFor(query, it) }
        if (response == null) {
            upstreamFailureCount.incrementAndGet()
            val mismatch = result.response != null
            TrustIssueDebugLog.append(
                this,
                "FocusWebProtection",
                "W",
                "DNS_UPSTREAM_FAILED purpose=$purpose " +
                    "qtype=${DnsPacketCodec.queryType(query) ?: -1} " +
                    "attempts=${result.attempts} elapsedMs=${result.elapsedMs} " +
                    "resolver=${result.resolverIndex} " +
                    "http=${result.httpStatus ?: -1} " +
                    "error=${if (mismatch) "response_mismatch" else result.error} " +
                    "trace=${result.attemptTrace.ifBlank { "none" }}"
            )
            return null
        }
        upstreamSuccessCount.incrementAndGet()
        if (result.attempts > 1) {
            TrustIssueDebugLog.append(
                this,
                "FocusWebProtection",
                "I",
                    "DNS_UPSTREAM_RECOVERED purpose=$purpose " +
                    "attempts=${result.attempts} elapsedMs=${result.elapsedMs} " +
                    "resolver=${result.resolverIndex} " +
                    "trace=${result.attemptTrace.ifBlank { "none" }}"
            )
        }
        return response
    }

    private fun cacheKeyForQuery(query: ByteArray): String {
        if (query.size <= 2) return ""
        return Base64.encodeToString(
            query,
            2,
            query.size - 2,
            Base64.NO_WRAP
        )
    }

    private fun cachedResponse(
        key: String,
        query: ByteArray,
        allowStale: Boolean
    ): CacheLookup? {
        val now = SystemClock.elapsedRealtime()
        val entry = synchronized(cacheLock) {
            val value = cache[key]
            if (value != null && value.staleUntilElapsedMs <= now) {
                cache.remove(key)
                null
            } else {
                value
            }
        } ?: return null
        val stale = entry.expiresAtElapsedMs <= now
        if (stale && !allowStale) return null
        return CacheLookup(
            response = DnsPacketCodec.withTransactionId(entry.response, query)
        )
    }

    private fun cacheResponse(key: String, response: ByteArray) {
        val ttlSeconds = (
            DnsPacketCodec.minimumAnswerTtlSeconds(response) ?: defaultCacheTtlSeconds
            ).coerceIn(minimumCacheTtlSeconds, maximumCacheTtlSeconds)
        val now = SystemClock.elapsedRealtime()
        val expiresAt = now + TimeUnit.SECONDS.toMillis(ttlSeconds)
        synchronized(cacheLock) {
            cache[key] = CacheEntry(
                response = response.copyOf(),
                expiresAtElapsedMs = expiresAt,
                staleUntilElapsedMs = expiresAt + staleCacheDurationMs
            )
        }
    }

    private fun writeDnsResponse(
        request: DnsPacketCodec.Request,
        dnsResponse: ByteArray
    ) {
        if (!running) return
        val packet = DnsPacketCodec.wrapResponse(request, dnsResponse)
        synchronized(outputLock) {
            runCatching {
                output?.write(packet)
                output?.flush()
            }.onFailure {
                if (running) {
                    tunWriteFailureCount.incrementAndGet()
                    diagnostic(
                        "TUN_WRITE_FAILED error=${it.javaClass.simpleName} " +
                            "packetBytes=${packet.size}",
                        "E"
                    )
                    WebProtectionConfig.setState(
                        this,
                        WebProtectionConfig.stateDegraded,
                        "tun_write_failed"
                    )
                    shutdown(stateOff = false)
                }
            }
        }
    }

    private fun notifyDomainBlocked(domain: String, category: String) {
        TrustIssueDebugLog.append(
            this,
            "FocusWebProtection",
            "I",
            "DNS_DOMAIN_BLOCKED token=${domainToken(domain)} " +
                "category=${category.take(40)}"
        )
        sendBroadcast(
            Intent(WebProtectionConfig.actionDomainBlocked)
                .setPackage(packageName)
                .putExtra(WebProtectionConfig.extraDomain, domain.take(253))
                .putExtra(WebProtectionConfig.extraCategory, category.take(40))
        )
        if (
            WebProtectionConfig.isGlobalProtectionEnabled(this) &&
            !TrackerConfig.isStudyModeEnabled(this) &&
            !ProtectionAccess.isAccessibilityOperational(this)
        ) {
            stateHandler.post {
                runCatching {
                    startActivity(
                        Intent(this, WebBlockActivity::class.java).apply {
                            addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                            )
                            putExtra(WebBlockActivity.EXTRA_DOMAIN, domain.take(253))
                            putExtra(WebBlockActivity.EXTRA_CATEGORY, category.take(40))
                        }
                    )
                }.onFailure {
                    TrustIssueDebugLog.append(
                        this,
                        "FocusWebProtection",
                        "W",
                        "GLOBAL_WEB_GATE_FAILED error=${it.javaClass.simpleName}"
                    )
                }
            }
        }
    }

    private fun setServiceState(state: String) {
        if (
            state != WebProtectionConfig.stateOff &&
            state != WebProtectionConfig.stateStopping &&
            !WebProtectionConfig.shouldRun(this)
        ) {
            return
        }
        if (WebProtectionConfig.storedState(this) != state) {
            WebProtectionConfig.setState(this, state)
        }
    }

    private fun markUpstreamHealthy() {
        stateHandler.post {
            if (!running || !WebProtectionConfig.shouldRun(this)) return@post
            val previous = WebProtectionConfig.storedState(this)
            upstreamFailureSinceElapsedMs = 0L
            stateHandler.removeCallbacks(degradeAfterGrace)
            stateHandler.removeCallbacks(healthProbe)
            setServiceState(WebProtectionConfig.stateActive)
            if (
                previous == WebProtectionConfig.stateReconnecting ||
                previous == WebProtectionConfig.stateDegraded
            ) {
                TrustIssueDebugLog.append(
                    this,
                    "FocusWebProtection",
                    "I",
                    "DNS_HEALTH_RECOVERED previous=$previous"
                )
            }
        }
    }

    private fun beginReconnectWindow(resetGrace: Boolean = false) {
        if (
            running &&
            WebProtectionConfig.shouldRun(this) &&
            WebProtectionConfig.storedState(this) != WebProtectionConfig.stateDegraded
        ) {
            // Publish fail-closed health before a SERVFAIL can reach the
            // browser, so Focus shows its own deterministic protection screen.
            setServiceState(WebProtectionConfig.stateReconnecting)
        }
        stateHandler.post {
            if (!running || !WebProtectionConfig.shouldRun(this)) return@post
            val now = SystemClock.elapsedRealtime()
            if (resetGrace || upstreamFailureSinceElapsedMs <= 0L) {
                upstreamFailureSinceElapsedMs = now
            }
            val elapsed = now - upstreamFailureSinceElapsedMs
            if (elapsed >= reconnectGraceMs) {
                setServiceState(WebProtectionConfig.stateDegraded)
            } else {
                setServiceState(WebProtectionConfig.stateReconnecting)
                stateHandler.removeCallbacks(degradeAfterGrace)
                stateHandler.postDelayed(
                    degradeAfterGrace,
                    reconnectGraceMs - elapsed
                )
            }
        }
    }

    private fun scheduleHealthProbe(delayMs: Long) {
        stateHandler.post {
            if (!running || !WebProtectionConfig.shouldRun(this)) return@post
            stateHandler.removeCallbacks(healthProbe)
            stateHandler.postDelayed(healthProbe, delayMs.coerceAtLeast(0L))
        }
    }

    private fun switchUnderlyingNetwork(network: Network?) {
        if (network == underlyingNetwork) return
        val previousNetwork = underlyingNetwork
        underlyingNetwork = network
        dohResolver.updateNetwork(network)
        val bindingResult = runCatching {
            setUnderlyingNetworks(
                network?.let { arrayOf(it) } ?: emptyArray<Network>()
            )
        }
        diagnostic(
            "UNDERLYING_NETWORK_SWITCH previous=${networkToken(previousNetwork)} " +
                "current=${networkToken(network)} bindingSuccess=${bindingResult.isSuccess} " +
                "bindingError=${bindingResult.exceptionOrNull()?.javaClass?.simpleName ?: "none"}",
            if (bindingResult.isSuccess && network != null) "I" else "W"
        )
        TrustIssueDebugLog.append(
            this,
            "FocusWebProtection",
            if (network == null) "W" else "I",
            if (network == null) {
                "UNDERLYING_NETWORK_UNAVAILABLE"
            } else {
                "UNDERLYING_NETWORK_CHANGED"
            }
        )
    }

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        val manager = getSystemService(ConnectivityManager::class.java)
        runCatching {
            manager.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
        }.onFailure {
            diagnostic(
                "NETWORK_CALLBACK_REGISTER_FAILED error=${it.javaClass.simpleName}",
                "W"
            )
        }
    }

    private fun unregisterNetworkCallback() {
        if (!networkCallbackRegistered) return
        val manager = getSystemService(ConnectivityManager::class.java)
        runCatching { manager.unregisterNetworkCallback(networkCallback) }
            .onFailure {
                diagnostic(
                    "NETWORK_CALLBACK_UNREGISTER_FAILED error=${it.javaClass.simpleName}",
                    "W"
                )
            }
        networkCallbackRegistered = false
    }

    private fun registerPackageReceiver() {
        if (packageReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(packageReceiver, filter)
        }
        packageReceiverRegistered = true
    }

    private fun unregisterPackageReceiver() {
        if (!packageReceiverRegistered) return
        runCatching { unregisterReceiver(packageReceiver) }
        packageReceiverRegistered = false
    }

    private fun domainToken(domain: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(domain.toByteArray(Charsets.UTF_8))
        return digest.take(6).joinToString("") {
            "%02x".format(it.toInt() and 0xFF)
        }
    }

    private fun networkToken(network: Network?): String {
        return network?.toString()?.take(40)?.ifBlank { "unknown" } ?: "none"
    }

    private fun diagnostic(message: String, level: String = "I") {
        TrustIssueDebugLog.append(
            this,
            "VpnDiagnostics",
            level,
            "instance=$diagnosticInstance ${message.take(700)}"
        )
    }

    private fun maybeLogTrafficSummary(
        trigger: String,
        force: Boolean = false
    ) {
        val now = SystemClock.elapsedRealtime()
        while (true) {
            val previous = lastTrafficSummaryAtElapsedMs.get()
            if (!force && now - previous < trafficSummaryIntervalMs) return
            if (lastTrafficSummaryAtElapsedMs.compareAndSet(previous, now)) break
        }
        val cacheEntries = synchronized(cacheLock) { cache.size }
        diagnostic(
            "TRAFFIC_SUMMARY trigger=$trigger uptimeMs=" +
                "${now - serviceCreatedAtElapsedMs} running=$running " +
                "state=${WebProtectionConfig.storedState(this)} " +
                "packets=${packetCount.get()} dns=${dnsRequestCount.get()} " +
                "dropped=${droppedPacketCount.get()} cacheHits=${cacheHitCount.get()} " +
                "staleHits=${staleCacheHitCount.get()} localBlocks=${localBlockCount.get()} " +
                "upstreamOk=${upstreamSuccessCount.get()} " +
                "upstreamFail=${upstreamFailureCount.get()} " +
                "servfail=${serverFailureCount.get()} queueRejected=${queueRejectedCount.get()} " +
                "writeFail=${tunWriteFailureCount.get()} cacheEntries=$cacheEntries " +
                "executorActive=${dnsExecutor.activeCount} executorQueue=${dnsExecutor.queue.size} " +
                "network=${networkToken(underlyingNetwork)}"
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                notificationChannelId,
                "Website Protection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description =
                    "Shown while TrustIssue protects browser website access."
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, notificationChannelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Website Protection")
            .setContentText("Adult and blocked-app websites are filtered")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun shutdown(
        stateOff: Boolean,
        requestStop: Boolean = true
    ) {
        diagnostic(
            "SHUTDOWN_BEGIN stateOff=$stateOff requestStop=$requestStop " +
                "running=$running storedState=${WebProtectionConfig.storedState(this)}"
        )
        maybeLogTrafficSummary("shutdown", force = true)
        running = false
        runningInProcess = false
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        runCatching { output?.close() }
        output = null
        packetThread?.interrupt()
        packetThread = null
        unregisterNetworkCallback()
        unregisterPackageReceiver()
        upstreamFailureSinceElapsedMs = 0L
        underlyingNetwork = null
        stateHandler.removeCallbacks(degradeAfterGrace)
        stateHandler.removeCallbacks(healthProbe)
        stateHandler.removeCallbacks(browserSetRefresh)
        stateHandler.removeCallbacks(interfaceRefreshRetry)
        changedPackages.clear()
        interfaceRefreshRetryAttempt = 0
        interfaceRefreshRetryReason = ""
        dohResolver.close()
        dnsExecutor.shutdownNow()
        synchronized(cacheLock) { cache.clear() }
        blockedRules = emptyMap()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        if (stateOff) {
            WebProtectionConfig.markServiceStopped(this)
        }
        diagnostic(
            "SHUTDOWN_COMPLETE stateOff=$stateOff requestStop=$requestStop " +
                "storedState=${WebProtectionConfig.storedState(this)}"
        )
        if (requestStop) stopSelf()
    }

    private fun ensureDnsExecutor() {
        if (dnsExecutor.isShutdown || dnsExecutor.isTerminated) {
            dnsExecutor = createDnsExecutor()
        }
    }

    private fun createDnsExecutor(): ThreadPoolExecutor {
        return ThreadPoolExecutor(
            4,
            8,
            30L,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(128)
        )
    }

    companion object {
        const val actionStart = "com.trustissue.child.action.START_WEB_PROTECTION"
        const val actionStop = "com.trustissue.child.action.STOP_WEB_PROTECTION"
        const val actionRefreshRules =
            "com.trustissue.child.action.REFRESH_WEB_PROTECTION_RULES"
        @Volatile
        private var runningInProcess = false

        fun isRunningInProcess(): Boolean = runningInProcess

        private const val vpnClientAddress = "10.111.222.1"
        private const val vpnClientAddressV6 = "fd00:111:222::1"
        private const val vpnDnsAddress = "10.111.222.2"
        private val guardedResolverIpv4 = listOf(
            "1.1.1.1",
            "1.0.0.1",
            "8.8.8.8",
            "8.8.4.4",
            "9.9.9.9",
            "149.112.112.112",
            "94.140.14.14",
            "94.140.15.15",
            "208.67.222.222",
            "208.67.220.220",
            "76.76.2.0",
            "76.76.10.0"
        )
        private val guardedResolverIpv6 = listOf(
            "2606:4700:4700::1111",
            "2606:4700:4700::1001",
            "2001:4860:4860::8888",
            "2001:4860:4860::8844",
            "2620:fe::fe",
            "2620:fe::9"
        )
        private val familyDohEndpoints = listOf(
            "https://family.cloudflare-dns.com/dns-query",
            "https://doh.cleanbrowsing.org/doh/family-filter/"
        )
        private const val notificationChannelId = "focus_web_protection"
        private const val resolverBypassLogIntervalMs = 30_000L
        private const val trafficSummaryIntervalMs = 30_000L
        private const val notificationId = 84061
        private const val cacheLimit = 256
        private const val minimumCacheTtlSeconds = 30L
        private const val defaultCacheTtlSeconds = 60L
        private const val maximumCacheTtlSeconds = 10L * 60L
        private const val staleCacheDurationMs = 15L * 60L * 1_000L
        private const val reconnectGraceMs = 8_000L
        private const val healthProbeRetryMs = 3_000L
        // 1,472 bytes plus the IPv4/UDP headers fits the configured 1,500 MTU.
        private const val maxDnsMessageBytes = 1_472
    }
}

internal object WebInterfaceRefreshRetryPolicy {
    const val packageChangeDebounceMs = 1_500L
    private const val initialRetryMs = 1_000L
    private const val maximumRetryMs = 30_000L
    private const val emptyBrowserRetryLimit = 5

    fun retryDelayMs(attempt: Int): Long {
        val shift = (attempt - 1).coerceIn(0, 5)
        return (initialRetryMs shl shift).coerceAtMost(maximumRetryMs)
    }

    fun shouldRetryEmptyBrowserSet(attempt: Int): Boolean =
        attempt < emptyBrowserRetryLimit
}
