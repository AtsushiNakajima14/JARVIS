package dev.jarvis

import dev.jarvis.assistant.notifications.*
import org.junit.Assert.*
import org.junit.Test

class NotificationPolicyTest {
    private val now = 2_000_000_000_000L
    private fun notice(key: String = "a", app: String = "Discord", preview: String = "Meet at noon", at: Long = now,
        sender: String = "Alice", messaging: Boolean = true) = NotificationRecord(key, "com.example.${app.lowercase()}", app,
        title = sender, preview = preview, sender = sender, postedAt = at, messaging = messaging)

    @Test fun `security and financial content is excluded before recording or answering`() {
        for (text in listOf("Your verification code is 482915", "A one-time password arrived", "PIN changed",
            "Your login code is AB2XQ", "Account balance updated", "Transaction approved", "The number is 4829"))
            assertTrue(text, NotificationPolicy.hidesContent("com.example.chat", text))
        assertTrue(NotificationPolicy.hidesContent("com.example.authenticator", "New alert"))
        assertTrue(NotificationPolicy.hidesContent("com.example.wallet", "New alert"))
    }

    @Test fun `ordinary message text and short counts remain readable`() {
        assertFalse(NotificationPolicy.hidesContent("com.discord", "Alice", "Meet at noon, I have 2 tickets"))
        assertFalse(NotificationPolicy.hidesContent("com.example.mail", "3 new messages"))
        assertEquals("Alice hello", NotificationPolicy.clean("Alice\n\u202ehello", 80))
        assertEquals(80, NotificationPolicy.clean("x".repeat(500), 80).length)
    }

    @Test fun `app filter handles spoken spacing without substring collisions`() {
        assertTrue(NotificationPolicy.matchesApp(notice(app = "YouTube"), "you tube"))
        assertTrue(NotificationPolicy.matchesApp(notice(app = "Discord"), "discord app"))
        assertTrue(NotificationPolicy.matchesApp(notice(app = "Discord"), "com.example.discord"))
        assertFalse(NotificationPolicy.matchesApp(notice(app = "Discordant"), "discord"))
    }

    @Test fun `notification answers hide security content and bound spoken previews`() {
        val private = notice(preview = "hidden secret").copy(contentHidden = true)
        val result = NotificationPolicy.notificationAnswer(listOf(private), "")
        assertTrue(result.contains("content is hidden")); assertFalse(result.contains("hidden secret"))
        val many = NotificationPolicy.notificationAnswer((0..8).map { notice(key = "$it", preview = "x".repeat(420)) }, "")
        assertTrue(many.contains("6 more")); assertTrue(many.length < 700)
    }

    @Test fun `recent chat answer only names captured incoming messaging correspondents`() {
        val result = NotificationPolicy.recentChatsAnswer(listOf(notice(), notice(key = "b"),
            notice(key = "c", sender = "Shopping sale", messaging = false), notice(key = "d", sender = "2 new messages")), "")
        assertTrue(result.contains("Alice on Discord"))
        assertEquals(1, Regex("Alice on Discord").findAll(result).count())
        assertFalse(result.contains("Shopping sale")); assertFalse(result.contains("2 new messages"))
        assertTrue(result.contains("not your full chat history or messages you sent"))
    }

    @Test fun `recent chat filters and empty explanations do not invent a person`() {
        val result = NotificationPolicy.recentChatsAnswer(listOf(notice()), "WhatsApp")
        assertFalse(result.contains("Alice")); assertTrue(result.contains("no captured messaging notifications"))
        assertTrue(NotificationPolicy.notificationAnswer(emptyList(), "").contains("no captured active"))
    }

    @Test fun `retention discards stale and future records and caps storage window`() {
        val items = (0..249).map { notice(key = "$it", at = now - it) } +
            notice(key = "old", at = now - NotificationPolicy.RETENTION_MS - 1) + notice(key = "future", at = now + 60_001)
        val recent = NotificationPolicy.recent(items, now)
        assertEquals(200, recent.size); assertEquals("0", recent.first().key); assertEquals("199", recent.last().key)
    }

    @Test fun `unchanged notification updates do not repeat announcements`() {
        val ledger = NotificationLedger()
        val item = notice(messaging = false)
        assertTrue(ledger.update(item, true, now).announce)
        val same = ledger.update(item.copy(postedAt = now + 60_000), true, now + 60_000)
        assertFalse(same.changed); assertFalse(same.announce)
    }

    @Test fun `new messages respect per-app cooldown and independent app alerts`() {
        val ledger = NotificationLedger()
        assertTrue(ledger.update(notice(), true, now).announce)
        assertFalse(ledger.update(notice(preview = "Second message", at = now + 1_000), true, now + 1_000).announce)
        assertTrue(ledger.update(notice(key = "other", app = "Messenger", at = now + 2_000), true, now + 2_000).announce)
        assertTrue(ledger.update(notice(preview = "Third message", at = now + 31_000), true, now + 31_000).announce)
    }

    @Test fun `clear rejects pre-clear snapshots but permits new notifications`() {
        val ledger = NotificationLedger()
        ledger.update(notice(), false, now)
        ledger.clear(now + 1)
        ledger.replaceActive(listOf(notice()), now + 2)
        assertTrue(ledger.snapshot(now + 2).isEmpty())
        assertTrue(ledger.update(notice(at = now + 3), true, now + 3).announce)
        assertEquals(1, ledger.snapshot(now + 3).size)
    }

    @Test fun `active snapshot replacement and removal do not leave dismissed alerts`() {
        val ledger = NotificationLedger()
        ledger.replaceActive(listOf(notice(), notice(key = "b")), now)
        assertEquals(2, ledger.snapshot(now).size)
        ledger.replaceActive(listOf(notice(key = "b")), now)
        assertEquals(listOf("b"), ledger.snapshot(now).map { it.key })
        ledger.remove("b"); assertTrue(ledger.snapshot(now).isEmpty())
    }

    @Test fun `a fresh reading opt in can import already active notifications`() {
        val ledger = NotificationLedger()
        ledger.clear(now + 1)
        ledger.allowExisting()
        ledger.replaceActive(listOf(notice()), now + 2)
        assertEquals(1, ledger.snapshot(now + 2).size)
    }

    @Test fun `active state stays bounded and old snapshots never announce on connection`() {
        val ledger = NotificationLedger()
        ledger.replaceActive((0..249).map { notice(key = "$it", at = now - it) }, now)
        assertEquals(200, ledger.snapshot(now).size)
        assertFalse(ledger.update(notice(key = "expired", at = now - 120_001), true, now).announce)
    }

    @Test fun `announcements contain app names only`() {
        val announcement = NotificationAnnouncement("Discord", now, 7)
        assertEquals("New notification from Discord.", announcement.text)
        assertFalse(announcement.text.contains("Alice")); assertFalse(announcement.text.contains("Meet at noon"))
    }
}
