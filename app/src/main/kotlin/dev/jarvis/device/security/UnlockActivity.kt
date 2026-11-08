package dev.jarvis.device.security

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.jarvis.ui.theme.JarvisTheme

/** Shows Android's own authentication UI. JARVIS never receives a device credential. */
class UnlockActivity : ComponentActivity() {
    private var requesting by mutableStateOf(false)
    private var detail by mutableStateOf("Use Android's secure prompt to unlock your phone. JARVIS does not read or save your PIN.")
    private var requestedAutomatically = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        setContent {
            JarvisTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.Center) {
                        Text("Unlock your phone", style = MaterialTheme.typography.headlineMedium)
                        Text(detail, modifier = Modifier.padding(vertical = 20.dp))
                        Button(onClick = ::requestUnlock, enabled = !requesting) { Text(if (requesting) "Waiting for Android" else "Show secure unlock") }
                        TextButton(onClick = { finish() }) { Text("Close") }
                    }
                }
            }
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        if (!requestedAutomatically) {
            requestedAutomatically = true
            requestUnlock()
        }
    }

    private fun requestUnlock() {
        if (isFinishing || isDestroyed || requesting) return
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isKeyguardLocked) { finish(); return }
        requesting = true
        try {
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { if (!isFinishing && !isDestroyed) finish() }
                override fun onDismissCancelled() { showResult("Unlock cancelled. Your phone stays locked.") }
                override fun onDismissError() { showResult("Android could not open the unlock prompt. Use your phone's lock screen, or try again.") }
            })
        } catch (error: Exception) {
            showResult("Android could not open the unlock prompt. Use your phone's lock screen, or try again.")
        }
    }

    private fun showResult(message: String) {
        if (isFinishing || isDestroyed) return
        requesting = false
        detail = message
    }
}
