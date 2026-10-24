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
 * What still does NOT complete, honestly: production wiring leaves
 * Gate 3's verifyZk at deny (Caleb's ruling — no proof system chosen),
 * so a production submission halts at Gate 3 and the outcome says
 * "engine execution halts at Gate 3 (ZK proof system not yet
 * deployed)". Gate 7 voice evidence is likewise absent until
 * enrollment lands (see VoiceBiometricPort). The ceremony is real;
 * the gates it cannot yet pass halt it, by design, with their reasons
 * surfaced verbatim in the write-back.
 */
class GovernedActionBridge(
    private val concierge: ConciergeInterface,
    private val ceremony: AssentCeremony,
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
    fun submit(prepared: PreparedCeremony, assentAudio: AudioSample): CeremonyOutcome =
        ceremony.submit(prepared, assentAudio)

    fun currentSubstrate(): SubstrateState = concierge.currentSubstrate()
}
