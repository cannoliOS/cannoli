package dev.cannoli.scorza.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.io.File

class RetroArchLauncher(
    private val context: Context,
    private val getRetroArchPackage: () -> String
) {
    fun launch(romFile: File, coreName: String, configPath: String? = null): LaunchResult {
        val retroArchPackage = getRetroArchPackage()
        if (!context.isPackageInstalled(retroArchPackage)) {
            return LaunchResult.AppNotInstalled(retroArchPackage)
        }

        val intent = Intent().apply {
            component = ComponentName(
                retroArchPackage,
                "com.retroarch.browser.retroactivity.RetroActivityFuture"
            )
            putExtra("LIBRETRO", "/data/data/$retroArchPackage/cores/${coreName}_android.so")
            putExtra("ROM", romFile.absolutePath)
            if (configPath != null) putExtra("CONFIGFILE", configPath)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            LaunchResult.Success
        } catch (e: Exception) {
            LaunchResult.Error(e.message ?: "Failed to launch RetroArch")
        }
    }
}

fun Context.isPackageInstalled(packageName: String): Boolean =
    packageManager.isPackageInstalled(packageName)

fun PackageManager.isPackageInstalled(packageName: String): Boolean {
    return try {
        getPackageInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
