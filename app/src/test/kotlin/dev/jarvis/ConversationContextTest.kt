package dev.jarvis

import dev.jarvis.assistant.response.ChatTurn
import dev.jarvis.assistant.response.ConversationContext
import org.junit.Assert.*
import org.junit.Test

class ConversationContextTest {
    @Test fun `clear increments revision and rejects an in flight old turn`() {
        val context = ConversationContext()
        val request = context.snapshot()
        assertTrue(context.recordTurn("Battery?", "72 percent.", request.revision))
        context.clear()
        assertEquals(request.revision + 1, context.currentRevision())
        assertFalse(context.recordTurn("Old private question", "Old answer", request.revision))
        assertTrue(context.snapshot().turns.isEmpty())
        assertTrue(context.recordTurn("New conversation", "Hello."))
        assertEquals(listOf(ChatTurn("New conversation", "Hello.")), context.snapshot().turns)
    }
    @Test fun `native query context is available to a follow up prompt`() {
        val context = ConversationContext()
        val revision = context.currentRevision()
        context.recordTurn("What is my battery percentage?", "Your battery is 72 percent.", revision)
        val prompt = dev.jarvis.assistant.response.LocalPrompt.build("Jarvis", "Tell me more", "Phone facts", null, context.snapshot().turns)
        assertTrue(prompt.contains("What is my battery percentage?"))
        assertTrue(prompt.contains("Your battery is 72 percent."))
    }
    @Test fun `duplicate callbacks do not duplicate the same turn and history is bounded`() {
        val context = ConversationContext(4)
        assertTrue(context.recordTurn("Hello", "Hi!"))
        assertTrue(context.recordTurn("Hello", "Hi!"))
        assertEquals(1, context.snapshot().turns.size)
        repeat(10) { context.recordTurn("Question $it", "Answer $it") }
        assertEquals(4, context.snapshot().turns.size)
        assertEquals("Question 6", context.snapshot().turns.first().user)
        assertEquals("Question 9", context.snapshot().turns.last().user)
    }
    @Test fun `snapshots stay immutable and personal text is bounded`() {
        val context = ConversationContext()
        val before = context.snapshot()
        assertFalse(context.recordTurn("", "No user text"))
        assertFalse(context.recordTurn("Question", ""))
        context.recordTurn("u".repeat(1000), "a".repeat(2000))
        assertTrue(before.turns.isEmpty())
        assertEquals(360, context.snapshot().turns.single().user.length)
        assertEquals(700, context.snapshot().turns.single().assistant.length)
    }
}
