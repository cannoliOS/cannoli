package dev.cannoli.scorza.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class CannoliAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
    }

    companion object {
        var onHomeKey: (() -> Unit)? = null
        var onMenuKey: ((Int) -> Unit)? = null
        private const val PKG = "dev.cannoli.scorza"
        private const val GAME_ACTIVITY = "dev.cannoli.scorza.libretro.LibretroActivity"
    }

    private var homeDownTime = 0L
    private var inCannoli = false
    private var inGame = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            val cls = event.className?.toString() ?: ""
            inCannoli = pkg == PKG
            inGame = inCannoli && cls == GAME_ACTIVITY
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_HOME -> {
                if (!inCannoli) return false
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (homeDownTime == 0L) homeDownTime = event.eventTime
                    onHomeKey?.invoke()
                }
                if (event.action == KeyEvent.ACTION_UP) {
                    val held = event.eventTime - homeDownTime
                    homeDownTime = 0L
                    if (!inGame && held >= 1000) return false
                }
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                if (!inCannoli) return false
                onMenuKey?.invoke(event.action) ?: return false
                return true
            }
        }
        return false
    }

    override fun onInterrupt() {}
}
