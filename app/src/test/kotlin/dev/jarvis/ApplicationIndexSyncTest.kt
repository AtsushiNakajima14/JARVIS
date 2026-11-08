package dev.jarvis

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.jarvis.core.common.AssistantSession
import dev.jarvis.core.database.JarvisDao
import dev.jarvis.core.database.JarvisDatabase
import dev.jarvis.core.database.SecretConverter
import dev.jarvis.core.security.TextCipher
import dev.jarvis.device.applications.ApplicationIndexSync
import dev.jarvis.device.applications.ApplicationRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ApplicationIndexSyncTest {
    private lateinit var application: Application
    private lateinit var database: JarvisDatabase
    private lateinit var dao: JarvisDao
    private lateinit var repository: ApplicationRepository
    private lateinit var sync: ApplicationIndexSync
    private val launcher get() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    @Before fun setup() {
        application = ApplicationProvider.getApplicationContext()
        val testCodec = object : TextCipher {
            override fun encrypt(value: String) = value
            override fun decrypt(value: String) = value
        }
        database = Room.inMemoryDatabaseBuilder(application, JarvisDatabase::class.java)
            .addTypeConverter(SecretConverter(testCodec)).allowMainThreadQueries().build()
        dao = database.dao()
        repository = ApplicationRepository(application, dao)
        sync = ApplicationIndexSync(application, repository, AssistantSession())
    }

    @After fun close() { sync.close(); database.close() }

    @Test fun `startup indexes real launcher metadata without a manual command`() = runBlocking {
        install("test.discord", "Discord")
        sync.start(application)
        awaitPackages(setOf("test.discord"))
        val app = dao.applicationList().first { it.packageName == "test.discord" }
        assertEquals("Discord", app.label)
        assertEquals("test.discord.Main", app.activity)
        assertEquals("2.4", app.version)
        assertEquals(1000L, app.installedAt)
        assertEquals(2000L, app.updatedAt)
        assertEquals("Social", app.category)
    }

    @Test fun `package installation and uninstallation update the index automatically`() = runBlocking {
        install("test.discord", "Discord")
        sync.start(application)
        awaitPackages(setOf("test.discord"))
        install("test.youtube", "YouTube")
        broadcast(Intent.ACTION_PACKAGE_ADDED, "test.youtube")
        awaitPackages(setOf("test.discord", "test.youtube"))
        shadowOf(application.packageManager).removePackage("test.discord")
        broadcast(Intent.ACTION_PACKAGE_REMOVED, "test.discord")
        awaitPackages(setOf("test.youtube"))
        assertNull(repository.find("Discord"))
        assertEquals("test.youtube", repository.find("you tube")?.packageName)
    }

    @Test fun `foreground return catches package changes missed while process was inactive`() = runBlocking {
        install("test.discord", "Discord")
        sync.start(application)
        awaitPackages(setOf("test.discord"))
        install("test.spotify", "Spotify")
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try { awaitPackages(setOf("test.discord", "test.spotify")) }
        finally { activity.pause().stop().destroy() }
    }

    @Test fun `a cold app list request fills its own index`() = runBlocking {
        install("test.discord", "Discord")
        assertTrue(repository.list().any { it.packageName == "test.discord" })
        assertEquals("test.discord", repository.find("dis cord")?.packageName)
    }

    private fun install(id: String, name: String) {
        val app = ApplicationInfo().apply { packageName = id; nonLocalizedLabel = name; enabled = true }
        val activity = ActivityInfo().apply {
            packageName = id; this.name = "$id.Main"; applicationInfo = app; enabled = true; exported = true
        }
        val info = PackageInfo().apply {
            packageName = id; applicationInfo = app; activities = arrayOf(activity)
            versionName = "2.4"; firstInstallTime = 1000; lastUpdateTime = 2000
        }
        val resolved = ResolveInfo().apply { activityInfo = activity; nonLocalizedLabel = name }
        val packages = shadowOf(application.packageManager)
        packages.installPackage(info)
        packages.addResolveInfoForIntent(launcher, resolved)
        packages.addResolveInfoForIntent(launcher.setPackage(id), resolved)
    }

    private fun broadcast(action: String, id: String) {
        application.sendBroadcast(Intent(action, Uri.fromParts("package", id, null)))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private suspend fun awaitPackages(expected: Set<String>) {
        withTimeout(5000) { dao.applications().first { apps ->
            apps.map { it.packageName }.filter { it.startsWith("test.") }.toSet() == expected
        } }
    }
}
