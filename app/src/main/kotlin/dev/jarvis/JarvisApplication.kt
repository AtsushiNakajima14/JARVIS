package dev.jarvis

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.jarvis.device.applications.ApplicationIndexSync
import javax.inject.Inject

@HiltAndroidApp
class JarvisApplication : Application() {
    @Inject lateinit var applicationIndexSync: ApplicationIndexSync
    override fun onCreate() { super.onCreate(); applicationIndexSync.start(this) }
}

