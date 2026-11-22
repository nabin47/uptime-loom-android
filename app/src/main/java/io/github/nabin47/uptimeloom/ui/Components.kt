package io.github.nabin47.uptimeloom.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun SectionCard(
    title: String? = null,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(16.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (title != null || subtitle != null) Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/** Turns "text with **bold** parts" into styled text. */
fun boldMarkup(text: String, highlight: Color): AnnotatedString = buildAnnotatedString {
    val parts = text.split("**")
    parts.forEachIndexed { i, part ->
        if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = highlight)) { append(part) }
        else append(part)
    }
}

/** Date/time formatting that follows the phone's language and 12/24h setting. */
object Fmt {
    private val zone get() = ZoneId.systemDefault()
    fun is24h(context: Context) = android.text.format.DateFormat.is24HourFormat(context)
    fun time(context: Context, t: Long): String =
        android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(t))
    fun dayLabel(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()))
    fun dayShort(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern("EEE d", Locale.getDefault()))
    fun dateMedium(d: LocalDate): String = d.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    fun date(t: Long): LocalDate = Instant.ofEpochMilli(t).atZone(zone).toLocalDate()
    fun monthYear(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault()))
}
