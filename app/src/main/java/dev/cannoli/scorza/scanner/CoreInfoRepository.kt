package dev.cannoli.scorza.scanner

import android.content.res.AssetManager

data class CoreInfo(
    val id: String,
    val displayName: String,
    val databases: List<String>
)

class CoreInfoRepository(private val assets: AssetManager) {

    @Volatile private var cores = listOf<CoreInfo>()
    @Volatile private var coreById = mapOf<String, CoreInfo>()

    private val tagToDatabases = mapOf(
        "GB" to listOf("Nintendo - Game Boy"),
        "GBC" to listOf("Nintendo - Game Boy Color"),
        "GBA" to listOf("Nintendo - Game Boy Advance"),
        "NES" to listOf("Nintendo - Nintendo Entertainment System", "Nintendo - Family Computer Disk System"),
        "FDS" to listOf("Nintendo - Family Computer Disk System"),
        "SNES" to listOf("Nintendo - Super Nintendo Entertainment System", "Nintendo - Sufami Turbo", "Nintendo - Satellaview"),
        "N64" to listOf("Nintendo - Nintendo 64"),
        "NDS" to listOf("Nintendo - Nintendo DS"),
        "GG" to listOf("Sega - Game Gear"),
        "SMS" to listOf("Sega - Master System - Mark III"),
        "MD" to listOf("Sega - Mega Drive - Genesis"),
        "SG1000" to listOf("Sega - SG-1000"),
        "32X" to listOf("Sega - 32X"),
        "SEGACD" to listOf("Sega - Mega-CD - Sega CD"),
        "SATURN" to listOf("Sega - Saturn"),
        "PS" to listOf("Sony - PlayStation"),
        "PSP" to listOf("Sony - PlayStation Portable"),
        "DC" to listOf("Sega - Dreamcast"),
        "LYNX" to listOf("Atari - Lynx"),
        "JAGUAR" to listOf("Atari - Jaguar"),
        "ATARI2600" to listOf("Atari - 2600"),
        "ATARI5200" to listOf("Atari - 5200"),
        "ATARI7800" to listOf("Atari - 7800"),
        "PCE" to listOf("NEC - PC Engine - TurboGrafx 16", "NEC - PC Engine CD - TurboGrafx-CD"),
        "SUPERGRAFX" to listOf("NEC - PC Engine SuperGrafx"),
        "PCFX" to listOf("NEC - PC-FX"),
        "NEOGEO" to listOf("SNK - Neo Geo"),
        "NGP" to listOf("SNK - Neo Geo Pocket"),
        "NGPC" to listOf("SNK - Neo Geo Pocket Color"),
        "WS" to listOf("Bandai - WonderSwan"),
        "WSC" to listOf("Bandai - WonderSwan Color"),
        "MAME" to listOf("MAME", "MAME 2003-Plus"),
        "FBN" to listOf("FBNeo - Arcade Games"),
        "VIRTUALBOY" to listOf("Nintendo - Virtual Boy"),
        "POKEMINI" to listOf("Nintendo - Pokemon Mini"),
        "COLECOVISION" to listOf("Coleco - ColecoVision"),
        "VECTREX" to listOf("GCE - Vectrex"),
        "INTELLIVISION" to listOf("Mattel - Intellivision"),
        "AMIGA" to listOf("Commodore - Amiga"),
        "AMIGA500" to listOf("Commodore - Amiga"),
        "AMIGA1200" to listOf("Commodore - Amiga"),
        "DOS" to listOf("DOS"),
        "SCUMMVM" to listOf("ScummVM")
    )

    fun load() {
        val result = mutableListOf<CoreInfo>()
        val files = try { assets.list("core_info") ?: emptyArray() } catch (_: Exception) { emptyArray() }
        for (filename in files) {
            if (!filename.endsWith(".info")) continue
            val id = filename.removeSuffix(".info")
            var displayName: String? = null
            val databases = mutableListOf<String>()
            try {
                assets.open("core_info/$filename").bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (displayName == null && trimmed.startsWith("corename")) {
                            displayName = trimmed.substringAfter('=').trim().removeSurrounding("\"")
                        } else if (databases.isEmpty() && trimmed.startsWith("database")) {
                            val value = trimmed.substringAfter('=').trim().removeSurrounding("\"")
                            databases.addAll(value.split('|').map { it.trim() })
                        }
                        if (displayName != null && databases.isNotEmpty()) break
                    }
                }
            } catch (_: Exception) {}
            if (displayName != null) {
                result.add(CoreInfo(id, displayName!!, databases))
            }
        }
        cores = result
        coreById = result.associateBy { it.id }
    }

    fun getDisplayName(coreId: String): String {
        return coreById[coreId]?.displayName ?: coreId
    }

    fun getCoresForTag(tag: String): List<CoreInfo> {
        val dbs = tagToDatabases[tag] ?: return emptyList()
        return cores.filter { core -> core.databases.any { it in dbs } }
            .sortedBy { it.displayName }
    }
}
