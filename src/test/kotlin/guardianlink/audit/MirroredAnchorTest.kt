package guardianlink.audit

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MirroredAnchor: N-way fan-out, per-mirror chain checks, and
 * cross-mirror tip agreement. A rewritten-but-self-consistent single
 * mirror must fail quorum even though its own chain verifies.
 */
class MirroredAnchorTest {

    @TempDir
    lateinit var dir: Path

    private fun mirrors(n: Int = 3): List<Path> =
        (0 until n).map { dir.resolve("m$it").also { Files.createDirectories(it) } }

    @Test
    fun `healthy mirrors anchor identically and reach quorum`() {
        val anchor = MirroredAnchor(mirrors())
        anchor.anchor("root-a", 0, 1000L)
        anchor.anchor("root-b", 1, 2000L)
        assertTrue(anchor.brokenMirrors().isEmpty())
        assertTrue(anchor.quorumHealthy())
        assertEquals(3, anchor.mirrorCount())
    }

    @Test
    fun `needs at least two distinct directories`() {
        val one = mirrors(1)
        assertFailsWith<IllegalArgumentException> { MirroredAnchor(one) }
        assertFailsWith<IllegalArgumentException> { MirroredAnchor(listOf(one[0], one[0])) }
    }

    @Test
    fun `garbage appended to one mirror breaks it`() {
        val dirs = mirrors()
        val anchor = MirroredAnchor(dirs)
        anchor.anchor("root-a", 0, 1000L)
        // Attacker appends garbage to mirror 1's file.
        Files.write(
            dirs[1].resolve("anchors.log"),
            "garbage-line\n".toByteArray(StandardCharsets.UTF_8),
            StandardOpenOption.APPEND,
        )
        assertEquals(listOf(1), anchor.brokenMirrors())
        assertFalse(anchor.quorumHealthy())
    }

    @Test
    fun `rewritten-but-self-consistent mirror fails quorum via tip divergence`() {
        val dirs = mirrors()
        val anchor = MirroredAnchor(dirs)
        anchor.anchor("root-a", 0, 1000L)
        anchor.anchor("root-b", 1, 2000L)
        // Sophisticated attacker: rewrites mirror 2's file with a VALID
        // chain (correct prevHash linkage) but a DIFFERENT second root.
        // Per-mirror verification passes — the forgery is self-consistent.
        val file2 = dirs[2].resolve("anchors.log")
        val lines = Files.readAllLines(file2, StandardCharsets.UTF_8).filter { it.isNotBlank() }
        assertEquals(2, lines.size)
        val forgedSecond = run {
            // Recompute the chain honestly, but with root-x instead of root-b.
            val prevHash = sha256(lines[0])
            "1|root-x|2000|$prevHash"
        }
        Files.write(
            file2,
            (lines[0] + "\n" + forgedSecond + "\n").toByteArray(StandardCharsets.UTF_8),
            StandardOpenOption.TRUNCATE_EXISTING,
        )
        // Mirror 2's own chain still verifies...
        assertTrue(anchor.brokenMirrors().isEmpty())
        // ...but the quorum sees the tip divergence.
        assertFalse(anchor.quorumHealthy())
    }

    @Test
    fun `single file anchor fsyncs - receipt survives process-equivalent re-read`() {
        // Durability smoke test: anchor, then re-open the file fresh and
        // verify the chain — the write must have reached the OS.
        val d = dir.resolve("single").also { Files.createDirectories(it) }
        val anchor = AppendOnlyFileAnchor(d)
        anchor.anchor("root-a", 0, 1000L)
        val fresh = AppendOnlyFileAnchor(d)
        assertEquals(-1, fresh.verifyChain())
    }

    private fun sha256(s: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
