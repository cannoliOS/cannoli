package dev.cannoli.scorza.scanner

import dev.cannoli.scorza.model.Collection
import dev.cannoli.scorza.model.Game
import dev.cannoli.scorza.model.LaunchTarget
import dev.cannoli.scorza.model.Platform
import dev.cannoli.scorza.util.sortedNatural
import java.io.File

class FileScanner(
    private val cannoliRoot: File,
    private val platformResolver: PlatformResolver
) {
    private val romsDir get() = File(cannoliRoot, "Roms")
    private val artDir get() = File(cannoliRoot, "Art")
    private val collectionsDir get() = File(cannoliRoot, "Collections")
    private val toolsDir get() = File(cannoliRoot, "Config/Launch Scripts/Tools")
    private val portsDir get() = File(cannoliRoot, "Config/Launch Scripts/Ports")

    private val artCache = mutableMapOf<String, Map<String, File>>()
    private val discRegex = Regex("""\s*\((Disc|Disk)\s*\d+\)|\s*\(CD\d+\)""", RegexOption.IGNORE_CASE)

    fun scanPlatforms(): List<Platform> {
        if (!romsDir.exists()) return emptyList()

        val tagDirs = romsDir.listFiles { f -> f.isDirectory } ?: return emptyList()

        return tagDirs
            .filter { platformResolver.isKnownTag(it.name) }
            .map { dir ->
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
        val appPackage = platformResolver.getAppPackage(tag)

        val files = baseDir.listFiles() ?: return emptyList()

        fun resolveTarget(isSubfolder: Boolean): LaunchTarget = when {
            isSubfolder -> LaunchTarget.RetroArch
            emuLaunch != null -> emuLaunch
            appPackage != null -> LaunchTarget.ApkLaunch(appPackage)
            coreName != null -> LaunchTarget.RetroArch
            else -> LaunchTarget.RetroArch
        }

        val rawGames = files
            .filter { it.name != ".emu_launch" }
            .mapNotNull { file ->
                if (file.isDirectory) {
                    val dirLaunch = findDirLaunchFile(file)
                    if (dirLaunch != null) {
                        Game(
                            file = dirLaunch.file,
                            displayName = file.name,
                            platformTag = tag,
                            artFile = findArt(tag, file.name),
                            launchTarget = resolveTarget(false),
                            discFiles = dirLaunch.discFiles
                        )
                    } else {
                        val hasChildren = file.listFiles()?.any { it.name != ".emu_launch" } == true
                        if (!hasChildren) return@mapNotNull null
                        Game(
                            file = file,
                            displayName = file.name,
                            platformTag = tag,
                            isSubfolder = true,
                            artFile = findArt(tag, file.name),
                            launchTarget = resolveTarget(true)
                        )
                    }
                } else {
                    Game(
                        file = file,
                        displayName = file.nameWithoutExtension,
                        platformTag = tag,
                        artFile = findArt(tag, file.nameWithoutExtension),
                        launchTarget = resolveTarget(false)
                    )
                }
            }

        val looseM3uNames = rawGames
            .filter { !it.isSubfolder && it.file.extension.equals("m3u", ignoreCase = true) }
            .map { it.file.nameWithoutExtension }
            .toSet()

        val (discCandidates, others) = rawGames.partition {
            !it.isSubfolder && discRegex.containsMatchIn(it.displayName)
        }

        val discGroups = discCandidates.groupBy { it.displayName.replace(discRegex, "").trim() }

        val grouped = discGroups.flatMap { (baseName, games) ->
            if (games.size <= 1) return@flatMap games

            val existingM3u = others.find {
                it.file.extension.equals("m3u", ignoreCase = true) &&
                    it.file.nameWithoutExtension == baseName
            }
            if (existingM3u != null) {
                return@flatMap listOf(existingM3u)
            }

            val sorted = games.sortedBy { it.file.name }
            listOf(sorted.first().copy(
                displayName = baseName,
                artFile = findArt(tag, baseName),
                discFiles = sorted.map { it.file }
            ))
        }

        val discFileSet = discGroups.values
            .filter { it.size > 1 }
            .flatten()
            .map { it.file.absolutePath }
            .toSet()
        val coveredByM3u = discGroups
            .filter { (baseName, games) ->
                games.size > 1 && looseM3uNames.contains(baseName)
            }
            .values.flatten().map { it.file.absolutePath }.toSet()

        val filtered = others.filter {
            it.file.absolutePath !in discFileSet && it.file.absolutePath !in coveredByM3u
        }

        return (filtered + grouped)
            .sortedWith(compareBy<Game> { !it.isSubfolder }.thenBy(dev.cannoli.scorza.util.NaturalSort) { it.displayName })
    }

    private data class DirLaunch(val file: File, val discFiles: List<File>? = null)

    private fun findDirLaunchFile(dir: File): DirLaunch? {
        File(dir, "${dir.name}.m3u").takeIf { it.exists() }?.let { return DirLaunch(it) }
        File(dir, "${dir.name}.cue").takeIf { it.exists() }?.let { return DirLaunch(it) }
        dir.listFiles()?.firstOrNull { it.extension.equals("cue", ignoreCase = true) }
            ?.let { return DirLaunch(it) }
        val children = dir.listFiles()?.filter { it.isFile } ?: return null
        val discFiles = children.filter { discRegex.containsMatchIn(it.nameWithoutExtension) }
        if (discFiles.size > 1) {
            val sorted = discFiles.sortedBy { it.name }
            return DirLaunch(sorted.first(), sorted)
        }
        return null
    }

    fun scanCollections(): List<Collection> {
        if (!collectionsDir.exists()) return emptyList()

        val files = collectionsDir.listFiles { f -> f.extension == "txt" } ?: return emptyList()

        return files.map { file ->
            val lines = file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
            val entries = lines.map { File(it) }.filter { it.exists() }

            if (entries.size < lines.size) {
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

    fun scanCollectionGames(collectionName: String): List<Game> {
        val collFile = File(collectionsDir, "$collectionName.txt")
        if (!collFile.exists()) return emptyList()

        return collFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { File(it) }
            .filter { it.exists() && it.isFile }
            .map { file ->
                val tag = resolvePlatformTag(file)
                val displayName = file.nameWithoutExtension
                val artFile = findArt(tag, displayName)
                val emuLaunch = platformResolver.getEmuLaunch(tag, romsDir)
                val coreName = platformResolver.getCoreName(tag)
                val appPackage = platformResolver.getAppPackage(tag)

                val target = when {
                    emuLaunch != null -> emuLaunch
                    appPackage != null -> LaunchTarget.ApkLaunch(appPackage)
                    coreName != null -> LaunchTarget.RetroArch
                    else -> LaunchTarget.RetroArch
                }

                Game(
                    file = file,
                    displayName = displayName,
                    platformTag = tag,
                    artFile = artFile,
                    launchTarget = target
                )
            }
            .sortedNatural { it.displayName }
    }

    fun addToCollection(collectionName: String, romPath: String) {
        collectionsDir.mkdirs()
        val collFile = File(collectionsDir, "$collectionName.txt")
        val existing = if (collFile.exists()) collFile.readLines().map { it.trim() } else emptyList()
        if (romPath !in existing) {
            collFile.appendText("$romPath\n")
        }
    }

    fun removeFromCollection(collectionName: String, romPath: String) {
        val collFile = File(collectionsDir, "$collectionName.txt")
        if (!collFile.exists()) return
        val remaining = collFile.readLines().map { it.trim() }.filter { it != romPath && it.isNotEmpty() }
        collFile.writeText(remaining.joinToString("\n") { it } + if (remaining.isNotEmpty()) "\n" else "")
    }

    fun isInCollection(collectionName: String, romPath: String): Boolean {
        val collFile = File(collectionsDir, "$collectionName.txt")
        if (!collFile.exists()) return false
        return collFile.readLines().any { it.trim() == romPath }
    }

    fun createCollection(name: String) {
        collectionsDir.mkdirs()
        File(collectionsDir, "$name.txt").createNewFile()
    }

    fun deleteCollection(name: String) {
        File(collectionsDir, "$name.txt").delete()
    }

    fun renameCollection(oldName: String, newName: String) {
        val oldFile = File(collectionsDir, "$oldName.txt")
        val newFile = File(collectionsDir, "$newName.txt")
        if (oldFile.exists() && !newFile.exists()) {
            oldFile.renameTo(newFile)
        }
    }

    fun getCollectionNames(): List<String> {
        if (!collectionsDir.exists()) return emptyList()
        return collectionsDir.listFiles { f -> f.extension == "txt" }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()
    }

    fun deleteGame(game: Game) {
        if (game.discFiles != null) {
            game.discFiles.forEach { it.delete() }
        } else if (game.file.isDirectory) {
            game.file.deleteRecursively()
        } else {
            game.file.delete()
        }
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

    val tools: File get() = toolsDir
    val ports: File get() = portsDir

    fun syncApkLaunches(dir: File, selected: List<Pair<String, String>>) {
        dir.mkdirs()
        dir.listFiles { f -> f.extension == "apk_launch" }?.forEach { it.delete() }
        selected.forEach { (displayName, packageName) ->
            val safeName = displayName.replace(Regex("[/\\\\:*?\"<>|]"), "_")
            File(dir, "$safeName.apk_launch").writeText(packageName)
        }
    }

    fun ensureDirectories() {
        listOf(
            romsDir, artDir, collectionsDir,
            File(cannoliRoot, "BIOS"),
            File(cannoliRoot, "Saves"),
            File(cannoliRoot, "Save States"),
            File(cannoliRoot, "Media/Screenshots"),
            File(cannoliRoot, "Media/Recordings"),
            File(cannoliRoot, "Config"),
            File(cannoliRoot, "Config/Overrides"),
            File(cannoliRoot, "Config/Overrides/Cores"),
            File(cannoliRoot, "Config/Overrides/Games"),
            File(cannoliRoot, "Backup"),
            File(cannoliRoot, "Wallpapers"),
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

    fun getCollectionsContaining(romPath: String): Set<String> {
        if (!collectionsDir.exists()) return emptySet()
        val result = mutableSetOf<String>()
        collectionsDir.listFiles { f -> f.extension == "txt" }?.forEach { file ->
            if (file.readLines().any { it.trim() == romPath }) {
                result.add(file.nameWithoutExtension)
            }
        }
        return result
    }

    private fun resolvePlatformTag(romFile: File): String {
        val romsPath = romsDir.absolutePath + "/"
        val filePath = romFile.absolutePath
        if (!filePath.startsWith(romsPath)) return romFile.parentFile?.name ?: ""
        val relative = filePath.removePrefix(romsPath)
        return relative.substringBefore('/')
    }

    private fun countGames(dir: File): Int {
        val files = dir.listFiles() ?: return 0
        val visible = files.filter { it.name != ".emu_launch" }
        val discFiles = visible.filter { !it.isDirectory && discRegex.containsMatchIn(it.nameWithoutExtension) }
        val discGroupCount = discFiles
            .groupBy { it.nameWithoutExtension.replace(discRegex, "").trim() }
            .count { it.value.size > 1 }
        val discFileCount = discFiles.count { f ->
            val base = f.nameWithoutExtension.replace(discRegex, "").trim()
            discFiles.count { it.nameWithoutExtension.replace(discRegex, "").trim() == base } > 1
        }
        return visible.size - discFileCount + discGroupCount
    }

    private fun findArt(tag: String, gameName: String): File? {
        val lookup = artCache.getOrPut(tag) {
            val artTagDir = File(artDir, tag)
            if (!artTagDir.exists()) return@getOrPut emptyMap()
            val map = mutableMapOf<String, File>()
            artTagDir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    map[file.nameWithoutExtension] = file
                }
            }
            map
        }
        return lookup[gameName]
    }

    fun invalidateArtCache() {
        artCache.clear()
    }

    private val configDir get() = File(cannoliRoot, "Config")

    fun loadPlatformOrder(): List<String> {
        val file = File(configDir, "platform_order.txt")
        if (!file.exists()) return emptyList()
        return file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun savePlatformOrder(tags: List<String>) {
        configDir.mkdirs()
        File(configDir, "platform_order.txt").writeText(tags.joinToString("\n") + "\n")
    }

    fun loadCollectionOrder(): List<String> {
        val file = File(configDir, "collection_order.txt")
        if (!file.exists()) return emptyList()
        return file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun saveCollectionOrder(names: List<String>) {
        configDir.mkdirs()
        File(configDir, "collection_order.txt").writeText(names.joinToString("\n") + "\n")
    }
}
