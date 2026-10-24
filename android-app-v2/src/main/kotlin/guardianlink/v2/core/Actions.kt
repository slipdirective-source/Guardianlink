package guardianlink.v2.core

import guardianlink.v2.core.zk.RailZkParam
import guardianlink.v2.core.zk.RailZkRecord
import guardianlink.v2.core.zk.UnavailableZkBridge
import guardianlink.v2.core.zk.ZkBridgeException
import guardianlink.v2.core.zk.ZkParamDescriptor
import guardianlink.v2.core.zk.ZkProverBridge
import guardianlink.v2.core.zk.ZkQuantization

/** Port of TS `PolicyOutcome` in `src/actions.ts`; [wire] is the TS string. */
enum class PolicyOutcome(val wire: String) {
    EXECUTABLE_UNDER_RAIL("executable_under_rail"),
    NEEDS_FRESH_ASSENT("needs_fresh_assent"),
    DRIFT_FLAGGED("drift_flagged"),
    DENIED("denied"),
    INSUFFICIENTLY_SPECIFIED("insufficiently_specified"),
}

/** Port of TS `ConsequenceClass`. */
enum class ConsequenceClass(val wire: String) {
    LOCAL_LOW("local_low"),
    LOCAL("local"),
    EXTERNAL("external"),
    HIGH("high"),
}

enum class Reversibility(val wire: String) {
    REVERSIBLE("reversible"),
    PARTIALLY_REVERSIBLE("partially_reversible"),
    IRREVERSIBLE("irreversible"),
}

enum class RequiredAuthority(val wire: String) {
    RAIL("rail"),
    FRESH_ASSENT("fresh_assent"),
}

/** A pre-committed rail / standing order (spec §3.5). Port of TS `Rail`. */
data class Rail(
    val id: String,
    val actionType: String,
    val target: String,
    /** committed envelope: numeric params must not exceed these ceilings */
    val envelope: Map<String, Double>,
    val active: Boolean,
    val createdBy: String,
    val createdAt: String,
    /**
     * Gate 3 envelope commitments (ZK-GATE3-DESIGN §2.2/§5), computed
     * at commit time through the ZK bridge when one is available.
     * Null = this rail carries no commitments (committed without the
     * bridge) and is NOT Gate-3-provable; the ceremony refuses it at
     * begin() with that named reason rather than approximating.
     */
    val zk: RailZkRecord? = null,
)

enum class ProposalStatus(val wire: String) {
    PROPOSED("proposed"),
    EXECUTED("executed"),
    REFUSED("refused"),
    EXPIRED("expired"),
}

/** Port of TS `ActionProposal`. [id] is the ledger event id of the proposal. */
class ActionProposal(
    val id: String,
    val actionType: String,
    val target: String,
    val parameters: Map<String, Any?>,
    val expectedEffect: String,
    val reversibility: Reversibility,
    val consequenceClass: ConsequenceClass,
    val requiredAuthority: RequiredAuthority,
    var policy: PolicyOutcome,
    var policyReason: String,
    var status: ProposalStatus,
    val requestedBy: String,
)

data class ProposalInput(
    val actionType: String,
    val target: String,
    val parameters: Map<String, Any?> = emptyMap(),
    val expectedEffect: String,
    val reversibility: Reversibility,
    val consequenceClass: ConsequenceClass,
    val requiredAuthority: RequiredAuthority,
    val requestedBy: String,
)

enum class OutcomeStatus(val wire: String) {
    OBSERVED_SUCCESS("observed_success"),
    OBSERVED_FAILURE("observed_failure"),
    OUTCOME_UNKNOWN("outcome_unknown"),
    CANNOT_EXECUTE("cannot_execute"),
}

/** Port of TS `ExecuteResult`. */
data class ExecuteResult(
    val executed: Boolean,
    val message: String,
    val executionEventId: String? = null,
    val outcomeEventId: String? = null,
    val outcomeStatus: OutcomeStatus? = null,
    val payload: Any? = null,
)

/** Adapters actually implemented in this core. Anything else: propose/draft only. */
val LOCAL_ADAPTERS: List<String> =
    listOf("create_standing_question", "set_rail", "export_ledger", "pause_collector", "resume_collector")

/** Hard-denied action types: no rail and no assent can authorize these inside the harness. */
val DENIED_ACTIONS: List<String> =
    listOf("delete_ledger", "disable_audit", "forge_assent", "widen_own_capability")

/** Dependencies of the action layer. Port of TS `ActionDeps`. */
class ActionDeps(
    val ledger: Ledger,
    val audit: AuditLog,
    val questions: QuestionEngine,
    val pauseCollector: (String) -> Unit,
    val resumeCollector: (String) -> Unit,
    val exportLedger: () -> Any?,
    val now: V2Clock,
    /**
     * The Gate 3 prover bridge (ZK-GATE3-DESIGN §7.1). Defaults to
     * [UnavailableZkBridge]: rails then commit without envelope
     * commitments and are not Gate-3-provable (named at ceremony
     * begin, never silently).
     */
    val zkBridge: ZkProverBridge = UnavailableZkBridge,
)

/**
 * Governed action layer (spec §3.5). Port of TS `src/actions.ts`.
 *
 * Language does not execute merely because it was recognized: a request
 * becomes an ActionProposal, policy evaluates it (rail / fresh assent /
 * drift-flagged / denied / insufficiently specified), and execution runs
 * only through this layer, with outcome events written back to the
 * ledger. No adapter → "can propose/draft, cannot execute". No fake
 * success, ever.
 */
class ActionLayer(private val deps: ActionDeps) {

    private val rails = mutableListOf<Rail>()
    private val proposals = LinkedHashMap<String, ActionProposal>()
    private var railSeq = 0

    fun listRails(): List<Rail> = rails.toList()

    fun allProposals(): List<ActionProposal> = proposals.values.toList()

    /**
     * Persistence hook for [StateStore]: replace rails and proposals
     * wholesale with persisted state, including the rail sequence so
     * future rail ids cannot collide with restored ones. No audit
     * entries are written — a restore reproduces state; it does not
     * re-decide anything.
     */
    fun loadPersistedState(restoredRails: List<Rail>, restoredProposals: List<ActionProposal>, restoredRailSeq: Int) {
        rails.clear()
        rails.addAll(restoredRails)
        proposals.clear()
        for (p in restoredProposals) proposals[p.id] = p
        railSeq = restoredRailSeq
    }

    /** The persisted rail sequence (see [loadPersistedState]). */
    fun railSeqValue(): Int = railSeq

    /** The ZK prover bridge this layer commits envelope commitments through. */
    fun zkBridge(): ZkProverBridge = deps.zkBridge

    /** The active rail covering (actionType, target), if any — the rail a ceremony proves against. */
    fun railCovering(actionType: String, target: String): Rail? = railFor(actionType, target)

    fun addRail(
        actionType: String,
        target: String,
        envelope: Map<String, Double>,
        createdBy: String,
    ): Rail {
        railSeq += 1
        val railId = "rail-$railSeq"
        // Rail-commit commitments (ZK-GATE3-DESIGN §5): computed
        // BEFORE the rail exists, so an unprovable envelope refuses
        // the commit itself (§8 case 13) instead of surprising Gate 3.
        val zk = buildZkRecord(railId, envelope)
        val rail = Rail(
            id = railId,
            actionType = actionType,
            target = target,
            envelope = envelope,
            active = true,
            createdBy = createdBy,
            createdAt = IsoTime.format(deps.now.now()),
            zk = zk,
        )
        rails.add(rail)
        deps.audit.record(createdBy, "rail_created", mapOf("railId" to rail.id, "actionType" to actionType, "target" to target, "envelope" to envelope))
        return rail
    }

    private fun railFor(actionType: String, target: String): Rail? =
        rails.firstOrNull { r -> r.active && r.actionType == actionType && (r.target == target || r.target == "*") }

    /**
     * Compute a rail's Gate 3 record: one descriptor + quantized
     * ceiling + Pedersen commitment per envelope parameter, in
     * envelope order. Returns null when the envelope is empty or the
     * bridge is unavailable (the rail then commits WITHOUT
     * commitments and is named not-Gate-3-provable at ceremony
     * begin). Throws IllegalArgumentException — refusing the commit —
     * when a ceiling does not quantize into the governed domain
     * (§2.3: an unprovable envelope is a configuration error at
     * commit time) or the bridge fails mid-commit.
     */
    private fun buildZkRecord(railId: String, envelope: Map<String, Double>): RailZkRecord? {
        if (envelope.isEmpty()) return null
        val bridge = deps.zkBridge
        if (!bridge.isAvailable()) return null
        val params = mutableListOf<RailZkParam>()
        for ((name, ceiling) in envelope) {
            val descriptor = ZkParamDescriptor(name)
            val ceilingQ = ZkQuantization.quantize(ceiling, descriptor)
                ?: throw IllegalArgumentException(
                    "rail commit refused: ceiling $ceiling for parameter "$name" does not quantize into " +
                        "[0, 2^${descriptor.bits}) at scale ${descriptor.scale}; an unprovable envelope is a " +
                        "configuration error at commit time (ZK-GATE3-DESIGN section 2.3).",
                )
            val committed = try {
                bridge.commitCeiling(ceilingQ)
            } catch (e: ZkBridgeException) {
                throw IllegalArgumentException("rail commit refused: ${e.message}")
            }
            params.add(RailZkParam(descriptor, ceilingQ, committed.commitment, committed.blinding))
        }
        return RailZkRecord(railId, params)
    }

    fun evaluate(input: ProposalInput): Pair<PolicyOutcome, String> {
        if (DENIED_ACTIONS.contains(input.actionType)) {
            return Pair(PolicyOutcome.DENIED, "${input.actionType} violates a hard constraint; no authority path exists inside the harness.")
        }
        if (input.target.isBlank() || input.target == "unspecified") {
            return Pair(PolicyOutcome.INSUFFICIENTLY_SPECIFIED, "No target specified; the action cannot be bound to an exact object.")
        }
        val rail = railFor(input.actionType, input.target)
        if (rail != null) {
            for ((key, ceiling) in rail.envelope) {
                val v = input.parameters[key]
                if (v is Number && v.toDouble() > ceiling) {
                    return Pair(
                        PolicyOutcome.DRIFT_FLAGGED,
                        "Parameter $key=${renderNumber(v)} exceeds the committed rail envelope ($key <= ${renderNumber(ceiling)}). " +
                            "Execution suspended for sovereign review; the system does not improvise past a drift flag.",
                    )
                }
            }
            return Pair(PolicyOutcome.EXECUTABLE_UNDER_RAIL, "Covered by ${rail.id}.")
        }
        return Pair(PolicyOutcome.NEEDS_FRESH_ASSENT, "No rail or standing order covers this action; fresh sovereign assent is required.")
    }

    fun propose(input: ProposalInput): ActionProposal {
        val (policy, reason) = evaluate(input)
        val ev = deps.ledger.admit(
            AdmitInput(
                kind = EventKind.ACTION_PROPOSAL,
                topic = "action.${input.actionType}",
                claim = "Proposed action: ${input.actionType} on ${input.target} — ${input.expectedEffect}",
                source = input.requestedBy,
                confidence = 1.0,
            ),
        )
        val proposal = ActionProposal(
            id = ev.id,
            actionType = input.actionType,
            target = input.target,
            parameters = input.parameters,
            expectedEffect = input.expectedEffect,
            reversibility = input.reversibility,
            consequenceClass = input.consequenceClass,
            requiredAuthority = input.requiredAuthority,
            policy = policy,
            policyReason = reason,
            status = ProposalStatus.PROPOSED,
            requestedBy = input.requestedBy,
        )
        proposals[ev.id] = proposal
        deps.audit.record(input.requestedBy, "proposal_created", mapOf("proposalId" to ev.id, "actionType" to input.actionType, "target" to input.target))
        deps.audit.record("policy", "policy_decision", mapOf("proposalId" to ev.id, "policy" to policy.wire, "reason" to reason))
        return proposal
    }

    fun getProposal(id: String): ActionProposal? = proposals[id]

    /** Record sovereign assent for a proposal. Assent is a ledger event, bound to the proposal id. */
    fun recordAssent(proposalId: String, source: String = "sovereign"): EvidenceEvent {
        val p = proposals[proposalId] ?: throw IllegalArgumentException("unknown proposal $proposalId")
        val ev = deps.ledger.admit(
            AdmitInput(
                kind = EventKind.ASSENT,
                topic = "action.${p.actionType}",
                claim = "Sovereign assent granted for proposal $proposalId (${p.actionType} on ${p.target}).",
                source = source,
                confidence = 1.0,
                relations = listOf(Relation(RelationType.AUTHORIZES, proposalId)),
            ),
        )
        deps.audit.record(source, "assent_recorded", mapOf("proposalId" to proposalId, "assentEventId" to ev.id))
        return ev
    }

    fun hasAssent(proposalId: String): Boolean =
        deps.ledger.allEvents().any { e ->
            e.kind == EventKind.ASSENT && e.relations.any { r -> r.type == RelationType.AUTHORIZES && r.targetId == proposalId }
        }

    /**
     * Execute only through the governed path (spec §3.5):
     *  - denied / drift-flagged / insufficiently specified: refused, always.
     *  - executable_under_rail: runs.
     *  - needs_fresh_assent: runs only with a recorded assent event from the sovereign.
     *  - no adapter: returns cannot_execute. No execution event is written. No fake success.
     */
    fun execute(proposalId: String, actor: String = "sovereign"): ExecuteResult {
        val p = proposals[proposalId]
            ?: return ExecuteResult(executed = false, message = "unknown proposal $proposalId")
        if (p.status == ProposalStatus.EXECUTED) {
            return ExecuteResult(executed = false, message = "proposal already executed")
        }

        // Re-evaluate at commit time: rails may have changed since proposal.
        val (policy, reason) = evaluate(ProposalInput(p.actionType, p.target, p.parameters, p.expectedEffect, p.reversibility, p.consequenceClass, p.requiredAuthority, p.requestedBy))
        p.policy = policy
        p.policyReason = reason

        if (policy == PolicyOutcome.DENIED || policy == PolicyOutcome.DRIFT_FLAGGED || policy == PolicyOutcome.INSUFFICIENTLY_SPECIFIED) {
            p.status = ProposalStatus.REFUSED
            deps.audit.record(actor, "execution_refused", mapOf("proposalId" to proposalId, "policy" to policy.wire, "reason" to reason))
            return ExecuteResult(executed = false, message = "Refused (${policy.wire}): $reason", outcomeStatus = OutcomeStatus.CANNOT_EXECUTE)
        }
        if (policy == PolicyOutcome.NEEDS_FRESH_ASSENT && !hasAssent(proposalId)) {
            deps.audit.record(actor, "execution_blocked_no_assent", mapOf("proposalId" to proposalId))
            return ExecuteResult(executed = false, message = "Blocked: this action requires fresh sovereign assent and none is recorded for this proposal.")
        }
        if (!LOCAL_ADAPTERS.contains(p.actionType)) {
            p.status = ProposalStatus.REFUSED
            deps.audit.record(actor, "execution_refused_no_adapter", mapOf("proposalId" to proposalId, "actionType" to p.actionType))
            return ExecuteResult(
                executed = false,
                message = "Can propose/draft, cannot execute: no execution adapter is implemented for \"${p.actionType}\" in this core. Nothing was executed and no execution was recorded.",
                outcomeStatus = OutcomeStatus.CANNOT_EXECUTE,
            )
        }

        val execEv = deps.ledger.admit(
            AdmitInput(
                kind = EventKind.EXECUTION,
                topic = "action.${p.actionType}",
                claim = "Executed ${p.actionType} on ${p.target}.",
                source = actor,
                confidence = 1.0,
                relations = listOf(Relation(RelationType.EXECUTES, proposalId)),
            ),
        )
        deps.audit.record(actor, "execution", mapOf("proposalId" to proposalId, "executionEventId" to execEv.id, "policy" to policy.wire))

        var outcomeStatus = OutcomeStatus.OBSERVED_SUCCESS
        var payload: Any? = null
        var outcomeClaim = "Outcome observed: ${p.expectedEffect}"
        try {
            payload = runAdapter(p)
        } catch (err: Exception) {
            outcomeStatus = OutcomeStatus.OBSERVED_FAILURE
            outcomeClaim = "Outcome observed: execution failed — ${err.message}"
        }
        val outcomeEv = deps.ledger.admit(
            AdmitInput(
                kind = EventKind.OUTCOME,
                topic = "action.${p.actionType}",
                claim = outcomeClaim,
                source = "system",
                confidence = 1.0,
                relations = listOf(Relation(RelationType.DERIVES_FROM, execEv.id)),
            ),
        )
        deps.audit.record("system", "outcome_recorded", mapOf("proposalId" to proposalId, "outcomeEventId" to outcomeEv.id, "outcomeStatus" to outcomeStatus.wire))
        p.status = ProposalStatus.EXECUTED
        return ExecuteResult(
            executed = outcomeStatus == OutcomeStatus.OBSERVED_SUCCESS,
            message = outcomeClaim,
            executionEventId = execEv.id,
            outcomeEventId = outcomeEv.id,
            outcomeStatus = outcomeStatus,
            payload = payload,
        )
    }

    private fun runAdapter(p: ActionProposal): Any? {
        return when (p.actionType) {
            "create_standing_question" -> {
                val text = (p.parameters["text"] as? String) ?: p.target
                val q = deps.questions.addCustomQuestion(text, p.requestedBy)
                deps.ledger.admit(
                    AdmitInput(
                        kind = EventKind.SYSTEM,
                        topic = "standing_question",
                        claim = "Standing question created: \"$text\"",
                        source = "system",
                        confidence = 1.0,
                    ),
                )
                deps.audit.record("system", "standing_question_created", mapOf("questionId" to q.id, "text" to text))
                mapOf("questionId" to q.id)
            }
            "set_rail" -> {
                @Suppress("UNCHECKED_CAST")
                val envelope = (p.parameters["envelope"] as? Map<String, Double>) ?: emptyMap()
                val rail = addRail(
                    p.parameters["actionType"] as String,
                    (p.parameters["target"] as? String) ?: p.target,
                    envelope,
                    p.requestedBy,
                )
                mapOf("railId" to rail.id)
            }
            "pause_collector" -> {
                deps.pauseCollector(p.target)
                mapOf("collector" to p.target, "status" to "paused")
            }
            "resume_collector" -> {
                deps.resumeCollector(p.target)
                mapOf("collector" to p.target, "status" to "active")
            }
            "export_ledger" -> deps.exportLedger()
            else -> throw IllegalStateException("no adapter for ${p.actionType}")
        }
    }

    private fun renderNumber(v: Number): String {
        val d = v.toDouble()
        return if (d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong().toString() else d.toString()
    }
}
