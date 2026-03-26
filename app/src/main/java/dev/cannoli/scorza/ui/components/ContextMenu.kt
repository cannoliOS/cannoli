package dev.cannoli.scorza.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.cannoli.scorza.R

private val menuPillH = 14.dp

/**
 * Full-screen dark overlay container with centered content and a bottom bar.
 */
@Composable
fun OverlayScrim(
    bottomBar: @Composable () -> Unit = {},
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(24.dp)
        ) {
            content()
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp)
        ) {
            bottomBar()
        }
    }
}

/**
 * Overlay with a title and a list of selectable options (pill highlight).
 */
@Composable
fun MenuOverlay(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    checkedIndices: Set<Int>? = null,
    leftItems: List<Pair<String, String>> = listOf("B" to stringResource(R.string.label_back)),
    rightItems: List<Pair<String, String>> = listOf("A" to stringResource(R.string.label_select))
) {
    OverlayScrim(
        bottomBar = { BottomBar(leftItems = leftItems, rightItems = rightItems) }
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        options.forEachIndexed { index, option ->
            val isSelected = index == selectedIndex
            val textColor = if (isSelected) Color.Black else Color.White
            val content: @Composable () -> Unit = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (checkedIndices != null) {
                        Text(
                            text = if (index in checkedIndices) "☑" else "☐",
                            style = MaterialTheme.typography.bodyLarge,
                            color = textColor
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = option,
                        style = MaterialTheme.typography.bodyLarge,
                        color = textColor
                    )
                }
            }

            if (isSelected) {
                Box(
                    modifier = Modifier
                        .padding(vertical = 2.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White)
                        .padding(horizontal = menuPillH, vertical = 8.dp)
                ) { content() }
            } else {
                Box(
                    modifier = Modifier
                        .padding(vertical = 2.dp)
                        .padding(horizontal = menuPillH, vertical = 8.dp)
                ) { content() }
            }
        }
    }
}

/**
 * Overlay that shows a simple message with a B-to-close bottom bar.
 */
@Composable
fun MessageOverlay(
    message: String,
    buttonLabel: String = stringResource(R.string.label_close)
) {
    OverlayScrim(
        bottomBar = { LegendPill(button = "B", label = buttonLabel) }
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White
        )
    }
}

/**
 * Overlay with a message and A/B confirm/cancel bottom bar.
 */
@Composable
fun ConfirmOverlay(
    message: String,
    cancelLabel: String = stringResource(R.string.label_cancel),
    confirmLabel: String = stringResource(R.string.label_delete)
) {
    OverlayScrim(
        bottomBar = {
            BottomBar(
                leftItems = listOf("B" to cancelLabel),
                rightItems = listOf("A" to confirmLabel)
            )
        }
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White
        )
    }
}
