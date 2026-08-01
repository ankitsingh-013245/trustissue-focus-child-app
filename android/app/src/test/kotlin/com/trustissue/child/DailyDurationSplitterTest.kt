package com.trustissue.child

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class DailyDurationSplitterTest {
    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun `duration crossing midnight is credited to both dates`() {
        val start = localTime(2026, Calendar.JULY, 23, 23, 59, utc)

        assertEquals(
            linkedMapOf(
                "2026-07-23" to 60_000L,
                "2026-07-24" to 60_000L
            ),
            DailyDurationSplitter.fromStart(start, 2 * 60_000L, utc)
        )
    }

    @Test
    fun `ending-at split keeps the complete elapsed duration`() {
        val end = localTime(2026, Calendar.JULY, 24, 0, 1, utc)
        val chunks = DailyDurationSplitter.endingAt(end, 2 * 60_000L, utc)

        assertEquals(2 * 60_000L, chunks.values.sum())
        assertEquals(setOf("2026-07-23", "2026-07-24"), chunks.keys)
    }

    @Test
    fun `daylight-saving transition does not lose elapsed time`() {
        val losAngeles = TimeZone.getTimeZone("America/Los_Angeles")
        val start = localTime(
            2026,
            Calendar.MARCH,
            8,
            0,
            30,
            losAngeles
        )
        val duration = 4 * 60 * 60 * 1000L

        assertEquals(
            duration,
            DailyDurationSplitter.fromStart(start, duration, losAngeles).values.sum()
        )
    }

    private fun localTime(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        timeZone: TimeZone
    ): Long = Calendar.getInstance(timeZone).run {
        clear()
        set(year, month, day, hour, minute)
        timeInMillis
    }
}
