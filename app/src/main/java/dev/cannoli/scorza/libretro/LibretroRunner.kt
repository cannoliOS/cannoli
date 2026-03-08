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

    fun setInput(mask: Int) = nativeSetInput(mask)

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

    private external fun nativeLoadCore(corePath: String): Boolean
    private external fun nativeInit(systemDir: String, saveDir: String)
    private external fun nativeSetAudioCallback(audio: LibretroAudio)
    private external fun nativeLoadGame(romPath: String): IntArray?
    private external fun nativeRun()
    private external fun nativeSetInput(mask: Int)
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
}
