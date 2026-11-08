package dev.jarvis.skills.contacts
import android.content.Intent
import android.net.Uri
import dev.jarvis.assistant.parser.*
import dev.jarvis.skills.*
import javax.inject.Inject
class CallSkill @Inject constructor(private val contacts: ContactResolver) : AssistantSkill {
    override val name = "Call"; override val description = "Confirm a contact and open the phone dialer."
    override val aliases = setOf("call", "dial", "tawagan")
    override fun canHandle(command: ParsedCommand) = command.type == IntentType.CALL
    override suspend fun execute(command: ParsedCommand): SkillResult {
        val contact = contacts.resolve(command.argument) ?: return SkillResult("I need one exact contact and phone number. Allow Contacts in Permissions, then use their full saved name.")
        val prompt = "Call ${contact.name.text}, number ending ${contact.number.text.filter(Char::isDigit).takeLast(4)}? Say confirm or cancel."
        return SkillResult(prompt, Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", contact.number.text, null)), prompt)
    }
}

