package dev.cannoli.launcher.model

sealed class LaunchTarget {
    object RetroArch : LaunchTarget()

    data class EmuLaunch(
        val packageName: String,
        val activityName: String,
        val action: String = "android.intent.action.VIEW"
    ) : LaunchTarget()

    data class ApkLaunch(
        val packageName: String
    ) : LaunchTarget()
}
