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
    val networkSpeech: Boolean = true, val history: Boolean = true, val memory: Boolean = true, val notifications: Boolean = false,
    val localAi: Boolean = true, val conversation: Boolean = true)

internal fun assistantSettingsFrom(preferences: Preferences): AssistantSettings = with(preferences) {
    AssistantSettings(this[booleanPreferencesKey("wake")] ?: true, this[booleanPreferencesKey("background")] ?: true,
        this[stringPreferencesKey("name")] ?: "Jarvis", this[floatPreferencesKey("speed")] ?: 1f,
        this[floatPreferencesKey("pitch")] ?: 1f, this[stringPreferencesKey("language")] ?: "system",
        this[stringPreferencesKey("voice")] ?: "", this[booleanPreferencesKey("network")] ?: true,
        this[booleanPreferencesKey("history")] ?: true, this[booleanPreferencesKey("memory")] ?: true,
        this[booleanPreferencesKey("notifications")] ?: false, this[booleanPreferencesKey("local_ai")] ?: true,
        this[booleanPreferencesKey("conversation")] ?: true)
}

@Singleton
class SettingsStore @Inject constructor(@param:ApplicationContext private val context: Context) {
    val settings: Flow<AssistantSettings> = context.settingsData.data.catch {
        if (it is IOException) emit(emptyPreferences()) else throw it
    }.map(::assistantSettingsFrom)
    suspend fun boolean(key: String, value: Boolean) { context.settingsData.edit { it[booleanPreferencesKey(key)] = value } }
    suspend fun text(key: String, value: String) { context.settingsData.edit { it[stringPreferencesKey(key)] = value.take(80) } }
    suspend fun number(key: String, value: Float) { context.settingsData.edit { it[floatPreferencesKey(key)] = value.coerceIn(0.5f, 2f) } }
    suspend fun reset() { context.settingsData.edit { it.clear() } }
}

