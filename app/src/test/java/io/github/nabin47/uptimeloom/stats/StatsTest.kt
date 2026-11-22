package io.github.nabin47.uptimeloom.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

class StatsTest {
    private val zone: ZoneId = ZoneId.of("Asia/Dhaka")
    private val cfg = StatsConfig(
        weekStart = DayOfWeek.MONDAY,
        weekend = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
        dailyTargetMs = 8 * H,
        workStartMin = 9 * 60,
        workEndMin = 17 * 60,
    )

    private fun t(d: LocalDate, h: Double) = d.atStartOfDay(zone).toInstant().toEpochMilli() + (h * H).toLong()
    private fun span(d: LocalDate, a: Double, b: Double) = Span(t(d, a), t(d, b))

    // Wednesday 2026-11-18, 11:00
    private val wed = LocalDate.of(2026, 11, 18)
    private val mon = wed.minusDays(2)
    private val tue = wed.minusDays(1)

    @Test fun overlappingTimeIsCountedOnce() {
        val s = Stats(listOf(span(mon, 9.0, 12.0), span(mon, 11.0, 13.0)), emptySet(), cfg, t(wed, 11.0), zone)
        assertEquals(4 * H, s.dayTotal(mon))
    }

    @Test fun sessionsAreSplitAtMidnight() {
        val s = Stats(listOf(Span(t(mon, 23.0), t(tue, 1.0))), emptySet(), cfg, t(wed, 11.0), zone)
        assertEquals(1 * H, s.dayTotal(mon))
        assertEquals(1 * H, s.dayTotal(tue))
    }

    @Test fun runningSessionCountsUntilNow() {
        val s = Stats(listOf(Span(t(wed, 9.0), null)), emptySet(), cfg, t(wed, 11.0), zone)
        assertEquals(2 * H, s.todayMs)
    }

    @Test fun weekSummary() {
        val spans = listOf(span(mon, 8.9, 17.3), span(tue, 9.6, 17.0), Span(t(wed, 9.0), null))
        val s = Stats(spans, emptySet(), cfg, t(wed, 11.0), zone)
        val w = s.week()
        assertEquals(40 * H, w.target)
        assertEquals(3, w.remainingWorkdays)               // Wed (today), Thu, Fri
        assertEquals(2, w.pastWorkdays)
        assertEquals(3, w.present)
        assertEquals(3, w.arrivalsCounted)
        assertEquals(2, w.onTime)                          // Mon 8:54 and Wed 9:00 on time, Tue 9:36 late
        assertTrue(w.missing.isEmpty())
        // finished days: 8.4h + 7.4h = 15.8h vs 16h -> 12m behind
        assertEquals((15.8 * H).toLong() - 16 * H, w.balance)
    }

    @Test fun dayOffLowersTargetAndIsNotMissed() {
        val spans = listOf(span(tue, 9.0, 17.0))
        val s = Stats(spans, setOf(mon), cfg, t(wed, 11.0), zone)
        val w = s.week()
        assertEquals(32 * H, w.target)
        assertEquals(1, w.daysOffCount)
        assertTrue(w.missing.isEmpty())
    }

    @Test fun missedWorkdayIsReported() {
        val s = Stats(listOf(span(tue, 9.0, 17.0)), emptySet(), cfg, t(wed, 11.0), zone)
        assertEquals(listOf(mon), s.week().missing)
        val notes = s.notes(use24h = true) { it.toString() }
        assertTrue(notes.any { it.dayOff == mon })
    }

    @Test fun formatting() {
        assertEquals("8h", fmtHM(8 * H))
        assertEquals("16m", fmtHM(16 * 60_000L))
        assertEquals("7h 56m", fmtHM(7 * H + 56 * 60_000L))
        assertEquals("40h", fmtH(40 * H))
        assertEquals("31.7h", fmtH((31.7 * H).toLong()))
        assertEquals("9:15 AM", fmtClock(9 * 60 + 15, use24h = false))
        assertEquals("17:00", fmtClock(17 * 60, use24h = true))
    }

    private companion object { const val H = 3_600_000L }
}
