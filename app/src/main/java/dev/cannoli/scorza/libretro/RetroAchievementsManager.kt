package dev.cannoli.scorza.libretro

import android.os.Handler
import android.os.Looper
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class RetroAchievementsManager(
    private val context: android.content.Context? = null,
    private val cacheDir: java.io.File? = null,
    private val onEvent: (type: Int, title: String, description: String, points: Int) -> Unit = { _, _, _, _ -> },
    private val onLogin: (success: Boolean, displayName: String, token: String?) -> Unit = { _, _, _ -> },
    private val onSyncStatus: (message: String) -> Unit = {}
) {
    private val httpExecutor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun init() {
        nativeInit()
        registerNetworkCallback()
    }

    fun destroy() {
        unregisterNetworkCallback()
        nativeDestroy()
        httpExecutor.shutdownNow()
    }

    fun loginWithToken(username: String, token: String) {
        nativeLoginWithToken(username, token)
    }

    fun loginWithPassword(username: String, password: String) {
        nativeLoginWithPassword(username, password)
    }

    fun loadGame(romPath: String, consoleId: Int) {
        nativeLoadGame(romPath, consoleId)
    }

    fun loadGameById(gameId: Int, consoleId: Int) {
        nativeLoadGameById(gameId, consoleId)
    }

    fun unloadGame() {
        nativeUnloadGame()
    }

    fun doFrame() {
        nativeDoFrame()
    }

    fun idle() {
        nativeIdle()
    }

    fun reset() {
        nativeReset()
    }

    val isLoggedIn: Boolean get() = nativeIsLoggedIn()
    val username: String get() = nativeGetUsername()

    private var cachedAchievements: List<Achievement>? = null
    val pendingSyncIds = mutableSetOf<Int>()
    val localUnlocks = mutableSetOf<Int>()

    private fun syncPending() {
        if (pendingSyncIds.isEmpty() || !nativeIsLoggedIn()) return
        val toSync = pendingSyncIds.toSet()
        val count = toSync.size
        pendingSyncIds.clear()
        savePendingSync()
        cachedAchievements = null
        httpExecutor.execute {
            for (id in toSync) nativeManualUnlock(id)
        }
        mainHandler.post {
            onSyncStatus("$count Offline ${if (count == 1) "Achievement" else "Achievements"} Synced")
        }
    }
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    private fun registerNetworkCallback() {
        if (context == null || networkCallback != null) return
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                syncPending()
            }
        }
        cm.registerDefaultNetworkCallback(cb)
        networkCallback = cb
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        val cm = context?.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return
        try { cm.unregisterNetworkCallback(cb) } catch (_: Exception) {}
        networkCallback = null
    }
    private val pendingSyncFile = cacheDir?.let { java.io.File(it, "pending_sync.txt") }

    init {
        pendingSyncFile?.let { file ->
            if (file.exists()) {
                try {
                    file.readLines().mapNotNull { it.trim().toIntOrNull() }.forEach {
                        pendingSyncIds.add(it)
                        localUnlocks.add(it)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun savePendingSync() {
        pendingSyncFile?.let { file ->
            try {
                file.parentFile?.mkdirs()
                if (pendingSyncIds.isEmpty()) file.delete()
                else file.writeText(pendingSyncIds.joinToString("\n"))
            } catch (_: Exception) {}
        }
    }
    @Volatile var isOffline = false
        private set

    fun getAchievements(): List<Achievement> {
        cachedAchievements?.let { return it }
        val raw = nativeGetAchievementData()
        if (raw.isEmpty()) return emptyList()
        val list = raw.split('\n').mapNotNull { line ->
            val parts = line.split('|', limit = 7)
            if (parts.size < 7) return@mapNotNull null
            Achievement(
                id = parts[0].toIntOrNull() ?: return@mapNotNull null,
                title = parts[1],
                description = parts[2],
                points = parts[3].toIntOrNull() ?: 0,
                unlocked = parts[4] == "1",
                state = parts[5].toIntOrNull() ?: 0,
                unlockTime = parts[6].toLongOrNull() ?: 0
            )
        }.filter { it.id > 0 && !it.title.startsWith("Warning:") }
            .sortedBy { if (it.points == 0) 1 else 0 }
        cachedAchievements = list
        return list
    }

    fun invalidateCache() {
        cachedAchievements = null
    }

    fun manualUnlock(achievementId: Int) {
        nativeManualUnlock(achievementId)
    }

    data class Achievement(
        val id: Int,
        val title: String,
        val description: String,
        val points: Int,
        val unlocked: Boolean,
        val state: Int,
        val unlockTime: Long = 0,
        val pendingSync: Boolean = false
    )

    private fun cacheKey(postData: String?): String? {
        if (postData == null) return null
        val cacheable = postData.contains("r=achievementsets") || postData.contains("r=login2") || postData.contains("r=startsession")
        if (!cacheable) return null
        return postData.replace(Regex("[&?](t|u)=[^&]+"), "")
            .hashCode().toUInt().toString(16)
    }

    private fun readCache(key: String): String? {
        val file = java.io.File(cacheDir ?: return null, "ra_$key.json")
        return if (file.exists()) try { file.readText() } catch (_: Exception) { null } else null
    }

    private fun writeCache(key: String, body: String) {
        val dir = cacheDir ?: return
        dir.mkdirs()
        try { java.io.File(dir, "ra_$key.json").writeText(body) } catch (_: Exception) {}
    }

    @Suppress("unused")
    private fun onServerCall(url: String, postData: String?, requestPtr: Long) {
        httpExecutor.execute {
            val key = cacheKey(postData)
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                if (postData != null) {
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    OutputStreamWriter(conn.outputStream).use { it.write(postData) }
                }
                val status = conn.responseCode
                val body = try {
                    conn.inputStream.bufferedReader().readText()
                } catch (_: Exception) {
                    conn.errorStream?.bufferedReader()?.readText() ?: ""
                }
                conn.disconnect()
                if (key != null && status == 200 && body.isNotEmpty()) writeCache(key, body)
                isOffline = false
                nativeHttpResponse(requestPtr, body, status)
            } catch (e: Exception) {
                val cached = if (key != null) readCache(key) else null
                if (cached != null) {
                    isOffline = true
                    nativeHttpResponse(requestPtr, cached, 200)
                } else {
                    isOffline = true
                    nativeHttpResponse(requestPtr, "", RC_SERVER_ERROR)
                }
            }
        }
    }

    val pendingSyncCount: Int get() = pendingSyncIds.size

    fun getStatus(): String = when {
        !isOffline -> "Online"
        pendingSyncIds.isEmpty() -> "Offline"
        else -> "Offline \u2022 ${pendingSyncIds.size} Pending Sync"
    }

    @Suppress("unused")
    private fun onAchievementEvent(type: Int, achievementId: Int, title: String, description: String, points: Int) {
        if (achievementId > 0) {
            localUnlocks.add(achievementId)
            pendingSyncIds.add(achievementId)
            savePendingSync()
        }
        cachedAchievements = null
        mainHandler.post { onEvent(type, title, description, points) }
    }

    @Suppress("unused")
    private fun onLoginResult(success: Boolean, displayNameOrError: String, token: String?) {
        if (success && !isOffline) syncPending()
        mainHandler.post { onLogin(success, displayNameOrError, token) }
    }

    private external fun nativeInit()
    private external fun nativeDestroy()
    private external fun nativeLoginWithToken(username: String, token: String)
    private external fun nativeLoginWithPassword(username: String, password: String)
    private external fun nativeLoadGame(romPath: String, consoleId: Int)
    private external fun nativeLoadGameById(gameId: Int, consoleId: Int)
    private external fun nativeUnloadGame()
    private external fun nativeDoFrame()
    private external fun nativeIdle()
    private external fun nativeReset()
    private external fun nativeIsLoggedIn(): Boolean
    private external fun nativeGetUsername(): String
    private external fun nativeHttpResponse(requestPtr: Long, body: String, httpStatus: Int)
    private external fun nativeGetAchievementData(): String
    private external fun nativeManualUnlock(achievementId: Int)

    companion object {
        init {
            System.loadLibrary("retro_bridge")
        }

        private const val RC_SERVER_ERROR = 503

        val CONSOLE_MAP = mapOf(
            "NES" to 7, "SNES" to 3, "GB" to 4, "GBC" to 6, "GBA" to 5,
            "GG" to 15, "MD" to 1, "SMS" to 11, "N64" to 2, "PS" to 12,
            "PCE" to 8, "LYNX" to 13, "NGP" to 14, "WS" to 53, "VB" to 28,
            "ATARI" to 25, "JAGUAR" to 17, "INTV" to 45, "MSX" to 29,
            "PSP" to 41, "DC" to 40, "SATURN" to 39, "DOS" to 68,
            "PCFX" to 49, "SCUMMVM" to 56, "AMIGA" to 35
        )
    }
}
