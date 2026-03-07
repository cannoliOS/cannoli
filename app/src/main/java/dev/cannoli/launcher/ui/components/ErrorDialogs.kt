package dev.cannoli.launcher.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.cannoli.launcher.ui.theme.GrayText

@Composable
fun MissingCoreDialog(coreName: String) {
    DialogOverlay {
        Text(
            text = "Core not installed: $coreName",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White
        )
        Text(
            text = "Install it via RetroArch > Online Updater.",
            style = MaterialTheme.typography.bodyMedium,
            color = GrayText,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
fun MissingAppDialog(packageName: String) {
    DialogOverlay {
        Text(
            text = "App not installed: $packageName",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White
        )
    }
}

@Composable
private fun DialogOverlay(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(24.dp)
        ) {
            content()
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp)
        ) {
            LegendPill(button = "B", label = "CLOSE")
        }
    }
}
