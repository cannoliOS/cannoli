package dev.cannoli.launcher.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.cannoli.launcher.R
import dev.cannoli.launcher.ui.screens.DialogState

private val menuPillH = 14.dp

@Composable
fun ContextMenuOverlay(state: DialogState.ContextMenu) {
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
            Text(
                text = state.gameName,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            state.options.forEachIndexed { index, option ->
                if (index == state.selectedOption) {
                    Box(
                        modifier = Modifier
                            .padding(vertical = 2.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.White)
                            .padding(horizontal = menuPillH, vertical = 8.dp)
                    ) {
                        Text(
                            text = option,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.Black
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .padding(vertical = 2.dp)
                            .padding(start = menuPillH, top = 8.dp, bottom = 8.dp)
                    ) {
                        Text(
                            text = option,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp)
        ) {
            BottomBar(
                leftItems = listOf("B" to stringResource(R.string.label_back)),
                rightItems = listOf("A" to stringResource(R.string.label_select))
            )
        }
    }
}

@Composable
fun DeleteConfirmOverlay(gameName: String) {
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
            Text(
                text = stringResource(R.string.dialog_delete_confirm, gameName),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp)
        ) {
            BottomBar(
                leftItems = listOf("B" to stringResource(R.string.label_cancel)),
                rightItems = listOf("A" to stringResource(R.string.label_delete))
            )
        }
    }
}

@Composable
fun CollectionPickerOverlay(state: DialogState.CollectionPicker) {
    val allOptions = state.collections + listOf("+ ${stringResource(R.string.dialog_new_collection)}")

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
            Text(
                text = stringResource(R.string.dialog_add_to_collection),
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            allOptions.forEachIndexed { index, option ->
                if (index == state.selectedIndex) {
                    Box(
                        modifier = Modifier
                            .padding(vertical = 2.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.White)
                            .padding(horizontal = menuPillH, vertical = 8.dp)
                    ) {
                        Text(
                            text = option,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.Black
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .padding(vertical = 2.dp)
                            .padding(start = menuPillH, top = 8.dp, bottom = 8.dp)
                    ) {
                        Text(
                            text = option,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp)
        ) {
            BottomBar(
                leftItems = listOf("B" to stringResource(R.string.label_back)),
                rightItems = listOf("A" to stringResource(R.string.label_add))
            )
        }
    }
}

@Composable
fun RenameOverlay(state: DialogState.RenameInput) {
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
            Text(
                text = stringResource(R.string.dialog_rename),
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            val displayName = buildString {
                state.currentName.forEachIndexed { i, c ->
                    if (i == state.cursorPos) append('|')
                    append(c)
                }
                if (state.cursorPos == state.currentName.length) append('|')
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.15f))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp)
        ) {
            BottomBar(
                leftItems = listOf("B" to stringResource(R.string.label_cancel)),
                rightItems = listOf("A" to stringResource(R.string.label_confirm))
            )
        }
    }
}

@Composable
fun RenameResultOverlay(state: DialogState.RenameResult) {
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
            Text(
                text = if (state.success) {
                    stringResource(R.string.dialog_rename_success)
                } else {
                    stringResource(R.string.dialog_rename_failed, state.message)
                },
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp)
        ) {
            LegendPill(button = "B", label = stringResource(R.string.label_close))
        }
    }
}
