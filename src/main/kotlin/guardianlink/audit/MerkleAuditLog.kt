package guardianlink.audit

import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class MerkleAuditLog(
    private val externalAnchor: ((rootHash: String, atEntryIndex: Int) -> Unit)? = null
) {
    data class Entry(
        val index: Int,
        val timestampEpochMs: Long,
        val payloadHash: String,
        val previousRoot: String
    )

    private val lock = ReentrantLock()
    private val entries = mutableListOf<Entry>()
    private val leafHashes = mutableListOf<String>()
    private var currentRoot: String = sha256("GENESIS")

    val size: Int get() = lock.withLock { entries.size }
    val root: String get() = lock.withLock { currentRoot }

    fun append(payload: ByteArray, now: Instant = Instant.now()): Entry = lock.withLock {
        val payloadHash = sha256(payload)
        val entry = Entry(
            index = entries.size,
            timestampEpochMs = now.toEpochMilli(),
            payloadHash = payloadHash,
            previousRoot = currentRoot
        )
        entries.add(entry)
        leafHashes.add(sha256("${entry.index}|${entry.timestampEpochMs}|${entry.payloadHash}|${entry.previousRoot}"))

        currentRoot = computeRoot(leafHashes)
        externalAnchor?.invoke(currentRoot, entry.index)

        entry
    }

    fun verifyIntegrity(): Int = lock.withLock {
        for (i in entries.indices) {
            val e = entries[i]
            val recomputed = sha256("${e.index}|${e.timestampEpochMs}|${e.payloadHash}|${e.previousRoot}")
            if (recomputed != leafHashes[i]) return i
            if (i > 0 && e.previousRoot != rootAtCheckpoint(i - 1)) return i
        }
        return -1
    }

    fun rootAtCheckpoint(index: Int): String = lock.withLock {
        require(index in entries.indices) { "index out of range" }
        computeRoot(leafHashes.subList(0, index + 1))
    }

    private fun computeRoot(leaves: List<String>): String {
        if (leaves.isEmpty()) return sha256("GENESIS")
        var level = leaves.toMutableList()
        while (level.size > 1) {
            val next = mutableListOf<String>()
            var i = 0
            while (i < level.size) {
                val left = level[i]
                val right = if (i + 1 < level.size) level[i + 1] else left
                next.add(sha256(left + right))
                i += 2
            }
            level = next
        }
        return level.first()
    }

    private fun sha256(s: String): String = sha256(s.toByteArray(Charsets.UTF_8))
    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}