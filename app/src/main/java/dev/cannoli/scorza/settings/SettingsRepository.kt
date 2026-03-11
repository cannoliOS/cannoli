package dev.cannoli.scorza.settings

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.io.File

class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("cannoli_settings", Context.MODE_PRIVATE)

    private var json = JSONObject()
    private var settingsFile: File? = null

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
            try { json = JSONObject(file.readText()) } catch (_: Exception) {}
        }
    }

    private fun save() {
        settingsFile?.let { file ->
            file.parentFile?.mkdirs()
            file.writeText(json.toString(2))
        }
    }

    private fun migrateFromPrefs() {
        if (json.length() > 0) return
        val keys = prefs.all.keys - KEY_SD_ROOT
        if (keys.isEmpty()) return
        for (key in keys) {
            when (val v = prefs.all[key]) {
                is String -> json.put(key, v)
                is Boolean -> json.put(key, v)
                is Int -> json.put(key, v)
            }
        }
        save()
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
        get() = json.optString(KEY_RA_PACKAGE, DEFAULT_RA_PACKAGE)
        set(value) { json.put(KEY_RA_PACKAGE, value); save() }

    var buttonLayout: ButtonLayout
        get() = ButtonLayout.fromString(json.optString(KEY_BUTTON_LAYOUT, null))
        set(value) { json.put(KEY_BUTTON_LAYOUT, value.name); save() }

    var textSize: TextSize
        get() = TextSize.fromString(json.optString(KEY_TEXT_SIZE, null))
        set(value) { json.put(KEY_TEXT_SIZE, value.name); save() }

    var gameSortOrder: SortOrder
        get() = SortOrder.fromString(json.optString(KEY_SORT_ORDER, null))
        set(value) { json.put(KEY_SORT_ORDER, value.name); save() }

    var scrollSpeed: ScrollSpeed
        get() = ScrollSpeed.fromString(json.optString(KEY_SCROLL_SPEED, null))
        set(value) { json.put(KEY_SCROLL_SPEED, value.name); save() }

    var showCoreTag: Boolean
        get() = json.optBoolean(KEY_SHOW_CORE_TAG, false)
        set(value) { json.put(KEY_SHOW_CORE_TAG, value); save() }

    var timeFormat: TimeFormat
        get() = TimeFormat.fromString(json.optString(KEY_TIME_FORMAT, null))
        set(value) { json.put(KEY_TIME_FORMAT, value.name); save() }

    var backgroundImagePath: String?
        get() = json.optString(KEY_BG_IMAGE, "").ifEmpty { null }
        set(value) { if (value != null) json.put(KEY_BG_IMAGE, value) else json.remove(KEY_BG_IMAGE); save() }

    var swapStartSelect: Boolean
        get() = json.optBoolean(KEY_SWAP_START_SELECT, false)
        set(value) { json.put(KEY_SWAP_START_SELECT, value); save() }

    var platformSwitching: Boolean
        get() = json.optBoolean(KEY_PLATFORM_SWITCHING, false)
        set(value) { json.put(KEY_PLATFORM_SWITCHING, value); save() }

    var showWifi: Boolean
        get() = json.optBoolean(KEY_SHOW_WIFI, true)
        set(value) { json.put(KEY_SHOW_WIFI, value); save() }

    var showBluetooth: Boolean
        get() = json.optBoolean(KEY_SHOW_BLUETOOTH, true)
        set(value) { json.put(KEY_SHOW_BLUETOOTH, value); save() }

    var showClock: Boolean
        get() = json.optBoolean(KEY_SHOW_CLOCK, true)
        set(value) { json.put(KEY_SHOW_CLOCK, value); save() }

    var showBattery: Boolean
        get() = json.optBoolean(KEY_SHOW_BATTERY, true)
        set(value) { json.put(KEY_SHOW_BATTERY, value); save() }

    var showEmpty: Boolean
        get() = json.optBoolean(KEY_SHOW_EMPTY, false)
        set(value) { json.put(KEY_SHOW_EMPTY, value); save() }

    var showTools: Boolean
        get() = json.optBoolean(KEY_SHOW_TOOLS, false)
        set(value) { json.put(KEY_SHOW_TOOLS, value); save() }

    var showPorts: Boolean
        get() = json.optBoolean(KEY_SHOW_PORTS, false)
        set(value) { json.put(KEY_SHOW_PORTS, value); save() }

    var toolsName: String
        get() = json.optString(KEY_TOOLS_NAME, "Tools").ifEmpty { "Tools" }
        set(value) { if (value == "Tools") json.remove(KEY_TOOLS_NAME) else json.put(KEY_TOOLS_NAME, value); save() }

    var portsName: String
        get() = json.optString(KEY_PORTS_NAME, "Ports").ifEmpty { "Ports" }
        set(value) { if (value == "Ports") json.remove(KEY_PORTS_NAME) else json.put(KEY_PORTS_NAME, value); save() }

    var backgroundTint: Int
        get() = json.optInt(KEY_BG_TINT, 0)
        set(value) { json.put(KEY_BG_TINT, value.coerceIn(0, 90)); save() }

    var colorHighlight: String
        get() = json.optString(KEY_COLOR_HIGHLIGHT, "#FFFFFF")
        set(value) { json.put(KEY_COLOR_HIGHLIGHT, value); save() }

    var colorText: String
        get() = json.optString(KEY_COLOR_TEXT, "#FFFFFF")
        set(value) { json.put(KEY_COLOR_TEXT, value); save() }

    var colorHighlightText: String
        get() = json.optString(KEY_COLOR_HIGHLIGHT_TEXT, "#000000")
        set(value) { json.put(KEY_COLOR_HIGHLIGHT_TEXT, value); save() }

    var colorAccent: String
        get() = json.optString(KEY_COLOR_ACCENT, "#FFFFFF")
        set(value) { json.put(KEY_COLOR_ACCENT, value); save() }

    companion object {
        const val DEFAULT_ROOT = "/storage/emulated/0/Cannoli/"
        const val DEFAULT_RA_PACKAGE = "com.retroarch.aarch64"

        private const val KEY_SETUP_COMPLETED = "setup_completed"
        private const val KEY_SD_ROOT = "sd_root"
        private const val KEY_RA_PACKAGE = "ra_package"
        private const val KEY_BUTTON_LAYOUT = "button_layout"
        private const val KEY_TEXT_SIZE = "text_size"
        private const val KEY_SORT_ORDER = "sort_order"
        private const val KEY_SCROLL_SPEED = "scroll_speed"
        private const val KEY_SHOW_CORE_TAG = "show_core_tag"
        private const val KEY_TIME_FORMAT = "time_format"
        private const val KEY_BG_IMAGE = "bg_image"
        private const val KEY_SWAP_START_SELECT = "swap_start_select"
        private const val KEY_BG_TINT = "bg_tint"
        private const val KEY_COLOR_HIGHLIGHT = "color_highlight"
        private const val KEY_COLOR_TEXT = "color_text"
        private const val KEY_COLOR_HIGHLIGHT_TEXT = "color_highlight_text"
        private const val KEY_COLOR_ACCENT = "color_accent"
        private const val KEY_PLATFORM_SWITCHING = "platform_switching"
        private const val KEY_SHOW_EMPTY = "show_empty"
        private const val KEY_SHOW_WIFI = "show_wifi"
        private const val KEY_SHOW_BLUETOOTH = "show_bluetooth"
        private const val KEY_SHOW_CLOCK = "show_clock"
        private const val KEY_SHOW_BATTERY = "show_battery"
        private const val KEY_SHOW_TOOLS = "show_tools"
        private const val KEY_SHOW_PORTS = "show_ports"
        private const val KEY_TOOLS_NAME = "tools_name"
        private const val KEY_PORTS_NAME = "ports_name"
    }
}

enum class ButtonLayout {
    XBOX, NINTENDO;
    companion object {
        fun fromString(value: String?): ButtonLayout =
            entries.firstOrNull { it.name == value } ?: XBOX
    }
}

enum class TextSize {
    COMPACT, DEFAULT;
    companion object {
        fun fromString(value: String?): TextSize =
            entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}

enum class SortOrder {
    NATURAL, DATE_ADDED;
    companion object {
        fun fromString(value: String?): SortOrder =
            entries.firstOrNull { it.name == value } ?: NATURAL
    }
}

enum class ScrollSpeed {
    SLOW, NORMAL, FAST;
    companion object {
        fun fromString(value: String?): ScrollSpeed =
            entries.firstOrNull { it.name == value } ?: NORMAL
    }
}

enum class TimeFormat {
    TWELVE_HOUR, TWENTY_FOUR_HOUR;
    companion object {
        fun fromString(value: String?): TimeFormat =
            entries.firstOrNull { it.name == value } ?: TWELVE_HOUR
    }
}
