package com.trustissue.child

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Splits elapsed duration into local calendar-day buckets. */
internal object DailyDurationSplitter {
    fun fromStart(
        startedAtMs: Long,
        durationMs: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Map<String, Long> {
        if (startedAtMs < 0L || durationMs <= 0L) return emptyMap()
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            this.timeZone = timeZone
        }
        val chunks = linkedMapOf<String, Long>()
        var cursorMs = startedAtMs
        var remainingMs = durationMs
        while (remainingMs > 0L) {
            val nextDayMs = Calendar.getInstance(timeZone).run {
                timeInMillis = cursorMs
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                timeInMillis.coerceAtLeast(cursorMs + 1L)
            }
            val chunkMs = minOf(remainingMs, nextDayMs - cursorMs)
            val date = format.format(Date(cursorMs))
            chunks[date] = (chunks[date] ?: 0L) + chunkMs
            cursorMs += chunkMs
            remainingMs -= chunkMs
        }
        return chunks
    }

    fun endingAt(
        endedAtMs: Long,
        durationMs: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Map<String, Long> {
        if (durationMs <= 0L) return emptyMap()
        return fromStart(
            startedAtMs = (endedAtMs - durationMs).coerceAtLeast(0L),
            durationMs = durationMs,
            timeZone = timeZone
        )
    }
}
