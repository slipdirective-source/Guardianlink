package guardianlink.gates

import kotlin.math.abs
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

    /** Gate 7, theta_assent: Verify_{K_p}(r, s_a). */
    fun verifyAssent(rendering: String, signature: ByteArray): Boolean

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
    /** S_9 — Sovereign Integration. Carry the prepared substrate for commit. */
    data class Integrated(val newSubstrate: SubstrateState) : GateOutcome

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
 */
class NineGates(private val rails: Rails, private val verifiers: Verifiers) {

    /**
     * Fail-closed verifier invocation. Ports are contracted total
     * (verification may fail, never throw); a throwing port is treated
     * as a failed verification — the engine halts, the exception never
     * escapes. Defense in depth around the plugin boundary.
     */
    private inline fun <T> checked(block: () -> T): T? =
        try {
            block()
        } catch (_: Exception) {
            null
        }

    fun evaluate(ctx: GateContext): GateOutcome {
        for (k in 0..8) {
            val reason = when (k) {
                0 -> gate0(ctx)
                1 -> gate1(ctx)
                2 -> gate2(ctx)
                3 -> gate3(ctx)
                4 -> gate4(ctx)
                5 -> gate5(ctx)
                6 -> gate6(ctx)
                7 -> gate7(ctx)
                8 -> gate8(ctx)
                else -> "unreachable gate $k"
            }
            if (reason != null) {
                ctx.ledger.append("HALT gate=$k action=${ctx.actionId} reason=$reason".toByteArray())
                return GateOutcome.Halted(k, reason)
            }
        }
        // Gate 8 seal: prepare x' purely, then seal the transition in M.
        // A throwing transition port halts instead of escaping.
        val newSubstrate = checked { verifiers.applyAction(ctx.substrate, ctx.action) }
        if (newSubstrate == null) {
            val reason = "theta_atom: transition verifier threw (fail-closed)"
            ctx.ledger.append("HALT gate=8 action=${ctx.actionId} reason=$reason".toByteArray())
            return GateOutcome.Halted(8, reason)
        }
        ctx.ledger.append(
            "SEAL action=${ctx.actionId} rendering=${ctx.rendering}".toByteArray()
        )
        return GateOutcome.Integrated(newSubstrate)
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
        val tau = ctx.clock.nowMs()
        if (abs(tau - ctx.proofs.issuedAtMs) > rails.deltaT)
            return "theta_time: proof outside freshness window (tau=$tau, t_pi=${ctx.proofs.issuedAtMs})"
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
    private fun gate6(ctx: GateContext): String? {
        val rulesOk = checked { verifiers.policyRules(ctx.action, ctx.substrate) }
            ?: return "psi_rules: governance verifier threw (fail-closed)"
        if (!rulesOk) return "psi_rules: governance rule failed"
        // psi_inv: core invariants of the transition — no record may appear
        // from nothing (the AST has no create node) and no field map may be null.
        val next = checked { verifiers.applyAction(ctx.substrate, ctx.action) }
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
    private fun gate7(ctx: GateContext): String? {
        // theta_render: the person signs Render(a), not the payload.
        if (ctx.rendering != render(ctx.action))
            return "theta_render: shown rendering != Render(a)"
        val assent = ctx.assent ?: return "theta_assent: no assent signature present"
        val assentOk = checked { verifiers.verifyAssent(ctx.rendering, assent.signature) }
            ?: return "theta_assent: assent verifier threw (fail-closed)"
        if (!assentOk) return "theta_assent: assent signature invalid"
        // theta_cool: fixed-rail window scaling with COMPUTED impact.
        val window = rails.coolingWindowMs(impactOf(ctx.action))
        val elapsed = ctx.clock.nowMs() - ctx.assentedAtMs
        if (elapsed < window)
            return "theta_cool: cooling window not elapsed ($elapsed < $window ms)"
        // theta_mem: static bounds from the AST.
        if (estimatedMemoryBytes(ctx.action, rails) > rails.maxMemoryBytes)
            return "theta_mem: memory estimate exceeds allocation"
        if (estimatedCycles(ctx.action, rails) > rails.maxCycles)
            return "theta_mem: cycle estimate exceeds allocation"
        return null
    }

    // GATE 8: SOVEREIGN INTEGRATION & IMMUTABLE SEAL
    private fun gate8(ctx: GateContext): String? {
        // theta_norev: a signed revocation between Gate 7 and Gate 8 drops to S_HALT.
        val tau = ctx.clock.nowMs()
        for (r in ctx.revocations) {
            if (r.actionId != ctx.actionId || r.revokedAtMs > tau) continue
            val valid = checked { verifiers.verifyRevocation(r) }
                ?: return "theta_norev: revocation verifier threw (fail-closed)"
            if (valid) return "theta_norev: valid signed revocation exists"
        }
        // theta_atom: the transition is exactly the pure function's result —
        // deterministic by construction; well-formedness is the check.
        val next = checked { verifiers.applyAction(ctx.substrate, ctx.action) }
            ?: return "theta_atom: transition verifier threw (fail-closed)"
        if (next.records.values.any { it == null }) return "theta_atom: malformed transition"
        // theta_merkle: the seal append happens in evaluate() after all gates pass.
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
