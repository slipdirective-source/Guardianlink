package guardianlink.adapters

import guardianlink.crypto.Ed25519
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Signed revocation list feed: authority-signed entries, fail-closed
 * loading. One bad entry poisons the whole load — a partially trusted
 * revocation list is worse than none.
 */
class SignedRevocationListFeedTest {

    @TempDir
    lateinit var dir: Path

    private fun writeList(vararg lines: String): Path {
        val p = dir.resolve("revocations.jsonl")
        Files.write(p, lines.joinToString("\n").toByteArray(StandardCharsets.UTF_8))
        return p
    }

    @Test
    fun `loads signed entries and serves by actionId`() {
        val authority = Ed25519.generateKeyPair()
        val p = writeList(
            SignedRevocationListFeed.signEntry(authority.private, "act-1", 1000L),
            SignedRevocationListFeed.signEntry(authority.private, "act-2", 2000L),
        )
        val feed = SignedRevocationListFeed.load(p, authority.public)
        val revs = feed.revocationsFor("act-1")
        assertEquals(1, revs.size)
        assertEquals("act-1", revs[0].actionId)
        assertEquals(1000L, revs[0].revokedAtMs)
        assertTrue(feed.revocationsFor("act-unknown").isEmpty())
    }

    @Test
    fun `tampered signature poisons the whole load`() {
        val authority = Ed25519.generateKeyPair()
        val good = SignedRevocationListFeed.signEntry(authority.private, "act-1", 1000L)
        // Flip a character inside the sig value.
        val bad = good.replaceFirst("\"sig\":\"", "\"sig\":\"A")
        val p = writeList(good, bad)
        assertFailsWith<IllegalArgumentException> {
            SignedRevocationListFeed.load(p, authority.public)
        }
    }

    @Test
    fun `wrong authority key rejects the load`() {
        val authority = Ed25519.generateKeyPair()
        val impostor = Ed25519.generateKeyPair()
        val p = writeList(SignedRevocationListFeed.signEntry(authority.private, "act-1", 1000L))
        assertFailsWith<IllegalArgumentException> {
            SignedRevocationListFeed.load(p, impostor.public)
        }
    }

    @Test
    fun `malformed lines fail closed`() {
        val authority = Ed25519.generateKeyPair()
        val good = SignedRevocationListFeed.signEntry(authority.private, "act-1", 1000L)
        // Not JSON.
        assertFailsWith<IllegalArgumentException> {
            SignedRevocationListFeed.load(writeList("not json at all"), authority.public)
        }
        // Missing key.
        assertFailsWith<IllegalArgumentException> {
            SignedRevocationListFeed.load(writeList("""{"actionId":"a","revokedAtMs":1}"""), authority.public)
        }
        // Extra key.
        val withExtra = good.replaceFirst("\"sig\":", "\"extra\":1,\"sig\":")
        assertFailsWith<IllegalArgumentException> {
            SignedRevocationListFeed.load(writeList(withExtra), authority.public)
        }
        // Float timestamp.
        val withFloat = good.replaceFirst("\"revokedAtMs\":1000", "\"revokedAtMs\":1000.5")
        assertFailsWith<IllegalArgumentException> {
            SignedRevocationListFeed.load(writeList(withFloat), authority.public)
        }
        // Trailing garbage after the object (trailing whitespace is benign).
        assertFailsWith<IllegalArgumentException> {
            SignedRevocationListFeed.load(writeList("$good x"), authority.public)
        }
        val feedWs = SignedRevocationListFeed.load(writeList("$good   "), authority.public)
        assertEquals(1, feedWs.revocationsFor("act-1").size)
        // Blank lines are benign and skipped.
        val feed = SignedRevocationListFeed.load(writeList("", good, "   "), authority.public)
        assertEquals(1, feed.revocationsFor("act-1").size)
    }

    @Test
    fun `missing file fails closed`() {
        val authority = Ed25519.generateKeyPair()
        assertFailsWith<IllegalArgumentException> {
            SignedRevocationListFeed.load(dir.resolve("nope.jsonl"), authority.public)
        }
    }

    @Test
    fun `reload picks up newly published revocations`() {
        val authority = Ed25519.generateKeyPair()
        val p = writeList(SignedRevocationListFeed.signEntry(authority.private, "act-1", 1000L))
        val feed = SignedRevocationListFeed.load(p, authority.public)
        assertTrue(feed.revocationsFor("act-9").isEmpty())
        Files.write(
            p,
            ("\n" + SignedRevocationListFeed.signEntry(authority.private, "act-9", 9000L))
                .toByteArray(StandardCharsets.UTF_8),
            java.nio.file.StandardOpenOption.APPEND,
        )
        feed.reload()
        assertEquals(1, feed.revocationsFor("act-9").size)
    }

    @Test
    fun `unicode actionIds survive the roundtrip`() {
        val authority = Ed25519.generateKeyPair()
        val p = writeList(SignedRevocationListFeed.signEntry(authority.private, "act-☃-\"q\"", 42L))
        val feed = SignedRevocationListFeed.load(p, authority.public)
        assertEquals(1, feed.revocationsFor("act-☃-\"q\"").size)
    }
}
