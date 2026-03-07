package dev.cannoli.launcher.ui.viewmodel

import androidx.lifecycle.ViewModel
import dev.cannoli.launcher.settings.ButtonLayout
import dev.cannoli.launcher.settings.ScrollSpeed
import dev.cannoli.launcher.settings.SettingsRepository
import dev.cannoli.launcher.settings.SortOrder
import dev.cannoli.launcher.settings.TextSize
import dev.cannoli.launcher.settings.TimeFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SettingsViewModel(
    private val settings: SettingsRepository
) : ViewModel() {

    data class SettingsItem(
        val key: String,
        val label: String,
        val valueDisplay: String
    )

    data class State(
        val items: List<SettingsItem> = emptyList(),
        val selectedIndex: Int = 0
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun load() {
        _state.value = State(items = buildItems())
    }

    fun moveSelection(delta: Int) {
        val current = _state.value
        if (current.items.isEmpty()) return
        val size = current.items.size
        val raw = current.selectedIndex + delta
        val newIndex = ((raw % size) + size) % size
        _state.value = current.copy(selectedIndex = newIndex)
    }

    fun toggleSelected() {
        val current = _state.value
        val item = current.items.getOrNull(current.selectedIndex) ?: return

        when (item.key) {
            "button_layout" -> {
                settings.buttonLayout = if (settings.buttonLayout == ButtonLayout.XBOX) ButtonLayout.NINTENDO else ButtonLayout.XBOX
            }
            "box_art" -> settings.boxArtEnabled = !settings.boxArtEnabled
            "text_size" -> {
                settings.textSize = when (settings.textSize) {
                    TextSize.SMALL -> TextSize.MEDIUM
                    TextSize.MEDIUM -> TextSize.LARGE
                    TextSize.LARGE -> TextSize.SMALL
                }
            }
            "sort_order" -> {
                settings.gameSortOrder = if (settings.gameSortOrder == SortOrder.NATURAL) SortOrder.DATE_ADDED else SortOrder.NATURAL
            }
            "scroll_speed" -> {
                settings.scrollSpeed = when (settings.scrollSpeed) {
                    ScrollSpeed.SLOW -> ScrollSpeed.NORMAL
                    ScrollSpeed.NORMAL -> ScrollSpeed.FAST
                    ScrollSpeed.FAST -> ScrollSpeed.SLOW
                }
            }
            "show_core_tag" -> settings.showCoreTag = !settings.showCoreTag
            "time_format" -> {
                settings.timeFormat = if (settings.timeFormat == TimeFormat.TWELVE_HOUR) TimeFormat.TWENTY_FOUR_HOUR else TimeFormat.TWELVE_HOUR
            }
            "battery_pct" -> settings.batteryPercentage = !settings.batteryPercentage
        }

        _state.value = current.copy(items = buildItems())
    }

    private fun buildItems(): List<SettingsItem> = listOf(
        SettingsItem("button_layout", "Button Layout", settings.buttonLayout.name.lowercase().replaceFirstChar { it.uppercase() }),
        SettingsItem("box_art", "Box Art", if (settings.boxArtEnabled) "On" else "Off"),
        SettingsItem("text_size", "Text Size", settings.textSize.name.lowercase().replaceFirstChar { it.uppercase() }),
        SettingsItem("sort_order", "Game Sort Order", if (settings.gameSortOrder == SortOrder.NATURAL) "Natural Sort" else "Date Added"),
        SettingsItem("scroll_speed", "Scroll Speed", settings.scrollSpeed.name.lowercase().replaceFirstChar { it.uppercase() }),
        SettingsItem("show_core_tag", "Show Core Tag", if (settings.showCoreTag) "On" else "Off"),
        SettingsItem("time_format", "Time Format", if (settings.timeFormat == TimeFormat.TWELVE_HOUR) "12h" else "24h"),
        SettingsItem("battery_pct", "Battery Percentage", if (settings.batteryPercentage) "On" else "Off"),
        SettingsItem("sd_root", "SD Card Root", settings.sdCardRoot),
        SettingsItem("ra_package", "RetroArch Package", settings.retroArchPackage)
    )
}
