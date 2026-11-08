package dev.jarvis.core.database

import androidx.room.*
import dev.jarvis.core.security.TextCipher
import kotlinx.coroutines.flow.Flow

data class SecretText(val text: String)
@ProvidedTypeConverter
class SecretConverter(private val cipher: TextCipher) {
    @TypeConverter fun encode(value: SecretText): String = cipher.encrypt(value.text)
    @TypeConverter fun decode(value: String): SecretText = SecretText(cipher.decrypt(value))
}
@Entity(tableName = "applications")
data class ApplicationEntity(@PrimaryKey val packageName: String, val label: String, val activity: String,
    val category: String, val version: String, val installedAt: Long, val updatedAt: Long)
@Entity(tableName = "device_info")
data class DeviceInfoEntity(@PrimaryKey val key: String, val value: SecretText, val scannedAt: Long)
@Entity(tableName = "contacts")
data class ContactEntity(@PrimaryKey val id: String, val name: SecretText, val number: SecretText)
@Entity(tableName = "history")
data class CommandHistoryEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0,
    val command: SecretText, val response: SecretText, val skill: String, val timestamp: Long = System.currentTimeMillis())
@Entity(tableName = "preferences")
data class UserPreferenceEntity(@PrimaryKey val id: String, val content: SecretText, val kind: String = "memory", val createdAt: Long = System.currentTimeMillis())

@Dao
interface JarvisDao {
    @Query("SELECT * FROM applications ORDER BY label COLLATE NOCASE") fun applications(): Flow<List<ApplicationEntity>>
    @Query("SELECT * FROM applications ORDER BY label COLLATE NOCASE") suspend fun applicationList(): List<ApplicationEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putApps(apps: List<ApplicationEntity>)
    @Query("DELETE FROM applications") suspend fun clearApps()
    @Transaction suspend fun replaceApps(apps: List<ApplicationEntity>) { clearApps(); putApps(apps) }
    @Query("SELECT * FROM device_info ORDER BY `key`") fun deviceInfo(): Flow<List<DeviceInfoEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putInfo(info: List<DeviceInfoEntity>)
    @Query("SELECT * FROM contacts") suspend fun contacts(): List<ContactEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putContacts(contacts: List<ContactEntity>)
    @Query("DELETE FROM contacts") suspend fun clearContacts()
    @Transaction suspend fun replaceContacts(contacts: List<ContactEntity>) { clearContacts(); putContacts(contacts) }
    @Query("SELECT * FROM history ORDER BY timestamp DESC LIMIT 200") fun history(): Flow<List<CommandHistoryEntity>>
    @Insert suspend fun addHistory(history: CommandHistoryEntity)
    @Query("DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY timestamp DESC LIMIT 200)") suspend fun trimHistory()
    @Query("DELETE FROM history") suspend fun clearHistory()
    @Query("SELECT * FROM preferences ORDER BY createdAt DESC") fun preferences(): Flow<List<UserPreferenceEntity>>
    @Query("SELECT * FROM preferences WHERE kind = :kind ORDER BY createdAt DESC") suspend fun preferenceList(kind: String = "memory"): List<UserPreferenceEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun remember(preference: UserPreferenceEntity)
    @Query("DELETE FROM preferences WHERE id = :id") suspend fun forget(id: String)
    @Query("DELETE FROM preferences WHERE kind = :kind") suspend fun clearKind(kind: String)
    @Query("DELETE FROM preferences") suspend fun clearMemory()
    @Query("DELETE FROM device_info") suspend fun clearInfo()
    @Transaction suspend fun clearAll() { clearApps(); clearContacts(); clearHistory(); clearMemory(); clearInfo() }
}
@Database(entities = [ApplicationEntity::class, DeviceInfoEntity::class, ContactEntity::class, CommandHistoryEntity::class, UserPreferenceEntity::class], version = 1, exportSchema = true)
@TypeConverters(SecretConverter::class)
abstract class JarvisDatabase : RoomDatabase() { abstract fun dao(): JarvisDao }

