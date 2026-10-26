package app.orbit.vpn.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** Brand green from the design mockup. */
val Brand = Color(0xFF19B46B)
val BrandDark = Color(0xFF2FD486)
val Upload = Color(0xFF2F80ED)

private val LightColors = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3F7EC),
    onPrimaryContainer = Color(0xFF0B5A35),
    secondaryContainer = Color(0xFFE3F7EC),
    onSecondaryContainer = Color(0xFF0B5A35),
    background = Color(0xFFF4F6F8),
    onBackground = Color(0xFF15191E),
    surface = Color(0xFFF4F6F8),
    onSurface = Color(0xFF15191E),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFEDF0F3),
    surfaceContainerHighest = Color(0xFFE7EBEF),
    surfaceVariant = Color(0xFFE9EDF1),
    onSurfaceVariant = Color(0xFF6B7480),
    outline = Color(0xFFD5DBE1),
    outlineVariant = Color(0xFFE6EAEE),
)

private val DarkColors = darkColorScheme(
    primary = BrandDark,
    onPrimary = Color(0xFF00391E),
    primaryContainer = Color(0xFF103A27),
    onPrimaryContainer = Color(0xFFB4F1CF),
    secondaryContainer = Color(0xFF103A27),
    onSecondaryContainer = Color(0xFFB4F1CF),
    background = Color(0xFF0F1215),
    onBackground = Color(0xFFE6E9EC),
    surface = Color(0xFF0F1215),
    onSurface = Color(0xFFE6E9EC),
    surfaceContainerLowest = Color(0xFF171B20),
    surfaceContainerLow = Color(0xFF171B20),
    surfaceContainer = Color(0xFF171B20),
    surfaceContainerHigh = Color(0xFF1F242A),
    surfaceContainerHighest = Color(0xFF262C33),
    surfaceVariant = Color(0xFF242A31),
    onSurfaceVariant = Color(0xFF9AA3AD),
    outline = Color(0xFF39414A),
    outlineVariant = Color(0xFF2A3037),
)

@Composable
fun OrbitTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = Shapes(
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(20.dp),
        ),
        content = content,
    )
}
