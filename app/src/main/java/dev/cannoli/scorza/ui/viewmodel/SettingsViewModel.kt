package dev.cannoli.scorza.ui.viewmodel

import android.content.pm.PackageManager
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import dev.cannoli.scorza.R
import dev.cannoli.scorza.launcher.isPackageInstalled
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.settings.TextSize
import dev.cannoli.scorza.settings.TimeFormat
import dev.cannoli.scorza.ui.theme.hexToColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val cannoliRoot: java.io.File? = null,
    private val packageManager: PackageManager? = null
) : ViewModel() {

    val isTelevision = packageManager?.hasSystemFeature(PackageManager.FEATURE_LEANBACK) == true

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

    var raPassword: String = ""

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
        val showVpn: Boolean = false,
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
        showVpn = settings.showVpn,
        showClock = settings.showClock,
        showBattery = settings.showBattery && !isTelevision,
        showTools = settings.showTools,
        showPorts = settings.showPorts
    )

    private val allCategories = listOf(
        Category("appearance", R.string.settings_appearance),
        Category("content", R.string.settings_content),
        Category("status_bar", R.string.settings_status_bar),
        Category("input", R.string.settings_input),
        Category("kitchen", R.string.settings_kitchen),
        Category("retroachievements", R.string.settings_retroachievements),
        Category("advanced", R.string.settings_advanced),
        Category("about", R.string.settings_about)
    )

    private val installedRaPackages: List<String> by lazy {
        val pm = packageManager ?: return@lazy listOf(settings.retroArchPackage)
        SettingsRepository.KNOWN_RA_PACKAGES.filter { pm.isPackageInstalled(it) }
    }

    private data class SettingsSnapshot(
        val textSize: TextSize,
        val timeFormat: TimeFormat,
        val bgImage: String?,
        val bgTint: Int,
        val colorHighlight: String,
        val colorText: String,
        val colorHighlightText: String,
        val colorAccent: String,
        val graphicsBackend: String,
        val platformSwitching: Boolean,
        val showWifi: Boolean,
        val showBluetooth: Boolean,
        val showVpn: Boolean,
        val showClock: Boolean,
        val showBattery: Boolean,
        val showEmpty: Boolean,
        val showTools: Boolean,
        val showPorts: Boolean,
        val sdRoot: String,
        val raPackage: String,
        val toolsName: String,
        val portsName: String
    )

    private var snapshot: SettingsSnapshot? = null

    fun load() {
        snapshot = captureSettings()
        _state.value = State(categories = allCategories, categoryIndex = 0)
        _appSettings.value = readAppSettings()
    }

    fun save() {
        snapshot = captureSettings()
    }

    fun cancel() {
        snapshot?.let { restoreSettings(it) }
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

    fun refreshSubList() {
        val current = _state.value
        val cat = current.activeCategory ?: return
        val items = buildItemsForCategory(cat)
        _state.update { it.copy(items = items) }
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
                val cur = entries.indexOf(settings.textSize).coerceAtLeast(0)
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
            "show_vpn" -> settings.showVpn = !settings.showVpn
            "show_battery" -> settings.showBattery = !settings.showBattery
            "show_tools" -> settings.showTools = !settings.showTools
            "show_ports" -> settings.showPorts = !settings.showPorts
            "graphics_backend" -> {
                val backends = listOf("GLES", "VULKAN")
                val cur = backends.indexOf(settings.graphicsBackend).coerceAtLeast(0)
                settings.graphicsBackend = backends[((cur + direction) % backends.size + backends.size) % backends.size]
            }
            "ra_package" -> {
                val pkgs = installedRaPackages
                if (pkgs.isNotEmpty()) {
                    val cur = pkgs.indexOf(settings.retroArchPackage).coerceAtLeast(0)
                    settings.retroArchPackage = pkgs[((cur + direction) % pkgs.size + pkgs.size) % pkgs.size]
                }
            }
        }

        val catKey = current.activeCategory ?: return
        val newItems = buildItemsForCategory(catKey)
        _state.update { it.copy(items = newItems, selectedIndex = it.selectedIndex.coerceAtMost((newItems.size - 1).coerceAtLeast(0))) }
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
            ?.filter { it.isFile && it.extension.lowercase(java.util.Locale.ROOT) in imageExtensions }
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
            val raw = currentIndex + direction
            if (raw < 0 || raw >= images.size) -1 else raw
        }

        settings.backgroundImagePath = if (newIndex < 0) null else images[newIndex].absolutePath
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

    private fun captureSettings() = SettingsSnapshot(
        textSize = settings.textSize,
        timeFormat = settings.timeFormat,
        bgImage = settings.backgroundImagePath,
        bgTint = settings.backgroundTint,
        colorHighlight = settings.colorHighlight,
        colorText = settings.colorText,
        colorHighlightText = settings.colorHighlightText,
        colorAccent = settings.colorAccent,
        graphicsBackend = settings.graphicsBackend,
        platformSwitching = settings.platformSwitching,
        showWifi = settings.showWifi,
        showBluetooth = settings.showBluetooth,
        showVpn = settings.showVpn,
        showClock = settings.showClock,
        showBattery = settings.showBattery,
        showEmpty = settings.showEmpty,
        showTools = settings.showTools,
        showPorts = settings.showPorts,
        sdRoot = settings.sdCardRoot,
        raPackage = settings.retroArchPackage,
        toolsName = settings.toolsName,
        portsName = settings.portsName
    )

    private fun restoreSettings(snap: SettingsSnapshot) {
        settings.textSize = snap.textSize
        settings.timeFormat = snap.timeFormat
        settings.backgroundImagePath = snap.bgImage
        settings.backgroundTint = snap.bgTint
        settings.colorHighlight = snap.colorHighlight
        settings.colorText = snap.colorText
        settings.colorHighlightText = snap.colorHighlightText
        settings.colorAccent = snap.colorAccent
        settings.graphicsBackend = snap.graphicsBackend
        settings.platformSwitching = snap.platformSwitching
        settings.showWifi = snap.showWifi
        settings.showBluetooth = snap.showBluetooth
        settings.showVpn = snap.showVpn
        settings.showClock = snap.showClock
        settings.showBattery = snap.showBattery
        settings.showEmpty = snap.showEmpty
        settings.showTools = snap.showTools
        settings.showPorts = snap.showPorts
        settings.sdCardRoot = snap.sdRoot
        settings.retroArchPackage = snap.raPackage
        settings.toolsName = snap.toolsName
        settings.portsName = snap.portsName
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
            add(SettingsItem("text_size", R.string.setting_text_size, valueText = settings.textSize.name.lowercase(java.util.Locale.ROOT).replaceFirstChar { it.uppercase() }))
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
        "status_bar" -> buildList {
            if (!isTelevision) add(SettingsItem("show_battery", R.string.setting_battery, valueRes = showHide(settings.showBattery)))
            add(SettingsItem("show_bluetooth", R.string.setting_bluetooth, valueRes = showHide(settings.showBluetooth)))
            add(SettingsItem("show_clock", R.string.setting_clock, valueText = if (!settings.showClock) null else if (settings.timeFormat == TimeFormat.TWELVE_HOUR) "12h" else "24h", valueRes = if (!settings.showClock) R.string.value_hide else null))
            add(SettingsItem("show_wifi", R.string.setting_wifi, valueRes = showHide(settings.showWifi)))
            add(SettingsItem("show_vpn", R.string.setting_vpn, valueRes = showHide(settings.showVpn)))
        }
        "input" -> listOf(
            SettingsItem("controls", R.string.setting_controls, isEditable = true),
            SettingsItem("shortcuts", R.string.setting_shortcuts, isEditable = true),
            SettingsItem("platform_switching", R.string.setting_platform_switching, valueRes = onOff(settings.platformSwitching))
        )
        "kitchen" -> emptyList()
        "retroachievements" -> buildList {
            add(SettingsItem("ra_username", R.string.setting_ra_username, valueText = settings.raUsername.ifEmpty { "Not set" }, isEditable = true))
            add(SettingsItem("ra_password", R.string.setting_ra_password, valueText = if (raPassword.isEmpty()) "Not set" else "●".repeat(raPassword.length), isEditable = true))
            if (settings.raUsername.isNotEmpty() && raPassword.isNotEmpty()) {
                add(SettingsItem("ra_login", R.string.setting_ra_login, isEditable = true))
            }
        }
        "advanced" -> listOf(
            SettingsItem("core_mapping", R.string.setting_core_mapping, isEditable = true),
            SettingsItem("sd_root", R.string.setting_sd_root, valueText = settings.sdCardRoot, isEditable = true),
            SettingsItem("ra_package", R.string.setting_ra_package, valueText = if (installedRaPackages.isEmpty()) "Not Installed" else settings.retroArchPackage, isEditable = installedRaPackages.size < 2)
        )
        else -> emptyList()
    }
}
