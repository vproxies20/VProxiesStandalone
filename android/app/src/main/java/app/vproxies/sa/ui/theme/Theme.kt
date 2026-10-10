package app.vproxies.sa.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import app.vproxies.sa.core.ThemeMode

// Palette taken from the Windows edition (App.xaml / ApplyTheme).
object VColors {
    val Cyan = Color(0xFF13E5FF)
    val Blue = Color(0xFF087BFF)
    val Purple = Color(0xFF8B35FF)
    val Success = Color(0xFF22C985)
    val SuccessText = Color(0xFF62E6A6)
    val Danger = Color(0xFFE84B70)
    val DangerText = Color(0xFFFF9BA8)
    val Warn = Color(0xFFFFC857)
    val LogTime = Color(0xFF7186A3)
    val LogInfo = Color(0xFF45DFA2)
    val LogError = Color(0xFFFF6689)
    val Brand = Brush.linearGradient(listOf(Cyan, Blue, Purple))
}

@Immutable
data class Surfaces(
    val page: Color,
    val sidebar: Color,
    val panel: Color,
    val altPanel: Color,
    val input: Color,
    val line: Color,
    val text: Color,
    val muted: Color,
    val logBackground: Color,
    val logText: Color,
    val isDark: Boolean,
)

private val DarkSurfaces = Surfaces(
    page = Color(0xFF141B27), sidebar = Color(0xFF101822), panel = Color(0xFF192230), altPanel = Color(0xFF1D2735),
    input = Color(0xFF171F2C), line = Color(0xFF303B4B), text = Color(0xFFF5F7FA), muted = Color(0xFF8FA3BE),
    logBackground = Color(0xFF0E141E), logText = Color(0xFFD8E5F5), isDark = true,
)

private val LightSurfaces = Surfaces(
    page = Color(0xFFE8EDF3), sidebar = Color(0xFFF7F9FC), panel = Color(0xFFFFFFFF), altPanel = Color(0xFFF3F6FA),
    input = Color(0xFFF7F9FC), line = Color(0xFFCBD5E1), text = Color(0xFF182230), muted = Color(0xFF5F6F82),
    logBackground = Color(0xFF0E141E), logText = Color(0xFFD8E5F5), isDark = false,
)

val LocalSurfaces = staticCompositionLocalOf { DarkSurfaces }

@Composable
fun VProxiesTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> isSystemInDarkTheme()
    }
    val s = if (dark) DarkSurfaces else LightSurfaces
    val scheme = if (dark) {
        darkColorScheme(
            primary = Color(0xFF10CFE5), onPrimary = Color(0xFF04131A),
            secondary = VColors.Purple, onSecondary = Color.White,
            tertiary = VColors.Blue,
            background = s.page, onBackground = s.text,
            surface = s.panel, onSurface = s.text,
            surfaceVariant = s.altPanel, onSurfaceVariant = s.muted,
            surfaceContainer = s.panel, surfaceContainerHigh = s.altPanel, surfaceContainerHighest = s.altPanel,
            surfaceContainerLow = s.panel, surfaceContainerLowest = s.page,
            outline = s.line, outlineVariant = s.line,
            error = VColors.Danger, onError = Color.White,
            primaryContainer = Color(0xFF163D48), onPrimaryContainer = Color(0xFFBDF6FF),
            secondaryContainer = Color(0xFF2A2350), onSecondaryContainer = Color(0xFFE2D5FF),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF0A8FB0), onPrimary = Color.White,
            secondary = Color(0xFF6E2BE0), onSecondary = Color.White,
            tertiary = VColors.Blue,
            background = s.page, onBackground = s.text,
            surface = s.panel, onSurface = s.text,
            surfaceVariant = s.altPanel, onSurfaceVariant = s.muted,
            surfaceContainer = s.panel, surfaceContainerHigh = s.altPanel, surfaceContainerHighest = s.altPanel,
            surfaceContainerLow = s.panel, surfaceContainerLowest = s.page,
            outline = s.line, outlineVariant = s.line,
            error = Color(0xFFC5254F), onError = Color.White,
            primaryContainer = Color(0xFFD3F5FC), onPrimaryContainer = Color(0xFF00363F),
            secondaryContainer = Color(0xFFEADDFF), onSecondaryContainer = Color(0xFF25005A),
        )
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalSurfaces provides s) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
