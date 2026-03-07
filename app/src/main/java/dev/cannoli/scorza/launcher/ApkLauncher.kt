package dev.cannoli.scorza.launcher

import android.content.Context
import android.content.Intent

class ApkLauncher(private val context: Context) {

    fun launch(packageName: String): LaunchResult {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return LaunchResult.AppNotInstalled(packageName)

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            context.startActivity(intent)
            LaunchResult.Success
        } catch (e: Exception) {
            LaunchResult.Error(e.message ?: "Failed to launch app")
        }
    }
}
