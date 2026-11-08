package dev.jarvis.skills.flashlight
import android.Manifest
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jarvis.assistant.parser.*
import dev.jarvis.core.permissions.PermissionStatus
import dev.jarvis.skills.*
import javax.inject.Inject
class FlashlightSkill @Inject constructor(@param:ApplicationContext private val context: Context) : AssistantSkill {
    override val name = "Flashlight"; override val description = "Switch the camera torch on or off."
    override val aliases = setOf("flashlight", "torch", "senter")
    override fun canHandle(command: ParsedCommand) = command.type in setOf(IntentType.FLASHLIGHT_ON, IntentType.FLASHLIGHT_OFF)
    override suspend fun execute(command: ParsedCommand): SkillResult {
        if (!PermissionStatus.granted(context, Manifest.permission.CAMERA)) return SkillResult("Allow Camera in Permissions to use the flashlight.")
        val manager = context.getSystemService(CameraManager::class.java)
        val camera = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return SkillResult("This device has no flashlight.")
        val enabled = command.type == IntentType.FLASHLIGHT_ON
        manager.setTorchMode(camera, enabled)
        return SkillResult("Flashlight ${if (enabled) "on" else "off"}.")
    }
}

