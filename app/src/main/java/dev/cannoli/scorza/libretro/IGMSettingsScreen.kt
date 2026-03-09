package dev.cannoli.scorza.libretro

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.ui.components.BottomBar
import dev.cannoli.scorza.ui.components.List
import dev.cannoli.scorza.ui.components.PillRowKeyValue
import dev.cannoli.scorza.ui.components.PillRowText
import dev.cannoli.scorza.ui.components.ScreenBackground
import dev.cannoli.scorza.ui.components.ScreenTitle
import dev.cannoli.scorza.ui.components.pillItemHeight
import dev.cannoli.scorza.ui.components.screenPadding
import dev.cannoli.scorza.ui.theme.LocalCannoliColors

private val fontSize = 22.sp
private val lineHeight = 32.sp
private val verticalPadding = 8.dp

data class IGMSettingsItem(
    val label: String,
    val value: String? = null
)

@Composable
fun IGMSettingsScreen(
    items: kotlin.collections.List<IGMSettingsItem>,
    selectedIndex: Int,
    coreInfo: String
) {
    val itemHeight = pillItemHeight(lineHeight, verticalPadding)
    val colors = LocalCannoliColors.current

    ScreenBackground(backgroundImagePath = null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(screenPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 48.dp)
            ) {
                ScreenTitle(
                    text = "Settings",
                    fontSize = fontSize,
                    lineHeight = lineHeight
                )
                Spacer(modifier = Modifier.height(8.dp))
                List(
                    items = items,
                    selectedIndex = selectedIndex,
                    itemHeight = itemHeight
                ) { index, item ->
                    if (item.value != null) {
                        PillRowKeyValue(
                            label = item.label,
                            value = item.value,
                            isSelected = index == selectedIndex,
                            fontSize = fontSize,
                            lineHeight = lineHeight,
                            verticalPadding = verticalPadding
                        )
                    } else {
                        PillRowText(
                            label = item.label,
                            isSelected = index == selectedIndex,
                            fontSize = fontSize,
                            lineHeight = lineHeight,
                            verticalPadding = verticalPadding
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp)
            ) {
                if (coreInfo.isNotEmpty()) {
                    Text(
                        text = coreInfo,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 14.sp,
                            color = colors.text.copy(alpha = 0.4f)
                        )
                    )
                }
            }

            BottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                leftItems = listOf("B" to "BACK"),
                rightItems = listOf("←→" to "CHANGE", "A" to "SELECT")
            )
        }
    }
}
