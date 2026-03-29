package dev.cannoli.scorza.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import dev.cannoli.scorza.settings.SettingsRepository
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class InstalledCoreService(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    var installedCores: Map<String, Set<String>> = emptyMap()
        private set

    suspend fun queryAllPackages() {
        val result = mutableMapOf<String, Set<String>>()
        for (pkg in SettingsRepository.KNOWN_RA_PACKAGES) {
            if (!context.isPackageInstalled(pkg)) continue
            val cores = queryPackage(pkg)
            if (cores.isNotEmpty()) result[pkg] = cores
        }
        installedCores = result
    }

    private suspend fun queryPackage(pkg: String, timeoutMs: Long = 3000L): Set<String> =
        suspendCancellableCoroutine { cont ->
            val token = Any()
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val cores = intent.getStringArrayExtra("CORES")
                        ?.map { soToCoreId(it) }?.toSet() ?: emptySet()
                    handler.removeCallbacksAndMessages(token)
                    try { context.unregisterReceiver(this) } catch (_: Exception) {}
                    if (cont.isActive) cont.resume(cores)
                }
            }

            context.registerReceiver(
                receiver,
                IntentFilter("com.retroarch.INSTALLED_CORES_RESULT"),
                Context.RECEIVER_EXPORTED
            )

            context.sendBroadcast(Intent("com.retroarch.QUERY_INSTALLED_CORES").apply {
                setPackage(pkg)
            })

            handler.postAtTime({
                try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
                if (cont.isActive) cont.resume(emptySet())
            }, token, android.os.SystemClock.uptimeMillis() + timeoutMs)

            cont.invokeOnCancellation {
                try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
                handler.removeCallbacksAndMessages(token)
            }
        }

    fun hasCoreInPackage(coreId: String, pkg: String): Boolean =
        installedCores[pkg]?.contains(coreId) == true

    companion object {
        private val PACKAGE_LABELS = mapOf(
            "dev.cannoli.ricotta.aarch64" to "RicottaArch",
            "dev.cannoli.ricotta" to "RicottaArch",
            "com.retroarch.aarch64" to "RetroArch",
            "com.retroarch" to "RetroArch"
        )

        fun getPackageLabel(pkg: String): String = PACKAGE_LABELS[pkg] ?: pkg

        fun soToCoreId(filename: String): String =
            filename.removeSuffix("_android.so").removeSuffix(".so")
    }
}
