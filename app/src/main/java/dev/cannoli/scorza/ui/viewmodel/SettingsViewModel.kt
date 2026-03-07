package dev.cannoli.scorza.ui.viewmodel

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import dev.cannoli.scorza.R
import dev.cannoli.scorza.settings.ButtonLayout
import dev.cannoli.scorza.settings.ScrollSpeed
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.settings.TextSize
import dev.cannoli.scorza.settings.TimeFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val cannoliRoot: java.io.File? = null
) : ViewModel() {

    data class SettingsItem(
        val key: String,
        @StringRes val labelRes: Int,
        @StringRes val valueRes: Int? = null,
        val valueText: String? = null,
        val isEditable: Boolean = false
    )

    data class Category(
        val key: String,
        @StringRes val labelRes: Int
    )

    data class State(
        val categories: List<Category> = emptyList(),
        val categoryIndex: Int = 0,
        val activeCategory: String? = null,
        val items: List<SettingsItem> = emptyList(),
        val selectedIndex: Int = 0
    ) {
        val inSubList: Boolean get() = activeCategory != null
    }

    data class AppSettings(
        val showBatteryPct: Boolean = false,
        val use24h: Boolean = false,
        val backgroundImagePath: String? = null,
        val textSize: TextSize = TextSize.MEDIUM,
        val boxArtEnabled: Boolean = true,
        val scrollSpeed: ScrollSpeed = ScrollSpeed.NORMAL
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val _appSettings = MutableStateFlow(readAppSettings())
    val appSettings: StateFlow<AppSettings> = _appSettings

    private fun readAppSettings() = AppSettings(
        showBatteryPct = settings.batteryPercentage,
        use24h = settings.timeFormat == TimeFormat.TWENTY_FOUR_HOUR,
        backgroundImagePath = settings.backgroundImagePath,
        textSize = settings.textSize,
        boxArtEnabled = settings.boxArtEnabled,
        scrollSpeed = settings.scrollSpeed
    )

    private val allCategories = listOf(
        Category("appearance", R.string.settings_appearance),
        Category("input", R.string.settings_input),
        Category("advanced", R.string.settings_advanced)
    )

    private var snapshot: Map<String, Any?> = emptyMap()

    fun load() {
        snapshot = captureSettings()
        _state.value = State(categories = allCategories, categoryIndex = 0)
        _appSettings.value = readAppSettings()
    }

    fun cancel() {
        restoreSettings(snapshot)
        _appSettings.value = readAppSettings()
    }

    fun moveSelection(delta: Int) {
        val current = _state.value
        if (current.inSubList) {
            if (current.items.isEmpty()) return
            val size = current.items.size
            val raw = current.selectedIndex + delta
            val newIndex = ((raw % size) + size) % size
            _state.value = current.copy(selectedIndex = newIndex)
        } else {
            if (current.categories.isEmpty()) return
            val size = current.categories.size
            val raw = current.categoryIndex + delta
            val newIndex = ((raw % size) + size) % size
            _state.value = current.copy(categoryIndex = newIndex)
        }
    }

    fun enterCategory(): Boolean {
        val current = _state.value
        if (current.inSubList) return false
        val cat = current.categories.getOrNull(current.categoryIndex) ?: return false
        val items = buildItemsForCategory(cat.key)
        _state.value = current.copy(
            activeCategory = cat.key,
            items = items,
            selectedIndex = 0
        )
        return true
    }

    fun exitSubList(): Boolean {
        val current = _state.value
        if (!current.inSubList) return false
        _state.value = current.copy(
            activeCategory = null,
            items = emptyList(),
            selectedIndex = 0
        )
        return true
    }

    fun cycleSelected(direction: Int) {
        val current = _state.value
        if (!current.inSubList) return
        val item = current.items.getOrNull(current.selectedIndex) ?: return

        when (item.key) {
            "button_layout" -> {
                settings.buttonLayout = if (settings.buttonLayout == ButtonLayout.XBOX) ButtonLayout.NINTENDO else ButtonLayout.XBOX
            }
            "box_art" -> settings.boxArtEnabled = !settings.boxArtEnabled
            "text_size" -> {
                val values = TextSize.values()
                val cur = values.indexOf(settings.textSize)
                settings.textSize = values[((cur + direction) % values.size + values.size) % values.size]
            }
            "scroll_speed" -> {
                val values = ScrollSpeed.values()
                val cur = values.indexOf(settings.scrollSpeed)
                settings.scrollSpeed = values[((cur + direction) % values.size + values.size) % values.size]
            }
            "time_format" -> {
                settings.timeFormat = if (settings.timeFormat == TimeFormat.TWELVE_HOUR) TimeFormat.TWENTY_FOUR_HOUR else TimeFormat.TWELVE_HOUR
            }
            "battery_pct" -> settings.batteryPercentage = !settings.batteryPercentage
            "bg_image" -> cycleBackgroundImage(direction)
            "swap_start_select" -> settings.swapStartSelect = !settings.swapStartSelect
        }

        val catKey = current.activeCategory ?: return
        _state.value = current.copy(items = buildItemsForCategory(catKey))
        _appSettings.value = readAppSettings()
    }

    fun enterSelected(): String? {
        val current = _state.value
        if (!current.inSubList) return null
        val item = current.items.getOrNull(current.selectedIndex) ?: return null

        return if (item.isEditable) {
            item.key
        } else {
            cycleSelected(1)
            null
        }
    }

    fun getSelectedItem(): SettingsItem? {
        val current = _state.value
        if (!current.inSubList) return null
        return current.items.getOrNull(current.selectedIndex)
    }

    fun getSelectedItemDisplayValue(): String {
        val item = getSelectedItem() ?: return ""
        return item.valueText ?: ""
    }

    private fun cycleBackgroundImage(direction: Int = 1) {
        val root = cannoliRoot ?: return
        val wallpapersDir = java.io.File(root, "Wallpapers")
        val imageExtensions = setOf("png", "jpg", "jpeg")
        val images = wallpapersDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in imageExtensions }
            ?.sortedBy { it.name }
            ?: emptyList()

        if (images.isEmpty()) {
            settings.backgroundImagePath = null
            return
        }

        val currentPath = settings.backgroundImagePath
        val currentIndex = images.indexOfFirst { it.absolutePath == currentPath }

        val newIndex = if (currentIndex == -1) {
            if (direction > 0) 0 else images.lastIndex
        } else {
            currentIndex + direction
        }

        settings.backgroundImagePath = when {
            newIndex < 0 || newIndex >= images.size -> null
            else -> images[newIndex].absolutePath
        }
    }

    private fun captureSettings(): Map<String, Any?> = mapOf(
        "button_layout" to settings.buttonLayout,
        "box_art" to settings.boxArtEnabled,
        "text_size" to settings.textSize,
        "scroll_speed" to settings.scrollSpeed,
        "time_format" to settings.timeFormat,
        "battery_pct" to settings.batteryPercentage,
        "bg_image" to settings.backgroundImagePath,
        "swap_start_select" to settings.swapStartSelect,
        "sd_root" to settings.sdCardRoot,
        "ra_package" to settings.retroArchPackage
    )

    @Suppress("UNCHECKED_CAST")
    private fun restoreSettings(snap: Map<String, Any?>) {
        (snap["button_layout"] as? ButtonLayout)?.let { settings.buttonLayout = it }
        (snap["box_art"] as? Boolean)?.let { settings.boxArtEnabled = it }
        (snap["text_size"] as? TextSize)?.let { settings.textSize = it }
        (snap["scroll_speed"] as? ScrollSpeed)?.let { settings.scrollSpeed = it }
        (snap["time_format"] as? TimeFormat)?.let { settings.timeFormat = it }
        (snap["battery_pct"] as? Boolean)?.let { settings.batteryPercentage = it }
        settings.backgroundImagePath = snap["bg_image"] as? String
        (snap["swap_start_select"] as? Boolean)?.let { settings.swapStartSelect = it }
        (snap["sd_root"] as? String)?.let { settings.sdCardRoot = it }
        (snap["ra_package"] as? String)?.let { settings.retroArchPackage = it }
    }

    private fun onOff(value: Boolean) = if (value) R.string.value_on else R.string.value_off

    private fun buildItemsForCategory(categoryKey: String): List<SettingsItem> = when (categoryKey) {
        "appearance" -> listOf(
            SettingsItem("box_art", R.string.setting_box_art, valueRes = onOff(settings.boxArtEnabled)),
            SettingsItem("text_size", R.string.setting_text_size, valueText = settings.textSize.name.lowercase().replaceFirstChar { it.uppercase() }),
            SettingsItem("scroll_speed", R.string.setting_scroll_speed, valueText = settings.scrollSpeed.name.lowercase().replaceFirstChar { it.uppercase() }),
            SettingsItem("bg_image", R.string.setting_bg_image, valueText = settings.backgroundImagePath?.let { java.io.File(it).name }, valueRes = if (settings.backgroundImagePath == null) R.string.value_none else null),
            SettingsItem("time_format", R.string.setting_time_format, valueText = if (settings.timeFormat == TimeFormat.TWELVE_HOUR) "12h" else "24h"),
            SettingsItem("battery_pct", R.string.setting_battery_pct, valueRes = onOff(settings.batteryPercentage))
        )
        "input" -> listOf(
            SettingsItem("button_layout", R.string.setting_button_layout, valueText = settings.buttonLayout.name.lowercase().replaceFirstChar { it.uppercase() }),
            SettingsItem("swap_start_select", R.string.setting_swap_start_select, valueRes = onOff(settings.swapStartSelect))
        )
        "advanced" -> listOf(
            SettingsItem("sd_root", R.string.setting_sd_root, valueText = settings.sdCardRoot, isEditable = true),
            SettingsItem("ra_package", R.string.setting_ra_package, valueText = settings.retroArchPackage, isEditable = true)
        )
        else -> emptyList()
    }
}
