package dev.jarvis.assistant.memory

import dev.jarvis.core.database.*
import dev.jarvis.assistant.parser.normalize
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryRepository @Inject constructor(private val dao: JarvisDao) {
    val memories = dao.preferences()
    suspend fun remember(text: String, kind: String = "memory") {
        require(text.isNotBlank())
        dao.remember(UserPreferenceEntity(UUID.randomUUID().toString(), SecretText(text.take(2000)), kind))
    }
    suspend fun recall(query: String): String? {
        val ignored = setOf("what", "how", "that", "this", "with", "have", "does", "from", "your", "remember", "contact", "group")
        val tokens = normalize(query).split(Regex("[^a-z0-9]+" )).filter { it.length > 2 && it !in ignored }.toSet()
        if (tokens.isEmpty()) return null
        return dao.preferenceList().map { memory ->
            memory.content.text to normalize(memory.content.text).split(Regex("[^a-z0-9]+")).toSet().intersect(tokens).size
        }.filter { it.second > 0 }.maxByOrNull { it.second }?.first
    }
    suspend fun forget(id: String) = dao.forget(id)
    suspend fun clear() = dao.clearKind("memory")
}

