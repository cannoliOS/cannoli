package dev.cannoli.scorza.scanner

import dev.cannoli.scorza.model.LaunchTarget
import dev.cannoli.scorza.model.Platform
import dev.cannoli.scorza.util.IniData
import dev.cannoli.scorza.util.IniParser
import java.io.File

class PlatformResolver(private val cannoliRoot: File) {

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

    fun load() {
        val configFile = File(cannoliRoot, "Config/platforms.ini")
        if (!configFile.exists()) {
            writeDefaultIni(configFile)
        }
        ini = IniParser.parse(configFile)
    }

    fun getDisplayName(tag: String): String {
        return ini.get("platforms", tag)
            ?: defaultPlatformNames[tag]
            ?: tag
    }

    fun getCoreName(tag: String): String? {
        return ini.get("cores", tag)
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
