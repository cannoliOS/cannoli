package dev.cannoli.scorza.libretro

import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.R
import dev.cannoli.scorza.ui.theme.GrayText
import dev.cannoli.scorza.ui.theme.MPlus1Code
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.viewinterop.AndroidView
import dev.cannoli.scorza.ui.components.BottomBar
import dev.cannoli.scorza.ui.components.ScreenBackground
import dev.cannoli.scorza.ui.components.LocalStatusBarLeftEdge
import dev.cannoli.scorza.ui.components.ScreenTitle
import dev.cannoli.scorza.ui.components.StatusBar
import dev.cannoli.scorza.ui.components.pillInternalH
import dev.cannoli.scorza.ui.components.screenPadding
import dev.cannoli.scorza.ui.theme.LocalCannoliColors

data class GameInfo(
    val coreName: String,
    val romPath: String,
    val savePath: String?,
    val rootPrefix: String = "",
    val originalRomPath: String? = null,
    val rendererName: String = "",
    val raStatus: String? = null,
    val raGameId: String? = null
)

@Composable
fun LibretroScreen(
    glSurfaceView: android.view.View,
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
    profileName: String = "",
    profileNames: List<String> = emptyList(),
    debugHud: Boolean,
    renderer: GraphicsBackend,
    runner: LibretroRunner,
    audioSampleRate: Int,
    showWifi: Boolean,
    showBluetooth: Boolean,
    showClock: Boolean,
    showBattery: Boolean,
    use24h: Boolean,
    osdMessage: String?,
    fastForwarding: Boolean,
    guideFiles: List<GuideFile> = emptyList(),
    guidePageCount: Int = 0,
    guideScrollDir: Int = 0,
    guideScrollXDir: Int = 0,
    guidePageJump: Int = 0,
    guidePageJumpDir: Int = 0,
    guideInitialScroll: Int = 0,
    guideInitialScrollX: Int = 0,
    onGuideScrollChanged: (y: Int, x: Int) -> Unit = { _, _ -> },
    gameInfo: GameInfo = GameInfo("", "", null)
) {
    val overlayVisible = screen != null
    val showDescription = when (screen) {
        is IGMScreen.Emulator -> screen.showDescription
        is IGMScreen.EmulatorCategory -> screen.showDescription
        else -> false
    }
    val isGuideScreen = screen is IGMScreen.Guide
    val statusBarEnabled = (showWifi || showBluetooth || showClock || showBattery) && !showDescription && !isGuideScreen
    val statusBarLeftEdge = remember { mutableIntStateOf(Int.MAX_VALUE) }

    CompositionLocalProvider(LocalStatusBarLeftEdge provides statusBarLeftEdge) {
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
                    undoLabel = undoLabel
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
                                text = stringResource(R.string.igm_delete_slot, selectedSlot.label),
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
                            leftItems = listOf("B" to stringResource(R.string.label_cancel)),
                            rightItems = listOf("X" to stringResource(R.string.label_delete))
                        )
                    }
                }
            }
            is IGMScreen.Controls -> {
                val activeLabel = stringResource(R.string.value_active)
                if (screen.menuOpen) {
                    val menuItems = listOf(
                        IGMSettingsItem(label = stringResource(R.string.context_rename)),
                        IGMSettingsItem(label = stringResource(R.string.context_delete))
                    )
                    IGMSettingsScreen(
                        title = profileNames.getOrNull(screen.selectedIndex) ?: "",
                        items = menuItems,
                        selectedIndex = screen.menuIndex,
                        bottomBarLeft = listOf("B" to stringResource(R.string.label_cancel)),
                        bottomBarRight = listOf("A" to stringResource(R.string.label_select))
                    )
                } else {
                    val items = profileNames.map { name ->
                        IGMSettingsItem(
                            label = name,
                            value = if (name == profileName) activeLabel else null
                        )
                    }
                    IGMSettingsScreen(
                        title = stringResource(R.string.title_controls),
                        items = items,
                        selectedIndex = screen.selectedIndex,
                        bottomBarLeft = listOf("B" to stringResource(R.string.label_back), "Y" to stringResource(R.string.label_new)),
                        bottomBarRight = listOf("X" to stringResource(R.string.label_edit), "A" to stringResource(R.string.label_select))
                    )
                }
            }
            is IGMScreen.ControlEdit -> ControlsScreen(
                input = input,
                selectedIndex = screen.selectedIndex,
                listeningIndex = screen.listeningIndex,
                listenCountdownMs = screen.listenCountdownMs,
                profileName = profileName,
                dirty = screen.dirty
            )
            is IGMScreen.ProfileName -> {
                dev.cannoli.scorza.ui.components.KeyboardOverlay(
                    text = screen.name,
                    cursorPos = screen.cursorPos,
                    keyRow = screen.keyRow,
                    keyCol = screen.keyCol,
                    caps = screen.caps,
                    symbols = screen.symbols
                )
            }
            is IGMScreen.Settings, is IGMScreen.Video, is IGMScreen.Advanced,
            is IGMScreen.ShaderSettings,
            is IGMScreen.Emulator, is IGMScreen.EmulatorCategory,
            is IGMScreen.Shortcuts, is IGMScreen.SavePrompt -> {
                val description = if (showDescription) {
                    settingsItems.getOrNull(screen.selectedIndex)?.hint
                } else null
                val isOptionList = screen is IGMScreen.EmulatorCategory ||
                    (screen is IGMScreen.Emulator && settingsItems.all { it.value != null })
                val changeLabel = stringResource(R.string.label_change)
                val selectLabel = stringResource(R.string.label_select)
                val bottomBarRight = when {
                    isOptionList -> listOf("A" to stringResource(R.string.label_info), "←→" to changeLabel)
                    screen is IGMScreen.Shortcuts && screen.selectedIndex == 0 -> listOf("←→" to changeLabel)
                    screen is IGMScreen.Shortcuts -> listOf("X" to stringResource(R.string.label_clear), "A" to stringResource(R.string.label_set))
                    screen is IGMScreen.Video -> listOf("A" to selectLabel, "←→" to changeLabel)
                    screen is IGMScreen.Advanced -> listOf("←→" to changeLabel)
                    screen is IGMScreen.ShaderSettings -> listOf("←→" to changeLabel)
                    else -> listOf("A" to selectLabel)
                }
                val emulatorLabel = stringResource(R.string.igm_emulator)
                val title = when (screen) {
                    is IGMScreen.Settings -> stringResource(R.string.igm_settings)
                    is IGMScreen.Video -> stringResource(R.string.igm_video)
                    is IGMScreen.Advanced -> stringResource(R.string.igm_advanced)
                    is IGMScreen.ShaderSettings -> stringResource(R.string.igm_shader_settings)
                    is IGMScreen.Emulator -> emulatorLabel
                    is IGMScreen.EmulatorCategory -> screen.categoryTitle.ifEmpty { emulatorLabel }
                    is IGMScreen.Shortcuts -> stringResource(R.string.title_shortcuts)
                    is IGMScreen.SavePrompt -> stringResource(R.string.igm_save_changes)
                    else -> stringResource(R.string.igm_settings)
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
                            val infoModifier = Modifier.padding(start = pillInternalH)
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clipToBounds()
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Spacer(modifier = Modifier.height(16.dp))
                                InfoRow(stringResource(R.string.info_core), gameInfo.coreName, infoModifier)
                                Spacer(modifier = Modifier.height(12.dp))
                                if (gameInfo.originalRomPath != null) {
                                    InfoRow(stringResource(R.string.info_rom), stripRoot(gameInfo.originalRomPath), infoModifier)
                                    Spacer(modifier = Modifier.height(12.dp))
                                    InfoRow(stringResource(R.string.info_extracted), stripRoot(gameInfo.romPath), infoModifier)
                                } else {
                                    InfoRow(stringResource(R.string.info_rom), stripRoot(gameInfo.romPath), infoModifier)
                                }
                                if (gameInfo.savePath != null) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    InfoRow(stringResource(R.string.info_save), stripRoot(gameInfo.savePath), infoModifier)
                                }
                                if (gameInfo.rendererName.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    InfoRow(stringResource(R.string.info_renderer), gameInfo.rendererName, infoModifier)
                                }
                                if (gameInfo.raStatus != null) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    InfoRow(stringResource(R.string.ra_title), gameInfo.raStatus, infoModifier)
                                }
                                if (gameInfo.raGameId != null) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    InfoRow(stringResource(R.string.info_game_id), gameInfo.raGameId, infoModifier)
                                }
                            }
                        }
                        BottomBar(
                            modifier = Modifier.align(Alignment.BottomCenter),
                            leftItems = listOf("B" to stringResource(R.string.label_back)),
                            rightItems = emptyList()
                        )
                    }
                }
            }
            is IGMScreen.GuidePicker -> {
                IGMSettingsScreen(
                    title = stringResource(R.string.title_guide),
                    items = guideFiles.map { IGMSettingsItem(it.name) },
                    selectedIndex = screen.selectedIndex
                )
            }
            is IGMScreen.Guide -> {
                val guide = guideFiles.firstOrNull { it.file.absolutePath == screen.filePath }
                val type = guide?.type ?: GuideType.TXT
                GuideScreen(
                    filePath = screen.filePath,
                    guideType = type,
                    page = screen.page,
                    initialScrollY = guideInitialScroll,
                    initialScrollX = guideInitialScrollX,
                    scrollDir = guideScrollDir,
                    scrollXDir = guideScrollXDir,
                    pageJump = guidePageJump,
                    pageJumpDir = guidePageJumpDir,
                    pageCount = guidePageCount,
                    textZoom = screen.textZoom,
                    onScrollPosChanged = onGuideScrollChanged
                )
            }
            is IGMScreen.Achievements -> {
                val filterLabel = when (screen.filter) { 0 -> stringResource(R.string.label_all); else -> stringResource(R.string.label_unlocked) }
                val filtered = when (screen.filter) {
                    1 -> screen.achievements.filter { it.unlocked }
                    else -> screen.achievements
                }
                IGMSettingsScreen(
                    title = stringResource(R.string.ach_title, screen.achievements.count { it.unlocked }, screen.achievements.size),
                    items = filtered.map { ach ->
                        val prefix = when {
                            ach.pendingSync -> "◐"
                            ach.unlocked -> "●"
                            else -> "○"
                        }
                        IGMSettingsItem(
                            label = "$prefix ${ach.title}",
                            value = stringResource(R.string.ach_points_short, ach.points)
                        )
                    },
                    selectedIndex = screen.selectedIndex.coerceAtMost((filtered.size - 1).coerceAtLeast(0)),
                    coreInfo = screen.status,
                    bottomBarRight = listOf("Y" to filterLabel, "A" to stringResource(R.string.label_details))
                )
            }
            is IGMScreen.AchievementDetail -> {
                val ach = screen.achievement
                val unlockText = if (ach.pendingSync) {
                    stringResource(R.string.ach_unlocked_pending)
                } else if (ach.unlocked && ach.unlockTime > 0) {
                    val date = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault())
                        .format(java.util.Date(ach.unlockTime * 1000))
                    stringResource(R.string.ach_unlocked_date, date)
                } else if (ach.unlocked) stringResource(R.string.ach_unlocked) else stringResource(R.string.ach_locked)

                ScreenBackground(backgroundImagePath = null, backgroundAlpha = 0.85f) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(screenPadding),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth(0.8f)
                        ) {
                            Text(
                                text = ach.title,
                                style = TextStyle(
                                    fontFamily = MPlus1Code,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 24.sp,
                                    color = Color.White,
                                    textAlign = TextAlign.Center
                                )
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = unlockText,
                                style = TextStyle(
                                    fontFamily = MPlus1Code,
                                    fontSize = 16.sp,
                                    color = Color.White
                                )
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.ach_points, ach.points),
                                style = TextStyle(
                                    fontFamily = MPlus1Code,
                                    fontSize = 16.sp,
                                    color = Color.White
                                )
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = ach.description,
                                style = TextStyle(
                                    fontFamily = MPlus1Code,
                                    fontSize = 18.sp,
                                    color = Color.White,
                                    textAlign = TextAlign.Center
                                )
                            )
                        }
                        BottomBar(
                            modifier = Modifier.align(Alignment.BottomCenter),
                            leftItems = listOf("B" to stringResource(R.string.label_back)),
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
                        text = if (screen.heldKeys.isEmpty()) stringResource(R.string.shortcut_hold_prompt)
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
                    .padding(bottom = 25.dp)
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
                    .onGloballyPositioned { coords ->
                        statusBarLeftEdge.intValue = coords.positionInWindow().x.toInt()
                    }
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
}

@Composable
private fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = LocalCannoliColors.current
    Column(modifier = modifier) {
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
