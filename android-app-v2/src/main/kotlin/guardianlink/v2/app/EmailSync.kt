package guardianlink.v2.app

import android.content.Context
import android.util.Log
import java.security.MessageDigest
import java.util.Properties
import javax.mail.Folder
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/**
 * Email sync: the mailbox as a governed collector.
 *
 * Fetches the newest messages from the connected mailbox over
 * IMAP/SSL and admits each one into the core as evidence (topic
 * "email.message") through the same admitSensor boundary as every
 * other collector — pause or deny the "email" collector and sync
 * stops at the gate. Deduplication is by a hash of the Message-ID,
 * kept in plain preferences (hashes only; no content).
 *
 * Blocking network work: call ONLY from background threads.
 */
object EmailSync {

    private const val TAG = "GlV2Email"
    private const val PREFS = "gl-email-sync"
    private const val FETCH_COUNT = 12

    data class Result(val admitted: Int, val total: Int, val error: String?)

    fun lastSyncAt(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("last_sync", 0L)

    fun lastError(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last_error", null)

    fun syncNow(context: Context, app: GuardianLinkV2App): Result {
        val account = EmailAccountStore(context).load()
            ?: return record(context, Result(0, 0, "No mailbox connected."))
        var admitted = 0
        var total = 0
        return try {
            val props = Properties().apply {
                put("mail.store.protocol", "imaps")
                put("mail.imaps.host", account.host)
                put("mail.imaps.port", account.port.toString())
                put("mail.imaps.ssl.enable", "true")
                put("mail.imaps.connectiontimeout", "15000")
                put("mail.imaps.timeout", "25000")
            }
            val session = Session.getInstance(props)
            val store = session.getStore("imaps")
            store.connect(account.host, account.address, account.password)
            try {
                val inbox = store.getFolder("INBOX")
                inbox.open(Folder.READ_ONLY)
                total = inbox.messageCount
                if (total > 0) {
                    val from = maxOf(1, total - FETCH_COUNT + 1)
                    val messages = inbox.getMessages(from, total).reversed()
                    val seen = seenHashes(context)
                    for (message in messages) {
                        val mime = message as? MimeMessage ?: continue
                        val id = mime.getHeader("Message-ID")?.firstOrNull()
                            ?: "${mime.from?.firstOrNull()}|${mime.subject}|${mime.sentDate}"
                        val hash = sha256(id)
                        if (hash in seen) continue
                        val claim = claimFor(mime)
                        try {
                            synchronized(app.coreLock) {
                                app.core.admitSensor("email", "email.message", claim, confidence = 0.95)
                            }
                            seen += hash
                            admitted++
                        } catch (refused: IllegalStateException) {
                            Log.w(TAG, "email evidence refused at the boundary: ${refused.message}")
                            break
                        }
                    }
                    saveSeenHashes(context, seen)
                }
                inbox.close(false)
            } finally {
                store.close()
            }
            record(context, Result(admitted, total, null))
        } catch (e: Exception) {
            Log.w(TAG, "email sync failed", e)
            record(context, Result(0, total, friendlyError(e)))
        }
    }

    private fun claimFor(message: MimeMessage): String {
        val from = (message.from?.firstOrNull() as? InternetAddress)?.let {
            it.personal?.takeIf { p -> p.isNotBlank() } ?: it.address
        } ?: "unknown sender"
        val subject = message.subject?.takeIf { it.isNotBlank() } ?: "(no subject)"
        val snippet = snippetOf(message).replace(Regex("\\s+"), " ").trim().take(170)
        return if (snippet.isBlank()) {
            "Email from $from: $subject"
        } else {
            "Email from $from: $subject — $snippet"
        }
    }

    private fun snippetOf(part: Part): String {
        return try {
            when {
                part.isMimeType("text/plain") -> (part.content as? String) ?: ""
                part.isMimeType("multipart/*") -> {
                    val mp = part.content as? Multipart ?: return ""
                    var fallback = ""
                    for (i in 0 until mp.count) {
                        val child = mp.getBodyPart(i)
                        if (child.isMimeType("text/plain")) return snippetOf(child)
                        if (fallback.isEmpty()) fallback = snippetOf(child)
                    }
                    fallback
                }
                else -> ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun friendlyError(e: Exception): String {
        val msg = (e.message ?: e.javaClass.simpleName)
        return when {
            msg.contains("AUTHENTICATIONFAILED", true) || msg.contains("Invalid credentials", true) ->
                "Sign-in failed — the app password was refused. Check it in Settings → Connected email."
            msg.contains("UnknownHost", true) || msg.contains("Unable to resolve host", true) ->
                "The mail server could not be reached (host or network)."
            msg.contains("timed out", true) || msg.contains("timeout", true) ->
                "The mail server timed out."
            else -> "Sync failed: ${msg.take(140)}"
        }
    }

    private fun record(context: Context, result: Result): Result {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("last_sync", System.currentTimeMillis())
            .putString("last_error", result.error)
            .putInt("last_admitted", result.admitted)
            .apply()
        return result
    }

    private fun seenHashes(context: Context): MutableSet<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet("seen", emptySet())?.toMutableSet() ?: mutableSetOf()

    private fun saveSeenHashes(context: Context, seen: Set<String>) {
        // Cap the set so preferences stay small; hashes of the
        // newest FETCH_COUNT window are what actually get re-asked.
        val capped = if (seen.size > 400) seen.toList().takeLast(400).toSet() else seen
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet("seen", capped)
            .apply()
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** The sovereign disconnected the mailbox: forget what was seen. */
    fun forgetSeen(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
