package dev.jarvis.assistant.notifications

import java.util.Locale

/** Only notification previews supplied by Android, never another app's chat database. */
data class NotificationRecord(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String = "",
    val preview: String = "",
    val sender: String = "",
    val postedAt: Long,
    val messaging: Boolean = false,
    val contentHidden: Boolean = false
)

data class NotificationAnnouncement(val appLabel: String, val postedAt: Long, val revision: Long) {
    val text: String get() = "New notification from $appLabel."
}

/** Conservative: security/financial notices stay app-only, including in encrypted history. */
object NotificationPolicy {
    const val MAX_RECORDS = 200
    const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000
    const val ANNOUNCEMENT_COOLDOWN_MS = 30_000L
    private val sensitive = Regex(
        "\\b(?:otp|pin|password|passcode|verification|verify|security code|authentication|authenticator|" +
            "one[ -]?time|two[ -]?factor|2fa|recovery code|access code|login code|log[ -]?in code|" +
            "sign[ -]?in code|account number|credit card|debit card|transaction|bank|balance|" +
            "reset password|sensitive notification|content hidden|unlock to view)\\b", RegexOption.IGNORE_CASE)
    private val code = Regex("(?<![\\p{L}\\p{N}])\\d{4,8}(?![\\p{L}\\p{N}])")
    private val secretPackage = Regex("(?:authenticator|authy|password|lastpass|1password|bitwarden|bank|paypal|wallet)", RegexOption.IGNORE_CASE)
    private val genericSender = Regex("^(?:(?:\\d+ )?(?:new )?(?:messages?|notifications?|chats?)(?: .*)?|you|me|yourself|myself)$", RegexOption.IGNORE_CASE)

    fun clean(value: CharSequence?, maximum: Int): String = value?.toString().orEmpty().take(maximum * 3)
        .replace(Regex("[\\p{Cc}\\p{Cf}]"), " ").replace(Regex("\\s+"), " ").trim().take(maximum)

    fun hidesContent(packageName: String, vararg text: String): Boolean = secretPackage.containsMatchIn(packageName) ||
        text.any { sensitive.containsMatchIn(it) || code.containsMatchIn(it) }

    fun validSender(sender: String): Boolean = sender.isNotBlank() && !genericSender.matches(sender) &&
        !hidesContent("", sender)

    fun matchesApp(record: NotificationRecord, filter: String): Boolean {
        val query = normalize(filter).removeSuffix(" app").removeSuffix(" application").trim()
        if (query.isBlank()) return true
        val label = normalize(record.appLabel)
        val id = record.packageName.lowercase(Locale.ROOT)
        return label == query || id == query || label.split(' ').contains(query) ||
            label.replace(" ", "") == query.replace(" ", "") ||
            id.split('.').contains(query.replace(" ", ""))
    }

    fun recent(records: List<NotificationRecord>, now: Long): List<NotificationRecord> = records
        .filter { it.postedAt in (now - RETENTION_MS)..(now + 60_000) }
        .sortedByDescending { it.postedAt }.take(MAX_RECORDS)

    fun notificationAnswer(records: List<NotificationRecord>, filter: String): String {
        val selected = records.filter { matchesApp(it, filter) }.sortedByDescending { it.postedAt }
        if (selected.isEmpty()) return if (filter.isBlank()) "There are no captured active notifications to read."
            else "There are no captured active notifications from ${clean(filter, 80)}."
        val details = selected.take(3).joinToString(" ") { record ->
            val app = record.appLabel.take(40)
            when {
                record.contentHidden -> "$app: private or security content is hidden."
                record.preview.isNotBlank() -> "$app${record.sender.takeIf { it.isNotBlank() }?.let { ", ${it.take(40)}" }.orEmpty()}: ${record.preview.take(110)}."
                record.title.isNotBlank() -> "$app: ${record.title.take(120)}."
                else -> "$app: a notification without a readable preview."
            }
        }
        return "From your active notification previews: $details" + if (selected.size > 3) " There are ${selected.size - 3} more." else ""
    }

    fun recentChatsAnswer(records: List<NotificationRecord>, filter: String): String {
        val all = records.asSequence().filter { it.messaging && !it.contentHidden && validSender(it.sender) &&
            !it.sender.equals(it.appLabel, true) && matchesApp(it, filter) }
            .sortedByDescending { it.postedAt }.distinctBy { it.packageName to it.sender.lowercase(Locale.ROOT) }.toList()
        val senders = all.take(5)
        if (senders.isEmpty()) return "I have no captured messaging notifications${if (filter.isBlank()) "" else " from ${clean(filter, 80)}"} with a sender name. I can only use notification previews received with permission; I cannot read full chat histories."
        return "Recent messaging notifications came from " + senders.joinToString { "${it.sender.take(40)} on ${it.appLabel.take(40)}" } +
            ". " + (if (all.size > 5) "There are ${all.size - 5} more correspondents. " else "") +
            "This covers captured incoming notifications, not your full chat history or messages you sent."
    }

    private fun normalize(value: String) = value.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}.]+"), " ").trim()
}

/** Bounded process-local active state and update deduplication. Caller serializes access. */
internal class NotificationLedger {
    private val active = linkedMapOf<String, NotificationRecord>()
    private val seen = linkedMapOf<String, NotificationRecord>()
    private val announcedAt = linkedMapOf<String, Long>()
    var clearedAt: Long = 0
        private set

    data class Change(val changed: Boolean, val announce: Boolean)

    fun update(record: NotificationRecord, announce: Boolean, now: Long): Change {
        if (record.postedAt <= clearedAt) return Change(false, false)
        val previous = seen[record.key]
        active[record.key] = record
        trim(active)
        seen[record.key] = record
        trim(seen)
        val contentChanged = previous == null || previous.copy(postedAt = record.postedAt) != record
        val timestampChanged = previous != null && record.postedAt != previous.postedAt &&
            (record.messaging || (record.preview.isBlank() && record.title.isBlank()))
        val changed = contentChanged || timestampChanged
        val last = announcedAt[record.packageName]
        val shouldAnnounce = announce && changed && record.postedAt >= now - 120_000 &&
            (last == null || now - last >= NotificationPolicy.ANNOUNCEMENT_COOLDOWN_MS)
        if (shouldAnnounce) {
            announcedAt[record.packageName] = now
            while (announcedAt.size > NotificationPolicy.MAX_RECORDS) announcedAt.remove(announcedAt.keys.first())
        }
        return Change(changed, shouldAnnounce)
    }

    fun replaceActive(records: List<NotificationRecord>, now: Long): List<NotificationRecord> {
        active.clear()
        return records.sortedBy { it.postedAt }.filter { update(it, false, now).changed }
    }
    fun remove(key: String) { active.remove(key); seen.remove(key) }
    fun snapshot(now: Long): List<NotificationRecord> = NotificationPolicy.recent(active.values.toList(), now)
    fun clear(now: Long) { active.clear(); seen.clear(); announcedAt.clear(); clearedAt = now }
    fun allowExisting() { clearedAt = 0 }
    private fun trim(map: MutableMap<String, NotificationRecord>) { while (map.size > NotificationPolicy.MAX_RECORDS) map.remove(map.keys.first()) }
}
