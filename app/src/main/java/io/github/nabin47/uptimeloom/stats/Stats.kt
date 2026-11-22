package io.github.nabin47.uptimeloom.stats

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

data class Span(val start: Long, val end: Long?)

data class StatsConfig(
    val weekStart: DayOfWeek,
    val weekend: Set<DayOfWeek>,
    val dailyTargetMs: Long,
    val workStartMin: Int?,
    val workEndMin: Int?,
)

data class DaySpan(val date: LocalDate, val arrivalMin: Int, val departureMin: Int, val departureNextDay: Boolean)

data class PeriodSummary(
    val worked: Long,
    val target: Long,
    val stillNeeded: Long,
    val perDayNeeded: Long,
    val remainingWorkdays: Int,
    val todayIsWorkday: Boolean,
    val balance: Long,
    val pastWorkdays: Int,
    val periodWorkdays: Int,
    val daysOffCount: Int,
    val present: Int,
    val workdaysSoFar: Int,
    val missing: List<LocalDate>,
    val avgPerDay: Long,
    val onTime: Int,
    val arrivalsCounted: Int,
    val leftEarly: Int,
    val departuresCounted: Int,
    val weekendDays: Int,
    val weekendMs: Long,
    val longDays: List<Pair<LocalDate, Long>>,
)

/** A plain sentence. Text between ** is highlighted. [dayOff] offers a "Mark as day off" button. */
data class Note(val text: String, val dayOff: LocalDate? = null)

data class WeekBar(val start: LocalDate, val ms: Long, val current: Boolean)
data class DayValue(val date: LocalDate, val ms: Long)

/**
 * All statistics, computed from work sessions. Pure Kotlin (no Android), so
 * it's unit-tested. A running session counts up to [now].
 */
class Stats(
    spans: List<Span>,
    private val daysOff: Set<LocalDate>,
    private val cfg: StatsConfig,
    val now: Long,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    companion object { const val GRACE_MIN = 15 }

    // Merged, sorted, non-overlapping intervals.
    private val starts: LongArray
    private val ends: LongArray

    init {
        val raw = spans.map { it.start to (it.end ?: now) }.filter { it.second > it.first }.sortedBy { it.first }
        val s = ArrayList<Long>(); val e = ArrayList<Long>()
        for ((a, b) in raw) {
            if (s.isNotEmpty() && a <= e.last()) e[e.lastIndex] = maxOf(e.last(), b)
            else { s.add(a); e.add(b) }
        }
        starts = s.toLongArray(); ends = e.toLongArray()
    }

    val today: LocalDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

    fun dayStart(d: LocalDate): Long = d.atStartOfDay(zone).toInstant().toEpochMilli()
    private fun toDate(t: Long): LocalDate = Instant.ofEpochMilli(t).atZone(zone).toLocalDate()
    private fun minuteOfDay(t: Long): Int = Instant.ofEpochMilli(t).atZone(zone).let { it.hour * 60 + it.minute }

    /** Tracked time inside [from, to). Binary search keeps this cheap for long histories. */
    fun msInRange(from: Long, to: Long): Long {
        if (to <= from || ends.isEmpty()) return 0
        var lo = 0; var hi = ends.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (ends[mid] <= from) lo = mid + 1 else hi = mid }
        var sum = 0L
        var i = lo
        while (i < starts.size && starts[i] < to) {
            sum += minOf(to, ends[i]) - maxOf(from, starts[i])
            i++
        }
        return sum
    }

    fun dayTotal(d: LocalDate): Long = msInRange(dayStart(d), dayStart(d.plusDays(1)))

    /** First arrival / last departure per day (by the day each stretch started). */
    val daySpans: Map<LocalDate, DaySpan> by lazy {
        val first = HashMap<LocalDate, Long>()
        val last = HashMap<LocalDate, Long>()
        for (i in starts.indices) {
            val d = toDate(starts[i])
            first.merge(d, starts[i]) { x, y -> minOf(x, y) }
            last.merge(d, ends[i]) { x, y -> maxOf(x, y) }
        }
        first.mapValues { (d, a) ->
            val e = last.getValue(d)
            DaySpan(d, minuteOfDay(a), minuteOfDay(e), toDate(e).isAfter(d))
        }
    }

    fun isWeekend(d: LocalDate) = d.dayOfWeek in cfg.weekend
    fun isWorkday(d: LocalDate) = !isWeekend(d) && d !in daysOff

    fun weekStartOf(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(cfg.weekStart))

    // ---------------------------------------------------------------- quick stats --

    val todayMs: Long get() = dayTotal(today)
    val thisWeekStart: LocalDate get() = weekStartOf(today)
    val weekMs: Long get() = msInRange(dayStart(thisWeekStart), now)
    val lastWeekByNow: Long get() = msInRange(dayStart(thisWeekStart.minusWeeks(1)), now - 7L * 86_400_000)
    val monthStart: LocalDate get() = today.withDayOfMonth(1)
    val monthMs: Long get() = msInRange(dayStart(monthStart), now)
    val lastMonthByNow: Long
        get() {
            val prevStart = monthStart.minusMonths(1)
            val samePoint = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
                .withYear(prevStart.year).withMonth(prevStart.monthValue)
                .withDayOfMonth(minOf(today.dayOfMonth, prevStart.lengthOfMonth()))
            return msInRange(dayStart(prevStart), samePoint.atZone(zone).toInstant().toEpochMilli())
        }

    /** Average first arrival over the last 10 days worked (minutes after midnight). */
    val usualArrivalMin: Int?
        get() = daySpans.values.sortedBy { it.date }.takeLast(10).map { it.arrivalMin }.averageOrNull()

    /** Average last departure over the last 10 finished days. */
    fun usualDepartureMin(running: Boolean): Int? =
        daySpans.values.filter { !(running && it.date == today) }.sortedBy { it.date }.takeLast(10)
            .map { if (it.departureNextDay) 24 * 60 else it.departureMin }.averageOrNull()

    private fun List<Int>.averageOrNull(): Int? = if (isEmpty()) null else average().roundToInt()

    // ------------------------------------------------------------------- summary --

    fun summarize(from: LocalDate, toExclusive: LocalDate): PeriodSummary {
        val perDay = cfg.dailyTargetMs
        val todayStart = dayStart(today)
        val worked = msInRange(dayStart(from), minOf(dayStart(toExclusive), now))
        val workedBeforeToday = msInRange(dayStart(from), minOf(dayStart(toExclusive), todayStart))
        var periodWorkdays = 0; var pastWorkdays = 0; var remaining = 0; var daysOffCount = 0
        var present = 0; var presentFinished = 0; var workedFinishedPresent = 0L; var soFar = 0
        var onTime = 0; var arrivals = 0; var leftEarly = 0; var departures = 0
        var weekendDays = 0; var weekendMs = 0L; var todayIsWorkday = false
        val missing = ArrayList<LocalDate>(); val longDays = ArrayList<Pair<LocalDate, Long>>()

        var d = from
        while (d.isBefore(toExclusive)) {
            val weekend = isWeekend(d); val off = d in daysOff; val workday = !weekend && !off
            if (off && !weekend) daysOffCount++
            if (workday) periodWorkdays++
            if (d.isAfter(today)) {
                if (workday) remaining++
                d = d.plusDays(1); continue
            }
            val total = dayTotal(d)
            val isToday = d == today
            if (workday && !isToday) { pastWorkdays++; soFar++ }
            if (workday && isToday) { remaining++; todayIsWorkday = true; if (total > 0) soFar++ }
            if (total > 10 * 3_600_000L) longDays.add(d to total)
            if (weekend && total > 0) { weekendDays++; weekendMs += total }
            if (workday) {
                if (total > 0) {
                    present++
                    if (!isToday) { presentFinished++; workedFinishedPresent += total }
                } else if (!isToday) missing.add(d)
                val span = daySpans[d]
                if (span != null && total > 0) {
                    cfg.workStartMin?.let { arrivals++; if (span.arrivalMin <= it + GRACE_MIN) onTime++ }
                    if (cfg.workEndMin != null && !isToday) {
                        departures++
                        val dep = if (span.departureNextDay) 24 * 60 else span.departureMin
                        if (dep < cfg.workEndMin - GRACE_MIN) leftEarly++
                    }
                }
            }
            d = d.plusDays(1)
        }
        val target = perDay * periodWorkdays
        val stillNeeded = (target - worked).coerceAtLeast(0)
        return PeriodSummary(
            worked = worked, target = target, stillNeeded = stillNeeded,
            perDayNeeded = if (remaining > 0) stillNeeded / remaining else 0,
            remainingWorkdays = remaining, todayIsWorkday = todayIsWorkday,
            balance = workedBeforeToday - perDay * pastWorkdays,
            pastWorkdays = pastWorkdays, periodWorkdays = periodWorkdays, daysOffCount = daysOffCount,
            present = present, workdaysSoFar = soFar, missing = missing,
            avgPerDay = when {
                presentFinished > 0 -> workedFinishedPresent / presentFinished
                present > 0 -> worked / present
                else -> 0
            },
            onTime = onTime, arrivalsCounted = arrivals, leftEarly = leftEarly, departuresCounted = departures,
            weekendDays = weekendDays, weekendMs = weekendMs, longDays = longDays,
        )
    }

    fun week() = summarize(thisWeekStart, thisWeekStart.plusWeeks(1))
    fun month() = summarize(monthStart, monthStart.plusMonths(1))

    // --------------------------------------------------------------------- notes --

    fun notes(use24h: Boolean, dateLabel: (LocalDate) -> String): List<Note> {
        val out = ArrayList<Note>()
        val wk = week()
        if (cfg.dailyTargetMs > 0) {
            out += when {
                wk.stillNeeded <= 0 -> Note("You've reached this week's **${fmtH(wk.target)}** target.")
                wk.remainingWorkdays > 0 -> Note(
                    "To reach this week's ${fmtH(wk.target)} target you need **${fmtHM(wk.stillNeeded)}** more. " +
                        "That's about **${fmtHM(wk.perDayNeeded)}** on each of the ${plural(wk.remainingWorkdays, "workday")} left" +
                        (if (wk.todayIsWorkday) ", counting today." else ".")
                )
                else -> Note("This week is **${fmtHM(wk.stillNeeded)}** short of the ${fmtH(wk.target)} target, with no workdays left.")
            }
        }
        // Missed workdays in the last 14 days.
        val missed = (14 downTo 1).map { today.minusDays(it.toLong()) }.filter { isWorkday(it) && dayTotal(it) == 0L }
        missed.takeLast(3).forEach {
            out += Note(
                "No time was recorded on **${dateLabel(it)}**, a workday. If you were on leave or it was a holiday, " +
                    "mark it as a day off. If you worked, add the time in the log.",
                dayOff = it,
            )
        }
        if (missed.size > 3) out += Note("${plural(missed.size - 3, "more workday")} in the last two weeks have no time recorded.")

        // Last day worked.
        (1..14).map { today.minusDays(it.toLong()) }.firstOrNull { dayTotal(it) > 0 && daySpans[it] != null }?.let { d ->
            val s = daySpans.getValue(d)
            val out_ = if (s.departureNextDay) "after midnight" else fmtClock(s.departureMin, use24h)
            out += Note("Last day worked (${dateLabel(d)}): arrived **${fmtClock(s.arrivalMin, use24h)}**, left **$out_**, **${fmtHM(dayTotal(d))}** in total.")
        }

        // Repeated late arrivals.
        cfg.workStartMin?.let { start ->
            val recent = (0 until 30).map { today.minusDays(it.toLong()) }
                .filter { !isWeekend(it) }.mapNotNull { daySpans[it] }.take(5)
            val late = recent.count { it.arrivalMin > start + GRACE_MIN }
            if (recent.size >= 3 && late >= 3) out += Note("You arrived after **${fmtClock(start + GRACE_MIN, use24h)}** on $late of your last ${recent.size} workdays.")
        }
        wk.longDays.forEach { (d, ms) -> out += Note("${dateLabel(d)} was a long day: **${fmtHM(ms)}**.") }
        if (out.isEmpty()) out += Note("Nothing needs your attention right now.")
        return out
    }

    // -------------------------------------------------------------------- charts --

    fun lastDays(n: Int): List<DayValue> = (n - 1 downTo 0).map { today.minusDays(it.toLong()) }.map { DayValue(it, dayTotal(it)) }

    fun weeks(n: Int): List<WeekBar> = (n - 1 downTo 0).map { i ->
        val ws = thisWeekStart.minusWeeks(i.toLong())
        WeekBar(ws, msInRange(dayStart(ws), minOf(dayStart(ws.plusWeeks(1)), now)), i == 0)
    }

    /** Average minutes worked in each clock hour over days worked in the last [days] days. */
    fun hourProfile(days: Int = 30): Pair<DoubleArray, Int> {
        val worked = (0 until days).map { today.minusDays(it.toLong()) }.filter { dayTotal(it) > 0 }
        val mins = DoubleArray(24)
        for (d in worked) for (h in 0 until 24) {
            val a = d.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()
            val b = if (h == 23) dayStart(d.plusDays(1)) else d.atTime(h + 1, 0).atZone(zone).toInstant().toEpochMilli()
            mins[h] += msInRange(a, b) / 60_000.0
        }
        if (worked.isNotEmpty()) for (h in 0 until 24) mins[h] /= worked.size
        return mins to worked.size
    }

    /** Average hours per day worked, by weekday, in display order. */
    fun weekdayAverages(): List<Pair<DayOfWeek, Long>> {
        val first = if (starts.isEmpty()) return orderedDays().map { it to 0L } else toDate(starts.first())
        val totals = HashMap<DayOfWeek, Long>(); val counts = HashMap<DayOfWeek, Int>()
        var d = first
        while (!d.isAfter(today)) {
            val t = dayTotal(d)
            if (t > 0) { totals.merge(d.dayOfWeek, t) { x, y -> x + y }; counts.merge(d.dayOfWeek, 1) { x, y -> x + y } }
            d = d.plusDays(1)
        }
        return orderedDays().map { dw -> dw to (counts[dw]?.let { totals.getValue(dw) / it } ?: 0L) }
    }

    fun orderedDays(): List<DayOfWeek> = (0 until 7).map { cfg.weekStart.plus(it.toLong()) }

    /** Calendar: [weeks] columns of 7 days ending this week; future days have ms = -1. */
    fun calendar(weeks: Int = 18): List<DayValue> {
        val start = thisWeekStart.minusWeeks((weeks - 1).toLong())
        return (0 until weeks * 7).map { i ->
            val d = start.plusDays(i.toLong())
            DayValue(d, if (d.isAfter(today)) -1 else dayTotal(d))
        }
    }
}

fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"
