package guardianlink.android

import guardianlink.core.ConciergeInterface
import guardianlink.gates.Assent
import guardianlink.gates.KeyMaterial
import guardianlink.gates.Proofs
import guardianlink.gates.Signal
import guardianlink.voice.AudioSample

/**
 * Evidence builder for the scaffold.
 *
 * DEMO: signal/keys/proofs/assent are caller-asserted placeholders, exactly
 * like Main.kt's demo evidence. The voice ceremony path
 * ([ceremonialEvidence]) is the real one: it verifies freshly captured
 * microphone audio against the Keystore-held enrolled template at Gate 7
 * (theta_voice), and refuses when no enrollment exists.
 *
 * Production MUST replace the placeholder ports with real cryptography:
 * Ed25519 origin signatures (Gate 1), a real ZK prover (Gate 3), and
 * Ed25519 over the canonical (rendering, assentedAtMs, actionId) triple
 * (Gate 7) — see ProductionVerifiers and AssentCodec.
 */
class AndroidEvidenceSupplier(private val app: GuardianApp) : EvidenceSupplier {

    override fun evidenceFor(
        action: guardianlink.gates.Action,
        actionId: String,
        rendering: String,
    ): ConciergeInterface.ActionEvidence = ConciergeInterface.ActionEvidence(
        actionId = actionId,
        signal = Signal("android".toByteArray(), snr = 20.0, wellFormed = true),
        keys = KeyMaterial("android".toByteArray(), "scaffold".toByteArray()),
        proofs = Proofs("zk".toByteArray(), "pub".toByteArray(), issuedAtMs = app.clock.nowMs()),
        contextEntropyBits = 2.0,
        contextParseTrees = 1,
        rendering = rendering,
        assent = Assent("scaffold".toByteArray()),
        assentedAtMs = app.clock.nowMs(),
        assentAudio = lastCeremonyAudio ?: AudioSample(DoubleArray(16000) { 0.0 }),
        intentVector = doubleArrayOf(1.0, 0.0),
        currentVector = doubleArrayOf(1.0, 0.0),
    )

    /**
     * The real ceremony: issue a liveness challenge, capture the response
     * on the microphone, verify against the enrolled template. Returns
     * the captured audio for the evidence bundle, or null when the
     * ceremony cannot run (no enrollment).
     */
    fun ceremonialAudio(challengeAudio: AudioSample): AudioSample? {
        val template = app.enrolledTemplate ?: return null
        val result = app.voiceVerifier.verify(challengeAudio, template)
        return if (result.decision == guardianlink.voice.VerificationResult.Decision.ACCEPT) {
            lastCeremonyAudio = challengeAudio
            challengeAudio
        } else {
            null
        }
    }

    companion object {
        @Volatile
        private var lastCeremonyAudio: AudioSample? = null
    }
}
