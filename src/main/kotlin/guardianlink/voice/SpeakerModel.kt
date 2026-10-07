package guardianlink.voice

/**
 * Speaker-model port: the voiceprint behind [BiometricVerifier].
 *
 * Contract:
 * - [enroll] maps MFCC frames to an opaque [SpeakerEmbedding].
 *   Deterministic: same frames → same embedding.
 * - [score] returns a similarity in "higher = more likely same speaker"
 *   units. The scale is implementation-defined; the verifier owns the
 *   threshold, so document the scale where you implement it.
 * - Both functions are total over finite inputs: they return values, never
 *   throw, on any finite frame list (empty lists score −∞).
 *
 * MODEL PLUGGABILITY — dropping in a stronger model (e.g. an ONNX
 * ECAPA-TDNN): implement this interface. [enroll] runs the network over
 * the frames and returns [SpeakerEmbedding.Vector] (the fixed-size
 * embedding, L2-normalized); [score] returns cosine similarity between the
 * utterance embedding and the enrolled vector. Nothing else in the
 * pipeline changes: MFCC extraction, the challenge/DTW liveness check,
 * the fail-closed wiring at Gate 7, and the [BiometricVerifier] port are
 * all model-agnostic. The ONNX runtime dependency itself is deployment
 * scope — this module stays dependency-free on purpose.
 */
interface SpeakerModel {
    fun enroll(frames: List<DoubleArray>): SpeakerEmbedding
    fun score(frames: List<DoubleArray>, embedding: SpeakerEmbedding): Double
}

/** Opaque voiceprint. [Vector] is the hook a neural model uses. */
sealed interface SpeakerEmbedding {
    /** GMM-UBM voiceprint: UBM means MAP-adapted to the speaker. */
    data class GmmModel(val model: Gmm) : SpeakerEmbedding

    /** Fixed-size neural embedding (e.g. ECAPA-TDNN), L2-normalized. */
    data class Vector(val values: DoubleArray) : SpeakerEmbedding {
        override fun equals(other: Any?): Boolean =
            other is Vector && values.contentEquals(other.values)
        override fun hashCode(): Int = values.contentHashCode()
    }
}

/**
 * The classical baseline: GMM-UBM over MFCCs.
 *
 * - [ubm] is the population background model (adapter-owned, trained on a
 *   background corpus — see [GmmUbmVoiceVerifier]).
 * - [enroll] MAP-adapts the UBM means to the speaker's frames.
 * - [score] is the mean per-frame log-likelihood ratio
 *   ⟨log p(x|speaker) − log p(x|UBM)⟩: positive means the frames look more
 *   like the enrolled speaker than the background population. Thresholds
 *   live in the verifier, calibrated on synthetic data (see README).
 */
class GmmUbmSpeakerModel(
    private val ubm: Gmm,
    private val relevance: Double = 16.0,
) : SpeakerModel {
    override fun enroll(frames: List<DoubleArray>): SpeakerEmbedding {
        if (frames.isEmpty()) return SpeakerEmbedding.GmmModel(ubm)
        return SpeakerEmbedding.GmmModel(Gmm.mapAdapt(ubm, frames, relevance))
    }

    override fun score(frames: List<DoubleArray>, embedding: SpeakerEmbedding): Double {
        val speaker = (embedding as? SpeakerEmbedding.GmmModel)?.model
            ?: return Double.NEGATIVE_INFINITY // wrong embedding kind: fail closed
        if (frames.isEmpty()) return Double.NEGATIVE_INFINITY
        var llr = 0.0
        for (f in frames) llr += (speaker.logLikelihood(f) - ubm.logLikelihood(f)) / frames.size
        return llr
    }
}
