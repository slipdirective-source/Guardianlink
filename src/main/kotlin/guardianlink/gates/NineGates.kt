package guardianlink.gates

import guardianlink.audit.MerkleAuditLog
import kotlin.math.sqrt

/**
 * Injected verification ports. Cryptography, governance policy, and the
 * substrate transition plug in here; the engine itself stays pure,
 * deterministic, and fail-closed. Every function must be total over its
 * inputs — verification may fail (return false), never throw.
 */
interface Verifiers {
    /** Gate 1, theta_sig: Verify_{K_p}(m, s). */
    fun verifySignature(message: ByteArray, signature: ByteArray): Boolean

    /** Gate 3, theta_zk: VerifyZK(VK, x_pub, pi_zk). */
    fun verifyZk(proof: ByteArray, publicInputs: ByteArray): Boolean

    /**
     * Gate 7, theta_assent: Verify_{K_p}(r, t_assent, actionId, s_a).
     *
     * ASSENT BINDING CONTRACT: the signature MUST bind the triple
     * (rendering, assentedAtMs, actionId). The engine passes all three;
     * binding them is what defeats backdating (skip the cooling window),
     * refreshing (defeat idle expiry), and replay (one assent for any
     * identical action). A verifier that checks the signature against the
     * rendering alone honors the letter of this port and defeats its
     * purpose — that is deployment-unsafe, the same class of error as
     * RailAuthorizer.PERMISSIVE.
     */
    fun verifyAssent(rendering: String, assentedAtMs: Long, actionId: String, signature: ByteArray): Boolean

    /**
     * Revocation feed for an action. The engine owns no revocation list;
     * revocations arrive through this port so the deployment — not the
     * request — controls the feed. Called at Gate 8 AND again immediately
     * before the seal (narrowing the check/commit gap); implementations
     * must tolerate repeated calls. Throwing fails closed like every
     * other port.
     */
    fun revocationsFor(actionId: String): List<Revocation>

    /** Gate 8, theta_norev: validity of a signed revocation. */
    fun verifyRevocation(revocation: Revocation): Boolean

    /** Gate 2, phi_perm: (a, c) ∈ M_policy — boundary permission. */
    fun boundaryPermitted(action: Action, substrate: SubstrateState): Boolean

    /** Gate 6, psi_rules: conjunction of governance rules over a(x). */
    fun policyRules(action: Action, substrate: SubstrateState): Boolean

    /** Gate 6, psi_align: divergence between action and governance goals. */
    fun governanceDivergence(action: Action): Double

    /** Gate 5, theta_loop: the meta-cognitive loop ran to a consistent state. */
    fun metaLoopConsistent(context: GateContext): Boolean

    /**
     * Gate 8, theta_atom: the substrate transition. Pure and total —
     * computing x' = ApplyAction(x, a) is the *prepare* phase; the caller
     * commits by adopting [GateOutcome.Integrated.newSubstrate]. The seal
     * is never post-hoc: no mutation happens inside the engine.
     */
    fun applyAction(substrate: SubstrateState, action: Action): SubstrateState
}

/** Outcome of the Nine Gates evaluation (Codex v2.2, §V). */
sealed interface GateOutcome {
    /** S_9 — Sovereign Integration. Carry the prepared substrate for commit,
     * plus the Merkle root of the seal entry, so a composition layer can
     * anchor the seal externally without touching the engine's ledger. */
    data class Integrated(val newSubstrate: SubstrateState, val sealRoot: String) : GateOutcome

    /** S_HALT — absorbing. Names the gate that failed and why. */
    data class Halted(val atGate: Int, val reason: String) : GateOutcome
}

/**
 * The Nine Gates finite state machine (Codex v2.2, §§III–V).
 *
 * M = (Q, Sigma, delta, q_0, F, P); Q = {S_0..S_9, S_HALT}; F = {S_9}.
 * delta(S_k, sigma) = S_{k+1} iff P_k(sigma) == 1, else S_HALT.
 * S_HALT is absorbing. P_NineGates = AND_{k=0..8} P_k.
 *
 * Fail-closed invariant (§I.4): any ambiguity, policy violation, or
 * unverified mutation drops execution to S_HALT. Every S_HALT transition
 * — including revocations — is appended to the ledger M.
 *
 * INSTRUMENT OWNERSHIP: the engine owns its clock and ledger. They are
 * constructor-supplied (defaulting to the system monotonic clock and a
 * fresh in-process ledger) and NEVER taken from the request — a request
 * cannot substitute the engine's time source or audit sink, which is what
 * the per-request GateContext ledger/clock allowed. Revocations likewise
 * arrive through the Verifiers.revocationsFor port, not the request.
 * Deployments wire a DurableMonotonicClock and a real external anchor;
 * the demo defaults are process-local only.
 */
class NineGates(
    private val rails: Rails,
    private val verifiers: Verifiers,
    val clock: MonotonicClock = SystemMonotonicClock(),
    val ledger: MerkleAuditLog = MerkleAuditLog(),
) {

    /**
     * Fail-closed verifier invocation. Ports are contracted total
     * (verification may fail, never throw); a throwing port is treated
     * as a failed verification — the engine halts, the exception never
     * escapes.
     *
     * Catches Throwable, not just Exception: a hostile or buggy adapter
     * can throw Error (AssertionError, StackOverflowError from deliberate
     * recursion, custom Error subclasses). Letting those escape would
     * bypass the halt ledger — the engine must halt, nothing escapes.
     */
    private inline fun <T> checked(block: () -> T): T? =
        try {
            block()
        } catch (_: Throwable) {
            null
        }

    /**
     * Single-evaluation slot: the substrate transition is prepared EXACTLY
     * ONCE per evaluate() call. Gate 6 fills the slot; Gate 8 and the seal
     * consume it. The adapter is never asked twice, so a stateful or
     * nondeterministic adapter cannot validate one result and seal another.
     * Fresh per evaluate() — never shared across evaluations or threads.
     */
    private class Prepared {
        var state: SubstrateState? = null
        var threw: Boolean = false
    }

    private fun prepare(ctx: GateContext, prep: Prepared): SubstrateState? {
        prep.state?.let { return it }
        if (prep.threw) return null
        val s = checked { verifiers.applyAction(ctx.substrate, ctx.action) }
        if (s == null) {
            prep.threw = true
            return null
        }
        prep.state = s
        return s
    }

    fun evaluate(ctx: GateContext): GateOutcome {
        val prep = Prepared()
        // theta_assent_idle needs the ledger's last-append time as of ENTRY:
        // halt/seal records are appended during the run, so a live read
        // inside Gate 7 would let this evaluation's own appends fake
        // liveness and make the idle check vacuous. The ledger is
        // engine-owned, so the snapshot is the engine's own record — not a
        // caller-supplied value.
        val ledgerIdleSnapshotMs = ledger.lastAppendMs()
        for (k in 0..8) {
            val reason = when (k) {
                0 -> gate0(ctx)
                1 -> gate1(ctx)
                2 -> gate2(ctx)
                3 -> gate3(ctx)
                4 -> gate4(ctx)
                5 -> gate5(ctx)
                6 -> gate6(ctx, prep)
                7 -> gate7(ctx, ledgerIdleSnapshotMs)
                8 -> gate8(ctx, prep)
                else -> "unreachable gate $k"
            }
            if (reason != null) {
                appendAudit("HALT gate=$k action=${ctx.actionId} reason=$reason")
                return GateOutcome.Halted(k, reason)
            }
        }
        // Narrow the revocation check/commit gap: re-scan revocations
        // immediately before sealing. A revocation that became visible (or
        // valid) after Gate 8's scan still halts here instead of sealing.
        revocationHaltReason(ctx)?.let { reason ->
            appendAudit("HALT gate=8 action=${ctx.actionId} reason=$reason")
            return GateOutcome.Halted(8, reason)
        }
        // Gate 8 seal: commit the PREPARED transition — the same state Gate 6
        // (psi_inv) and Gate 8 (theta_atom) validated. No second evaluation.
        // All gates passed, so the slot is filled; the null branch is
        // unreachable defense-in-depth.
        val newSubstrate = prep.state
        if (newSubstrate == null) {
            val reason = "theta_atom: prepared transition missing (fail-closed)"
            appendAudit("HALT gate=8 action=${ctx.actionId} reason=$reason")
            return GateOutcome.Halted(8, reason)
        }
        appendAudit("SEAL action=${ctx.actionId} rendering=${ctx.rendering}")
        return GateOutcome.Integrated(newSubstrate, sealRoot = ledger.root)
    }

    /**
     * Engine-attested audit append. The timestamp comes from the
     * engine-owned clock — never from the request — closing the
     * caller-supplied-timestamp forgery vector for engine records.
     * (External appenders using MerkleAuditLog directly keep the
     * documented caller-supplied caveat.)
     */
    private fun appendAudit(payload: String) {
        ledger.append(payload.toByteArray(), clock.nowMs())
    }

    // GATE 0: VOID / SIGNAL DISCRIMINATION & ADMISSIBILITY
    private fun gate0(ctx: GateContext): String? {
        // NaN is never < gammaNoise: without this guard a NaN SNR
        // sails through the noise check. Non-finite input is hostile.
        if (!ctx.signal.snr.isFinite()) return "theta_snr: non-finite SNR (fail-closed)"
        if (ctx.signal.snr < rails.gammaNoise) return "theta_snr: SNR ${ctx.signal.snr} < ${rails.gammaNoise}"
        if (!ctx.signal.wellFormed) return "theta_syn: malformed signal header"
        if (!validateAction(ctx.action, rails)) return "theta_ast: action not in A_total (unbounded or empty)"
        return null
    }

    // GATE 1: IDENTITY & INTENT ORIGIN
    private fun gate1(ctx: GateContext): String? {
        val sigOk = checked { verifiers.verifySignature(ctx.keys.message, ctx.keys.signature) }
            ?: return "theta_sig: signature verifier threw (fail-closed)"
        if (!sigOk) return "theta_sig: signature verification failed"
        val d = euclidean(ctx.keys.biometricTemplate, ctx.keys.enrolledTemplate)
            ?: return "theta_bio: template dimension mismatch (fail-closed)"
        if (!d.isFinite()) return "theta_bio: non-finite biometric distance (fail-closed)"
        if (d > rails.epsilonBio) return "theta_bio: biometric distance $d > ${rails.epsilonBio}"
        return null
    }

    // GATE 2: BOUNDARY ENFORCEMENT
    private fun gate2(ctx: GateContext): String? {
        // phi_iso: the action may only name records present in the substrate.
        val named = namedRecords(ctx.action)
        val unknown = named - ctx.substrate.records.keys
        if (unknown.isNotEmpty()) return "phi_iso: action names records outside substrate: $unknown"
        // phi_perm: delegated to the boundary-permission port.
        val permitted = checked { verifiers.boundaryPermitted(ctx.action, ctx.substrate) }
            ?: return "phi_perm: boundary verifier threw (fail-closed)"
        if (!permitted) return "phi_perm: (action, context) not in M_policy"
        // phi_leak: holds by construction — Action is a closed AST with no
        // external-effect nodes, so External_Entropy(a(x)) == 0 structurally.
        return null
    }

    // GATE 3: ZERO-KNOWLEDGE CONTEXT AUDIT
    private fun gate3(ctx: GateContext): String? {
        val zkOk = checked { verifiers.verifyZk(ctx.proofs.zkProof, ctx.proofs.publicInputs) }
            ?: return "theta_zk: proof verifier threw (fail-closed)"
        if (!zkOk) return "theta_zk: proof verification failed"
        val tau = clock.nowMs()
        val issuedAt = ctx.proofs.issuedAtMs
        // A proof dated in the future is clock fraud or a replay window:
        // fail closed. The old abs() tolerance accepted slightly-future
        // proofs — that is fail-open and is removed.
        if (issuedAt > tau)
            return "theta_time: proof issued in the future (t_pi=$issuedAt > tau=$tau)"
        // issuedAt <= tau here, so (tau - issuedAt) is mathematically >= 0.
        // A negative computed age means the subtraction overflowed (issuedAt
        // near Long.MIN_VALUE) — an ancient timestamp, not a fresh one.
        // Without this check the overflow wraps negative and defeats the
        // freshness window entirely.
        val age = tau - issuedAt
        if (age < 0 || age > rails.deltaT)
            return "theta_time: proof outside freshness window (tau=$tau, t_pi=$issuedAt)"
        return null
    }

    // GATE 4: NEGENTROPY & ORDER FILTER
    private fun gate4(ctx: GateContext): String? {
        // NaN is never > hMax: without this guard a NaN entropy
        // sails through the filter.
        if (!ctx.contextEntropyBits.isFinite()) return "theta_ent: non-finite entropy (fail-closed)"
        if (ctx.contextEntropyBits > rails.hMax)
            return "theta_ent: context entropy ${ctx.contextEntropyBits} > ${rails.hMax} bits"
        if (ctx.contextParseTrees != 1)
            return "theta_unamb: ${ctx.contextParseTrees} parse trees, need exactly 1"
        return null
    }

    // GATE 5: META-COGNITIVE SELF-AUDIT LOOP
    private fun gate5(ctx: GateContext): String? {
        val drift = cosineDistance(ctx.intentVector, ctx.currentVector)
            ?: return "theta_drift: intent vector dimension mismatch (fail-closed)"
        // NaN drift is never > epsilonDrift: without this guard it authorizes.
        // epsilon_drift is a fixed rail: drift may halt an action, never authorize one.
        if (!drift.isFinite()) return "theta_drift: non-finite drift (fail-closed)"
        if (drift > rails.epsilonDrift) return "theta_drift: intent drift $drift > ${rails.epsilonDrift}"
        val loopOk = checked { verifiers.metaLoopConsistent(ctx) }
            ?: return "theta_loop: meta-loop verifier threw (fail-closed)"
        if (!loopOk) return "theta_loop: meta-cognitive loop inconsistent"
        return null
    }

    // GATE 6: GOVERNANCE FILTER
    private fun gate6(ctx: GateContext, prep: Prepared): String? {
        val rulesOk = checked { verifiers.policyRules(ctx.action, ctx.substrate) }
            ?: return "psi_rules: governance verifier threw (fail-closed)"
        if (!rulesOk) return "psi_rules: governance rule failed"
        // psi_inv: core invariants of the transition — no record may appear
        // from nothing (the AST has no create node) and no field map may be null.
        // This call FILLS the single-evaluation slot (see prepare()).
        val next = prepare(ctx, prep)
            ?: return "psi_inv: transition verifier threw (fail-closed)"
        val created = next.records.keys - ctx.substrate.records.keys
        if (created.isNotEmpty()) return "psi_inv: transition creates records: $created"
        val divergence = checked { verifiers.governanceDivergence(ctx.action) }
            ?: return "psi_align: divergence verifier threw (fail-closed)"
        if (!divergence.isFinite()) return "psi_align: non-finite divergence (fail-closed)"
        if (divergence > rails.epsilonGovernance)
            return "psi_align: governance divergence $divergence > ${rails.epsilonGovernance}"
        return null
    }

    // GATE 7: ASSENT & BOUNDED ALLOCATION
    private fun gate7(ctx: GateContext, ledgerIdleSnapshotMs: Long?): String? {
        // theta_render: the person signs Render(a), not the payload.
        if (ctx.rendering != render(ctx.action))
            return "theta_render: shown rendering != Render(a)"
        val assent = ctx.assent ?: return "theta_assent: no assent signature present"
        // The signature must bind (rendering, assentedAtMs, actionId) —
        // see the Verifiers.verifyAssent contract. Backdating, refreshing,
        // and replay are defeated by the binding, not by this gate alone.
        val assentOk = checked { verifiers.verifyAssent(ctx.rendering, ctx.assentedAtMs, ctx.actionId, assent.signature) }
            ?: return "theta_assent: assent verifier threw (fail-closed)"
        if (!assentOk) return "theta_assent: assent signature invalid"
        // theta_assent_idle: an outstanding assent dies only when BOTH hold:
        //   (a) the assent itself is older than maxAssentIdleMs, AND
        //   (b) the audit ledger has been silent longer than maxAssentIdleMs.
        // A fresh assent on a quiet system still authorizes (the person is
        // present); an old assent on a continuously-logging system rode along
        // under observation; but an old assent plus a dark ledger means the
        // world moved unobserved — fail closed. The snapshot was taken at
        // evaluate() entry (see above).
        val tau = clock.nowMs()
        val assentedAt = ctx.assentedAtMs
        // A future-dated assent is clock fraud or a replay window: fail closed.
        if (assentedAt > tau)
            return "theta_assent: assent dated in the future (t_assent=$assentedAt > tau=$tau)"
        // assentedAt <= tau here, so (tau - assentedAt) is mathematically >= 0.
        // A negative computed age means the subtraction overflowed (assentedAt
        // near Long.MIN_VALUE) — an ancient timestamp, not a fresh one:
        // treat as expired, fail closed.
        val assentAge = tau - assentedAt
        if (assentAge < 0)
            return "theta_assent: assent age overflow — treated as expired (fail-closed)"
        val ledgerIdle = ledgerIdleMs(tau, ledgerIdleSnapshotMs, assentAge)
        if (assentAge > rails.maxAssentIdleMs && ledgerIdle > rails.maxAssentIdleMs)
            return "theta_assent_idle: assent expired — age ${assentAge}ms and ledger silent ${ledgerIdle}ms (max ${rails.maxAssentIdleMs}ms)"
        // theta_cool: fixed-rail window scaling with COMPUTED impact.
        val window = rails.coolingWindowMs(impactOf(ctx.action))
        if (assentAge < window)
            return "theta_cool: cooling window not elapsed ($assentAge < $window ms)"
        // theta_mem: static bounds from the AST.
        if (estimatedMemoryBytes(ctx.action, rails) > rails.maxMemoryBytes)
            return "theta_mem: memory estimate exceeds allocation"
        if (estimatedCycles(ctx.action, rails) > rails.maxCycles)
            return "theta_mem: cycle estimate exceeds allocation"
        return null
    }

    /**
     * Ledger silence duration in ms, overflow-safe.
     *
     * [snapshot] is lastAppendMs() taken at evaluate() entry (null = empty
     * ledger). A null ledger falls back to [assentAge]: a brand-new ledger
     * plus an old assent is expired — there is no recorded observation for
     * the assent to have ridden along under.
     *
     * Arithmetic cases: snapshot <= tau makes (tau - snapshot)
     * mathematically >= 0 — a negative computed result means the
     * subtraction overflowed (snapshot near Long.MIN_VALUE, i.e. an ancient
     * append), so the true silence exceeds Long.MAX_VALUE and therefore the
     * rail: saturate to force the halt. snapshot > tau means the ledger
     * postdates the attested clock (clock-domain anomaly or future-dated
     * append): the ledger shows recorded activity, so treat as live (0).
     * Forged future ledger timestamps are a caller-supply trust-boundary
     * issue — the same forgery defeats the audit trail itself; the external
     * anchor is the recourse (see MerkleAuditLog.lastAppendMs docs).
     */
    private fun ledgerIdleMs(tau: Long, snapshot: Long?, assentAge: Long): Long {
        if (snapshot == null) return assentAge
        if (snapshot > tau) return 0L
        val idle = tau - snapshot
        // idle < 0 with snapshot <= tau is mathematically impossible — the
        // subtraction overflowed, so true silence > Long.MAX_VALUE > rail.
        return if (idle < 0) Long.MAX_VALUE else idle
    }

    // GATE 8: SOVEREIGN INTEGRATION & IMMUTABLE SEAL
    private fun gate8(ctx: GateContext, prep: Prepared): String? {
        // theta_norev: a signed revocation between Gate 7 and Gate 8 drops to S_HALT.
        revocationHaltReason(ctx)?.let { return it }
        // theta_atom: the transition is the PREPARED state from Gate 6 —
        // never recomputed. Well-formedness is re-checked on that same state.
        val next = prepare(ctx, prep)
            ?: return "theta_atom: transition verifier threw (fail-closed)"
        if (next.records.values.any { it == null }) return "theta_atom: malformed transition"
        // theta_merkle: the seal append happens in evaluate() after all gates pass.
        return null
    }

    /**
     * theta_norev scan. Fail-closed in both time directions: a VALID signed
     * revocation for this action halts whether it is dated in the past or
     * the future. A future-dated revocation is clock skew or fraud —
     * ignoring it (the old `revokedAtMs > tau → continue`) is fail-open.
     * Revocation timestamps order revocations for audit; they never gate
     * validity.
     *
     * Scanned at Gate 8 AND again immediately before the seal, narrowing
     * the check/commit gap to the instructions between the two scans. The
     * residual gap — a revocation issued after evaluate() returns but
     * before the caller commits Integrated.newSubstrate — is architectural:
     * the engine cannot observe the caller's commit. Callers MUST re-scan
     * revocations at commit time or hold a commit lock across
     * evaluate()+commit.
     */
    private fun revocationHaltReason(ctx: GateContext): String? {
        // Revocations arrive through the port — the deployment's feed, not
        // the request. A throwing feed fails closed like every other port.
        val revocations = checked { verifiers.revocationsFor(ctx.actionId) }
            ?: return "theta_norev: revocation feed threw (fail-closed)"
        for (r in revocations) {
            if (r.actionId != ctx.actionId) continue
            val valid = checked { verifiers.verifyRevocation(r) }
                ?: return "theta_norev: revocation verifier threw (fail-closed)"
            if (valid) return "theta_norev: valid signed revocation exists"
        }
        return null
    }

    private fun namedRecords(a: Action): Set<String> = when (a) {
        is Action.Read -> setOf(a.recordId)
        is Action.Write -> setOf(a.recordId)
        is Action.Delete -> setOf(a.recordId)
        is Action.Sequence -> a.steps.flatMap(::namedRecords).toSet()
        is Action.Guarded -> namedRecords(a.then) +
            (a.otherwise?.let(::namedRecords) ?: emptySet()) +
            conditionRecords(a.condition)
    }

    // phi_iso must cover records named by conditions too: a Guarded whose
    // condition reads a record outside the substrate is not isolated, even
    // when both branches only touch known records.
    private fun conditionRecords(c: Condition): Set<String> = when (c) {
        is Condition.FieldEquals -> setOf(c.recordId)
        is Condition.And -> c.parts.flatMap(::conditionRecords).toSet()
        is Condition.Not -> conditionRecords(c.inner)
    }

    private fun euclidean(u: DoubleArray, v: DoubleArray): Double? {
        if (u.size != v.size || u.isEmpty()) return null
        var sum = 0.0
        for (i in u.indices) { val d = u[i] - v[i]; sum += d * d }
        return sqrt(sum)
    }

    private fun cosineDistance(u: DoubleArray, v: DoubleArray): Double? {
        if (u.size != v.size || u.isEmpty()) return null
        var dot = 0.0; var nu = 0.0; var nv = 0.0
        for (i in u.indices) { dot += u[i] * v[i]; nu += u[i] * u[i]; nv += v[i] * v[i] }
        if (nu == 0.0 || nv == 0.0) return null
        return 1.0 - dot / sqrt(nu * nv)
    }
}
