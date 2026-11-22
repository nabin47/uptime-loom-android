package io.github.nabin47.uptimeloom.ui

import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nabin47.uptimeloom.BuildConfig
import io.github.nabin47.uptimeloom.data.ThemeMode
import io.github.nabin47.uptimeloom.stats.fmtClock
import io.github.nabin47.uptimeloom.tracking.TrackingMode
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

const val WEBSITE = "https://nabin47.github.io/uptime-loom-android/"

@Composable
fun SettingsScreen(state: UiState, vm: MainViewModel) {
    val s = state.settings
    val context = LocalContext.current
    val use24h = Fmt.is24h(context)
    var showEvents by remember { mutableStateOf(false) }
    var targetText by remember(s.weeklyTargetH) { mutableStateOf(trimFloat(s.weeklyTargetH)) }
    val days = (0 until 7).map { s.weekStart.plus(it.toLong()) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)

        SectionCard(title = "Your week") {
            Label("Week starts on")
            ChipRow(DayOfWeek.entries.map { it to it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }, selected = { it == s.weekStart }) { d ->
                vm.updateSettings { it.copy(weekStart = d) }
            }
            Label("Weekend days (not workdays)")
            ChipRow(days.map { it to it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }, selected = { it in s.weekend }) { d ->
                vm.updateSettings { it.copy(weekend = if (d in it.weekend) it.weekend - d else it.weekend + d) }
            }
            Label("Weekly target (hours, 0 = off)")
            OutlinedTextField(
                value = targetText, singleLine = true,
                onValueChange = { v ->
                    targetText = v
                    v.replace(',', '.').toFloatOrNull()?.takeIf { it in 0f..168f }?.let { h -> vm.updateSettings { it.copy(weeklyTargetH = h) } }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                supportingText = { if (s.dailyTargetMs > 0) Text("= ${io.github.nabin47.uptimeloom.stats.fmtHM(s.dailyTargetMs)} per workday") },
            )
            Label("Work hours (for \"on time\" and \"left early\")")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TimeButton(s.workStartMin, use24h) { m -> vm.updateSettings { it.copy(workStartMin = m) } }
                Text("to")
                TimeButton(s.workEndMin, use24h) { m -> vm.updateSettings { it.copy(workEndMin = m) } }
            }
            Text("15 minutes of leeway either side.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row {
                TextButton(onClick = { vm.updateSettings { it.copy(workStartMin = null, workEndMin = null) } }) { Text("Turn off") }
                TextButton(onClick = { vm.updateSettings { it.copy(workStartMin = 9 * 60, workEndMin = 17 * 60) } }) { Text("Reset to 9–5") }
            }
        }

        SectionCard(title = "Arriving and leaving") {
            var arrive by remember(s.arriveDelayMin) { mutableStateOf(s.arriveDelayMin.toFloat()) }
            var leave by remember(s.leaveDelayMin) { mutableStateOf(s.leaveDelayMin.toFloat()) }
            Label("Count as arrived after ${arrive.toInt()} min inside an area")
            Slider(value = arrive, onValueChange = { arrive = it }, valueRange = 1f..10f, steps = 8,
                onValueChangeFinished = { vm.updateSettings { it.copy(arriveDelayMin = arrive.toInt()) } })
            Label("Count as left after ${leave.toInt()} min outside all areas")
            Slider(value = leave, onValueChange = { leave = it }, valueRange = 0f..15f, steps = 14,
                onValueChangeFinished = { vm.updateSettings { it.copy(leaveDelayMin = leave.toInt()) } })
            Text("Short trips out (coffee, car park) don't split your day. Times are still recorded from when you actually arrived and left.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SwitchRow("Notify when tracking starts and stops", s.notifications) { v -> vm.updateSettings { it.copy(notifications = v) } }
            Text(
                if (state.mode == TrackingMode.GEOFENCING) "Tracking: Android geofencing (Google Play services) — uses almost no battery."
                else "Tracking: 15-minute checks (this phone has no Google Play services).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = {
                runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }) { Text("Battery optimisation settings") }
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://dontkillmyapp.com/"))) }) {
                Text("Tips for your phone brand (dontkillmyapp.com)")
            }
            TextButton(onClick = { showEvents = true }) { Text("Activity log (what the tracker received)") }
        }

        SectionCard(title = "Appearance") {
            ChipRow(ThemeMode.entries.map { it to it.name.lowercase().replaceFirstChar(Char::uppercase) }, selected = { it == s.theme }) { m ->
                vm.updateSettings { it.copy(theme = m) }
            }
            SwitchRow("Use colours from your wallpaper", s.dynamicColor) { v -> vm.updateSettings { it.copy(dynamicColor = v) } }
        }

        SectionCard(title = "About") {
            Text("Uptime Loom ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
            Text("All your data stays on this phone. Nothing is uploaded.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(WEBSITE))) }) { Text("Website and updates") }
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(WEBSITE + "privacy.html"))) }) { Text("Privacy policy") }
        }
    }

    if (showEvents) {
        val events by vm.events.collectAsStateWithLifecycle()
        AlertDialog(
            onDismissRequest = { showEvents = false },
            title = { Text("Activity log") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    if (events.isEmpty()) item { Text("Nothing yet.") }
                    items(events, key = { it.id }) { e ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text("${Fmt.dayShort(Fmt.date(e.time))} ${Fmt.time(context, e.time)} · ${e.kind}", style = MaterialTheme.typography.labelMedium)
                            Text(e.detail, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showEvents = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
}

@Composable
private fun <T> ChipRow(options: List<Pair<T, String>>, selected: (T) -> Boolean, onClick: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (v, label) -> FilterChip(selected = selected(v), onClick = { onClick(v) }, label = { Text(label) }) }
    }
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun TimeButton(minutes: Int?, use24h: Boolean, onPicked: (Int) -> Unit) {
    val context = LocalContext.current
    TextButton(onClick = {
        val m = minutes ?: (9 * 60)
        TimePickerDialog(context, { _, h, mm -> onPicked(h * 60 + mm) }, m / 60, m % 60, use24h).show()
    }) { Text(if (minutes == null) "Off" else fmtClock(minutes, use24h), style = MaterialTheme.typography.titleMedium) }
}

private fun trimFloat(f: Float) = if (f % 1f == 0f) f.toInt().toString() else f.toString()
