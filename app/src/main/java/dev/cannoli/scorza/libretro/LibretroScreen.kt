package dev.cannoli.scorza.libretro

import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.cannoli.scorza.ui.components.BottomBar
import dev.cannoli.scorza.ui.components.ScreenBackground
import dev.cannoli.scorza.ui.components.ScreenTitle
import dev.cannoli.scorza.ui.components.StatusBar
import dev.cannoli.scorza.ui.components.screenPadding
import dev.cannoli.scorza.ui.theme.LocalCannoliColors

data class GameInfo(
    val coreName: String,
    val romPath: String,
    val savePath: String?,
    val rootPrefix: String = ""
)

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
    controlSource: OverrideSource,
    debugHud: Boolean,
    renderer: LibretroRenderer,
    runner: LibretroRunner,
    audioSampleRate: Int,
    showWifi: Boolean,
    showBluetooth: Boolean,
    showClock: Boolean,
    showBattery: Boolean,
    use24h: Boolean,
    osdMessage: String?,
    fastForwarding: Boolean,
    gameInfo: GameInfo = GameInfo("", "", null)
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
            is IGMScreen.Menu -> {
                InGameMenu(
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
                if (screen.confirmDeleteSlot) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.85f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Text(
                                text = "Delete ${selectedSlot.label}?",
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Box(modifier = Modifier.fillMaxWidth(0.5f)) {
                                PolaroidFrame(
                                    thumbnail = slotThumbnail,
                                    selectedSlotIndex = selectedSlot.index,
                                    slotOccupied = slotOccupied,
                                    showIndicators = false
                                )
                            }
                        }
                        BottomBar(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(screenPadding),
                            leftItems = listOf("B" to "CANCEL"),
                            rightItems = listOf("X" to "DELETE")
                        )
                    }
                }
            }
            is IGMScreen.Controls -> ControlsScreen(
                input = input,
                selectedIndex = screen.selectedIndex,
                listeningIndex = screen.listeningIndex,
                listenCountdownMs = screen.listenCountdownMs,
                controlSource = controlSource
            )
            is IGMScreen.Settings, is IGMScreen.Frontend, is IGMScreen.CrtSettings,
            is IGMScreen.Emulator, is IGMScreen.EmulatorCategory,
            is IGMScreen.Shortcuts, is IGMScreen.SavePrompt -> {
                val description = if (showDescription) {
                    settingsItems.getOrNull(screen.selectedIndex)?.hint
                } else null
                val isOptionList = screen is IGMScreen.EmulatorCategory ||
                    (screen is IGMScreen.Emulator && settingsItems.all { it.value != null })
                val bottomBarRight = when {
                    isOptionList -> listOf("A" to "INFO", "←→" to "CHANGE")
                    screen is IGMScreen.Shortcuts && screen.selectedIndex == 0 -> listOf("←→" to "CHANGE")
                    screen is IGMScreen.Shortcuts -> listOf("X" to "CLEAR", "A" to "SET")
                    screen is IGMScreen.Frontend -> listOf("A" to "SELECT", "←→" to "CHANGE")
                    screen is IGMScreen.CrtSettings -> listOf("←→" to "CHANGE")
                    else -> listOf("A" to "SELECT")
                }
                val title = when (screen) {
                    is IGMScreen.Settings -> "Settings"
                    is IGMScreen.Frontend -> "Frontend"
                    is IGMScreen.CrtSettings -> "CRT Settings"
                    is IGMScreen.Emulator -> "Emulator"
                    is IGMScreen.EmulatorCategory -> screen.categoryTitle.ifEmpty { "Emulator" }
                    is IGMScreen.Shortcuts -> "Shortcuts"
                    is IGMScreen.SavePrompt -> "Save Changes"
                    else -> "Settings"
                }
                IGMSettingsScreen(
                    title = title,
                    items = settingsItems,
                    selectedIndex = screen.selectedIndex,
                    coreInfo = coreInfo,
                    description = description,
                    bottomBarRight = bottomBarRight
                )
            }
            is IGMScreen.Info -> {
                val colors = LocalCannoliColors.current
                fun stripRoot(path: String): String {
                    if (gameInfo.rootPrefix.isNotEmpty() && path.startsWith(gameInfo.rootPrefix)) {
                        return path.removePrefix(gameInfo.rootPrefix).removePrefix("/")
                    }
                    return path
                }
                ScreenBackground(backgroundImagePath = null, backgroundAlpha = 0.85f) {
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
                                fontSize = 22.sp,
                                lineHeight = 32.sp
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            InfoRow("Core", gameInfo.coreName)
                            Spacer(modifier = Modifier.height(12.dp))
                            InfoRow("ROM", stripRoot(gameInfo.romPath))
                            if (gameInfo.savePath != null) {
                                Spacer(modifier = Modifier.height(12.dp))
                                InfoRow("Save", stripRoot(gameInfo.savePath))
                            }
                        }
                        BottomBar(
                            modifier = Modifier.align(Alignment.BottomCenter),
                            leftItems = listOf("B" to "BACK"),
                            rightItems = emptyList()
                        )
                    }
                }
            }
            null -> {}
        }

        if (screen is IGMScreen.Shortcuts && screen.listening) {
            val colors = LocalCannoliColors.current
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.92f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(0.7f)
                ) {
                    val actionName = settingsItems.getOrNull(screen.selectedIndex)?.label ?: ""
                    Text(
                        text = actionName,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 16.sp,
                            color = colors.text.copy(alpha = 0.6f)
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (screen.heldKeys.isEmpty()) "Hold buttons..."
                        else screen.heldKeys.joinToString(" + ") { LibretroInput.keyCodeName(it) },
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 24.sp,
                            color = colors.text
                        )
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    if (screen.heldKeys.isNotEmpty()) {
                        val progress = (screen.countdownMs / 1500f).coerceIn(0f, 1f)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.5f)
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(colors.text.copy(alpha = 0.2f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(progress)
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(colors.highlight)
                            )
                        }
                    }
                }
            }
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

        if (fastForwarding && !overlayVisible && osdMessage == null) {
            val colors = LocalCannoliColors.current
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp)
                    .clip(RoundedCornerShape(50))
                    .background(colors.highlight)
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "▶▶",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    color = colors.highlightText
                )
            }
        }

        if (osdMessage != null) {
            val colors = LocalCannoliColors.current
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp)
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

@Composable
private fun InfoRow(label: String, value: String) {
    val colors = LocalCannoliColors.current
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 14.sp,
                color = colors.text.copy(alpha = 0.5f)
            )
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 18.sp,
                color = Color.White
            )
        )
    }
}
