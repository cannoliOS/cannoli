package dev.cannoli.scorza.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dev.cannoli.scorza.libretro.LibretroActivity
import dev.cannoli.scorza.libretro.SaveSlotManager
import dev.cannoli.scorza.model.Game
import dev.cannoli.scorza.model.LaunchTarget
import dev.cannoli.scorza.scanner.PlatformResolver
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.settings.TimeFormat
import dev.cannoli.scorza.ui.screens.DialogState
import java.io.File

class LaunchManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val platformResolver: PlatformResolver,
    private val retroArchLauncher: RetroArchLauncher,
    private val emuLauncher: EmuLauncher,
    private val apkLauncher: ApkLauncher,
) {

    fun ensureRetroArchConfig(root: File) {
        val configFile = File(root, "Config/retroarch.cfg")
        if (configFile.exists()) return
        configFile.parentFile?.mkdirs()
        val rootPath = root.absolutePath
        configFile.writeText(
            """
            savefile_directory = "$rootPath/Saves"
            savestate_directory = "$rootPath/Save States"
            system_directory = "$rootPath/BIOS"
            sort_savefiles_by_content_enable = "true"
            sort_savestates_by_content_enable = "true"
            """.trimIndent() + "\n"
        )
    }

    fun createTempM3u(game: Game): File {
        val m3uDir = File(context.cacheDir, "m3u")
        m3uDir.mkdirs()
        val m3uFile = File(m3uDir, "${game.displayName}.m3u")
        m3uFile.writeText(checkNotNull(game.discFiles).joinToString("\n") { it.absolutePath } + "\n")
        return m3uFile
    }

    fun findEmbeddedCore(coreName: String): String? {
        val soName = "${coreName}_android.so"
        val coreFile = File(context.filesDir, "cores/$soName")
        return if (coreFile.exists()) coreFile.absolutePath else null
    }

    fun getEmbeddedCorePath(game: Game): String? {
        val gameOverride = platformResolver.getGameOverride(game.file.absolutePath)
        if (gameOverride?.appPackage != null) return null
        val target = game.launchTarget
        if (target is LaunchTarget.Embedded) return target.corePath
        if (target !is LaunchTarget.RetroArch) return null
        val core = gameOverride?.coreId ?: platformResolver.getCoreName(game.platformTag) ?: return null
        val runnerPref = gameOverride?.runner ?: platformResolver.getRunnerPreference(game.platformTag)
        if (runnerPref == "RetroArch") return null
        return findEmbeddedCore(core)
    }

    fun findMostRecentSlot(game: Game): Int? {
        val cannoliRoot = File(settings.sdCardRoot)
        val romName = game.file.nameWithoutExtension
        val stateBase = File(cannoliRoot, "Save States/${game.platformTag}/$romName/$romName.state")
        val slotManager = SaveSlotManager(stateBase.absolutePath)
        var bestSlot = -1
        var bestTime = 0L
        for (slot in slotManager.slots) {
            val f = File(slotManager.statePath(slot))
            if (f.exists() && f.lastModified() > bestTime) {
                bestTime = f.lastModified()
                bestSlot = slot.index
            }
        }
        return if (bestSlot >= 0) bestSlot else null
    }

    fun findResumableGames(games: List<Game>): Set<String> {
        val cannoliRoot = File(settings.sdCardRoot)
        val result = mutableSetOf<String>()
        for (game in games) {
            if (game.isSubfolder) continue
            if (getEmbeddedCorePath(game) == null) continue
            val romName = game.file.nameWithoutExtension
            val stateDir = File(cannoliRoot, "Save States/${game.platformTag}/$romName")
            if (stateDir.exists() && stateDir.listFiles()?.any { it.extension == "state" || it.name.contains(".state.") } == true) {
                result.add(game.file.absolutePath)
            }
        }
        return result
    }

    fun launchGame(game: Game): DialogState? {
        val launchFile = if (game.discFiles != null) createTempM3u(game) else game.file

        val gameOverride = platformResolver.getGameOverride(game.file.absolutePath)
        if (gameOverride?.appPackage != null) {
            return toLaunchDialog(apkLauncher.launchWithRom(gameOverride.appPackage, launchFile))
        }

        val result = when (val target = game.launchTarget) {
            is LaunchTarget.RetroArch -> {
                val runnerPref = gameOverride?.runner ?: platformResolver.getRunnerPreference(game.platformTag)
                if (runnerPref == "App") {
                    val app = platformResolver.getAppPackage(game.platformTag)
                    if (app != null) {
                        apkLauncher.launchWithRom(app, launchFile)
                    } else {
                        LaunchResult.CoreNotInstalled("unknown")
                    }
                } else {
                    val core = gameOverride?.coreId ?: platformResolver.getCoreName(game.platformTag)
                    if (core != null) {
                        if (runnerPref != "RetroArch") {
                            val embeddedCorePath = findEmbeddedCore(core)
                            if (embeddedCorePath != null) {
                                launchEmbedded(game.copy(file = launchFile), embeddedCorePath)
                                return null
                            }
                        }
                        retroArchLauncher.launch(launchFile, core)
                    } else {
                        LaunchResult.CoreNotInstalled("unknown")
                    }
                }
            }
            is LaunchTarget.EmuLaunch -> {
                emuLauncher.launch(launchFile, target.packageName, target.activityName, target.action)
            }
            is LaunchTarget.ApkLaunch -> {
                if (launchFile.exists()) {
                    apkLauncher.launchWithRom(target.packageName, launchFile)
                } else {
                    apkLauncher.launch(target.packageName)
                }
            }
            is LaunchTarget.Embedded -> {
                launchEmbedded(game.copy(file = launchFile), target.corePath)
                return null
            }
        }

        return toLaunchDialog(result)
    }

    fun resumeGame(game: Game) {
        val corePath = getEmbeddedCorePath(game) ?: return
        val slot = findMostRecentSlot(game) ?: return
        val launchFile = if (game.discFiles != null) createTempM3u(game) else game.file
        launchEmbedded(game.copy(file = launchFile), corePath, slot)
    }

    fun toLaunchDialog(result: LaunchResult): DialogState? {
        return when (result) {
            is LaunchResult.CoreNotInstalled -> DialogState.MissingCore(result.coreName)
            is LaunchResult.AppNotInstalled -> {
                val appName = try {
                    val info = context.packageManager.getApplicationInfo(result.packageName, 0)
                    context.packageManager.getApplicationLabel(info).toString()
                } catch (_: Exception) {
                    result.packageName
                }
                DialogState.MissingApp(appName, result.packageName)
            }
            is LaunchResult.Error -> DialogState.LaunchError(result.message)
            LaunchResult.Success -> null
        }
    }

    fun launchEmbedded(game: Game, corePath: String, resumeSlot: Int = -1) {
        val cannoliRoot = File(settings.sdCardRoot)
        val romName = game.file.nameWithoutExtension
        val saveDir = File(cannoliRoot, "Saves/${game.platformTag}")
        saveDir.mkdirs()

        val intent = Intent(context, LibretroActivity::class.java).apply {
            putExtra("game_title", game.displayName)
            putExtra("core_path", corePath)
            putExtra("rom_path", game.file.absolutePath)
            putExtra("sram_path", File(saveDir, "$romName.srm").absolutePath)
            val stateDir = File(cannoliRoot, "Save States/${game.platformTag}/$romName")
            stateDir.mkdirs()
            putExtra("state_path", File(stateDir, "$romName.state").absolutePath)
            putExtra("platform_tag", game.platformTag)
            putExtra("platform_name", platformResolver.getDisplayName(game.platformTag))
            putExtra("cannoli_root", cannoliRoot.absolutePath)
            putExtra("system_dir", File(cannoliRoot, "BIOS").absolutePath)
            putExtra("save_dir", saveDir.absolutePath)
            putExtra("color_highlight", settings.colorHighlight)
            putExtra("color_text", settings.colorText)
            putExtra("color_highlight_text", settings.colorHighlightText)
            putExtra("color_accent", settings.colorAccent)
            putExtra("show_wifi", settings.showWifi)
            putExtra("show_bluetooth", settings.showBluetooth)
            putExtra("show_clock", settings.showClock)
            putExtra("show_battery", settings.showBattery)
            putExtra("use_24h", settings.timeFormat == TimeFormat.TWENTY_FOUR_HOUR)
            putExtra("swap_start_select", settings.swapStartSelect)
            if (resumeSlot >= 0) putExtra("resume_slot", resumeSlot)
        }
        context.startActivity(intent)
    }

    companion object {
        fun extractBundledCores(context: Context): String {
            val coresDir = File(context.filesDir, "cores")
            coresDir.mkdirs()
            val versionFile = File(coresDir, ".version")
            val currentVersion = File(context.applicationInfo.sourceDir).lastModified().toString()
            if (versionFile.exists() && versionFile.readText() == currentVersion) return coresDir.absolutePath
            java.util.zip.ZipFile(context.applicationInfo.sourceDir).use { apkZip ->
                val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
                val prefix = "lib/$abi/"
                for (entry in apkZip.entries()) {
                    if (!entry.name.startsWith(prefix) || !entry.name.endsWith("_libretro_android.so")) continue
                    val name = entry.name.removePrefix(prefix)
                    val dst = File(coresDir, name)
                    apkZip.getInputStream(entry).use { inp -> dst.outputStream().use { inp.copyTo(it) } }
                }
            }
            versionFile.writeText(currentVersion)
            return coresDir.absolutePath
        }
    }
}
