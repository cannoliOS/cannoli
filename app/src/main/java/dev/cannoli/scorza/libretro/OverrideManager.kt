package dev.cannoli.scorza.libretro

import dev.cannoli.scorza.util.IniParser
import dev.cannoli.scorza.util.IniWriter
import java.io.File

enum class OverrideSource { GLOBAL, PLATFORM, GAME }

class OverrideManager(
    cannoliRoot: String,
    private val platformTag: String,
    private val gameBaseName: String,
    coreName: String = ""
) {
    private val overridesDir = File(cannoliRoot, "Config/Overrides")
    private val globalFile = File(overridesDir, "global.ini")
    private val platformFile = File(overridesDir, "systems/$platformTag.ini")
    private val gameFile = File(overridesDir, "Games/$platformTag/$gameBaseName.ini")

    init {
        if (coreName.isNotEmpty() && !platformFile.exists()) {
            val oldCoreFile = File(overridesDir, "Cores/$coreName.ini")
            if (oldCoreFile.exists()) {
                platformFile.parentFile?.mkdirs()
                oldCoreFile.copyTo(platformFile)
            }
        }
    }

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
        var controlSource: OverrideSource = OverrideSource.GLOBAL,
        var shortcutSource: OverrideSource = OverrideSource.GLOBAL,
        var controls: Map<String, Int> = emptyMap(),
        var shortcuts: Map<ShortcutAction, Set<Int>> = emptyMap(),
        var coreOptions: Map<String, String> = emptyMap()
    ) {
        fun frontendEquals(other: Settings): Boolean =
            scalingMode == other.scalingMode &&
            screenEffect == other.screenEffect &&
            sharpness == other.sharpness &&
            debugHud == other.debugHud &&
            maxFfSpeed == other.maxFfSpeed &&
            crtCurvature == other.crtCurvature &&
            crtScanline == other.crtScanline &&
            crtMaskDark == other.crtMaskDark &&
            crtVignette == other.crtVignette &&
            crtGlow == other.crtGlow &&
            crtSweep == other.crtSweep &&
            crtBrightness == other.crtBrightness &&
            crtNoise == other.crtNoise &&
            coreOptions == other.coreOptions
    }

    fun load(): Settings {
        val settings = Settings()
        applyFrontend(platformFile, settings)
        applyOptions(platformFile, settings)
        applyFrontend(gameFile, settings)
        applyOptions(gameFile, settings)
        resolveSourcePreferences(settings)
        loadControlsFrom(sourceFile(settings.controlSource), settings)
        loadShortcutsFrom(sourceFile(settings.shortcutSource), settings)
        return settings
    }

    fun loadPlatformBaseline(): Settings {
        val settings = Settings()
        applyFrontend(platformFile, settings)
        applyOptions(platformFile, settings)
        return settings
    }

    fun savePlatform(settings: Settings) {
        val existing = if (platformFile.exists()) IniParser.parse(platformFile).sections.toMutableMap() else mutableMapOf()
        existing["frontend"] = buildFrontendMap(settings)
        if (settings.coreOptions.isNotEmpty()) existing["options"] = settings.coreOptions
        IniWriter.write(platformFile, existing)
    }

    fun saveGameDelta(settings: Settings, baseline: Settings) {
        val existing = if (gameFile.exists()) IniParser.parse(gameFile).sections.toMutableMap() else mutableMapOf()

        val frontendDelta = buildFrontendDelta(settings, baseline)
        if (frontendDelta.isNotEmpty()) existing["frontend"] = frontendDelta
        else existing.remove("frontend")

        val optionsDelta = mutableMapOf<String, String>()
        for ((key, value) in settings.coreOptions) {
            if (baseline.coreOptions[key] != value) optionsDelta[key] = value
        }
        if (optionsDelta.isNotEmpty()) existing["options"] = optionsDelta
        else existing.remove("options")

        if (existing.any { it.value.isNotEmpty() }) IniWriter.write(gameFile, existing)
        else if (gameFile.exists()) gameFile.delete()
    }

    fun saveControlSource(source: OverrideSource) {
        IniWriter.mergeWrite(gameFile, "meta", mapOf("control_source" to source.name))
    }

    fun saveShortcutSource(source: OverrideSource) {
        IniWriter.mergeWrite(gameFile, "meta", mapOf("shortcut_source" to source.name))
    }

    fun saveControls(source: OverrideSource, controls: Map<String, Int>) {
        IniWriter.mergeWrite(sourceFile(source), "controls", controls.mapValues { it.value.toString() })
    }

    fun saveShortcuts(source: OverrideSource, shortcuts: Map<ShortcutAction, Set<Int>>) {
        IniWriter.mergeWrite(
            sourceFile(source), "shortcuts",
            shortcuts.mapKeys { it.key.name }.mapValues { it.value.joinToString(",") }
        )
    }

    fun loadControlsForSource(source: OverrideSource): Map<String, Int> {
        val s = Settings()
        loadControlsFrom(sourceFile(source), s)
        return s.controls
    }

    fun loadShortcutsForSource(source: OverrideSource): Map<ShortcutAction, Set<Int>> {
        val s = Settings()
        loadShortcutsFrom(sourceFile(source), s)
        return s.shortcuts
    }

    fun hasGameOverrides(): Boolean {
        if (!gameFile.exists()) return false
        val ini = IniParser.parse(gameFile)
        return ini.getSection("frontend").isNotEmpty() || ini.getSection("options").isNotEmpty()
    }

    private fun sourceFile(source: OverrideSource): File = when (source) {
        OverrideSource.GLOBAL -> globalFile
        OverrideSource.PLATFORM -> platformFile
        OverrideSource.GAME -> gameFile
    }

    private fun resolveSourcePreferences(settings: Settings) {
        val gameMeta = if (gameFile.exists()) IniParser.parse(gameFile).getSection("meta") else emptyMap()
        val platformMeta = if (platformFile.exists()) IniParser.parse(platformFile).getSection("meta") else emptyMap()

        settings.controlSource = gameMeta["control_source"]?.let { enumSafe<OverrideSource>(it) }
            ?: platformMeta["control_source"]?.let { enumSafe<OverrideSource>(it) }
            ?: legacyGlobalControlsFallback()

        settings.shortcutSource = gameMeta["shortcut_source"]?.let { enumSafe<OverrideSource>(it) }
            ?: platformMeta["shortcut_source"]?.let { enumSafe<OverrideSource>(it) }
            ?: OverrideSource.GLOBAL
    }

    private fun legacyGlobalControlsFallback(): OverrideSource {
        for (file in listOf(gameFile, platformFile)) {
            if (!file.exists()) continue
            val controls = IniParser.parse(file).getSection("controls")
            if (controls["use_global_controls"] == "true") return OverrideSource.GLOBAL
        }
        return OverrideSource.GLOBAL
    }

    private fun applyFrontend(file: File, settings: Settings) {
        if (!file.exists()) return
        val s = IniParser.parse(file).getSection("frontend")
        s["scaling"]?.let { v -> enumSafe<ScalingMode>(v)?.let { settings.scalingMode = it } }
        s["effect"]?.let { v -> enumSafe<ScreenEffect>(v)?.let { settings.screenEffect = it } }
        s["sharpness"]?.let { v -> enumSafe<Sharpness>(v)?.let { settings.sharpness = it } }
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

    private fun applyOptions(file: File, settings: Settings) {
        if (!file.exists()) return
        val s = IniParser.parse(file).getSection("options")
        if (s.isNotEmpty()) {
            val merged = settings.coreOptions.toMutableMap()
            merged.putAll(s)
            settings.coreOptions = merged
        }
    }

    private fun loadControlsFrom(file: File, settings: Settings) {
        if (!file.exists()) return
        val s = IniParser.parse(file).getSection("controls")
        if (s.isNotEmpty()) {
            val map = mutableMapOf<String, Int>()
            for ((key, value) in s) {
                value.toIntOrNull()?.let { map[key] = it }
            }
            if (map.isNotEmpty()) settings.controls = map
        }
    }

    private fun loadShortcutsFrom(file: File, settings: Settings) {
        if (!file.exists()) return
        val s = IniParser.parse(file).getSection("shortcuts")
        if (s.isNotEmpty()) {
            val map = mutableMapOf<ShortcutAction, Set<Int>>()
            for ((key, value) in s) {
                val action = try { ShortcutAction.valueOf(key) } catch (_: Exception) { continue }
                val chord = if (value.isEmpty()) emptySet()
                else value.split(",").mapNotNull { it.toIntOrNull() }.toSet()
                map[action] = chord
            }
            settings.shortcuts = map
        }
    }

    private fun buildFrontendMap(settings: Settings): Map<String, String> = mapOf(
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

    private fun buildFrontendDelta(settings: Settings, baseline: Settings): Map<String, String> {
        val delta = mutableMapOf<String, String>()
        if (settings.scalingMode != baseline.scalingMode) delta["scaling"] = settings.scalingMode.name
        if (settings.screenEffect != baseline.screenEffect) delta["effect"] = settings.screenEffect.name
        if (settings.sharpness != baseline.sharpness) delta["sharpness"] = settings.sharpness.name
        if (settings.debugHud != baseline.debugHud) delta["debug_hud"] = settings.debugHud.toString()
        if (settings.maxFfSpeed != baseline.maxFfSpeed) delta["max_ff_speed"] = settings.maxFfSpeed.toString()
        if (settings.crtCurvature != baseline.crtCurvature) delta["crt_curvature"] = settings.crtCurvature.toString()
        if (settings.crtScanline != baseline.crtScanline) delta["crt_scanline"] = settings.crtScanline.toString()
        if (settings.crtMaskDark != baseline.crtMaskDark) delta["crt_mask_dark"] = settings.crtMaskDark.toString()
        if (settings.crtVignette != baseline.crtVignette) delta["crt_vignette"] = settings.crtVignette.toString()
        if (settings.crtGlow != baseline.crtGlow) delta["crt_glow"] = settings.crtGlow.toString()
        if (settings.crtSweep != baseline.crtSweep) delta["crt_sweep"] = settings.crtSweep.toString()
        if (settings.crtBrightness != baseline.crtBrightness) delta["crt_brightness"] = settings.crtBrightness.toString()
        if (settings.crtNoise != baseline.crtNoise) delta["crt_noise"] = settings.crtNoise.toString()
        return delta
    }

    private inline fun <reified T : Enum<T>> enumSafe(value: String): T? {
        return try { enumValueOf<T>(value) } catch (_: Exception) { null }
    }
}
