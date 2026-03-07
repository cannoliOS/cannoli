package dev.cannoli.launcher.launcher

sealed class LaunchResult {
    object Success : LaunchResult()
    data class CoreNotInstalled(val coreName: String) : LaunchResult()
    data class AppNotInstalled(val packageName: String) : LaunchResult()
    data class Error(val message: String) : LaunchResult()
}
