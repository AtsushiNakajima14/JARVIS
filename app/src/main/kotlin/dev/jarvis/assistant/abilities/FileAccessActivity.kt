package dev.jarvis.assistant.abilities

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/** Android owns selection and grants. Voice requests never grant broad storage access. */
@AndroidEntryPoint
class FileAccessActivity : ComponentActivity() {
    @Inject lateinit var files: FileKnowledgeRepository
    private var selectionRevision = 0L
    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) { finish(); return@registerForActivityResult }
        val data = result.data
        val uris = buildList {
            data?.data?.let(::add)
            data?.clipData?.let { clips -> repeat(minOf(clips.itemCount, 20)) { add(clips.getItemAt(it).uri) } }
        }.distinct().take(20)
        lifecycleScope.launch {
            var saved = 0
            try {
                uris.forEach {
                    try { if (files.add(it, selectionRevision)) saved++ }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { /* A revoked or unavailable provider must not crash the picker. */ }
                }
            }
            finally {
                Toast.makeText(this@FileAccessActivity, if (saved == 0) "No file metadata saved. Choose a local document with lasting access."
                    else "$saved documents added to JARVIS. Their contents stay in the original files.", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectionRevision = savedInstanceState?.getLong("file_selection_revision") ?: files.currentRevision()
        if (savedInstanceState == null) {
            try {
                picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
            } catch (_: android.content.ActivityNotFoundException) {
                Toast.makeText(this, "No Android document picker is available.", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("file_selection_revision", selectionRevision)
        super.onSaveInstanceState(outState)
    }
}
