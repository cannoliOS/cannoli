package dev.cannoli.scorza.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.R

const val KEY_SHIFT = "⇧"
const val KEY_ENTER = "↵"
const val KEY_BACKSPACE = "←"
const val KEY_SYMBOLS = "⌨"
const val KEY_SPACE = " "

val KEYBOARD_ALPHA = listOf(
    listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", KEY_BACKSPACE),
    listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
    listOf("a", "s", "d", "f", "g", "h", "j", "k", "l", KEY_ENTER),
    listOf(KEY_SHIFT, "z", "x", "c", "v", "b", "n", "m", KEY_SYMBOLS),
    listOf(KEY_SPACE)
)

val KEYBOARD_ALPHA_SHIFTED = listOf(
    listOf("!", "@", "#", "$", "%", "^", "&", "*", "(", ")", KEY_BACKSPACE),
    listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"),
    listOf("A", "S", "D", "F", "G", "H", "J", "K", "L", KEY_ENTER),
    listOf(KEY_SHIFT, "Z", "X", "C", "V", "B", "N", "M", KEY_SYMBOLS),
    listOf(KEY_SPACE)
)

val KEYBOARD_SYMBOLS = listOf(
    listOf("~", "`", "|", "\\", "<", ">", "{", "}", "[", "]", KEY_BACKSPACE),
    listOf("!", "@", "#", "$", "%", "^", "&", "*", "(", ")"),
    listOf("-", "_", "=", "+", ";", ":", "'", "\"", "?", KEY_ENTER),
    listOf(KEY_SHIFT, ",", ".", "/", "\\", "|", "~", "`", KEY_SYMBOLS),
    listOf(KEY_SPACE)
)

fun getKeyboardRows(caps: Boolean, symbols: Boolean): List<List<String>> = when {
    symbols -> KEYBOARD_SYMBOLS
    caps -> KEYBOARD_ALPHA_SHIFTED
    else -> KEYBOARD_ALPHA
}

private val HIGHLIGHT_COLOR = Color(0xFF6366F1)
private val KEY_BG = Color.White.copy(alpha = 0.12f)

@Composable
fun KeyboardOverlay(
    text: String,
    cursorPos: Int,
    keyRow: Int,
    keyCol: Int,
    caps: Boolean,
    symbols: Boolean = false
) {
    val rows = getKeyboardRows(caps, symbols)
    val row = keyRow.coerceIn(0, rows.lastIndex)
    val col = keyCol.coerceIn(0, rows[row].lastIndex)

    val scrollState = rememberScrollState()
    var cursorVisible by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(530)
            cursorVisible = !cursorVisible
        }
    }

    LaunchedEffect(cursorPos, text) {
        cursorVisible = true
    }

    val beforeCursor = text.substring(0, cursorPos)
    val afterCursor = text.substring(cursorPos)
    val cursorChar = if (cursorVisible) "|" else " "
    val displayText = "$beforeCursor$cursorChar$afterCursor"

    LaunchedEffect(cursorPos, text) {
        val target = if (text.isEmpty()) 0
        else (scrollState.maxValue * cursorPos / (text.length + 1).coerceAtLeast(1))
        scrollState.scrollTo(target.coerceAtLeast(0))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White.copy(alpha = 0.12f))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .clipToBounds()
                    .horizontalScroll(scrollState)
            ) {
                Text(
                    text = displayText,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp),
                    color = Color.White,
                    maxLines = 1,
                    softWrap = false
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            rows.forEachIndexed { ri, keys ->
                if (keys.size == 1 && keys[0] == KEY_SPACE) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val isSelected = ri == row
                        Box(
                            modifier = Modifier
                                .padding(2.dp)
                                .height(40.dp)
                                .fillMaxWidth(0.7f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) HIGHLIGHT_COLOR else KEY_BG),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(60.dp)
                                    .height(2.dp)
                                    .background(Color.White.copy(alpha = 0.5f))
                            )
                        }
                    }
                } else {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        keys.forEachIndexed { ci, key ->
                            val isSelected = ri == row && ci == col
                            val isAction = key in listOf(KEY_SHIFT, KEY_ENTER, KEY_BACKSPACE, KEY_SYMBOLS)
                            val isShiftActive = key == KEY_SHIFT && caps

                            Box(
                                modifier = Modifier
                                    .padding(2.dp)
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        when {
                                            isSelected -> HIGHLIGHT_COLOR
                                            isShiftActive -> HIGHLIGHT_COLOR.copy(alpha = 0.5f)
                                            else -> KEY_BG
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = key,
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontSize = if (isAction) 18.sp else 16.sp
                                    ),
                                    color = Color.White,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
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
                leftItems = listOf(
                    "Y" to stringResource(R.string.label_cancel)
                ),
                rightItems = listOf(
                    "\uDB81\uDC0A" to stringResource(R.string.label_confirm)
                )
            )
        }
    }
}

fun handleKeyboardConfirm(
    caps: Boolean, symbols: Boolean, keyRow: Int, keyCol: Int,
    currentName: String, cursorPos: Int,
    onChar: (String, Int) -> Unit,
    onShift: () -> Unit,
    onSymbols: () -> Unit,
    onEnter: () -> Unit
) {
    val rows = getKeyboardRows(caps, symbols)
    val row = rows.getOrNull(keyRow) ?: return
    val key = row.getOrNull(keyCol) ?: return

    when (key) {
        KEY_SHIFT -> onShift()
        KEY_SYMBOLS -> onSymbols()
        KEY_ENTER -> onEnter()
        KEY_BACKSPACE -> {
            if (cursorPos > 0) {
                val newName = currentName.removeRange(cursorPos - 1, cursorPos)
                onChar(newName, cursorPos - 1)
            }
        }
        KEY_SPACE -> {
            val newName = currentName.substring(0, cursorPos) + " " + currentName.substring(cursorPos)
            onChar(newName, cursorPos + 1)
        }
        else -> {
            val newName = currentName.substring(0, cursorPos) + key + currentName.substring(cursorPos)
            onChar(newName, cursorPos + 1)
        }
    }
}
