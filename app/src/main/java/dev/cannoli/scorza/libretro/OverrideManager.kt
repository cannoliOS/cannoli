package dev.cannoli.scorza.libretro

import dev.cannoli.scorza.util.IniParser
import dev.cannoli.scorza.util.IniWriter
import java.io.File

class OverrideManager(
    cannoliRoot: String,
    private val coreName: String,
    private val platformTag: String,
    private val gameBaseName: String
) {
    private val overridesDir = File(cannoliRoot, "Config/Overrides")
    private val globalFile = File(overridesDir, "global.ini")
    private val coreFile = File(overridesDir, "Cores/$coreName.ini")
    private val gameFile = File(overridesDir, "Games/$platformTag/$gameBaseName.ini")

    data class Settings(
        var scalingMode: ScalingMode = ScalingMode.CORE_REPORTED,
        var screenEffect: ScreenEffect = ScreenEffect.NONE,
        var sharpness: Sharpness = Sharpness.SHARP,
        var debugHud: Boolean = false,
        var maxFfSpeed: Int = 4,
        var crtCurvature: Float = 1.7f,
        var crtScanline: Float = 0.75f,
        var crtMaskDark: Float = 0.3f,
        var crtVignette: Float = 0.85f,
        var crtGlow: Float = 0.25f,
        var crtSweep: Float = 1.0f,
        var crtBrightness: Float = 1.0f,
        var crtNoise: Float = 0.15f,
        var controls: Map<String, Int> = emptyMap(),
        var shortcuts: Map<ShortcutAction, Set<Int>> = emptyMap(),
        var coreOptions: Map<String, String> = emptyMap()
    )

    fun load(): Settings {
        val settings = Settings()
        applyFile(globalFile, settings, loadOptions = false)
        applyFile(coreFile, settings, loadOptions = true)
        applyFile(gameFile, settings, loadOptions = true)
        return settings
    }

    fun saveGlobal(settings: Settings) {
        writeNonCore(globalFile, settings)
    }

    fun saveCore(settings: Settings) {
        writeNonCore(coreFile, settings)
        writeCoreOptions(coreFile, settings)
    }

    fun saveGame(settings: Settings) {
        writeNonCore(gameFile, settings)
        writeCoreOptions(gameFile, settings)
    }

    private fun applyFile(file: File, settings: Settings, loadOptions: Boolean) {
        if (!file.exists()) return
        val ini = IniParser.parse(file)

        ini.getSection("frontend").let { s ->
            s["scaling"]?.let { settings.scalingMode = enumSafe(it, settings.scalingMode) }
            s["effect"]?.let { settings.screenEffect = enumSafe(it, settings.screenEffect) }
            s["sharpness"]?.let { settings.sharpness = enumSafe(it, settings.sharpness) }
            s["debug_hud"]?.let { settings.debugHud = it == "true" }
            s["max_ff_speed"]?.let { v -> v.toIntOrNull()?.let { settings.maxFfSpeed = it } }
            s["crt_curvature"]?.toFloatOrNull()?.let { settings.crtCurvature = it }
            s["crt_scanline"]?.toFloatOrNull()?.let { settings.crtScanline = it }
            s["crt_mask_dark"]?.toFloatOrNull()?.let { settings.crtMaskDark = it }
            s["crt_vignette"]?.toFloatOrNull()?.let { settings.crtVignette = it }
            s["crt_glow"]?.toFloatOrNull()?.let { settings.crtGlow = it }
            s["crt_sweep"]?.toFloatOrNull()?.let { settings.crtSweep = it }
            s["crt_brightness"]?.toFloatOrNull()?.let { settings.crtBrightness = it }
            s["crt_noise"]?.toFloatOrNull()?.let { settings.crtNoise = it }
        }

        ini.getSection("controls").let { s ->
            if (s.isNotEmpty()) {
                val merged = settings.controls.toMutableMap()
                for ((key, value) in s) {
                    value.toIntOrNull()?.let { merged[key] = it }
                }
                settings.controls = merged
            }
        }

        ini.getSection("shortcuts").let { s ->
            if (s.isNotEmpty()) {
                val merged = settings.shortcuts.toMutableMap()
                for ((key, value) in s) {
                    val action = try { ShortcutAction.valueOf(key) } catch (_: Exception) { continue }
                    val chord = if (value.isEmpty()) emptySet()
                    else value.split(",").mapNotNull { it.toIntOrNull() }.toSet()
                    merged[action] = chord
                }
                settings.shortcuts = merged
            }
        }

        if (loadOptions) {
            ini.getSection("options").let { s ->
                if (s.isNotEmpty()) {
                    val merged = settings.coreOptions.toMutableMap()
                    merged.putAll(s)
                    settings.coreOptions = merged
                }
            }
        }
    }

    private fun writeNonCore(file: File, settings: Settings) {
        val sections = mutableMapOf<String, Map<String, String>>()

        sections["frontend"] = mapOf(
            "scaling" to settings.scalingMode.name,
            "effect" to settings.screenEffect.name,
            "sharpness" to settings.sharpness.name,
            "debug_hud" to settings.debugHud.toString(),
            "max_ff_speed" to settings.maxFfSpeed.toString(),
            "crt_curvature" to settings.crtCurvature.toString(),
            "crt_scanline" to settings.crtScanline.toString(),
            "crt_mask_dark" to settings.crtMaskDark.toString(),
            "crt_vignette" to settings.crtVignette.toString(),
            "crt_glow" to settings.crtGlow.toString(),
            "crt_sweep" to settings.crtSweep.toString(),
            "crt_brightness" to settings.crtBrightness.toString(),
            "crt_noise" to settings.crtNoise.toString()
        )

        if (settings.controls.isNotEmpty()) {
            sections["controls"] = settings.controls.mapValues { it.value.toString() }
        }

        if (settings.shortcuts.isNotEmpty()) {
            sections["shortcuts"] = settings.shortcuts.mapKeys { it.key.name }
                .mapValues { it.value.joinToString(",") }
        }

        val existing = if (file.exists()) IniParser.parse(file) else null
        val merged = existing?.sections?.toMutableMap() ?: mutableMapOf()
        for ((section, entries) in sections) {
            merged[section] = entries
        }
        IniWriter.write(file, merged)
    }

    private fun writeCoreOptions(file: File, settings: Settings) {
        if (settings.coreOptions.isNotEmpty()) {
            IniWriter.mergeWrite(file, "options", settings.coreOptions)
        }
    }

    private inline fun <reified T : Enum<T>> enumSafe(value: String, fallback: T): T {
        return try { enumValueOf<T>(value) } catch (_: Exception) { fallback }
    }
}
