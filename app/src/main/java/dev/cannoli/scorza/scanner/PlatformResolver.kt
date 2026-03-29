package dev.cannoli.scorza.scanner

import android.content.pm.PackageManager
import android.content.res.AssetManager
import dev.cannoli.scorza.launcher.InstalledCoreService
import dev.cannoli.scorza.launcher.isPackageInstalled
import dev.cannoli.scorza.model.LaunchTarget
import dev.cannoli.scorza.model.Platform
import dev.cannoli.scorza.util.IniData
import dev.cannoli.scorza.util.IniParser
import dev.cannoli.scorza.util.sortedNatural
import org.json.JSONObject
import java.io.File

data class GameCoreOverride(val coreId: String = "", val runner: String? = null, val appPackage: String? = null, val raPackage: String? = null)

class PlatformResolver(
    private val cannoliRoot: File,
    private val assets: AssetManager,
    private val coreInfo: CoreInfoRepository? = null,
    private val nativeLibDir: String? = null
) {

    private var defaultCores = mapOf<String, String>()
    private var defaultPlatformNames = mapOf<String, String>()
    private var defaultRetroArchCores = mapOf<String, List<String>>()
    private var defaultApps = mapOf<String, List<String>>()
    private var arcadePlatforms = setOf<String>()

    private fun loadPlatformsAsset() {
        val json = JSONObject(assets.open("platforms.json").use { it.bufferedReader().readText() })
        val cores = mutableMapOf<String, String>()
        val names = mutableMapOf<String, String>()
        val raCores = mutableMapOf<String, List<String>>()
        val apps = mutableMapOf<String, List<String>>()
        val arcade = mutableSetOf<String>()
        for (tag in json.keys()) {
            val entry = json.getJSONObject(tag)
            entry.optString("name", "").takeIf { it.isNotEmpty() }?.let { names[tag] = it }
            entry.optString("core", "").takeIf { it.isNotEmpty() }?.let { cores[tag] = it }
            if (entry.optBoolean("arcade")) arcade.add(tag)
            val appArray = entry.optJSONArray("app")
            if (appArray != null) {
                val list = (0 until appArray.length()).map { appArray.getString(it) }
                if (list.isNotEmpty()) apps[tag] = list
            } else {
                entry.optString("app", "").takeIf { it.isNotEmpty() }?.let { apps[tag] = listOf(it) }
            }
            val raArray = entry.optJSONArray("retroarch")
            if (raArray != null) {
                val list = (0 until raArray.length()).map { raArray.getString(it) }
                if (list.isNotEmpty()) raCores[tag] = list
            }
        }
        defaultCores = cores
        defaultPlatformNames = names
        defaultRetroArchCores = raCores
        defaultApps = apps
        arcadePlatforms = arcade
    }

    private var ini: IniData = IniData(emptyMap())
    private var userCores: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()
    private var userRunners: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()
    private var userApps: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()
    private var userPackages: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()
    private var gameOverrides: MutableMap<String, GameCoreOverride> = java.util.concurrent.ConcurrentHashMap()
    private val coresFile get() = File(cannoliRoot, "Config/cores.json")

    fun load() {
        loadPlatformsAsset()
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
        userApps.clear()
        userPackages.clear()
        gameOverrides.clear()
        if (!coresFile.exists()) return
        try {
            val json = JSONObject(coresFile.readText())
            val cores = json.optJSONObject("cores")
            if (cores != null) for (key in cores.keys()) userCores[key] = cores.getString(key)
            val runners = json.optJSONObject("runners")
            if (runners != null) for (key in runners.keys()) userRunners[key] = runners.getString(key)
            val apps = json.optJSONObject("apps")
            if (apps != null) for (key in apps.keys()) userApps[key] = apps.getString(key)
            val packages = json.optJSONObject("packages")
            if (packages != null) for (key in packages.keys()) userPackages[key] = packages.getString(key)
            val overrides = json.optJSONObject("gameOverrides")
            if (overrides != null) {
                for (path in overrides.keys()) {
                    val obj = overrides.getJSONObject(path)
                    gameOverrides[path] = GameCoreOverride(
                        coreId = obj.optString("core", ""),
                        runner = obj.optString("runner", "").ifEmpty { null },
                        appPackage = obj.optString("app", "").ifEmpty { null },
                        raPackage = obj.optString("raPackage", "").ifEmpty { null }
                    )
                }
            }
        } catch (_: java.io.IOException) {} catch (_: org.json.JSONException) {}
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
        if (userApps.isNotEmpty()) {
            val apps = JSONObject()
            for ((tag, app) in userApps) apps.put(tag, app)
            json.put("apps", apps)
        }
        if (userPackages.isNotEmpty()) {
            val packages = JSONObject()
            for ((tag, pkg) in userPackages) packages.put(tag, pkg)
            json.put("packages", packages)
        }
        if (gameOverrides.isNotEmpty()) {
            val overrides = JSONObject()
            for ((path, ov) in gameOverrides) {
                val obj = JSONObject()
                if (ov.appPackage != null) {
                    obj.put("app", ov.appPackage)
                } else {
                    obj.put("core", ov.coreId)
                    if (ov.runner != null) obj.put("runner", ov.runner)
                    if (ov.raPackage != null) obj.put("raPackage", ov.raPackage)
                }
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

    fun setCoreMapping(tag: String, core: String, runner: String? = null, raPackage: String? = null) {
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
        if (raPackage != null) {
            userPackages[tag] = raPackage
        } else {
            userPackages.remove(tag)
        }
        userApps.remove(tag)
    }

    fun getPackage(tag: String): String? = userPackages[tag]

    fun getRunnerPreference(tag: String): String? = userRunners[tag]

    fun getGameOverride(gamePath: String): GameCoreOverride? = gameOverrides[gamePath]

    fun setGameOverride(gamePath: String, coreId: String?, runner: String?, raPackage: String? = null) {
        if (coreId == null) {
            gameOverrides.remove(gamePath)
        } else {
            gameOverrides[gamePath] = GameCoreOverride(coreId, runner, raPackage = raPackage)
        }
        saveCoreMappings()
    }

    fun isKnownTag(tag: String): Boolean = tag in defaultPlatformNames || tag in ini.getSection("platforms")

    fun isArcade(tag: String): Boolean = tag in arcadePlatforms

    fun getAllTags(): Set<String> = defaultPlatformNames.keys + ini.getSection("platforms").keys

    fun getAppPackage(tag: String): String? = userApps[tag] ?: defaultApps[tag]?.firstOrNull()

    fun getAppOptions(tag: String): List<String> = defaultApps[tag] ?: emptyList()

    fun setAppMapping(tag: String, appPackage: String?) {
        if (appPackage == null) {
            userApps.remove(tag)
            userRunners.remove(tag)
        } else {
            userApps[tag] = appPackage
            userRunners[tag] = "Standalone"
        }
        userCores.remove(tag)
        userPackages.remove(tag)
    }

    fun setGameAppOverride(gamePath: String, appPackage: String?) {
        if (appPackage == null) {
            gameOverrides.remove(gamePath)
        } else {
            gameOverrides[gamePath] = GameCoreOverride(appPackage = appPackage)
        }
        saveCoreMappings()
    }

    fun getCoreDisplayName(coreId: String): String {
        return coreInfo?.getDisplayName(coreId) ?: coreId
    }

    fun getRunnerLabel(tag: String, coreId: String): String {
        val romsDir = File(cannoliRoot, "Roms")
        if (File(romsDir, "$tag/.emu_launch").exists()) return "External"
        val override = userRunners[tag]
        if (override == "App") return "Standalone"
        if (override != null) return override
        if (nativeLibDir != null && File(nativeLibDir, "${coreId}_android.so").exists()) return "Internal"
        val pkg = userPackages[tag]
        if (pkg != null) return InstalledCoreService.getPackageLabel(pkg)
        return "RetroArch"
    }

    fun getDetailedMappings(
        pm: PackageManager? = null,
        installedRaCores: Map<String, Set<String>> = emptyMap(),
        embeddedCoresDir: String? = null
    ): List<dev.cannoli.scorza.ui.screens.CoreMappingEntry> {
        val tags = (defaultCores.keys + defaultApps.keys + userCores.keys + userApps.keys)
        return tags.map { tag ->
            val app = getAppPackage(tag)
            val coreId = getCoreMapping(tag)
            if (app != null && coreId.isBlank()) {
                val installed = pm?.isPackageInstalled(app) ?: true
                if (installed) {
                    val appName = pm?.let { resolveAppLabel(it, app) } ?: app
                    dev.cannoli.scorza.ui.screens.CoreMappingEntry(
                        tag = tag, platformName = getDisplayName(tag),
                        coreDisplayName = appName, runnerLabel = "Standalone"
                    )
                } else {
                    dev.cannoli.scorza.ui.screens.CoreMappingEntry(
                        tag = tag, platformName = getDisplayName(tag),
                        coreDisplayName = "Missing", runnerLabel = ""
                    )
                }
            } else if (coreId.isBlank()) {
                dev.cannoli.scorza.ui.screens.CoreMappingEntry(
                    tag = tag, platformName = getDisplayName(tag),
                    coreDisplayName = "None", runnerLabel = ""
                )
            } else {
                val runner = getRunnerLabel(tag, coreId)
                val present = isCorePresent(tag, coreId, runner, installedRaCores, embeddedCoresDir)
                if (present) {
                    dev.cannoli.scorza.ui.screens.CoreMappingEntry(
                        tag = tag, platformName = getDisplayName(tag),
                        coreDisplayName = getCoreDisplayName(coreId), runnerLabel = runner
                    )
                } else {
                    dev.cannoli.scorza.ui.screens.CoreMappingEntry(
                        tag = tag, platformName = getDisplayName(tag),
                        coreDisplayName = "Missing", runnerLabel = ""
                    )
                }
            }
        }.sortedNatural { it.platformName }
    }

    private fun isCorePresent(
        tag: String, coreId: String, runner: String,
        installedRaCores: Map<String, Set<String>>,
        embeddedCoresDir: String?
    ): Boolean {
        if (runner == "Internal") {
            val dir = embeddedCoresDir ?: nativeLibDir ?: return false
            return File(dir, "${coreId}_android.so").exists()
        }
        if (runner == "External") return true
        // RetroArch / RicottaArch — check specific package first, then any package
        val pkg = userPackages[tag]
        if (pkg != null) return installedRaCores[pkg]?.contains(coreId) == true
        return installedRaCores.any { it.value.contains(coreId) }
    }

    fun getCorePickerOptions(
        tag: String,
        pm: PackageManager? = null,
        installedRaCores: Map<String, Set<String>> = emptyMap(),
        embeddedCoresDir: String? = null
    ): List<dev.cannoli.scorza.ui.screens.CorePickerOption> {
        val options = mutableListOf<dev.cannoli.scorza.ui.screens.CorePickerOption>()

        // Gather all candidate core IDs from core info files + platform defaults
        val candidateCoreIds = mutableSetOf<String>()
        defaultCores[tag]?.let { candidateCoreIds.add(it) }
        defaultRetroArchCores[tag]?.forEach { candidateCoreIds.add(it) }
        coreInfo?.getCoresForTag(tag)?.forEach { candidateCoreIds.add(it.id) }

        for (coreId in candidateCoreIds) {
            val displayName = getCoreDisplayName(coreId)

            // Check embedded/internal cores
            val checkDir = embeddedCoresDir ?: nativeLibDir
            if (checkDir != null && File(checkDir, "${coreId}_android.so").exists()) {
                options.add(dev.cannoli.scorza.ui.screens.CorePickerOption(
                    coreId = coreId, displayName = displayName, runnerLabel = "Internal"
                ))
            }

            // Check each RA package for this core
            for ((pkg, cores) in installedRaCores) {
                if (coreId in cores) {
                    options.add(dev.cannoli.scorza.ui.screens.CorePickerOption(
                        coreId = coreId, displayName = displayName,
                        runnerLabel = InstalledCoreService.getPackageLabel(pkg),
                        raPackage = pkg
                    ))
                }
            }
        }

        // Standalone app options
        val appPackages = getAppOptions(tag)
        for (pkg in appPackages) {
            val appName = pm?.let { resolveAppLabel(it, pkg) } ?: pkg
            options.add(dev.cannoli.scorza.ui.screens.CorePickerOption(
                coreId = "", displayName = appName, runnerLabel = "Standalone", appPackage = pkg
            ))
        }

        return options
    }

    private fun resolveAppLabel(pm: PackageManager, packageName: String): String {
        return try {
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
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
