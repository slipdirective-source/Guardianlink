package guardianlink.durability

/**
 * Recovery oracle (Phase 3a) — the acceptance condition, written before the
 * mechanism it judges.
 *
 * ACCEPTANCE CONDITION: after any crash and recovery, the observable
 * capability-store state must equal the state after some atomic prefix of
 * the operation history. Formally, there exists k (0 <= k <= n) such that:
 *
 *   recovered_state == simulate(history[0], ..., history[k])
 *
 * where simulate applies each operation atomically — fully or not at all —
 * in history order, using the spec's operation semantics.
 *
 * What this rules out:
 * - A consumed capability that becomes unconsumed (gap: consume applied,
 *   then lost — no prefix explains it).
 * - Usage that rolls backward (same).
 * - A revocation that disappears (same).
 * - An operation applied without its predecessors (reordering).
 * - A partially applied operation (torn commit visible as state).
 *
 * What this permits:
 * - Losing a suffix of operations (they never became durable).
 * - Refused operations anywhere (they change nothing; every prefix that
 *   includes them simulates identically).
 *
 * The checker is implementation-independent: it takes the attempted history
 * and the recovered state, and verifies the prefix property by simulation.
 * The crash harness (Phase 3d) fails against this checker. The durable
 * store (Phase 3b) must satisfy it under every injected crash.
 *
 * Threat-model boundary (explicit): this oracle covers the store's own
 * crash consistency. It does NOT cover rollback of the store itself
 * (restoring an older snapshot file) — no purely local mechanism prevents
 * that without an external monotonic anchor. See the Phase 3 threat-model
 * doc. Usage and epochs are monotonic counters, never wall-clock-derived,
 * so "cannot roll backward" holds under clock skew.
 */

// ---------- Operation history ----------

/**
 * Durable-store operations, in the order attempted. Each carries a unique
 * sequence number so the harness can identify prefixes unambiguously.
 * Semantics follow the frozen capability spec v2.1 (commit rules).
 */
sealed interface StoreOp {
    val seq: Long

    /**
     * Mark a mutation capability consumed. Applies iff the capability is
     * neither consumed nor revoked at apply time; otherwise a no-op
     * (refused). Idempotent: re-applying after a crash changes nothing.
     */
    data class CommitMutation(override val seq: Long, val capId: String) : StoreOp

    /**
     * Record one epoch effect. Applies iff within bounds at apply time;
     * otherwise a no-op. Monotonic: counters never decrease.
     */
    data class RecordUsage(
        override val seq: Long,
        val capId: String,
        val bytes: Long,
        val maxMutations: Long?,
        val maxBytes: Long?,
    ) : StoreOp

    /** Revocation kills remaining lifetime. Idempotent. Separate from expiry. */
    data class Revoke(override val seq: Long, val capId: String) : StoreOp

    /** Coarse revocation: kills the whole epoch. Idempotent. */
    data class RevokeEpoch(override val seq: Long, val epoch: Long) : StoreOp

    /** Sovereign issuance permit consumption. Idempotent: re-applying is a no-op. */
    data class PermitConsumed(override val seq: Long, val permitId: String) : StoreOp
}

// ---------- Observable state ----------

/**
 * The observable capability-store state: everything recovery must preserve.
 * All fields are monotonic in the allowed direction (sets only grow,
 * counters only increase) — this is what "cannot roll backward" means
 * mechanically.
 */
data class StoreState(
    val consumed: Set<String> = emptySet(),
    val revoked: Set<String> = emptySet(),
    val revokedEpochs: Set<Long> = emptySet(),
    /** capId -> (mutation count, byte count). Counters only increase. */
    val usage: Map<String, Pair<Long, Long>> = emptyMap(),
) {
    companion object {
        val EMPTY = StoreState()
    }
}

// ---------- Simulation (the spec's semantics, atomically) ----------

/**
 * Apply one operation atomically to a state, per the spec. Returns the new
 * state (which may equal the old state if the operation was refused).
 * This is the reference semantics the checker simulates; the durable store
 * must agree with it on every prefix.
 */
fun applyOpAtomically(state: StoreState, op: StoreOp): StoreState {
    return when (op) {
        is StoreOp.CommitMutation -> {
            if (op.capId in state.consumed || op.capId in state.revoked) state
            else state.copy(consumed = state.consumed + op.capId)
        }
        is StoreOp.RecordUsage -> {
            if (op.capId in state.revoked) return state
            val (muts, bytes) = state.usage.getOrDefault(op.capId, 0L to 0L)
            val newMuts = muts + 1
            val newBytes = bytes + op.bytes
            if (op.maxMutations != null && newMuts > op.maxMutations) return state
            if (op.maxBytes != null && newBytes > op.maxBytes) return state
            state.copy(usage = state.usage + (op.capId to (newMuts to newBytes)))
        }
        is StoreOp.Revoke ->
            state.copy(revoked = state.revoked + op.capId)
        is StoreOp.RevokeEpoch ->
            state.copy(revokedEpochs = state.revokedEpochs + op.epoch)
    }
}

/** Simulate the atomic prefix history[0..k] (k operations). */
fun simulatePrefix(history: List<StoreOp>, k: Int): StoreState {
    require(k in 0..history.size)
    var state = StoreState.EMPTY
    for (i in 0 until k) {
        state = applyOpAtomically(state, history[i])
    }
    return state
}

// ---------- The checker ----------

sealed interface OracleResult {
    /** Recovered state matches some atomic prefix. Carries the witness k. */
    data class Holds(val prefixLength: Int) : OracleResult
    /**
     * No atomic prefix explains the recovered state. Carries the closest
     * prefix length tried and a description of the divergence.
     */
    data class Violated(val detail: String) : OracleResult
}

/**
 * The recovery oracle. Given the full attempted history (in order) and the
 * state observed after crash + recovery, verify the acceptance condition.
 *
 * This is deliberately strict: it does not ask "is the state reasonable?"
 * but "is there an atomic prefix of the history that yields EXACTLY this
 * state?" Anything else — a gap, a reordering, a torn write, a resurrected
 * capability, rolled-back usage — is a violation.
 */
object RecoveryOracle {
    fun check(history: List<StoreOp>, recovered: StoreState): OracleResult {
        // History must be ordered by seq (the harness guarantees this;
        // the oracle asserts it).
        for (i in 1 until history.size) {
            if (history[i].seq <= history[i - 1].seq) {
                return OracleResult.Violated(
                    "history not ordered by seq at index $i"
                )
            }
        }
        // Try every prefix, longest first (prefer the most complete explanation).
        for (k in history.size downTo 0) {
            if (simulatePrefix(history, k) == recovered) {
                return OracleResult.Holds(k)
            }
        }
        // No prefix explains it. Describe the divergence against the full
        // simulation to aid diagnosis.
        val full = simulatePrefix(history, history.size)
        val detail = buildString {
            append("no atomic prefix of ${history.size} ops yields the recovered state; ")
            val missingConsumed = full.consumed - recovered.consumed
            val extraConsumed = recovered.consumed - full.consumed
            val regressedUsage = recovered.usage.filter { (id, after) ->
                val before = full.usage[id]
                before != null && (after.first < before.first || after.second < before.second)
            }.keys
            if (missingConsumed.isNotEmpty()) append("consumed lost: $missingConsumed; ")
            if (extraConsumed.isNotEmpty()) append("consumed appeared: $extraConsumed; ")
            if (regressedUsage.isNotEmpty()) append("usage rolled back: $regressedUsage; ")
            val missingRevoked = full.revoked - recovered.revoked
            if (missingRevoked.isNotEmpty()) append("revocations lost: $missingRevoked; ")
        }
        return OracleResult.Violated(detail)
    }
}
