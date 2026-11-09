package dev.jarvis.assistant.memory

import dev.jarvis.core.database.*
import dev.jarvis.assistant.parser.normalize
import dev.jarvis.core.security.PrivateCommandPolicy
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryRepository @Inject constructor(private val dao: JarvisDao) {
    private val mutation = Mutex()
    val memories = dao.preferences()
    suspend fun remember(text: String, kind: String = "memory") = mutation.withLock {
        require(text.isNotBlank())
        require(!PrivateCommandPolicy.isCredentialRequest("remember $text")) { "Credentials cannot be saved as memory." }
        require(kind != "memory" || dao.preferenceList("memory").size < 200) { "Memory is full. Forget an older item first." }
        dao.remember(UserPreferenceEntity(UUID.randomUUID().toString(), SecretText(text.take(2000)), kind))
    }
    /** Only explicit user-approved memories, never notification/task/document internal records. */
    suspend fun approved(): List<UserPreferenceEntity> = dao.preferenceList("memory")

    suspend fun describe(): String {
        val rows = approved()
        if (rows.isEmpty()) return "You haven't asked me to remember anything yet. Tell me what you would like me to remember."
        return rows.take(5).joinToString(" ") { "${category(it.content.text).label}: ${it.content.text.take(150)}." } +
            if (rows.size > 5) " There are ${rows.size - 5} more in your memory viewer." else ""
    }

    suspend fun forgetMatching(query: String): String = mutation.withLock {
        val rows = approved()
        val matches = if (query == "__latest__") rows.take(1) else MemorySelection.matches(query, rows)
        when (matches.size) {
            0 -> "I couldn't identify a saved memory to forget. Tell me which fact, or choose it in the memory viewer."
            1 -> { dao.forget(matches.single().id); "I've forgotten that saved memory." }
            else -> "That matches ${matches.size} memories. Tell me the specific fact, or delete it in the memory viewer."
        }
    }
    suspend fun recall(query: String): String? {
        val ignored = setOf("what", "how", "that", "this", "with", "have", "does", "from", "your", "remember", "contact", "group")
        val tokens = normalize(query).split(Regex("[^a-z0-9]+" )).filter { it.length > 2 && it !in ignored }.toSet()
        if (tokens.isEmpty()) return null
        return dao.preferenceList().map { memory ->
            memory.content.text to normalize(memory.content.text).split(Regex("[^a-z0-9]+")).toSet().intersect(tokens).size
        }.filter { it.second > 0 }.maxByOrNull { it.second }?.first
    }
    suspend fun forget(id: String) = mutation.withLock { dao.forget(id) }
    suspend fun clear() = mutation.withLock { dao.clearKind("memory") }

    companion object {
        fun category(text: String): MemoryCategory {
            val value = normalize(text)
            return when {
                Regex("\\b(my name|i am called|call me|birthday|i live|my age)\\b").containsMatchIn(value) -> MemoryCategory.PROFILE
                Regex("\\b(every|daily|usually|morning|evening|routine|weekdays|weekends)\\b").containsMatchIn(value) -> MemoryCategory.ROUTINE
                Regex("\\b(i use|i prefer|i like|favorite|favourite|prefer|for chatting)\\b").containsMatchIn(value) -> MemoryCategory.PREFERENCE
                else -> MemoryCategory.IMPORTANT
            }
        }
    }
}

enum class MemoryCategory(val label: String) {
    PROFILE("User profile"), PREFERENCE("Preference"), ROUTINE("Routine"), IMPORTANT("Important information")
}

object MemorySelection {
    fun matches(query: String, memories: List<UserPreferenceEntity>): List<UserPreferenceEntity> {
        val value = normalize(query).trim().take(2000)
        if (value.isBlank() || value in setOf("this", "that", "it", "all", "everything", "my memories")) return emptyList()
        val exact = memories.filter { normalize(it.content.text) == value }
        if (exact.isNotEmpty()) return exact
        val tokens = value.split(Regex("[^a-z0-9]+")).filter { it.length > 2 && it !in setOf("the", "that", "about", "memory", "forget", "please", "saved", "remember") }
        if (tokens.isEmpty()) return emptyList()
        return memories.filter { row ->
            val words = normalize(row.content.text).split(Regex("[^a-z0-9]+")).toSet()
            tokens.all { it in words }
        }
    }
}

