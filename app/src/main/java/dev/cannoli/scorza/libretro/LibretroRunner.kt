package dev.cannoli.scorza.libretro

import java.nio.ByteBuffer

data class SystemAvInfo(
    val width: Int,
    val height: Int,
    val fps: Double,
    val sampleRate: Int
)

class LibretroRunner {

    companion object {
        init {
            System.loadLibrary("retro_bridge")
        }

        const val PIXEL_FORMAT_RGB565 = 2
        const val PIXEL_FORMAT_XRGB8888 = 1
        const val DEVICE_NONE = 0
        const val DEVICE_JOYPAD = 1
        const val MAX_PORTS = 4
    }

    fun loadCore(corePath: String): Boolean = nativeLoadCore(corePath)

    fun init(systemDir: String, saveDir: String) = nativeInit(systemDir, saveDir)

    fun setAudioCallback(audio: LibretroAudio) = nativeSetAudioCallback(audio)

    fun loadGame(romPath: String): SystemAvInfo? {
        val result = nativeLoadGame(romPath) ?: return null
        return SystemAvInfo(
            width = result[0],
            height = result[1],
            fps = result[2] / 100.0,
            sampleRate = result[3]
        )
    }

    fun run() = nativeRun()

    fun setInput(port: Int, mask: Int) = nativeSetInput(port, mask)

    fun setControllerPortDevice(port: Int, device: Int) = nativeSetControllerPortDevice(port, device)

    fun getRotation(): Int = nativeGetRotation()
    fun getPixelFormat(): Int = nativeGetPixelFormat()
    fun getFrameWidth(): Int = nativeGetFrameWidth()
    fun getFrameHeight(): Int = nativeGetFrameHeight()
    fun hasNewFrame(): Boolean = nativeHasNewFrame()
    fun copyFrame(buffer: ByteBuffer) = nativeCopyFrame(buffer)
    fun copyLastFrame(buffer: ByteBuffer) = nativeCopyLastFrame(buffer)

    fun saveState(path: String): Boolean = nativeSaveState(path)
    fun loadState(path: String): Boolean = nativeLoadState(path)
    fun saveSRAM(path: String): Boolean = nativeSaveSRAM(path)
    fun loadSRAM(path: String): Boolean = nativeLoadSRAM(path)

    fun unloadGame() = nativeUnloadGame()
    fun deinit() = nativeDeinit()
    fun reset() = nativeReset()

    fun getSystemInfo(): Pair<String, String> {
        val arr = nativeGetSystemInfo()
        return (arr[0] ?: "") to (arr[1] ?: "")
    }

    fun getAspectRatio(): Float = nativeGetAspectRatio()

    data class CoreOptionValue(val value: String, val label: String)
    data class CoreOption(
        val key: String,
        val desc: String,
        val values: List<CoreOptionValue>,
        val selected: String,
        val category: String,
        val info: String
    )
    data class CoreOptionCategory(val key: String, val desc: String, val info: String)

    fun getCoreOptions(): List<CoreOption> {
        val arr = nativeGetCoreOptions()
        val result = mutableListOf<CoreOption>()
        var i = 0
        while (i + 6 < arr.size) {
            val rawValues = arr[i + 2].split('|').filter { it.isNotEmpty() }
            val rawLabels = arr[i + 5].split('|')
            val values = rawValues.mapIndexed { idx, v ->
                CoreOptionValue(v, rawLabels.getOrElse(idx) { v })
            }
            result.add(CoreOption(
                key = arr[i],
                desc = arr[i + 1],
                values = values,
                selected = arr[i + 3],
                category = arr[i + 4],
                info = arr[i + 6]
            ))
            i += 7
        }
        return result
    }

    fun getCoreCategories(): List<CoreOptionCategory> {
        val arr = nativeGetCoreCategories()
        val result = mutableListOf<CoreOptionCategory>()
        var i = 0
        while (i + 2 < arr.size) {
            result.add(CoreOptionCategory(arr[i], arr[i + 1], arr[i + 2]))
            i += 3
        }
        return result
    }

    fun setCoreOption(key: String, value: String) = nativeSetCoreOption(key, value)

    fun getDiskCount(): Int = nativeGetDiskCount()
    fun getDiskIndex(): Int = nativeGetDiskIndex()
    fun setDiskIndex(index: Int): Boolean = nativeSetDiskIndex(index)
    fun getDiskLabel(index: Int): String? = nativeGetDiskLabel(index)

    private external fun nativeLoadCore(corePath: String): Boolean
    private external fun nativeInit(systemDir: String, saveDir: String)
    private external fun nativeSetAudioCallback(audio: LibretroAudio)
    private external fun nativeLoadGame(romPath: String): IntArray?
    private external fun nativeRun()
    private external fun nativeSetInput(port: Int, mask: Int)
    private external fun nativeSetControllerPortDevice(port: Int, device: Int)
    private external fun nativeGetRotation(): Int
    private external fun nativeGetPixelFormat(): Int
    private external fun nativeGetFrameWidth(): Int
    private external fun nativeGetFrameHeight(): Int
    private external fun nativeHasNewFrame(): Boolean
    private external fun nativeCopyFrame(buffer: ByteBuffer)
    private external fun nativeCopyLastFrame(buffer: ByteBuffer)
    private external fun nativeSaveState(path: String): Boolean
    private external fun nativeLoadState(path: String): Boolean
    private external fun nativeSaveSRAM(path: String): Boolean
    private external fun nativeLoadSRAM(path: String): Boolean
    private external fun nativeUnloadGame()
    private external fun nativeDeinit()
    private external fun nativeReset()
    private external fun nativeGetSystemInfo(): Array<String>
    private external fun nativeGetAspectRatio(): Float
    private external fun nativeGetCoreOptions(): Array<String>
    private external fun nativeGetCoreCategories(): Array<String>
    private external fun nativeSetCoreOption(key: String, value: String)
    private external fun nativeGetDiskCount(): Int
    private external fun nativeGetDiskIndex(): Int
    private external fun nativeSetDiskIndex(index: Int): Boolean
    private external fun nativeGetDiskLabel(index: Int): String?
}
