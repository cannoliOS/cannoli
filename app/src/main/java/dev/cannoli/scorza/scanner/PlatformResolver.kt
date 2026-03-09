package dev.cannoli.scorza.scanner

import dev.cannoli.scorza.model.LaunchTarget
import dev.cannoli.scorza.model.Platform
import dev.cannoli.scorza.util.IniData
import dev.cannoli.scorza.util.IniParser
import dev.cannoli.scorza.util.sortedNatural
import org.json.JSONObject
import java.io.File

data class GameCoreOverride(val coreId: String, val runner: String?)

class PlatformResolver(
    private val cannoliRoot: File,
    private val coreInfo: CoreInfoRepository? = null
) {

    private val defaultCores = mapOf(
        "GB" to "gambatte_libretro",
        "GBC" to "gambatte_libretro",
        "GBA" to "mgba_libretro",
        "NES" to "nestopia_libretro",
        "SNES" to "snes9x_libretro",
        "N64" to "mupen64plus_next_libretro",
        "NDS" to "melonds_libretro",
        "GG" to "genesis_plus_gx_libretro",
        "SMS" to "genesis_plus_gx_libretro",
        "MD" to "genesis_plus_gx_libretro",
        "32X" to "picodrive_libretro",
        "SCD" to "genesis_plus_gx_libretro",
        "SAT" to "mednafen_saturn_libretro",
        "PS" to "pcsx_rearmed_libretro",
        "PSP" to "ppsspp_libretro",
        "LYNX" to "mednafen_lynx_libretro",
        "JAGU" to "virtualjaguar_libretro",
        "PCE" to "mednafen_pce_libretro",
        "PCFX" to "mednafen_pcfx_libretro",
        "NGP" to "mednafen_ngp_libretro",
        "WSC" to "mednafen_wswan_libretro",
        "MAME" to "mame2003_plus_libretro",
        "FBN" to "fbneo_libretro",
        "VBOY" to "mednafen_vb_libretro",
        "POKE" to "pokemini_libretro",
        "AMOR" to "puae_libretro",
        "DOS" to "dosbox_pure_libretro",
        "SCUM" to "scummvm_libretro"
    )


    private val defaultPlatformNames = mapOf(
        "GB" to "Game Boy",
        "GBC" to "Game Boy Color",
        "GBA" to "Game Boy Advance",
        "NES" to "NES",
        "SNES" to "Super Nintendo",
        "N64" to "Nintendo 64",
        "NDS" to "Nintendo DS",
        "GG" to "Game Gear",
        "SMS" to "Master System",
        "MD" to "Sega Genesis",
        "32X" to "Sega 32X",
        "SCD" to "Sega CD",
        "SAT" to "Sega Saturn",
        "PS" to "PlayStation",
        "PS2" to "PlayStation 2",
        "PSP" to "PSP",
        "DC" to "Dreamcast",
        "GC" to "GameCube",
        "WII" to "Wii",
        "WIIU" to "Wii U",
        "3DS" to "Nintendo 3DS",
        "VITA" to "PS Vita",
        "PS3" to "PlayStation 3",
        "NSW" to "Nintendo Switch",
        "LYNX" to "Atari Lynx",
        "JAGU" to "Atari Jaguar",
        "PCE" to "PC Engine",
        "NGP" to "Neo Geo Pocket",
        "WSC" to "WonderSwan",
        "MAME" to "Arcade (MAME)",
        "FBN" to "Arcade (FBNeo)",
        "VBOY" to "Virtual Boy",
        "POKE" to "Pokemon Mini",
        "AMOR" to "Amiga",
        "DOS" to "DOS",
        "SCUM" to "ScummVM"
    )

    private var ini: IniData = IniData(emptyMap())
    private var userCores: MutableMap<String, String> = mutableMapOf()
    private var userRunners: MutableMap<String, String> = mutableMapOf()
    private var gameOverrides: MutableMap<String, GameCoreOverride> = mutableMapOf()
    private val coresFile get() = File(cannoliRoot, "Config/cores.json")

    fun load() {
        val configFile = File(cannoliRoot, "Config/platforms.ini")
        if (!configFile.exists()) {
            writeDefaultIni(configFile)
        }
        ini = IniParser.parse(configFile)
        loadCoreMappings()
    }

    private fun loadCoreMappings() {
        userCores.clear()
        userRunners.clear()
        gameOverrides.clear()
        if (!coresFile.exists()) return
        try {
            val json = JSONObject(coresFile.readText())
            val cores = json.optJSONObject("cores")
            if (cores != null) for (key in cores.keys()) userCores[key] = cores.getString(key)
            val runners = json.optJSONObject("runners")
            if (runners != null) for (key in runners.keys()) userRunners[key] = runners.getString(key)
            val overrides = json.optJSONObject("gameOverrides")
            if (overrides != null) {
                for (path in overrides.keys()) {
                    val obj = overrides.getJSONObject(path)
                    gameOverrides[path] = GameCoreOverride(
                        coreId = obj.getString("core"),
                        runner = obj.optString("runner", "").ifEmpty { null }
                    )
                }
            }
        } catch (_: Exception) {}
    }

    fun reloadCoreMappings() {
        loadCoreMappings()
    }

    fun saveCoreMappings() {
        val json = JSONObject()
        val cores = JSONObject()
        for ((tag, core) in userCores) cores.put(tag, core)
        json.put("cores", cores)
        if (userRunners.isNotEmpty()) {
            val runners = JSONObject()
            for ((tag, runner) in userRunners) runners.put(tag, runner)
            json.put("runners", runners)
        }
        if (gameOverrides.isNotEmpty()) {
            val overrides = JSONObject()
            for ((path, ov) in gameOverrides) {
                val obj = JSONObject()
                obj.put("core", ov.coreId)
                if (ov.runner != null) obj.put("runner", ov.runner)
                overrides.put(path, obj)
            }
            json.put("gameOverrides", overrides)
        }
        coresFile.parentFile?.mkdirs()
        coresFile.writeText(json.toString(2))
    }

    fun getCoreMapping(tag: String): String {
        return userCores[tag] ?: defaultCores[tag] ?: ""
    }

    fun setCoreMapping(tag: String, core: String, runner: String? = null) {
        if (core.isBlank() || core == defaultCores[tag]) {
            userCores.remove(tag)
        } else {
            userCores[tag] = core
        }
        if (runner != null) {
            userRunners[tag] = runner
        } else {
            userRunners.remove(tag)
        }
    }

    fun getRunnerPreference(tag: String): String? = userRunners[tag]

    fun getGameOverride(gamePath: String): GameCoreOverride? = gameOverrides[gamePath]

    fun setGameOverride(gamePath: String, coreId: String?, runner: String?) {
        if (coreId == null) {
            gameOverrides.remove(gamePath)
        } else {
            gameOverrides[gamePath] = GameCoreOverride(coreId, runner)
        }
        saveCoreMappings()
    }

    fun getCoreDisplayName(coreId: String): String {
        return coreInfo?.getDisplayName(coreId) ?: coreId
    }

    fun getCoresForTag(tag: String): List<CoreInfo> {
        return coreInfo?.getCoresForTag(tag) ?: emptyList()
    }

    fun getRunnerLabel(tag: String, coreId: String): String {
        val romsDir = File(cannoliRoot, "Roms")
        if (File(romsDir, "$tag/.emu_launch").exists()) return "External"
        val override = userRunners[tag]
        if (override != null) return override
        val coresDir = File(cannoliRoot, "Cores")
        if (File(coresDir, "${coreId}_android.so").exists()) return "Internal"
        return "RetroArch"
    }

    fun getDetailedMappings(): List<dev.cannoli.scorza.ui.screens.DialogState.CoreMappingEntry> {
        val tags = (defaultCores.keys + userCores.keys)
        return tags.map { tag ->
            val coreId = getCoreMapping(tag)
            dev.cannoli.scorza.ui.screens.DialogState.CoreMappingEntry(
                tag = tag,
                platformName = getDisplayName(tag),
                coreDisplayName = if (coreId.isBlank()) "None" else getCoreDisplayName(coreId),
                runnerLabel = if (coreId.isBlank()) "" else getRunnerLabel(tag, coreId)
            )
        }.sortedNatural { it.platformName }
    }

    fun getCorePickerOptions(tag: String): List<dev.cannoli.scorza.ui.screens.DialogState.CorePickerOption> {
        val cores = getCoresForTag(tag)
        val coresDir = File(cannoliRoot, "Cores")
        return cores.flatMap { core ->
            val hasInternal = File(coresDir, "${core.id}_android.so").exists()
            if (hasInternal) {
                listOf(
                    dev.cannoli.scorza.ui.screens.DialogState.CorePickerOption(
                        coreId = core.id,
                        displayName = core.displayName,
                        runnerLabel = "Internal"
                    ),
                    dev.cannoli.scorza.ui.screens.DialogState.CorePickerOption(
                        coreId = core.id,
                        displayName = core.displayName,
                        runnerLabel = "RetroArch"
                    )
                )
            } else {
                listOf(
                    dev.cannoli.scorza.ui.screens.DialogState.CorePickerOption(
                        coreId = core.id,
                        displayName = core.displayName,
                        runnerLabel = getRunnerLabel(tag, core.id)
                    )
                )
            }
        }.sortedNatural { it.displayName }
    }

    fun getDisplayName(tag: String): String {
        return ini.get("platforms", tag)
            ?: defaultPlatformNames[tag]
            ?: tag
    }

    fun setDisplayName(tag: String, name: String) {
        val configFile = File(cannoliRoot, "Config/platforms.ini")
        val currentNames = ini.getSection("platforms").toMutableMap()
        val defaultName = defaultPlatformNames[tag]
        if (name == defaultName || name == tag) {
            currentNames.remove(tag)
        } else {
            currentNames[tag] = name
        }
        val cores = ini.getSection("cores")
        val sb = StringBuilder()
        sb.appendLine("[platforms]")
        for ((t, n) in currentNames) {
            sb.appendLine("%-6s = %s".format(t, n))
        }
        sb.appendLine()
        sb.appendLine("[cores]")
        for ((t, c) in cores) {
            sb.appendLine("%-6s = %s".format(t, c))
        }
        configFile.parentFile?.mkdirs()
        configFile.writeText(sb.toString())
        ini = IniParser.parse(configFile)
    }

    fun getCoreName(tag: String): String? {
        return userCores[tag]
            ?: ini.get("cores", tag)
            ?: defaultCores[tag]
    }

    fun getEmuLaunch(tag: String, romsDir: File): LaunchTarget.EmuLaunch? {
        val emuFile = File(romsDir, "$tag/.emu_launch")
        if (!emuFile.exists()) return null

        val emu = IniParser.parse(emuFile)
        val pkg = emu.get("emulator", "package") ?: return null
        val activity = emu.get("emulator", "activity") ?: return null
        val action = emu.get("emulator", "action") ?: "android.intent.action.VIEW"

        return LaunchTarget.EmuLaunch(pkg, activity, action)
    }

    fun resolvePlatform(tag: String, romsDir: File, gameCount: Int): Platform {
        val hasEmu = File(romsDir, "$tag/.emu_launch").exists()
        return Platform(
            tag = tag,
            displayName = getDisplayName(tag),
            coreName = getCoreName(tag),
            hasEmuLaunch = hasEmu,
            gameCount = gameCount
        )
    }

    private fun writeDefaultIni(file: File) {
        file.parentFile?.mkdirs()
        val sb = StringBuilder()
        sb.appendLine("[platforms]")
        for ((tag, name) in defaultPlatformNames) {
            sb.appendLine("%-6s = %s".format(tag, name))
        }
        sb.appendLine()
        sb.appendLine("[cores]")
        sb.appendLine("; Optional - overrides bundled TAG->core lookup")
        sb.appendLine("; GBA = mgba_libretro")
        file.writeText(sb.toString())
    }
}
