package dev.cannoli.scorza.settings

import android.content.Context
import android.content.SharedPreferences

class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("cannoli_settings", Context.MODE_PRIVATE)

    init {
        if (prefs.getString(KEY_RA_PACKAGE, null) == "com.retroarch") {
            prefs.edit().putString(KEY_RA_PACKAGE, DEFAULT_RA_PACKAGE).apply()
        }
    }

    var sdCardRoot: String
        get() = prefs.getString(KEY_SD_ROOT, DEFAULT_ROOT) ?: DEFAULT_ROOT
        set(value) = prefs.edit().putString(KEY_SD_ROOT, value).apply()

    var retroArchPackage: String
        get() = prefs.getString(KEY_RA_PACKAGE, DEFAULT_RA_PACKAGE) ?: DEFAULT_RA_PACKAGE
        set(value) = prefs.edit().putString(KEY_RA_PACKAGE, value).apply()

    var buttonLayout: ButtonLayout
        get() = ButtonLayout.fromString(prefs.getString(KEY_BUTTON_LAYOUT, null))
        set(value) = prefs.edit().putString(KEY_BUTTON_LAYOUT, value.name).apply()

    var boxArtEnabled: Boolean
        get() = prefs.getBoolean(KEY_BOX_ART, true)
        set(value) = prefs.edit().putBoolean(KEY_BOX_ART, value).apply()

    var textSize: TextSize
        get() = TextSize.fromString(prefs.getString(KEY_TEXT_SIZE, null))
        set(value) = prefs.edit().putString(KEY_TEXT_SIZE, value.name).apply()

    var gameSortOrder: SortOrder
        get() = SortOrder.fromString(prefs.getString(KEY_SORT_ORDER, null))
        set(value) = prefs.edit().putString(KEY_SORT_ORDER, value.name).apply()

    var scrollSpeed: ScrollSpeed
        get() = ScrollSpeed.fromString(prefs.getString(KEY_SCROLL_SPEED, null))
        set(value) = prefs.edit().putString(KEY_SCROLL_SPEED, value.name).apply()

    var showCoreTag: Boolean
        get() = prefs.getBoolean(KEY_SHOW_CORE_TAG, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_CORE_TAG, value).apply()

    var timeFormat: TimeFormat
        get() = TimeFormat.fromString(prefs.getString(KEY_TIME_FORMAT, null))
        set(value) = prefs.edit().putString(KEY_TIME_FORMAT, value.name).apply()

    var batteryPercentage: Boolean
        get() = prefs.getBoolean(KEY_BATTERY_PCT, false)
        set(value) = prefs.edit().putBoolean(KEY_BATTERY_PCT, value).apply()

    var backgroundImagePath: String?
        get() = prefs.getString(KEY_BG_IMAGE, null)
        set(value) = prefs.edit().putString(KEY_BG_IMAGE, value).apply()

    var swapStartSelect: Boolean
        get() = prefs.getBoolean(KEY_SWAP_START_SELECT, false)
        set(value) = prefs.edit().putBoolean(KEY_SWAP_START_SELECT, value).apply()

    var platformSwitching: Boolean
        get() = prefs.getBoolean(KEY_PLATFORM_SWITCHING, false)
        set(value) = prefs.edit().putBoolean(KEY_PLATFORM_SWITCHING, value).apply()

    var showTools: Boolean
        get() = prefs.getBoolean(KEY_SHOW_TOOLS, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_TOOLS, value).apply()

    var showPorts: Boolean
        get() = prefs.getBoolean(KEY_SHOW_PORTS, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_PORTS, value).apply()

    /** Background tint opacity 0–90 in steps of 10. 0 = off. */
    var backgroundTint: Int
        get() = prefs.getInt(KEY_BG_TINT, 0)
        set(value) = prefs.edit().putInt(KEY_BG_TINT, value.coerceIn(0, 90)).apply()

    var colorHighlight: String
        get() = prefs.getString(KEY_COLOR_HIGHLIGHT, "#FFFFFF") ?: "#FFFFFF"
        set(value) = prefs.edit().putString(KEY_COLOR_HIGHLIGHT, value).apply()

    var colorText: String
        get() = prefs.getString(KEY_COLOR_TEXT, "#FFFFFF") ?: "#FFFFFF"
        set(value) = prefs.edit().putString(KEY_COLOR_TEXT, value).apply()

    var colorHighlightText: String
        get() = prefs.getString(KEY_COLOR_HIGHLIGHT_TEXT, "#000000") ?: "#000000"
        set(value) = prefs.edit().putString(KEY_COLOR_HIGHLIGHT_TEXT, value).apply()

    var colorAccent: String
        get() = prefs.getString(KEY_COLOR_ACCENT, "#FFFFFF") ?: "#FFFFFF"
        set(value) = prefs.edit().putString(KEY_COLOR_ACCENT, value).apply()

    companion object {
        const val DEFAULT_ROOT = "/storage/emulated/0/Cannoli/"
        const val DEFAULT_RA_PACKAGE = "com.retroarch.aarch64"

        private const val KEY_SD_ROOT = "sd_root"
        private const val KEY_RA_PACKAGE = "ra_package"
        private const val KEY_BUTTON_LAYOUT = "button_layout"
        private const val KEY_BOX_ART = "box_art"
        private const val KEY_TEXT_SIZE = "text_size"
        private const val KEY_SORT_ORDER = "sort_order"
        private const val KEY_SCROLL_SPEED = "scroll_speed"
        private const val KEY_SHOW_CORE_TAG = "show_core_tag"
        private const val KEY_TIME_FORMAT = "time_format"
        private const val KEY_BATTERY_PCT = "battery_percentage"
        private const val KEY_BG_IMAGE = "bg_image"
        private const val KEY_SWAP_START_SELECT = "swap_start_select"
        private const val KEY_BG_TINT = "bg_tint"
        private const val KEY_COLOR_HIGHLIGHT = "color_highlight"
        private const val KEY_COLOR_TEXT = "color_text"
        private const val KEY_COLOR_HIGHLIGHT_TEXT = "color_highlight_text"
        private const val KEY_COLOR_ACCENT = "color_accent"
        private const val KEY_PLATFORM_SWITCHING = "platform_switching"
        private const val KEY_SHOW_TOOLS = "show_tools"
        private const val KEY_SHOW_PORTS = "show_ports"
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
    SMALL, MEDIUM, LARGE;
    companion object {
        fun fromString(value: String?): TextSize =
            entries.firstOrNull { it.name == value } ?: MEDIUM
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
