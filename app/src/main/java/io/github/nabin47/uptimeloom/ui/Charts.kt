package io.github.nabin47.uptimeloom.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.nabin47.uptimeloom.stats.DayValue

/**
 * Simple bar chart drawn directly (no chart library: small and fast).
 * [colors] per bar; optional [line] (e.g. rolling average) and dashed [target].
 */
@Composable
fun BarChart(
    values: List<Float>,
    colors: List<Color>,
    labels: List<String?>,
    description: String,
    line: List<Float>? = null,
    target: Float? = null,
    height: Int = 140,
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val targetColor = Tone.warn
    val max = maxOf(1e-6f, values.maxOrNull() ?: 0f, line?.maxOrNull() ?: 0f, target ?: 0f)
    Column {
        Canvas(
            Modifier.fillMaxWidth().height(height.dp).semantics { contentDescription = description },
        ) {
            val n = values.size.coerceAtLeast(1)
            val step = size.width / n
            val barW = step * 0.62f
            values.forEachIndexed { i, v ->
                val h = (v / max * size.height).coerceAtLeast(if (v > 0) 2f else 1f)
                drawRoundRect(
                    color = colors.getOrElse(i) { colors.last() },
                    topLeft = Offset(i * step + (step - barW) / 2, size.height - h),
                    size = Size(barW, h),
                    cornerRadius = CornerRadius(barW / 4, barW / 4),
                )
            }
            if (target != null && target > 0) {
                val y = size.height - target / max * size.height
                drawLine(targetColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            }
            if (line != null && line.size > 1) {
                val path = Path()
                line.forEachIndexed { i, v ->
                    val x = i * step + step / 2
                    val y = size.height - v / max * size.height
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, lineColor, style = Stroke(width = 5f, cap = StrokeCap.Round))
            }
        }
        if (labels.any { it != null }) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                labels.forEach { l ->
                    Text(
                        l ?: "", modifier = Modifier.weight(1f), textAlign = TextAlign.Center, maxLines = 1,
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** GitHub-style calendar: one column per week, one row per weekday. */
@Composable
fun CalendarHeatmap(days: List<DayValue>, description: String) {
    val levels = listOf(
        MaterialTheme.colorScheme.surfaceContainerHigh,
        MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
        MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
        MaterialTheme.colorScheme.primary,
    )
    val max = days.maxOfOrNull { it.ms }?.coerceAtLeast(1) ?: 1
    val weeks = days.chunked(7)
    Row(
        Modifier.fillMaxWidth().semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        weeks.forEach { week ->
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                week.forEach { d ->
                    val c = when {
                        d.ms < 0 -> Color.Transparent
                        d.ms == 0L -> levels[0]
                        else -> levels[(1 + (d.ms * 4 / (max + 1)).toInt()).coerceIn(1, 4)]
                    }
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).background(c, RoundedCornerShape(2.dp)))
                }
            }
        }
    }
}
