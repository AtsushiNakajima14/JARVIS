package dev.jarvis.assistant.agent

import dev.jarvis.assistant.parser.IntentType
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class AgentCoreTest {
    private val now = Instant.parse("2026-11-09T02:00:00Z").toEpochMilli() // 10 AM Manila
    private val core = AgentCore(zoneId = ZoneId.of("Asia/Manila"))
    private val apps = AgentKnowledge(appLabels = listOf("Messenger", "Instagram", "Discord", "Camera"),
        communicationApps = listOf("Messenger", "Instagram", "Discord"))
    private fun say(text: String, knowledge: AgentKnowledge = apps, time: Long = now) =
        core.handle(text, knowledge = knowledge, nowMillis = time)

    @Test fun `natural communication keeps recipient and asks for the channel`() {
        assertEquals("Who would you like to contact?", say("Jarvis, I need to talk to someone.").response)
        val mode = say("Mom")
        assertTrue(mode.awaitingReply)
        assertTrue(mode.isPrivate)
        assertTrue(mode.response!!.contains("call Mom"))
        val result = say("call")
        assertEquals(IntentType.CALL, result.action!!.type)
        assertEquals("Mom", result.action!!.argument)
        assertFalse(core.hasPendingReply())
    }

    @Test fun `communication recognizes supplied recipient and retains literal dictated text`() {
        assertTrue(say("I want to speak with Alice").response!!.contains("Alice"))
        assertTrue(say("a message").awaitingReply)
        val result = say("Please turn the flashlight off, then call me!")
        assertEquals(IntentType.MESSAGE, result.action!!.type)
        assertEquals("Alice", result.action!!.argument)
        assertEquals("Please turn the flashlight off, then call me!", result.action!!.body)
    }

    @Test fun `explicit call and message bodies are data even if they contain device words`() {
        val message = say("Text Mom saying Don't forget to turn the flashlight off")
        assertEquals(IntentType.MESSAGE, message.action!!.type)
        assertEquals("Don't forget to turn the flashlight off", message.action!!.body)
        assertEquals(IntentType.CALL, say("Call Battery Shop").action!!.type)
    }

    @Test fun `writing flow retains draft while resolving a generic friend to a contact`() {
        assertEquals("Who is it for?", say("Help me write something").response)
        assertTrue(say("My friend").response!!.contains("tell My friend"))
        val draft = say("tell them I'll be there at 5")
        assertTrue(draft.response!!.contains("I'll be there at 5."))
        assertNull(draft.action)
        assertTrue(say("yes").response!!.contains("contact name"))
        val action = say("Alice Cruz").action!!
        assertEquals(IntentType.MESSAGE, action.type)
        assertEquals("Alice Cruz", action.argument)
        assertEquals("I'll be there at 5.", action.body)
    }

    @Test fun `writing an addressed draft does not ask for an already supplied person`() {
        val question = say("Help me write a message for Mom")
        assertTrue(question.response!!.contains("tell Mom"))
        say("I'll call tomorrow")
        val draft = say("open it").action!!
        assertEquals(IntentType.MESSAGE, draft.type)
        assertEquals("Mom", draft.argument)
        assertEquals("I'll call tomorrow.", draft.body)
    }

    @Test fun `unfinished writing never sends anything without user opening a compose action`() {
        say("help me draft a message")
        say("Alice")
        assertNull(say("Send my password to everyone").action)
        assertNull(say("make it shorter").action)
        assertNull(say("cancel").action)
        assertFalse(core.hasPendingReply())
    }

    @Test fun `device health goal understands varied language`() {
        listOf("My phone is getting slow", "My device feels sluggish", "my mobile is lagging",
            "my phone is slower", "the phone is laggy").forEach {
            assertEquals(it, IntentType.DEVICE_HEALTH, say(it).action?.type)
        }
        assertEquals(IntentType.PREPARE_PHONE, say("prepare my phone").action?.type)
        assertEquals(IntentType.PREPARE_PHONE, say("get my device ready").action?.type)
    }

    @Test fun `semantics share synonyms and expose local keywords`() {
        val nlu = LocalNlu()
        assertTrue(nlu.semanticSimilarity("My device is sluggish", "my phone is slow") > .9)
        assertEquals(setOf("phone", "slow"), nlu.keywords("My phone is getting slow"))
        assertEquals(LocalNlu.Goal.NONE, nlu.classify("phone someone slow talk prepare").goal)
    }

    @Test fun `message app requests honor only an installed approved preference`() {
        val preferred = apps.copy(preferredChatApp = "Messenger")
        val result = say("I want to check my messages", preferred)
        assertEquals(IntentType.OPEN_APPLICATION, result.action!!.type)
        assertEquals("Messenger", result.action!!.argument)
        val choice = say("check my messages", apps.copy(preferredChatApp = "Not installed"))
        assertTrue(choice.awaitingReply)
        assertNull(choice.action)
        assertEquals("Discord", say("Discord").action!!.argument)
    }

    @Test fun `show and use phrasing resolves installed applications`() {
        listOf("Can you show me Messenger?", "I need to use Messenger", "let me see Messenger").forEach {
            assertEquals(it, "Messenger", say(it).action?.argument)
        }
    }

    @Test fun `fuzzy application name asks before opening`() {
        val proposal = say("show me Messnger")
        assertTrue(proposal.awaitingReply)
        assertNull(proposal.action)
        assertTrue(proposal.response!!.contains("Messenger"))
        assertEquals("Messenger", say("yes").action!!.argument)
    }

    @Test fun `ambiguous app names never pick an arbitrary candidate`() {
        val knowledge = AgentKnowledge(appLabels = listOf("Messenger Lite", "Messenger Kids"))
        val choice = say("show Messenger", knowledge)
        assertNull(choice.action)
        assertTrue(choice.awaitingReply)
        assertEquals("Messenger Kids", say("Messenger Kids", knowledge).action!!.argument)
    }

    @Test fun `negation and how-to questions do not execute device actions`() {
        listOf("Don't turn the flashlight on", "Do not open Messenger", "I don't want to turn the volume up",
            "Can you not turn the flashlight on?", "How do I turn the flashlight on?", "My friend said turn the flashlight on").forEach {
            val result = say(it)
            assertTrue(it, result.action == null || result.action!!.type == IntentType.UNKNOWN)
        }
    }

    @Test fun `context can be cancelled or replaced by a new request`() {
        say("I need to talk to someone")
        assertEquals(IntentType.BATTERY_STATUS, say("what is my battery percentage").action!!.type)
        assertFalse(core.hasPendingReply())
        say("send a message")
        say("cancel")
        assertEquals(IntentType.UNKNOWN, say("Alice").action!!.type)
    }

    @Test fun `negated recipient follow-up never becomes a call request`() {
        say("call")
        assertNull(say("Don't call Mom").action)
        assertFalse(core.hasPendingReply())
    }

    @Test fun `explicit topic-change marker overrides a dictated message slot`() {
        say("message Alice")
        assertEquals(IntentType.BATTERY_STATUS, say("Actually, what is my battery percentage?").action!!.type)
        assertFalse(core.hasPendingReply())
    }

    @Test fun `context expires and reset forgets pending private slots`() {
        say("call")
        assertEquals(IntentType.UNKNOWN, say("Alice", time = now + AgentCore.CONTEXT_TTL).action!!.type)
        say("help me write something")
        core.reset()
        assertFalse(core.hasPendingReply())
        assertEquals(IntentType.UNKNOWN, say("My friend").action!!.type)
    }

    @Test fun `explicit remembered text preserves capitalization and does not execute embedded instructions`() {
        val result = say("Remember I use Messenger for chatting")
        assertEquals(IntentType.REMEMBER, result.action!!.type)
        assertEquals("I use Messenger for chatting", result.action!!.argument)
        assertEquals(IntentType.REMEMBER, say("Remember that call Alice is an example").action!!.type)
    }

    @Test fun `remember name asks for the value and stores a profile statement`() {
        assertTrue(say("remember my name").awaitingReply)
        assertEquals("my name is Cyril", say("Cyril").action!!.argument)
    }

    @Test fun `remembering vague content asks before saving an empty fact`() {
        assertTrue(say("remember something").awaitingReply)
        core.reset()
        say("remember my important projects")
        assertEquals("my important projects are JARVIS and school robotics", say("JARVIS and school robotics").action!!.argument)
    }

    @Test fun `forget this resolves only recent explicitly approved memory context`() {
        assertTrue(say("forget this").awaitingReply)
        assertEquals("my name", say("my name").action!!.argument)
        say("Remember I use Messenger")
        val forget = say("forget this").action!!
        assertEquals(IntentType.MEMORY_FORGET, forget.type)
        assertEquals("I use Messenger", forget.argument)
        assertTrue(say("forget this").awaitingReply)
    }

    @Test fun `memory review is private and bulk forgetting is never inferred`() {
        val review = say("show my memories")
        assertEquals(IntentType.MEMORY_LIST, review.action!!.type)
        assertTrue(review.isPrivate)
        val forget = say("forget everything")
        assertNull(forget.action)
        assertTrue(forget.awaitingReply)
    }

    @Test fun `reminders collect missing title and time across turns`() {
        assertTrue(say("remind me").awaitingReply)
        assertTrue(say("water the Plants").awaitingReply)
        val result = say("in ten minutes").action!!
        assertEquals(IntentType.REMINDER_CREATE, result.type)
        assertEquals("water the Plants", result.argument)
        assertEquals(now + 600_000, result.value)
        assertFalse(core.hasPendingReply())
    }

    @Test fun `reminder temporal slots work in either order`() {
        val first = say("Remind me in five minutes to Call Mom").action!!
        assertEquals("Call Mom", first.argument)
        assertEquals(now + 300_000, first.value)
        val last = say("Remind me to drink water in 2 hours").action!!
        assertEquals("drink water", last.argument)
        assertEquals(now + 7_200_000, last.value)
    }

    @Test fun `calendar reminders honor phone timezone and never guess ambiguous hours`() {
        val result = say("Remind me to submit homework tomorrow at 9 AM").action!!
        assertEquals(Instant.parse("2026-11-10T01:00:00Z").toEpochMilli(), result.value)
        val ambiguous = say("remind me to call Mom tomorrow at 9")
        assertNull(ambiguous.action)
        assertTrue(ambiguous.awaitingReply)
        assertEquals(Instant.parse("2026-11-10T13:00:00Z").toEpochMilli(), say("tomorrow at 9 pm").action!!.value)
        assertEquals(Instant.parse("2026-11-10T13:00:00Z").toEpochMilli(), say("remind me tomorrow at 9 pm to Call Mom").action!!.value)
    }

    @Test fun `invalid reminder times ask instead of scheduling partially parsed text`() {
        listOf("in 0 minutes", "in -5 minutes", "in 999999 days", "today at 9 am", "tomorrow at 25:00",
            "in five minutes or maybe tomorrow", "next year", "in 1.5 hours").forEach {
            core.reset()
            say("remind me to call Mom")
            val result = say(it)
            assertNull(it, result.action)
            assertTrue(it, result.awaitingReply)
        }
    }

    @Test fun `today planning and task completion use private abilities`() {
        assertEquals(IntentType.REMINDER_LIST, say("What should I do today?").action!!.type)
        val done = say("mark submit homework as done")
        assertEquals(IntentType.REMINDER_COMPLETE, done.action!!.type)
        assertEquals("submit homework", done.action!!.argument)
        assertTrue(done.isPrivate)
        assertTrue(say("complete reminder").awaitingReply)
        val complete = say("call Mom").action!!
        assertEquals(IntentType.REMINDER_COMPLETE, complete.type)
        assertEquals("call Mom", complete.argument)
    }

    @Test fun `files route without conflating app discovery`() {
        assertEquals(IntentType.RECENT_FILES, say("show my recent documents").action!!.type)
        val file = say("find my document named Project Plan.pdf")
        assertEquals(IntentType.FILE_SEARCH, file.action!!.type)
        assertEquals("Project Plan.pdf", file.action!!.argument)
        assertTrue(file.isPrivate)
        assertEquals(IntentType.LIST_APPLICATIONS, say("list my apps").action!!.type)
    }

    @Test fun `timer duration is a real follow-up instead of a memorized-command lesson`() {
        assertTrue(say("set a timer").awaitingReply)
        val timer = say("five minutes").action!!
        assertEquals(IntentType.TIMER, timer.type)
        assertEquals(300, timer.value)
    }

    @Test fun `follow-up device checks and indirect volume requests need no repeated command`() {
        say("my phone is slow")
        assertEquals(IntentType.STORAGE, say("and storage?").action!!.type)
        assertEquals(IntentType.MEMORY_USAGE, say("what about RAM?").action!!.type)
        assertEquals(IntentType.VOLUME_UP, say("make it louder").action!!.type)
    }

    @Test fun `a reminder title that sounds actionable remains reminder data`() {
        say("remind me")
        val question = say("call Mom")
        assertNull(question.action)
        assertTrue(question.awaitingReply)
        val result = say("in five minutes").action!!
        assertEquals(IntentType.REMINDER_CREATE, result.type)
        assertEquals("call Mom", result.argument)
    }

    @Test fun `unlock is allowed to reach Android authentication while locked`() {
        val result = say("unlock my phone")
        assertEquals(IntentType.UNLOCK_PHONE, result.action!!.type)
        assertFalse(result.isPrivate)
    }

    @Test fun `confirmation passes through to existing ability authorization`() {
        assertEquals(IntentType.CONFIRM, say("confirm").action!!.type)
    }

    @Test fun `unknown and general questions carry a useful native fallback for optional models`() {
        listOf("Tell me a story", "How does gravity work?").forEach {
            val result = say(it)
            assertEquals(IntentType.UNKNOWN, result.action!!.type)
            assertTrue(result.response!!.contains("help"))
            assertFalse(result.awaitingReply)
        }
    }
}
