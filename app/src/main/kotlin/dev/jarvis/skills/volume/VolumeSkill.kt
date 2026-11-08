package dev.jarvis.skills.volume
import android.content.Context
import android.media.AudioManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jarvis.assistant.parser.*
import dev.jarvis.skills.*
import javax.inject.Inject
class VolumeSkill @Inject constructor(@param:ApplicationContext private val context: Context) : AssistantSkill {
    override val name = "Volume"; override val description = "Adjust media volume."
    override val aliases = setOf("volume", "lakasan", "hinaan")
    override fun canHandle(command: ParsedCommand) = command.type in setOf(IntentType.VOLUME_UP, IntentType.VOLUME_DOWN, IntentType.VOLUME_SET)
    override suspend fun execute(command: ParsedCommand): SkillResult {
        val audio = context.getSystemService(AudioManager::class.java)
        if (audio.isVolumeFixed) return SkillResult("This device has fixed volume.")
        if (command.type == IntentType.VOLUME_SET) audio.setStreamVolume(AudioManager.STREAM_MUSIC, (audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * command.value.coerceIn(0, 100) / 100).toInt(), 0)
        else audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (command.type == IntentType.VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
        return SkillResult("Volume adjusted.")
    }
}

