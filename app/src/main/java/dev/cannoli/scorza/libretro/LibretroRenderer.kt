package dev.cannoli.scorza.libretro

import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

enum class ScalingMode { CORE_REPORTED, INTEGER, FULLSCREEN }
enum class Sharpness { SHARP, CRISP, SOFT }
enum class ScreenEffect { NONE, LCD, CRT }

class LibretroRenderer(private val runner: LibretroRunner) : GLSurfaceView.Renderer {

    @Volatile var paused = false
    @Volatile var fastForwardFrames = 0
    @Volatile var scalingMode = ScalingMode.CORE_REPORTED
    @Volatile var coreAspectRatio = 0f
    @Volatile var debugHud = false

    @Volatile var sharpness = Sharpness.SHARP
        set(value) { field = value; sharpnessDirty = true }

    @Volatile var screenEffect = ScreenEffect.NONE
        set(value) { field = value; shaderDirty = true }

    @Volatile var crtCurvature = 1.7f
    @Volatile var crtScanline = 0.75f
    @Volatile var crtMaskDark = 0.3f
    @Volatile var crtVignette = 0.85f
    @Volatile var crtGlow = 0.25f
    @Volatile var crtSweep = 1.0f
    @Volatile var crtSweepBright = 0.35f
    @Volatile var crtBrightness = 1.0f
    @Volatile var crtNoise = 0.15f

    @Volatile var overlayPath: String? = null
        set(value) { field = value; overlayDirty = true }

    @Volatile private var sharpnessDirty = false
    @Volatile private var shaderDirty = false
    @Volatile private var overlayDirty = false
    private var overlayTextureId = 0
    private var overlayLoaded = false

    @Volatile var fps = 0f; private set
    @Volatile var frameTimeMs = 0f; private set
    @Volatile var viewportWidth = 0; private set
    @Volatile var viewportHeight = 0; private set

    private var frameCount = 0
    private var fpsTimestamp = 0L
    private var startTimeNanos = 0L
    private var sweepAccumNanos = 0L
    private var sweepLastNanos = 0L

    var onFrameRendered: (() -> Unit)? = null

    private var textureId = 0
    private var programNone = 0

    private var programLcd = 0
    private var programKawase = 0
    private var programCrt = 0
    private var activeProgramId = 0
    private var frameBuffer: ByteBuffer? = null
    private var lastWidth = 0
    private var lastHeight = 0
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    private var fboBlit = 0
    private var texBlit = 0
    private var fboBlurA = 0
    private var texBlurA = 0
    private var fboBlurB = 0
    private var texBlurB = 0
    private var crtFboW = 0
    private var crtFboH = 0

    private lateinit var vertexBuffer: FloatBuffer
    private lateinit var texCoordBuffer: FloatBuffer
    private lateinit var fboTexCoordBuffer: FloatBuffer

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        fpsTimestamp = System.nanoTime()
        val now = System.nanoTime()
        startTimeNanos = now
        sweepLastNanos = now

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

        programLcd = createProgram(Shaders.vertex, Shaders.lcd)
        programKawase = createProgram(Shaders.vertex, Shaders.kawaseBlur)
        programCrt = createProgram(Shaders.vertex, Shaders.crtComposite)
        activeProgramId = programNone

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

        crtFboW = 0
        crtFboH = 0

        shaderDirty = true
        sharpnessDirty = true
        overlayDirty = true
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
            if (screenEffect == ScreenEffect.CRT && elapsedSeconds() < 1.867f) {
                drawCrtBoot()
            } else {
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            }
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
            activeProgramId = when (screenEffect) {
                ScreenEffect.NONE -> programNone
                ScreenEffect.LCD -> programLcd
                ScreenEffect.CRT -> programCrt
            }
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

        if (screenEffect == ScreenEffect.CRT) {
            drawCrt(w, h, vpX, vpY, vpW, vpH)
        } else {
            drawSimple(w, h, vpX, vpY, vpW, vpH)
        }

        if (overlayLoaded) drawOverlay()

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
    }

    private fun drawSimple(w: Int, h: Int, vpX: Int, vpY: Int, vpW: Int, vpH: Int) {
        GLES20.glViewport(vpX, vpY, vpW, vpH)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(activeProgramId)
        bindQuadAttribs(activeProgramId)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(activeProgramId, "uTexture"), 0)

        val srcLoc = GLES20.glGetUniformLocation(activeProgramId, "uSourceSize")
        if (srcLoc >= 0) GLES20.glUniform2f(srcLoc, w.toFloat(), h.toFloat())
        val outLoc = GLES20.glGetUniformLocation(activeProgramId, "uOutputSize")
        if (outLoc >= 0) GLES20.glUniform2f(outLoc, vpW.toFloat(), vpH.toFloat())

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        unbindQuadAttribs(activeProgramId)
    }

    private fun drawCrtBoot() {
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(programCrt)
        bindQuadAttribs(programCrt, fboTexCoordBuffer)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programCrt, "uTexture"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programCrt, "uGlowTex"), 1)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(programCrt, "uSourceSize"), 256f, 224f)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(programCrt, "uOutputSize"), surfaceWidth.toFloat(), surfaceHeight.toFloat())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programCrt, "uCurvature"), crtCurvature)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programCrt, "uScanline"), crtScanline)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programCrt, "uMaskDark"), crtMaskDark)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programCrt, "uVignette"), crtVignette)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programCrt, "uGlow"), crtGlow)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programCrt, "uSweep"), crtSweep)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programCrt, "uSweepBright"), crtSweepBright)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programCrt, "uBrightness"), crtBrightness)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programCrt, "uNoise"), crtNoise)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programCrt, "uTime"), elapsedSeconds())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programCrt, "uSweepPhase"), sweepPhase())
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        unbindQuadAttribs(programCrt)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    private val SWEEP_CYCLE_NANOS = 8_000_000_000L

    private fun elapsedSeconds(): Float =
        (System.nanoTime() - startTimeNanos) / 1_000_000_000f

    private fun sweepPhase(): Float {
        val now = System.nanoTime()
        if (!paused) {
            sweepAccumNanos += now - sweepLastNanos
            sweepAccumNanos %= SWEEP_CYCLE_NANOS
        }
        sweepLastNanos = now
        return 1f - sweepAccumNanos.toFloat() / SWEEP_CYCLE_NANOS
    }

    private fun drawCrt(w: Int, h: Int, vpX: Int, vpY: Int, vpW: Int, vpH: Int) {
        ensureCrtFbos(w, h)

        // Blit: copy game frame into FBO with Y-flip
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboBlit)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(programNone)
        bindQuadAttribs(programNone, texCoordBuffer)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programNone, "uTexture"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        unbindQuadAttribs(programNone)

        // Kawase blur passes: blit → A → B → A → B
        val kawaseDistances = floatArrayOf(0f, 1f, 2f, 3f)
        val texelW = 1f / w
        val texelH = 1f / h
        var readTex = texBlit
        val targets = arrayOf(
            fboBlurA to texBlurA,
            fboBlurB to texBlurB,
            fboBlurA to texBlurA,
            fboBlurB to texBlurB
        )
        for (i in kawaseDistances.indices) {
            val (targetFbo, _) = targets[i]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targetFbo)
            GLES20.glViewport(0, 0, w, h)
            GLES20.glUseProgram(programKawase)
            bindQuadAttribs(programKawase, fboTexCoordBuffer)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, readTex)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(programKawase, "uTexture"), 0)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(programKawase, "uTexelSize"), texelW, texelH)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(programKawase, "uDistance"), kawaseDistances[i])
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            unbindQuadAttribs(programKawase)
            readTex = targets[i].second
        }

        // CRT composite: sharp frame + glow → screen
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(vpX, vpY, vpW, vpH)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(programCrt)
        bindQuadAttribs(programCrt, fboTexCoordBuffer)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texBlit)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programCrt, "uTexture"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texBlurB)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programCrt, "uGlowTex"), 1)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(programCrt, "uSourceSize"), w.toFloat(), h.toFloat())
        GLES20.glUniform2f(GLES20.glGetUniformLocation(programCrt, "uOutputSize"), vpW.toFloat(), vpH.toFloat())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programCrt, "uCurvature"), crtCurvature)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programCrt, "uScanline"), crtScanline)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programCrt, "uMaskDark"), crtMaskDark)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programCrt, "uVignette"), crtVignette)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programCrt, "uGlow"), crtGlow)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programCrt, "uSweep"), crtSweep)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programCrt, "uSweepBright"), crtSweepBright)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programCrt, "uBrightness"), crtBrightness)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programCrt, "uNoise"), crtNoise)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programCrt, "uTime"), elapsedSeconds())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programCrt, "uSweepPhase"), sweepPhase())
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        unbindQuadAttribs(programCrt)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
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

    private fun ensureCrtFbos(w: Int, h: Int) {
        if (crtFboW == w && crtFboH == h) return
        destroyCrtFbos()

        val blit = createFbo(w, h)
        fboBlit = blit.first
        texBlit = blit.second
        val blurA = createFbo(w, h)
        fboBlurA = blurA.first
        texBlurA = blurA.second
        val blurB = createFbo(w, h)
        fboBlurB = blurB.first
        texBlurB = blurB.second

        crtFboW = w
        crtFboH = h
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun createFbo(w: Int, h: Int): Pair<Int, Int> {
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[0])
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val fboIds = IntArray(1)
        GLES20.glGenFramebuffers(1, fboIds, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, texIds[0], 0
        )

        return fboIds[0] to texIds[0]
    }

    private fun destroyCrtFbos() {
        if (crtFboW == 0) return
        val allFbos = intArrayOf(fboBlit, fboBlurA, fboBlurB)
        val allTexs = intArrayOf(texBlit, texBlurA, texBlurB)
        GLES20.glDeleteFramebuffers(allFbos.size, allFbos, 0)
        GLES20.glDeleteTextures(allTexs.size, allTexs, 0)
        crtFboW = 0
        crtFboH = 0
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
