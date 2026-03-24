package dev.cannoli.scorza.libretro

sealed class IGMScreen {
    abstract val selectedIndex: Int

    data class Menu(override val selectedIndex: Int = 0, val confirmDeleteSlot: Boolean = false) : IGMScreen()
    data class Settings(override val selectedIndex: Int = 0) : IGMScreen()
    data class Frontend(override val selectedIndex: Int = 0) : IGMScreen()

    data class Emulator(override val selectedIndex: Int = 0, val showDescription: Boolean = false) : IGMScreen()
    data class EmulatorCategory(override val selectedIndex: Int = 0, val categoryKey: String, val categoryTitle: String = "", val showDescription: Boolean = false) : IGMScreen()
    data class Controls(override val selectedIndex: Int = 0, val listeningIndex: Int = -1, val listenCountdownMs: Int = 0) : IGMScreen()
    data class Shortcuts(override val selectedIndex: Int = 0, val listening: Boolean = false, val heldKeys: Set<Int> = emptySet(), val countdownMs: Int = 0) : IGMScreen()
    data class ShaderSettings(override val selectedIndex: Int = 0) : IGMScreen()
    data class SavePrompt(override val selectedIndex: Int = 0) : IGMScreen()
    data class Info(override val selectedIndex: Int = 0) : IGMScreen()
}
