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
    private val toolsDir get() = File(cannoliRoot, "Tools")
    private val portsDir get() = File(cannoliRoot, "Ports")

    private val artCache = mutableMapOf<String, Map<String, File>>()

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
            .mapNotNull { file ->
                val m3uFile = if (file.isDirectory) {
                    File(file, "${file.name}.m3u").takeIf { it.exists() }
                } else null
                val isGameDir = m3uFile != null

                if (file.isDirectory && !isGameDir) {
                    val hasChildren = file.listFiles()?.any { it.name != ".emu_launch" } == true
                    if (!hasChildren) return@mapNotNull null
                }

                val launchFile = m3uFile ?: file
                val displayName = if (file.isDirectory) file.name else file.nameWithoutExtension
                val artFile = findArt(tag, displayName)

                val target = when {
                    file.isDirectory && !isGameDir -> LaunchTarget.RetroArch
                    emuLaunch != null -> emuLaunch
                    coreName != null -> LaunchTarget.RetroArch
                    else -> LaunchTarget.RetroArch
                }

                Game(
                    file = if (isGameDir) launchFile else file,
                    displayName = displayName,
                    platformTag = tag,
                    isSubfolder = file.isDirectory && !isGameDir,
                    artFile = artFile,
                    launchTarget = target
                )
            }
            .sortedWith(compareBy<Game> { !it.isSubfolder }.thenBy(dev.cannoli.scorza.util.NaturalSort) { it.displayName })
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

                val target = when {
                    emuLaunch != null -> emuLaunch
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
        game.file.delete()
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
            File(cannoliRoot, "Screenshots"),
            File(cannoliRoot, "Recordings"),
            File(cannoliRoot, "Config"),
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
        return files.count { it.name != ".emu_launch" }
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
