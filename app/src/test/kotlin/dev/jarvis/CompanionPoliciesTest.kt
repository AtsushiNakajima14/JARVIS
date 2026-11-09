package dev.jarvis

import dev.jarvis.assistant.abilities.FileKnowledgePolicy
import dev.jarvis.assistant.abilities.LocalReminder
import dev.jarvis.assistant.abilities.ReminderPolicy
import dev.jarvis.assistant.memory.MemoryCategory
import dev.jarvis.assistant.memory.MemoryRepository
import dev.jarvis.assistant.memory.MemorySelection
import dev.jarvis.core.database.SecretText
import dev.jarvis.core.database.UserPreferenceEntity
import org.junit.Assert.*
import org.junit.Test

class CompanionPoliciesTest {
    private fun memory(id: String, text: String) = UserPreferenceEntity(id, SecretText(text))

    @Test fun `approved memories have useful local categories`() {
        assertEquals(MemoryCategory.PROFILE, MemoryRepository.category("My name is CyCy"))
        assertEquals(MemoryCategory.PREFERENCE, MemoryRepository.category("I use Messenger for chatting"))
        assertEquals(MemoryCategory.ROUTINE, MemoryRepository.category("I exercise every morning"))
        assertEquals(MemoryCategory.IMPORTANT, MemoryRepository.category("My important project is JARVIS"))
    }
    @Test fun `forget matching never treats pronouns or all as authority to delete`() {
        val rows = listOf(memory("one", "I use Messenger"))
        listOf("", "this", "that", "all", "everything", "the memory").forEach {
            assertTrue(it, MemorySelection.matches(it, rows).isEmpty())
        }
    }
    @Test fun `forget uses exact first and preserves ambiguity`() {
        val rows = listOf(memory("one", "I use Messenger for friends"), memory("two", "I use Messenger for school"))
        assertEquals(2, MemorySelection.matches("Messenger", rows).size)
        assertEquals("two", MemorySelection.matches("I use Messenger for school", rows).single().id)
        assertTrue(MemorySelection.matches("Messnger", rows).isEmpty())
    }
    @Test fun `reminder dates must be future and bounded`() {
        val now = 1_000_000_000L
        assertFalse(ReminderPolicy.validDueTime(now, now))
        assertFalse(ReminderPolicy.validDueTime(now - 1, now))
        assertTrue(ReminderPolicy.validDueTime(now + 60_000, now))
        assertFalse(ReminderPolicy.validDueTime(Long.MAX_VALUE, now))
    }
    @Test fun `task completion cannot fuzzy select or complete all`() {
        val rows = listOf(LocalReminder("one", "Call Alex", 123), LocalReminder("two", "Call Alex at school", 456))
        assertEquals("one", ReminderPolicy.matches("Call Alex", rows).single().id)
        assertEquals(2, ReminderPolicy.matches("Alex", rows).size)
        assertTrue(ReminderPolicy.matches("all", rows).isEmpty())
        assertTrue(ReminderPolicy.matches("Alix", rows).isEmpty())
    }
    @Test fun `file matching handles case extensions and multiple filename words`() {
        assertTrue(FileKnowledgePolicy.matches("project pdf", "JARVIS Project Notes.PDF"))
        assertFalse(FileKnowledgePolicy.matches("secret budget", "JARVIS Project Notes.PDF"))
        assertTrue(FileKnowledgePolicy.matches("", "myfile.txt"))
    }
}
