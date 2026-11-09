package dev.jarvis.assistant.abilities

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jarvis.assistant.parser.normalize
import dev.jarvis.core.database.JarvisDao
import dev.jarvis.core.database.SecretText
import dev.jarvis.core.database.UserPreferenceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

data class KnownFile(val id: String, val name: String, val uri: String, val mimeType: String,
    val modifiedAt: Long, val important: Boolean)

/** Bounded metadata only. Documents are selected by the owner; file bodies are never indexed. */
@Singleton
class FileKnowledgeRepository @Inject constructor(@param:ApplicationContext private val context: Context,
    private val dao: JarvisDao) {
    private val mutation = Mutex()
    private val revision = AtomicLong()
    fun currentRevision(): Long = revision.get()
    val knownFiles = dao.preferences().map { rows ->
        val allowed = grantedUris()
        rows.mapNotNull(::decode).filter { it.uri in allowed }.sortedByDescending { it.modifiedAt }
    }

    suspend fun files(): List<KnownFile> = mutation.withLock {
        val allowed = grantedUris()
        val rows = savedFiles()
        rows.filter { it.uri !in allowed }.forEach { dao.forget(it.id) }
        rows.filter { it.uri in allowed }.sortedByDescending { it.modifiedAt }
    }

    /** A persisted read grant is required; accepting metadata is itself explicit document approval. */
    suspend fun add(uri: Uri, expectedRevision: Long = currentRevision()): Boolean = mutation.withLock {
        if (expectedRevision != currentRevision()) return@withLock false
        if (uri.scheme != "content" || uri.toString().length > 8192) return@withLock false
        withContext(Dispatchers.IO) {
            val existing = savedFiles()
            if (existing.none { it.uri == uri.toString() } && existing.size >= MAX_SAVED) return@withContext false
            val previouslyGranted = uri.toString() in grantedUris()
            var saved = false
            try {
                runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                if (uri.toString() !in grantedUris()) return@withContext false
                val metadata = readMetadata(uri) ?: return@withContext false
                val id = existing.firstOrNull { it.uri == uri.toString() }?.id ?: "document:${UUID.randomUUID()}"
                val value = JSONObject().put("name", metadata.first.take(180)).put("uri", uri.toString())
                    .put("mime", runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: "application/octet-stream")
                    .put("modified", metadata.second).put("important", true)
                if (expectedRevision != currentRevision()) return@withContext false
                dao.remember(UserPreferenceEntity(id, SecretText(value.toString()), KIND))
                saved = true
                true
            } finally {
                if (!saved && !previouslyGranted) runCatching {
                    context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        }
    }

    suspend fun search(query: String): List<KnownFile> {
        val saved = files()
        val accessible = withContext(Dispatchers.IO) { mediaStoreFiles() }
        return (saved + accessible).distinctBy { it.uri }.filter { FileKnowledgePolicy.matches(query, it.name) }
            .sortedByDescending { it.modifiedAt }.take(20)
    }

    suspend fun forget(id: String) = mutation.withLock {
        val item = savedFiles().singleOrNull { it.id == id } ?: return@withLock
        withContext(NonCancellable + Dispatchers.IO) {
            dao.forget(item.id)
            runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(item.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    suspend fun clear() {
        revision.incrementAndGet()
        mutation.withLock { withContext(NonCancellable + Dispatchers.IO) {
            val old = savedFiles()
            dao.clearKind(KIND)
            dao.clearKind("file")
            old.forEach { runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(it.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        } }
    }

    private suspend fun savedFiles(): List<KnownFile> =
        (dao.preferenceList(KIND) + dao.preferenceList("file")).mapNotNull(::decode)

    private fun grantedUris(): Set<String> = context.contentResolver.persistedUriPermissions
        .filter { it.isReadPermission }.map { it.uri.toString() }.toSet()

    private fun readMetadata(uri: Uri): Pair<String, Long>? = runCatching {
        val document = DocumentsContract.isDocumentUri(context, uri)
        val columns = if (document) arrayOf(OpenableColumns.DISPLAY_NAME, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            else arrayOf(OpenableColumns.DISPLAY_NAME)
        context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val name = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: return@use null
            val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val modified = if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) cursor.getLong(modifiedIndex) else System.currentTimeMillis()
            name to modified.coerceAtLeast(0)
        }
    }.getOrNull()

    private fun mediaStoreFiles(): List<KnownFile> {
        val result = mutableListOf<KnownFile>()
        val collection = MediaStore.Files.getContentUri("external")
        // Scoped storage exposes only allowed rows; older devices can reject this query.
        // No storage/all-files permission is requested to expand access.
        runCatching {
            context.contentResolver.query(collection, arrayOf(MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.DATE_MODIFIED), null, null,
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC")?.use { cursor ->
                var visited = 0
                while (visited++ < 200 && cursor.moveToNext()) {
                    val name = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                    val uri = ContentUris.withAppendedId(collection, cursor.getLong(0)).toString()
                    result += KnownFile("media:${cursor.getLong(0)}", name.take(180), uri,
                        cursor.getString(2) ?: "application/octet-stream", cursor.getLong(3) * 1000, false)
                }
            }
        }
        return result
    }

    companion object {
        const val KIND = "companion_file"
        const val MAX_SAVED = 100
        fun decode(row: UserPreferenceEntity): KnownFile? = runCatching {
            if (row.kind == "file") {
                val uri = row.content.text.substringAfterLast('\n')
                if (!uri.startsWith("content://")) return null
                return KnownFile(row.id, row.content.text.substringBefore('\n').take(180), uri,
                    "application/octet-stream", row.createdAt, true)
            }
            if (row.kind != KIND) return null
            val value = JSONObject(row.content.text)
            val uri = value.getString("uri")
            if (!uri.startsWith("content://")) return null
            KnownFile(row.id, value.getString("name").take(180), uri, value.optString("mime", "application/octet-stream"),
                value.getLong("modified"), value.optBoolean("important", true))
        }.getOrNull()
    }
}

object FileKnowledgePolicy {
    fun matches(query: String, name: String): Boolean {
        val tokens = normalize(query).take(160).split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
        val filename = normalize(name)
        return tokens.all { filename.contains(it) }
    }
}
