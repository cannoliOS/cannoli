package dev.cannoli.scorza.libretro

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SaveSlotManager(private val stateBasePath: String) {

    data class Slot(val index: Int, val label: String)

    val slots = listOf(Slot(0, "Auto")) +
        (0..9).map { Slot(it + 1, "Slot $it") }

    fun statePath(slot: Slot): String {
        if (slot.index == 0) return "$stateBasePath.auto"
        val n = slot.index - 1
        return if (n == 0) stateBasePath else "$stateBasePath$n"
    }

    fun thumbnailPath(slot: Slot): String {
        if (slot.index == 0) return "$stateBasePath.auto.png"
        val n = slot.index - 1
        return if (n == 0) "$stateBasePath.png" else "$stateBasePath$n.png"
    }

    fun stateExists(slot: Slot): Boolean = File(statePath(slot)).exists()

    fun deleteState(slot: Slot) {
        File(statePath(slot)).delete()
        File(thumbnailPath(slot)).delete()
    }

    fun loadThumbnail(slot: Slot): Bitmap? {
        val f = File(thumbnailPath(slot))
        if (!f.exists()) return null
        return try { BitmapFactory.decodeFile(f.absolutePath) } catch (_: Exception) { null }
    }

    fun saveState(runner: LibretroRunner, slot: Slot): Boolean {
        val path = statePath(slot)
        File(path).parentFile?.mkdirs()

        if (slot.index == 0) rotateSlots()

        val ok = runner.saveState(path)
        if (ok) captureScreenshot(runner, thumbnailPath(slot))
        return ok
    }

    fun loadState(runner: LibretroRunner, slot: Slot): Boolean {
        val path = statePath(slot)
        if (!File(path).exists()) return false
        return runner.loadState(path)
    }

    private fun raPath(n: Int) = if (n == 0) stateBasePath else "$stateBasePath$n"
    private fun raThumbPath(n: Int) = if (n == 0) "$stateBasePath.png" else "$stateBasePath$n.png"

    private fun rotateSlots() {
        for (i in 9 downTo 1) {
            moveFile(raPath(i - 1), raPath(i))
            moveFile(raThumbPath(i - 1), raThumbPath(i))
        }
        moveFile("$stateBasePath.auto", raPath(0))
        moveFile("$stateBasePath.auto.png", raThumbPath(0))
    }

    private fun moveFile(src: String, dst: String) {
        val srcFile = File(src)
        if (!srcFile.exists()) return
        val dstFile = File(dst)
        dstFile.delete()
        if (!srcFile.renameTo(dstFile)) {
            srcFile.copyTo(dstFile, overwrite = true)
            srcFile.delete()
        }
    }

    private fun undoStatePath() = "$stateBasePath.undo"
    private fun undoThumbPath() = "$stateBasePath.undo.png"

    fun cacheForUndoSave(slot: Slot) {
        clearUndoCache()
        val state = File(statePath(slot))
        val thumb = File(thumbnailPath(slot))
        if (state.exists()) state.copyTo(File(undoStatePath()), overwrite = true)
        if (thumb.exists()) thumb.copyTo(File(undoThumbPath()), overwrite = true)
    }

    fun cacheForUndoLoad(runner: LibretroRunner) {
        clearUndoCache()
        runner.saveState(undoStatePath())
        captureScreenshot(runner, undoThumbPath())
    }

    fun performUndoSave(slot: Slot) {
        val undoState = File(undoStatePath())
        val undoThumb = File(undoThumbPath())
        if (undoState.exists()) {
            undoState.copyTo(File(statePath(slot)), overwrite = true)
            undoState.delete()
        } else {
            File(statePath(slot)).delete()
        }
        if (undoThumb.exists()) {
            undoThumb.copyTo(File(thumbnailPath(slot)), overwrite = true)
            undoThumb.delete()
        } else {
            File(thumbnailPath(slot)).delete()
        }
    }

    fun performUndoLoad(runner: LibretroRunner) {
        val undoState = File(undoStatePath())
        if (undoState.exists()) {
            runner.loadState(undoStatePath())
            undoState.delete()
            File(undoThumbPath()).delete()
        }
    }

    fun clearUndoCache() {
        File(undoStatePath()).delete()
        File(undoThumbPath()).delete()
    }

    private fun captureScreenshot(runner: LibretroRunner, path: String) {
        val w = runner.getFrameWidth()
        val h = runner.getFrameHeight()
        if (w == 0 || h == 0) return

        val pixelFormat = runner.getPixelFormat()
        val bpp = if (pixelFormat == LibretroRunner.PIXEL_FORMAT_XRGB8888) 4 else 2
        val buf = ByteBuffer.allocateDirect(w * h * bpp).order(ByteOrder.nativeOrder())
        runner.copyLastFrame(buf)
        buf.position(0)

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)

        if (pixelFormat == LibretroRunner.PIXEL_FORMAT_XRGB8888) {
            for (i in pixels.indices) {
                val r = buf.get().toInt() and 0xFF
                val g = buf.get().toInt() and 0xFF
                val b = buf.get().toInt() and 0xFF
                buf.get()
                pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        } else {
            for (i in pixels.indices) {
                val pixel = buf.short.toInt() and 0xFFFF
                val r = ((pixel shr 11) and 0x1F) * 255 / 31
                val g = ((pixel shr 5) and 0x3F) * 255 / 63
                val b = (pixel and 0x1F) * 255 / 31
                pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)

        try {
            File(path).outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
        } catch (_: Exception) {}
        bitmap.recycle()
    }
}
