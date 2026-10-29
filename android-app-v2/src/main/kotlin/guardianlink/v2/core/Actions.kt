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
     * Conversational-rail time bound in plain words ("morning",
     * "weekdays", "work hours") — when in the sovereign's day the
     * rail covers (SMOOTHNESS-ADDENDUM §15: a rail is rendered with
     * its bounds and assent binds to the rendering). Null = no time
     * bound; the rail covers at any hour.
     */
    val timeBound: String? = null,
    /** ISO instant after which the rail covers nothing. Null = until revoked. */
    val expiresAt: String? = null,
    /**
     * The exact rendering the sovereign assented to (scope, bounds,
     * expiry, suspension terms), kept with the rail so Settings and
     * the Agency answer can show the rail as it was bound.
     */
    val terms: String? = null,
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

/**
 * Device action types: executed by the platform layer's injected
 * handler (calls, texts, sending mail — the phone operated as the
 * sovereign). The core owns the proposal, the policy, the assent,
 * and the record; the handler owns only the effect. No handler
 * injected → no adapter → cannot execute, stated plainly.
 */
val DEVICE_ACTIONS: List<String> =
    listOf("phone_call", "send_text", "send_email", "device_open_app", "device_tap", "device_type", "device_press", "read_screen")

/** What a device handler reports back: the observed outcome, in words. */
data class DeviceOutcome(val claim: String, val payload: Any? = null)

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
    /**
     * Platform device-action handler (see [DEVICE_ACTIONS]). Null in
     * pure-core contexts: device proposals can be drafted there but
     * never executed, and the refusal says so.
     */
    val deviceAction: ((ActionProposal) -> DeviceOutcome)? = null,
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

    /** Whether a platform device-action handler is attached in this build. */
    fun deviceActionsAvailable(): Boolean = deps.deviceAction != null

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
        timeBound: String? = null,
        expiresAt: String? = null,
        terms: String? = null,
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
            timeBound = timeBound,
            expiresAt = expiresAt,
            terms = terms,
        )
        rails.add(rail)
        deps.audit.record(createdBy, "rail_created", mapOf("railId" to rail.id, "actionType" to actionType, "target" to target, "envelope" to envelope))
        return rail
    }

    private fun railFor(actionType: String, target: String): Rail? =
        rails.firstOrNull { r ->
            r.active && r.actionType == actionType && (r.target == target || r.target == "*") && coversNow(r)
        }

    /**
     * Whether a rail covers a proposal RIGHT NOW: its expiry has not
     * passed and the moment sits inside its time bound. A rail that
     * does not cover simply does not apply — the proposal falls back
     * to fresh assent, it is not drift and it is not a refusal
     * (SMOOTHNESS-ADDENDUM §15: outside the bound, it asks, as now).
     * An unrecognized bound word never silently narrows coverage:
     * the bound is named in the rail's terms, and unknown words are
     * treated as unbounded rather than guessed at.
     */
    private fun coversNow(r: Rail): Boolean {
        val nowInstant = deps.now.now()
        if (r.expiresAt != null) {
            val expiry = try {
                java.time.Instant.parse(r.expiresAt)
            } catch (e: Exception) {
                null
            }
            if (expiry != null && !nowInstant.isBefore(expiry)) return false
        }
        val bound = r.timeBound?.trim()?.lowercase() ?: return true
        if (bound.isEmpty()) return true
        val local = nowInstant.atZone(java.time.ZoneId.systemDefault())
        val hour = local.hour
        val weekday = local.dayOfWeek.value // 1 = Monday .. 7 = Sunday
        return when (bound) {
            "morning" -> hour < 12
            "afternoon" -> hour in 12..16
            "evening" -> hour >= 17
            "weekdays" -> weekday in 1..5
            "weekends" -> weekday >= 6
            "work hours" -> weekday in 1..5 && hour in 8..17
            else -> true
        }
    }

    /**
     * Revoke a rail conversationally (SMOOTHNESS-ADDENDUM §16): the
     * rail stays on record, inactive — revocation is a state, not an
     * erasure. Returns the revoked rail, or null if the id is unknown
     * or the rail was already inactive.
     */
    fun revokeRail(id: String): Rail? {
        val idx = rails.indexOfFirst { it.id == id }
        if (idx < 0 || !rails[idx].active) return null
        val revoked = rails[idx].copy(active = false)
        rails[idx] = revoked
        deps.audit.record("sovereign", "rail_revoked", mapOf("railId" to id, "actionType" to revoked.actionType, "target" to revoked.target))
        return revoked
    }

    /** Re-bound a rail's time window conversationally (§16: "tighten it to weekdays"). */
    fun retimeRail(id: String, timeBound: String?): Rail? {
        val idx = rails.indexOfFirst { it.id == id }
        if (idx < 0 || !rails[idx].active) return null
        val updated = rails[idx].copy(timeBound = timeBound)
        rails[idx] = updated
        deps.audit.record("sovereign", "rail_retimed", mapOf("railId" to id, "timeBound" to timeBound))
        return updated
    }

    /** The most recently created rail still in force, if any — what "that rail" points at. */
    fun latestActiveRail(): Rail? = rails.lastOrNull { it.active }

    /**
     * The Agency answer's raw material (SMOOTHNESS-ADDENDUM §4):
     * what is OPEN to the sovereign — rails in force, in the words
     * he assented to, and recent counterparties as options. Never
     * the action log: what already happened is History's material.
     */
    fun agencyOptions(): List<String> {
        val out = mutableListOf<String>()
        for (r in rails) {
            if (!r.active) continue
            out.add(
                r.terms ?: "Standing rail: ${railScopeWords(r.actionType, r.target)} runs without asking" +
                    (r.timeBound?.let { ", $it" } ?: "") + ".",
            )
        }
        val seen = LinkedHashSet<String>()
        val executedDevice = proposals.values.filter { p ->
            p.status == ProposalStatus.EXECUTED && DEVICE_ACTIONS.contains(p.actionType)
        }
        for (p in executedDevice.asReversed()) {
            if (!seen.add(p.actionType + "|" + p.target)) continue
            if (seen.size > 3) break
            out.add(
                when (p.actionType) {
                    "phone_call" -> "You can call ${p.target} back."
                    "send_text" -> "You can text ${p.target}."
                    "send_email" -> "You can email ${p.target}."
                    else -> "You can run that on ${p.target} again."
                },
            )
        }
        return out
    }

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
                    "rail commit refused: ceiling $ceiling for parameter '$name' does not quantize into " +
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
            return Pair(PolicyOutcome.DENIED, "that is one of the few things this system will never do, under any approval — it would break the record everything else stands on.")
        }
        if (input.target.isBlank() || input.target == "unspecified") {
            return Pair(PolicyOutcome.INSUFFICIENTLY_SPECIFIED, "it doesn't name exactly what it would act on, so it can't be tied to one exact thing.")
        }
        val rail = railFor(input.actionType, input.target)
        if (rail != null) {
            for ((key, ceiling) in rail.envelope) {
                val v = input.parameters[key]
                if (v is Number && v.toDouble() > ceiling) {
                    return Pair(
                        PolicyOutcome.DRIFT_FLAGGED,
                        "it goes past the limit you set on the rail ($key is ${renderNumber(v)}; you set $key at no more than ${renderNumber(ceiling)}). " +
                            "It stops here for your review — a rail is never stretched on my own say-so.",
                    )
                }
            }
            return Pair(PolicyOutcome.EXECUTABLE_UNDER_RAIL, "Covered by ${rail.id}.")
        }
        return Pair(PolicyOutcome.NEEDS_FRESH_ASSENT, "no rail or standing order covers it, so it needs your fresh say-so.")
    }

    fun propose(input: ProposalInput): ActionProposal {
        // Message bodies are reframed to the recipient at the
        // single choke point every proposal passes through (his
        // correction, 2026-10-25): "tell Shannon I love her"
        // proposes the text Shannon will read — "I love you" —
        // and the review prompt and assent bind to that text.
        val framedParams = if (input.actionType == "send_text" || input.actionType == "send_email") {
            val body = input.parameters["body"] as? String
            if (body != null) input.parameters + ("body" to MessageFraming.reframe(body)) else input.parameters
        } else {
            input.parameters
        }
        val framedInput = if (framedParams === input.parameters) input else input.copy(parameters = framedParams)
        @Suppress("NAME_SHADOWING") val input = framedInput
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
            return ExecuteResult(executed = false, message = "Refused: $reason", outcomeStatus = OutcomeStatus.CANNOT_EXECUTE)
        }
        if (policy == PolicyOutcome.NEEDS_FRESH_ASSENT && !hasAssent(proposalId)) {
            deps.audit.record(actor, "execution_blocked_no_assent", mapOf("proposalId" to proposalId))
            return ExecuteResult(executed = false, message = "It didn't run — this action needs your fresh say-so, and none is recorded for it.")
        }
        val hasAdapter = LOCAL_ADAPTERS.contains(p.actionType) ||
            (DEVICE_ACTIONS.contains(p.actionType) && deps.deviceAction != null)
        if (!hasAdapter) {
            p.status = ProposalStatus.REFUSED
            deps.audit.record(actor, "execution_refused_no_adapter", mapOf("proposalId" to proposalId, "actionType" to p.actionType))
            return ExecuteResult(
                executed = false,
                message = "I can draft that, but I cannot execute it — nothing in this build can carry it out yet. Nothing was executed, and nothing was recorded as done.",
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
        var outcomeClaim = p.expectedEffect
        try {
            val result = runAdapter(p)
            if (result is DeviceOutcome) {
                // A device handler reports what it observed; that
                // report IS the outcome claim, not the expectation.
                outcomeClaim = result.claim
                payload = result.payload
            } else {
                payload = result
            }
        } catch (err: Exception) {
            outcomeStatus = OutcomeStatus.OBSERVED_FAILURE
            outcomeClaim = "Execution failed — ${err.message}"
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
                    timeBound = p.parameters["timeBound"] as? String,
                    expiresAt = p.parameters["expiresAt"] as? String,
                    terms = p.parameters["terms"] as? String,
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
            "phone_call", "send_text", "send_email",
            "device_open_app", "device_tap", "device_type", "device_press", "read_screen" -> {
                val handler = deps.deviceAction
                    ?: throw IllegalStateException("no device-action handler is attached in this build")
                handler(p)
            }
            else -> throw IllegalStateException("no adapter for ${p.actionType}")
        }
    }

    private fun railScopeWords(actionType: String, target: String): String = when (actionType) {
        "send_text" -> "texts to $target"
        "phone_call" -> "calls to $target"
        "send_email" -> "email to $target"
        else -> "$actionType on $target"
    }

    private fun renderNumber(v: Number): String {
        val d = v.toDouble()
        return if (d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong().toString() else d.toString()
    }
}
