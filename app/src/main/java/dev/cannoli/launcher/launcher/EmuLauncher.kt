package dev.cannoli.launcher.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

class EmuLauncher(private val context: Context) {

    fun launch(romFile: File, packageName: String, activityName: String, action: String): LaunchResult {
        if (!isPackageInstalled(packageName)) {
            return LaunchResult.AppNotInstalled(packageName)
        }

        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            romFile
        )

        val intent = Intent(action).apply {
            setDataAndType(uri, "*/*")
            component = ComponentName(packageName, activityName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        return try {
            context.startActivity(intent)
            LaunchResult.Success
        } catch (e: Exception) {
            LaunchResult.Error(e.message ?: "Failed to launch emulator")
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
