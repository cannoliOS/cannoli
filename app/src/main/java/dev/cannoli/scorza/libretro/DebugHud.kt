package dev.cannoli.scorza.libretro

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

data class DebugHudState(
    val fps: Float = 0f,
    val frameTimeMs: Float = 0f,
    val coreWidth: Int = 0,
    val coreHeight: Int = 0,
    val viewportWidth: Int = 0,
    val viewportHeight: Int = 0,
    val pixelFormat: String = "",
    val coreName: String = "",
    val audioSampleRate: Int = 0,
    val backendName: String = ""
)

@Composable
fun DebugHud(
    renderer: GraphicsBackend,
    runner: LibretroRunner,
    coreName: String,
    audioSampleRate: Int
) {
    var state by remember { mutableStateOf(DebugHudState()) }

    LaunchedEffect(Unit) {
        while (true) {
            val pf = runner.getPixelFormat()
            state = DebugHudState(
                fps = renderer.fps,
                frameTimeMs = renderer.frameTimeMs,
                coreWidth = runner.getFrameWidth(),
                coreHeight = runner.getFrameHeight(),
                viewportWidth = renderer.viewportWidth,
                viewportHeight = renderer.viewportHeight,
                pixelFormat = if (pf == LibretroRunner.PIXEL_FORMAT_XRGB8888) "XRGB8888" else "RGB565",
                coreName = coreName,
                audioSampleRate = audioSampleRate,
                backendName = renderer.backendName
            )
            delay(500)
        }
    }

    val textStyle = androidx.compose.ui.text.TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        color = Color.White,
        lineHeight = 16.sp
    )

    Column(
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(8.dp)
    ) {
        Text("%.1f fps  %.1f ms".format(state.fps, state.frameTimeMs), style = textStyle)
        Text("${state.backendName}  ${state.coreWidth}x${state.coreHeight}  ${state.pixelFormat}", style = textStyle)
        Text("Viewport: ${state.viewportWidth}x${state.viewportHeight}", style = textStyle)
        Text("Audio: ${state.audioSampleRate} Hz", style = textStyle)
        Text(state.coreName, style = textStyle)
    }
}
