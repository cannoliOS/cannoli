package dev.cannoli.scorza.input

import dev.cannoli.scorza.util.IniParser
import dev.cannoli.scorza.util.IniWriter
import java.io.File

class ControllerConfigStore(private val configRoot: String) {

    private fun fileFor(descriptor: String): File {
        val sanitized = descriptor.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(configRoot, "Config/Controllers/$sanitized.ini")
    }

    fun readControls(descriptor: String): Map<String, Int> {
        val ini = IniParser.parse(fileFor(descriptor))
        val map = mutableMapOf<String, Int>()
        for ((key, value) in ini.getSection("controls")) {
            value.toIntOrNull()?.let { map[key] = it }
        }
        return map
    }

    fun saveControls(descriptor: String, name: String, controls: Map<String, Int>) {
        val file = fileFor(descriptor)
        val identity = mapOf("name" to name)
        IniWriter.mergeWrite(file, "identity", identity)
        IniWriter.mergeWrite(file, "controls", controls.mapValues { it.value.toString() })
    }

    fun hasConfig(descriptor: String): Boolean = fileFor(descriptor).exists()
}
