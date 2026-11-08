package dev.jarvis.skills.contacts
import android.Manifest
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jarvis.assistant.parser.normalize
import dev.jarvis.core.database.*
import dev.jarvis.core.permissions.PermissionStatus
import dev.jarvis.device.scanner.DeviceScanner
import javax.inject.Inject
class ContactResolver @Inject constructor(@param:ApplicationContext private val context: Context, private val dao: JarvisDao, private val scanner: DeviceScanner) {
    suspend fun resolve(name: String): ContactEntity? {
        if (!PermissionStatus.granted(context, Manifest.permission.READ_CONTACTS)) { dao.clearContacts(); return null }
        scanner.scanContacts()
        val candidates = dao.contacts().filter { normalize(it.name.text) == normalize(name) }.distinctBy { it.number.text.filter(Char::isDigit) }
        return candidates.singleOrNull()
    }
}

