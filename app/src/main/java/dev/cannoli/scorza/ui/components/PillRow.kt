package dev.cannoli.scorza.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.ui.theme.GrayText
import dev.cannoli.scorza.ui.theme.LocalCannoliColors

val screenPadding = 20.dp
val pillInternalH = 14.dp

/**
 * A row that highlights with a pill when selected.
 * Use the content slot for custom inner content (e.g. marquee text).
 */
@Composable
fun PillRow(
    isSelected: Boolean,
    verticalPadding: Dp = 8.dp,
    content: @Composable () -> Unit
) {
    val colors = LocalCannoliColors.current
    if (isSelected) {
        Box(
            modifier = Modifier
                .padding(vertical = 2.dp)
                .clip(RoundedCornerShape(50))
                .background(colors.highlight)
                .padding(horizontal = pillInternalH, vertical = verticalPadding)
        ) {
            content()
        }
    } else {
        Box(
            modifier = Modifier
                .padding(vertical = 2.dp)
                .padding(start = pillInternalH, top = verticalPadding, bottom = verticalPadding)
        ) {
            content()
        }
    }
}

/**
 * Simple pill row with a text label.
 */
@Composable
fun PillRowText(
    label: String,
    isSelected: Boolean,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    verticalPadding: Dp,
    showReorderIcon: Boolean = false,
    checkState: Boolean? = null
) {
    val colors = LocalCannoliColors.current
    val textStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = fontSize,
        lineHeight = lineHeight
    )

    PillRow(isSelected = isSelected, verticalPadding = verticalPadding) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (checkState != null) {
                Text(
                    text = if (checkState) "☑" else "☐",
                    style = textStyle,
                    color = if (isSelected) colors.highlightText else colors.text
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            if (showReorderIcon) {
                Text(
                    text = "↕",
                    style = textStyle,
                    color = if (isSelected) colors.highlightText else colors.text
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = label,
                style = textStyle,
                color = if (isSelected) colors.highlightText else colors.text
            )
        }
    }
}

/**
 * Pill row with a label and a trailing value (for settings).
 */
@Composable
fun PillRowKeyValue(
    label: String,
    value: String,
    isSelected: Boolean,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    verticalPadding: Dp,
    swatchColor: Color? = null
) {
    val colors = LocalCannoliColors.current
    val textStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = fontSize,
        lineHeight = lineHeight
    )
    val valueStyle = MaterialTheme.typography.bodyMedium.copy(
        fontSize = (fontSize.value * 0.72f).sp
    )

    if (isSelected) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .clip(RoundedCornerShape(50))
                .background(colors.highlight)
                .padding(horizontal = pillInternalH, vertical = verticalPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = textStyle,
                color = colors.highlightText,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            if (swatchColor != null) {
                Box(
                    modifier = Modifier
                        .size((fontSize.value * 0.7f).dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(swatchColor)
                        .border(1.dp, colors.highlightText.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = value,
                style = valueStyle,
                color = colors.highlightText.copy(alpha = 0.5f),
                maxLines = 1
            )
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .padding(start = pillInternalH, top = verticalPadding, bottom = verticalPadding, end = pillInternalH),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = textStyle,
                color = colors.text,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            if (swatchColor != null) {
                Box(
                    modifier = Modifier
                        .size((fontSize.value * 0.7f).dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(swatchColor)
                        .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = value,
                style = valueStyle,
                color = GrayText,
                maxLines = 1
            )
        }
    }
}
