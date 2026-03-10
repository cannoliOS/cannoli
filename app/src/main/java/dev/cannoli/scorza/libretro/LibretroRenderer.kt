package dev.cannoli.scorza.libretro

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

enum class ScalingMode { CORE_REPORTED, INTEGER, FULLSCREEN }
enum class Sharpness { SHARP, CRISP, SOFT }
enum class ScreenEffect { NONE, SCANLINE, GRID, CRT }

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

    @Volatile private var sharpnessDirty = false
    @Volatile private var shaderDirty = false

    @Volatile var fps = 0f; private set
    @Volatile var frameTimeMs = 0f; private set
    @Volatile var viewportWidth = 0; private set
    @Volatile var viewportHeight = 0; private set

    private var frameCount = 0
    private var fpsTimestamp = 0L

    var onFrameRendered: (() -> Unit)? = null

    private var textureId = 0
    private var programNone = 0
    private var programScanline = 0
    private var programGrid = 0
    private var programCrt = 0
    private var activeProgramId = 0
    private var frameBuffer: ByteBuffer? = null
    private var lastWidth = 0
    private var lastHeight = 0
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    private lateinit var vertexBuffer: FloatBuffer
    private lateinit var texCoordBuffer: FloatBuffer

    private val vertexShaderCode = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    private val fragmentNone = """
        precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D uTexture;
        void main() {
            gl_FragColor = texture2D(uTexture, vTexCoord);
        }
    """.trimIndent()

    private val fragmentScanline = """
        precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D uTexture;
        uniform vec2 uSourceSize;
        uniform vec2 uOutputSize;
        void main() {
            vec4 color = texture2D(uTexture, vTexCoord);
            float line = mod(floor(vTexCoord.y * uSourceSize.y), 2.0);
            float dim = 1.0 - line * 0.25;
            gl_FragColor = vec4(color.rgb * dim, color.a);
        }
    """.trimIndent()

    private val fragmentGrid = """
        precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D uTexture;
        uniform vec2 uSourceSize;
        uniform vec2 uOutputSize;
        void main() {
            vec4 color = texture2D(uTexture, vTexCoord);
            float lineX = mod(floor(vTexCoord.x * uSourceSize.x), 2.0);
            float lineY = mod(floor(vTexCoord.y * uSourceSize.y), 2.0);
            float dim = 1.0 - max(lineX, lineY) * 0.2;
            gl_FragColor = vec4(color.rgb * dim, color.a);
        }
    """.trimIndent()

    private val fragmentCrt = """
        precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D uTexture;
        uniform vec2 uSourceSize;
        uniform vec2 uOutputSize;

        vec2 barrel(vec2 uv, float amt) {
            vec2 cc = uv - 0.5;
            float dist = dot(cc, cc);
            return uv + cc * dist * amt;
        }

        void main() {
            vec2 uv = barrel(vTexCoord, 0.22);

            if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
                gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                return;
            }

            vec4 color = texture2D(uTexture, uv);

            float scanline = sin(uv.y * uSourceSize.y * 3.14159) * 0.5 + 0.5;
            scanline = mix(1.0, scanline, 0.18);
            color.rgb *= scanline;

            float px = fract(uv.x * uSourceSize.x);
            vec3 mask;
            if (px < 0.333) mask = vec3(1.0, 0.7, 0.7);
            else if (px < 0.666) mask = vec3(0.7, 1.0, 0.7);
            else mask = vec3(0.7, 0.7, 1.0);
            color.rgb *= mix(vec3(1.0), mask, 0.15);

            float vignette = 1.0 - dot(uv - 0.5, uv - 0.5) * 0.6;
            color.rgb *= vignette;

            gl_FragColor = color;
        }
    """.trimIndent()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        fpsTimestamp = System.nanoTime()

        val vertices = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(vertices).also { it.position(0) }

        val texCoords = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
        texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(texCoords).also { it.position(0) }

        programNone = createProgram(vertexShaderCode, fragmentNone)
        programScanline = createProgram(vertexShaderCode, fragmentScanline)
        programGrid = createProgram(vertexShaderCode, fragmentGrid)
        programCrt = createProgram(vertexShaderCode, fragmentCrt)
        activeProgramId = programNone

        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        textureId = texIds[0]

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        shaderDirty = true
        sharpnessDirty = true
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
                ScreenEffect.SCANLINE -> programScanline
                ScreenEffect.GRID -> programGrid
                ScreenEffect.CRT -> programCrt
            }
        }

        // Viewport calculation
        val gameAspect = when (scalingMode) {
            ScalingMode.FULLSCREEN -> surfaceWidth.toFloat() / surfaceHeight.toFloat()
            ScalingMode.CORE_REPORTED -> if (coreAspectRatio > 0f) coreAspectRatio else w.toFloat() / h.toFloat()
            ScalingMode.INTEGER -> w.toFloat() / h.toFloat()
        }
        val screenAspect = surfaceWidth.toFloat() / surfaceHeight.toFloat()

        var vpW: Int
        var vpH: Int
        if (scalingMode == ScalingMode.INTEGER || sharpness == Sharpness.CRISP) {
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

        GLES20.glViewport(vpX, vpY, vpW, vpH)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(activeProgramId)

        val posHandle = GLES20.glGetAttribLocation(activeProgramId, "aPosition")
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        val texHandle = GLES20.glGetAttribLocation(activeProgramId, "aTexCoord")
        GLES20.glEnableVertexAttribArray(texHandle)
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(activeProgramId, "uTexture"), 0)

        val srcLoc = GLES20.glGetUniformLocation(activeProgramId, "uSourceSize")
        if (srcLoc >= 0) GLES20.glUniform2f(srcLoc, w.toFloat(), h.toFloat())
        val outLoc = GLES20.glGetUniformLocation(activeProgramId, "uOutputSize")
        if (outLoc >= 0) GLES20.glUniform2f(outLoc, vpW.toFloat(), vpH.toFloat())

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(posHandle)
        GLES20.glDisableVertexAttribArray(texHandle)

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
