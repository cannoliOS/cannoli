package dev.cannoli.launcher.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.launcher.ui.theme.Nunito

private val outerPillColor = Color.White.copy(alpha = 0.15f)
private val innerPillColor = Color.White.copy(alpha = 0.30f)

@Composable
fun LegendPill(button: String, label: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(outerPillColor)
            .padding(start = 5.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Inner pill with button name
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(innerPillColor)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = button,
                style = TextStyle(
                    fontFamily = Nunito,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color.White
                )
            )
        }

        // Action label
        Text(
            text = label,
            style = TextStyle(
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = Color.White
            )
        )
    }
}

@Composable
fun BottomBar(
    modifier: Modifier = Modifier,
    leftItems: List<Pair<String, String>> = emptyList(),
    rightItems: List<Pair<String, String>> = emptyList()
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            leftItems.forEach { (button, label) ->
                LegendPill(button = button, label = label)
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            rightItems.forEach { (button, label) ->
                LegendPill(button = button, label = label)
            }
        }
    }
}
