package io.github.nabin47.uptimeloom.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek
import java.time.temporal.WeekFields
import java.util.Locale

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val weekStart: DayOfWeek,
    val weekend: Set<DayOfWeek>,
    val weeklyTargetH: Float,
    /** Minutes after midnight, or null when not set. */
    val workStartMin: Int?,
    val workEndMin: Int?,
    /** Inside an area this long before it counts as arriving. */
    val arriveDelayMin: Int,
    /** Outside all areas this long before it counts as leaving. */
    val leaveDelayMin: Int,
    val notifications: Boolean,
    val theme: ThemeMode,
    val dynamicColor: Boolean,
    val onboarded: Boolean,
) {
    val workdaysPerWeek: Int get() = 7 - weekend.size
    val dailyTargetMs: Long
        get() = if (weeklyTargetH > 0 && workdaysPerWeek > 0) (weeklyTargetH * 3_600_000f / workdaysPerWeek).toLong() else 0L

    companion object {
        fun defaults(locale: Locale = Locale.getDefault()): AppSettings {
            val wf = WeekFields.of(locale)
            val weekend = when (locale.country) {
                // Friday-Saturday weekend
                "BD", "DZ", "BH", "EG", "IQ", "IL", "JO", "KW", "LY", "MV", "OM", "QA", "SA", "SD", "SY", "AE", "YE" ->
                    setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)
                "AF", "IR" -> setOf(DayOfWeek.FRIDAY)
                "NP" -> setOf(DayOfWeek.SATURDAY)
                else -> setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
            }
            return AppSettings(
                weekStart = wf.firstDayOfWeek,
                weekend = weekend,
                weeklyTargetH = 40f,
                workStartMin = 9 * 60,
                workEndMin = 17 * 60,
                arriveDelayMin = 3,
                leaveDelayMin = 5,
                notifications = true,
                theme = ThemeMode.SYSTEM,
                dynamicColor = true,
                onboarded = false,
            )
        }
    }
}

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepo(private val context: Context) {
    private object K {
        val weekStart = intPreferencesKey("week_start")          // 1..7 (ISO)
        val weekend = stringPreferencesKey("weekend")            // "6,7"
        val target = floatPreferencesKey("weekly_target_h")
        val workStart = intPreferencesKey("work_start_min")      // -1 = off
        val workEnd = intPreferencesKey("work_end_min")
        val arrive = intPreferencesKey("arrive_delay_min")
        val leave = intPreferencesKey("leave_delay_min")
        val notifications = booleanPreferencesKey("notifications")
        val theme = stringPreferencesKey("theme")
        val dynamic = booleanPreferencesKey("dynamic_color")
        val onboarded = booleanPreferencesKey("onboarded")
    }

    val flow: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = flow.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val s = transform(prefs.toSettings())
            prefs[K.weekStart] = s.weekStart.value
            prefs[K.weekend] = s.weekend.joinToString(",") { it.value.toString() }
            prefs[K.target] = s.weeklyTargetH
            prefs[K.workStart] = s.workStartMin ?: -1
            prefs[K.workEnd] = s.workEndMin ?: -1
            prefs[K.arrive] = s.arriveDelayMin
            prefs[K.leave] = s.leaveDelayMin
            prefs[K.notifications] = s.notifications
            prefs[K.theme] = s.theme.name
            prefs[K.dynamic] = s.dynamicColor
            prefs[K.onboarded] = s.onboarded
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings.defaults()
        return AppSettings(
            weekStart = this[K.weekStart]?.let { runCatching { DayOfWeek.of(it) }.getOrNull() } ?: d.weekStart,
            weekend = this[K.weekend]?.let { raw ->
                raw.split(',').mapNotNull { it.trim().toIntOrNull()?.takeIf { n -> n in 1..7 }?.let(DayOfWeek::of) }.toSet()
            } ?: d.weekend,
            weeklyTargetH = this[K.target] ?: d.weeklyTargetH,
            workStartMin = this[K.workStart].let { if (it == null) d.workStartMin else it.takeIf { v -> v >= 0 } },
            workEndMin = this[K.workEnd].let { if (it == null) d.workEndMin else it.takeIf { v -> v >= 0 } },
            arriveDelayMin = this[K.arrive] ?: d.arriveDelayMin,
            leaveDelayMin = this[K.leave] ?: d.leaveDelayMin,
            notifications = this[K.notifications] ?: d.notifications,
            theme = this[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.theme,
            dynamicColor = this[K.dynamic] ?: d.dynamicColor,
            onboarded = this[K.onboarded] ?: d.onboarded,
        )
    }
}
