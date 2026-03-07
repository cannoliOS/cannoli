package dev.cannoli.scorza.ui.components

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.ui.theme.NerdSymbols
import dev.cannoli.scorza.ui.theme.MPlus1Code
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Timer
import java.util.TimerTask

private const val ICON_BLUETOOTH = "\uDB80\uDCAF"   // 󰂯 nf-md-bluetooth
private const val ICON_WIFI = "\uDB81\uDDA9"         // 󰖩 nf-md-wifi
private const val ICON_BAT_0 = "\uDB80\uDC7A"        // 󰁺 nf-md-battery_10
private const val ICON_BAT_25 = "\uDB80\uDC7C"       // 󰁼 nf-md-battery_30
private const val ICON_BAT_50 = "\uDB80\uDC7E"       // 󰁾 nf-md-battery_50
private const val ICON_BAT_75 = "\uDB80\uDC80"       // 󰂀 nf-md-battery_70
private const val ICON_BAT_100 = "\uDB80\uDC79"      // 󰁹 nf-md-battery
private const val ICON_BAT_CHARGE_0 = "\uDB82\uDC9C"  // 󰢜 nf-md-battery_charging_10
private const val ICON_BAT_CHARGE_25 = "\uDB80\uDC87" // 󰂇 nf-md-battery_charging_30
private const val ICON_BAT_CHARGE_50 = "\uDB82\uDC9D" // 󰢝 nf-md-battery_charging_50
private const val ICON_BAT_CHARGE_75 = "\uDB82\uDC9E" // 󰢞 nf-md-battery_charging_70
private const val ICON_BAT_CHARGE_100 = "\uDB80\uDC85" // 󰂅 nf-md-battery_charging_100

@Composable
fun StatusBar(
    showBatteryPercentage: Boolean = false,
    use24hTime: Boolean = false
) {
    val context = LocalContext.current

    var batteryLevel by remember { mutableIntStateOf(0) }
    var isCharging by remember { mutableStateOf(false) }
    var hasWifi by remember { mutableStateOf(false) }
    var hasBluetooth by remember { mutableStateOf(false) }
    var rawTime by remember { mutableStateOf(Date()) }

    DisposableEffect(Unit) {
        rawTime = Date()
        val timer = Timer()
        timer.scheduleAtFixedRate(object : TimerTask() {
            override fun run() { rawTime = Date() }
        }, 1000, 15000)

        val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                batteryLevel = (level * 100) / scale
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            }
        }
        context.registerReceiver(batteryReceiver, batteryFilter)

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        var networkCallback: ConnectivityManager.NetworkCallback? = null
        try {
            if (cm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                fun checkWifi() {
                    val net = cm.activeNetwork
                    val caps = if (net != null) cm.getNetworkCapabilities(net) else null
                    hasWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                }
                checkWifi()
                networkCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) { checkWifi() }
                    override fun onLost(network: Network) { checkWifi() }
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { checkWifi() }
                }
                cm.registerDefaultNetworkCallback(networkCallback!!)
            }
        } catch (_: SecurityException) {
            hasWifi = false
        }

        val btReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                hasBluetooth = state == BluetoothAdapter.STATE_ON
            }
        }
        try {
            val btAdapter = BluetoothAdapter.getDefaultAdapter()
            hasBluetooth = btAdapter?.isEnabled == true
            context.registerReceiver(btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        } catch (_: SecurityException) {
            hasBluetooth = false
        }

        onDispose {
            timer.cancel()
            try { context.unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
            try { context.unregisterReceiver(btReceiver) } catch (_: Exception) {}
            try { networkCallback?.let { cm?.unregisterNetworkCallback(it) } } catch (_: Exception) {}
        }
    }

    val timeText = if (use24hTime) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(rawTime)
    } else {
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(rawTime)
    }

    val batteryIcon = if (isCharging) {
        when {
            batteryLevel >= 90 -> ICON_BAT_CHARGE_100
            batteryLevel >= 60 -> ICON_BAT_CHARGE_75
            batteryLevel >= 40 -> ICON_BAT_CHARGE_50
            batteryLevel >= 15 -> ICON_BAT_CHARGE_25
            else -> ICON_BAT_CHARGE_0
        }
    } else {
        when {
            batteryLevel >= 90 -> ICON_BAT_100
            batteryLevel >= 60 -> ICON_BAT_75
            batteryLevel >= 40 -> ICON_BAT_50
            batteryLevel >= 15 -> ICON_BAT_25
            else -> ICON_BAT_0
        }
    }

    val iconStyle = TextStyle(
        fontFamily = NerdSymbols,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        color = Color.Black
    )

    val textStyle = TextStyle(
        fontFamily = MPlus1Code,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        color = Color.Black
    )

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (hasBluetooth) {
            Text(text = ICON_BLUETOOTH, style = iconStyle)
        }

        if (hasWifi) {
            Text(text = ICON_WIFI, style = iconStyle)
        }

        if (showBatteryPercentage) {
            Text(text = "$batteryLevel% $timeText", style = textStyle)
        } else {
            Text(text = batteryIcon, style = iconStyle)
            Text(text = timeText, style = textStyle)
        }
    }
}
