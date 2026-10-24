package guardianlink.v2.app

import guardianlink.core.ConciergeInterface
import guardianlink.gates.SubstrateState
import guardianlink.v2.core.AssentCeremony
import guardianlink.v2.core.CeremonyBegin
import guardianlink.v2.core.CeremonyOutcome
import guardianlink.v2.core.CeremonyRequest
import guardianlink.v2.core.PreparedCeremony
import guardianlink.voice.AudioSample

/**
 * Sole-writer bridge to the governed engine (scaffold ActionDispatcher
 * shape, same serialization discipline): every governed engine action
 * enters through the assent ceremony and exits through
 * [ConciergeInterface.execute] — the ONLY execution doorway. The
 * concierge serializes internally on its commit lock; the @Synchronized
 * phase methods here are defense in depth, as in the scaffold.
 *
 * HISTORY — the fail-closed provider is GONE, and what replaced it
 * matters: the first cut shipped FailClosedEvidenceProvider, which
 * threw on every evidence request because no real evidence could be
 * constructed (no Gate 1 signer, no Gate 7 ceremony, no custody). This
 * cut builds the ceremony (guardianlink.v2.core.AssentCeremony):
 * persistent Keystore-sealed key custody, translation, Gate 1 origin
 * signing, AssentCodec-bound assent at the sovereign's confirm, and
 * outcome write-back into the v2 ledger. The pre-ceremony state is
 * represented by the ABSENCE of a bridge (GuardianLinkV2App.bridge is
 * null until the engine and custody are up) — not by a throwing stub
 * sitting in the production path.
 *
 * Gate 3 is REAL in this wiring (ZK-GATE3-DESIGN, Caleb's Option A
 * ruling 2026-10-23): the ceremony proves rail-envelope compliance
 * through the Rust glzk bridge at submit, and ProductionVerifiers'
 * zkVerifier is the bridge verifier (see GuardianLinkV2App). A Gate 3
 * halt here means the envelope proof did not verify; when the wiring
 * is present, this bridge refines the outcome's user message with
 * the verifier's failure class (parse / freshness / coherence /
 * proof-invalid / bridge-internal) from its reason code (§5.4) —
 * never a bare gate number. Gate 7 voice evidence is still absent
 * until enrollment lands (see VoiceBiometricPort). The ceremony is
 * real; the gates it cannot pass halt it, by design, with their
 * reasons surfaced verbatim in the write-back.
 */
class GovernedActionBridge(
    private val concierge: ConciergeInterface,
    private val ceremony: AssentCeremony,
    private val zkWiring: ZkVerifierWiring? = null,
) {

    /** Ceremony step 1–2: translate, sign origin, produce the rendering to show the sovereign. */
    @Synchronized
    fun begin(request: CeremonyRequest): CeremonyBegin = ceremony.begin(request)

    /**
     * Ceremony step 3: the sovereign's confirm — produces the Gate 7
     * assent signature. Returns the earliest tau at which [submit]
     * may run (assent time + the fixed-rail cooling window).
     */
    @Synchronized
    fun confirm(prepared: PreparedCeremony): Long = ceremony.confirm(prepared)

    /** Ceremony step 4–6: assemble the bundle, submit through the concierge, write the outcome back. */
    @Synchronized
    fun submit(prepared: PreparedCeremony, assentAudio: AudioSample): CeremonyOutcome {
        val outcome = ceremony.submit(prepared, assentAudio)
        val wiring = zkWiring
        if (wiring != null && outcome is CeremonyOutcome.Submitted) {
            val result = outcome.result
            if (result is ConciergeInterface.ExecutionResult.Rejected && result.atGate == 3) {
                val failureClass = guardianlink.v2.core.zk.ZkReason.className(wiring.lastReasonCode)
                return outcome.copy(
                    userMessage = "engine execution halts at Gate 3 (ZK proof rejected — $failureClass): ${result.reason}",
                )
            }
        }
        return outcome
    }

    fun currentSubstrate(): SubstrateState = concierge.currentSubstrate()
}
