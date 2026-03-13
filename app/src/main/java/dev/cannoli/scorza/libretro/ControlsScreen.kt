package dev.cannoli.scorza.libretro

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.ui.components.BottomBar
import dev.cannoli.scorza.ui.components.List
import dev.cannoli.scorza.ui.components.PillRowKeyValue
import dev.cannoli.scorza.ui.components.ScreenBackground
import dev.cannoli.scorza.ui.components.ScreenTitle
import dev.cannoli.scorza.ui.components.pillItemHeight
import dev.cannoli.scorza.ui.components.screenPadding

private val fontSize = 22.sp
private val lineHeight = 32.sp
private val verticalPadding = 8.dp

@Composable
fun ControlsScreen(
    input: LibretroInput,
    selectedIndex: Int,
    listeningIndex: Int,
    useGlobalControls: Boolean? = null,
    title: String = "Controls"
) {
    val itemHeight = pillItemHeight(lineHeight, verticalPadding)
    val hasToggle = useGlobalControls != null
    val buttonOffset = if (hasToggle) 1 else 0

    ScreenBackground(backgroundImagePath = null, backgroundAlpha = 0.85f) {
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
                    text = title,
                    fontSize = fontSize,
                    lineHeight = lineHeight
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (hasToggle) {
                    PillRowKeyValue(
                        label = "Use Global Controls",
                        value = if (useGlobalControls == true) "On" else "Off",
                        isSelected = selectedIndex == 0,
                        fontSize = fontSize,
                        lineHeight = lineHeight,
                        verticalPadding = verticalPadding
                    )
                }
                List(
                    items = input.buttons,
                    selectedIndex = selectedIndex - buttonOffset,
                    itemHeight = itemHeight
                ) { index, button ->
                    val value = if (index + buttonOffset == listeningIndex) {
                        "..."
                    } else {
                        LibretroInput.keyCodeName(input.getKeyCodeFor(button))
                    }
                    PillRowKeyValue(
                        label = button.label,
                        value = value,
                        isSelected = index == selectedIndex - buttonOffset,
                        fontSize = fontSize,
                        lineHeight = lineHeight,
                        verticalPadding = verticalPadding
                    )
                }
            }

            val bottomLeft = if (useGlobalControls == true) {
                listOf("B" to "BACK")
            } else {
                listOf("B" to "BACK", "X" to "RESET ALL")
            }
            val bottomRight = if (listeningIndex >= 0) {
                listOf("" to "PRESS A BUTTON...")
            } else if (hasToggle && selectedIndex == 0) {
                listOf("A" to "TOGGLE")
            } else if (useGlobalControls == true) {
                emptyList()
            } else {
                listOf("A" to "REMAP")
            }

            BottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                leftItems = bottomLeft,
                rightItems = bottomRight
            )
        }
    }
}
