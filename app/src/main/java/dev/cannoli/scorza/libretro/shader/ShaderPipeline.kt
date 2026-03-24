package dev.cannoli.scorza.libretro.shader

import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class ShaderPipeline private constructor(
    private val preset: ShaderPreset,
    private val passPrograms: IntArray,
    private val passFbos: IntArray,
    private val passTextures: IntArray,
    private val sourceFbo: Int,
    private val sourceTexture: Int,
    private val lutTextures: Map<String, Int>,
    val parameters: MutableMap<String, Float>
) {
    private var frameCount = 0
    private var fboWidth = 0
    private var fboHeight = 0

    fun render(
        gameTexture: Int,
        frameW: Int, frameH: Int,
        vpX: Int, vpY: Int, vpW: Int, vpH: Int,
        texCoordBuffer: FloatBuffer,
        fboTexCoordBuffer: FloatBuffer,
        vertexBuffer: FloatBuffer,
        targetFramebuffer: Int = 0
    ) {
        if (preset.passes.isEmpty()) return
        ensureFbos(frameW, frameH, vpW, vpH)

        // Blit game frame into source FBO (Y-flip correction)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sourceFbo)
        GLES20.glViewport(0, 0, frameW, frameH)
        GLES20.glUseProgram(passthroughProgram)
        bindQuad(passthroughProgram, vertexBuffer, texCoordBuffer)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, gameTexture)
        setUniform1i(passthroughProgram, "Texture", 0)
        setUniform1i(passthroughProgram, "uTexture", 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        unbindQuad(passthroughProgram)

        frameCount++

        for (i in preset.passes.indices) {
            val pass = preset.passes[i]
            val isLast = i == preset.passes.lastIndex
            val program = passPrograms[i]

            val inputTex = if (i == 0) sourceTexture else passTextures[i - 1]
            val inputW = if (i == 0) frameW else passOutputW(i - 1, frameW, vpW)
            val inputH = if (i == 0) frameH else passOutputH(i - 1, frameH, vpH)
            val outW = if (isLast) vpW else passOutputW(i, frameW, vpW)
            val outH = if (isLast) vpH else passOutputH(i, frameH, vpH)

            if (isLast) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targetFramebuffer)
                if (targetFramebuffer == 0) {
                    GLES20.glViewport(vpX, vpY, vpW, vpH)
                } else {
                    GLES20.glViewport(0, 0, vpW, vpH)
                }
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            } else {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, passFbos[i])
                GLES20.glViewport(0, 0, outW, outH)
            }

            GLES20.glUseProgram(program)
            bindQuad(program, vertexBuffer, fboTexCoordBuffer)

            // Bind input texture
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTex)
            val filter = if (pass.filterLinear) GLES20.GL_LINEAR else GLES20.GL_NEAREST
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
            val wrap = if (pass.wrapRepeat) GLES20.GL_REPEAT else GLES20.GL_CLAMP_TO_EDGE
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, wrap)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, wrap)
            setUniform1i(program, "Texture", 0)
            setUniform1i(program, "Source", 0)

            // Bind original frame
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sourceTexture)
            setUniform1i(program, "OrigTexture", 1)
            setUniform1i(program, "Original", 1)

            // Bind LUT textures starting at unit 2
            var texUnit = 2
            for ((name, texId) in lutTextures) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + texUnit)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
                setUniform1i(program, name, texUnit)
                texUnit++
            }

            // Bind previous pass outputs by alias
            for (j in 0 until i) {
                val alias = preset.passes[j].alias
                if (alias.isNotEmpty()) {
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + texUnit)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, passTextures[j])
                    setUniform1i(program, alias, texUnit)
                    val pw = passOutputW(j, frameW, vpW)
                    val ph = passOutputH(j, frameW, vpH)
                    setUniform2f(program, "${alias}Size", pw.toFloat(), ph.toFloat())
                    setUniform2f(program, "${alias}TextureSize", pw.toFloat(), ph.toFloat())
                    texUnit++
                }
            }

            // Standard uniforms (vec2 for GLSL presets)
            setUniform2f(program, "TextureSize", inputW.toFloat(), inputH.toFloat())
            setUniform2f(program, "InputSize", frameW.toFloat(), frameH.toFloat())
            setUniform2f(program, "OutputSize", outW.toFloat(), outH.toFloat())
            setUniform2f(program, "OrigTextureSize", frameW.toFloat(), frameH.toFloat())
            setUniform2f(program, "OrigInputSize", frameW.toFloat(), frameH.toFloat())

            // Slang push_constant convention (vec4: xy = size, zw = 1/size)
            for (prefix in arrayOf("params.", "params_")) {
                setUniform4f(program, "${prefix}SourceSize", inputW.toFloat(), inputH.toFloat(), 1f / inputW, 1f / inputH)
                setUniform4f(program, "${prefix}OriginalSize", frameW.toFloat(), frameH.toFloat(), 1f / frameW, 1f / frameH)
                setUniform4f(program, "${prefix}OutputSize", outW.toFloat(), outH.toFloat(), 1f / outW, 1f / outH)
            }

            val fc = if (pass.frameCountMod > 0) frameCount % pass.frameCountMod else frameCount
            setUniform1i(program, "FrameCount", fc)
            setUniform1i(program, "params_FrameCount", fc)
            setUniform1i(program, "FrameDirection", 1)

            // MVP identity matrix
            for (name in arrayOf("MVPMatrix", "global.MVP", "global_MVP")) {
                val loc = GLES20.glGetUniformLocation(program, name)
                if (loc >= 0) GLES20.glUniformMatrix4fv(loc, 1, false, IDENTITY_MATRIX, 0)
            }

            // User-adjustable parameters
            for ((key, value) in parameters) {
                setUniform1f(program, key, value)
                setUniform1f(program, "params_$key", value)
            }

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            unbindQuad(program)
        }

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    fun destroy() {
        for (p in passPrograms) if (p != 0) GLES20.glDeleteProgram(p)
        destroyFbos()
        for ((_, texId) in lutTextures) {
            GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
        }
    }

    private fun destroyFbos() {
        if (fboWidth == 0) return
        val allFbos = passFbos.toList() + sourceFbo
        val allTexs = passTextures.toList() + sourceTexture
        GLES20.glDeleteFramebuffers(allFbos.size, allFbos.toIntArray(), 0)
        GLES20.glDeleteTextures(allTexs.size, allTexs.toIntArray(), 0)
        fboWidth = 0
        fboHeight = 0
    }

    private fun ensureFbos(frameW: Int, frameH: Int, vpW: Int, vpH: Int) {
        if (fboWidth == frameW && fboHeight == frameH) return
        recreateFbo(sourceFbo, sourceTexture, frameW, frameH, true, false)

        for (i in preset.passes.indices) {
            if (i == preset.passes.lastIndex) continue
            val w = passOutputW(i, frameW, vpW)
            val h = passOutputH(i, frameH, vpH)
            recreateFbo(passFbos[i], passTextures[i], w, h, true, preset.passes[i].floatFbo)
        }
        fboWidth = frameW
        fboHeight = frameH
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun passOutputW(passIndex: Int, frameW: Int, vpW: Int): Int {
        val pass = preset.passes[passIndex]
        return when (pass.scaleType) {
            ScaleType.SOURCE -> (frameW * pass.scaleX).toInt().coerceAtLeast(1)
            ScaleType.VIEWPORT -> (vpW * pass.scaleX).toInt().coerceAtLeast(1)
            ScaleType.ABSOLUTE -> pass.scaleX.toInt().coerceAtLeast(1)
        }
    }

    private fun passOutputH(passIndex: Int, frameH: Int, vpH: Int): Int {
        val pass = preset.passes[passIndex]
        return when (pass.scaleType) {
            ScaleType.SOURCE -> (frameH * pass.scaleY).toInt().coerceAtLeast(1)
            ScaleType.VIEWPORT -> (vpH * pass.scaleY).toInt().coerceAtLeast(1)
            ScaleType.ABSOLUTE -> pass.scaleY.toInt().coerceAtLeast(1)
        }
    }

    companion object {
        private const val TAG = "ShaderPipeline"
        private var passthroughProgram = 0

        fun invalidateSharedProgram() {
            passthroughProgram = 0
        }

        private val IDENTITY_MATRIX = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )

        private val DEFAULT_VERTEX = """
            precision mediump float;
            attribute vec2 VertexCoord;
            attribute vec2 TexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(VertexCoord, 0.0, 1.0);
                vTexCoord = TexCoord;
            }
        """.trimIndent()

        private val PASSTHROUGH_VERTEX = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        private val PASSTHROUGH_FRAGMENT = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """.trimIndent()

        fun compile(preset: ShaderPreset): ShaderPipeline? {
            if (preset.passes.isEmpty()) return null

            if (passthroughProgram == 0) {
                passthroughProgram = compileProgram(PASSTHROUGH_VERTEX, PASSTHROUGH_FRAGMENT)
                if (passthroughProgram == 0) {
                    Log.e(TAG, "Failed to compile passthrough program")
                    return null
                }
            }

            val passPrograms = IntArray(preset.passes.size)
            for (i in preset.passes.indices) {
                val pass = preset.passes[i]
                val shaderFile = File(preset.basePath, pass.shaderPath)
                if (!shaderFile.exists()) {
                    Log.e(TAG, "Shader file not found: ${shaderFile.absolutePath}")
                    cleanup(passPrograms)
                    return null
                }
                val source = shaderFile.readText()
                val vs: String
                val fs: String

                if (SlangTranspiler.isVulkanGLSL(source)) {
                    val (rawVs, rawFs) = SlangTranspiler.splitSlangStages(source)
                    val basePath = shaderFile.parent
                    val transVs = SlangTranspiler.transpile(rawVs, isVertex = true, basePath)
                    val transFs = SlangTranspiler.transpile(rawFs, isVertex = false, basePath)
                    if (transVs == null || transFs == null) {
                        cleanup(passPrograms)
                        return null
                    }
                    vs = transVs
                    fs = transFs
                } else {
                    val prepared = prepareSource(source)
                    val (splitVs, splitFs) = PresetParser.splitVertexFragment(prepared)
                    vs = splitVs ?: DEFAULT_VERTEX
                    fs = splitFs ?: prepared
                }

                val program = compileProgram(vs, ensurePrecision(fs))
                if (program == 0) {
                    cleanup(passPrograms)
                    return null
                }
                passPrograms[i] = program
            }

            // Create FBO handles (actual textures allocated lazily in ensureFbos)
            val passCount = preset.passes.size
            val passFbos = IntArray(passCount)
            val passTextures = IntArray(passCount)
            for (i in 0 until passCount - 1) {
                val fbo = createFboHandle()
                passFbos[i] = fbo.first
                passTextures[i] = fbo.second
            }
            val sourceFbo = createFboHandle()

            // Load LUT textures
            val lutTextures = mutableMapOf<String, Int>()
            for ((name, ref) in preset.textures) {
                val file = File(preset.basePath, ref.path)
                val texId = loadLutTexture(file, ref)
                if (texId != 0) lutTextures[name] = texId
            }

            val paramValues = mutableMapOf<String, Float>()
            for ((key, def) in preset.parameters) {
                paramValues[key] = def.default
            }

            return ShaderPipeline(
                preset, passPrograms, passFbos, passTextures,
                sourceFbo.first, sourceFbo.second, lutTextures, paramValues
            )
        }

        private fun prepareSource(source: String): String {
            var result = source
            if (source.contains("#pragma parameter")) {
                result = "#define PARAMETER_UNIFORM 1\n$result"
            }
            return result
        }

        private fun ensurePrecision(fragment: String): String {
            if (fragment.contains("precision ")) return fragment
            return "precision mediump float;\n$fragment"
        }

        var cacheDir: File? = null

        private fun compileProgram(vertexSrc: String, fragmentSrc: String): Int {
            val cached = loadCachedBinary(vertexSrc, fragmentSrc)
            if (cached != 0) return cached

            val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
            if (vs == 0) return 0
            val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
            if (fs == 0) { GLES20.glDeleteShader(vs); return 0 }
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vs)
            GLES20.glAttachShader(program, fs)
            GLES30.glProgramParameteri(program, GLES30.GL_PROGRAM_BINARY_RETRIEVABLE_HINT, GLES20.GL_TRUE)
            GLES20.glLinkProgram(program)
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            val status = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                val err = GLES20.glGetProgramInfoLog(program)
                Log.e(TAG, "Link error: $err")
                GLES20.glDeleteProgram(program)
                return 0
            }
            saveBinaryToCache(program, vertexSrc, fragmentSrc)
            return program
        }

        private fun sourceHash(vertexSrc: String, fragmentSrc: String): String {
            val digest = MessageDigest.getInstance("SHA-1")
            digest.update(vertexSrc.toByteArray())
            digest.update(fragmentSrc.toByteArray())
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun loadCachedBinary(vertexSrc: String, fragmentSrc: String): Int {
            val dir = cacheDir ?: return 0
            val hash = sourceHash(vertexSrc, fragmentSrc)
            val binFile = File(dir, "$hash.bin")
            val fmtFile = File(dir, "$hash.fmt")
            if (!binFile.exists() || !fmtFile.exists()) return 0

            return try {
                val binary = binFile.readBytes()
                val format = fmtFile.readText().trim().toInt()
                val buf = ByteBuffer.allocateDirect(binary.size)
                    .order(ByteOrder.nativeOrder()).put(binary).also { it.position(0) }
                val program = GLES20.glCreateProgram()
                GLES30.glProgramBinary(program, format, buf, binary.size)
                val status = IntArray(1)
                GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
                if (status[0] == 0) {
                    GLES20.glDeleteProgram(program)
                    binFile.delete()
                    fmtFile.delete()
                    0
                } else {
                    Log.i(TAG, "Loaded cached shader: $hash")
                    program
                }
            } catch (_: Exception) { 0 }
        }

        private fun saveBinaryToCache(program: Int, vertexSrc: String, fragmentSrc: String) {
            val dir = cacheDir ?: return
            try {
                val length = IntArray(1)
                GLES20.glGetProgramiv(program, GLES30.GL_PROGRAM_BINARY_LENGTH, length, 0)
                if (length[0] <= 0) return
                val binary = ByteBuffer.allocateDirect(length[0]).order(ByteOrder.nativeOrder())
                val format = IntArray(1)
                val written = IntArray(1)
                GLES30.glGetProgramBinary(program, length[0], written, 0, format, 0, binary)
                if (written[0] <= 0) return
                val hash = sourceHash(vertexSrc, fragmentSrc)
                dir.mkdirs()
                val bytes = ByteArray(written[0])
                binary.position(0)
                binary.get(bytes)
                File(dir, "$hash.bin").writeBytes(bytes)
                File(dir, "$hash.fmt").writeText(format[0].toString())
            } catch (_: Exception) { }
        }

        private fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val typeName = if (type == GLES20.GL_VERTEX_SHADER) "vertex" else "fragment"
                val err = GLES20.glGetShaderInfoLog(shader)
                Log.e(TAG, "Compile error ($typeName): $err")
                GLES20.glDeleteShader(shader)
                return 0
            }
            return shader
        }

        private fun createFboHandle(): Pair<Int, Int> {
            val texIds = IntArray(1)
            GLES20.glGenTextures(1, texIds, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[0])
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

        private fun recreateFbo(fbo: Int, tex: Int, w: Int, h: Int, linear: Boolean, floatFbo: Boolean) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            if (floatFbo) {
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F,
                    w, h, 0, GLES20.GL_RGBA, GLES20.GL_FLOAT, null
                )
            } else {
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                    w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
                )
            }
            val filter = if (linear) GLES20.GL_LINEAR else GLES20.GL_NEAREST
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
        }

        private fun loadLutTexture(file: File, ref: TextureRef): Int {
            if (!file.exists()) return 0
            val bitmap = try { BitmapFactory.decodeFile(file.absolutePath) } catch (_: Exception) { return 0 }
            val texIds = IntArray(1)
            GLES20.glGenTextures(1, texIds, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[0])
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            bitmap.recycle()
            val filter = if (ref.filterLinear) GLES20.GL_LINEAR else GLES20.GL_NEAREST
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
            val wrap = if (ref.wrapRepeat) GLES20.GL_REPEAT else GLES20.GL_CLAMP_TO_EDGE
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, wrap)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, wrap)
            if (ref.mipmap) GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
            return texIds[0]
        }

        private fun cleanup(programs: IntArray) {
            for (p in programs) if (p != 0) GLES20.glDeleteProgram(p)
        }

        private fun findAttrib(program: Int, vararg names: String): Int {
            for (name in names) {
                val loc = GLES20.glGetAttribLocation(program, name)
                if (loc >= 0) return loc
            }
            return -1
        }

        private fun bindQuad(program: Int, vertexBuffer: FloatBuffer, texCoordBuffer: FloatBuffer) {
            val posLoc = findAttrib(program, "VertexCoord", "Position", "aPosition")
            if (posLoc >= 0) {
                GLES20.glEnableVertexAttribArray(posLoc)
                GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
            }
            val texLoc = findAttrib(program, "TexCoord", "aTexCoord")
            if (texLoc >= 0) {
                GLES20.glEnableVertexAttribArray(texLoc)
                GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
            }
        }

        private fun unbindQuad(program: Int) {
            val posLoc = findAttrib(program, "VertexCoord", "Position", "aPosition")
            if (posLoc >= 0) GLES20.glDisableVertexAttribArray(posLoc)
            val texLoc = findAttrib(program, "TexCoord", "aTexCoord")
            if (texLoc >= 0) GLES20.glDisableVertexAttribArray(texLoc)
        }

        private fun setUniform1i(program: Int, name: String, value: Int) {
            val loc = GLES20.glGetUniformLocation(program, name)
            if (loc >= 0) GLES20.glUniform1i(loc, value)
        }

        private fun setUniform1f(program: Int, name: String, value: Float) {
            val loc = GLES20.glGetUniformLocation(program, name)
            if (loc >= 0) GLES20.glUniform1f(loc, value)
        }

        private fun setUniform2f(program: Int, name: String, x: Float, y: Float) {
            val loc = GLES20.glGetUniformLocation(program, name)
            if (loc >= 0) GLES20.glUniform2f(loc, x, y)
        }

        private fun setUniform4f(program: Int, name: String, x: Float, y: Float, z: Float, w: Float) {
            val loc = GLES20.glGetUniformLocation(program, name)
            if (loc >= 0) GLES20.glUniform4f(loc, x, y, z, w)
        }
    }
}
