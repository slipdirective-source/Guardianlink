package guardianlink.audit

import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Merkle-chained audit ledger (Codex v2.2, Gate 8, theta_merkle).
 *
 * TRUST BOUNDARY — read this before relying on the log:
 *
 * 1. Tamper-EVIDENT, not tamper-PROOF. Within one process the hash chain
 *    detects modification, truncation, or reordering. Against an attacker
 *    who controls the process memory or the whole machine, no in-process
 *    structure can promise immutability.
 * 2. "Immutable seal" requires a DURABLE EXTERNAL ANCHOR: each new root
 *    must be published to a write-once / independently timestamped store
 *    (transparency log, timestamping authority, anchored chain). The
 *    [externalAnchor] callback is that hook — the demo anchor only prints,
 *    which anchors nothing. Deployments MUST wire a real anchor.
 * 3. Entry timestamps are wall-clock ([Instant]) for human audit only.
 *    They are caller-supplied and MUST NOT feed security decisions; every
 *    security time predicate in the Nine Gates uses tau ([MonotonicClock]).
 *    The [append] overload taking a raw millisecond value is the
 *    engine-attested path: NineGates supplies timestamps from its owned
 *    clock, closing the caller-supplied forgery vector for engine records.
 *    Direct external appenders keep the caller-supplied caveat.
 *
 * Hashing uses domain separation: leaf, internal node, and genesis hashes
 * are computed over distinct prefixes, so a leaf preimage can never be
 * mistaken for an internal node (second-preimage resistance across levels).
 * Odd levels duplicate the last leaf (standard Merkle padding).
 */
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
    private var currentRoot: String = genesisRoot()

    val size: Int get() = lock.withLock { entries.size }
    val root: String get() = lock.withLock { currentRoot }

    /**
     * Epoch-ms of the most recent append, or null when the ledger is empty.
     *
     * Recorded as the max over all observed append times (high-water mark):
     * a backward jump of the caller-supplied `now` cannot regress the
     * marker, so a clock glitch can neither erase recorded liveness nor
     * manufacture it beyond what was observed.
     *
     * TRUST BOUNDARY: `now` is caller-supplied per append. A caller that
     * forges future-dated appends defeats any silence-based check built on
     * this value — the same forgery defeats the audit trail itself. The
     * recourse is the durable external anchor, not this marker.
     */
    private var lastAppendEpochMs: Long? = null

    fun lastAppendMs(): Long? = lock.withLock { lastAppendEpochMs }

    /**
     * Engine-attested append: the timestamp is supplied by the caller of
     * this overload — NineGates passes its owned clock's nowMs(), so engine
     * records carry engine-attested time, never request-supplied time.
     */
    fun append(payload: ByteArray, timestampMs: Long): Entry {
        // Build the entry and advance the chain under the lock...
        val (entry, rootAfter) = lock.withLock {
            val payloadHash = sha256(payload)
            val entry = Entry(
                index = entries.size,
                timestampEpochMs = timestampMs,
                payloadHash = payloadHash,
                previousRoot = currentRoot
            )
            entries.add(entry)
            leafHashes.add(leafHash(entry))
            currentRoot = computeRoot(leafHashes)
            // High-water liveness marker (see lastAppendMs): max() so a
            // backward jump of the supplied time cannot regress it.
            lastAppendEpochMs = maxOf(lastAppendEpochMs ?: Long.MIN_VALUE, timestampMs)
            Pair(entry, currentRoot)
        }
        // ...but invoke the external anchor OUTSIDE the lock: a callback
        // that appends reentrantly must not run while this append holds the
        // lock. Ordering note: with concurrent appenders, anchor(i+1) may
        // fire before anchor(i) — the lock is released before the call, so
        // two threads can interleave here. Every anchor call carries its
        // entry index; a real anchor MUST order by index, not by arrival.
        // An anchor exception propagates to the caller: the entry IS recorded
        // locally, but external durability failed — the caller must handle it.
        externalAnchor?.invoke(rootAfter, entry.index)
        return entry
    }

    /**
     * Caller-supplied timestamp append (wall-clock Instant, for human audit
     * and external seeding). Keeps the documented caveat: these timestamps
     * MUST NOT feed security decisions — only the engine-attested overload
     * above carries engine time.
     */
    fun append(payload: ByteArray, now: Instant = Instant.now()): Entry =
        append(payload, now.toEpochMilli())

    /**
     * Full integrity verification. Returns -1 when the log is intact,
     * otherwise the index of the first failing entry — or [size] when every
     * entry is well-formed but [currentRoot] does not match the recomputed
     * root (in-memory root tampering).
     *
     * Checks, in order: leaf-hash recomputation per entry, genesis linkage
     * of entry 0 (previousRoot == genesis), chaining of every subsequent
     * entry against the checkpoint root before it, and finally currentRoot
     * against a full recomputation.
     */
    fun verifyIntegrity(): Int = lock.withLock {
        for (i in entries.indices) {
            val e = entries[i]
            if (leafHash(e) != leafHashes[i]) return i
            val expectedPrev = if (i == 0) genesisRoot() else rootAtCheckpointLocked(i - 1)
            if (e.previousRoot != expectedPrev) return i
        }
        if (computeRoot(leafHashes) != currentRoot) return entries.size
        return -1
    }

    fun rootAtCheckpoint(index: Int): String = lock.withLock {
        require(index in entries.indices) { "index out of range" }
        rootAtCheckpointLocked(index)
    }

    private fun rootAtCheckpointLocked(index: Int): String =
        computeRoot(leafHashes.subList(0, index + 1))

    private fun computeRoot(leaves: List<String>): String {
        if (leaves.isEmpty()) return genesisRoot()
        var level = leaves.toMutableList()
        while (level.size > 1) {
            val next = mutableListOf<String>()
            var i = 0
            while (i < level.size) {
                val left = level[i]
                val right = if (i + 1 < level.size) level[i + 1] else left
                next.add(nodeHash(left, right))
                i += 2
            }
            level = next
        }
        return level.first()
    }

    private fun genesisRoot(): String = sha256("GUARDIANLINK-MERKLE/GENESIS/v1".toByteArray(Charsets.UTF_8))

    private fun leafHash(e: Entry): String =
        sha256("GUARDIANLINK-MERKLE/LEAF/v1|${e.index}|${e.timestampEpochMs}|${e.payloadHash}|${e.previousRoot}")

    private fun nodeHash(left: String, right: String): String =
        sha256("GUARDIANLINK-MERKLE/NODE/v1|$left|$right")

    private fun sha256(s: String): String = sha256(s.toByteArray(Charsets.UTF_8))
    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
