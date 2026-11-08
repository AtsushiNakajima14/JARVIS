package dev.jarvis.device.applications

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jarvis.assistant.parser.ApplicationMatcher
import dev.jarvis.assistant.parser.NamedApplication
import dev.jarvis.assistant.parser.normalize
import dev.jarvis.core.database.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApplicationRepository @Inject constructor(@param:ApplicationContext private val context: Context, private val dao: JarvisDao) {
    private val scanLock = Mutex()
    val applications = dao.applications()
    suspend fun scan(): Int = scanLock.withLock { withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0).mapNotNull { resolved ->
            runCatching {
                val activity = resolved.activityInfo
                val info = pm.getPackageInfo(activity.packageName, 0)
                ApplicationEntity(activity.packageName, resolved.loadLabel(pm).toString(), activity.name,
                    category(resolved.loadLabel(pm).toString(), activity.applicationInfo.category), info.versionName ?: "Unknown",
                    info.firstInstallTime, info.lastUpdateTime)
            }.getOrNull()
        }.distinctBy { it.packageName }
        // Foreground resumes often leave the inventory unchanged; avoid invalidating all
        // Room observers or racing an older scan over a more recent package event.
        if (apps.sortedBy { it.packageName } != dao.applicationList().sortedBy { it.packageName }) dao.replaceApps(apps)
        apps.size
    } }
    suspend fun find(query: String): ApplicationEntity? {
        val all = dao.applicationList()
        val match = ApplicationMatcher.find(query, all.map { NamedApplication(it.packageName, it.label) })
        return all.firstOrNull { it.packageName == match }
    }
    suspend fun list(): List<ApplicationEntity> {
        val existing = dao.applicationList()
        if (existing.isNotEmpty()) return existing
        scan()
        return dao.applicationList()
    }
    fun launchIntent(app: ApplicationEntity): Intent? = context.packageManager.getLaunchIntentForPackage(app.packageName)
    private fun category(label: String, category: Int): String {
        val text = normalize(label)
        if (listOf("studio", "code", "termux", "github", "gitlab", "compiler", "programming").any { text.contains(it) }) return "Development"
        if (listOf("edit", "capcut", "lightroom", "snapseed", "canva", "photoshop").any { text.contains(it) }) return "Editing"
        if (listOf("discord", "facebook", "messenger", "whatsapp", "telegram", "instagram", "signal").any { text.contains(it) }) return "Social"
        if (listOf("learn", "classroom", "school", "duolingo", "anki").any { text.contains(it) }) return "Education"
        return when (category) {
            ApplicationInfo.CATEGORY_GAME -> "Games"
            ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO -> "Entertainment"
            ApplicationInfo.CATEGORY_SOCIAL -> "Social"
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Productivity"
            ApplicationInfo.CATEGORY_IMAGE -> "Editing"
            else -> "Other"
        }
    }
}

