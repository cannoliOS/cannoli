package dev.cannoli.scorza.ui.viewmodel

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import dev.cannoli.scorza.R
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.settings.TextSize
import dev.cannoli.scorza.settings.TimeFormat
import dev.cannoli.scorza.ui.theme.hexToColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val cannoliRoot: java.io.File? = null
) : ViewModel() {

    data class SettingsItem(
        val key: String,
        @param:StringRes val labelRes: Int,
        @param:StringRes val valueRes: Int? = null,
        val valueText: String? = null,
        val isEditable: Boolean = false,
        val swatchColor: Color? = null
    )

    data class Category(
        val key: String,
        @param:StringRes val labelRes: Int
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
        val use24h: Boolean = false,
        val backgroundImagePath: String? = null,
        val backgroundTint: Int = 0,
        val textSize: TextSize = TextSize.DEFAULT,
        val colorHighlight: Color = Color.White,
        val colorText: Color = Color.White,
        val colorHighlightText: Color = Color.Black,
        val colorAccent: Color = Color.White,
        val showWifi: Boolean = true,
        val showBluetooth: Boolean = true,
        val showClock: Boolean = true,
        val showBattery: Boolean = true,
        val showTools: Boolean = false,
        val showPorts: Boolean = false
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val _appSettings = MutableStateFlow(readAppSettings())
    val appSettings: StateFlow<AppSettings> = _appSettings

    private fun readAppSettings() = AppSettings(
        use24h = settings.timeFormat == TimeFormat.TWENTY_FOUR_HOUR,
        backgroundImagePath = settings.backgroundImagePath,
        backgroundTint = settings.backgroundTint,
        textSize = settings.textSize,
        colorHighlight = hexToColor(settings.colorHighlight) ?: Color.White,
        colorText = hexToColor(settings.colorText) ?: Color.White,
        colorHighlightText = hexToColor(settings.colorHighlightText) ?: Color.Black,
        colorAccent = hexToColor(settings.colorAccent) ?: Color.White,
        showWifi = settings.showWifi,
        showBluetooth = settings.showBluetooth,
        showClock = settings.showClock,
        showBattery = settings.showBattery,
        showTools = settings.showTools,
        showPorts = settings.showPorts
    )

    private val allCategories = listOf(
        Category("appearance", R.string.settings_appearance),
        Category("content", R.string.settings_content),
        Category("status_bar", R.string.settings_status_bar),
        Category("input", R.string.settings_input),
        Category("kitchen", R.string.settings_kitchen),
        Category("advanced", R.string.settings_advanced),
        Category("about", R.string.settings_about)
    )

    private var snapshot: Map<String, Any?> = emptyMap()

    fun load() {
        snapshot = captureSettings()
        _state.value = State(categories = allCategories, categoryIndex = 0)
        _appSettings.value = readAppSettings()
    }

    fun save() {
        snapshot = captureSettings()
    }

    fun cancel() {
        restoreSettings(snapshot)
        _appSettings.value = readAppSettings()
    }

    fun moveSelection(delta: Int) {
        _state.update { current ->
            if (current.inSubList) {
                if (current.items.isEmpty()) return@update current
                val size = current.items.size
                val raw = current.selectedIndex + delta
                val newIndex = ((raw % size) + size) % size
                current.copy(selectedIndex = newIndex)
            } else {
                if (current.categories.isEmpty()) return@update current
                val size = current.categories.size
                val raw = current.categoryIndex + delta
                val newIndex = ((raw % size) + size) % size
                current.copy(categoryIndex = newIndex)
            }
        }
    }

    fun setCategoryIndex(index: Int) {
        _state.update { it.copy(categoryIndex = index) }
    }

    fun enterCategory(): Boolean {
        val current = _state.value
        if (current.inSubList) return false
        val cat = current.categories.getOrNull(current.categoryIndex) ?: return false
        val items = buildItemsForCategory(cat.key)
        _state.update {
            it.copy(activeCategory = cat.key, items = items, selectedIndex = 0)
        }
        return true
    }

    fun exitSubList(): Boolean {
        val current = _state.value
        if (!current.inSubList) return false
        _state.update {
            it.copy(activeCategory = null, items = emptyList(), selectedIndex = 0)
        }
        return true
    }

    fun cycleSelected(direction: Int) {
        val current = _state.value
        if (!current.inSubList) return
        val item = current.items.getOrNull(current.selectedIndex) ?: return

        when (item.key) {
            "text_size" -> {
                val entries = TextSize.entries
                val cur = entries.indexOf(settings.textSize)
                settings.textSize = entries[((cur + direction) % entries.size + entries.size) % entries.size]
            }
            "show_clock" -> {
                if (!settings.showClock) {
                    settings.showClock = true
                    settings.timeFormat = if (direction > 0) TimeFormat.TWELVE_HOUR else TimeFormat.TWENTY_FOUR_HOUR
                } else if (settings.timeFormat == TimeFormat.TWELVE_HOUR && direction > 0) {
                    settings.timeFormat = TimeFormat.TWENTY_FOUR_HOUR
                } else if (settings.timeFormat == TimeFormat.TWENTY_FOUR_HOUR && direction < 0) {
                    settings.timeFormat = TimeFormat.TWELVE_HOUR
                } else {
                    settings.showClock = false
                }
            }
            "bg_image" -> cycleBackgroundImage(direction)
            "bg_tint" -> {
                val cur = settings.backgroundTint
                val next = cur + direction * 10
                settings.backgroundTint = when {
                    next > 90 -> 0
                    next < 0 -> 90
                    else -> next
                }
            }
            "platform_switching" -> settings.platformSwitching = !settings.platformSwitching
            "show_empty" -> settings.showEmpty = !settings.showEmpty
            "show_wifi" -> settings.showWifi = !settings.showWifi
            "show_bluetooth" -> settings.showBluetooth = !settings.showBluetooth
            "show_battery" -> settings.showBattery = !settings.showBattery
            "show_tools" -> settings.showTools = !settings.showTools
            "show_ports" -> settings.showPorts = !settings.showPorts

        }

        val catKey = current.activeCategory ?: return
        _state.update { it.copy(items = buildItemsForCategory(catKey)) }
        _appSettings.value = readAppSettings()
    }

    fun enterSelected(): String? {
        val current = _state.value
        if (!current.inSubList) return null
        val item = current.items.getOrNull(current.selectedIndex) ?: return null

        return if (item.isEditable) {
            item.key
        } else {
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

    fun getColorEntries(): List<dev.cannoli.scorza.ui.screens.ColorEntry> {
        val names = mapOf(
            "color_text" to "Text",
            "color_highlight" to "Highlight",
            "color_highlight_text" to "Highlight Text",
            "color_accent" to "Accent"
        )
        return names.map { (key, label) ->
            val hex = getColorHex(key)
            val color = hexToColor(hex)
            dev.cannoli.scorza.ui.screens.ColorEntry(
                key = key,
                label = label,
                hex = hex,
                color = dev.cannoli.scorza.ui.theme.colorToArgbLong(color ?: androidx.compose.ui.graphics.Color.White)
            )
        }
    }

    fun getColorHex(key: String): String = when (key) {
        "color_highlight" -> settings.colorHighlight
        "color_text" -> settings.colorText
        "color_highlight_text" -> settings.colorHighlightText
        "color_accent" -> settings.colorAccent
        else -> "#FFFFFF"
    }

    fun setColor(key: String, hex: String) {
        when (key) {
            "color_highlight" -> settings.colorHighlight = hex
            "color_text" -> settings.colorText = hex
            "color_highlight_text" -> settings.colorHighlightText = hex
            "color_accent" -> settings.colorAccent = hex
        }
        val catKey = _state.value.activeCategory ?: return
        _state.update { it.copy(items = buildItemsForCategory(catKey)) }
        _appSettings.value = readAppSettings()
    }

    private fun captureSettings(): Map<String, Any?> = mapOf(
        "text_size" to settings.textSize,
        "time_format" to settings.timeFormat,
        "bg_image" to settings.backgroundImagePath,
        "bg_tint" to settings.backgroundTint,
        "color_highlight" to settings.colorHighlight,
        "color_text" to settings.colorText,
        "color_highlight_text" to settings.colorHighlightText,
        "color_accent" to settings.colorAccent,
        "swap_start_select" to settings.swapStartSelect,
        "platform_switching" to settings.platformSwitching,
        "show_wifi" to settings.showWifi,
        "show_bluetooth" to settings.showBluetooth,
        "show_clock" to settings.showClock,
        "show_battery" to settings.showBattery,
        "show_empty" to settings.showEmpty,
        "show_tools" to settings.showTools,
        "show_ports" to settings.showPorts,
        "sd_root" to settings.sdCardRoot,
        "ra_package" to settings.retroArchPackage
    )

    private fun restoreSettings(snap: Map<String, Any?>) {
        (snap["text_size"] as? TextSize)?.let { settings.textSize = it }
        (snap["time_format"] as? TimeFormat)?.let { settings.timeFormat = it }
        settings.backgroundImagePath = snap["bg_image"] as? String
        (snap["bg_tint"] as? Int)?.let { settings.backgroundTint = it }
        (snap["color_highlight"] as? String)?.let { settings.colorHighlight = it }
        (snap["color_text"] as? String)?.let { settings.colorText = it }
        (snap["color_highlight_text"] as? String)?.let { settings.colorHighlightText = it }
        (snap["color_accent"] as? String)?.let { settings.colorAccent = it }
        (snap["swap_start_select"] as? Boolean)?.let { settings.swapStartSelect = it }
        (snap["platform_switching"] as? Boolean)?.let { settings.platformSwitching = it }
        (snap["show_wifi"] as? Boolean)?.let { settings.showWifi = it }
        (snap["show_bluetooth"] as? Boolean)?.let { settings.showBluetooth = it }
        (snap["show_clock"] as? Boolean)?.let { settings.showClock = it }
        (snap["show_battery"] as? Boolean)?.let { settings.showBattery = it }
        (snap["show_empty"] as? Boolean)?.let { settings.showEmpty = it }
        (snap["show_tools"] as? Boolean)?.let { settings.showTools = it }
        (snap["show_ports"] as? Boolean)?.let { settings.showPorts = it }
        (snap["sd_root"] as? String)?.let { settings.sdCardRoot = it }
        (snap["ra_package"] as? String)?.let { settings.retroArchPackage = it }
    }

    private fun onOff(value: Boolean) = if (value) R.string.value_on else R.string.value_off
    private fun showHide(value: Boolean) = if (value) R.string.value_show else R.string.value_hide

    private fun buildItemsForCategory(categoryKey: String): List<SettingsItem> = when (categoryKey) {
        "appearance" -> buildList {
            add(SettingsItem("bg_image", R.string.setting_bg_image, valueText = settings.backgroundImagePath?.let { java.io.File(it).name }, valueRes = if (settings.backgroundImagePath == null) R.string.value_none else null))
            if (settings.backgroundImagePath != null) {
                val tintVal = settings.backgroundTint
                add(SettingsItem("bg_tint", R.string.setting_bg_tint, valueText = if (tintVal == 0) null else "$tintVal%", valueRes = if (tintVal == 0) R.string.value_off else null))
            }
            add(SettingsItem("colors", R.string.setting_colors, isEditable = true))
            add(SettingsItem("text_size", R.string.setting_text_size, valueText = settings.textSize.name.lowercase().replaceFirstChar { it.uppercase() }))
        }
        "content" -> buildList {
            add(SettingsItem("show_empty", R.string.setting_show_empty, valueRes = showHide(settings.showEmpty)))
            add(SettingsItem("show_ports", R.string.setting_show_ports, valueRes = showHide(settings.showPorts)))
            if (settings.showPorts) {
                add(SettingsItem("manage_ports", R.string.setting_manage_ports, isEditable = true))
            }
            add(SettingsItem("show_tools", R.string.setting_show_tools, valueRes = showHide(settings.showTools)))
            if (settings.showTools) {
                add(SettingsItem("manage_tools", R.string.setting_manage_tools, isEditable = true))
            }
        }
        "colors" -> listOf(
            SettingsItem("color_text", R.string.setting_color_text, valueText = settings.colorText.uppercase(), isEditable = true, swatchColor = hexToColor(settings.colorText)),
            SettingsItem("color_highlight", R.string.setting_color_highlight, valueText = settings.colorHighlight.uppercase(), isEditable = true, swatchColor = hexToColor(settings.colorHighlight)),
            SettingsItem("color_highlight_text", R.string.setting_color_highlight_text, valueText = settings.colorHighlightText.uppercase(), isEditable = true, swatchColor = hexToColor(settings.colorHighlightText)),
            SettingsItem("color_accent", R.string.setting_color_accent, valueText = settings.colorAccent.uppercase(), isEditable = true, swatchColor = hexToColor(settings.colorAccent))
        )
        "status_bar" -> listOf(
            SettingsItem("show_battery", R.string.setting_battery, valueRes = showHide(settings.showBattery)),
            SettingsItem("show_bluetooth", R.string.setting_bluetooth, valueRes = showHide(settings.showBluetooth)),
            SettingsItem("show_clock", R.string.setting_clock, valueText = if (!settings.showClock) null else if (settings.timeFormat == TimeFormat.TWELVE_HOUR) "12h" else "24h", valueRes = if (!settings.showClock) R.string.value_hide else null),
            SettingsItem("show_wifi", R.string.setting_wifi, valueRes = showHide(settings.showWifi))
        )
        "input" -> listOf(
            SettingsItem("controls", R.string.setting_controls, isEditable = true),
            SettingsItem("shortcuts", R.string.setting_shortcuts, isEditable = true),
            SettingsItem("platform_switching", R.string.setting_platform_switching, valueRes = onOff(settings.platformSwitching))
        )
        "kitchen" -> emptyList()
        "advanced" -> listOf(
            SettingsItem("core_mapping", R.string.setting_core_mapping, isEditable = true),
            SettingsItem("sd_root", R.string.setting_sd_root, valueText = settings.sdCardRoot, isEditable = true),
            SettingsItem("ra_package", R.string.setting_ra_package, valueText = settings.retroArchPackage, isEditable = true)
        )
        else -> emptyList()
    }
}
