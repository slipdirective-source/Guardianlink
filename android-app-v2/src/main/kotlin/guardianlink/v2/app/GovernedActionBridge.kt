package guardianlink.v2.app

import guardianlink.core.ConciergeInterface
import guardianlink.gates.Action
import guardianlink.gates.MonotonicClock
import guardianlink.gates.Rails
import guardianlink.gates.render
import guardianlink.gates.validateAction
import java.util.concurrent.atomic.AtomicLong

/**
 * The evidence seam between the v0.2 app and the governed engine.
 *
 * CI-VERIFY-FIRST / ENGINE ADAPTER: this interface is OURS (modeled on
 * the scaffold's `guardianlink.android.EvidenceSupplier`), not an engine
 * API. Its single method is the one place an implementation may fabricate
 * a [ConciergeInterface.ActionEvidence] — which is exactly why the only
 * implementation shipped in this cut refuses to.
 */
fun interface EngineEvidenceProvider {
    fun evidenceFor(
        action: Action,
        actionId: String,
        rendering: String,
    ): ConciergeInterface.ActionEvidence
}

/** Thrown when governed engine execution is requested before real evidence exists. */
class EvidenceUnavailableException(message: String) : IllegalStateException(message)

/**
 * =======================================================================
 * FAIL-CLOSED EVIDENCE PROVIDER — READ BEFORE REPLACING
 * =======================================================================
 *
 * This provider DENIES every governed engine action by throwing. That is
 * deliberate, and it is the honest posture for this cut:
 *
 * A real [ConciergeInterface.ActionEvidence] needs, per the engine's own
 * trust-boundary docs (GateContext) and the scaffold's DEMO notes:
 *   - Gate 1: an Ed25519 origin signature over the request message,
 *   - Gate 3: a real ZK proof (NO prover exists in the engine or here),
 *   - Gate 7: an Ed25519 assent signature over the canonical
 *     (rendering, assentedAtMs, actionId) triple via AssentCodec, bound
 *     to a voice ceremony whose audio verifies against an ENROLLED
 *     template (no enrollment activity exists in v0.2 yet).
 *
 * None of that evidence exists in v0.2 today. Inventing placeholder bytes
 * (what the scaffold does) would produce evidence that LOOKS structured
 * while proving nothing — and paired with anything less than the
 * deny-by-default [guardianlink.adapters.ProductionVerifiers] now wired
 * in [GuardianLinkV2App], it would pass the gates. We do not ship that.
 *
 * Replacement path: implement the assent ceremony (rendering display +
 * AssentCodec signing with the operator key + voice capture/enrollment),
 * a Gate 1 signer, and a Gate 3 prover; then swap this provider in
 * GuardianLinkV2App.initEngine. Until then, engine execution is
 * unavailable and says so, loudly, on every attempt.
 * =======================================================================
 */
class FailClosedEvidenceProvider : EngineEvidenceProvider {
    override fun evidenceFor(
        action: Action,
        actionId: String,
        rendering: String,
    ): ConciergeInterface.ActionEvidence {
        throw EvidenceUnavailableException(
            "Governed engine execution is unavailable in GuardianLink v0.2 (first cut): " +
                "no real evidence ceremony exists yet (Ed25519 origin signature, ZK proof, " +
                "and AssentCodec-bound voice assent are not implemented), and this provider " +
                "fails closed rather than fabricating evidence. Action $actionId was NOT executed.",
        )
    }
}

/**
 * Sole-writer bridge to the governed engine, modeled directly on the
 * scaffold's `guardianlink.android.ActionDispatcher` (same shape, same
 * serialization discipline): every engine action is validated into
 * A_total, rendered, evidenced, and executed through
 * [ConciergeInterface.execute] — the ONLY execution doorway.
 *
 * [ConciergeInterface] serializes internally on a commit lock; the
 * @Synchronized here is defense in depth, as in the scaffold.
 *
 * In this cut every submission is refused by [FailClosedEvidenceProvider]
 * before evidence exists. The refusal is surfaced as
 * [BridgeResult.DeniedEvidenceUnavailable], never as a fake success.
 */
class GovernedActionBridge(
    private val concierge: ConciergeInterface,
    private val clock: MonotonicClock,
    private val evidenceProvider: EngineEvidenceProvider,
) {
    private val actionSeq = AtomicLong(0)

    sealed interface BridgeResult {
        data class Executed(val newSubstrate: guardianlink.gates.SubstrateState) : BridgeResult
        data class Rejected(val atGate: Int, val reason: String) : BridgeResult
        data class DeniedEvidenceUnavailable(val reason: String) : BridgeResult
    }

    @Synchronized
    fun submit(action: Action): BridgeResult {
        require(validateAction(action, Rails())) { "action not in A_total" }
        val actionId = "a-${clock.nowMs()}-${actionSeq.incrementAndGet()}"
        val evidence = try {
            evidenceProvider.evidenceFor(action, actionId, render(action))
        } catch (denied: EvidenceUnavailableException) {
            return BridgeResult.DeniedEvidenceUnavailable(denied.message ?: "evidence unavailable")
        }
        return when (val result = concierge.execute(action, evidence)) {
            is ConciergeInterface.ExecutionResult.Executed ->
                BridgeResult.Executed(result.newSubstrate)
            is ConciergeInterface.ExecutionResult.Rejected ->
                BridgeResult.Rejected(result.atGate, result.reason)
        }
    }

    fun currentSubstrate() = concierge.currentSubstrate()
}
