package dev.cannoli.scorza.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.ui.theme.LocalCannoliColors


val LocalStatusBarLeftEdge = staticCompositionLocalOf<MutableIntState> { mutableIntStateOf(Int.MAX_VALUE) }

@Composable
fun ScreenTitle(
    text: String,
    fontSize: TextUnit,
    lineHeight: TextUnit
) {
    val scrollState = rememberScrollState()
    val statusBarLeftPx = LocalStatusBarLeftEdge.current.intValue
    val density = LocalDensity.current

    MarqueeEffect(scrollState, active = true, key = text, initialDelayMs = 800)

    val scaledFontSizeSp = fontSize.value * 1.3f
    var adjustedFontSizeSp by remember(text, scaledFontSizeSp, statusBarLeftPx) {
        mutableStateOf(scaledFontSizeSp)
    }
    var fontSettled by remember(text, scaledFontSizeSp, statusBarLeftPx) {
        mutableStateOf(false)
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val containerWidthPx = with(density) { maxWidth.toPx() }
        val paddingPx = with(density) { (screenPadding + pillInternalH).toPx() }
        val availableWidthPx = if (statusBarLeftPx < Int.MAX_VALUE) {
            (statusBarLeftPx - paddingPx).coerceAtLeast(0f)
        } else {
            containerWidthPx
        }

        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = adjustedFontSizeSp.sp,
                lineHeight = lineHeight
            ),
            color = LocalCannoliColors.current.text,
            maxLines = 1,
            softWrap = false,
            onTextLayout = { result ->
                if (!fontSettled && result.size.width > availableWidthPx && adjustedFontSizeSp > fontSize.value) {
                    val scale = availableWidthPx / result.size.width
                    adjustedFontSizeSp = (adjustedFontSizeSp * scale - 0.2f).coerceAtLeast(fontSize.value)
                    fontSettled = true
                }
            },
            modifier = Modifier
                .padding(start = pillInternalH)
                .horizontalScroll(scrollState)
        )
    }
}
