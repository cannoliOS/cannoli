package dev.cannoli.scorza.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.cannoli.scorza.R
import dev.cannoli.scorza.ui.theme.GrayText

@Composable
fun MissingCoreDialog(coreName: String) {
    OverlayScrim(
        bottomBar = { LegendPill(button = "B", label = stringResource(R.string.label_close)) }
    ) {
        Text(
            text = stringResource(R.string.dialog_title_missing_core),
            style = MaterialTheme.typography.titleLarge,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.dialog_missing_core, coreName),
            style = MaterialTheme.typography.bodyLarge,
            color = GrayText
        )
    }
}

@Composable
fun MissingAppDialog(appName: String) {
    OverlayScrim(
        bottomBar = { LegendPill(button = "B", label = stringResource(R.string.label_close)) }
    ) {
        Text(
            text = stringResource(R.string.dialog_title_missing_app),
            style = MaterialTheme.typography.titleLarge,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.dialog_missing_app, appName),
            style = MaterialTheme.typography.bodyLarge,
            color = GrayText
        )
    }
}

@Composable
fun LaunchErrorDialog(message: String) {
    OverlayScrim(
        bottomBar = { LegendPill(button = "B", label = stringResource(R.string.label_close)) }
    ) {
        Text(
            text = stringResource(R.string.dialog_title_launch_error),
            style = MaterialTheme.typography.titleLarge,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = GrayText
        )
    }
}
