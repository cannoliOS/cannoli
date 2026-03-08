package dev.cannoli.scorza.libretro

import android.view.KeyEvent

object LibretroInput {

    const val RETRO_B      = 1 shl 0
    const val RETRO_Y      = 1 shl 1
    const val RETRO_SELECT = 1 shl 2
    const val RETRO_START  = 1 shl 3
    const val RETRO_UP     = 1 shl 4
    const val RETRO_DOWN   = 1 shl 5
    const val RETRO_LEFT   = 1 shl 6
    const val RETRO_RIGHT  = 1 shl 7
    const val RETRO_A      = 1 shl 8
    const val RETRO_X      = 1 shl 9
    const val RETRO_L      = 1 shl 10
    const val RETRO_R      = 1 shl 11

    fun keyCodeToRetroMask(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> RETRO_A
        KeyEvent.KEYCODE_BUTTON_B -> RETRO_B
        KeyEvent.KEYCODE_BUTTON_X -> RETRO_X
        KeyEvent.KEYCODE_BUTTON_Y -> RETRO_Y
        KeyEvent.KEYCODE_BUTTON_L1 -> RETRO_L
        KeyEvent.KEYCODE_BUTTON_R1 -> RETRO_R
        KeyEvent.KEYCODE_BUTTON_SELECT -> RETRO_SELECT
        KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> RETRO_START
        KeyEvent.KEYCODE_DPAD_UP -> RETRO_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> RETRO_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> RETRO_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> RETRO_RIGHT
        else -> null
    }
}
