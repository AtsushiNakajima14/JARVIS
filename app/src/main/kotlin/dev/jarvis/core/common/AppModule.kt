package dev.jarvis.core.common

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.jarvis.core.database.*
import dev.jarvis.core.security.LocalCipher
import dev.jarvis.assistant.parser.CommandParser
import dev.jarvis.assistant.wakeword.WakeWordEngine
import dev.jarvis.assistant.wakeword.VoskWakeWordEngine
import javax.inject.Singleton

@Module @InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton fun database(@ApplicationContext context: Context, cipher: LocalCipher): JarvisDatabase =
        Room.databaseBuilder(context, JarvisDatabase::class.java, "jarvis.db")
            .addTypeConverter(SecretConverter(cipher)).build()
    @Provides fun dao(db: JarvisDatabase): JarvisDao = db.dao()
    @Provides @Singleton fun parser(): CommandParser = CommandParser()
    @Provides fun wakeWord(engine: VoskWakeWordEngine): WakeWordEngine = engine
}

