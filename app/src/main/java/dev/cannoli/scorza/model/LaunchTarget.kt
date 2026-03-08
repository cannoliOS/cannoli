package dev.cannoli.scorza.model

sealed interface LaunchTarget {
    data object RetroArch : LaunchTarget

    data class EmuLaunch(
        val packageName: String,
        val activityName: String,
        val action: String = "android.intent.action.VIEW"
    ) : LaunchTarget

    data class ApkLaunch(
        val packageName: String
    ) : LaunchTarget

    data class Embedded(
        val corePath: String
    ) : LaunchTarget
}
