package dev.jarvis.skills.music
import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import android.provider.Settings
import android.view.KeyEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jarvis.assistant.parser.*
import dev.jarvis.device.scanner.NotificationIndexer
import dev.jarvis.skills.*
import javax.inject.Inject
class MusicSkill @Inject constructor(@param:ApplicationContext private val context: Context) : AssistantSkill {
    override val name = "Music"; override val description = "Control the active media session with notification access."
    override val aliases = setOf("play", "pause", "next", "previous")
    override fun canHandle(command: ParsedCommand) = command.type in setOf(IntentType.MUSIC_PLAY, IntentType.MUSIC_PAUSE, IntentType.MUSIC_NEXT, IntentType.MUSIC_PREVIOUS)
    override suspend fun execute(command: ParsedCommand): SkillResult {
        val component = ComponentName(context, NotificationIndexer::class.java)
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")?.split(':')?.contains(component.flattenToString()) == true
        if (!enabled) return SkillResult("Enable Notification access in Permissions to control your music.")
        val controller = context.getSystemService(MediaSessionManager::class.java).getActiveSessions(component).firstOrNull()
            ?: return SkillResult("Start music in your player first.")
        val key = when (command.type) { IntentType.MUSIC_PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY; IntentType.MUSIC_PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE; IntentType.MUSIC_NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT; else -> KeyEvent.KEYCODE_MEDIA_PREVIOUS }
        controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        return SkillResult("Done.")
    }
}

