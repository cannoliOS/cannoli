package dev.cannoli.scorza.libretro

import android.os.Handler
import android.os.Looper
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class RetroAchievementsManager(
    private val onEvent: (type: Int, title: String, description: String, points: Int) -> Unit = { _, _, _, _ -> },
    private val onLogin: (success: Boolean, displayName: String, token: String?) -> Unit = { _, _, _ -> }
) {
    private val httpExecutor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun init() {
        nativeInit()
    }

    fun destroy() {
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

    fun getAchievements(): List<Achievement> {
        val json = nativeGetAchievements()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                Achievement(
                    id = obj.getInt("id"),
                    title = obj.getString("title"),
                    description = obj.getString("description"),
                    points = obj.getInt("points"),
                    unlocked = obj.getInt("unlocked") == 1,
                    state = obj.getInt("state"),
                    badgeUrl = obj.optString("badge", ""),
                    unlockTime = obj.optLong("unlock_time", 0)
                )
            }.filter { it.id > 0 && !it.title.startsWith("Warning:") }
        } catch (_: Exception) { emptyList() }
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
        val badgeUrl: String = "",
        val unlockTime: Long = 0
    )

    @Suppress("unused")
    private fun onServerCall(url: String, postData: String?, requestPtr: Long) {
        httpExecutor.execute {
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
                nativeHttpResponse(requestPtr, body, status)
            } catch (e: Exception) {
                nativeHttpResponse(requestPtr, "", RC_SERVER_ERROR)
            }
        }
    }

    @Suppress("unused")
    private fun onAchievementEvent(type: Int, title: String, description: String, points: Int) {
        mainHandler.post { onEvent(type, title, description, points) }
    }

    @Suppress("unused")
    private fun onLoginResult(success: Boolean, displayNameOrError: String, token: String?) {
        mainHandler.post { onLogin(success, displayNameOrError, token) }
    }

    private external fun nativeInit()
    private external fun nativeDestroy()
    private external fun nativeLoginWithToken(username: String, token: String)
    private external fun nativeLoginWithPassword(username: String, password: String)
    private external fun nativeLoadGame(romPath: String, consoleId: Int)
    private external fun nativeUnloadGame()
    private external fun nativeDoFrame()
    private external fun nativeIdle()
    private external fun nativeReset()
    private external fun nativeIsLoggedIn(): Boolean
    private external fun nativeGetUsername(): String
    private external fun nativeHttpResponse(requestPtr: Long, body: String, httpStatus: Int)
    private external fun nativeGetAchievements(): String
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
