package guardianlink.core

import guardianlink.audit.ExternalAnchor
import guardianlink.audit.MerkleAuditLog
import guardianlink.capability.CapabilityIssuer
import guardianlink.capability.CheckResult
import guardianlink.capability.sha256Hex
import guardianlink.durability.DurableCapabilityStore
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
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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
 * Thread-safe: [execute] serializes callers on an internal commit lock.
 * The engine's evaluate is not atomic across gates, so the boundary
 * enforces serialization itself instead of requiring it of embedders:
 * callers cannot reach the commit relation without serialization.
 * (Sole-authority-path, crash-consistency law.)
 *
 * Phase 2 (capabilities): on [GateOutcome.Integrated] the admission
 * boundary mints a [guardianlink.capability.MutationCapability] binding
 * this exact (actionId, rendering), and the commit point — substrate
 * adoption — crosses ONLY on [CapabilityIssuer.checkAndConsumeMutation].
 * No governed mutation commits without a currently valid capability
 * minted by the admission boundary for that exact mutation. The issuer
 * is injectable for falsification testing; the default is the engine's
 * own admission boundary on the engine clock.
 *
 * R-U1: embedders pass [permitStore] rooted at the same durability
 * location as the rest of the engine's durable state (the sidecar's
 * data dir, the Android app's filesDir — the root that holds
 * clock.state and the anchors), and the default issuer is built with
 * it, so signed-permit consumption is WAL-backed in production
 * composition and survives restart. An explicitly injected issuer is
 * used exactly as given. Null (demos, tests) = in-memory single-use.
 */
class ConciergeInterface(
    private val policyEngine: PolicyEngine,
    private val auditLog: MerkleAuditLog,
    private val gates: NineGates,
    private val anchor: ExternalAnchor,
    initialSubstrate: SubstrateState = SubstrateState(emptyMap()),
    issuer: CapabilityIssuer? = null,
    permitStore: DurableCapabilityStore? = null,
) {
    private val issuer: CapabilityIssuer = issuer ?: CapabilityIssuer(
        clockMs = { gates.clock.nowMs() },
        permitStore = permitStore,
    )
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
        /**
         * Epoch binding (C-1): ID of the EpochCapability under which this
         * mutation is authorized. The holder proposes; the issuer verifies
         * (live, unrevoked, unexpired) and binds into the MutationCapability
         * at mint time. The commit path derives from the authenticated
         * binding — never trusts this field directly. Null = no epoch
         * (no cumulative bound enforced).
         */
        val epochId: String? = null,
    )

    sealed interface ExecutionResult {
        data class Executed(
            val newSubstrate: SubstrateState,
            val anchorReceipt: ExternalAnchor.Receipt,
        ) : ExecutionResult
        data class Rejected(val atGate: Int, val reason: String) : ExecutionResult
    }

    private var substrate: SubstrateState = initialSubstrate

    /**
     * Commit serialization, enforced at the boundary. Every path to
     * a governed mutation crosses [execute], and [execute] cannot
     * run concurrently with itself: check (gates), commit
     * (substrate assignment), and durable publication (anchor) are
     * one critical section. Embedders must not — and need not —
     * serialize; the sidecar's external lock is defense in depth.
     */
    private val commitLock = ReentrantLock()

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

    fun execute(action: Action, evidence: ActionEvidence): ExecutionResult = commitLock.withLock {
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
                // The sole authority path at the commit point: the admission
                // boundary mints a mutation capability for this exact
                // mutation, and substrate adoption crosses ONLY on a valid,
                // live, unconsumed capability. Predicate-not-possession is
                // structural here — the commit does not consult the gates'
                // verdict directly, only the capability.
                //
                // notBeforeMs is bound to t_c (the commit instant), not to a
                // second clock read: otherwise a clock tick between the read
                // and the mint would make the fresh capability "not yet
                // valid" — a TOCTOU race in the commit point itself.
                val t_c = gates.clock.nowMs()
                val cap = try {
                    issuer.mintMutation(
                        mutationHash = sha256Hex(evidence.rendering),
                        notBeforeMs = t_c,
                        epochId = evidence.epochId,
                    )
                } catch (e: IllegalArgumentException) {
                    // Epoch binding invalid: fail closed, do not mint.
                    return ExecutionResult.Rejected(
                        -1, "epoch binding invalid at mint: ${e.message}"
                    )
                }
                when (val c = issuer.checkAndConsumeMutation(cap, evidence.rendering, t_c)) {
                    is CheckResult.Refused ->
                        return ExecutionResult.Rejected(
                            -1, "capability refused at commit point: ${c.reason}"
                        )
                    CheckResult.Ok -> { /* the commit point is crossed */ }
                }
                // C-1: cumulative admission. Derive the epoch from the
                // AUTHENTICATED binding (the MutationCapability minted by the
                // issuer) — never from caller-supplied evidence. Every
                // successful mutation under an epoch consumes/records against
                // that epoch's aggregate bound. Fail closed if the epoch is
                // unusable.
                cap.epochId?.let { boundEpochId ->
                    val epochEffect = guardianlink.capability.EpochEffect(
                        target = when (val a = action) {
                            is Action.Write -> a.recordId
                            is Action.Read -> a.recordId
                            is Action.Delete -> a.recordId
                            else -> "unknown"
                        },
                        type = when (action) {
                            is Action.Write -> "write"
                            is Action.Read -> "read"
                            is Action.Delete -> "delete"
                            else -> "other"
                        },
                        bytes = evidence.rendering.toByteArray().size.toLong(),
                    )
                    // Look up the bound epoch from the issuer's live set.
                    // The binding was verified at mint; re-verify liveness
                    // here (revocation could have landed between mint and commit).
                    val epochCap = issuer.liveEpoch(boundEpochId)
                        ?: return ExecutionResult.Rejected(
                            -1, "epoch binding unusable at commit: $boundEpochId"
                        )
                    when (val e = issuer.checkAndRecordEpoch(epochCap, epochEffect, t_c)) {
                        is CheckResult.Refused ->
                            return ExecutionResult.Rejected(
                                -1, "epoch cumulative bound exceeded: ${e.reason}"
                            )
                        CheckResult.Ok -> { /* cumulative admission granted */ }
                    }
                }
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
