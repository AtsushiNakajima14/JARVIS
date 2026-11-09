package dev.jarvis.skills

import dev.jarvis.assistant.parser.ParsedCommand
import dev.jarvis.skills.apps.*
import dev.jarvis.skills.battery.BatterySkill
import dev.jarvis.skills.camera.CameraSkill
import dev.jarvis.skills.contacts.CallSkill
import dev.jarvis.skills.flashlight.FlashlightSkill
import dev.jarvis.skills.messages.MessageSkill
import dev.jarvis.skills.music.MusicSkill
import dev.jarvis.skills.settings.SettingsSkill
import dev.jarvis.skills.system.*
import dev.jarvis.skills.timer.TimerSkill
import dev.jarvis.skills.volume.VolumeSkill
import dev.jarvis.skills.personal.PersonalAssistantSkill
import dev.jarvis.skills.companion.DeviceHealthAbility
import dev.jarvis.assistant.abilities.CompanionAbility
import dev.jarvis.skills.time.TimeAbility
import javax.inject.Inject

class AbilityRegistry @Inject constructor(open: OpenAppSkill, apps: ApplicationSkill, battery: BatterySkill,
    flashlight: FlashlightSkill, volume: VolumeSkill, camera: CameraSkill, call: CallSkill, message: MessageSkill,
    timer: TimerSkill, music: MusicSkill, settings: SettingsSkill, system: SystemSkill, memory: MemorySkill,
    personal: PersonalAssistantSkill, deviceHealth: DeviceHealthAbility, companion: CompanionAbility, clock: TimeAbility) {
    val abilities: List<AssistantAbility> = listOf(open, apps, battery, flashlight, volume, camera, call, message, timer, music, settings, system, memory, personal, deviceHealth, companion, clock)
    fun match(goal: ParsedCommand): AssistantAbility? = SkillMatcher.match(abilities, goal)
}
typealias SkillRegistry = AbilityRegistry
object SkillMatcher {
    fun match(skills: List<AssistantSkill>, command: ParsedCommand): AssistantSkill? =
        if (command.confidence >= 0.7) skills.singleOrNull { it.canHandle(command) } else null
}

