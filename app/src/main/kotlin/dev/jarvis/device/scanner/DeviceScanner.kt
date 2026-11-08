package dev.jarvis.device.scanner

import android.Manifest
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jarvis.core.database.*
import dev.jarvis.core.device.DeviceInspector
import dev.jarvis.core.permissions.PermissionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceScanner @Inject constructor(@param:ApplicationContext private val context: Context, private val dao: JarvisDao, private val inspector: DeviceInspector) {
    suspend fun scanDevice() = withContext(Dispatchers.IO) {
        dao.putInfo(inspector.snapshot().map { DeviceInfoEntity(it.key, SecretText(it.value), System.currentTimeMillis()) })
    }
    suspend fun scanContacts(): Int = withContext(Dispatchers.IO) {
        if (!PermissionStatus.granted(context, Manifest.permission.READ_CONTACTS)) { dao.clearContacts(); return@withContext 0 }
        val found = mutableListOf<ContactEntity>()
        context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone._ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) found += ContactEntity(cursor.getString(0), SecretText(cursor.getString(1) ?: "Unnamed"), SecretText(cursor.getString(2) ?: ""))
        }
        dao.replaceContacts(found)
        found.size
    }
    suspend fun scanCalendar(): Int = withContext(Dispatchers.IO) {
        dao.clearKind("calendar")
        if (!PermissionStatus.granted(context, Manifest.permission.READ_CALENDAR)) return@withContext 0
        val now = System.currentTimeMillis()
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        android.content.ContentUris.appendId(builder, now)
        android.content.ContentUris.appendId(builder, now + 7 * 86400000L)
        var count = 0
        context.contentResolver.query(builder.build(), arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN), null, null, CalendarContract.Instances.BEGIN)?.use { c ->
            while (c.moveToNext() && count < 100) {
                dao.remember(UserPreferenceEntity("calendar:${c.getLong(0)}:${c.getLong(2)}", SecretText("${c.getString(1)} · ${java.util.Date(c.getLong(2))}"), "calendar"))
                count++
            }
        }
        count
    }
    suspend fun addFile(uri: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) dao.remember(UserPreferenceEntity("file:${uri.toString().hashCode()}", SecretText("${cursor.getString(0)} · ${cursor.getLong(1)} bytes\n$uri"), "file"))
        }
    }
}

