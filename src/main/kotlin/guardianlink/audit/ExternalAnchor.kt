package guardianlink.audit

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * External anchor port: durable, out-of-process publication of sealed
 * Merkle roots (Codex v2.2, Gate 8, theta_merkle).
 *
 * The in-process Merkle chain is tamper-EVIDENT; the anchor makes seals
 * durable and checkable outside the process. Without an anchor, a seal
 * dies with the process that made it.
 *
 * Contract: [anchor] is append-only — implementations must never overwrite
 * or reorder prior anchors. It throws on failure: a failed anchor means
 * the seal IS recorded in the engine ledger but is NOT durably published.
 * Fail-loud, never silent — the caller must handle it.
 */
interface ExternalAnchor {
    data class Receipt(
        val root: String,
        val entryIndex: Int,
        val timestampMs: Long,
        /** Human-readable location of the anchor record (file + line, URI, …). */
        val location: String,
    )

    fun anchor(root: String, entryIndex: Int, timestampMs: Long): Receipt
}

/**
 * Append-only file anchor: each sealed root is appended as one line,
 * chained to the previous line's hash so the anchor file itself is
 * tamper-evident.
 *
 * Line format: `entryIndex|root|timestampMs|prevAnchorHash`, where
 * prevAnchorHash is the SHA-256 of the previous line ("GUARDIANLINK-ANCHOR/GENESIS/v1"
 * for the first). [verifyChain] recomputes the chain and reports the first
 * broken line, or -1 when intact.
 *
 * TRUST BOUNDARY — honest grading of this anchor:
 * - Durable across process restarts; out-of-process; tamper-evident via
 *   chaining; serialized within one process.
 * - NOT resistant to an attacker with write access to the anchor file or
 *   its directory: such an attacker can rewrite history. Multi-process
 *   concurrent appends can interleave lines (each line stays well-formed;
 *   entryIndex ordering is checked by [verifyChain]).
 * - Deployments needing host-adversary resistance must substitute a
 *   write-once store or timestamping authority behind the [ExternalAnchor]
 *   port. This implementation is the simple real path, not the final one.
 */
class AppendOnlyFileAnchor(directory: Path) : ExternalAnchor {
    private val lock = ReentrantLock()
    private val file: Path = directory.resolve("anchors.log").also {
        Files.createDirectories(directory)
    }

    override fun anchor(root: String, entryIndex: Int, timestampMs: Long): ExternalAnchor.Receipt =
        lock.withLock {
            val prevHash = if (Files.exists(file) && Files.size(file) > 0) sha256(lastLine()) else GENESIS
            val lineIndex = lineCount()
            val line = "$entryIndex|$root|$timestampMs|$prevHash"
            Files.write(file, (line + "\n").toByteArray(Charsets.UTF_8), CREATE, APPEND)
            ExternalAnchor.Receipt(root, entryIndex, timestampMs, "$file#line=$lineIndex")
        }

    /**
     * Verifies the anchor chain. Returns -1 when intact (or empty —
     * vacuously), else the 0-based line index of the first break:
     * a malformed line, a prevHash mismatch, or a regressed entryIndex.
     */
    fun verifyChain(): Int = lock.withLock {
        if (!Files.exists(file) || Files.size(file) == 0L) return -1
        val lines = Files.readAllLines(file, Charsets.UTF_8).filter { it.isNotBlank() }
        var prevHash = GENESIS
        var prevEntryIndex = -1
        for ((i, line) in lines.withIndex()) {
            val parts = line.split("|")
            if (parts.size != 4) return i
            val entryIndex = parts[0].toIntOrNull() ?: return i
            if (entryIndex < prevEntryIndex) return i
            if (parts[3] != prevHash) return i
            prevEntryIndex = entryIndex
            prevHash = sha256(line)
        }
        return -1
    }

    private fun lineCount(): Int =
        if (!Files.exists(file)) 0
        else Files.readAllLines(file, Charsets.UTF_8).count { it.isNotBlank() }

    private fun lastLine(): String =
        Files.readAllLines(file, Charsets.UTF_8).last { it.isNotBlank() }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val GENESIS = "GUARDIANLINK-ANCHOR/GENESIS/v1"
    }
}
