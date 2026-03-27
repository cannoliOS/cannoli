package dev.cannoli.scorza.libretro

import android.graphics.BitmapFactory
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import dev.cannoli.scorza.libretro.shader.PresetParser
import dev.cannoli.scorza.libretro.shader.SlangTranspiler
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

class VulkanBackend(private val runner: LibretroRunner) : GraphicsBackend, SurfaceHolder.Callback {

    companion object {
        init {
            System.loadLibrary("vulkan_renderer")
        }

        fun isAvailable(): Boolean = try {
            Class.forName("dev.cannoli.scorza.libretro.VulkanBackend")
            true
        } catch (_: Throwable) { false }
    }

    var debugPath: String? = null
    var pipelineCachePath: String? = null
    private var surfaceViewRef: SurfaceView? = null

    @Volatile override var paused = false
    @Volatile override var fastForwardFrames = 0
    @Volatile override var scalingMode = ScalingMode.CORE_REPORTED
        set(value) { field = value; pushScaling() }
    @Volatile override var coreAspectRatio = 0f
        set(value) { field = value; pushScaling() }
    @Volatile override var debugHud = false
    @Volatile override var sharpness = Sharpness.SHARP
        set(value) { field = value; pushScaling() }
    @Volatile override var screenEffect = ScreenEffect.NONE
    @Volatile override var overlayPath: String? = null
        set(value) { field = value; loadOverlayImage(value) }
    @Volatile override var shaderPresetPath: String? = null
        set(value) { field = value; loadShaderPreset(value) }
    @Volatile override var lowLatency = false
        set(value) {
            field = value
            renderHandler?.post { if (initialized) nativeSetLowLatency(value) }
        }
    @Volatile override var onFrameRendered: (() -> Unit)? = null

    override val backendName = "Vulkan"
    @Volatile override var fps = 0f; private set
    @Volatile override var frameTimeMs = 0f; private set
    @Volatile override var viewportWidth = 0; private set
    @Volatile override var viewportHeight = 0; private set

    private fun loadShaderPreset(path: String?) {
        if (renderHandler == null) {
            // Render thread not ready yet — will load when surface is created
            return
        }
        renderHandler?.post {
            val wasRunning = running
            running = false
            vkWaitIdle()
            nativeUnloadPreset()
            try {
                if (!path.isNullOrEmpty() && screenEffect != ScreenEffect.NONE) {
                    val singlePath = path.split("|").firstOrNull { it.isNotEmpty() }
                    if (singlePath != null) {
                        val file = java.io.File(singlePath)
                        val preset = PresetParser.parse(file)
                        if (preset != null) {
                            val spirvData = mutableListOf<ByteArray>()
                            val configData = mutableListOf<Int>()
                            val scaleData = mutableListOf<Float>()
                            var ok = true

                            for (pass in preset.passes) {
                                val shaderFile = java.io.File(preset.basePath, pass.shaderPath)
                                if (!shaderFile.exists()) { ok = false; break }
                                val source = shaderFile.readText()
                                if (!SlangTranspiler.isVulkanGLSL(source)) { ok = false; break }

                                val (rawVs, rawFs) = SlangTranspiler.splitSlangStages(source)
                                val basePath = shaderFile.parent
                                val resolvedVs = basePath?.let { SlangTranspiler.resolveIncludesPublic(rawVs, it) } ?: rawVs
                                val resolvedFs = basePath?.let { SlangTranspiler.resolveIncludesPublic(rawFs, it) } ?: rawFs

                                val vertSpirv = SlangTranspiler.compileToSpirv(resolvedVs, isVertex = true)
                                val fragSpirv = SlangTranspiler.compileToSpirv(resolvedFs, isVertex = false)
                                if (vertSpirv == null || fragSpirv == null) { ok = false; break }

                                spirvData.add(vertSpirv)
                                spirvData.add(fragSpirv)
                                configData.add(pass.scaleType.ordinal)
                                configData.add(if (pass.filterLinear) 1 else 0)
                                configData.add(if (source.contains("Original") || source.contains("OrigTexture")) 1 else 0)
                                scaleData.add(pass.scaleX)
                                scaleData.add(pass.scaleY)
                            }

                            if (ok) {
                                nativeLoadPreset(
                                    spirvData.toTypedArray(),
                                    configData.toIntArray(),
                                    scaleData.toFloatArray(),
                                    preset.passes.size
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) { Log.e("VulkanBackend", "Shader preset load failed", e) }
            if (wasRunning) { running = true; renderLoopGen++; postRenderFrame() }
        }
    }

    private fun vkWaitIdle() {
        if (initialized) nativeWaitIdle()
    }

    private fun loadOverlayImage(path: String?) {
        renderHandler?.post {
            if (path.isNullOrEmpty()) {
                nativeUnloadOverlay()
                return@post
            }
            val bitmap = try { BitmapFactory.decodeFile(path) } catch (_: Exception) { null }
            if (bitmap == null) { nativeUnloadOverlay(); return@post }
            val buf = ByteBuffer.allocateDirect(bitmap.width * bitmap.height * 4).order(ByteOrder.nativeOrder())
            bitmap.copyPixelsToBuffer(buf)
            buf.position(0)
            nativeLoadOverlay(buf, bitmap.width, bitmap.height)
            bitmap.recycle()
        }
    }

    private fun pushScaling() {
        renderHandler?.post {
            nativeSetScaling(scalingMode.ordinal, coreAspectRatio, sharpness.ordinal)
        }
    }

    private var lastFrameNanos = 0L

    private val shaderParamOverrides = ConcurrentHashMap<String, Float>()
    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null
    private var initialized = false
    private var running = false
    private var renderLoopGen = 0

    override fun setShaderParameter(id: String, value: Float) {
        shaderParamOverrides[id] = value
        renderHandler?.post { nativeSetParam(id, value) }
    }

    override fun clearShaderParamOverrides() {
        shaderParamOverrides.clear()
    }

    fun attachToSurface(surfaceView: SurfaceView) {
        surfaceViewRef = surfaceView
        surfaceView.holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        renderThread = HandlerThread("VulkanRender").also { it.start() }
        renderHandler = Handler(renderThread!!.looper)
        renderHandler?.post {
            initialized = nativeInit(holder.surface, pipelineCachePath)
            if (initialized) {
                nativeSetScaling(scalingMode.ordinal, coreAspectRatio, sharpness.ordinal)
                // Load shader preset and overlay now that native is ready
                loadShaderPreset(shaderPresetPath)
                loadOverlayImage(overlayPath)
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
        renderThread?.join(3000)
        renderThread = null
        renderHandler = null
    }

    private fun postRenderFrame() {
        val gen = renderLoopGen
        renderHandler?.post {
            if (!running || !initialized || gen != renderLoopGen) return@post

            if (!paused) {
                runner.run()
                val extra = fastForwardFrames
                if (extra > 0) for (i in 1 until extra) runner.run()
            }

            nativeRenderFrame()
            val now = System.nanoTime()
            if (lastFrameNanos != 0L) frameTimeMs = (now - lastFrameNanos) / 1_000_000f
            lastFrameNanos = now
            fps = nativeGetFps()
            viewportWidth = nativeGetViewportWidth()
            viewportHeight = nativeGetViewportHeight()
            onFrameRendered?.invoke()

            if (running) postRenderFrame()
        }
    }

    private external fun nativeInit(surface: Surface, cachePath: String?): Boolean
    private external fun nativeRenderFrame()
    private external fun nativeSurfaceChanged(width: Int, height: Int)
    private external fun nativeDestroy()
    private external fun nativeGetFps(): Float
    private external fun nativeSetParam(name: String, value: Float)
    private external fun nativeSetScaling(mode: Int, coreAspect: Float, sharpness: Int)
    private external fun nativeGetViewportWidth(): Int
    private external fun nativeGetViewportHeight(): Int
    private external fun nativeLoadOverlay(buffer: ByteBuffer, width: Int, height: Int)
    private external fun nativeUnloadOverlay()
    private external fun nativeLoadPreset(passData: Array<ByteArray>, configData: IntArray, scales: FloatArray, passCount: Int): Boolean
    private external fun nativeUnloadPreset()
    private external fun nativeWaitIdle()
    private external fun nativeSetLowLatency(enabled: Boolean)
}
