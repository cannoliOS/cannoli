package dev.cannoli.scorza.settings

import dev.cannoli.scorza.libretro.ShortcutAction
import dev.cannoli.scorza.util.IniParser
import dev.cannoli.scorza.util.IniWriter
import java.io.File

class GlobalOverridesManager(private val sdCardRoot: () -> String) {

    private fun iniFile() = File(sdCardRoot(), "Config/Overrides/global.ini")

    fun readControls(): Map<String, Int> {
        val ini = IniParser.parse(iniFile())
        val map = mutableMapOf<String, Int>()
        for ((key, value) in ini.getSection("controls")) {
            value.toIntOrNull()?.let { map[key] = it }
        }
        return map
    }

    fun readShortcuts(): Map<ShortcutAction, Set<Int>> {
        val ini = IniParser.parse(iniFile())
        val map = mutableMapOf<ShortcutAction, Set<Int>>()
        for ((key, value) in ini.getSection("shortcuts")) {
            val action = try { ShortcutAction.valueOf(key) } catch (_: Exception) { continue }
            val chord = if (value.isEmpty()) emptySet()
            else value.split(",").mapNotNull { it.toIntOrNull() }.toSet()
            map[action] = chord
        }
        return map
    }

    fun saveControls(controls: Map<String, Int>) {
        IniWriter.mergeWrite(iniFile(), "controls", controls.mapValues { it.value.toString() })
    }

    fun saveShortcuts(shortcuts: Map<ShortcutAction, Set<Int>>) {
        IniWriter.mergeWrite(
            iniFile(), "shortcuts",
            shortcuts.mapKeys { it.key.name }.mapValues { it.value.joinToString(",") }
        )
    }
}
