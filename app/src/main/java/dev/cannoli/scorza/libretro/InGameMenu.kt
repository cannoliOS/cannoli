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
import dev.cannoli.scorza.ui.components.PillRowText
import dev.cannoli.scorza.ui.components.ScreenBackground
import dev.cannoli.scorza.ui.components.ScreenTitle
import dev.cannoli.scorza.ui.components.pillItemHeight
import dev.cannoli.scorza.ui.components.screenPadding

object InGameMenu {
    const val RESUME = 0
    const val SAVE_STATE = 1
    const val LOAD_STATE = 2
    const val RESET = 3
    const val QUIT = 4

    val OPTIONS = listOf("Resume", "Save State", "Load State", "Reset", "Quit")
}

private val fontSize = 22.sp
private val lineHeight = 32.sp
private val verticalPadding = 8.dp

@Composable
fun InGameMenu(
    gameTitle: String,
    selectedIndex: Int,
    onAction: (Int) -> Unit
) {
    val itemHeight = pillItemHeight(lineHeight, verticalPadding)

    ScreenBackground(backgroundImagePath = null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(screenPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.65f)
                    .padding(bottom = 48.dp)
            ) {
                ScreenTitle(
                    text = gameTitle,
                    fontSize = fontSize,
                    lineHeight = lineHeight
                )
                Spacer(modifier = Modifier.height(8.dp))
                List(
                    items = InGameMenu.OPTIONS,
                    selectedIndex = selectedIndex,
                    itemHeight = itemHeight
                ) { index, option ->
                    PillRowText(
                        label = option,
                        isSelected = index == selectedIndex,
                        fontSize = fontSize,
                        lineHeight = lineHeight,
                        verticalPadding = verticalPadding
                    )
                }
            }

            BottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                leftItems = listOf("B" to "Back"),
                rightItems = listOf("A" to "Select")
            )
        }
    }
}
