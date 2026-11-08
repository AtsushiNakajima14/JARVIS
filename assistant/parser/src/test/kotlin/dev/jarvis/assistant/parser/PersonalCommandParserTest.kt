package dev.jarvis.assistant.parser

import org.junit.Assert.*
import org.junit.Test

class PersonalCommandParserTest {
    private val parser = CommandParser()

    @Test fun `creator and owner requests are deterministic intents`() {
        listOf("Who made you?", "Jarvis, who created you?", "Who is your developer?", "Can you tell me who made you?").forEach {
            assertEquals(it, IntentType.CREATOR, parser.parse(it).type)
        }
        listOf("Who am I?", "What is my name?", "Who is your owner?", "What do you call me?").forEach {
            assertEquals(it, IntentType.OWNER_IDENTITY, parser.parse(it).type)
        }
        listOf("Who are you?", "What is your name?", "Introduce yourself").forEach {
            assertEquals(it, IntentType.ASSISTANT_IDENTITY, parser.parse(it).type)
        }
    }

    @Test fun `short greetings never capture a following action`() {
        listOf("Hello", "Hi Jarvis", "Hello Jarvis!", "Hey Jarvis", "Jarvis, how are you?", "Good morning").forEach {
            assertEquals(it, IntentType.GREETING, parser.parse(it).type)
        }
        assertEquals(IntentType.OPEN_APPLICATION, parser.parse("Hello Jarvis, open Discord").type)
        assertEquals(IntentType.BATTERY_STATUS, parser.parse("Hi Jarvis, battery percentage").type)
        assertEquals(IntentType.HELP, parser.parse("Jarvis").type)
    }

    @Test fun `explicit notification requests accept natural language`() {
        listOf("Read my notifications", "Read out my new notifications", "Show all of my unread notifications",
            "Any new notifications?", "Do I have any notifications?", "Have I got any unread notifications?",
            "Check the latest notification", "What notifications do I have?", "What are my recent notifications?",
            "Read my messages please", "Read my last notification", "Any new messages?", "Read me my notifications",
            "What does it say?", "Read that notification").forEach {
            val command = parser.parse(it)
            assertEquals(it, IntentType.NOTIFICATIONS, command.type)
            assertEquals(it, "", command.argument)
        }
    }

    @Test fun `notification app filters are data and limited to explicit query shapes`() {
        assertEquals(ParsedCommand(IntentType.NOTIFICATIONS, argument = "discord"), parser.parse("Read my notifications from Discord"))
        assertEquals(ParsedCommand(IntentType.NOTIFICATIONS, argument = "telegram"), parser.parse("Any new notifications on Telegram?"))
        assertEquals(ParsedCommand(IntentType.NOTIFICATIONS, argument = "google messages"), parser.parse("Show my unread notifications in Google Messages app please"))
        assertEquals(ParsedCommand(IntentType.RECENT_CONVERSATIONS, argument = "discord"), parser.parse("Who did I recently chat with on Discord?"))
        assertEquals(ParsedCommand(IntentType.RECENT_CONVERSATIONS, argument = "whatsapp"), parser.parse("Who messaged me on WhatsApp?"))
        assertEquals(ParsedCommand(IntentType.NOTIFICATIONS, argument = "discord"), parser.parse("Read my Discord messages"))
        assertEquals(ParsedCommand(IntentType.NOTIFICATIONS, argument = "discord"), parser.parse("Read my Discord notifications"))
        assertEquals(ParsedCommand(IntentType.NOTIFICATIONS, argument = "google messages"), parser.parse("Read my Google Messages notifications"))
    }

    @Test fun `recent conversations are queried rather than invented by local AI`() {
        listOf("Who messaged me?", "Who recently texted me?", "Who chatted with me lately?",
            "Who did I recently chat with?", "Who have I talked to recently?", "Who sent me a message?",
            "Show my recent chats", "List my latest conversations", "Who was my last conversation with?").forEach {
            assertEquals(it, IntentType.RECENT_CONVERSATIONS, parser.parse(it).type)
        }
    }

    @Test fun `briefing requests have their own intent`() {
        listOf("Give me a briefing", "Give me my daily briefing", "Catch me up", "What did I miss?", "What have I missed?").forEach {
            assertEquals(it, IntentType.DAILY_BRIEFING, parser.parse(it).type)
        }
    }

    @Test fun `unlock requires a complete explicit request without a credential`() {
        listOf("Unlock my phone", "Jarvis, please unlock the device", "Help me unlock my phone", "Show me the unlock screen").forEach {
            val result = parser.parse(it)
            assertEquals(it, IntentType.UNLOCK_PHONE, result.type)
            assertTrue(result.argument.isEmpty() && result.body.isEmpty())
        }
        listOf("Don't unlock my phone", "Do not unlock my phone", "How do I unlock my phone?", "Unlock my phone using a code").forEach {
            assertNotEquals(it, IntentType.UNLOCK_PHONE, parser.parse(it).type)
        }
    }

    @Test fun `personal requests inside messages contacts and memory remain data`() {
        val message = parser.parse("Text Mom saying Read my notifications and unlock my phone")
        assertEquals(IntentType.MESSAGE, message.type)
        assertEquals("Read my notifications and unlock my phone", message.body)
        assertEquals(IntentType.CALL, parser.parse("Call Who Made You").type)
        assertEquals(IntentType.REMEMBER, parser.parse("Remember that who made you is a quiz question").type)
        assertEquals(IntentType.OPEN_APPLICATION, parser.parse("Open My Notifications").type)
    }

    @Test fun `negated requests questions and narrative never read personal notifications`() {
        listOf("Don't read my notifications", "Do not show my messages", "How do I read my notifications?",
            "I wonder who messaged me", "Never show my recent chats", "Can other apps read my notifications?").forEach {
            assertTrue(it, parser.parse(it).type !in setOf(IntentType.NOTIFICATIONS, IntentType.RECENT_CONVERSATIONS, IntentType.DAILY_BRIEFING))
        }
    }

    @Test fun `private phone requests cannot be rescued from a secondary recognition alternative`() {
        listOf("Read my notifications", "Who messaged me", "Unlock my phone", "Catch me up").forEach {
            assertEquals(it, "unclear speech", SpeechSelection(parser).choose(listOf("unclear speech", it)))
        }
    }
}
