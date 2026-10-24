package guardianlink.v2.core

import guardianlink.gates.Action
import guardianlink.gates.GateContext
import guardianlink.gates.SubstrateState
import guardianlink.gates.render
import guardianlink.v2.core.zk.PublicInputsCodec

/**
 * The deployment policy ports behind the engine's Gates 2, 5, and 6
 * (EVIDENCE-PATH build step 2), implemented from the v2 core's OWN
 * governance state. Wired into ProductionVerifiers in place of its
 * deny defaults. Every port is total and fail-closed: an exception
 * yields deny / +Inf, exactly the engine's own convention — and
 * ProductionVerifiers additionally wraps each port in its own
 * try/catch, so a throw here can never become a pass there.
 *
 * The ports evaluate the CORE's governance record of the action, found
 * by reverse-mapping the engine action to the core proposal it was
 * translated from (EngineTranslation is deterministic, so the mapping
 * is recomputed, not stored). An action with no matching live core
 * proposal is an action the harness never governed: denied.
 *
 * GATE 2 — boundaryCheck (phi_perm, structural boundary):
 * Permits only the action SHAPES this deployment translates:
 *  - Read of any projected record.
 *  - Write to collector/<name> records, only the fields the translator
 *    emits (status, statusReason), only for a collector the core
 *    registry actually holds.
 *  - Write to harness/meta, only the translator's fields
 *    (lastStandingQuestion, lastRailCommitted).
 * Everything else — Delete, writes to account/plug/rail/standing
 * question records, writes with foreign fields, sequences/guards the
 * translator never produces — is outside the boundary. This port
 * answers "is this action inside the deployment's structural
 * boundary?", NOT "did policy approve it?" (that is Gate 6's port).
 *
 * GATE 6 — policyCheck (psi_rules, the core's policy decision):
 * Finds the core proposal this action was translated from and requires
 * its CURRENT core policy state to be executable:
 *  - EXECUTABLE_UNDER_RAIL: covered by a committed rail.
 *  - NEEDS_FRESH_ASSENT: only with a recorded sovereign assent event
 *    bound to the proposal (the core's own assent record — the engine
 *    ceremony is the second, cryptographic binding of the same act).
 * DENIED / DRIFT_FLAGGED / INSUFFICIENTLY_SPECIFIED proposals, already
 * executed or refused proposals, and actions with no proposal: denied.
 * Policy is re-read at gate time, so a rail committed or a drift flag
 * raised after translation is honored — the same re-evaluation the
 * core's ActionLayer performs at its own commit point.
 *
 * GATE 6 — divergenceFn (psi_align): see [divergence] for the mapping.
 *
 * GATE 5 — loopCheck (theta_loop, meta-cognitive consistency):
 * A redundancy check over the assembled context. It establishes that
 * the bundle AS PRESENTED is internally coherent:
 *  - the rendering is exactly Render(action) (the same equality Gate 7
 *    enforces — a bundle that disagrees with itself about what was
 *    shown fails here first);
 *  - every record the action names exists in the presented substrate;
 *  - intent and current vectors are same-length, non-empty, finite;
 *  - the entropy measurement is finite;
 *  - the parse-tree count is exactly 1.
 *  - the Gate 3 cross-binding the engine cannot do (ZK-GATE3-DESIGN
 *    §4.3): the engine treats publicInputs as opaque, so NOTHING in
 *    NineGates compares the GLZK1 block's fields against the bundle.
 *    This port does: the block must decode, and its actionId must
 *    equal the context's, its renderingHash must equal SHA-256 of
 *    the context's rendering, and its issuedAtMs must equal
 *    Proofs.issuedAtMs. Any parse failure or mismatch is incoherent —
 *    a proof minted for action A presented on action B dies here,
 *    even if it would verify at Gate 3 on its own terms.
 * What it does NOT establish: that any evidence is TRUE. Truth comes
 * from the cryptographic gates and the biometric port; this port is
 * the loop's self-consistency pass, and it is documented as such
 * rather than dressed as more.
 */
class CorePolicyPorts(private val coreProvider: () -> GuardianLink) {

    /** Gate 2 port: structural boundary over (action, substrate). */
    fun boundaryCheck(action: Action, substrate: SubstrateState): Boolean =
        try {
            boundary(action, substrate)
        } catch (_: Exception) {
            false
        }

    /** Gate 6 port: the core's policy decision for the matching proposal. */
    fun policyCheck(action: Action, substrate: SubstrateState): Boolean =
        try {
            val core = coreProvider()
            val proposal = matchingProposal(core, action, substrate) ?: return false
            when (proposal.policy) {
                PolicyOutcome.EXECUTABLE_UNDER_RAIL -> true
                PolicyOutcome.NEEDS_FRESH_ASSENT -> core.actions.hasAssent(proposal.id)
                else -> false
            }
        } catch (_: Exception) {
            false
        }

    /**
     * Gate 6 divergence port (psi_align). Finite and honestly derived
     * from the core's own trajectory/conflict signals — never a
     * constant: a constant 0.0 would be a rubber stamp, and +Inf is
     * reserved for port failure (ProductionVerifiers maps exceptions
     * and non-finite returns to +Inf itself).
     *
     * Mapping (deliberately coarse and monotone; each term is a count
     * of something the core actually maintains):
     *   divergence = 0.05 * C + 0.05 * D, capped at 0.15, where
     *   C = number of standing questions whose current answer carries
     *       unresolved conflicts, capped at 2;
     *   D = 1 when the Trajectory answer reports a drift flag in its
     *       current text, else 0.
     * A settled core reads 0.0. One conflicted question reads 0.05
     * (under the 0.1 rail: friction, not a halt). Two conflicted
     * questions plus active drift reads 0.15 (over the rail: the
     * action halts at psi_align while the world is that unsettled).
     * This is an aggregation of the harness's own signals, documented
     * as such — it is not a probability and claims no finer meaning.
     */
    fun divergence(action: Action): Double =
        try {
            val core = coreProvider()
            val questions = core.questions.all()
            val conflicted = questions.count { q -> q.current.conflicts.isNotEmpty() }.coerceAtMost(2)
            val drift = questions.any { q ->
                q.cluster == ClusterId.TRAJECTORY && q.current.text.contains("DRIFT FLAGGED")
            }
            (0.05 * conflicted + if (drift) 0.05 else 0.0).coerceAtMost(0.15)
        } catch (_: Exception) {
            Double.POSITIVE_INFINITY
        }

    /** Gate 5 port: internal coherence of the assembled context. */
    fun loopCheck(context: GateContext): Boolean {
        return try {
            if (context.rendering != render(context.action)) return false
            val named = namedRecords(context.action)
            if (!context.substrate.records.keys.containsAll(named)) return false
            if (context.intentVector.isEmpty() || context.intentVector.size != context.currentVector.size) return false
            if (!context.intentVector.all { it.isFinite() } || !context.currentVector.all { it.isFinite() }) return false
            if (!context.contextEntropyBits.isFinite()) return false
            if (context.contextParseTrees != 1) return false
            // §4.3 cross-binding: the GLZK1 block must decode and its
            // bound fields must equal the bundle's own fields.
            val parsed = try {
                PublicInputsCodec.decode(context.proofs.publicInputs)
            } catch (_: Exception) {
                return false
            }
            if (parsed.actionId != context.actionId) return false
            val renderingHash = PublicInputsCodec.sha256(context.rendering.toByteArray(Charsets.UTF_8))
            if (!parsed.renderingHash.contentEquals(renderingHash)) return false
            if (parsed.issuedAtMs != context.proofs.issuedAtMs) return false
            true
        } catch (_: Exception) {
            false
        }
    }

    /* ---------------- internals ---------------- */

    private fun boundary(action: Action, substrate: SubstrateState): Boolean =
        when (action) {
            is Action.Read -> substrate.records.containsKey(action.recordId)
            is Action.Write -> writeWithinBoundary(action, substrate)
            is Action.Delete -> false
            is Action.Sequence -> action.steps.all { boundary(it, substrate) }
            is Action.Guarded -> boundary(action.then, substrate) &&
                (action.otherwise?.let { boundary(it, substrate) } ?: true)
        }

    private fun writeWithinBoundary(action: Action.Write, substrate: SubstrateState): Boolean {
        if (!substrate.records.containsKey(action.recordId)) return false
        return when {
            action.recordId == EngineTranslation.META_RECORD ->
                action.fields.keys.all { it == "lastStandingQuestion" || it == "lastRailCommitted" }
            action.recordId.startsWith("collector/") -> {
                val name = action.recordId.removePrefix("collector/")
                val core = coreProvider()
                action.fields.keys.all { it == "status" || it == "statusReason" } &&
                    core.collectors.all().any { c -> EngineTranslation.sanitizeId(c.name) == name }
            }
            else -> false
        }
    }

    /**
     * The core proposal [action] was translated from, found by
     * re-running the deterministic translation over live proposals.
     * Executed/refused/expired proposals do not match: their actions
     * are spent, and re-presenting one is a replay, not a proposal.
     */
    private fun matchingProposal(core: GuardianLink, action: Action, substrate: SubstrateState): ActionProposal? =
        core.actions.allProposals()
            .filter { p -> p.status == ProposalStatus.PROPOSED }
            .firstOrNull { p ->
                val t = EngineTranslation.translate(p, substrate)
                t is TranslationResult.Ok && t.action == action
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

    private fun conditionRecords(c: guardianlink.gates.Condition): Set<String> = when (c) {
        is guardianlink.gates.Condition.FieldEquals -> setOf(c.recordId)
        is guardianlink.gates.Condition.And -> c.parts.flatMap(::conditionRecords).toSet()
        is guardianlink.gates.Condition.Not -> conditionRecords(c.inner)
    }
}
