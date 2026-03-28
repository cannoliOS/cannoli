package dev.cannoli.scorza.libretro

import dev.cannoli.scorza.util.IniData
import dev.cannoli.scorza.util.IniParser
import dev.cannoli.scorza.util.IniWriter
import java.io.File

enum class GuideType { PDF, TXT, IMAGE }

data class GuideFile(val file: File, val type: GuideType) {
    val name: String get() = file.name
}

class GuideManager(
    cannoliRoot: String,
    private val platformTag: String,
    private val gameTitle: String
) {
    private val guidesDir = File(cannoliRoot, "Guides/$platformTag/$gameTitle")
    private val positionsFile = File(cannoliRoot, "Config/guide_positions.ini")

    private val supportedExtensions = mapOf(
        "pdf" to GuideType.PDF,
        "txt" to GuideType.TXT,
        "png" to GuideType.IMAGE,
        "jpg" to GuideType.IMAGE,
        "jpeg" to GuideType.IMAGE
    )

    fun findGuides(): List<GuideFile> {
        if (!guidesDir.isDirectory) return emptyList()
        val files = guidesDir.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && supportedExtensions.containsKey(it.extension.lowercase()) }
            .sortedBy { it.name.lowercase() }
            .map { GuideFile(it, supportedExtensions[it.extension.lowercase()]!!) }
    }

    private fun positionKey(file: File): String =
        "$platformTag/$gameTitle/${file.name}"

    fun loadPosition(file: File): Int {
        val ini = IniParser.parse(positionsFile)
        return ini.get("positions", positionKey(file))?.toIntOrNull() ?: 0
    }

    fun loadScrollY(file: File): Int {
        val ini = IniParser.parse(positionsFile)
        return ini.get("scroll_y", positionKey(file))?.toIntOrNull() ?: 0
    }

    fun loadZoom(file: File): Int {
        val ini = IniParser.parse(positionsFile)
        return ini.get("zoom", positionKey(file))?.toIntOrNull() ?: 1
    }

    fun loadScrollX(file: File): Int {
        val ini = IniParser.parse(positionsFile)
        return ini.get("scroll_x", positionKey(file))?.toIntOrNull() ?: 0
    }

    fun save(file: File, position: Int, scrollY: Int, scrollX: Int, zoom: Int) {
        val key = positionKey(file)
        val ini = if (positionsFile.exists()) IniParser.parse(positionsFile) else IniData(emptyMap())
        val sections = ini.sections.toMutableMap()
        fun put(section: String, value: String) {
            val map = (sections[section] ?: emptyMap()).toMutableMap()
            map[key] = value
            sections[section] = map
        }
        put("positions", position.toString())
        put("scroll_y", scrollY.toString())
        put("scroll_x", scrollX.toString())
        put("zoom", zoom.toString())
        IniWriter.write(positionsFile, sections)
    }
}
