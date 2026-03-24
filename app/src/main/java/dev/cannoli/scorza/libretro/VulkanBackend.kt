package dev.cannoli.scorza.libretro

import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView

class VulkanBackend(private val runner: LibretroRunner) : GraphicsBackend, SurfaceHolder.Callback {

    companion object {
        init {
            System.loadLibrary("vulkan_renderer")
        }

        fun isAvailable(): Boolean = try {
            Class.forName("dev.cannoli.scorza.libretro.VulkanBackend")
            true
        } catch (_: Exception) { false }
    }

    @Volatile override var paused = false
    @Volatile override var fastForwardFrames = 0
    @Volatile override var scalingMode = ScalingMode.CORE_REPORTED
    @Volatile override var coreAspectRatio = 0f
    @Volatile override var debugHud = false
    @Volatile override var sharpness = Sharpness.SHARP
    @Volatile override var screenEffect = ScreenEffect.NONE
    @Volatile override var overlayPath: String? = null
    @Volatile override var shaderPresetPath: String? = null
    override var onFrameRendered: (() -> Unit)? = null

    @Volatile override var fps = 0f; private set
    @Volatile override var frameTimeMs = 0f; private set
    @Volatile override var viewportWidth = 0; private set
    @Volatile override var viewportHeight = 0; private set

    private val shaderParamOverrides = mutableMapOf<String, Float>()
    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null
    private var initialized = false
    private var running = false

    override fun setShaderParameter(id: String, value: Float) {
        shaderParamOverrides[id] = value
        renderHandler?.post { nativeSetParam(id, value) }
    }

    override fun clearShaderParamOverrides() {
        shaderParamOverrides.clear()
    }

    fun attachToSurface(surfaceView: SurfaceView) {
        surfaceView.holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        renderThread = HandlerThread("VulkanRender").also { it.start() }
        renderHandler = Handler(renderThread!!.looper)
        renderHandler?.post {
            initialized = nativeInit(holder.surface)
            if (initialized) {
                running = true
                postRenderFrame()
            }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        renderHandler?.post {
            if (initialized) nativeSurfaceChanged(width, height)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        running = false
        renderHandler?.post {
            if (initialized) {
                nativeDestroy()
                initialized = false
            }
        }
        renderThread?.quitSafely()
        renderThread?.join()
        renderThread = null
        renderHandler = null
    }

    private fun postRenderFrame() {
        renderHandler?.post {
            if (!running || !initialized) return@post

            if (!paused) {
                runner.run()
                val extra = fastForwardFrames
                if (extra > 0) for (i in 1 until extra) runner.run()
            }

            nativeRenderFrame()
            fps = nativeGetFps()
            onFrameRendered?.invoke()

            if (running) postRenderFrame()
        }
    }

    private external fun nativeInit(surface: Surface): Boolean
    private external fun nativeRenderFrame()
    private external fun nativeSurfaceChanged(width: Int, height: Int)
    private external fun nativeDestroy()
    private external fun nativeGetFps(): Float
    private external fun nativeSetParam(name: String, value: Float)
}
