package dev.cannoli.scorza.libretro

import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import dev.cannoli.scorza.libretro.shader.PresetParser
import dev.cannoli.scorza.libretro.shader.ShaderPipeline
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

enum class ScalingMode { CORE_REPORTED, INTEGER, FULLSCREEN }
enum class Sharpness { SHARP, CRISP, SOFT }
enum class ScreenEffect { NONE, SHADER }
enum class GraphicsBackendPref { AUTO, GLES, VULKAN }

class LibretroRenderer(private val runner: LibretroRunner) : GLSurfaceView.Renderer, GraphicsBackend {

    @Volatile override var paused = false
    @Volatile override var fastForwardFrames = 0
    @Volatile override var scalingMode = ScalingMode.CORE_REPORTED
    @Volatile override var coreAspectRatio = 0f
    @Volatile override var debugHud = false

    @Volatile override var sharpness = Sharpness.SHARP
        set(value) { field = value; sharpnessDirty = true }

    @Volatile override var screenEffect = ScreenEffect.NONE
        set(value) { field = value; shaderDirty = true }

    @Volatile override var overlayPath: String? = null
        set(value) { field = value; overlayDirty = true }

    @Volatile override var shaderPresetPath: String? = null
        set(value) { field = value; pipelineDirty = true }

    @Volatile private var sharpnessDirty = false
    @Volatile private var shaderDirty = false
    @Volatile private var overlayDirty = false
    @Volatile private var pipelineDirty = false
    private var pipeline: ShaderPipeline? = null
    private var overlayTextureId = 0
    private var overlayLoaded = false

    override val backendName = "GLES 3.0"
    @Volatile override var fps = 0f; private set
    @Volatile override var frameTimeMs = 0f; private set
    @Volatile override var viewportWidth = 0; private set
    @Volatile override var viewportHeight = 0; private set

    private var frameCount = 0
    private var fpsTimestamp = 0L

    private val shaderParamOverrides = mutableMapOf<String, Float>()

    override fun setShaderParameter(id: String, value: Float) {
        shaderParamOverrides[id] = value
        pipeline?.parameters?.set(id, value)
    }

    override fun clearShaderParamOverrides() {
        shaderParamOverrides.clear()
    }

    override var onFrameRendered: (() -> Unit)? = null

    private var textureId = 0
    private var programNone = 0
    private var frameBuffer: ByteBuffer? = null
    private var lastWidth = 0
    private var lastHeight = 0
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    private lateinit var vertexBuffer: FloatBuffer
    private lateinit var texCoordBuffer: FloatBuffer
    private lateinit var fboTexCoordBuffer: FloatBuffer

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        fpsTimestamp = System.nanoTime()

        val vertices = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(vertices).also { it.position(0) }

        val texCoords = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
        texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(texCoords).also { it.position(0) }

        val fboTexCoords = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        fboTexCoordBuffer = ByteBuffer.allocateDirect(fboTexCoords.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(fboTexCoords).also { it.position(0) }

        programNone = createProgram(Shaders.vertex, Shaders.passthrough)

        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        textureId = texIds[0]

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val black = ByteBuffer.allocateDirect(4).put(byteArrayOf(0, 0, 0, -1)).also { it.position(0) }
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, black)

        val ovlIds = IntArray(1)
        GLES20.glGenTextures(1, ovlIds, 0)
        overlayTextureId = ovlIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        overlayLoaded = false

        pipeline?.destroy()
        pipeline = null
        ShaderPipeline.invalidateSharedProgram()

        shaderDirty = true
        sharpnessDirty = true
        overlayDirty = true
        pipelineDirty = true
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
    }

    override fun onDrawFrame(gl: GL10?) {
        if (!paused) {
            runner.run()
            val extraFrames = fastForwardFrames
            if (extraFrames > 0) {
                for (i in 1 until extraFrames) runner.run()
            }
        }

        val w = runner.getFrameWidth()
        val h = runner.getFrameHeight()
        if (w == 0 || h == 0) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            frameCount++
            val now = System.nanoTime()
            val elapsed = now - fpsTimestamp
            if (elapsed >= 1_000_000_000L) {
                fps = frameCount * 1_000_000_000f / elapsed
                frameTimeMs = elapsed / (frameCount * 1_000_000f)
                frameCount = 0
                fpsTimestamp = now
            }
            onFrameRendered?.invoke()
            return
        }

        if (runner.hasNewFrame()) {
            val pixelFormat = runner.getPixelFormat()
            val bpp = if (pixelFormat == LibretroRunner.PIXEL_FORMAT_XRGB8888) 4 else 2
            val needed = w * h * bpp

            if (frameBuffer == null || lastWidth != w || lastHeight != h) {
                frameBuffer = ByteBuffer.allocateDirect(needed).order(ByteOrder.nativeOrder())
                lastWidth = w
                lastHeight = h
            }

            frameBuffer!!.clear()
            runner.copyFrame(frameBuffer!!)
            frameBuffer!!.position(0)

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            if (pixelFormat == LibretroRunner.PIXEL_FORMAT_XRGB8888) {
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                    w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, frameBuffer
                )
            } else {
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB,
                    w, h, 0, GLES20.GL_RGB, GLES20.GL_UNSIGNED_SHORT_5_6_5, frameBuffer
                )
            }
        }

        if (sharpnessDirty) {
            sharpnessDirty = false
            val filter = when (sharpness) {
                Sharpness.SHARP -> GLES20.GL_NEAREST
                Sharpness.CRISP, Sharpness.SOFT -> GLES20.GL_LINEAR
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
        }

        if (shaderDirty) {
            shaderDirty = false
            if (screenEffect != ScreenEffect.NONE) pipelineDirty = true
        }

        if (pipelineDirty) {
            pipelineDirty = false
            loadPipeline()
        }

        if (overlayDirty) {
            overlayDirty = false
            loadOverlayTexture()
        }

        val gameAspect = when (scalingMode) {
            ScalingMode.FULLSCREEN -> surfaceWidth.toFloat() / surfaceHeight.toFloat()
            ScalingMode.CORE_REPORTED -> if (coreAspectRatio > 0f) coreAspectRatio else w.toFloat() / h.toFloat()
            ScalingMode.INTEGER -> w.toFloat() / h.toFloat()
        }
        val screenAspect = surfaceWidth.toFloat() / surfaceHeight.toFloat()

        var vpW: Int
        var vpH: Int
        if (scalingMode == ScalingMode.INTEGER) {
            val scaleX = surfaceWidth / w
            val scaleY = surfaceHeight / h
            val scale = maxOf(1, minOf(scaleX, scaleY))
            vpW = w * scale
            vpH = h * scale
        } else if (gameAspect > screenAspect) {
            vpW = surfaceWidth
            vpH = (surfaceWidth / gameAspect).toInt()
        } else {
            vpW = (surfaceHeight * gameAspect).toInt()
            vpH = surfaceHeight
        }
        val vpX = (surfaceWidth - vpW) / 2
        val vpY = (surfaceHeight - vpH) / 2

        viewportWidth = vpW
        viewportHeight = vpH

        if (screenEffect != ScreenEffect.NONE && pipeline != null) {
            pipeline!!.render(textureId, w, h, vpX, vpY, vpW, vpH,
                texCoordBuffer, fboTexCoordBuffer, vertexBuffer)
        } else {
            drawSimple(w, h, vpX, vpY, vpW, vpH)
        }
        if (overlayLoaded) drawOverlay()

        frameCount++
        val fpsNow = System.nanoTime()
        val elapsed = fpsNow - fpsTimestamp
        if (elapsed >= 1_000_000_000L) {
            fps = frameCount * 1_000_000_000f / elapsed
            frameTimeMs = elapsed / (frameCount * 1_000_000f)
            frameCount = 0
            fpsTimestamp = fpsNow
        }

        onFrameRendered?.invoke()
    }

    private fun drawSimple(w: Int, h: Int, vpX: Int, vpY: Int, vpW: Int, vpH: Int) {
        GLES20.glViewport(vpX, vpY, vpW, vpH)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(programNone)
        bindQuadAttribs(programNone)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programNone, "uTexture"), 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        unbindQuadAttribs(programNone)
    }

    private fun loadPipeline() {
        pipeline?.destroy()
        pipeline = null
        val path = shaderPresetPath
        if (path.isNullOrEmpty() || screenEffect == ScreenEffect.NONE) return
        val file = File(path)
        val preset = PresetParser.parse(file)
        if (preset == null) { Log.w("LibretroRenderer", "Failed to parse: $path"); return }
        pipeline = ShaderPipeline.compile(preset)
        if (pipeline == null) { Log.w("LibretroRenderer", "Failed to compile: $path"); return }
        for ((key, value) in shaderParamOverrides) {
            pipeline!!.parameters[key] = value
        }
    }

    private fun loadOverlayTexture() {
        val path = overlayPath
        if (path.isNullOrEmpty()) { overlayLoaded = false; return }
        val bitmap = try { BitmapFactory.decodeFile(path) } catch (_: Exception) { null }
        if (bitmap == null) { overlayLoaded = false; return }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
        overlayLoaded = true
    }

    private fun drawOverlay() {
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(programNone)
        bindQuadAttribs(programNone)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programNone, "uTexture"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        unbindQuadAttribs(programNone)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun bindQuadAttribs(program: Int, tcBuffer: FloatBuffer = texCoordBuffer) {
        val posHandle = GLES20.glGetAttribLocation(program, "aPosition")
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        val texHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        GLES20.glEnableVertexAttribArray(texHandle)
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 0, tcBuffer)
    }

    private fun unbindQuadAttribs(program: Int) {
        GLES20.glDisableVertexAttribArray(GLES20.glGetAttribLocation(program, "aPosition"))
        GLES20.glDisableVertexAttribArray(GLES20.glGetAttribLocation(program, "aTexCoord"))
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        return program
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        return shader
    }
}
