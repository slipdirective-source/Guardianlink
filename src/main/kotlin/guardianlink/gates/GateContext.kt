package guardianlink.gates

import guardianlink.audit.MerkleAuditLog

/**
 * sigma — the global system state vector (Codex v2.2, §II).
 *
 * sigma = ( eta, kappa, pi, S_entropy, c, a, x, M, r, s_a, tau )
 */
data class GateContext(
    /** eta: raw signal payload + measured SNR + well-formedness. */
    val signal: Signal,
    /** kappa: key material & biometric commitments. */
    val keys: KeyMaterial,
    /** pi: zero-knowledge proofs & attestations. */
    val proofs: Proofs,
    /** S_entropy: measured context entropy, bits. */
    val contextEntropyBits: Double,
    /** c: context frame — number of parse trees (unambiguity check). */
    val contextParseTrees: Int,
    /** a: action, a ∈ A_total. */
    val action: Action,
    /** Stable identifier for the action, used in audit records. */
    val actionId: String,
    /** x: substrate state configuration (bounded projection the gates may inspect). */
    val substrate: SubstrateState,
    /** M: Merkle audit ledger. Every S_HALT transition is appended. */
    val ledger: MerkleAuditLog,
    /** r: plain-fact rendering of a shown to the person. Must equal Render(a). */
    val rendering: String,
    /** s_a: person's assent signature over r. Null until given. */
    val assent: Assent?,
    /** tau-domain timestamp (ms) when r was shown for assent. */
    val assentedAtMs: Long,
    /** Signed revocations of this action, if any. */
    val revocations: List<Revocation>,
    /** tau: the one attested monotonic clock serving every time predicate. */
    val clock: MonotonicClock,
    /** v_int: intent vector at session start. */
    val intentVector: DoubleArray,
    /** v_curr: current intent vector. */
    val currentVector: DoubleArray,
)

/** eta — raw signal payload / ambient noise (Gate 0). */
data class Signal(
    val payload: ByteArray,
    /** Measured signal-to-noise ratio. */
    val snr: Double,
    /** Header(eta) ∈ ValidFormat. */
    val wellFormed: Boolean,
)

/** kappa — key material & biometric commitments (Gate 1). */
data class KeyMaterial(
    /** m: the message whose origin is being proven. Bound to the request. */
    val message: ByteArray,
    /** s: signature over m, verified against K_p. */
    val signature: ByteArray,
    /** h_bio: presented biometric template. */
    val biometricTemplate: DoubleArray,
    /** C_root: enrolled biometric commitment. */
    val enrolledTemplate: DoubleArray,
)

/** pi — zero-knowledge proofs & attestations (Gate 3). */
data class Proofs(
    val zkProof: ByteArray,
    val publicInputs: ByteArray,
    /** t_pi: issuance timestamp, must be in tau's clock domain. */
    val issuedAtMs: Long,
)

/**
 * x — substrate state configuration (Gates 2, 6, 8).
 *
 * The decidability argument via A_total covers the *program*; predicates
 * over x additionally require x to be a bounded projection, which this
 * value type enforces structurally (finite map of finite maps).
 */
data class SubstrateState(val records: Map<String, Map<String, String>>)

/** s_a — the person's assent signature over the rendering r (Gate 7). */
data class Assent(val signature: ByteArray)

/** A signed revocation of an action (Codex v2.2, change (c)). */
data class Revocation(
    val actionId: String,
    val signature: ByteArray,
    /** t_rev: revocation timestamp, must be in tau's clock domain. */
    val revokedAtMs: Long,
)

/**
 * tau — one attested monotonic clock serving every time predicate
 * (Codex v2.2, change (d)). Monotonicity defeats rollback; all compared
 * timestamps (t_pi, t_shown, t_rev) must live in tau's domain.
 */
interface MonotonicClock {
    fun nowMs(): Long
}

/** Wall adapter: monotonic within a process via nanoTime. */
class SystemMonotonicClock : MonotonicClock {
    private val originNanos = System.nanoTime()
    private val originMs = System.currentTimeMillis()
    override fun nowMs(): Long = originMs + (System.nanoTime() - originNanos) / 1_000_000L
}

/** Deterministic clock for tests. */
class FakeClock(var t: Long = 0L) : MonotonicClock {
    override fun nowMs(): Long = t
    fun advance(ms: Long) { t += ms }
}
