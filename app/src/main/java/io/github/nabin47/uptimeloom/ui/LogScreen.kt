package io.github.nabin47.uptimeloom.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.BeachAccess
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.nabin47.uptimeloom.data.Session
import io.github.nabin47.uptimeloom.stats.fmtHM
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

private enum class Period(val label: String) { ThisWeek("This week"), LastWeek("Last week"), ThisMonth("This month"), LastMonth("Last month"), Days30("30 days"), All("All") }

private sealed interface LogItem {
    data class Header(val date: LocalDate, val total: Long, val dayOff: String?, val weekend: Boolean) : LogItem
    data class Row(val session: Session) : LogItem
}

@Composable
fun LogScreen(state: UiState, vm: MainViewModel) {
    val stats = state.stats ?: return
    val context = LocalContext.current
    var period by rememberSaveable { mutableStateOf(Period.Days30) }
    var editing by remember { mutableStateOf<Session?>(null) }
    var dayOffDialog by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.exportCsv(uri) { ok ->
            Toast.makeText(context, if (ok) "Exported" else "Export failed", Toast.LENGTH_SHORT).show()
        }
    }

    val (from, to) = remember(period, stats) {
        val t = stats.today
        when (period) {
            Period.ThisWeek -> stats.thisWeekStart to stats.thisWeekStart.plusWeeks(1)
            Period.LastWeek -> stats.thisWeekStart.minusWeeks(1) to stats.thisWeekStart
            Period.ThisMonth -> stats.monthStart to stats.monthStart.plusMonths(1)
            Period.LastMonth -> stats.monthStart.minusMonths(1) to stats.monthStart
            Period.Days30 -> t.minusDays(29) to t.plusDays(1)
            Period.All -> LocalDate.of(2000, 1, 1) to t.plusDays(2)
        }
    }
    val rows = remember(state.sessions, state.daysOff, from, to, stats) {
        val byDay = state.sessions.groupBy { Fmt.date(it.start) }.filterKeys { !it.isBefore(from) && it.isBefore(to) }
        val days = (byDay.keys + state.daysOff.keys.filter { !it.isBefore(from) && it.isBefore(to) }).toSortedSet().reversed()
        buildList {
            for (d in days) {
                add(LogItem.Header(d, stats.dayTotal(d), state.daysOff[d]?.note, stats.isWeekend(d)))
                byDay[d].orEmpty().sortedByDescending { it.start }.forEach { add(LogItem.Row(it)) }
            }
        }
    }
    val total = remember(rows) { rows.filterIsInstance<LogItem.Header>().sumOf { it.total } }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Log", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { dayOffDialog = true }) { Icon(Icons.Outlined.BeachAccess, contentDescription = "Add a day off") }
                    IconButton(onClick = { exportLauncher.launch("uptime-loom-${stats.today}.csv") }) {
                        Icon(Icons.Outlined.FileDownload, contentDescription = "Export CSV")
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Period.entries.forEach { p -> FilterChip(selected = period == p, onClick = { period = p }, label = { Text(p.label) }) }
                }
                Text(
                    "${fmtHM(total)} over ${rows.count { it is LogItem.Header && it.total > 0 }} days",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                )
            }
            if (rows.isEmpty()) item { Text("No sessions in this period.", Modifier.padding(vertical = 24.dp)) }
            items(rows, key = { when (it) { is LogItem.Header -> "h${it.date}"; is LogItem.Row -> "s${it.session.id}" } }) { item ->
                when (item) {
                    is LogItem.Header -> Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(Fmt.dayLabel(item.date), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        if (item.dayOff != null) {
                            TextButton(onClick = { vm.removeDayOff(item.date) }) { Text("Day off${if (item.dayOff.isNotBlank()) " · ${item.dayOff}" else ""} ✕") }
                        } else {
                            Text(fmtHM(item.total) + if (item.weekend) " · weekend" else "",
                                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    is LogItem.Row -> SessionRow(item.session, state) { editing = item.session }
                }
            }
        }
        FloatingActionButton(
            onClick = {
                val now = System.currentTimeMillis()
                editing = Session(id = 0, start = now - 3_600_000, end = now, source = Session.SOURCE_MANUAL)
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Filled.Add, contentDescription = "Add a session") }
    }

    editing?.let { s -> SessionDialog(s, onDismiss = { editing = null }, onSave = { vm.saveSession(it); editing = null },
        onDelete = { vm.deleteSession(s.id); editing = null }) }
    if (dayOffDialog) DayOffDialog(onDismiss = { dayOffDialog = false }) { d, note -> vm.setDayOff(d, note); dayOffDialog = false }
}

@Composable
private fun SessionRow(s: Session, state: UiState, onClick: () -> Unit) {
    val context = LocalContext.current
    SectionCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${Fmt.time(context, s.start)} – ${s.end?.let { Fmt.time(context, it) } ?: "now"}" +
                        if (s.end != null && Fmt.date(s.end) != Fmt.date(s.start)) " (+1d)" else "",
                    style = MaterialTheme.typography.bodyLarge,
                )
                val tags = listOfNotNull(
                    state.placeName(s.placeId),
                    if (s.source == Session.SOURCE_MANUAL) "manual" else null,
                    if (s.edited) "edited" else null,
                    s.note.takeIf { it.isNotBlank() },
                )
                if (tags.isNotEmpty()) Text(tags.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                fmtHM((s.end ?: System.currentTimeMillis()) - s.start),
                style = MaterialTheme.typography.titleSmall,
                color = if (s.end == null) Tone.good else MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun pickDateTime(context: Context, initial: LocalDateTime, onPicked: (LocalDateTime) -> Unit) {
    DatePickerDialog(context, { _, y, m, d ->
        TimePickerDialog(context, { _, h, min ->
            onPicked(LocalDateTime.of(y, m + 1, d, h, min))
        }, initial.hour, initial.minute, Fmt.is24h(context)).show()
    }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
}

@Composable
private fun SessionDialog(session: Session, onDismiss: () -> Unit, onSave: (Session) -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    var start by remember { mutableStateOf(session.start) }
    var end by remember { mutableStateOf(session.end) }
    var note by remember { mutableStateOf(session.note) }
    val running = session.id != 0L && session.end == null
    fun ldt(t: Long) = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(t), zone)
    fun ms(l: LocalDateTime) = l.atZone(zone).toInstant().toEpochMilli()
    val valid = end == null || end!! > start

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (session.id == 0L) "Add session" else "Edit session") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickDateTime(context, ldt(start)) { start = ms(it) } }, modifier = Modifier.fillMaxWidth()) {
                    Text("Start: ${Fmt.dayLabel(Fmt.date(start))}, ${Fmt.time(context, start)}")
                }
                if (running) {
                    Text("Still running — it ends when you leave or check out.", style = MaterialTheme.typography.bodySmall)
                } else {
                    OutlinedButton(onClick = { pickDateTime(context, ldt(end ?: start)) { end = ms(it) } }, modifier = Modifier.fillMaxWidth()) {
                        Text("End: ${end?.let { "${Fmt.dayLabel(Fmt.date(it))}, ${Fmt.time(context, it)}" } ?: "—"}")
                    }
                }
                OutlinedTextField(value = note, onValueChange = { note = it.take(80) }, label = { Text("Note (optional)") }, singleLine = true)
                if (!valid) Text("End must be after start.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(enabled = valid, onClick = { onSave(session.copy(start = start, end = if (running) null else end, note = note.trim())) }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (session.id != 0L) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun DayOffDialog(onDismiss: () -> Unit, onSave: (LocalDate, String) -> Unit) {
    val context = LocalContext.current
    var date by remember { mutableStateOf(LocalDate.now()) }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Day off") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Leave, holiday or sick day. It won't count as missed and lowers that week's and month's target.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    DatePickerDialog(context, { _, y, m, d -> date = LocalDate.of(y, m + 1, d) }, date.year, date.monthValue - 1, date.dayOfMonth).show()
                }, modifier = Modifier.fillMaxWidth()) { Text(Fmt.dayLabel(date)) }
                OutlinedTextField(value = note, onValueChange = { note = it.take(80) }, label = { Text("Reason (optional)") }, singleLine = true)
            }
        },
        confirmButton = { Button(onClick = { onSave(date, note.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
