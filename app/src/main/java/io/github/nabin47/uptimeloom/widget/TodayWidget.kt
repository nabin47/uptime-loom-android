package io.github.nabin47.uptimeloom.widget

import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import io.github.nabin47.uptimeloom.Graph
import io.github.nabin47.uptimeloom.stats.Span
import io.github.nabin47.uptimeloom.stats.Stats
import io.github.nabin47.uptimeloom.stats.StatsConfig
import io.github.nabin47.uptimeloom.stats.fmtHM
import io.github.nabin47.uptimeloom.ui.MainActivity
import java.time.LocalDate

private data class WidgetData(val today: String, val status: String, val left: String)

/** Home-screen widget: today's hours, where you are, what's left this week. */
class TodayWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = load(context)
        provideContent {
            GlanceTheme {
                Column(
                    GlanceModifier.fillMaxSize()
                        .background(GlanceTheme.colors.widgetBackground)
                        .cornerRadius(20.dp)
                        .padding(14.dp)
                        .clickable(actionStartActivity<MainActivity>()),
                ) {
                    Text(data.status, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
                    Text(data.today, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 26.sp, fontWeight = FontWeight.Bold))
                    Text(data.left, style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 12.sp))
                }
            }
        }
    }

    private suspend fun load(context: Context): WidgetData {
        Graph.init(context)
        val s = Graph.settings.current()
        val sessions = Graph.db.sessions().all()
        val daysOff = Graph.db.daysOff().all().mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }.toSet()
        val stats = Stats(
            sessions.map { Span(it.start, it.end) }, daysOff,
            StatsConfig(s.weekStart, s.weekend, s.dailyTargetMs, s.workStartMin, s.workEndMin),
            System.currentTimeMillis(),
        )
        val running = sessions.lastOrNull { it.end == null }
        val presence = Graph.db.presence().all()
        val status = when {
            running == null -> "Not at work"
            presence.isNotEmpty() -> "At " + (Graph.db.places().byId(presence.first().placeId)?.name ?: "work")
            else -> "Working"
        }
        val week = stats.week()
        val left = when {
            s.dailyTargetMs <= 0 -> "This week ${fmtHM(week.worked)}"
            week.stillNeeded <= 0 -> "Week target reached"
            else -> "${fmtHM(week.stillNeeded)} left this week"
        }
        return WidgetData("Today ${fmtHM(stats.todayMs)}", status, left)
    }

    companion object {
        suspend fun refresh(context: Context) = TodayWidget().updateAll(context)
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}
