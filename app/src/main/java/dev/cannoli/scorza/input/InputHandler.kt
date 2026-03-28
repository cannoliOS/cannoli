package dev.cannoli.scorza.input

import android.view.KeyEvent

class InputHandler(
    private val getButtonMappings: () -> Map<String, Int> = { emptyMap() }
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
    var onL3: () -> Unit = {}
    var onR3: () -> Unit = {}
    var onX: () -> Unit = {}
    var onY: () -> Unit = {}
    var onMenu: () -> Unit = {}

    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_MULTIPLE) return false

        val button = resolveButton(event.keyCode)
        if (button != null) return dispatchButton(button)

        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> { onConfirm(); true }
            KeyEvent.KEYCODE_BACK -> true
            KeyEvent.KEYCODE_DEL,
            KeyEvent.KEYCODE_ESCAPE -> { onBack(); true }
            else -> false
        }
    }

    private fun resolveButton(keyCode: Int): String? {
        val mappings = getButtonMappings()
        if (mappings.isNotEmpty()) {
            for ((prefKey, mapped) in mappings) {
                if (mapped == keyCode) return prefKey
            }
        }
        return DEFAULT_KEY_MAP[keyCode]
    }

    private fun dispatchButton(button: String): Boolean {
        when (button) {
            "btn_up" -> onUp()
            "btn_down" -> onDown()
            "btn_left" -> onLeft()
            "btn_right" -> onRight()
            "btn_a" -> onConfirm()
            "btn_b" -> onBack()
            "btn_x" -> onX()
            "btn_y" -> onY()
            "btn_select" -> onSelect()
            "btn_start" -> onStart()
            "btn_l" -> onL1()
            "btn_r" -> onR1()
            "btn_l2" -> onL2()
            "btn_r2" -> onR2()
            "btn_l3" -> onL3()
            "btn_r3" -> onR3()
            "btn_menu" -> onMenu()
            else -> return false
        }
        return true
    }

    companion object {
        private val DEFAULT_KEY_MAP = mapOf(
            KeyEvent.KEYCODE_BUTTON_A to "btn_a",
            KeyEvent.KEYCODE_BUTTON_B to "btn_b",
            KeyEvent.KEYCODE_BUTTON_X to "btn_x",
            KeyEvent.KEYCODE_BUTTON_Y to "btn_y",
            KeyEvent.KEYCODE_BUTTON_L1 to "btn_l",
            KeyEvent.KEYCODE_BUTTON_R1 to "btn_r",
            KeyEvent.KEYCODE_BUTTON_L2 to "btn_l2",
            KeyEvent.KEYCODE_BUTTON_R2 to "btn_r2",
            KeyEvent.KEYCODE_BUTTON_THUMBL to "btn_l3",
            KeyEvent.KEYCODE_BUTTON_THUMBR to "btn_r3",
            KeyEvent.KEYCODE_BUTTON_START to "btn_start",
            KeyEvent.KEYCODE_BUTTON_SELECT to "btn_select",
            KeyEvent.KEYCODE_BUTTON_MODE to "btn_menu",
            KeyEvent.KEYCODE_DPAD_UP to "btn_up",
            KeyEvent.KEYCODE_DPAD_DOWN to "btn_down",
            KeyEvent.KEYCODE_DPAD_LEFT to "btn_left",
            KeyEvent.KEYCODE_DPAD_RIGHT to "btn_right",
        )
    }
}
