package dev.cannoli.scorza.server

import android.content.res.AssetManager
import android.net.wifi.WifiManager
import java.io.File

object KitchenManager {

    private var server: FileServer? = null

    val isRunning: Boolean get() = server?.isRunning ?: false
    val pin: String get() = server?.pin ?: ""

    fun toggle(cannoliRoot: File, assets: AssetManager) {
        val s = server
        if (s != null && s.isRunning) {
            s.stop()
        } else {
            val newServer = FileServer(cannoliRoot, assets)
            server = newServer
            newServer.start()
        }
    }

    fun stop() {
        server?.stop()
    }

    fun getUrl(wifiManager: WifiManager?): String {
        val ip = getWifiIp(wifiManager)
        return "http://$ip:9090"
    }

    private fun getWifiIp(wifiManager: WifiManager?): String {
        val wifiInfo = wifiManager?.connectionInfo
        val ipInt = wifiInfo?.ipAddress ?: 0
        if (ipInt == 0) return "?.?.?.?"
        return "%d.%d.%d.%d".format(
            ipInt and 0xff,
            ipInt shr 8 and 0xff,
            ipInt shr 16 and 0xff,
            ipInt shr 24 and 0xff
        )
    }
}
