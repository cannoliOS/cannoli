package dev.cannoli.scorza.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.R
import dev.cannoli.scorza.ui.theme.COLOR_PRESETS

const val COLOR_GRID_COLS = 4
private val HIGHLIGHT_BORDER = Color(0xFF6366F1)

@Composable
fun ColorPickerOverlay(
    selectedRow: Int,
    selectedCol: Int,
    currentColor: Long
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Preview swatch
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(currentColor))
                    .border(2.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            )

            Spacer(modifier = Modifier.height(20.dp))

            // 4x4 grid
            val rows = COLOR_PRESETS.chunked(COLOR_GRID_COLS)
            rows.forEachIndexed { ri, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEachIndexed { ci, preset ->
                        val isSelected = ri == selectedRow && ci == selectedCol
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .then(
                                    if (isSelected) Modifier.border(3.dp, HIGHLIGHT_BORDER, RoundedCornerShape(8.dp))
                                    else Modifier.border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                                )
                                .background(Color(preset.color)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (preset.color == currentColor) {
                                Text(
                                    text = "●",
                                    color = if (preset.color == 0xFFFFFFFF.toLong() || preset.color == 0xFFFFD54F.toLong()) Color.Black else Color.White,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }
                if (ri < rows.lastIndex) {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Show name of hovered color
            val hoveredIdx = selectedRow * COLOR_GRID_COLS + selectedCol
            val hoveredPreset = COLOR_PRESETS.getOrNull(hoveredIdx)
            if (hoveredPreset != null) {
                Text(
                    text = hoveredPreset.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp)
        ) {
            BottomBar(
                leftItems = listOf("B" to stringResource(R.string.label_back)),
                rightItems = listOf(
                    "X" to "HEX",
                    "A" to stringResource(R.string.label_select)
                )
            )
        }
    }
}

val HEX_KEYS = listOf("0", "1", "2", "3", "4", "5", "6", "7", "←",
                       "8", "9", "A", "B", "C", "D", "E", "F", "↵")
private const val HEX_ROW_SIZE = 9

@Composable
fun HexColorInputOverlay(
    currentHex: String,
    selectedIndex: Int
) {
    val displayHex = "#$currentHex"
    val previewColor = if (currentHex.length == 6) {
        try { Color(0xFF000000 or currentHex.toLong(16)) } catch (_: Exception) { Color.Black }
    } else Color.Black

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Preview swatch + hex display
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(previewColor)
                        .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = displayHex,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 24.sp),
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 2 rows of 9 keys
            for (rowStart in 0 until HEX_KEYS.size step HEX_ROW_SIZE) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (i in rowStart until (rowStart + HEX_ROW_SIZE).coerceAtMost(HEX_KEYS.size)) {
                        val key = HEX_KEYS[i]
                        val isSelected = i == selectedIndex
                        val isAction = key == "←" || key == "↵"
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (isSelected) Color(0xFF6366F1)
                                    else Color.White.copy(alpha = 0.12f)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = key,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = if (isAction) 18.sp else 16.sp
                                ),
                                color = Color.White
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp)
        ) {
            BottomBar(
                leftItems = listOf("B" to stringResource(R.string.label_back)),
                rightItems = emptyList()
            )
        }
    }
}
