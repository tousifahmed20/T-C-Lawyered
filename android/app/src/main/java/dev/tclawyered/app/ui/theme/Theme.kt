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
private val Light = lightColorScheme(
    primary = Color(0xFF000000),
    onPrimary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF000000),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFEDEDED),
    onSurfaceVariant = Color(0xFF1A1A1A),
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
    outline = Color(0xFF5A5A5A),
)

@Composable
fun TcTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
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
    leading: String? = null,
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
        Text(
            text = if (leading != null) "$leading  $text" else text,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
