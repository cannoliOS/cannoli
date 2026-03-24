package dev.cannoli.scorza.libretro

interface GraphicsBackend {
    var paused: Boolean
    var fastForwardFrames: Int
    var scalingMode: ScalingMode
    var coreAspectRatio: Float
    var debugHud: Boolean
    var sharpness: Sharpness
    var screenEffect: ScreenEffect
    var overlayPath: String?
    var shaderPresetPath: String?
    var onFrameRendered: (() -> Unit)?

    val backendName: String
    val fps: Float
    val frameTimeMs: Float
    val viewportWidth: Int
    val viewportHeight: Int

    fun setShaderParameter(id: String, value: Float)
    fun clearShaderParamOverrides()
}
