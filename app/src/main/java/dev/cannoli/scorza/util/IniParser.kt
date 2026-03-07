package dev.cannoli.scorza.util

import java.io.File

data class IniData(
    val sections: Map<String, Map<String, String>>
) {
    fun get(section: String, key: String): String? = sections[section]?.get(key)

    fun getSection(section: String): Map<String, String> = sections[section] ?: emptyMap()
}

object IniParser {

    fun parse(file: File): IniData {
        if (!file.exists()) return IniData(emptyMap())
        return parse(file.readText())
    }

    fun parse(text: String): IniData {
        val sections = mutableMapOf<String, MutableMap<String, String>>()
        var currentSection = ""

        for (line in text.lines()) {
            val trimmed = line.trim()

            if (trimmed.isEmpty() || trimmed.startsWith(";")) continue

            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                currentSection = trimmed.substring(1, trimmed.length - 1).trim()
                sections.getOrPut(currentSection) { mutableMapOf() }
                continue
            }

            val eqIndex = trimmed.indexOf('=')
            if (eqIndex > 0) {
                val key = trimmed.substring(0, eqIndex).trim()
                val value = trimmed.substring(eqIndex + 1).trim()
                sections.getOrPut(currentSection) { mutableMapOf() }[key] = value
            }
        }

        return IniData(sections)
    }
}
