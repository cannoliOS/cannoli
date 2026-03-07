package dev.cannoli.launcher.model

data class Platform(
    val tag: String,
    val displayName: String,
    val coreName: String?,
    val hasEmuLaunch: Boolean = false,
    val gameCount: Int = 0
) {
    val hasLauncher: Boolean get() = coreName != null || hasEmuLaunch
}
