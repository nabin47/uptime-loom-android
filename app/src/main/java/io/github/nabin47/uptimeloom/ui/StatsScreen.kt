package io.github.nabin47.uptimeloom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.nabin47.uptimeloom.stats.PeriodSummary
import io.github.nabin47.uptimeloom.stats.Stats
import io.github.nabin47.uptimeloom.stats.fmtClock
import io.github.nabin47.uptimeloom.stats.fmtH
import io.github.nabin47.uptimeloom.stats.fmtHM
import io.github.nabin47.uptimeloom.stats.plural
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun StatsScreen(state: UiState, vm: MainViewModel) {
    val stats = state.stats ?: return
    val context = LocalContext.current
    val use24h = Fmt.is24h(context)
    val s = state.settings
    val wk = remember(stats) { stats.week() }
    val mo = remember(stats) { stats.month() }
    val notes = remember(stats, use24h) { stats.notes(use24h) { Fmt.dayLabel(it) } }
    val hasTarget = s.dailyTargetMs > 0

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Statistics", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)

        SectionCard(
            title = "Summary",
            subtitle = "Week of ${Fmt.dayLabel(stats.thisWeekStart)} · ${Fmt.monthYear(stats.today)}" +
                if (hasTarget) " · ${fmtH(s.dailyTargetMs)} per workday" else "",
        ) {
            SummaryHeader()
            val good = Tone.good; val warn = Tone.warn
            SummaryRow("Hours worked", "includes manual sessions", fmtHM(wk.worked), fmtHM(mo.worked))
            SummaryRow("Target", null, targetText(wk, hasTarget, s.dailyTargetMs), targetText(mo, hasTarget, s.dailyTargetMs))
            SummaryRow("Still needed", null, neededText(wk, hasTarget), neededText(mo, hasTarget),
                if (wk.stillNeeded <= 0 && hasTarget) good else null, if (mo.stillNeeded <= 0 && hasTarget) good else null)
            SummaryRow("Ahead or behind", "finished days only", balanceText(wk, hasTarget), balanceText(mo, hasTarget),
                balanceColor(wk, hasTarget, good, warn), balanceColor(mo, hasTarget, good, warn))
            SummaryRow("Days present", "workdays with time recorded", presentText(wk), presentText(mo))
            SummaryRow("Average per day", "finished days", if (wk.present > 0) fmtHM(wk.avgPerDay) else "—", if (mo.present > 0) fmtHM(mo.avgPerDay) else "—")
            s.workStartMin?.let { start ->
                SummaryRow("On time", "arrived by ${fmtClock(start + Stats.GRACE_MIN, use24h)}",
                    ofDays(wk.onTime, wk.arrivalsCounted), ofDays(mo.onTime, mo.arrivalsCounted))
            }
            s.workEndMin?.let { end ->
                SummaryRow("Left early", "before ${fmtClock(end - Stats.GRACE_MIN, use24h)}",
                    earlyText(wk), earlyText(mo), if (wk.leftEarly > 0) warn else null, if (mo.leftEarly > 0) warn else null)
            }
            SummaryRow("Weekend work", null, weekendText(wk), weekendText(mo))
        }

        SectionCard(title = "Things to know") {
            val hl = MaterialTheme.colorScheme.primary
            notes.forEachIndexed { i, n ->
                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(boldMarkup(n.text, hl), style = MaterialTheme.typography.bodyMedium)
                n.dayOff?.let { d ->
                    OutlinedButton(onClick = { vm.setDayOff(d, "") }, modifier = Modifier.padding(top = 6.dp)) { Text("Mark as day off") }
                }
            }
        }

        val primary = MaterialTheme.colorScheme.primary
        val track = MaterialTheme.colorScheme.surfaceContainerHigh
        val muted = MaterialTheme.colorScheme.outlineVariant

        SectionCard(title = "Daily hours", subtitle = "last 30 days · line = 7-day average") {
            val days = remember(stats) { stats.lastDays(36) }
            val shown = days.drop(6)
            val avg = shown.indices.map { i -> days.subList(i, i + 7).map { it.ms }.average().toFloat() / 3_600_000f }
            BarChart(
                values = shown.map { it.ms / 3_600_000f },
                colors = shown.map { if (stats.isWeekend(it.date)) muted else track },
                labels = shown.mapIndexed { i, d -> if (i % 7 == 6 || i == shown.lastIndex) d.dayOfMonth.toString() else null },
                description = "Daily hours for the last 30 days",
                line = avg,
            )
        }

        SectionCard(title = "Weekly hours", subtitle = "last 12 weeks" + if (hasTarget) " · dashed line = ${fmtH((s.weeklyTargetH * 3_600_000).toLong())} target" else "") {
            val weeks = remember(stats) { stats.weeks(12) }
            val good = Tone.good
            val target = s.weeklyTargetH
            BarChart(
                values = weeks.map { it.ms / 3_600_000f },
                colors = weeks.map { when { it.current -> primary; target > 0 && it.ms / 3_600_000f >= target -> good; else -> track } },
                labels = weeks.mapIndexed { i, w -> if (i % 3 == 2) "${w.start.dayOfMonth}/${w.start.monthValue}" else null },
                description = "Weekly hours for the last 12 weeks",
                target = if (target > 0) target else null,
            )
        }

        SectionCard(title = "When you work") {
            val (profile, count) = remember(stats) { stats.hourProfile(30) }
            val peak = profile.indices.maxByOrNull { profile[it] }?.takeIf { profile[it] > 0 }
            Text(
                if (count == 0) "No days worked in the last 30 days."
                else "Average minutes per hour, ${plural(count, "day")} worked in the last 30 days" +
                    (peak?.let { " · busiest ${fmtClock(it * 60, use24h)}" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            BarChart(
                values = profile.map { it.toFloat() },
                colors = profile.map { primary.copy(alpha = (0.35f + 0.65f * (it / 60.0).toFloat()).coerceIn(0.35f, 1f)) },
                labels = (0 until 24).map { if (it % 6 == 0) it.toString() else null },
                description = "Average minutes worked in each hour of the day",
                height = 110,
            )
        }

        SectionCard(title = "By day of the week", subtitle = "average hours per day worked") {
            val avgs = remember(stats) { stats.weekdayAverages() }
            BarChart(
                values = avgs.map { it.second / 3_600_000f },
                colors = avgs.map { if (it.first in s.weekend) muted else primary },
                labels = avgs.map { it.first.getDisplayName(TextStyle.SHORT, Locale.getDefault()) },
                description = "Average hours by day of the week",
                height = 110,
            )
        }

        SectionCard(title = "Calendar", subtitle = "last 18 weeks") {
            CalendarHeatmap(remember(stats) { stats.calendar(18) }, "Activity calendar for the last 18 weeks")
        }
    }
}

@Composable
private fun SummaryHeader() {
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Spacer(Modifier.weight(1.3f))
        Text("This week", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("This month", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SummaryRow(label: String, hint: String?, week: String, month: String, weekColor: Color? = null, monthColor: Color? = null) {
    HorizontalDivider()
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1.3f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (hint != null) Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(week, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = weekColor ?: Color.Unspecified)
        Text(month, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = monthColor ?: Color.Unspecified)
    }
}

private fun targetText(p: PeriodSummary, has: Boolean, perDay: Long) =
    if (!has) "—" else "${fmtH(p.target)}\n${fmtHM(perDay)} × ${p.periodWorkdays}" + if (p.daysOffCount > 0) " (${p.daysOffCount} off)" else ""

private fun neededText(p: PeriodSummary, has: Boolean) = when {
    !has -> "—"
    p.stillNeeded <= 0 -> "Target reached"
    p.remainingWorkdays > 0 -> "${fmtHM(p.stillNeeded)}\n~${fmtHM(p.perDayNeeded)}/day × ${p.remainingWorkdays}"
    else -> "${fmtHM(p.stillNeeded)}\nno workdays left"
}

private fun balanceText(p: PeriodSummary, has: Boolean) = when {
    !has || p.pastWorkdays == 0 -> "—"
    kotlin.math.abs(p.balance) < 60_000 -> "On track"
    p.balance > 0 -> "${fmtHM(p.balance)} ahead"
    else -> "${fmtHM(-p.balance)} behind"
}

private fun balanceColor(p: PeriodSummary, has: Boolean, good: Color, warn: Color): Color? = when {
    !has || p.pastWorkdays == 0 || kotlin.math.abs(p.balance) < 60_000 -> null
    p.balance > 0 -> good
    else -> warn
}

private fun presentText(p: PeriodSummary) =
    "${p.present} of ${p.workdaysSoFar}" + if (p.missing.isNotEmpty()) "\nmissed: " + p.missing.take(3).joinToString(", ") { Fmt.dayShort(it) } else ""

private fun ofDays(n: Int, of: Int) = if (of == 0) "—" else "$n of $of"
private fun earlyText(p: PeriodSummary) = if (p.departuresCounted == 0) "—" else if (p.leftEarly == 0) "None" else plural(p.leftEarly, "day")
private fun weekendText(p: PeriodSummary) = if (p.weekendDays == 0) "None" else "${plural(p.weekendDays, "day")}\n${fmtHM(p.weekendMs)}"
