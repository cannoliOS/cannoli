package dev.cannoli.scorza.libretro

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class LibretroRenderer(private val runner: LibretroRunner) : GLSurfaceView.Renderer {

    @Volatile
    var paused = false

    private var textureId = 0
    private var programId = 0
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

    private val fragmentShaderCode = """
        precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D uTexture;
        void main() {
            gl_FragColor = texture2D(uTexture, vTexCoord);
        }
    """.trimIndent()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        // Full-screen quad vertices
        val vertices = floatArrayOf(
            -1f, -1f,
             1f, -1f,
            -1f,  1f,
             1f,  1f
        )
        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices)
            .also { it.position(0) }

        // Tex coords (flipped Y for libretro's top-down layout)
        val texCoords = floatArrayOf(
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f
        )
        texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(texCoords)
            .also { it.position(0) }

        programId = createProgram(vertexShaderCode, fragmentShaderCode)

        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        textureId = texIds[0]

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
    }

    override fun onDrawFrame(gl: GL10?) {
        if (!paused) {
            runner.run()
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
            val glFormat = if (pixelFormat == LibretroRunner.PIXEL_FORMAT_XRGB8888) {
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                    w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, frameBuffer
                )
                GLES20.GL_RGBA
            } else {
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB,
                    w, h, 0, GLES20.GL_RGB, GLES20.GL_UNSIGNED_SHORT_5_6_5, frameBuffer
                )
                GLES20.GL_RGB
            }
        }

        // Calculate viewport to maintain aspect ratio
        val gameAspect = w.toFloat() / h.toFloat()
        val screenAspect = surfaceWidth.toFloat() / surfaceHeight.toFloat()
        val (vpW, vpH) = if (gameAspect > screenAspect) {
            surfaceWidth to (surfaceWidth / gameAspect).toInt()
        } else {
            (surfaceHeight * gameAspect).toInt() to surfaceHeight
        }
        val vpX = (surfaceWidth - vpW) / 2
        val vpY = (surfaceHeight - vpH) / 2

        GLES20.glViewport(vpX, vpY, vpW, vpH)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(programId)

        val posHandle = GLES20.glGetAttribLocation(programId, "aPosition")
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        val texHandle = GLES20.glGetAttribLocation(programId, "aTexCoord")
        GLES20.glEnableVertexAttribArray(texHandle)
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "uTexture"), 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(posHandle)
        GLES20.glDisableVertexAttribArray(texHandle)
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
