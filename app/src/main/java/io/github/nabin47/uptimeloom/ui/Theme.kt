package io.github.nabin47.uptimeloom.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import io.github.nabin47.uptimeloom.data.ThemeMode

private val Light = lightColorScheme(
    primary = Color(0xFF3866D6), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE3FF), onPrimaryContainer = Color(0xFF0B1E4D),
    secondary = Color(0xFF2F9E63), onSecondary = Color.White,
    tertiary = Color(0xFFB7791F),
    background = Color(0xFFF6F7F9), surface = Color(0xFFF6F7F9),
    surfaceContainer = Color(0xFFFFFFFF), surfaceContainerHigh = Color(0xFFF0F2F5),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF8DAEFF), onPrimary = Color(0xFF0B1E4D),
    primaryContainer = Color(0xFF22386E), onPrimaryContainer = Color(0xFFDCE3FF),
    secondary = Color(0xFF5BC98A), onSecondary = Color(0xFF00391C),
    tertiary = Color(0xFFE0A95B),
    background = Color(0xFF0B0D10), surface = Color(0xFF0B0D10),
    surfaceContainer = Color(0xFF15181D), surfaceContainerHigh = Color(0xFF1A1E24),
)

@Composable
fun UptimeTheme(mode: ThemeMode, dynamic: Boolean, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Semantic colours used by the stats (good = ahead/on time, warn = behind/late). */
object Tone {
    private val isDark @Composable get() = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val good @Composable get() = if (isDark) Color(0xFF5BC98A) else Color(0xFF2F9E63)
    val warn @Composable get() = if (isDark) Color(0xFFE0A95B) else Color(0xFFB7791F)
}
