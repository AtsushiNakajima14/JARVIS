package dev.jarvis.skills.camera
import android.content.Intent
import android.provider.MediaStore
import dev.jarvis.assistant.parser.*
import dev.jarvis.skills.*
import javax.inject.Inject
class CameraSkill @Inject constructor() : AssistantSkill {
    override val name = "Camera"; override val description = "Open the device camera."
    override val aliases = setOf("camera")
    override fun canHandle(command: ParsedCommand) = command.type == IntentType.CAMERA
    override suspend fun execute(command: ParsedCommand) = SkillResult("Opening the camera.", Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
}

