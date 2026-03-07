package dev.cannoli.launcher.input

import android.view.KeyEvent
import dev.cannoli.launcher.settings.ButtonLayout

class InputHandler(private val getButtonLayout: () -> ButtonLayout) {

    var onUp: () -> Unit = {}
    var onDown: () -> Unit = {}
    var onLeft: () -> Unit = {}
    var onRight: () -> Unit = {}
    var onConfirm: () -> Unit = {}
    var onBack: () -> Unit = {}
    var onSelect: () -> Unit = {}
    var onStart: () -> Unit = {}
    var onL1: () -> Unit = {}
    var onR1: () -> Unit = {}

    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_MULTIPLE) return false

        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { onUp(); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { onDown(); true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { onLeft(); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { onRight(); true }

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> { onConfirm(); true }

            KeyEvent.KEYCODE_BUTTON_A -> {
                if (getButtonLayout() == ButtonLayout.XBOX) onConfirm() else onBack()
                true
            }
            KeyEvent.KEYCODE_BUTTON_B -> {
                if (getButtonLayout() == ButtonLayout.XBOX) onBack() else onConfirm()
                true
            }

            KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_BACK -> {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    onBack()
                } else {
                    onSelect()
                }
                true
            }

            KeyEvent.KEYCODE_BUTTON_START -> { onStart(); true }
            KeyEvent.KEYCODE_BUTTON_L1 -> { onL1(); true }
            KeyEvent.KEYCODE_BUTTON_R1 -> { onR1(); true }

            KeyEvent.KEYCODE_DEL,
            KeyEvent.KEYCODE_ESCAPE -> { onBack(); true }

            else -> false
        }
    }
}
