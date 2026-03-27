package dev.cannoli.scorza.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
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
    val textMeasurer = rememberTextMeasurer()

    MarqueeEffect(scrollState, active = true, key = text, initialDelayMs = 800)

    val scaledFontSizeSp = fontSize.value * 1.3f

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val containerWidthPx = with(density) { maxWidth.toPx() }
        val paddingPx = with(density) { (screenPadding + pillInternalH).toPx() }
        val gapPx = with(density) { 16.dp.toPx() }
        val availableWidthPx = if (statusBarLeftPx < Int.MAX_VALUE) {
            (statusBarLeftPx - paddingPx - gapPx).coerceAtLeast(0f)
        } else {
            containerWidthPx
        }

        val resolvedFontSize = remember(text, scaledFontSizeSp, availableWidthPx) {
            val baseStyle = TextStyle(fontSize = scaledFontSizeSp.sp, lineHeight = lineHeight)
            val measured = textMeasurer.measure(text, baseStyle, constraints = Constraints())
            if (measured.size.width > availableWidthPx && scaledFontSizeSp > fontSize.value) {
                val scale = availableWidthPx / measured.size.width
                (scaledFontSizeSp * scale - 0.2f).coerceAtLeast(fontSize.value)
            } else {
                scaledFontSizeSp
            }
        }

        val availableWidthDp = with(density) { availableWidthPx.toDp() }

        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = resolvedFontSize.sp,
                lineHeight = lineHeight
            ),
            color = LocalCannoliColors.current.text,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .padding(start = pillInternalH)
                .then(
                    if (statusBarLeftPx < Int.MAX_VALUE) Modifier.widthIn(max = availableWidthDp)
                    else Modifier
                )
                .horizontalScroll(scrollState)
        )
    }
}
