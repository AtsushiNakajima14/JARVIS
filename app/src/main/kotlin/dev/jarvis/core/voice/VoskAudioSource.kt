package dev.jarvis.core.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jarvis.core.permissions.PermissionStatus
import kotlinx.coroutines.*
import java.io.IOException
import javax.inject.Inject

/** One microphone owner; native audio failures propagate to the service's retry policy. */
class VoskAudioSource @Inject constructor(@param:ApplicationContext private val context: Context) {
    @SuppressLint("MissingPermission") // Permission is checked immediately before constructing AudioRecord.
    suspend fun <T : Any> recordFrames(timeoutMillis: Long? = null, onReady: () -> Unit = {},
        process: (ShortArray, Int) -> T?): T? = withContext(Dispatchers.IO) { MicrophoneLease.exclusive {
        check(PermissionStatus.microphone(context)) { "Microphone permission revoked" }
        val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) throw IOException("Unsupported microphone format")
        val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 64000))
        val buffer = ShortArray(3200)
        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) throw IOException("Microphone could not initialize")
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IOException("Microphone could not start")
            currentCoroutineContext().ensureActive()
            onReady()
            val began = SystemClock.elapsedRealtime()
            while (timeoutMillis == null || SystemClock.elapsedRealtime() - began < timeoutMillis) {
                currentCoroutineContext().ensureActive()
                val count = recorder.read(buffer, 0, buffer.size)
                currentCoroutineContext().ensureActive()
                if (count < 0) throw IOException("Microphone read failed: $count")
                if (count == 0) { delay(20); continue }
                process(buffer, count)?.let { return@exclusive it }
            }
            null
        } finally {
            buffer.fill(0)
            runCatching { if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop() }
            recorder.release()
        }
    } }
}
