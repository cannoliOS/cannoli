package dev.cannoli.launcher.scanner

import dev.cannoli.launcher.model.Collection
import dev.cannoli.launcher.model.Game
import dev.cannoli.launcher.model.LaunchTarget
import dev.cannoli.launcher.model.Platform
import dev.cannoli.launcher.util.sortedNatural
import java.io.File

class FileScanner(
    private val cannoliRoot: File,
    private val platformResolver: PlatformResolver
) {
    private val romsDir get() = File(cannoliRoot, "Roms")
    private val artDir get() = File(cannoliRoot, "Art")
    private val collectionsDir get() = File(cannoliRoot, "Collections")
    private val toolsDir get() = File(cannoliRoot, "Tools")
    private val portsDir get() = File(cannoliRoot, "Ports")

    fun scanPlatforms(): List<Platform> {
        if (!romsDir.exists()) return emptyList()

        val tagDirs = romsDir.listFiles { f -> f.isDirectory } ?: return emptyList()

        return tagDirs.map { dir ->
            val tag = dir.name
            val gameCount = countGames(dir)
            platformResolver.resolvePlatform(tag, romsDir, gameCount)
        }.sortedNatural { it.displayName }
    }

    fun scanGames(tag: String, subfolder: String? = null): List<Game> {
        val baseDir = if (subfolder != null) {
            File(romsDir, "$tag/$subfolder")
        } else {
            File(romsDir, tag)
        }

        if (!baseDir.exists()) return emptyList()

        val emuLaunch = platformResolver.getEmuLaunch(tag, romsDir)
        val coreName = platformResolver.getCoreName(tag)

        val files = baseDir.listFiles() ?: return emptyList()

        return files
            .filter { it.name != ".emu_launch" }
            .map { file ->
                val displayName = if (file.isDirectory) file.name else file.nameWithoutExtension
                val artFile = findArt(tag, displayName)

                val target = when {
                    file.isDirectory -> LaunchTarget.RetroArch // placeholder, subfolder
                    emuLaunch != null -> emuLaunch
                    coreName != null -> LaunchTarget.RetroArch
                    else -> LaunchTarget.RetroArch // will show warning
                }

                Game(
                    file = file,
                    displayName = displayName,
                    platformTag = tag,
                    isSubfolder = file.isDirectory,
                    artFile = artFile,
                    launchTarget = target
                )
            }
            .sortedWith(compareBy<Game> { !it.isSubfolder }.thenBy(dev.cannoli.launcher.util.NaturalSort) { it.displayName })
    }

    fun scanCollections(): List<Collection> {
        if (!collectionsDir.exists()) return emptyList()

        val files = collectionsDir.listFiles { f -> f.extension == "txt" } ?: return emptyList()

        return files.map { file ->
            val entries = file.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { File(it) }
                .filter { it.exists() }

            // Clean stale entries
            if (entries.size < file.readLines().count { it.trim().isNotEmpty() }) {
                file.writeText(entries.joinToString("\n") { it.absolutePath } + "\n")
            }

            Collection(
                name = file.nameWithoutExtension,
                file = file,
                entries = entries
            )
        }.filter { it.entries.isNotEmpty() }
            .sortedNatural { it.name }
    }

    fun scanApkLaunches(dir: File): List<LaunchTarget.ApkLaunch> {
        if (!dir.exists()) return emptyList()

        val files = dir.listFiles { f -> f.extension == "apk_launch" } ?: return emptyList()

        return files.mapNotNull { file ->
            val pkg = file.readText().trim()
            if (pkg.isNotEmpty()) LaunchTarget.ApkLaunch(pkg) else null
        }
    }

    fun scanTools(): List<Pair<String, LaunchTarget.ApkLaunch>> {
        return scanApkLaunchesWithNames(toolsDir)
    }

    fun scanPorts(): List<Pair<String, LaunchTarget.ApkLaunch>> {
        return scanApkLaunchesWithNames(portsDir)
    }

    fun ensureDirectories() {
        listOf(
            romsDir, artDir, collectionsDir,
            File(cannoliRoot, "BIOS"),
            File(cannoliRoot, "Saves"),
            File(cannoliRoot, "Save States"),
            File(cannoliRoot, "Screenshots"),
            File(cannoliRoot, "Recordings"),
            File(cannoliRoot, "Config"),
            File(cannoliRoot, "Backup"),
            toolsDir, portsDir
        ).forEach { it.mkdirs() }
    }

    private fun scanApkLaunchesWithNames(dir: File): List<Pair<String, LaunchTarget.ApkLaunch>> {
        if (!dir.exists()) return emptyList()

        val files = dir.listFiles { f -> f.extension == "apk_launch" } ?: return emptyList()

        return files.mapNotNull { file ->
            val pkg = file.readText().trim()
            if (pkg.isNotEmpty()) {
                file.nameWithoutExtension to LaunchTarget.ApkLaunch(pkg)
            } else null
        }.sortedNatural { it.first }
    }

    private fun countGames(dir: File): Int {
        val files = dir.listFiles() ?: return 0
        return files.count { it.name != ".emu_launch" }
    }

    private fun findArt(tag: String, gameName: String): File? {
        val artTagDir = File(artDir, tag)
        if (!artTagDir.exists()) return null

        val extensions = listOf("png", "jpg", "jpeg", "PNG", "JPG", "JPEG")
        for (ext in extensions) {
            val candidate = File(artTagDir, "$gameName.$ext")
            if (candidate.exists()) return candidate
        }
        return null
    }
}
