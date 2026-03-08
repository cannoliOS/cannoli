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
    menuVisible: Boolean,
    menuSelectedIndex: Int,
    selectedSlot: SaveSlotManager.Slot,
    slotThumbnail: Bitmap?,
    slotExists: Boolean,
    slotOccupied: List<Boolean>,
    undoLabel: String?,
    onMenuAction: (Int) -> Unit,
    settingsVisible: Boolean,
    settingsSelectedIndex: Int,
    controlsVisible: Boolean,
    controlsSelectedIndex: Int,
    controlsListeningIndex: Int,
    input: LibretroInput,
    showWifi: Boolean,
    showBluetooth: Boolean,
    showClock: Boolean,
    showBattery: Boolean,
    use24h: Boolean,
    osdMessage: String?
) {
    val overlayVisible = menuVisible || settingsVisible || controlsVisible
    val statusBarEnabled = showWifi || showBluetooth || showClock || showBattery

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { glSurfaceView },
            modifier = Modifier.fillMaxSize()
        )

        when {
            controlsVisible -> ControlsScreen(
                input = input,
                selectedIndex = controlsSelectedIndex,
                listeningIndex = controlsListeningIndex
            )
            settingsVisible -> IGMSettingsScreen(
                selectedIndex = settingsSelectedIndex
            )
            menuVisible -> InGameMenu(
                gameTitle = gameTitle,
                selectedIndex = menuSelectedIndex,
                selectedSlot = selectedSlot,
                slotThumbnail = slotThumbnail,
                slotExists = slotExists,
                slotOccupied = slotOccupied,
                undoLabel = undoLabel,
                onAction = onMenuAction
            )
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
