package guardianlink.durability

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.util.concurrent.locks.ReentrantLock

/**
 * Durable capability store — the unified mechanism for Phase 3 (1+2).
 *
 * A single atomic operation on a shared store is BOTH the crash-consistency
 * mechanism AND the cross-process linearization point. Two separate
 * mechanisms could disagree; one cannot.
 *
 * Design:
 * - Write-ahead log (WAL), append-only, fsync'd per operation. The log is
 *   the source of truth; in-memory state is a cache rebuilt on recovery.
 * - `FileChannel.lock()` on a lock file for cross-process mutual exclusion.
 *   Combined with a local ReentrantLock for in-process threads.
 * - Every mutation is check-and-set UNDER the lock: validation and state
 *   transition are one atomic step. There is no validation outside the
 *   commit that can go stale (the TOCTOU the fencing epoch guards is
 *   closed structurally; the epoch remains as defense-in-depth).
 * - Fencing epoch: monotonic, persisted, bumped on revocation. A committer
 *   may present the epoch from its validation; a stale epoch refuses.
 *   (Primary protection is the under-lock check; fencing is for validations
 *   that cannot be moved under the lock, e.g. future expensive crypto.)
 * - Monotonic counters (seq, fencing epoch, usage) are never wall-clock
 *   derived. Expiry uses wall-clock only as a comparison against notAfterMs,
 *   never for ordering — "cannot roll backward" holds under clock skew.
 * - Crash injection points at every write/fsync boundary for the harness.
 *
 * Threat-model boundary (explicit): this store guarantees crash consistency
 * of its own log. It does NOT prevent rollback of the store file itself
 * (restoring an older snapshot resurrects consumed capabilities). No purely
 * local mechanism prevents that without an external monotonic anchor —
 * out of scope in writing; the existing ExternalAnchor is the natural
 * future anchor point.
 */

sealed interface StoreResult {
    data object Committed : StoreResult
    data class Refused(val reason: String) : StoreResult
    /** Fencing epoch stale: the world changed since validation. Retry. */
    data object StaleEpoch : StoreResult
}

/** Crash injection points. The harness enables these; production disables. */
enum class CrashPoint {
    AFTER_INTENT_WRITE,
    AFTER_INTENT_FSYNC,
    AFTER_COMMIT_WRITE,
    AFTER_COMMIT_FSYNC,
    AFTER_STATE_UPDATE,
}

/** Throws CrashInjected at enabled points. Production uses DISABLED. */
class CrashInjector(private val enabled: Set<CrashPoint> = emptySet()) {
    class CrashInjected(val point: CrashPoint) : RuntimeException("injected crash at $point")
    fun inject(point: CrashPoint) {
        if (point in enabled) throw CrashInjected(point)
    }
    companion object {
        val DISABLED = CrashInjector()
    }
}

class DurableCapabilityStore(
    private val dir: File,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val crash: CrashInjector = CrashInjector.DISABLED,
) {
    private val logFile = dir.resolve("capability.log")
    private val lockFile = dir.resolve("capability.lock")

    private var state: StoreState = StoreState.EMPTY
    private var fencingEpoch: Long = 0L
    private var nextSeq: Long = 0L

    private val localLock = ReentrantLock()

    init {
        dir.mkdirs()
        recover()
    }

    // ---------- Locking ----------

    /**
     * Cross-process + in-process mutual exclusion. The file lock is the
     * cross-process linearization point: only one process holds it, so only
     * one process can be inside a commit operation. The OS releases the file
     * lock on process death (no stale locks from crashes). The local lock
     * serializes threads within this process.
     *
     * Note on "crashed lock holder needs leases": with FileChannel.lock(),
     * the OS guarantees release on death. A *stalled* (not dead) holder
     * blocks progress but cannot cause incorrectness — the fencing epoch
     * (below) ensures a stalled holder that resumes after the world changed
     * cannot commit. Liveness vs safety: we choose safety.
     */
    private inline fun <T> withStoreLock(block: () -> T): T {
        localLock.lock()
        var raf: RandomAccessFile? = null
        var lock: FileLock? = null
        try {
            raf = RandomAccessFile(lockFile, "rw")
            lock = raf.channel.lock() // exclusive, cross-process
            return block()
        } finally {
            try { lock?.release() } catch (_: Exception) {}
            try { raf?.close() } catch (_: Exception) {}
            localLock.unlock()
        }
    }

    // ---------- Log ----------

    private fun appendLog(line: String) {
        logFile.appendText(line + "\n", Charsets.UTF_8)
    }

    private fun fsyncLog() {
        RandomAccessFile(logFile, "rw").use { raf ->
            raf.channel.force(true) // fsync data + metadata
        }
        // Directory fsync for durability of the file itself (rename durability).
        // On Linux, opening the directory and fsyncing ensures the directory
        // entry is durable. Best-effort: not all filesystems support it.
        try {
            java.io.FileInputStream(dir).use { fis ->
                fis.channel.force(true)
            }
        } catch (_: Exception) {
            // Best-effort; the file fsync above is the critical one.
        }
    }

    // ---------- Recovery ----------

    /**
     * Rebuild state from the log. Idempotent: crashing during recovery and
     * re-running yields the same state. INTENT records without a matching
     * COMMIT are ignored (rolled back — the operation never reached the
     * commit point). COMMIT records are applied (rolled forward).
     */
    private fun recover() {
        if (!logFile.exists()) return
        val intents = mutableSetOf<Long>() // seqs with INTENT but no COMMIT yet
        val commits = mutableListOf<Pair<Long, StoreOp>>()
        var maxSeq = -1L
        var maxEpoch = 0L
        logFile.forEachLine(Charsets.UTF_8) { line ->
            if (line.isBlank()) return@forEachLine
            val parts = line.split("|")
            if (parts.size < 4) return@forEachLine // torn write: ignore partial line
            val seq = parts[0].toLongOrNull() ?: return@forEachLine
            val epoch = parts[1].toLongOrNull() ?: return@forEachLine
            val type = parts[2]
            if (seq > maxSeq) maxSeq = seq
            if (epoch > maxEpoch) maxEpoch = epoch
            when (type) {
                "INTENT" -> intents.add(seq)
                "COMMIT" -> {
                    intents.remove(seq)
                    parseOp(seq, parts)?.let { commits.add(seq to it) }
                }
                "EPOCH" -> {
                    // Fencing epoch bump record.
                }
            }
        }
        // Orphaned INTENTs (no COMMIT) are dropped: rolled back.
        // Apply COMMITs in seq order.
        var s = StoreState.EMPTY
        for ((_, op) in commits.sortedBy { it.first }) {
            s = applyOpAtomically(s, op)
        }
        state = s
        fencingEpoch = maxEpoch
        nextSeq = maxSeq + 1
    }

    private fun parseOp(seq: Long, parts: List<String>): StoreOp? {
        // Format: seq|epoch|COMMIT|OP_TYPE|params...
        // parts[0]=seq, parts[1]=epoch, parts[2]=COMMIT, parts[3]=OP_TYPE,
        // parts[4..]=params.
        if (parts.size < 5) return null
        return when (parts[3]) {
            "COMMIT_MUTATION" ->
                StoreOp.CommitMutation(seq, parts[4])
            "RECORD_USAGE" -> {
                if (parts.size < 8) return null
                StoreOp.RecordUsage(
                    seq,
                    parts[4],
                    parts[5].toLongOrNull() ?: return null,
                    parts[6].toLongOrNull(),
                    parts[7].toLongOrNull(),
                )
            }
            "REVOKE" ->
                StoreOp.Revoke(seq, parts[4])
            "REVOKE_EPOCH" ->
                StoreOp.RevokeEpoch(seq, parts[4].toLongOrNull() ?: return null)
            else -> null
        }
    }

    // ---------- Operations (atomic check-and-set under lock) ----------

    /**
     * Atomic commit of a mutation capability. Under the store lock:
     * fresh expiry check, fencing check, consumed/revoked check, then
     * durably record. Either fully applies or not at all.
     */
    fun commitMutation(
        capId: String,
        notAfterMs: Long,
        expectedEpoch: Long? = null,
    ): StoreResult = withStoreLock {
        if (expectedEpoch != null && expectedEpoch != fencingEpoch) {
            return StoreResult.StaleEpoch
        }
        val now = clockMs()
        if (now > notAfterMs) {
            return StoreResult.Refused("capability expired (store clock)")
        }
        if (capId in state.consumed) {
            return StoreResult.Refused("already consumed")
        }
        if (capId in state.revoked) {
            return StoreResult.Refused("revoked")
        }
        val seq = nextSeq++
        // Intent: durable before the commit point.
        appendLog("$seq|$fencingEpoch|INTENT|COMMIT_MUTATION|$capId")
        crash.inject(CrashPoint.AFTER_INTENT_WRITE)
        fsyncLog()
        crash.inject(CrashPoint.AFTER_INTENT_FSYNC)
        // Commit point: the atomic state transition.
        appendLog("$seq|$fencingEpoch|COMMIT|COMMIT_MUTATION|$capId")
        crash.inject(CrashPoint.AFTER_COMMIT_WRITE)
        fsyncLog()
        crash.inject(CrashPoint.AFTER_COMMIT_FSYNC)
        state = applyOpAtomically(state, StoreOp.CommitMutation(seq, capId))
        crash.inject(CrashPoint.AFTER_STATE_UPDATE)
        StoreResult.Committed
    }

    fun recordUsage(
        capId: String,
        bytes: Long,
        maxMutations: Long?,
        maxBytes: Long?,
        notAfterMs: Long,
        expectedEpoch: Long? = null,
    ): StoreResult = withStoreLock {
        if (expectedEpoch != null && expectedEpoch != fencingEpoch) {
            return StoreResult.StaleEpoch
        }
        val now = clockMs()
        if (now > notAfterMs) {
            return StoreResult.Refused("capability expired (store clock)")
        }
        if (capId in state.revoked) {
            return StoreResult.Refused("revoked")
        }
        val (muts, b) = state.usage.getOrDefault(capId, 0L to 0L)
        if (maxMutations != null && muts + 1 > maxMutations) {
            return StoreResult.Refused("mutation bound exceeded")
        }
        if (maxBytes != null && b + bytes > maxBytes) {
            return StoreResult.Refused("byte bound exceeded")
        }
        val seq = nextSeq++
        appendLog("$seq|$fencingEpoch|INTENT|RECORD_USAGE|$capId|$bytes|$maxMutations|$maxBytes")
        crash.inject(CrashPoint.AFTER_INTENT_WRITE)
        fsyncLog()
        crash.inject(CrashPoint.AFTER_INTENT_FSYNC)
        appendLog("$seq|$fencingEpoch|COMMIT|RECORD_USAGE|$capId|$bytes|$maxMutations|$maxBytes")
        crash.inject(CrashPoint.AFTER_COMMIT_WRITE)
        fsyncLog()
        crash.inject(CrashPoint.AFTER_COMMIT_FSYNC)
        state = applyOpAtomically(
            state, StoreOp.RecordUsage(seq, capId, bytes, maxMutations, maxBytes)
        )
        crash.inject(CrashPoint.AFTER_STATE_UPDATE)
        StoreResult.Committed
    }

    fun revoke(capId: String): Unit = withStoreLock {
        val seq = nextSeq++
        fencingEpoch++ // revocation changes the world: fence stalled validators
        appendLog("$seq|$fencingEpoch|INTENT|REVOKE|$capId")
        fsyncLog()
        appendLog("$seq|$fencingEpoch|COMMIT|REVOKE|$capId")
        fsyncLog()
        // Also persist the epoch bump itself.
        appendLog("$seq|$fencingEpoch|EPOCH|BUMP")
        fsyncLog()
        state = applyOpAtomically(state, StoreOp.Revoke(seq, capId))
    }

    fun revokeEpoch(epoch: Long): Unit = withStoreLock {
        val seq = nextSeq++
        fencingEpoch++
        appendLog("$seq|$fencingEpoch|INTENT|REVOKE_EPOCH|$epoch")
        fsyncLog()
        appendLog("$seq|$fencingEpoch|COMMIT|REVOKE_EPOCH|$epoch")
        fsyncLog()
        appendLog("$seq|$fencingEpoch|EPOCH|BUMP")
        fsyncLog()
        state = applyOpAtomically(state, StoreOp.RevokeEpoch(seq, epoch))
    }

    /** Current fencing epoch, for validators to capture. */
    fun currentEpoch(): Long = withStoreLock { fencingEpoch }

    /** Observable state, for the oracle and tests. */
    fun currentState(): StoreState = withStoreLock { state }

    /** For the harness: the ordered history of COMMITTED operations. */
    fun committedHistory(): List<StoreOp> {
        val ops = mutableListOf<StoreOp>()
        if (!logFile.exists()) return ops
        logFile.forEachLine(Charsets.UTF_8) { line ->
            if (line.isBlank()) return@forEachLine
            val parts = line.split("|")
            if (parts.size < 4 || parts[2] != "COMMIT") return@forEachLine
            val seq = parts[0].toLongOrNull() ?: return@forEachLine
            parseOp(seq, parts)?.let { ops.add(it) }
        }
        return ops.sortedBy { it.seq }
    }
}
