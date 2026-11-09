package dev.jarvis

import android.app.Application
import android.app.KeyguardManager
import android.app.Notification
import android.app.Person
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.UserHandle
import android.provider.Settings
import android.service.notification.StatusBarNotification
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.jarvis.assistant.notifications.NotificationRepository
import dev.jarvis.core.common.SettingsStore
import dev.jarvis.core.database.*
import dev.jarvis.core.security.TextCipher
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@android.annotation.TargetApi(34)
class NotificationRepositoryTest {
    private lateinit var context: Application
    private lateinit var database: JarvisDatabase
    private lateinit var dao: JarvisDao
    private lateinit var settings: SettingsStore
    private lateinit var notifications: NotificationRepository

    @Before fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        val codec = object : TextCipher {
            private val key = SecretKeySpec(ByteArray(32) { (it + 3).toByte() }, "AES")
            override fun encrypt(value: String): String {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
                return Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
            }
            override fun decrypt(value: String): String {
                val bytes = Base64.getDecoder().decode(value)
                return Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                }.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
            }
        }
        database = Room.inMemoryDatabaseBuilder(context, JarvisDatabase::class.java)
            .addTypeConverter(SecretConverter(codec)).allowMainThreadQueries().build()
        dao = database.dao()
        settings = SettingsStore(context)
        settings.reset()
        notifications = NotificationRepository(context, dao, settings)
        Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners",
            "${context.packageName}/dev.jarvis.device.scanner.NotificationIndexer")
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        val app = ApplicationInfo().apply { packageName = "com.discord"; nonLocalizedLabel = "Discord" }
        shadowOf(context.packageManager).installPackage(PackageInfo().apply { packageName = "com.discord"; applicationInfo = app })
    }

    @After fun close() = runBlocking {
        settings.reset()
        Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners", "")
        database.close()
    }

    private fun message(text: String = "Meet at noon", sender: String = "Alice", at: Long = System.currentTimeMillis(),
        id: Int = 1, ongoing: Boolean = false, secret: Boolean = false): StatusBarNotification {
        val style = Notification.MessagingStyle(Person.Builder().setName("Phone owner").build())
            .addMessage(text, at, Person.Builder().setName(sender).build())
        val notification = Notification.Builder(context, "test-chat")
            .setSmallIcon(android.R.drawable.ic_dialog_email).setContentTitle(sender).setContentText(text)
            .setCategory(Notification.CATEGORY_MESSAGE).setStyle(style).setOngoing(ongoing)
            .setVisibility(if (secret) Notification.VISIBILITY_SECRET else Notification.VISIBILITY_PRIVATE).build()
        return StatusBarNotification("com.discord", "com.discord", id, "chat$id", 10001, 0, 0,
            notification, UserHandle.getUserHandleForUid(10001), at)
    }

    @Test fun `first reading opt in imports already active notification after initial opt out cleanup`() = runBlocking {
        val active = message(at = System.currentTimeMillis() - 30_000)
        notifications.connect { arrayOf(active) }
        notifications.clear() // The listener initially observes the default false setting.
        settings.boolean("notification_reading", true)
        notifications.refresh()
        val answer = notifications.answer(false, "discord")
        assertTrue(answer, answer.contains("Alice")); assertTrue(answer.contains("Meet at noon"))
        assertEquals(1, dao.preferenceList(NotificationRepository.KIND).size)
    }

    @Test fun `preview storage is encrypted and readouts are withheld while locked`() = runBlocking {
        settings.boolean("notification_reading", true)
        val active = message()
        notifications.connect { arrayOf(active) }
        notifications.posted(active)
        val row = dao.preferenceList(NotificationRepository.KIND).single()
        assertTrue(notifications.savedPreviewLabel(row)!!.contains("Discord · Alice"))
        database.openHelper.readableDatabase.query("SELECT content FROM preferences WHERE kind = 'notification_message'").use { cursor ->
            assertTrue(cursor.moveToFirst()); assertFalse(cursor.getString(0).contains("Alice")); assertFalse(cursor.getString(0).contains("Meet at noon"))
        }
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        assertTrue(notifications.answer(false).contains("Unlock your phone"))
        assertTrue(notifications.answer(true).contains("Unlock your phone"))
        assertNull(notifications.savedPreviewLabel(row))
    }

    @Test fun `OTP and secret visibility never enter captured preview history`() = runBlocking {
        settings.boolean("notification_reading", true)
        val code = message("Your verification code is 482915", id = 1)
        val secret = message("Hidden personal text", id = 2, secret = true)
        notifications.connect { arrayOf(code, secret) }
        notifications.refresh()
        assertTrue(dao.preferenceList(NotificationRepository.KIND).isEmpty())
        val answer = notifications.answer(false)
        assertTrue(answer.contains("content is hidden"))
        assertFalse(answer.contains("482915")); assertFalse(answer.contains("Hidden personal text")); assertFalse(answer.contains("Alice"))
    }

    @Test fun `explicit delete fences old event and active snapshots without deleting saved memory`() = runBlocking {
        settings.boolean("notification_reading", true)
        val active = message(at = System.currentTimeMillis() - 1_000)
        notifications.connect { arrayOf(active) }
        notifications.posted(active)
        dao.remember(UserPreferenceEntity("my-memory", SecretText("I use Discord for school")))
        val oldRevision = notifications.currentRevision()
        notifications.clear()
        notifications.posted(active, oldRevision)
        notifications.refresh()
        assertTrue(dao.preferenceList(NotificationRepository.KIND).isEmpty())
        assertEquals(1, dao.preferenceList("memory").size)
        assertTrue(notifications.answer(false).contains("no captured active"))
        val fresh = message("A new message", at = System.currentTimeMillis() + 10, id = 2)
        notifications.posted(fresh)
        assertEquals(1, dao.preferenceList(NotificationRepository.KIND).size)
    }

    @Test fun `app-only announcements work independently without preview capture`() = runBlocking {
        settings.boolean("notification_announcements", true)
        val alert = async(start = CoroutineStart.UNDISPATCHED) { notifications.announcements.first() }
        notifications.posted(message())
        val event = withTimeout(2_000) { alert.await() }
        assertEquals("New notification from Discord.", event.text)
        assertFalse(event.text.contains("Alice")); assertFalse(event.text.contains("Meet at noon"))
        assertTrue(dao.preferenceList(NotificationRepository.KIND).isEmpty())
        assertTrue(notifications.answer(false).contains("Enable Read notification previews"))
    }

    @Test fun `ongoing notifications are excluded and recent correspondents stay scoped to captured messages`() = runBlocking {
        settings.boolean("notification_reading", true)
        notifications.posted(message("Sync in progress", ongoing = true))
        assertTrue(dao.preferenceList(NotificationRepository.KIND).isEmpty())
        notifications.posted(message())
        val answer = notifications.answer(true)
        assertTrue(answer.contains("Alice on Discord")); assertTrue(answer.contains("not your full chat history"))
        Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners", "")
        assertTrue(notifications.answer(true).contains("Enable Notification access"))
    }

    @Test fun `clearing invalidates active private responses and app alerts`() = runBlocking {
        settings.boolean("notification_reading", true)
        val invalidated = async(start = CoroutineStart.UNDISPATCHED) { notifications.invalidations.first() }
        val old = notifications.currentRevision()
        notifications.clear()
        withTimeout(2_000) { invalidated.await() }
        assertTrue(notifications.currentRevision() > old)
        assertFalse(notifications.isCurrent(dev.jarvis.assistant.notifications.NotificationAnnouncement("Discord", System.currentTimeMillis(), old)))
    }

    @Test fun `device briefing counts visible notifications without reading private preview aloud`() = runBlocking {
        settings.boolean("notification_reading", true)
        notifications.connect { arrayOf(message()) }
        val answer = notifications.summary()
        assertTrue(answer.contains("1 active notification"))
        assertFalse(answer.contains("Alice"))
        assertFalse(answer.contains("Meet at noon"))
    }

    @Test fun `device briefing cannot infer notification counts before consent or while locked`() = runBlocking {
        notifications.connect { arrayOf(message()) }
        assertTrue(notifications.summary().contains("awareness is off"))
        assertTrue(dao.preferenceList(NotificationRepository.KIND).isEmpty())
        settings.boolean("notification_reading", true)
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        assertTrue(notifications.summary().contains("Unlock your phone"))
        assertTrue(dao.preferenceList(NotificationRepository.KIND).isEmpty())
    }

    @Test fun `briefing with no connected listener does not report zero unread messages`() = runBlocking {
        settings.boolean("notification_reading", true)
        assertTrue(notifications.summary().contains("not connected"))
    }
}
