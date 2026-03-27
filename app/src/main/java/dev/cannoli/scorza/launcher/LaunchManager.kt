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
import dev.cannoli.scorza.util.ArchiveExtractor
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.text.Normalizer

class LaunchManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val platformResolver: PlatformResolver,
    private val retroArchLauncher: RetroArchLauncher,
    private val emuLauncher: EmuLauncher,
    private val apkLauncher: ApkLauncher,
) {
    private var raConfigPath: String? = null
    private val CONFIG_VERSION = 3

    fun syncRetroArchConfig(root: File) {
        val configDir = File(root, "Config")
        configDir.mkdirs()
        val localConfig = File(configDir, "retroarch.cfg")
        val hashFile = File(configDir, ".ra_config_hash")

        val raPackage = settings.retroArchPackage
        val sourceConfig = File("/storage/emulated/0/Android/data/$raPackage/files/retroarch.cfg")

        if (!sourceConfig.exists()) {
            if (!localConfig.exists()) {
                localConfig.writeText(buildMinimalConfig(root.absolutePath))
            }
            raConfigPath = localConfig.absolutePath
            return
        }

        val sourceBytes = try { sourceConfig.readBytes() } catch (_: IOException) {
            if (!localConfig.exists()) localConfig.writeText(buildMinimalConfig(root.absolutePath))
            raConfigPath = localConfig.absolutePath
            return
        }
        val sourceHash = sha256(sourceBytes, "$CONFIG_VERSION:${settings.raUsername}:${settings.raToken}".toByteArray())
        val storedHash = if (hashFile.exists()) try { hashFile.readText().trim() } catch (_: IOException) { "" } else ""

        if (sourceHash != storedHash || !localConfig.exists()) {
            val patched = patchRetroArchConfig(String(sourceBytes), root.absolutePath)
            localConfig.writeText(patched)
            hashFile.writeText(sourceHash)
        }

        raConfigPath = localConfig.absolutePath
    }

    private fun buildGameConfig(game: Game, resume: Boolean = false): String? {
        val base = raConfigPath ?: return null
        val baseConfig = try { File(base).readText() } catch (_: IOException) { return null }
        val cannoliRoot = File(settings.sdCardRoot)
        val romName = normalizedRomName(game)
        val stateDir = File(cannoliRoot, "Save States/${game.platformTag}/$romName")
        stateDir.mkdirs()
        val gameOverrides = buildMap {
            put("savestate_directory", stateDir.absolutePath)
            put("sort_savestates_by_content_enable", "false")
            put("state_slot", "0")
            if (resume) {
                put("savestate_auto_load", "true")
            }
        }
        val patched = applyOverrides(baseConfig, gameOverrides)
        val launchConfig = File(cannoliRoot, "Config/retroarch_launch.cfg")
        launchConfig.writeText(patched)
        return launchConfig.absolutePath
    }

    private fun buildMinimalConfig(rootPath: String) = buildString {
        appendLine("savefile_directory = \"$rootPath/Saves\"")
        appendLine("savestate_directory = \"$rootPath/Save States\"")
        appendLine("system_directory = \"$rootPath/BIOS\"")
        appendLine("sort_savefiles_by_content_enable = \"true\"")
        appendLine("savestate_file_compression = \"false\"")
        appendLine("config_save_on_exit = \"false\"")
    }

    private fun patchRetroArchConfig(source: String, rootPath: String): String {
        val raUser = settings.raUsername
        val raToken = settings.raToken
        val overrides = buildMap {
            put("savefile_directory", "$rootPath/Saves")
            put("savestate_directory", "$rootPath/Save States")
            put("system_directory", "$rootPath/BIOS")
            put("screenshot_directory", "$rootPath/Media/Screenshots")
            put("recording_output_directory", "$rootPath/Media/Recordings")
            put("sort_savefiles_by_content_enable", "true")
            put("savestate_file_compression", "false")
            put("savestate_block_format", "false")
            put("savestate_thumbnail_enable", "true")
            put("savestate_auto_save", "true")
            put("config_save_on_exit", "false")
            if (raUser.isNotEmpty() && raToken.isNotEmpty()) {
                put("cheevos_enable", "true")
                put("cheevos_username", raUser)
                put("cheevos_token", raToken)
            }
        }
        return applyOverrides(source, overrides)
    }

    private fun applyOverrides(source: String, overrides: Map<String, String>): String {
        val applied = mutableSetOf<String>()
        val lines = source.lines().map { line ->
            val trimmed = line.trimStart()
            val key = trimmed.substringBefore('=').trim().removePrefix("# ")
            if (key in overrides) {
                applied.add(key)
                "$key = \"${overrides[key]}\""
            } else line
        }.toMutableList()
        for ((key, value) in overrides) {
            if (key !in applied) lines.add("$key = \"$value\"")
        }
        return lines.joinToString("\n")
    }

    private fun sha256(vararg parts: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (part in parts) digest.update(part)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun createTempM3u(game: Game): File {
        val m3uDir = File(context.cacheDir, "m3u")
        m3uDir.mkdirs()
        val m3uFile = File(m3uDir, "${game.displayName}.m3u")
        m3uFile.writeText(checkNotNull(game.discFiles).joinToString("\n") { it.absolutePath } + "\n")
        return m3uFile
    }

    fun resolveLaunchFile(game: Game): File? {
        if (game.discFiles != null) return createTempM3u(game)
        if (ArchiveExtractor.isArchive(game.file)) {
            return ArchiveExtractor.extract(game.file, context.cacheDir)
        }
        return game.file
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
        val romName = normalizedRomName(game)
        val stateBase = File(cannoliRoot, "Save States/${game.platformTag}/$romName/$romName.state")
        val slotManager = SaveSlotManager(stateBase.absolutePath)
        return slotManager.slots
            .filter { File(slotManager.statePath(it)).exists() }
            .maxByOrNull { File(slotManager.statePath(it)).lastModified() }
            ?.index
    }

    private fun hasAutoState(game: Game): Boolean {
        val cannoliRoot = File(settings.sdCardRoot)
        val romName = normalizedRomName(game)
        return File(cannoliRoot, "Save States/${game.platformTag}/$romName/$romName.state.auto").exists()
    }

    fun findResumableGames(games: List<Game>): Set<String> {
        val result = mutableSetOf<String>()
        for (game in games) {
            if (game.isSubfolder) continue
            if (!hasAutoState(game)) continue
            val target = game.launchTarget
            if (target is LaunchTarget.RetroArch || target is LaunchTarget.Embedded || getEmbeddedCorePath(game) != null) {
                result.add(game.file.absolutePath)
            }
        }
        return result
    }

    fun launchGame(game: Game): DialogState? {
        val launchFile = resolveLaunchFile(game)
            ?: return DialogState.LaunchError("Failed to extract archive")

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
                                launchEmbedded(game.copy(file = launchFile), embeddedCorePath, originalRomPath = game.file.absolutePath)
                                return null
                            }
                        }
                        syncRetroArchConfig(File(settings.sdCardRoot))
                        val launchConfig = buildGameConfig(game) ?: raConfigPath
                        retroArchLauncher.launch(launchFile, core, launchConfig)
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
                launchEmbedded(game.copy(file = launchFile), target.corePath, originalRomPath = game.file.absolutePath)
                return null
            }
        }

        return toLaunchDialog(result)
    }

    private fun promoteAutoState(game: Game) {
        val cannoliRoot = File(settings.sdCardRoot)
        val romName = normalizedRomName(game)
        val stateDir = File(cannoliRoot, "Save States/${game.platformTag}/$romName")
        val slot0 = File(stateDir, "$romName.state")
        val auto = File(stateDir, "$romName.state.auto")
        if (auto.exists() && (!slot0.exists() || auto.lastModified() > slot0.lastModified())) {
            auto.copyTo(slot0, overwrite = true)
        }
    }

    fun resumeGame(game: Game) {
        promoteAutoState(game)
        val launchFile = resolveLaunchFile(game) ?: return
        val embeddedCorePath = getEmbeddedCorePath(game)
        if (embeddedCorePath != null) {
            launchEmbedded(game.copy(file = launchFile), embeddedCorePath, 0, originalRomPath = game.file.absolutePath)
            return
        }
        val gameOverride = platformResolver.getGameOverride(game.file.absolutePath)
        val core = gameOverride?.coreId ?: platformResolver.getCoreName(game.platformTag) ?: return
        syncRetroArchConfig(File(settings.sdCardRoot))
        val launchConfig = buildGameConfig(game, resume = true) ?: raConfigPath
        retroArchLauncher.launch(launchFile, core, launchConfig)
    }

    fun toLaunchDialog(result: LaunchResult): DialogState? {
        return when (result) {
            is LaunchResult.CoreNotInstalled -> DialogState.MissingCore(result.coreName)
            is LaunchResult.AppNotInstalled -> {
                val appName = try {
                    val info = context.packageManager.getApplicationInfo(result.packageName, 0)
                    context.packageManager.getApplicationLabel(info).toString()
                } catch (_: PackageManager.NameNotFoundException) {
                    result.packageName
                }
                DialogState.MissingApp(appName, result.packageName)
            }
            is LaunchResult.Error -> DialogState.LaunchError(result.message)
            LaunchResult.Success -> null
        }
    }

    fun launchEmbedded(game: Game, corePath: String, resumeSlot: Int = -1, originalRomPath: String? = null) {
        val cannoliRoot = File(settings.sdCardRoot)
        val romName = normalizedRomName(game)
        val saveDir = File(cannoliRoot, "Saves/${game.platformTag}")
        saveDir.mkdirs()

        val intent = Intent(context, LibretroActivity::class.java).apply {
            putExtra("game_title", game.displayName)
            putExtra("core_path", corePath)
            putExtra("rom_path", game.file.absolutePath)
            if (originalRomPath != null && originalRomPath != game.file.absolutePath) {
                putExtra("original_rom_path", originalRomPath)
            }
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
            putExtra("graphics_backend", settings.graphicsBackend)
            putExtra("ra_username", settings.raUsername)
            putExtra("ra_token", settings.raToken)
            val raIdFile = File(File(settings.sdCardRoot, "Config"), "ra_game_ids.txt")
            if (raIdFile.exists()) {
                val romAbs = game.file.absolutePath
                raIdFile.readLines().firstOrNull { it.startsWith("$romAbs=") }
                    ?.substringAfter('=')?.trim()?.toIntOrNull()
                    ?.let { putExtra("ra_game_id", it) }
            }
            if (resumeSlot >= 0) putExtra("resume_slot", resumeSlot)
        }
        context.startActivity(intent)
    }

    private fun normalizedRomName(game: Game): String =
        Normalizer.normalize(game.file.nameWithoutExtension, Normalizer.Form.NFC)

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
