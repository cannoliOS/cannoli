package dev.cannoli.scorza.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.HandlerThread
import org.json.JSONObject
import java.io.File

class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("cannoli_settings", Context.MODE_PRIVATE)

    private var json = JSONObject()
    private val jsonLock = Any()
    private var settingsFile: File? = null

    private val saveThread = HandlerThread("settings-save").apply { start() }
    private val saveHandler = Handler(saveThread.looper)
    private val saveRunnable = Runnable { saveToDisk() }

    private inline fun <T> jsonRead(block: JSONObject.() -> T): T = synchronized(jsonLock) { json.block() }
    private inline fun jsonWrite(block: JSONObject.() -> Unit) { synchronized(jsonLock) { json.block() }; scheduleSave() }

    init {
        if (prefs.getString(KEY_RA_PACKAGE, null) == "com.retroarch") {
            prefs.edit().putString(KEY_RA_PACKAGE, DEFAULT_RA_PACKAGE).apply()
        }
        loadFromDisk()
        migrateFromPrefs()
    }

    private fun loadFromDisk() {
        val file = File(sdCardRoot, "Config/settings.json")
        settingsFile = file
        if (file.exists()) {
            try { synchronized(jsonLock) { json = JSONObject(file.readText()) } } catch (_: java.io.IOException) {} catch (_: org.json.JSONException) {}
        }
    }

    private fun scheduleSave() {
        saveHandler.removeCallbacks(saveRunnable)
        saveHandler.postDelayed(saveRunnable, 100)
    }

    private fun saveToDisk() {
        settingsFile?.let { file ->
            file.parentFile?.mkdirs()
            synchronized(jsonLock) { file.writeText(json.toString(2)) }
        }
    }

    fun flush() {
        saveHandler.removeCallbacks(saveRunnable)
        saveToDisk()
    }

    fun shutdown() {
        flush()
        saveThread.quitSafely()
    }

    private fun migrateFromPrefs() {
        synchronized(jsonLock) { if (json.length() > 0) return }
        val keys = prefs.all.keys - KEY_SD_ROOT
        if (keys.isEmpty()) return
        synchronized(jsonLock) {
            for (key in keys) {
                when (val v = prefs.all[key]) {
                    is String -> json.put(key, v)
                    is Boolean -> json.put(key, v)
                    is Int -> json.put(key, v)
                }
            }
        }
        saveToDisk()
        val editor = prefs.edit()
        for (key in keys) editor.remove(key)
        editor.apply()
    }

    var setupCompleted: Boolean
        get() = prefs.getBoolean(KEY_SETUP_COMPLETED, false)
        set(value) { prefs.edit().putBoolean(KEY_SETUP_COMPLETED, value).apply() }

    var sdCardRoot: String
        get() = prefs.getString(KEY_SD_ROOT, DEFAULT_ROOT) ?: DEFAULT_ROOT
        set(value) {
            prefs.edit().putString(KEY_SD_ROOT, value).apply()
            settingsFile = File(value, "Config/settings.json")
            loadFromDisk()
        }

    var retroArchPackage: String
        get() = jsonRead { optString(KEY_RA_PACKAGE, DEFAULT_RA_PACKAGE) }
        set(value) = jsonWrite { put(KEY_RA_PACKAGE, value) }

    var textSize: TextSize
        get() = TextSize.fromString(jsonRead { optString(KEY_TEXT_SIZE, null) })
        set(value) = jsonWrite { put(KEY_TEXT_SIZE, value.name) }

    var timeFormat: TimeFormat
        get() = TimeFormat.fromString(jsonRead { optString(KEY_TIME_FORMAT, null) })
        set(value) = jsonWrite { put(KEY_TIME_FORMAT, value.name) }

    var backgroundImagePath: String?
        get() = jsonRead { optString(KEY_BG_IMAGE, "").ifEmpty { null } }
        set(value) = jsonWrite { if (value != null) put(KEY_BG_IMAGE, value) else remove(KEY_BG_IMAGE) }

    var graphicsBackend: String
        get() = jsonRead { optString(KEY_GRAPHICS_BACKEND, "GLES") }
        set(value) = jsonWrite { put(KEY_GRAPHICS_BACKEND, value) }

    var platformSwitching: Boolean
        get() = jsonRead { optBoolean(KEY_PLATFORM_SWITCHING, false) }
        set(value) = jsonWrite { put(KEY_PLATFORM_SWITCHING, value) }

    var showWifi: Boolean
        get() = jsonRead { optBoolean(KEY_SHOW_WIFI, true) }
        set(value) = jsonWrite { put(KEY_SHOW_WIFI, value) }

    var showBluetooth: Boolean
        get() = jsonRead { optBoolean(KEY_SHOW_BLUETOOTH, true) }
        set(value) = jsonWrite { put(KEY_SHOW_BLUETOOTH, value) }

    var showVpn: Boolean
        get() = jsonRead { optBoolean(KEY_SHOW_VPN, false) }
        set(value) = jsonWrite { put(KEY_SHOW_VPN, value) }

    var showClock: Boolean
        get() = jsonRead { optBoolean(KEY_SHOW_CLOCK, true) }
        set(value) = jsonWrite { put(KEY_SHOW_CLOCK, value) }

    var showBattery: Boolean
        get() = jsonRead { optBoolean(KEY_SHOW_BATTERY, true) }
        set(value) = jsonWrite { put(KEY_SHOW_BATTERY, value) }

    var showEmpty: Boolean
        get() = jsonRead { optBoolean(KEY_SHOW_EMPTY, false) }
        set(value) = jsonWrite { put(KEY_SHOW_EMPTY, value) }

    var showTools: Boolean
        get() = jsonRead { optBoolean(KEY_SHOW_TOOLS, false) }
        set(value) = jsonWrite { put(KEY_SHOW_TOOLS, value) }

    var showPorts: Boolean
        get() = jsonRead { optBoolean(KEY_SHOW_PORTS, false) }
        set(value) = jsonWrite { put(KEY_SHOW_PORTS, value) }

    var toolsName: String
        get() = jsonRead { optString(KEY_TOOLS_NAME, "Tools").ifEmpty { "Tools" } }
        set(value) = jsonWrite { if (value == "Tools") remove(KEY_TOOLS_NAME) else put(KEY_TOOLS_NAME, value) }

    var portsName: String
        get() = jsonRead { optString(KEY_PORTS_NAME, "Ports").ifEmpty { "Ports" } }
        set(value) = jsonWrite { if (value == "Ports") remove(KEY_PORTS_NAME) else put(KEY_PORTS_NAME, value) }

    var backgroundTint: Int
        get() = jsonRead { optInt(KEY_BG_TINT, 0) }
        set(value) = jsonWrite { put(KEY_BG_TINT, value.coerceIn(0, 90)) }

    var colorHighlight: String
        get() = jsonRead { optString(KEY_COLOR_HIGHLIGHT, "#FFFFFF") }
        set(value) = jsonWrite { put(KEY_COLOR_HIGHLIGHT, value) }

    var colorText: String
        get() = jsonRead { optString(KEY_COLOR_TEXT, "#FFFFFF") }
        set(value) = jsonWrite { put(KEY_COLOR_TEXT, value) }

    var colorHighlightText: String
        get() = jsonRead { optString(KEY_COLOR_HIGHLIGHT_TEXT, "#000000") }
        set(value) = jsonWrite { put(KEY_COLOR_HIGHLIGHT_TEXT, value) }

    var colorAccent: String
        get() = jsonRead { optString(KEY_COLOR_ACCENT, "#FFFFFF") }
        set(value) = jsonWrite { put(KEY_COLOR_ACCENT, value) }

    var raUsername: String
        get() = jsonRead { optString(KEY_RA_USERNAME, "") }
        set(value) = jsonWrite { if (value.isEmpty()) remove(KEY_RA_USERNAME) else put(KEY_RA_USERNAME, value) }

    var raToken: String
        get() = jsonRead { optString(KEY_RA_TOKEN, "") }
        set(value) = jsonWrite { if (value.isEmpty()) remove(KEY_RA_TOKEN) else put(KEY_RA_TOKEN, value) }

    companion object {
        const val DEFAULT_ROOT = "/storage/emulated/0/Cannoli/"
        const val DEFAULT_RA_PACKAGE = "com.retroarch.aarch64"
        val KNOWN_RA_PACKAGES = listOf(
            "com.retroarch.aarch64",
            "com.retroarch",
            "com.retroarch.ra32"
        )

        private const val KEY_SETUP_COMPLETED = "setup_completed"
        private const val KEY_SD_ROOT = "sd_root"
        private const val KEY_RA_PACKAGE = "ra_package"
        private const val KEY_TEXT_SIZE = "text_size"
        private const val KEY_TIME_FORMAT = "time_format"
        private const val KEY_BG_IMAGE = "bg_image"
        private const val KEY_BG_TINT = "bg_tint"
        private const val KEY_COLOR_HIGHLIGHT = "color_highlight"
        private const val KEY_COLOR_TEXT = "color_text"
        private const val KEY_COLOR_HIGHLIGHT_TEXT = "color_highlight_text"
        private const val KEY_COLOR_ACCENT = "color_accent"
        private const val KEY_PLATFORM_SWITCHING = "platform_switching"
        private const val KEY_SHOW_EMPTY = "show_empty"
        private const val KEY_SHOW_WIFI = "show_wifi"
        private const val KEY_SHOW_BLUETOOTH = "show_bluetooth"
        private const val KEY_SHOW_VPN = "show_vpn"
        private const val KEY_SHOW_CLOCK = "show_clock"
        private const val KEY_SHOW_BATTERY = "show_battery"
        private const val KEY_SHOW_TOOLS = "show_tools"
        private const val KEY_SHOW_PORTS = "show_ports"
        private const val KEY_TOOLS_NAME = "tools_name"
        private const val KEY_PORTS_NAME = "ports_name"
        private const val KEY_GRAPHICS_BACKEND = "graphics_backend"
        private const val KEY_RA_USERNAME = "ra_username"
        private const val KEY_RA_TOKEN = "ra_token"
    }
}

enum class TextSize {
    COMPACT, DEFAULT;
    companion object {
        fun fromString(value: String?): TextSize =
            entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}

enum class TimeFormat {
    TWELVE_HOUR, TWENTY_FOUR_HOUR;
    companion object {
        fun fromString(value: String?): TimeFormat =
            entries.firstOrNull { it.name == value } ?: TWELVE_HOUR
    }
}
