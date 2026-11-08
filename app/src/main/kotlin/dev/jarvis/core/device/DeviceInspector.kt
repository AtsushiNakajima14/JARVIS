package dev.jarvis.core.device

import android.Manifest
import android.app.ActivityManager
import android.bluetooth.BluetoothManager
import android.content.*
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.*
import dev.jarvis.core.permissions.PermissionStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceInspector @Inject constructor(@param:ApplicationContext private val context: Context) {
    fun battery(): String {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        if (level < 0 || scale <= 0) return "Battery information is unavailable."
        val charging = (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        return "Your battery is ${level * 100 / scale} percent${if (charging) " and charging" else ""}."
    }
    fun ram(): String {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return "${size(info.availMem)} available out of ${size(info.totalMem)} RAM."
    }
    fun storage(): String {
        val fs = StatFs(context.filesDir.path)
        return "${size(fs.availableBytes)} free out of ${size(fs.totalBytes)} internal storage."
    }
    fun network(): String {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val network = when { caps == null -> "Offline"; caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi connected"; caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile network connected"; else -> "Network connected" }
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java).isWifiEnabled
        val bluetooth = if (Build.VERSION.SDK_INT < 31 || PermissionStatus.granted(context, Manifest.permission.BLUETOOTH_CONNECT))
            runCatching { if (context.getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true) "on" else "off" }.getOrDefault("unavailable") else "permission needed"
        return "$network. Wi-Fi ${if (wifi) "on" else "off"}. Bluetooth $bluetooth."
    }
    fun snapshot(): Map<String, String> {
        val display = context.resources.displayMetrics
        val cpu = runCatching { File("/proc/cpuinfo").useLines { it.take(8).joinToString("; ").take(500) } }.getOrDefault(Build.SUPPORTED_ABIS.joinToString())
        return linkedMapOf("Device" to "${Build.MANUFACTURER} ${Build.MODEL}", "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "CPU" to "${Build.SUPPORTED_ABIS.joinToString()} · ${Runtime.getRuntime().availableProcessors()} cores · $cpu",
            "RAM" to ram(), "Storage" to storage(), "Battery" to battery(), "Network" to network(),
            "Display" to "${display.widthPixels} × ${display.heightPixels}, ${display.densityDpi} dpi")
    }
    companion object { fun size(bytes: Long): String = String.format(java.util.Locale.getDefault(), "%.1f GB", bytes / 1_073_741_824.0) }
}

