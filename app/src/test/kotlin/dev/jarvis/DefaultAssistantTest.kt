package dev.jarvis

import android.app.Application
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import dev.jarvis.core.common.AssistantSession
import dev.jarvis.core.device.ActionLauncher
import dev.jarvis.device.assistant.DefaultAssistant
import dev.jarvis.device.assistant.JarvisVoiceInteractionService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DefaultAssistantTest {
    private lateinit var context: Application
    private lateinit var controller: ServiceController<JarvisVoiceInteractionService>
    private lateinit var session: AssistantSession
    private lateinit var launcher: ActionLauncher

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        Settings.Secure.putString(context.contentResolver, "voice_interaction_service", "")
        controller = Robolectric.buildService(JarvisVoiceInteractionService::class.java).create()
        session = AssistantSession()
        launcher = ActionLauncher(context, session)
    }
    @After fun close() { controller.destroy() }

    private fun selectAssistant() {
        Settings.Secure.putString(context.contentResolver, "voice_interaction_service",
            ComponentName(context, JarvisVoiceInteractionService::class.java).flattenToString())
        controller.get().onReady()
    }

    @Test fun `selected and system-ready assistant opens a background action without a notification`() = runBlocking {
        selectAssistant()
        val original = Intent(context, MainActivity::class.java).setAction("test.open")
        assertNull(launcher.launch(original))
        val started = shadowOf(context).nextStartedActivity
        assertEquals("test.open", started.action)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals(0, original.flags)
        assertEquals(0, shadowOf(context.getSystemService(NotificationManager::class.java)).size())
    }

    @Test fun `role selection alone does not claim a system-ready voice binding`() {
        Settings.Secure.putString(context.contentResolver, "voice_interaction_service",
            ComponentName(context, JarvisVoiceInteractionService::class.java).flattenToString())
        assertTrue(DefaultAssistant.isSelected(context))
        assertFalse(DefaultAssistant.canOpenFromBackground(context))
        controller.get().onReady()
        assertTrue(DefaultAssistant.canOpenFromBackground(context))
        controller.get().onShutdown()
        assertFalse(DefaultAssistant.canOpenFromBackground(context))
    }

    @Test fun `a locked phone never uses the default-assistant background launch path`() = runBlocking {
        selectAssistant()
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        assertFalse(DefaultAssistant.canOpenFromBackground(context))
        assertNotNull(launcher.launch(Intent(context, MainActivity::class.java)))
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test fun `foreground delivery stays in the visible activity`() = runBlocking {
        session.visible = true
        val action = Intent(context, MainActivity::class.java)
        assertNull(launcher.launch(action))
        assertEquals(action, session.launches.receive())
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test fun `unselected assistant explains setup and keeps the tap fallback`() = runBlocking {
        val response = launcher.launch(Intent(context, MainActivity::class.java))
        assertTrue(response!!.contains("default digital assistant"))
        assertEquals(1, shadowOf(context.getSystemService(NotificationManager::class.java)).size())
        assertNull(shadowOf(context).nextStartedActivity)
    }
}
