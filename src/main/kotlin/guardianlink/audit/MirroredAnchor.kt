package guardianlink.audit

import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Mirrored external anchor: every sealed root is fanned out to N
 * independent [AppendOnlyFileAnchor]s (separate directories — ideally
 * separate filesystems or hosts).
 *
 * Why this is a grade up from one anchor file: a single anchor file is
 * tamper-evident but not tamper-resistant — an attacker with write
 * access rewrites history and the chain still verifies (it is
 * self-consistent). With N mirrors, the attacker must rewrite all N
 * consistently; any mirror they miss, or rewrite differently, is
 * detectable via [brokenMirrors] / [quorumHealthy]. This is defense in
 * depth, not disk-attacker-proof: a sufficiently privileged attacker who
 * rewrites every mirror identically still wins. The next grade up is a
 * write-once store or timestamping authority (Rekor / RFC 3161 TSA)
 * behind the [ExternalAnchor] port.
 *
 * Ordering: all mirrors are appended under ONE lock, in the same order,
 * so concurrent callers cannot interleave mirrors differently. Anchoring
 * is all-or-nothing per call — if any mirror throws, the exception
 * propagates (fail-loud) and the caller must reconcile; mirrors are
 * never left to drift silently.
 */
class MirroredAnchor(directories: List<Path>) : ExternalAnchor {
    init {
        require(directories.size >= 2) { "mirroring needs at least 2 directories" }
        require(directories.toSet().size == directories.size) { "mirror directories must be distinct" }
    }

    private val lock = ReentrantLock()
    private val mirrors: List<AppendOnlyFileAnchor> = directories.map { AppendOnlyFileAnchor(it) }

    override fun anchor(root: String, entryIndex: Int, timestampMs: Long): ExternalAnchor.Receipt =
        lock.withLock {
            var first: ExternalAnchor.Receipt? = null
            for (mirror in mirrors) {
                val receipt = mirror.anchor(root, entryIndex, timestampMs)
                if (first == null) first = receipt
            }
            // +1 mirror count is recorded in the location so operators can
            // see the fan-out without guessing.
            val r = first!!
            r.copy(location = "${r.location} (+${mirrors.size - 1} mirrors)")
        }

    /**
     * Indexes of mirrors whose own hash chain is broken (malformed line,
     * prevHash mismatch, or regressed entryIndex). Empty means every
     * mirror is internally intact — which does NOT yet mean they agree.
     */
    fun brokenMirrors(): List<Int> = lock.withLock {
        mirrors.indices.filter { mirrors[it].verifyChain() >= 0 }
    }

    /**
     * True iff every mirror's chain is intact AND all mirrors agree on
     * the tip. A rewritten-but-self-consistent single mirror fails here
     * (its tip diverges from its siblings) even though [brokenMirrors]
     * would pass it.
     */
    fun quorumHealthy(): Boolean = lock.withLock {
        if (brokenMirrors().isNotEmpty()) return false
        mirrors.map { it.tipHash() }.toSet().size == 1
    }

    /** Number of mirrors. */
    fun mirrorCount(): Int = mirrors.size
}
