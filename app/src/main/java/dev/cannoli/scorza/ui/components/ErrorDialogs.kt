package dev.cannoli.scorza.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.cannoli.scorza.R

@Composable
fun MissingCoreDialog(coreName: String) {
    MessageOverlay(message = stringResource(R.string.dialog_missing_core, coreName))
}

@Composable
fun MissingAppDialog(packageName: String) {
    MessageOverlay(message = stringResource(R.string.dialog_missing_app, packageName))
}
