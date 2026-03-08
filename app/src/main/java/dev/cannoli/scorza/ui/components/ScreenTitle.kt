package dev.cannoli.scorza.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.ui.theme.LocalCannoliColors

@Composable
fun ScreenTitle(
    text: String,
    fontSize: TextUnit,
    lineHeight: TextUnit
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge.copy(
            fontSize = fontSize * 1.2f,
            lineHeight = lineHeight * 1.2f
        ),
        color = LocalCannoliColors.current.text,
        modifier = Modifier.padding(start = 2.dp)
    )
}
