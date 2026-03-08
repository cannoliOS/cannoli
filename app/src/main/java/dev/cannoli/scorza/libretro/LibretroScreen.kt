package dev.cannoli.scorza.libretro

import android.opengl.GLSurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun LibretroScreen(
    glSurfaceView: GLSurfaceView,
    gameTitle: String,
    menuVisible: Boolean,
    menuSelectedIndex: Int,
    onMenuAction: (Int) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { glSurfaceView },
            modifier = Modifier.fillMaxSize()
        )

        if (menuVisible) {
            InGameMenu(
                gameTitle = gameTitle,
                selectedIndex = menuSelectedIndex,
                onAction = onMenuAction
            )
        }
    }
}
