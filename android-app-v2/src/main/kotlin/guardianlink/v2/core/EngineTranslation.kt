package guardianlink.v2.core

import guardianlink.gates.Action
import guardianlink.gates.SubstrateState

/** Result of translating a core proposal into the engine's action model. */
sealed interface TranslationResult {
    data class Ok(val action: Action, val substrate: SubstrateState) : TranslationResult

    /** Translation refused, with the named reason. No lossy approximation was substituted. */
    data class Refused(val reason: String) : TranslationResult
}

/**
 * Translation between the v2 core and the engine's record-substrate
 * action model (EVIDENCE-PATH build step 2).
 *
 * THE MAPPING — the heart of the evidence-path slice:
 *
 * The engine governs a substrate of named records with string fields
 * (guardianlink.gates.SubstrateState) and a closed action AST
 * (Read/Write/Delete/Sequence/Guarded) whose transition semantics are
 * the engine's ReferenceTransition: Write merges fields into an
 * EXISTING record (creation is forbidden — Gate 6 psi_inv), Delete
 * removes a record, Read is pure.
 *
 * The core's world projects onto that substrate as:
 *
 *   record id                     fields
 *   ---------------------------   --------------------------------------
 *   harness/meta                  format, deviceId, ledgerRoot,
 *                                 ledgerEvents, lastStandingQuestion,
 *                                 lastRailCommitted
 *   collector/<name>              status, purpose, statusReason
 *   standing_question/<id>        text, version, delta
 *   account/<id>                  label, role, connected
 *   plug/<id>                     name, revoked
 *   rail/<id>                     actionType, target, active
 *
 * PROJECTION LIFECYCLE: the projection seeds the ConciergeInterface's
 * initial substrate at engine init. After that the engine's substrate
 * is mutated ONLY by sealed engine executions (the concierge adopts
 * the prepared transition). Core-side changes after init (a collector
 * paused through the core's own local path, a new standing question)
 * are NOT re-projected until restart — a named limitation of this
 * slice. The ceremony reconciles the one case it causes: a sealed
 * pause/resume execution is mirrored back into the core registry (see
 * AssentCeremony), so the two views of collector state cannot diverge
 * on the path the engine itself took.
 *
 * PROPOSAL -> ACTION, for the local action classes the core supports
 * (LOCAL_ADAPTERS). Everything is a Write onto an existing projected
 * record, or a Read:
 *
 *   pause_collector    Write collector/<target>
 *                      {status: "paused", statusReason: "paused by
 *                      sovereign through governed engine execution"}
 *   resume_collector   Write collector/<target>
 *                      {status: "active", statusReason: ""}
 *   create_standing_   Write harness/meta
 *   question           {lastStandingQuestion: <question text>}
 *   set_rail           Write harness/meta
 *                      {lastRailCommitted: "<actionType>@<target>"}
 *   export_ledger      Read harness/meta {ledgerRoot}
 *
 * The engine substrate is a PROJECTION, not the core's store of truth:
 * a Write to harness/meta records that the governed act occurred; the
 * core's own ActionLayer remains the executor of the local effect
 * (creating the question, committing the rail). The ceremony writes
 * the engine outcome back into the core ledger as evidence, which is
 * where the two records meet.
 *
 * REFUSALS (named, never approximated):
 *  - action types in DENIED_ACTIONS: refused as hard-constraint
 *    violations (they are refused by core policy too).
 *  - action types with no local adapter: refused — the core cannot
 *    execute them, so an engine seal would authorize an effect nobody
 *    performs (the no-fake-execution rule, applied to translation).
 *  - pause/resume of a collector that is not a projected record:
 *    refused — a Write to an absent record is a no-op under
 *    ReferenceTransition, which would let a seal claim an effect that
 *    did not happen.
 *  - Delete: NO local action class maps to Delete in this slice.
 *    Record deletion is a destructive class the core does not offer;
 *    there is deliberately no translation for it.
 *  - Sequence/Guarded: not produced by this translator (single
 *    proposals are single effects). A future multi-effect proposal
 *    class gets its own translation review, not a silent composition.
 */
object EngineTranslation {

    const val META_RECORD = "harness/meta"

    /** Record ids must survive Render's quoting and stay readable: keep [A-Za-z0-9/_-], fold the rest to '_'. */
    fun sanitizeId(raw: String): String =
        raw.map { c -> if (c.isLetterOrDigit() || c == '/' || c == '_' || c == '-') c else '_' }.joinToString("")

    /** Project the core's current state onto the engine substrate. */
    fun substrateFrom(core: GuardianLink): SubstrateState {
        val records = LinkedHashMap<String, Map<String, String>>()
        records[META_RECORD] = mapOf(
            "format" to "gl-substrate-1",
            "deviceId" to core.ledger.deviceId,
            "ledgerRoot" to Ledger.rootOf(core.ledger.events()),
            "ledgerEvents" to core.ledger.events().size.toString(),
            "lastStandingQuestion" to "",
            "lastRailCommitted" to "",
        )
        for (c in core.collectors.all()) {
            records["collector/${sanitizeId(c.name)}"] = mapOf(
                "status" to c.status.wire,
                "purpose" to c.purpose,
                "statusReason" to (c.statusReason ?: ""),
            )
        }
        for (q in core.questions.all()) {
            records["standing_question/${sanitizeId(q.id)}"] = mapOf(
                "text" to q.text,
                "version" to q.current.version.toString(),
                "delta" to q.current.delta.wire,
            )
        }
        for (a in core.accounts.all()) {
            records["account/${sanitizeId(a.id)}"] = mapOf(
                "label" to a.label,
                "role" to a.role,
                "connected" to a.connected.toString(),
            )
        }
        for (p in core.plugs.all()) {
            records["plug/${sanitizeId(p.id)}"] = mapOf(
                "name" to p.name,
                "revoked" to p.revoked.toString(),
            )
        }
        for (r in core.actions.listRails()) {
            records["rail/${sanitizeId(r.id)}"] = mapOf(
                "actionType" to r.actionType,
                "target" to r.target,
                "active" to r.active.toString(),
            )
        }
        return SubstrateState(records)
    }

    /**
     * Translate [proposal] against [substrate] (the engine's CURRENT
     * substrate — the concierge's — so Gate 2's isolation check and the
     * transition both see the state the seal would actually mutate).
     */
    fun translate(proposal: ActionProposal, substrate: SubstrateState): TranslationResult {
        if (DENIED_ACTIONS.contains(proposal.actionType)) {
            return TranslationResult.Refused(
                "${proposal.actionType} violates a hard constraint; no engine translation exists and no authority path can create one.",
            )
        }
        if (!LOCAL_ADAPTERS.contains(proposal.actionType)) {
            return TranslationResult.Refused(
                "no engine translation for action type \"${proposal.actionType}\": the v2 core has no local adapter for it, " +
                    "so an engine seal would authorize an effect nobody performs. Refused rather than approximated.",
            )
        }
        val action: Action = when (proposal.actionType) {
            "pause_collector", "resume_collector" -> {
                val recordId = "collector/${sanitizeId(proposal.target)}"
                if (!substrate.records.containsKey(recordId)) {
                    return TranslationResult.Refused(
                        "collector \"${proposal.target}\" is not a projected substrate record ($recordId absent); " +
                            "a Write would be a no-op under the reference transition and the seal would claim an effect that did not happen.",
                    )
                }
                if (proposal.actionType == "pause_collector") {
                    Action.Write(
                        recordId,
                        mapOf(
                            "status" to "paused",
                            "statusReason" to "paused by sovereign through governed engine execution",
                        ),
                    )
                } else {
                    Action.Write(recordId, mapOf("status" to "active", "statusReason" to ""))
                }
            }
            "create_standing_question" -> {
                val text = (proposal.parameters["text"] as? String) ?: proposal.target
                Action.Write(META_RECORD, mapOf("lastStandingQuestion" to text))
            }
            "set_rail" -> {
                val railAction = (proposal.parameters["actionType"] as? String) ?: ""
                val railTarget = (proposal.parameters["target"] as? String) ?: proposal.target
                Action.Write(META_RECORD, mapOf("lastRailCommitted" to "$railAction@$railTarget"))
            }
            "export_ledger" -> Action.Read(META_RECORD, listOf("ledgerRoot"))
            else -> return TranslationResult.Refused(
                "action type \"${proposal.actionType}\" is a local adapter with no engine translation in this slice.",
            )
        }
        return TranslationResult.Ok(action, substrate)
    }
}
