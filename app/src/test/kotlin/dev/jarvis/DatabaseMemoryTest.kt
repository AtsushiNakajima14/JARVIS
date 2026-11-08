package dev.jarvis

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.jarvis.assistant.memory.MemoryRepository
import dev.jarvis.core.database.*
import dev.jarvis.core.security.TextCipher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DatabaseMemoryTest {
    private lateinit var db: JarvisDatabase
    private lateinit var dao: JarvisDao
    @Before fun setup() {
        // Exercise real AES-GCM and Room; Android Keystore itself is verified on a device.
        val codec = object : TextCipher {
            private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
            override fun encrypt(value: String): String {
                val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
                return Base64.getEncoder().encodeToString(c.iv + c.doFinal(value.toByteArray()))
            }
            override fun decrypt(value: String): String {
                val bytes = Base64.getDecoder().decode(value)
                return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
            }
        }
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), JarvisDatabase::class.java).addTypeConverter(SecretConverter(codec)).allowMainThreadQueries().build()
        dao = db.dao()
    }
    @After fun close() { db.close() }
    @Test fun `history persists encrypted and round trips`() = runTest {
        dao.addHistory(CommandHistoryEntity(command = SecretText("call mom"), response = SecretText("Confirm?"), skill = "Call"))
        assertEquals("call mom", dao.history().first().single().command.text)
        db.openHelper.readableDatabase.query("SELECT command FROM history").use { cursor -> cursor.moveToFirst(); assertFalse(cursor.getString(0).contains("call mom")) }
    }
    @Test fun `replace index removes uninstalled apps`() = runTest {
        fun app(id: String) = ApplicationEntity(id, id, "Main", "Other", "1", 1, 1)
        dao.replaceApps(listOf(app("a"), app("b")))
        dao.replaceApps(listOf(app("b")))
        assertEquals(listOf("b"), dao.applicationList().map { it.packageName })
    }
    @Test fun `memory recall and deletion`() = runTest {
        val memory = MemoryRepository(dao)
        memory.remember("I use Discord for school")
        memory.remember("I use Spotify for music")
        assertEquals("I use Discord for school", memory.recall("How do I contact my school group?"))
        assertNull(memory.recall("Where is my bicycle?"))
        memory.clear()
        assertNull(memory.recall("school"))
    }
    @Test fun `clear all removes every indexed data type`() = runTest {
        dao.remember(UserPreferenceEntity("x", SecretText("private")))
        dao.putContacts(listOf(ContactEntity("1", SecretText("Mom"), SecretText("12345"))))
        dao.clearAll()
        assertTrue(dao.preferenceList().isEmpty()); assertTrue(dao.contacts().isEmpty())
    }
    @Test fun `deleting assistant memory does not remove timer recovery records`() = runTest {
        dao.remember(UserPreferenceEntity("timer:1", SecretText("123456789"), "timer"))
        val memory = MemoryRepository(dao)
        memory.remember("School uses Discord")
        memory.clear()
        assertTrue(dao.preferenceList("memory").isEmpty())
        assertEquals(1, dao.preferenceList("timer").size)
    }
    @Test fun `history retention limits to newest 200`() = runTest {
        repeat(205) { n -> dao.addHistory(CommandHistoryEntity(command = SecretText("$n"), response = SecretText("OK"), skill = "test", timestamp = n.toLong())) }
        dao.trimHistory()
        val history = dao.history().first()
        assertEquals(200, history.size); assertEquals("204", history.first().command.text); assertEquals("5", history.last().command.text)
    }
}

