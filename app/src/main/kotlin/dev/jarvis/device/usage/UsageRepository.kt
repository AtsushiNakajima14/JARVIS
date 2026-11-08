package dev.jarvis.device.usage

import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import android.os.storage.StorageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jarvis.core.database.ApplicationEntity
import dev.jarvis.core.device.DeviceInspector
import dev.jarvis.core.permissions.PermissionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class UsageRepository @Inject constructor(@param:ApplicationContext private val context: Context) {
    suspend fun usage(apps: List<ApplicationEntity>, rare: Boolean): String = withContext(Dispatchers.IO) {
        if (!PermissionStatus.usage(context)) return@withContext "Enable Usage access for JARVIS in Permissions first."
        val end = System.currentTimeMillis()
        val stats = context.getSystemService(UsageStatsManager::class.java).queryAndAggregateUsageStats(end - 7 * 86400000L, end)
        val ranked = apps.map { it.label to (stats[it.packageName]?.totalTimeInForeground ?: 0) }
        val chosen = (if (rare) ranked.sortedBy { it.second } else ranked.filter { it.second > 0 }.sortedByDescending { it.second }).take(5)
        if (chosen.isEmpty()) "No usage data is available for the last seven days." else "In the last seven days: " + chosen.joinToString { "${it.first}, ${it.second / 60000} minutes" } + "."
    }
    suspend fun storage(apps: List<ApplicationEntity>): String = withContext(Dispatchers.IO) {
        if (!PermissionStatus.usage(context)) return@withContext DeviceInspector(context).storage() + " Enable Usage access for app storage sizes."
        val manager = context.getSystemService(StorageStatsManager::class.java)
        val largest = apps.mapNotNull { app ->
            runCatching { val s = manager.queryStatsForPackage(StorageManager.UUID_DEFAULT, app.packageName, Process.myUserHandle()); app.label to s.appBytes + s.dataBytes }.getOrNull()
        }.sortedByDescending { it.second }.take(5)
        DeviceInspector(context).storage() + if (largest.isEmpty()) " App sizes are unavailable." else " Largest visible apps: " + largest.joinToString { "${it.first}, ${DeviceInspector.size(it.second)}" } + "."
    }
}
