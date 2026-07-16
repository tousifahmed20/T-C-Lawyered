package dev.tclawyered.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Google brand palette. Fixed (not Material-You dynamic) — the brief asks for Google colours.
val GoogleBlue = Color(0xFF4285F4)
val GoogleRed = Color(0xFFEA4335)
val GoogleYellow = Color(0xFFFBBC04)
val GoogleGreen = Color(0xFF34A853)
private val InkOnLight = Color(0xFF1A1D24)

private val Light = lightColorScheme(
    primary = GoogleBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7E3FF),
    onPrimaryContainer = Color(0xFF001A41),
    secondary = GoogleGreen,
    onSecondary = Color.White,
    tertiary = GoogleRed,
    onTertiary = Color.White,
    background = Color(0xFFFDFBFF),
    surface = Color(0xFFFDFBFF),
    surfaceVariant = Color(0xFFEEF1F8),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFAAC7FF),
    onPrimary = Color(0xFF002E69),
    primaryContainer = Color(0xFF284777),
    onPrimaryContainer = Color(0xFFD7E3FF),
    secondary = Color(0xFF7FD89B),
    onSecondary = Color(0xFF00391C),
    tertiary = Color(0xFFFFB4AB),
    onTertiary = Color(0xFF690005),
)

@Composable
fun TcTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        content = content,
    )
}

/** Google's four-dot motif — a cheap bit of brand character under the title. */
@Composable
fun GoogleDots(modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(GoogleBlue, GoogleRed, GoogleYellow, GoogleGreen).forEach { c ->
            Surface(color = c, shape = CircleShape, modifier = Modifier.size(9.dp)) {}
        }
    }
}

/**
 * The one button used across the app: full-width, fixed 56dp height, rounded.
 * Every call gets the same footprint, so rows and stacks line up automatically.
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
            contentColor = content ?: Color.White,
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
