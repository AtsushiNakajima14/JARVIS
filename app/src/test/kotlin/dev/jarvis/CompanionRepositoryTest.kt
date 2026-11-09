package dev.jarvis

import android.app.Application
import android.app.NotificationManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.jarvis.assistant.abilities.FileKnowledgeRepository
import dev.jarvis.assistant.abilities.ReminderRepository
import dev.jarvis.assistant.memory.MemoryRepository
import dev.jarvis.core.database.*
import dev.jarvis.core.security.TextCipher
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CompanionRepositoryTest {
    private lateinit var context: Application
    private lateinit var db: JarvisDatabase
    private lateinit var dao: JarvisDao
    private lateinit var reminders: ReminderRepository

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val codec = object : TextCipher {
            private val key = SecretKeySpec(ByteArray(32) { (it + 7).toByte() }, "AES")
            override fun encrypt(value: String): String {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
                return Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()))
            }
            override fun decrypt(value: String): String {
                val bytes = Base64.getDecoder().decode(value)
                return Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                }.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
            }
        }
        db = Room.inMemoryDatabaseBuilder(context, JarvisDatabase::class.java).addTypeConverter(SecretConverter(codec))
            .allowMainThreadQueries().build()
        dao = db.dao()
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        reminders = ReminderRepository(context, dao)
    }

    @After fun close() {
        WorkManager.getInstance(context).cancelAllWork().result.get()
        WorkManagerTestInitHelper.closeWorkDatabase()
        db.close()
    }

    @Test fun `task persists encrypted and background input holds only its id`() = runBlocking {
        val task = reminders.create("Discuss the private project", System.currentTimeMillis() + 60_000)
        assertEquals(task.title, ReminderRepository(context, dao).list().single().title)
        db.openHelper.readableDatabase.query("SELECT content FROM preferences WHERE kind = 'companion_reminder'").use {
            assertTrue(it.moveToFirst()); assertFalse(it.getString(0).contains("private project"))
        }
        val work = WorkManager.getInstance(context).getWorkInfosForUniqueWork(ReminderRepository.workName(task.id)).get().single()
        assertEquals(WorkInfo.State.ENQUEUED, work.state)
        val input = androidx.work.impl.WorkManagerImpl.getInstance(context).workDatabase.workSpecDao()
            .getWorkSpec(work.id.toString())!!.input
        assertEquals(setOf("reminder_id"), input.keyValueMap.keys)
        assertEquals(task.id, input.getString("reminder_id"))
    }
    @Test fun `completing a task cancels its pending work and prevents later delivery`() = runBlocking {
        val task = reminders.create("Call Sam", System.currentTimeMillis() + 60_000)
        assertTrue(reminders.completeId(task.id))
        assertTrue(reminders.list().single().completed)
        assertEquals(WorkInfo.State.CANCELLED, WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(ReminderRepository.workName(task.id)).get().single().state)
        assertTrue(reminders.deliver(task.id, task.dueAt + 1))
        assertEquals(0, shadowOf(context.getSystemService(NotificationManager::class.java)).size())
    }
    @Test fun `ambiguous completion leaves all reminders intact`() = runBlocking {
        reminders.create("Read project notes", System.currentTimeMillis() + 60_000)
        reminders.create("Read meeting notes", System.currentTimeMillis() + 60_000)
        assertTrue(reminders.complete("notes").contains("2 unfinished"))
        assertTrue(reminders.list().none { it.completed })
    }
    @Test fun `clear cancels work and preserves approved user memory`() = runBlocking {
        val task = reminders.create("Water plants", System.currentTimeMillis() + 60_000)
        MemoryRepository(dao).remember("My favorite color is blue")
        reminders.clear()
        assertTrue(reminders.list().isEmpty())
        assertEquals(1, dao.preferenceList("memory").size)
        assertTrue(reminders.deliver(task.id, Long.MAX_VALUE))
        assertEquals(WorkInfo.State.CANCELLED, WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(ReminderRepository.workName(task.id)).get().single().state)
    }
    @Test fun `reminders never display private titles in Android notifications`() = runBlocking {
        val task = reminders.create("Discuss private project Falcon", System.currentTimeMillis() + 60_000)
        assertTrue(reminders.deliver(task.id, task.dueAt + 1))
        val manager = shadowOf(context.getSystemService(NotificationManager::class.java))
        val notification = manager.allNotifications.single()
        assertEquals("Your reminder is due", notification.extras.getString(android.app.Notification.EXTRA_TITLE))
        assertFalse(notification.extras.toString().contains("Falcon"))
        assertTrue(reminders.list().single().notified)
    }
    @Test fun `approved memory views and forgetting never select internal records`() = runBlocking {
        val memory = MemoryRepository(dao)
        memory.remember("I use Messenger for friends")
        dao.remember(UserPreferenceEntity("internal", SecretText("Messenger private message"), "notification_message"))
        assertEquals(1, memory.approved().size)
        assertTrue(memory.forgetMatching("Messenger").contains("forgotten"))
        assertEquals(1, dao.preferenceList("notification_message").size)
        assertTrue(memory.approved().isEmpty())
    }
    @Test fun `files require persisted grants and stale grants remove metadata`() = runBlocking {
        val repository = FileKnowledgeRepository(context, dao)
        assertFalse(repository.add(Uri.parse("file:///sdcard/private.txt")))
        dao.remember(UserPreferenceEntity("old", SecretText("Old file.txt\ncontent://missing.provider/document/1"), "file"))
        assertTrue(repository.files().isEmpty())
        assertTrue(dao.preferenceList("file").isEmpty())
    }
    @Test fun `selected file metadata is encrypted and forgetting revokes its grant`() = runBlocking {
        val uri = Uri.parse("content://jarvis.test.documents/document/1")
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "application/pdf"
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
                MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("Private project.pdf")) }
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        }
        ShadowContentResolver.registerProviderInternal("jarvis.test.documents", provider)
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val repository = FileKnowledgeRepository(context, dao)
        assertTrue(repository.add(uri))
        val file = repository.files().single()
        assertEquals("Private project.pdf", file.name)
        assertTrue(file.important)
        db.openHelper.readableDatabase.query("SELECT content FROM preferences WHERE kind = 'companion_file'").use {
            assertTrue(it.moveToFirst()); assertFalse(it.getString(0).contains("Private project"))
        }
        repository.forget(file.id)
        assertTrue(repository.files().isEmpty())
        assertTrue(context.contentResolver.persistedUriPermissions.none { it.uri == uri && it.isReadPermission })
    }
    @Test fun `corrupt or unrelated records cannot become tasks or documents`() {
        assertNull(ReminderRepository.decode(UserPreferenceEntity("x", SecretText("secret"), "memory")))
        assertNull(ReminderRepository.decode(UserPreferenceEntity("x", SecretText("not json"), ReminderRepository.KIND)))
        assertNull(FileKnowledgeRepository.decode(UserPreferenceEntity("x", SecretText("content://example"), "memory")))
    }
    @Test fun `clearing files rejects an earlier document picker result`() = runBlocking {
        val repository = FileKnowledgeRepository(context, dao)
        val oldSelection = repository.currentRevision()
        repository.clear()
        assertFalse(repository.add(Uri.parse("content://jarvis.test.documents/document/old"), oldSelection))
        assertTrue(dao.preferenceList(FileKnowledgeRepository.KIND).isEmpty())
    }
}
