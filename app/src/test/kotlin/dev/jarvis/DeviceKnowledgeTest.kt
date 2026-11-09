package dev.jarvis

import android.app.Application
import android.app.AppOpsManager
import android.app.KeyguardManager
import android.content.Intent
import android.os.BatteryManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.jarvis.core.database.JarvisDatabase
import dev.jarvis.core.database.SecretConverter
import dev.jarvis.core.device.DeviceInspector
import dev.jarvis.core.security.TextCipher
import dev.jarvis.device.applications.ApplicationRepository
import dev.jarvis.device.knowledge.ApplicationUsage
import dev.jarvis.device.knowledge.DeviceHealthAdvice
import dev.jarvis.device.knowledge.DeviceKnowledge
import dev.jarvis.device.knowledge.DeviceKnowledgeRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

class DeviceHealthAdviceTest {
    private fun phone() = DeviceKnowledge("Test phone", "15", 45, false,
        1_000_000_000L, 3_000_000_000L, false, 20_000_000_000L, 64_000_000_000L,
        "Wi-Fi connected", "Permission not enabled", 12, false, emptyList(), 1L)

    @Test fun `slow phone advice distinguishes pressure from normal available memory`() {
        val normal = DeviceHealthAdvice.describe(phone())
        assertTrue(normal.contains("free RAM alone does not tell us why"))
        assertFalse(normal.contains("reports memory pressure"))
        val pressure = DeviceHealthAdvice.describe(phone().copy(lowMemory = true))
        assertTrue(pressure.contains("reports memory pressure"))
        assertTrue(pressure.contains("cannot inspect every app's running processes"))
        assertFalse(pressure.contains("I closed"))
    }

    @Test fun `low storage suggestion depends on actual free space`() {
        assertFalse(DeviceHealthAdvice.describe(phone()).contains("Storage is nearly full"))
        assertTrue(DeviceHealthAdvice.describe(phone().copy(freeStorage = 100L)).contains("Storage is nearly full"))
    }

    @Test fun `unavailable readings do not invent empty battery or disk`() {
        val answer = DeviceHealthAdvice.describe(phone().copy(batteryPercent = null, freeStorage = null, totalStorage = null))
        assertTrue(answer.contains("Battery reading unavailable"))
        assertTrue(answer.contains("Storage information is unavailable"))
        assertFalse(answer.contains("Battery is 0"))
        assertFalse(answer.contains("Storage is nearly full"))
    }

    @Test fun `usage names require permission and are described as historical`() {
        val usage = listOf(ApplicationUsage("chat.example", "Example Chat", 60_000, 1))
        assertFalse(DeviceHealthAdvice.describe(phone().copy(frequentApps = usage)).contains("Example Chat"))
        val answer = DeviceHealthAdvice.describe(phone().copy(usageAllowed = true, frequentApps = usage))
        assertTrue(answer.contains("over the last week include Example Chat"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DeviceKnowledgeRepositoryTest {
    private lateinit var context: Application
    private lateinit var database: JarvisDatabase
    private lateinit var repository: DeviceKnowledgeRepository

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context.getSystemService(AppOpsManager::class.java)).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName, AppOpsManager.MODE_IGNORED)
        // This suite checks native fact capture; Keystore/AES behavior has dedicated tests.
        val testCipher = object : TextCipher {
            override fun encrypt(value: String) = value.reversed()
            override fun decrypt(value: String) = value.reversed()
        }
        database = Room.inMemoryDatabaseBuilder(context, JarvisDatabase::class.java)
            .addTypeConverter(SecretConverter(testCipher)).allowMainThreadQueries().build()
        repository = DeviceKnowledgeRepository(context, database.dao(), ApplicationRepository(context, database.dao()), DeviceInspector(context))
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
    }

    @After fun close() { database.close() }

    @Suppress("DEPRECATION")
    @Test fun `native battery reading is persisted with a timestamp and missing Bluetooth permission is explicit`() = runBlocking {
        context.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_LEVEL, 45).putExtra(BatteryManager.EXTRA_SCALE, 100)
            .putExtra(BatteryManager.EXTRA_PLUGGED, 0))
        val result = repository.refresh()
        assertEquals(45, result.batteryPercent)
        assertEquals("Permission not enabled", result.bluetooth)
        assertFalse(result.usageAllowed)
        assertEquals(result, repository.snapshot.value)
        val stored = database.dao().deviceInfo().first()
        assertTrue(stored.first { it.key == "Battery" }.value.text.contains("45%"))
        assertTrue(stored.all { it.scannedAt == result.measuredAt })
        repository.clearSnapshot()
        assertNull(repository.snapshot.value)
    }

    @Test fun `locked refresh cannot populate UI snapshot or return usage details`() = runBlocking {
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        val result = repository.refresh()
        assertNull(repository.snapshot.value)
        assertFalse(result.usageAllowed)
        assertTrue(result.frequentApps.isEmpty())
        assertTrue(database.dao().deviceInfo().first().first { it.key == "App usage (7 days)" }.value.text.contains("Unlock your phone"))
    }
}
