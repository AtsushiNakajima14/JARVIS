package dev.jarvis.skills.messages
import android.content.Intent
import android.net.Uri
import dev.jarvis.assistant.parser.*
import dev.jarvis.skills.*
import dev.jarvis.skills.contacts.ContactResolver
import javax.inject.Inject
class MessageSkill @Inject constructor(private val contacts: ContactResolver) : AssistantSkill {
    override val name = "Message"; override val description = "Prepare a message after confirmation; sending happens in your messaging app."
    override val aliases = setOf("message", "text", "i-text")
    override fun canHandle(command: ParsedCommand) = command.type == IntentType.MESSAGE
    override suspend fun execute(command: ParsedCommand): SkillResult {
        if (command.body.isBlank()) return SkillResult("Include the message: send a message to John saying I will be there.")
        val contact = contacts.resolve(command.argument) ?: return SkillResult("I need one exact contact and phone number. Allow Contacts, then use their full saved name.")
        val prompt = "Message ${contact.name.text}: ${command.body}. Open this message? Say confirm or cancel."
        return SkillResult(prompt, Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", contact.number.text, null)).putExtra("sms_body", command.body), prompt)
    }
}

