package dev.cannoli.scorza.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val CannoliColorScheme = darkColorScheme(
    primary = White,
    onPrimary = Black,
    background = Black,
    surface = Black,
    onBackground = White,
    onSurface = White,
    surfaceVariant = DarkGray,
    onSurfaceVariant = GrayText
)

@Composable
fun CannoliTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CannoliColorScheme,
        typography = buildTypography(),
        content = content
    )
}
