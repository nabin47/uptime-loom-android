package io.github.nabin47.uptimeloom.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.nabin47.uptimeloom.Graph
import io.github.nabin47.uptimeloom.data.AppSettings
import io.github.nabin47.uptimeloom.data.DayOff
import io.github.nabin47.uptimeloom.data.EventLog
import io.github.nabin47.uptimeloom.data.Place
import io.github.nabin47.uptimeloom.data.Presence
import io.github.nabin47.uptimeloom.data.Session
import io.github.nabin47.uptimeloom.stats.Span
import io.github.nabin47.uptimeloom.stats.Stats
import io.github.nabin47.uptimeloom.stats.StatsConfig
import io.github.nabin47.uptimeloom.tracking.AreaMonitor
import io.github.nabin47.uptimeloom.tracking.TrackingMode
import io.github.nabin47.uptimeloom.widget.TodayWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

data class UiState(
    val loaded: Boolean = false,
    val settings: AppSettings = AppSettings.defaults(),
    val places: List<Place> = emptyList(),
    val sessions: List<Session> = emptyList(),
    val daysOff: Map<LocalDate, DayOff> = emptyMap(),
    val presence: List<Presence> = emptyList(),
    val stats: Stats? = null,
    val mode: TrackingMode = TrackingMode.GEOFENCING,
) {
    val running: Session? get() = sessions.lastOrNull { it.end == null }
    fun placeName(id: Long?) = places.firstOrNull { it.id == id }?.name
}

private data class Inputs(
    val settings: AppSettings, val places: List<Place>, val sessions: List<Session>,
    val daysOff: List<DayOff>, val presence: List<Presence>,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    init { Graph.init(app) }
    private val db = Graph.db
    private val tracker = Graph.tracker

    /** Re-computes stats when data changes and once a minute (for the running session). */
    private val minuteTicks = flow { while (true) { emit(System.currentTimeMillis()); delay(60_000) } }

    private val inputs = combine(
        Graph.settings.flow, db.places().observeAll(), db.sessions().observeAll(),
        db.daysOff().observeAll(), db.presence().observeAll(),
    ) { s, p, se, d, pr -> Inputs(s, p, se, d, pr) }

    val state: StateFlow<UiState> = combine(inputs, minuteTicks) { i, now ->
        val offDates = i.daysOff.mapNotNull { d -> runCatching { LocalDate.parse(d.date) }.getOrNull()?.let { it to d } }.toMap()
        val stats = Stats(
            spans = i.sessions.map { Span(it.start, it.end) },
            daysOff = offDates.keys,
            cfg = StatsConfig(i.settings.weekStart, i.settings.weekend, i.settings.dailyTargetMs, i.settings.workStartMin, i.settings.workEndMin),
            now = now,
            zone = ZoneId.systemDefault(),
        )
        UiState(true, i.settings, i.places, i.sessions, offDates, i.presence, stats, AreaMonitor.mode(getApplication<Application>()))
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    val events = db.events().observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<EventLog>())

    // ----------------------------------------------------------------- actions --

    fun checkIn() = viewModelScope.launch { tracker.checkIn() }
    fun checkOut() = viewModelScope.launch { tracker.checkOut() }

    fun savePlace(place: Place) = viewModelScope.launch {
        if (place.id == 0L) db.places().insert(place) else db.places().update(place)
        if (!place.enabled) tracker.forgetPlace(place.id)
        resync("area saved")
    }

    fun deletePlace(place: Place) = viewModelScope.launch {
        tracker.forgetPlace(place.id)
        db.places().delete(place.id)
        db.presence().delete(place.id)
        resync("area deleted")
    }

    fun saveSession(session: Session) = viewModelScope.launch {
        if (session.id == 0L) db.sessions().insert(session) else db.sessions().update(session.copy(edited = true))
        refreshWidget()
    }

    fun deleteSession(id: Long) = viewModelScope.launch {
        db.sessions().delete(id)
        refreshWidget()
    }

    fun setDayOff(date: LocalDate, note: String) = viewModelScope.launch {
        db.daysOff().upsert(DayOff(date.toString(), note))
    }

    fun removeDayOff(date: LocalDate) = viewModelScope.launch { db.daysOff().delete(date.toString()) }

    fun updateSettings(transform: (AppSettings) -> AppSettings) = viewModelScope.launch {
        val before = Graph.settings.current()
        Graph.settings.update(transform)
        val after = Graph.settings.current()
        if (before.arriveDelayMin != after.arriveDelayMin) resync("arrival delay changed")
    }

    /** Re-arm area monitoring (after permissions change, areas edited...). */
    fun resync(reason: String) = viewModelScope.launch(Dispatchers.IO) {
        AreaMonitor.sync(getApplication<Application>(), reason)
    }

    fun exportCsv(uri: Uri, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val s = state.value
                val zone = ZoneId.systemDefault()
                val sb = StringBuilder("Date,Day,Type,Start,End,Hours,Area,Note\n")
                fun q(v: String) = "\"" + v.replace("\"", "\"\"") + "\""
                fun ts(t: Long) = java.time.Instant.ofEpochMilli(t).atZone(zone).toLocalDateTime().toString().replace('T', ' ').take(16)
                for (se in s.sessions) {
                    val d = java.time.Instant.ofEpochMilli(se.start).atZone(zone).toLocalDate()
                    val end = se.end ?: System.currentTimeMillis()
                    sb.append(listOf(d, d.dayOfWeek.name.take(3), if (se.source == Session.SOURCE_MANUAL) "manual" else if (se.edited) "edited" else "auto",
                        ts(se.start), if (se.end == null) "running" else ts(end), "%.2f".format(java.util.Locale.ROOT, (end - se.start) / 3_600_000.0),
                        q(s.placeName(se.placeId) ?: ""), q(se.note)).joinToString(",")).append('\n')
                }
                for ((d, off) in s.daysOff.toSortedMap()) {
                    sb.append(listOf(d, d.dayOfWeek.name.take(3), "day off", "", "", "", "", q(off.note)).joinToString(",")).append('\n')
                }
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { it.write(sb.toString().toByteArray()) }
                    ?: error("couldn't open file")
            }.isSuccess
        }
        onDone(ok)
    }

    private suspend fun refreshWidget() = runCatching { TodayWidget.refresh(getApplication<Application>()) }
}
