package dev.tclawyered.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Monochrome, adaptive: black-on-white in light, white-on-black in dark.
// surfaceContainer* are set explicitly so Cards get a defined step off the
// background — M3 resolves filled Card containers against these roles, and
// without them the card boundary collapses (especially in light mode).
private val Light = lightColorScheme(
    primary = Color(0xFF000000),
    onPrimary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF000000),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFEDEDED),
    onSurfaceVariant = Color(0xFF1A1A1A),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F5F5),
    surfaceContainer = Color(0xFFF0F0F0),
    surfaceContainerHigh = Color(0xFFEAEAEA),
    surfaceContainerHighest = Color(0xFFE4E4E4),
    outline = Color(0xFFBDBDBD),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF000000),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF1C1C1C),
    onSurfaceVariant = Color(0xFFE6E6E6),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF141414),
    surfaceContainer = Color(0xFF1A1A1A),
    surfaceContainerHigh = Color(0xFF242424),
    surfaceContainerHighest = Color(0xFF2E2E2E),
    outline = Color(0xFF5A5A5A),
)

/** User's theme preference. SYSTEM follows the OS; LIGHT/DARK force one. */
enum class ThemeMode {
    SYSTEM, LIGHT, DARK;

    companion object {
        fun from(value: String?): ThemeMode = when (value) {
            "light" -> LIGHT
            "dark" -> DARK
            else -> SYSTEM
        }
    }

    val wire: String get() = name.lowercase()
}

@Composable
fun TcTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        content = content,
    )
}

/**
 * The one button used across the app: fixed 56dp height, rounded. Every call
 * gets the same footprint, so rows and stacks line up automatically. Defaults to
 * the theme's primary (black/white); pass [container] for a tonal secondary.
 */
@Composable
fun TcButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    container: Color? = null,
    content: Color? = null,
) {
    val colors: ButtonColors = if (container != null) {
        ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content ?: MaterialTheme.colorScheme.onSurface,
        )
    } else {
        ButtonDefaults.buttonColors()
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = colors,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.height(56.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.titleMedium)
    }
}
