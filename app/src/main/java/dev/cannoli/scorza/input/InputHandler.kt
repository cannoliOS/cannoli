package dev.cannoli.scorza.input

import android.view.KeyEvent
import dev.cannoli.scorza.settings.ButtonLayout

class InputHandler(
    private val getButtonLayout: () -> ButtonLayout,
    private val getSwapStartSelect: () -> Boolean = { false }
) {

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
    var onL2: () -> Unit = {}
    var onR2: () -> Unit = {}
    var onX: () -> Unit = {}
    var onY: () -> Unit = {}

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

            KeyEvent.KEYCODE_BUTTON_X -> { onX(); true }
            KeyEvent.KEYCODE_BUTTON_Y -> { onY(); true }

            KeyEvent.KEYCODE_BUTTON_SELECT -> {
                if (getSwapStartSelect()) onStart() else onSelect()
                true
            }

            KeyEvent.KEYCODE_BACK -> { onBack(); true }

            KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_MENU -> {
                if (getSwapStartSelect()) onSelect() else onStart()
                true
            }
            KeyEvent.KEYCODE_BUTTON_L1 -> { onL1(); true }
            KeyEvent.KEYCODE_BUTTON_R1 -> { onR1(); true }
            KeyEvent.KEYCODE_BUTTON_L2 -> { onL2(); true }
            KeyEvent.KEYCODE_BUTTON_R2 -> { onR2(); true }

            KeyEvent.KEYCODE_DEL,
            KeyEvent.KEYCODE_ESCAPE -> { onBack(); true }

            else -> false
        }
    }
}
