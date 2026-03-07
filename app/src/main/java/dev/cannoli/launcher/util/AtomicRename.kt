package dev.cannoli.launcher.util

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AtomicRename(private val cannoliRoot: File) {

    private val backupDir get() = File(cannoliRoot, "Backup")
    private val savesDir get() = File(cannoliRoot, "Saves")
    private val statesDir get() = File(cannoliRoot, "Save States")
    private val artDir get() = File(cannoliRoot, "Art")
    private val collectionsDir get() = File(cannoliRoot, "Collections")

    data class RenameResult(val success: Boolean, val error: String? = null)

    fun rename(romFile: File, newBaseName: String, platformTag: String): RenameResult {
        val oldBaseName = romFile.nameWithoutExtension
        val extension = romFile.extension
        val romDir = romFile.parentFile ?: return RenameResult(false, "Cannot resolve ROM directory")

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val backupTagDir = File(backupDir, "$platformTag/${oldBaseName}-$timestamp")

        try {
            backupTagDir.mkdirs()
            romFile.copyTo(File(backupTagDir, romFile.name), overwrite = true)
            findArtFile(platformTag, oldBaseName)?.let { artFile ->
                artFile.copyTo(File(backupTagDir, artFile.name), overwrite = true)
            }
            findMatchingFiles(File(savesDir, platformTag), oldBaseName).forEach { save ->
                save.copyTo(File(backupTagDir, "saves_${save.name}"), overwrite = true)
            }
            findMatchingFiles(File(statesDir, platformTag), oldBaseName).forEach { state ->
                state.copyTo(File(backupTagDir, "states_${state.name}"), overwrite = true)
            }
        } catch (e: Exception) {
            backupTagDir.deleteRecursively()
            return RenameResult(false, "Backup failed: ${e.message}")
        }

        try {
            val newRomFile = File(romDir, "$newBaseName.$extension")
            if (!romFile.renameTo(newRomFile)) {
                throw Exception("Failed to rename ROM file")
            }
            findArtFile(platformTag, oldBaseName)?.let { artFile ->
                val newArtFile = File(artFile.parentFile, "$newBaseName.${artFile.extension}")
                artFile.renameTo(newArtFile)
            }
            findMatchingFiles(File(savesDir, platformTag), oldBaseName).forEach { save ->
                val newName = save.name.replaceFirst(oldBaseName, newBaseName)
                save.renameTo(File(save.parentFile, newName))
            }
            findMatchingFiles(File(statesDir, platformTag), oldBaseName).forEach { state ->
                val newName = state.name.replaceFirst(oldBaseName, newBaseName)
                state.renameTo(File(state.parentFile, newName))
            }
            updateCollectionReferences(romFile.absolutePath, newRomFile.absolutePath)
        } catch (e: Exception) {
            try {
                rollback(backupTagDir, romFile, platformTag, oldBaseName)
            } catch (_: Exception) { }
            return RenameResult(false, "Rename failed: ${e.message}")
        }

        return RenameResult(true)
    }

    private fun rollback(backupDir: File, originalRom: File, tag: String, oldBaseName: String) {
        val romDir = originalRom.parentFile ?: return
        backupDir.listFiles()?.forEach { backup ->
            when {
                backup.name.startsWith("saves_") -> {
                    val origName = backup.name.removePrefix("saves_")
                    backup.copyTo(File(File(savesDir, tag), origName), overwrite = true)
                }
                backup.name.startsWith("states_") -> {
                    val origName = backup.name.removePrefix("states_")
                    backup.copyTo(File(File(statesDir, tag), origName), overwrite = true)
                }
                backup.name == originalRom.name -> {
                    backup.copyTo(File(romDir, backup.name), overwrite = true)
                }
                else -> {
                    val artTagDir = File(artDir, tag)
                    artTagDir.mkdirs()
                    backup.copyTo(File(artTagDir, backup.name), overwrite = true)
                }
            }
        }
    }

    private fun findArtFile(tag: String, baseName: String): File? {
        val artTagDir = File(artDir, tag)
        if (!artTagDir.exists()) return null
        val extensions = listOf("png", "jpg", "jpeg", "PNG", "JPG", "JPEG")
        for (ext in extensions) {
            val candidate = File(artTagDir, "$baseName.$ext")
            if (candidate.exists()) return candidate
        }
        return null
    }

    private fun findMatchingFiles(dir: File, baseName: String): List<File> {
        if (!dir.exists()) return emptyList()
        return dir.listFiles()?.filter {
            it.nameWithoutExtension == baseName || it.nameWithoutExtension.startsWith("$baseName.")
        } ?: emptyList()
    }

    private fun updateCollectionReferences(oldPath: String, newPath: String) {
        if (!collectionsDir.exists()) return
        collectionsDir.listFiles { f -> f.extension == "txt" }?.forEach { collFile ->
            val lines = collFile.readLines()
            val updated = lines.map { line ->
                if (line.trim() == oldPath) newPath else line
            }
            if (updated != lines) {
                collFile.writeText(updated.joinToString("\n") + "\n")
            }
        }
    }
}
