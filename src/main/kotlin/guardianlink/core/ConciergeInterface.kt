package guardianlink.core

import guardianlink.audit.ExternalAnchor
import guardianlink.audit.MerkleAuditLog
import guardianlink.gates.Action
import guardianlink.gates.Assent
import guardianlink.gates.GateContext
import guardianlink.gates.GateOutcome
import guardianlink.gates.KeyMaterial
import guardianlink.gates.NineGates
import guardianlink.gates.Proofs
import guardianlink.gates.Signal
import guardianlink.gates.SubstrateState
import guardianlink.integrity.DeviceIntegrityTier
import guardianlink.policy.PolicyEngine
import guardianlink.voice.AudioSample

/**
 * The composed interface: the principal's doorway into the system.
 *
 * Two facets, one doorway:
 * - [handle]: policy-tier queries (EVIDENCE_ONLY / HARD_ENFORCE / …) via
 *   the PolicyEngine. Answers "what tier does this request get?" —
 *   executes nothing.
 * - [execute]: action execution — the ONLY execution path on this
 *   interface, forced through the NineGates engine. There is no method
 *   here that applies an action without the gates deciding first.
 *
 * On [GateOutcome.Integrated] the interface adopts the engine-prepared
 * substrate — exactly-once is preserved because the engine applied the
 * transition at Gate 6 and the interface never re-applies — then anchors
 * the seal root through the [ExternalAnchor] port. On
 * [GateOutcome.Halted] nothing is adopted and nothing is anchored.
 *
 * A throwing anchor propagates: the transition IS applied and sealed in
 * the engine ledger, but NOT durably published. Fail-loud, never silent —
 * the caller must handle it.
 *
 * Not thread-safe: serialize callers (the engine's evaluate is not
 * atomic across gates).
 */
class ConciergeInterface(
    private val policyEngine: PolicyEngine,
    private val auditLog: MerkleAuditLog,
    private val gates: NineGates,
    private val anchor: ExternalAnchor,
    initialSubstrate: SubstrateState = SubstrateState(emptyMap()),
) {
    data class Request(val tier: PolicyEngine.Tier, val hardRailRef: String?, val context: String)
    data class Response(val decision: PolicyEngine.EnforcementDecision, val auditEntryIndex: Int)

    /**
     * Per-action evidence supplied by the caller. Evidence only —
     * instruments (clock, ledger, revocation feed) stay with the engine.
     */
    data class ActionEvidence(
        val actionId: String,
        val signal: Signal,
        val keys: KeyMaterial,
        val proofs: Proofs,
        val contextEntropyBits: Double,
        val contextParseTrees: Int,
        val rendering: String,
        val assent: Assent,
        val assentedAtMs: Long,
        /**
         * PCM captured during the assent ceremony. Evidence only — the
         * engine verifies it against its own enrolled voice template at
         * Gate 7 (theta_voice).
         */
        val assentAudio: AudioSample,
        val intentVector: DoubleArray,
        val currentVector: DoubleArray,
    )

    sealed interface ExecutionResult {
        data class Executed(
            val newSubstrate: SubstrateState,
            val anchorReceipt: ExternalAnchor.Receipt,
        ) : ExecutionResult
        data class Rejected(val atGate: Int, val reason: String) : ExecutionResult
    }

    private var substrate: SubstrateState = initialSubstrate

    fun handle(request: Request, deviceIntegrity: DeviceIntegrityTier.Level): Response {
        val decision = policyEngine.evaluate(
            requestedTier = request.tier,
            hardRailRef = request.hardRailRef,
            deviceIntegrity = deviceIntegrity,
            context = request.context
        )

        val entry = auditLog.append(
            "${request.tier}|${request.hardRailRef}|${decision}".toByteArray(Charsets.UTF_8)
        )

        return Response(decision, entry.index)
    }

    fun execute(action: Action, evidence: ActionEvidence): ExecutionResult {
        val outcome = gates.evaluate(
            GateContext(
                signal = evidence.signal,
                keys = evidence.keys,
                proofs = evidence.proofs,
                contextEntropyBits = evidence.contextEntropyBits,
                contextParseTrees = evidence.contextParseTrees,
                action = action,
                actionId = evidence.actionId,
                substrate = substrate,
                rendering = evidence.rendering,
                assent = evidence.assent,
                assentedAtMs = evidence.assentedAtMs,
                assentAudio = evidence.assentAudio,
                intentVector = evidence.intentVector,
                currentVector = evidence.currentVector,
            )
        )
        return when (outcome) {
            is GateOutcome.Integrated -> {
                substrate = outcome.newSubstrate
                val receipt = anchor.anchor(
                    root = outcome.sealRoot,
                    entryIndex = gates.ledger.size - 1, // the seal was the last append
                    timestampMs = gates.clock.nowMs(),
                )
                ExecutionResult.Executed(outcome.newSubstrate, receipt)
            }
            is GateOutcome.Halted ->
                ExecutionResult.Rejected(outcome.atGate, outcome.reason)
        }
    }

    /** Currently governed state, for inspection and testing. */
    fun currentSubstrate(): SubstrateState = substrate
}
