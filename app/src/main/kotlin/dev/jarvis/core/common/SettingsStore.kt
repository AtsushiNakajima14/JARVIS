package dev.jarvis.core.common

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsData by preferencesDataStore("jarvis_settings")
data class AssistantSettings(val wakeWord: Boolean = true, val background: Boolean = true, val name: String = "Jarvis",
    val speed: Float = 1f, val pitch: Float = 1f, val language: String = "system", val voice: String = "",
    val networkSpeech: Boolean = false, val history: Boolean = true, val memory: Boolean = true, val notifications: Boolean = false)

@Singleton
class SettingsStore @Inject constructor(@param:ApplicationContext private val context: Context) {
    val settings: Flow<AssistantSettings> = context.settingsData.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map { p ->
        AssistantSettings(p[booleanPreferencesKey("wake")] ?: true, p[booleanPreferencesKey("background")] ?: true,
            p[stringPreferencesKey("name")] ?: "Jarvis", p[floatPreferencesKey("speed")] ?: 1f,
            p[floatPreferencesKey("pitch")] ?: 1f, p[stringPreferencesKey("language")] ?: "system",
            p[stringPreferencesKey("voice")] ?: "", p[booleanPreferencesKey("network")] ?: false,
            p[booleanPreferencesKey("history")] ?: true, p[booleanPreferencesKey("memory")] ?: true,
            p[booleanPreferencesKey("notifications")] ?: false)
    }
    suspend fun boolean(key: String, value: Boolean) { context.settingsData.edit { it[booleanPreferencesKey(key)] = value } }
    suspend fun text(key: String, value: String) { context.settingsData.edit { it[stringPreferencesKey(key)] = value.take(80) } }
    suspend fun number(key: String, value: Float) { context.settingsData.edit { it[floatPreferencesKey(key)] = value.coerceIn(0.5f, 2f) } }
    suspend fun reset() { context.settingsData.edit { it.clear() } }
}

