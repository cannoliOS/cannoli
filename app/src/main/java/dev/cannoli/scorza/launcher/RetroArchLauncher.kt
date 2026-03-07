package dev.cannoli.scorza.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import java.io.File

class RetroArchLauncher(
    private val context: Context,
    private val retroArchPackage: String
) {
    fun launch(romFile: File, coreName: String): LaunchResult {
        if (!isPackageInstalled(retroArchPackage)) {
            return LaunchResult.AppNotInstalled(retroArchPackage)
        }

        val intent = Intent().apply {
            component = ComponentName(
                retroArchPackage,
                "com.retroarch.browser.retroactivity.RetroActivityFuture"
            )
            putExtra("LIBRETRO", "/data/data/$retroArchPackage/cores/${coreName}_android.so")
            putExtra("ROM", romFile.absolutePath)
            putExtra("CONFIGFILE", "")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            LaunchResult.Success
        } catch (e: Exception) {
            LaunchResult.Error(e.message ?: "Failed to launch RetroArch")
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }
}
