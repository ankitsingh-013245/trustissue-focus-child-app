package com.trustissue.child

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import java.util.Calendar

/**
 * Reads Android's package-level usage totals for the current local day.
 * Results are cached briefly because app-limit protection checks while the
 * screen is interactive. No screen content or browsing data is inspected.
 */
object UsageStatsReader {
    private const val cacheMs = 2_500L
    private var cachedAtMs = 0L
    private var cachedDayStartMs = 0L
    private var cachedUsage: Map<String, Long> = emptyMap()

    @Synchronized
    fun todayUsageByPackage(context: Context, force: Boolean = false): Map<String, Long> {
        val now = System.currentTimeMillis()
        val dayStart = startOfToday(now)
        if (!force && cachedDayStartMs == dayStart && now - cachedAtMs <= cacheMs) {
            return cachedUsage
        }
        if (!ProtectionAccess.hasUsageAccess(context)) return emptyMap()

        val manager =
            context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val stats = runCatching {
            manager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                dayStart,
                now
            )
        }.getOrDefault(emptyList())
        val totals = linkedMapOf<String, Long>()
        for (item in stats) {
            val packageName = item.packageName.orEmpty().trim()
            val foregroundMs = item.totalTimeInForeground.coerceAtLeast(0L)
            if (packageName.isNotEmpty() && foregroundMs > 0L) {
                totals[packageName] = (totals[packageName] ?: 0L) + foregroundMs
            }
        }
        cachedAtMs = now
        cachedDayStartMs = dayStart
        cachedUsage = totals
        return totals
    }

    fun usageTodayMs(context: Context, packageName: String): Long {
        if (packageName.isBlank()) return 0L
        return todayUsageByPackage(context)[packageName].orZero()
    }

    fun summaryMap(context: Context): Map<String, Any> {
        val usage = todayUsageByPackage(context, force = true)
        val launchablePackages = runCatching {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            context.packageManager.queryIntentActivities(intent, 0)
                .mapNotNull { it.activityInfo?.packageName?.trim() }
                .filter(String::isNotEmpty)
                .toSet()
        }.getOrDefault(emptySet())
        val visibleUsage = usage.filterKeys { packageName ->
            packageName != context.packageName && launchablePackages.contains(packageName)
        }
        return mapOf(
            "totalMs" to visibleUsage.values.sum(),
            "byPackage" to visibleUsage
        )
    }

    @Synchronized
    fun invalidate() {
        cachedAtMs = 0L
        cachedDayStartMs = 0L
        cachedUsage = emptyMap()
    }

    private fun startOfToday(now: Long): Long = Calendar.getInstance().run {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        timeInMillis
    }

    private fun Long?.orZero(): Long = this ?: 0L
}
