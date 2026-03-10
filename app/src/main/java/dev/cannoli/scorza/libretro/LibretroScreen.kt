package dev.cannoli.scorza.libretro

import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.cannoli.scorza.ui.components.StatusBar
import dev.cannoli.scorza.ui.theme.LocalCannoliColors

@Composable
fun LibretroScreen(
    glSurfaceView: GLSurfaceView,
    gameTitle: String,
    screen: IGMScreen?,
    menuOptions: InGameMenuOptions,
    selectedSlot: SaveSlotManager.Slot,
    slotThumbnail: Bitmap?,
    slotExists: Boolean,
    slotOccupied: List<Boolean>,
    undoLabel: String?,
    settingsItems: List<IGMSettingsItem>,
    coreInfo: String,
    input: LibretroInput,
    debugHud: Boolean,
    renderer: LibretroRenderer,
    runner: LibretroRunner,
    audioSampleRate: Int,
    showWifi: Boolean,
    showBluetooth: Boolean,
    showClock: Boolean,
    showBattery: Boolean,
    use24h: Boolean,
    osdMessage: String?
) {
    val overlayVisible = screen != null
    val showDescription = when (screen) {
        is IGMScreen.Emulator -> screen.showDescription
        is IGMScreen.EmulatorCategory -> screen.showDescription
        else -> false
    }
    val statusBarEnabled = (showWifi || showBluetooth || showClock || showBattery) && !showDescription

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { glSurfaceView },
            modifier = Modifier.fillMaxSize()
        )

        when (screen) {
            is IGMScreen.Menu -> InGameMenu(
                gameTitle = gameTitle,
                menuOptions = menuOptions,
                selectedIndex = screen.selectedIndex,
                selectedSlot = selectedSlot,
                slotThumbnail = slotThumbnail,
                slotExists = slotExists,
                slotOccupied = slotOccupied,
                undoLabel = undoLabel,
                onAction = {}
            )
            is IGMScreen.Controls -> ControlsScreen(
                input = input,
                selectedIndex = screen.selectedIndex,
                listeningIndex = screen.listeningIndex
            )
            is IGMScreen.Settings, is IGMScreen.Frontend, is IGMScreen.Emulator,
            is IGMScreen.EmulatorCategory, is IGMScreen.Shortcuts,
            is IGMScreen.SaveSettings -> {
                val description = if (showDescription) {
                    settingsItems.getOrNull(screen.selectedIndex)?.hint
                } else null
                val isOptionList = screen is IGMScreen.EmulatorCategory ||
                    (screen is IGMScreen.Emulator && settingsItems.all { it.value != null })
                val bottomBarRight = if (isOptionList) {
                    listOf("A" to "INFO", "←→" to "CHANGE")
                } else {
                    listOf("←→" to "CHANGE", "A" to "SELECT")
                }
                IGMSettingsScreen(
                    items = settingsItems,
                    selectedIndex = screen.selectedIndex,
                    coreInfo = coreInfo,
                    description = description,
                    bottomBarRight = bottomBarRight
                )
            }
            null -> {}
        }

        if (debugHud && !overlayVisible) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
            ) {
                DebugHud(
                    renderer = renderer,
                    runner = runner,
                    coreName = coreInfo,
                    audioSampleRate = audioSampleRate
                )
            }
        }

        if (osdMessage != null) {
            val colors = LocalCannoliColors.current
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 20.dp)
                    .clip(RoundedCornerShape(50))
                    .background(colors.highlight)
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text(
                    text = osdMessage,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    color = colors.highlightText
                )
            }
        }

        if (statusBarEnabled) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(20.dp)
                    .alpha(if (overlayVisible) 1f else 0f)
            ) {
                StatusBar(
                    use24hTime = use24h,
                    showWifi = showWifi,
                    showBluetooth = showBluetooth,
                    showClock = showClock,
                    showBattery = showBattery
                )
            }
        }
    }
}
