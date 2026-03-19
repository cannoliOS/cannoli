package dev.cannoli.scorza.model

data class Platform(
    val tag: String,
    val displayName: String,
    val coreName: String?,
    val hasEmuLaunch: Boolean = false,
    val gameCount: Int = 0,
    val tags: List<String> = emptyList()
) {
    val hasLauncher: Boolean get() = coreName != null || hasEmuLaunch
    val allTags: List<String> get() = tags.ifEmpty { listOf(tag) }
}
