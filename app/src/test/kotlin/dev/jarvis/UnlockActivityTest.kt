package dev.jarvis

import android.app.Application
import android.app.KeyguardManager
import androidx.test.core.app.ApplicationProvider
import dev.jarvis.device.security.UnlockActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UnlockActivityTest {
    private val keyguard: KeyguardManager
        get() = ApplicationProvider.getApplicationContext<Application>().getSystemService(KeyguardManager::class.java)

    @Test fun `requesting the Android prompt does not unlock a secure phone`() {
        shadowOf(keyguard).setIsKeyguardSecure(true)
        shadowOf(keyguard).setKeyguardLocked(true)
        val controller = Robolectric.buildActivity(UnlockActivity::class.java).setup()
        try {
            assertTrue(keyguard.isKeyguardLocked)
            assertTrue(keyguard.isKeyguardSecure)
            assertFalse(controller.get().isFinishing)
            // Only Android's authentication result is allowed to finish the flow.
            shadowOf(keyguard).setKeyguardLocked(false)
            assertTrue(controller.get().isFinishing)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `cancelled authentication leaves the device locked`() {
        shadowOf(keyguard).setIsKeyguardSecure(true)
        shadowOf(keyguard).setKeyguardLocked(true)
        val controller = Robolectric.buildActivity(UnlockActivity::class.java).setup()
        try {
            shadowOf(keyguard).setKeyguardLocked(true) // Robolectric sends onDismissCancelled.
            assertTrue(keyguard.isKeyguardLocked)
            assertFalse(controller.get().isFinishing)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `an already unlocked device needs no further authentication flow`() {
        shadowOf(keyguard).setKeyguardLocked(false)
        val controller = Robolectric.buildActivity(UnlockActivity::class.java).setup()
        try { assertTrue(controller.get().isFinishing) }
        finally { controller.pause().stop().destroy() }
    }
}
