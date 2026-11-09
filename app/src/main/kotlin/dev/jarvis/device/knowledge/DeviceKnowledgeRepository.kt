package dev.jarvis.device.knowledge

import android.Manifest
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.usage.UsageStatsManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jarvis.core.database.DeviceInfoEntity
import dev.jarvis.core.database.JarvisDao
import dev.jarvis.core.database.SecretText
import dev.jarvis.core.device.DeviceInspector
import dev.jarvis.core.permissions.PermissionStatus
import dev.jarvis.device.applications.ApplicationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.atomic.AtomicLong

data class ApplicationUsage(val packageName: String, val label: String, val foregroundMillis: Long, val lastUsedAt: Long)

/** Measured, timestamped facts; missing readings are never represented as zero. */
data class DeviceKnowledge(
    val model: String,
    val androidVersion: String,
    val batteryPercent: Int?,
    val charging: Boolean,
    val availableRam: Long?,
    val totalRam: Long?,
    val lowMemory: Boolean,
    val freeStorage: Long?,
    val totalStorage: Long?,
    val connection: String,
    val bluetooth: String,
    val applicationCount: Int,
    val usageAllowed: Boolean,
    val frequentApps: List<ApplicationUsage>,
    val measuredAt: Long
) {
    val batteryDescription: String get() = batteryPercent?.let { "Battery is $it%${if (charging) " and charging" else ""}" } ?: "Battery reading unavailable"
    fun summary(): String = "$batteryDescription. $connection."
}

/** Native readings happen on demand, not on a repeating background polling loop. */
@Singleton
class DeviceKnowledgeRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: JarvisDao,
    private val applications: ApplicationRepository,
    private val inspector: DeviceInspector
) {
    private val mutex = Mutex()
    private val current = MutableStateFlow<DeviceKnowledge?>(null)
    private val snapshotRevision = AtomicLong()
    val snapshot = current.asStateFlow()

    suspend fun refresh(): DeviceKnowledge = mutex.withLock { withContext(Dispatchers.IO) {
        val measuredAt = System.currentTimeMillis()
        val expectedRevision = snapshotRevision.get()
        val battery = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) (level.toLong() * 100 / scale).toInt().takeIf { it in 0..100 } else null
        val memory = runCatching { ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) } }.getOrNull()
        val storage = runCatching { StatFs(context.filesDir.path) }.getOrNull()
        val connection = runCatching {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
            when {
                capabilities == null -> "No active network"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi connected"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile network connected"
                else -> "Network connected"
            }
        }.getOrDefault("Network status unavailable")
        val bluetooth = if (Build.VERSION.SDK_INT >= 31 && !PermissionStatus.granted(context, Manifest.permission.BLUETOOTH_CONNECT)) "Permission not enabled" else
            runCatching { context.getSystemService(BluetoothManager::class.java).adapter?.let { if (it.isEnabled) "On" else "Off" } ?: "Not available" }.getOrDefault("Unavailable")
        val apps = applications.list()
        val usageAllowed = canReadUsage()
        val usage = if (!usageAllowed) emptyList() else runCatching {
            val stats = context.getSystemService(UsageStatsManager::class.java)
                .queryAndAggregateUsageStats(measuredAt - 7 * 86_400_000L, measuredAt)
            apps.mapNotNull { app -> stats[app.packageName]?.takeIf { it.totalTimeInForeground > 0 }?.let {
                ApplicationUsage(app.packageName, app.label, it.totalTimeInForeground, it.lastTimeUsed)
            } }.sortedByDescending { it.foregroundMillis }.take(50)
        }.getOrDefault(emptyList())
        var result = DeviceKnowledge(
            "${Build.MANUFACTURER} ${Build.MODEL}", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            percent, (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0,
            memory?.availMem?.takeIf { it >= 0 }, memory?.totalMem?.takeIf { it > 0 }, memory?.lowMemory == true,
            storage?.availableBytes?.takeIf { it >= 0 }, storage?.totalBytes?.takeIf { it > 0 },
            connection, bluetooth, apps.size, usageAllowed, usage, measuredAt
        )
        if (!canReadUsage()) result = result.copy(usageAllowed = false, frequentApps = emptyList())
        // One bounded usage record replaces older readings, including on access revocation.
        // The package in ApplicationEntity is also the stable reference for its Android icon.
        val facts = runCatching { inspector.snapshot() }.getOrDefault(emptyMap()).toMutableMap().apply {
            put("Device", result.model)
            put("Android", result.androidVersion)
            put("Battery", result.batteryDescription)
            put("Network", result.connection)
            put("Bluetooth", result.bluetooth)
            put("Applications", "${apps.size} visible launcher apps; icons stay in Android")
            put("App usage (7 days)", if (!result.usageAllowed) "Unlock your phone and allow Usage access to see usage readings." else if (result.frequentApps.isEmpty()) "No usage readings available." else
                result.frequentApps.joinToString("\n") { "${it.label}: ${it.foregroundMillis / 60_000} minutes (${it.packageName})" })
            put("Updated", java.text.DateFormat.getDateTimeInstance().format(java.util.Date(measuredAt)))
        }
        dao.putInfo(facts.map { DeviceInfoEntity(it.key, SecretText(it.value), measuredAt) })
        if (!canReadUsage()) {
            result = result.copy(usageAllowed = false, frequentApps = emptyList())
            dao.putInfo(listOf(DeviceInfoEntity("App usage (7 days)", SecretText("Unlock your phone and allow Usage access to see usage readings."), measuredAt)))
        }
        if (snapshotRevision.get() == expectedRevision && !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) current.value = result
        result
    } }

    private fun canReadUsage() = PermissionStatus.usage(context) && !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
    fun clearSnapshot() { snapshotRevision.incrementAndGet(); current.value = null }
}

/** Advice is based on observed conditions, never a promise to clean or speed up Android. */
object DeviceHealthAdvice {
    fun describe(device: DeviceKnowledge): String = buildString {
        append(device.batteryDescription).append(". ")
        if (device.freeStorage != null && device.totalStorage != null) {
            append("Storage has ${DeviceInspector.size(device.freeStorage)} free out of ${DeviceInspector.size(device.totalStorage)}. ")
            if (device.freeStorage.toDouble() / device.totalStorage.coerceAtLeast(1) < 0.1)
                append("Storage is nearly full. Review large downloads or apps you no longer use in Android Settings. ")
        } else append("Storage information is unavailable. ")
        if (device.availableRam != null && device.totalRam != null)
            append("${DeviceInspector.size(device.availableRam)} of ${DeviceInspector.size(device.totalRam)} RAM is currently available. ")
        if (device.lowMemory) append("Android reports memory pressure. Finish a heavy game or app, then try again. ")
        else append("Android manages memory automatically; free RAM alone does not tell us why a phone feels slow. ")
        if (device.usageAllowed && device.frequentApps.isNotEmpty())
            append("Your most used visible apps over the last week include ${device.frequentApps.take(3).joinToString { it.label }}. ")
        append("I cannot inspect every app's running processes. If the slowdown continues, restart the phone and check for app or system updates.")
    }
}
