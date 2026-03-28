package dev.cannoli.scorza.scanner

import dev.cannoli.scorza.model.Collection
import dev.cannoli.scorza.model.Game
import dev.cannoli.scorza.model.LaunchTarget
import dev.cannoli.scorza.model.Platform
import dev.cannoli.scorza.util.sortedNatural
import java.io.File
import java.io.IOException

class FileScanner(
    private val cannoliRoot: File,
    private val platformResolver: PlatformResolver
) {
    private val romsDir = File(cannoliRoot, "Roms")
    private val artDir = File(cannoliRoot, "Art")
    private val collectionsDir = File(cannoliRoot, "Collections")
    private val toolsDir = File(cannoliRoot, "Config/Launch Scripts/Tools")
    private val portsDir = File(cannoliRoot, "Config/Launch Scripts/Ports")

    private val favoritesLock = Any()
    @Volatile private var favoritesCache: Set<String>? = null
    private val artCache = java.util.concurrent.ConcurrentHashMap<String, Map<String, File>>()
    private val mapCache = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()
    private val discRegex = Regex("""\s*\((Disc|Disk)\s*\d+\)|\s*\(CD\d+\)""", RegexOption.IGNORE_CASE)
    private val tagRegex = Regex("""\s*(\([^)]*\)|\[[^\]]*\])""")

    fun scanPlatforms(): List<Platform> {
        if (!romsDir.exists()) return emptyList()

        val tagDirs = romsDir.listFiles { f -> f.isDirectory } ?: return emptyList()

        val all = tagDirs
            .filter { platformResolver.isKnownTag(it.name) }
            .map { dir ->
                val tag = dir.name
                val gameCount = countGames(dir)
                platformResolver.resolvePlatform(tag, romsDir, gameCount)
            }

        return all.groupBy { it.displayName }.map { (_, group) ->
            if (group.size == 1) group[0]
            else {
                val primary = group.maxBy { it.gameCount }
                primary.copy(
                    gameCount = group.sumOf { it.gameCount },
                    tags = group.map { it.tag }
                )
            }
        }.sortedNatural { it.displayName }
    }

    fun scanGames(tags: List<String>, subfolder: String? = null): List<Game> {
        if (tags.size == 1) return scanGames(tags[0], subfolder)
        val combined = tags.flatMap { scanGames(it, subfolder) }
        return combined.sortedWith(
            compareBy<Game> { !it.isSubfolder }
                .thenBy { !it.displayName.startsWith("★") }
                .thenBy(dev.cannoli.scorza.util.NaturalSort) { it.displayName.removePrefix("★ ") }
        )
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
            coreName != null -> LaunchTarget.RetroArch
            appPackage != null -> LaunchTarget.ApkLaunch(appPackage)
            else -> LaunchTarget.RetroArch
        }

        val rawGames = files
            .filter { it.name != ".emu_launch" && it.name != "map.txt" }
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

        val usedM3uPaths = mutableSetOf<String>()
        val grouped = discGroups.flatMap { (baseName, games) ->
            if (games.size <= 1) return@flatMap games

            val existingM3u = others.find {
                it.file.extension.equals("m3u", ignoreCase = true) &&
                    it.file.nameWithoutExtension == baseName
            }
            if (existingM3u != null) {
                usedM3uPaths.add(existingM3u.file.absolutePath)
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
            it.file.absolutePath !in discFileSet &&
                it.file.absolutePath !in coveredByM3u &&
                it.file.absolutePath !in usedM3uPaths
        }

        val nameMap = parseMapFile(baseDir)
        val favPaths = getFavoritePaths()
        val all = applyMap(stripTags(filtered + grouped), nameMap)
        val starred = all.map { game ->
            if (!game.isSubfolder && game.file.absolutePath in favPaths)
                game.copy(displayName = "★ ${game.displayName}")
            else game
        }
        return starred.sortedWith(
            compareBy<Game> { !it.isSubfolder }
                .thenBy { !it.displayName.startsWith("★") }
                .thenBy(dev.cannoli.scorza.util.NaturalSort) { it.displayName.removePrefix("★ ") }
        )
    }

    private data class DirLaunch(val file: File, val discFiles: List<File>? = null)

    private fun parseMapFile(dir: File): Map<String, String> {
        return mapCache.getOrPut(dir.absolutePath) {
            val mapFile = File(dir, "map.txt")
            if (!mapFile.exists()) return@getOrPut emptyMap()
            mapFile.readLines()
                .filter { '\t' in it }
                .associate { line ->
                    val (filename, displayName) = line.split('\t', limit = 2)
                    filename.trim() to displayName.trim()
                }
        }
    }

    private fun applyMap(games: List<Game>, nameMap: Map<String, String>): List<Game> {
        if (nameMap.isEmpty()) return games
        return games.map { game ->
            val mapped = nameMap[game.file.name]
            if (mapped != null) game.copy(displayName = mapped) else game
        }
    }

    private fun stripTags(games: List<Game>): List<Game> {
        val stripped = games.map { g ->
            if (g.isSubfolder) g to g.displayName
            else g to tagRegex.replace(g.displayName, "").trim()
        }
        val baseCounts = mutableMapOf<String, Int>()
        for ((_, base) in stripped) {
            baseCounts[base] = (baseCounts[base] ?: 0) + 1
        }
        return stripped.map { (game, base) ->
            if (game.isSubfolder || base.isEmpty()) game
            else if (baseCounts[base]!! > 1) game
            else game.copy(displayName = base)
        }
    }

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

        return files.mapNotNull { file ->
            val lines = try {
                file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
            } catch (_: IOException) { return@mapNotNull null }
            val entries = lines.map { File(it) }.filter { it.exists() }

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

        val lines = try {
            collFile.readLines()
        } catch (_: IOException) { return emptyList() }

        return lines
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { File(it) }
            .filter { it.exists() && it.isFile }
            .map { file ->
                val tag = resolvePlatformTag(file)
                val rawName = file.nameWithoutExtension
                val displayName = rawName.replace(discRegex, "").trim().ifEmpty { rawName }
                val artFile = findArt(tag, displayName)
                val emuLaunch = platformResolver.getEmuLaunch(tag, romsDir)
                val coreName = platformResolver.getCoreName(tag)
                val appPackage = platformResolver.getAppPackage(tag)

                val target = when {
                    emuLaunch != null -> emuLaunch
                    coreName != null -> LaunchTarget.RetroArch
                    appPackage != null -> LaunchTarget.ApkLaunch(appPackage)
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
            .let(::stripTags)
            .map { game ->
                val mapped = game.file.parentFile?.let { parseMapFile(it) }?.get(game.file.name)
                if (mapped != null) game.copy(displayName = mapped) else game
            }
            .let { games ->
                if (collectionName.equals("Favorites", ignoreCase = true)) {
                    games.sortedWith(compareBy(dev.cannoli.scorza.util.NaturalSort) { it.displayName })
                } else {
                    val favPaths = getFavoritePaths()
                    games.map { game ->
                        if (game.file.absolutePath in favPaths)
                            game.copy(displayName = "★ ${game.displayName}")
                        else game
                    }.sortedWith(
                        compareBy<Game> { !it.displayName.startsWith("★") }
                            .thenBy(dev.cannoli.scorza.util.NaturalSort) { it.displayName.removePrefix("★ ") }
                    )
                }
            }
    }

    fun addToCollection(collectionName: String, romPath: String) {
        collectionsDir.mkdirs()
        val collFile = File(collectionsDir, "$collectionName.txt")
        val existing = try {
            if (collFile.exists()) collFile.readLines().map { it.trim() } else emptyList()
        } catch (_: IOException) { emptyList() }
        if (romPath !in existing) {
            try { collFile.appendText("$romPath\n") } catch (_: IOException) { }
        }
        favoritesCache = null
    }

    fun removeFromCollection(collectionName: String, romPath: String) {
        val collFile = File(collectionsDir, "$collectionName.txt")
        if (!collFile.exists()) return
        try {
            val remaining = collFile.readLines().map { it.trim() }.filter { it != romPath && it.isNotEmpty() }
            collFile.writeText(remaining.joinToString("\n") + if (remaining.isNotEmpty()) "\n" else "")
        } catch (_: IOException) { }
        favoritesCache = null
    }

    fun isInCollection(collectionName: String, romPath: String): Boolean {
        val collFile = File(collectionsDir, "$collectionName.txt")
        if (!collFile.exists()) return false
        return try {
            collFile.readLines().any { it.trim() == romPath }
        } catch (_: IOException) { false }
    }

    fun getFavoritePaths(): Set<String> {
        favoritesCache?.let { return it }
        return synchronized(favoritesLock) {
            favoritesCache?.let { return it }
            val collFile = File(collectionsDir, "Favorites.txt")
            if (!collFile.exists()) return emptySet()
            val result = try {
                collFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            } catch (_: IOException) { emptySet() }
            favoritesCache = result
            result
        }
    }

    fun createCollection(name: String) {
        collectionsDir.mkdirs()
        File(collectionsDir, "$name.txt").createNewFile()
    }

    fun deleteCollection(name: String) {
        File(collectionsDir, "$name.txt").delete()
        removeFromCollectionParents(name)
    }

    fun renameCollection(oldName: String, newName: String): Boolean {
        val oldFile = File(collectionsDir, "$oldName.txt")
        val newFile = File(collectionsDir, "$newName.txt")
        if (oldFile.exists() && !newFile.exists()) {
            val renamed = oldFile.renameTo(newFile)
            if (renamed) renameInCollectionParents(oldName, newName)
            return renamed
        }
        return false
    }

    fun getCollectionNames(): List<String> {
        if (!collectionsDir.exists()) return emptyList()
        return collectionsDir.listFiles { f -> f.extension == "txt" }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()
    }

    fun deleteGame(game: Game) {
        val paths = (game.discFiles?.map { it.absolutePath } ?: listOf(game.file.absolutePath)).toSet()
        if (game.discFiles != null) {
            game.discFiles.forEach { it.delete() }
        } else if (game.file.isDirectory) {
            game.file.deleteRecursively()
        } else {
            game.file.delete()
        }
        cleanCollectionPaths(paths)
    }

    private fun cleanCollectionPaths(deletedPaths: Set<String>) {
        if (!collectionsDir.exists()) return
        collectionsDir.listFiles { f -> f.extension == "txt" }?.forEach { collFile ->
            try {
                val lines = collFile.readLines()
                val cleaned = lines.filter { it.trim() !in deletedPaths }
                if (cleaned.size != lines.size) {
                    collFile.writeText(cleaned.joinToString("\n") + if (cleaned.isNotEmpty()) "\n" else "")
                }
            } catch (_: IOException) { }
        }
        favoritesCache = null
    }

    fun scanApkLaunches(dir: File): List<LaunchTarget.ApkLaunch> {
        if (!dir.exists()) return emptyList()

        val files = dir.listFiles { f -> f.extension == "apk_launch" } ?: return emptyList()

        return files.mapNotNull { file ->
            val pkg = try {
                file.readText().trim()
            } catch (_: IOException) { return@mapNotNull null }
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
            File(cannoliRoot, "Config/Overrides/systems"),
            File(cannoliRoot, "Config/Overrides/Games"),
            File(cannoliRoot, "Backup"),
            File(cannoliRoot, "Guides"),
            File(cannoliRoot, "Wallpapers"),
            toolsDir, portsDir
        ).forEach { it.mkdirs() }

        for (tag in platformResolver.getAllTags()) {
            File(romsDir, tag).mkdirs()
            File(artDir, tag).mkdirs()
            File(cannoliRoot, "Saves/$tag").mkdirs()
            File(cannoliRoot, "Save States/$tag").mkdirs()
        }
    }

    private fun scanApkLaunchesWithNames(dir: File): List<Pair<String, LaunchTarget.ApkLaunch>> {
        if (!dir.exists()) return emptyList()

        val files = dir.listFiles { f -> f.extension == "apk_launch" } ?: return emptyList()

        return files.mapNotNull { file ->
            val pkg = try {
                file.readText().trim()
            } catch (_: IOException) { return@mapNotNull null }
            if (pkg.isNotEmpty()) {
                file.nameWithoutExtension to LaunchTarget.ApkLaunch(pkg)
            } else null
        }.sortedNatural { it.first }
    }

    fun getCollectionsContaining(romPath: String): Set<String> {
        if (!collectionsDir.exists()) return emptySet()
        val result = mutableSetOf<String>()
        collectionsDir.listFiles { f -> f.extension == "txt" }?.forEach { file ->
            try {
                if (file.readLines().any { it.trim() == romPath }) {
                    result.add(file.nameWithoutExtension)
                }
            } catch (_: IOException) { }
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
        val visible = files.filter { it.name != ".emu_launch" && it.name != "map.txt" }
        val m3uNames = visible
            .filter { !it.isDirectory && it.extension.equals("m3u", ignoreCase = true) }
            .map { it.nameWithoutExtension }
            .toSet()
        val discFiles = visible.filter { !it.isDirectory && discRegex.containsMatchIn(it.nameWithoutExtension) }
        val groups = discFiles.groupBy { it.nameWithoutExtension.replace(discRegex, "").trim() }
        val multiDiscGroups = groups.filter { it.value.size > 1 }
        val coveredByM3u = multiDiscGroups.filter { it.key in m3uNames }
        val uncoveredGroups = multiDiscGroups - coveredByM3u.keys
        val discFileCount = uncoveredGroups.values.sumOf { it.size }
        val coveredDiscCount = coveredByM3u.values.sumOf { it.size }
        return visible.size - discFileCount + uncoveredGroups.size - coveredDiscCount
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
        mapCache.clear()
        favoritesCache = null
    }

    private val configDir = File(cannoliRoot, "Config")

    fun loadPlatformOrder(): List<String> {
        val file = File(configDir, "platform_order.txt")
        if (!file.exists()) return emptyList()
        return file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun savePlatformOrder(tags: List<String>) {
        configDir.mkdirs()
        File(configDir, "platform_order.txt").writeText(tags.joinToString("\n") + "\n")
    }

    fun getRaGameId(romPath: String): Int? {
        val file = File(configDir, "ra_game_ids.txt")
        if (!file.exists()) return null
        return try {
            file.readLines().firstOrNull { it.startsWith("$romPath=") }
                ?.substringAfter('=')?.trim()?.toIntOrNull()
        } catch (_: IOException) { null }
    }

    fun setRaGameId(romPath: String, gameId: Int?) {
        configDir.mkdirs()
        val file = File(configDir, "ra_game_ids.txt")
        val existing = try {
            if (file.exists()) file.readLines().filter { !it.startsWith("$romPath=") && it.isNotEmpty() }
            else emptyList()
        } catch (_: IOException) { emptyList() }
        val lines = if (gameId != null) existing + "$romPath=$gameId" else existing
        if (lines.isEmpty()) file.delete()
        else file.writeText(lines.joinToString("\n") + "\n")
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

    private val collectionParentsFile = File(configDir, "collection_parents.txt")

    fun loadCollectionParents(): Map<String, Set<String>> {
        if (!collectionParentsFile.exists()) return emptyMap()
        return try {
            collectionParentsFile.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && '=' in it }
                .associate { line ->
                    val (child, parentsStr) = line.split('=', limit = 2)
                    child.trim() to parentsStr.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                }
                .filterValues { it.isNotEmpty() }
        } catch (_: IOException) { emptyMap() }
    }

    private fun saveCollectionParents(map: Map<String, Set<String>>) {
        configDir.mkdirs()
        val filtered = map.filterValues { it.isNotEmpty() }
        if (filtered.isEmpty()) {
            collectionParentsFile.delete()
            return
        }
        collectionParentsFile.writeText(
            filtered.entries.joinToString("\n") { (child, parents) ->
                "$child=${parents.joinToString(",")}"
            } + "\n"
        )
    }

    fun getCollectionParents(childName: String): Set<String> {
        return loadCollectionParents()[childName] ?: emptySet()
    }

    fun setCollectionParents(childName: String, parents: Set<String>) {
        val map = loadCollectionParents().toMutableMap()
        if (parents.isEmpty()) map.remove(childName) else map[childName] = parents
        saveCollectionParents(map)
    }

    fun getChildCollections(parentName: String): List<String> {
        val allParents = loadCollectionParents()
        val children = allParents.entries
            .filter { parentName in it.value }
            .map { it.key }
        val order = loadChildOrder(parentName)
        if (order.isEmpty()) return children.sortedNatural { it }
        val byName = children.toSet()
        val ordered = order.filter { it in byName }
        val remaining = children.filter { it !in order }.sortedNatural { it }
        return ordered + remaining
    }

    fun loadChildOrder(parentName: String): List<String> {
        val file = File(configDir, "child_order_$parentName.txt")
        if (!file.exists()) return emptyList()
        return file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun saveChildOrder(parentName: String, names: List<String>) {
        configDir.mkdirs()
        val file = File(configDir, "child_order_$parentName.txt")
        if (names.isEmpty()) { file.delete(); return }
        file.writeText(names.joinToString("\n") + "\n")
    }

    fun isTopLevelCollection(name: String): Boolean {
        return getCollectionParents(name).isEmpty()
    }

    fun getDescendants(name: String): Set<String> {
        val allParents = loadCollectionParents()
        val result = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(name)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val children = allParents.entries
                .filter { current in it.value }
                .map { it.key }
            for (child in children) {
                if (result.add(child)) queue.add(child)
            }
        }
        return result
    }

    fun getAncestors(name: String): Set<String> {
        val allParents = loadCollectionParents()
        val result = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(name)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val parents = allParents[current] ?: emptySet()
            for (parent in parents) {
                if (result.add(parent)) queue.add(parent)
            }
        }
        return result
    }

    fun setChildCollections(parentName: String, children: Set<String>) {
        val map = loadCollectionParents().toMutableMap()
        val currentChildren = map.entries
            .filter { parentName in it.value }
            .map { it.key }
            .toSet()
        for (removed in currentChildren - children) {
            val parents = map[removed]?.minus(parentName) ?: emptySet()
            if (parents.isEmpty()) map.remove(removed) else map[removed] = parents
        }
        for (added in children - currentChildren) {
            map[added] = (map[added] ?: emptySet()) + parentName
        }
        saveCollectionParents(map)
    }

    private fun removeFromCollectionParents(name: String) {
        val map = loadCollectionParents().toMutableMap()
        map.remove(name)
        val updated = map.mapValues { (_, parents) -> parents - name }
        saveCollectionParents(updated)
    }

    private fun renameInCollectionParents(oldName: String, newName: String) {
        val map = loadCollectionParents()
        val updated = mutableMapOf<String, Set<String>>()
        for ((child, parents) in map) {
            val newChild = if (child == oldName) newName else child
            val newParents = if (oldName in parents) (parents - oldName) + newName else parents
            updated[newChild] = newParents
        }
        saveCollectionParents(updated)
    }
}
