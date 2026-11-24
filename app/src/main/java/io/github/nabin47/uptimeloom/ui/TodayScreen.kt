package io.github.nabin47.uptimeloom.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nabin47.uptimeloom.stats.fmtClock
import io.github.nabin47.uptimeloom.stats.fmtH
import io.github.nabin47.uptimeloom.stats.fmtHM
import io.github.nabin47.uptimeloom.stats.fmtHMS
import io.github.nabin47.uptimeloom.tracking.AreaMonitor
import io.github.nabin47.uptimeloom.tracking.TrackingMode
import kotlinx.coroutines.delay

@Composable
fun TodayScreen(state: UiState, vm: MainViewModel, openAreas: () -> Unit) {
    val stats = state.stats ?: return
    val context = LocalContext.current
    val use24h = Fmt.is24h(context)
    val running = state.running

    // Live timer: today's total from the stats snapshot + time since it was taken.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running?.id) {
        while (running != null) { nowMs = System.currentTimeMillis(); delay(1000) }
    }
    val todayMs = stats.todayMs + if (running != null) (nowMs - stats.now).coerceAtLeast(0) else 0
    val week = stats.week()
    val month = stats.month()
    val daily = state.settings.dailyTargetMs

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Uptime Loom", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)

        SetupCard(state, vm, openAreas)
        OnOpenModeNote(state, vm)

        SectionCard {
            val inside = state.presence.mapNotNull { p -> state.placeName(p.placeId) }
            val status = when {
                running == null -> "Not at work"
                running.source == "manual" -> "Checked in"
                inside.isNotEmpty() -> "At ${inside.joinToString(", ")}"
                else -> "Leaving…"
            }
            AssistChip(onClick = {}, label = { Text(status) })
            Spacer(Modifier.height(8.dp))
            Text("Today", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                fmtHMS(todayMs), fontSize = 52.sp, fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start,
            )
            Text(
                if (running != null) "Started ${Fmt.time(context, running.start)}" +
                    (state.placeName(running.placeId)?.let { " at $it" } ?: "")
                else state.sessions.lastOrNull()?.end?.let { "Last session ended ${Fmt.dayLabel(Fmt.date(it))}, ${Fmt.time(context, it)}" } ?: "No sessions yet",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (daily > 0 && stats.isWorkday(stats.today)) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { (todayMs.toFloat() / daily).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (todayMs >= daily) "Today's ${fmtHM(daily)} done" else "${fmtHM(daily - todayMs)} to go of today's ${fmtHM(daily)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(12.dp))
            if (running == null) {
                FilledTonalButton(onClick = { vm.checkIn() }) { Text("Check in manually") }
            } else {
                FilledTonalButton(onClick = { vm.checkOut() }) { Text("Check out") }
            }
        }

        // Quick stats
        val tiles = listOf(
            Triple("This week", fmtH(week.worked), stats.lastWeekByNow.takeIf { it > 0 }?.let { "last week by now: ${fmtH(it)}" } ?: ""),
            Triple("Left this week",
                when { daily <= 0 -> "--"; week.stillNeeded <= 0 -> "Done"; else -> fmtHM(week.stillNeeded) },
                if (daily <= 0) "set a target in Settings" else "of ${fmtH(week.target)} target"),
            Triple("This month", fmtH(month.worked), stats.lastMonthByNow.takeIf { it > 0 }?.let { "last month by now: ${fmtH(it)}" } ?: ""),
            Triple("Days present", "${month.present}/${month.workdaysSoFar}",
                if (month.missing.isEmpty()) "workdays this month" else "${month.missing.size} missed this month"),
            Triple("Usual arrival", fmtClock(stats.usualArrivalMin, use24h), "last 10 days"),
            Triple("Usual departure", fmtClock(stats.usualDepartureMin(running != null), use24h), "last 10 days"),
        )
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (label, value, sub) ->
                    SectionCard(modifier = Modifier.weight(1f)) {
                        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (state.mode == TrackingMode.FALLBACK) {
            Text(
                "This phone has no Google Play services, so areas are checked every 15 minutes. Arrival and leaving times are accurate to about 15 minutes.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ------------------------------------------------------------------ setup --

private fun granted(context: Context, perm: String) =
    ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

private fun ignoringBatteryOpt(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

/**
 * Everything needed for automatic tracking, in order. Shown until done.
 * The location explanation (Google Play's "prominent disclosure") is always
 * shown before Android's own permission prompts.
 */
@Composable
private fun SetupCard(state: UiState, vm: MainViewModel, openAreas: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }

    val location = remember(refresh) { AreaMonitor.hasPreciseLocation(context) }
    val background = remember(refresh) { AreaMonitor.hasBackgroundLocation(context) }
    val notifications = remember(refresh) { Build.VERSION.SDK_INT < 33 || granted(context, Manifest.permission.POST_NOTIFICATIONS) }
    val battery = remember(refresh) { ignoringBatteryOpt(context) }
    val hasArea = state.places.any { it.enabled }

    var disclosure by remember { mutableStateOf(false) }
    val fgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refresh++; vm.resync("location permission changed")
    }
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        refresh++; vm.resync("background location changed")
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }

    // Re-arm monitoring whenever the app comes back with everything granted.
    LaunchedEffect(location, background, hasArea) { if (location && hasArea) vm.resync("app opened") }

    val onOpenOnly = state.settings.trackOnOpenOnly
    if (location && (background || onOpenOnly) && notifications && (battery || onOpenOnly) && hasArea) return

    if (disclosure) {
        AlertDialog(
            onDismissRequest = { disclosure = false },
            title = { Text("Location in the background") },
            text = {
                Text(
                    "Uptime Loom uses your location, including when the app is closed or not in use, " +
                        "to notice when you arrive at and leave the work areas you set. That's how it starts " +
                        "and stops counting your work time automatically.\n\n" +
                        "Your location stays on this phone. It is never uploaded or shared."
                )
            },
            confirmButton = {
                Button(onClick = {
                    disclosure = false
                    if (!location) fgLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    else if (Build.VERSION.SDK_INT >= 29) bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { disclosure = false }) { Text("Not now") } },
        )
    }

    SectionCard(
        title = "Finish setting up",
        subtitle = "A few one-time steps so tracking starts and stops by itself.",
    ) {
        SetupStep(
            done = location, title = "Allow location",
            detail = "Choose \"Precise\" so arriving and leaving are noticed reliably.",
            action = "Allow",
        ) { disclosure = true }
        if (!onOpenOnly) {
            SetupStep(
                done = background, enabled = location, title = "Allow location all the time",
                detail = if (Build.VERSION.SDK_INT >= 30) "On the next screen choose \"Allow all the time\". This is what makes counting automatic."
                else "Needed while the app is closed.",
                action = "Allow",
            ) { disclosure = true }
            if (location && !background) {
                TextButton(
                    onClick = { vm.updateSettings { it.copy(trackOnOpenOnly = true) } },
                    modifier = Modifier.padding(start = 26.dp),
                ) { Text("I'd rather open the app myself") }
            }
        }
        SetupStep(done = hasArea, title = "Add your work area", detail = "Your office, campus or anywhere else that counts as work.", action = "Add") { openAreas() }
        if (!onOpenOnly) SetupStep(
            done = battery, title = "Don't restrict in the background",
            detail = "Some phones stop apps in the background. Find Uptime Loom in the list and choose \"Don't optimise\" / \"Unrestricted\".",
            action = "Open",
        ) {
            runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                .onFailure {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            SetupStep(done = notifications, title = "Notifications (optional)", detail = "A quiet note when tracking starts or stops.", action = "Allow") {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

/**
 * Shown while counting depends on opening the app: either chosen in
 * Settings, or because location isn't allowed all the time.
 */
@Composable
private fun OnOpenModeNote(state: UiState, vm: MainViewModel) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val location = remember(refresh) { AreaMonitor.hasForegroundLocation(context) }
    val checking by vm.checking.collectAsStateWithLifecycle()
    // Only when chosen in Settings. (If location simply isn't allowed all the
    // time, the setup card explains it and offers this mode.) Needs an area
    // and basic location access first, which the setup card also covers.
    if (!state.settings.trackOnOpenOnly || !location || state.places.none { it.enabled }) return

    SectionCard {
        Text("Counting when you open the app", style = MaterialTheme.typography.titleSmall)
        Text(
            if (checking) "Checking your location…"
            else "Open Uptime Loom when you arrive and again after you leave. Time in between is counted.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { vm.updateSettings { it.copy(trackOnOpenOnly = false) } }, modifier = Modifier.padding(top = 2.dp)) {
            Text("Switch to automatic")
        }
    }
}

@Composable
private fun SetupStep(done: Boolean, title: String, detail: String, action: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (done) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked, contentDescription = null,
            tint = if (done) Tone.good else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!done) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!done) TextButton(onClick = onClick, enabled = enabled) { Text(action) }
    }
}
