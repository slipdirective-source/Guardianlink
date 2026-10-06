package guardianlink.gates

/**
 * sigma — the global system state vector (Codex v2.2, §II).
 *
 * sigma = ( eta, kappa, pi, S_entropy, c, a, x, M, r, s_a, tau )
 *
 * TRUST BOUNDARY: every evidence field in this context (signal payload/SNR,
 * biometric templates, proof bytes, entropy bits, parse-tree count, intent
 * vectors) is CALLER-SUPPLIED. The gates check these values
 * for internal consistency, bounds, and finiteness — they do not and cannot
 * verify their truth. A caller that supplies self-consistent false evidence
 * passes the consistency checks; detecting that requires real adapters
 * (attested sensors, signature verification, ZK proof systems) behind the
 * Verifiers ports. See README "Trust boundaries & adapter requirements".
 *
 * INSTRUMENT OWNERSHIP: the request carries evidence only. The engine owns
 * its clock and ledger (constructor-supplied to NineGates, never taken from
 * the request), and revocations arrive through the Verifiers.revocationsFor
 * port. A request cannot substitute the engine's time source, audit sink,
 * or revocation feed.
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
    /** r: plain-fact rendering of a shown to the person. Must equal Render(a). */
    val rendering: String,
    /** s_a: person's assent signature over r. Null until given. */
    val assent: Assent?,
    /**
     * Claimed tau-domain timestamp (ms) when r was shown for assent.
     * Caller-supplied; the assent signature MUST bind (rendering,
     * assentedAtMs, actionId) — see Verifiers.verifyAssent. The engine
     * passes all three to the verifier; a verifier that checks only the
     * rendering is deployment-unsafe.
     */
    val assentedAtMs: Long,
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

/**
 * Durable monotonic clock: preserves monotonicity ACROSS restarts.
 *
 * SystemMonotonicClock is monotonic only within a process — after a
 * restart its wall-clock origin can move backward, reopening replay and
 * revocation-bypass windows. This adapter persists a high-water mark to
 * [stateFile] and never reports a time below it: on startup the clock
 * resumes at max(wall, persisted + throttle) — see the crash-safety
 * argument in [nowMs].
 *
 * Persistence is THROTTLED: at most one state-file write per
 * [PERSIST_THROTTLE_MS] of clock advance. nowMs() itself stays exact on
 * every tick; only the durable copy lags, and the lag is bounded.
 *
 * There is no close/flush hook: for a clean shutdown, tick once more
 * before exit to narrow the persist lag to ~0.
 *
 * Thread-safe. A missing or corrupt state file falls back to wall time
 * (fail-open only against an attacker who can delete the file — that
 * attacker already owns the host; document the file's integrity
 * requirement in deployment). Writes are best-effort: if the file cannot
 * be written, crash-monotonicity degrades to the wall clock — monitor the
 * file in production.
 */
class DurableMonotonicClock(
    private val stateFile: java.io.File,
    private val inner: MonotonicClock = SystemMonotonicClock(),
    /**
     * Persistence sink, separated for testability: the throttle is verified
     * by counting sink invocations. Defaults to the state file.
     */
    internal val persist: (Long) -> Unit = { t -> writeStateFile(stateFile, t) },
) : MonotonicClock {
    companion object {
        /**
         * Persistence throttle: at most one write per interval of clock
         * advance. Also the worst-case forward jump on restart (see below).
         */
        const val PERSIST_THROTTLE_MS: Long = 1000L

        private fun saturatingAdd(a: Long, b: Long): Long =
            if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b

        private fun writeStateFile(f: java.io.File, t: Long) {
            try {
                f.parentFile?.mkdirs()
                f.writeText(t.toString())
            } catch (_: Exception) {
                // Persistence is best-effort; monotonicity within this process
                // still holds via the in-memory high-water mark.
            }
        }
    }

    /** Raw persisted value; null when the file is missing or corrupt. */
    private val persisted: Long? = readPersisted()

    /**
     * Crash-recovery: the persisted value may lag the pre-crash in-memory
     * high-water mark by up to PERSIST_THROTTLE_MS (throttled writes — see
     * nowMs for why the lag is strictly bounded). Resuming from
     * persisted + throttle (saturating) therefore starts strictly above any
     * value this clock ever returned, so the clock can never move backward
     * across a restart. Cost: a forward jump of up to PERSIST_THROTTLE_MS
     * per restart; repeated restarts ratchet forward by up to the throttle
     * each time.
     */
    private var highWater: Long =
        persisted?.let { saturatingAdd(it, PERSIST_THROTTLE_MS) } ?: Long.MIN_VALUE

    /** Clock value at the last persist; null until the first write. */
    private var lastWriteAt: Long? = persisted

    private fun readPersisted(): Long? = try {
        stateFile.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
    } catch (_: Exception) {
        null
    }

    @Synchronized
    override fun nowMs(): Long {
        val t = maxOf(inner.nowMs(), highWater)
        highWater = t
        // Throttled persistence. highWater only advances here, and a tick
        // that advanced it by >= PERSIST_THROTTLE_MS since the last write
        // persists synchronously — so at every completed tick,
        // highWater - (last persisted value) < PERSIST_THROTTLE_MS. That
        // strict bound is what makes the resume arithmetic above airtight:
        // no observed pre-crash value can exceed persisted + throttle.
        // (lastWriteAt <= t always: highWater is non-decreasing, so the
        // subtraction cannot overflow.)
        if (lastWriteAt == null || t - lastWriteAt!! >= PERSIST_THROTTLE_MS) {
            persist(t)
            lastWriteAt = t
        }
        return t
    }
}

/** Deterministic clock for tests. */
class FakeClock(var t: Long = 0L) : MonotonicClock {
    override fun nowMs(): Long = t
    fun advance(ms: Long) { t += ms }
}
