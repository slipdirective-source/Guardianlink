package guardianlink.voice

/**
 * Injected voice-biometric port — the Identity & Intent Anchor for Gate 7
 * assent. Verifiers-family style: a constructor instrument of the engine,
 * total over its inputs (verification may fail, never throw — a throwing
 * adapter fails closed via the engine's checked() wrapper like every
 * other port).
 *
 * Two ceremonies, one port:
 * - ENROLLMENT (deployment-time): [enroll] turns labeled audio samples
 *   into a [VoiceTemplate]. The template is deployment-owned state —
 *   enrolled once, stored by the deployment, passed back at verify time.
 *   It is never caller evidence: a caller-supplied template would let the
 *   caller enroll themselves.
 * - VERIFICATION (assent-time): [verify] scores a fresh [AudioSample]
 *   against the enrolled template AND checks liveness. Both must pass.
 *
 * Liveness is challenge-response (see [VoiceChallengeVerifier]): the
 * adapter issues a random phrase, the person speaks it, and verification
 * requires the spoken content to match the challenge in addition to voice
 * consistency. Anti-spoofing limits are documented on the implementation —
 * this port does not claim deepfake detection.
 */
interface BiometricVerifier {
    /** Enrollment: labeled audio → deployment-owned voice template. */
    fun enroll(samples: List<AudioSample>): VoiceTemplate

    /**
     * Verification: fresh ceremony audio vs. the enrolled template.
     * Returns [VerificationResult] — score, accept/reject, liveness.
     * Fail-closed: any invalid input (empty/non-finite audio, wrong rate,
     * template mismatch) yields REJECT, never ACCEPT.
     */
    fun verify(sample: AudioSample, template: VoiceTemplate): VerificationResult
}

/**
 * A random liveness challenge issued by the adapter. The deployment plays
 * [prompt] to the person and captures the spoken response as the
 * [BiometricVerifier.verify] sample. [phraseId] keys the enrolled phrase
 * reference the content check compares against.
 */
data class VoiceChallenge(val phraseId: String, val prompt: String)

/**
 * Deployment-owned voice template: the speaker voiceprint plus enrolled
 * phrase references for the liveness content check. Contains model
 * parameters — protect at rest (a stolen template aids impersonation
 * research, though it cannot by itself pass liveness).
 */
data class VoiceTemplate(
    val speakerEmbedding: SpeakerEmbedding,
    /** phraseId → enrolled MFCC frame sequence for the DTW content check. */
    val phraseReferences: Map<String, List<DoubleArray>>,
    val sampleRateHz: Int = Mfcc.SAMPLE_RATE,
)

/** Outcome of one verification ceremony. */
data class VerificationResult(
    /** Speaker similarity (GMM-UBM: mean frame log-likelihood ratio). */
    val score: Double,
    val decision: Decision,
    val liveness: Liveness,
) {
    enum class Decision { ACCEPT, REJECT }
    enum class Liveness {
        /** Spoken content matched the active challenge. */
        LIVE,
        /**
         * Content did not match, no usable reference existed, or the
         * ceremony failed before the content check ran.
         */
        NOT_LIVE,
        /** No challenge was active — fail-closed: decision is REJECT. */
        NOT_CHECKED,
    }
}
