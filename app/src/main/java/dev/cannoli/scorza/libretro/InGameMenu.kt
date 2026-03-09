package dev.cannoli.scorza.libretro

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.ui.components.BottomBar
import dev.cannoli.scorza.ui.components.List
import dev.cannoli.scorza.ui.components.PillRowText
import dev.cannoli.scorza.ui.components.ScreenBackground
import dev.cannoli.scorza.ui.components.ScreenTitle
import dev.cannoli.scorza.ui.components.pillItemHeight
import dev.cannoli.scorza.ui.components.screenPadding
import dev.cannoli.scorza.ui.theme.GrayText
import dev.cannoli.scorza.ui.theme.LocalCannoliColors

object InGameMenu {
    const val RESUME = 0
    const val SAVE_STATE = 1
    const val LOAD_STATE = 2
    const val SETTINGS = 3
    const val RESET = 4
    const val QUIT = 5

    val OPTIONS = listOf("Resume", "Save State", "Load State", "Settings", "Reset", "Quit")
}

object IGMSettings {
    const val FRONTEND = 0
    const val EMULATOR = 1
    const val CONTROLS = 2
    const val SHORTCUTS = 3
    const val SAVE_SETTINGS = 4

    val CATEGORIES = listOf("Frontend", "Emulator", "Controls", "Shortcuts", "Save Settings")
}

enum class ShortcutAction(val label: String) {
    SAVE_STATE("Save State"),
    LOAD_STATE("Load State"),
    RESET_GAME("Reset Game"),
    SAVE_AND_QUIT("Save and Quit"),
    CYCLE_SCALING("Cycle Scaling"),
    CYCLE_EFFECT("Cycle Effect"),
    TOGGLE_FF("Toggle Fast Forward"),
    HOLD_FF("Hold Fast Forward")
}

private val fontSize = 22.sp
private val lineHeight = 32.sp
private val verticalPadding = 8.dp

@Composable
fun InGameMenu(
    gameTitle: String,
    selectedIndex: Int,
    selectedSlot: SaveSlotManager.Slot,
    slotThumbnail: Bitmap?,
    slotExists: Boolean,
    slotOccupied: List<Boolean>,
    undoLabel: String?,
    onAction: (Int) -> Unit
) {
    val itemHeight = pillItemHeight(lineHeight, verticalPadding)
    val showThumbnail = selectedIndex == InGameMenu.SAVE_STATE || selectedIndex == InGameMenu.LOAD_STATE

    ScreenBackground(backgroundImagePath = null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(screenPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 48.dp)
            ) {
                ScreenTitle(
                    text = gameTitle,
                    fontSize = fontSize,
                    lineHeight = lineHeight
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .then(if (showThumbnail) Modifier.fillMaxWidth(0.5f) else Modifier.fillMaxWidth())
                    ) {
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

                    if (showThumbnail) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth()
                                .padding(start = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            PolaroidFrame(
                                thumbnail = slotThumbnail,
                                selectedSlotIndex = selectedSlot.index,
                                slotOccupied = slotOccupied
                            )
                        }
                    }
                }
            }

            val leftItems = buildList {
                add("B" to "BACK")
                if (undoLabel != null) add("X" to undoLabel.uppercase())
            }
            val rightItems = if (showThumbnail) {
                listOf("◀▶" to "SLOT", "A" to "SELECT")
            } else {
                listOf("A" to "SELECT")
            }

            BottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                leftItems = leftItems,
                rightItems = rightItems
            )
        }
    }
}

@Composable
private fun PolaroidFrame(
    thumbnail: Bitmap?,
    selectedSlotIndex: Int,
    slotOccupied: List<Boolean>
) {
    val accent = LocalCannoliColors.current.accent
    val aspectRatio = if (thumbnail != null) {
        thumbnail.width.toFloat() / thumbnail.height.toFloat()
    } else {
        10f / 9f
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.White)
            .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 12.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0xFF222222)),
            contentAlignment = Alignment.Center
        ) {
            if (thumbnail != null) {
                val imageBitmap = remember(thumbnail) { thumbnail.asImageBitmap() }
                Image(
                    bitmap = imageBitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else {
                Text(
                    text = "Empty",
                    style = MaterialTheme.typography.bodyMedium,
                    color = GrayText
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            val autoSelected = selectedSlotIndex == 0
            val autoOccupied = slotOccupied.getOrElse(0) { false }
            Text(
                text = "A",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = when {
                        autoSelected -> accent
                        autoOccupied -> Color.Black
                        else -> Color(0xFFCCCCCC)
                    }
                )
            )
            Spacer(modifier = Modifier.width(6.dp))

            for (i in 1..10) {
                val occupied = slotOccupied.getOrElse(i) { false }
                val selected = selectedSlotIndex == i
                val dotSize = if (selected) 10.dp else 8.dp

                Box(
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .size(dotSize)
                        .then(
                            if (occupied) {
                                Modifier
                                    .clip(CircleShape)
                                    .background(if (selected) accent else Color.Black)
                            } else {
                                Modifier
                                    .clip(CircleShape)
                                    .border(1.dp, Color.Black, CircleShape)
                            }
                        )
                )
            }
        }
    }
}
