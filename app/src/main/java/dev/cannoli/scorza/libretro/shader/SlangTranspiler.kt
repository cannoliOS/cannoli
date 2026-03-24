package dev.cannoli.scorza.libretro.shader

import android.util.Log
import java.io.File
import java.security.MessageDigest

object SlangTranspiler {

    private const val TAG = "SlangTranspiler"

    init {
        System.loadLibrary("slang_transpiler")
    }

    var cacheDir: File? = null

    fun isVulkanGLSL(source: String): Boolean =
        source.contains("#version 450") || source.contains("layout(push_constant)")

    fun splitSlangStages(source: String): Pair<String, String> {
        val lines = source.lines()
        val common = StringBuilder()
        val vertex = StringBuilder()
        val fragment = StringBuilder()
        var current: StringBuilder = common

        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed == "#pragma stage vertex" -> { current = vertex; continue }
                trimmed == "#pragma stage fragment" -> { current = fragment; continue }
            }
            current.appendLine(line)
        }

        val commonStr = common.toString()
        return (commonStr + vertex.toString()) to (commonStr + fragment.toString())
    }

    fun transpile(source: String, isVertex: Boolean, basePath: String? = null): String? {
        val cacheKey = sourceHash(source, isVertex)
        val cached = loadFromCache(cacheKey)
        if (cached != null) return cached

        val resolved = basePath?.let { resolveIncludes(source, it) } ?: source
        val raw = nativeTranspile(resolved, isVertex)
        if (raw == null) {
            Log.e(TAG, "Transpilation failed: ${nativeGetLastError()}")
            return null
        }
        val result = flattenUBOs(raw)
        saveToCache(cacheKey, result)
        return result
    }

    private val layoutUboPattern = Regex(
        """layout\(std140\)\s+uniform\s+(\w+)\s*\{([^}]+)\}\s*(\w+)\s*;""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val structPattern = Regex(
        """struct\s+(\w+)\s*\{([^}]+)\}\s*;""",
        RegexOption.DOT_MATCHES_ALL
    )

    private fun flattenUBOs(source: String): String {
        var result = source

        for (match in layoutUboPattern.findAll(source)) {
            val instanceName = match.groupValues[3]
            val body = match.groupValues[2]
            result = result.replace(match.value, flattenBlock(body, instanceName))
            result = result.replace("${instanceName}.", "${instanceName}_")
        }

        for (match in structPattern.findAll(result)) {
            val structName = match.groupValues[1]
            val body = match.groupValues[2]
            val uniformPattern = Regex("""uniform\s+$structName\s+(\w+)\s*;""")
            val uniformMatch = uniformPattern.find(result) ?: continue
            val instanceName = uniformMatch.groupValues[1]
            result = result.replace(match.value, "")
            result = result.replace(uniformMatch.value, flattenBlock(body, instanceName))
            result = result.replace("${instanceName}.", "${instanceName}_")
        }

        return result
    }

    private fun flattenBlock(body: String, instanceName: String): String {
        val uniforms = StringBuilder()
        for (line in body.lines()) {
            val trimmed = line.trim().removeSuffix(";").trim()
            if (trimmed.isEmpty()) continue
            val parts = trimmed.split("\\s+".toRegex())
            if (parts.size < 2) continue
            val name = parts.last()
            val rawType = parts.dropLast(1).joinToString(" ")
                .replace("highp ", "").replace("mediump ", "").replace("lowp ", "")
            val glType = if (rawType == "uint") "highp int" else "highp $rawType"
            uniforms.appendLine("uniform $glType ${instanceName}_$name;")
        }
        return uniforms.toString().trimEnd()
    }

    private fun resolveIncludes(source: String, basePath: String): String {
        val sb = StringBuilder()
        for (line in source.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#include")) {
                val path = trimmed.substringAfter('"').substringBefore('"')
                if (path.isNotEmpty()) {
                    val file = File(basePath, path)
                    if (file.exists()) {
                        sb.appendLine(resolveIncludes(file.readText(), file.parent ?: basePath))
                        continue
                    }
                }
            }
            sb.appendLine(line)
        }
        return sb.toString()
    }

    private fun sourceHash(source: String, isVertex: Boolean): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(source.toByteArray())
        digest.update(if (isVertex) 1.toByte() else 0.toByte())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun loadFromCache(key: String): String? {
        val dir = cacheDir ?: return null
        val file = File(dir, "$key.gles")
        return if (file.exists()) try { file.readText() } catch (_: Exception) { null } else null
    }

    private fun saveToCache(key: String, source: String) {
        val dir = cacheDir ?: return
        try {
            dir.mkdirs()
            File(dir, "$key.gles").writeText(source)
        } catch (_: Exception) { }
    }

    fun getLastError(): String? = nativeGetLastError()

    private external fun nativeTranspile(source: String, isVertex: Boolean): String?
    private external fun nativeGetLastError(): String?
}
