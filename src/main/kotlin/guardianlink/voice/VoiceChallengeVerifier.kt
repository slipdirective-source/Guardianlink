package guardianlink.voice

import kotlin.random.Random

/**
 * Real voice-biometric adapter: GMM-UBM speaker verification over MFCCs
 * plus challenge-response liveness.
 *
 * Ceremony:
 * 1. Deployment calls [issueChallenge] → plays [VoiceChallenge.prompt] to
 *    the person → captures PCM.
 * 2. Deployment calls [verify] with the captured [AudioSample] and the
 *    deployment-owned [VoiceTemplate].
 * 3. ACCEPT requires BOTH: speaker log-likelihood-ratio ≥ [acceptThreshold]
 *    AND the spoken content DTW-matches the enrolled reference for the
 *    challenged phrase (liveness LIVE).
 *
 * Fail-closed throughout: empty/non-finite audio, wrong sample rate, no
 * active challenge, missing phrase reference, or a template/embedding-kind
 * mismatch all yield REJECT — never ACCEPT, never throw.
 *
 * The challenge is single-use: [verify] consumes the active challenge
 * whether it passes or fails, so every ceremony needs a fresh
 * [issueChallenge]. A recording of a *used* challenge response cannot be
 * re-verified — but a recording of the *current* challenge response can,
 * which is the documented replay residual (see README).
 *
 * Thread-safety: challenge state is synchronized; ceremonies must still be
 * serial per adapter instance (one active challenge at a time).
 */
class GmmUbmVoiceVerifier(
    ubm: Gmm,
    private val speakerModel: SpeakerModel = GmmUbmSpeakerModel(ubm),
    /**
     * Speaker accept threshold on the mean frame LLR. Calibrated on
     * deterministic synthetic voices (see README): same-speaker LLRs sit
     * well above, different-speaker well below.
     */
    val acceptThreshold: Double = 0.0,
    /**
     * Liveness threshold on the length-normalized DTW distance between the
     * utterance and the enrolled phrase reference. Calibrated (see README):
     * same-phrase ≈ 0, different-phrase an order of magnitude higher.
     */
    val phraseThreshold: Double = 2.0,
    val phrases: List<VoiceChallenge> = defaultPhrases,
    seed: Long = CHALLENGE_SEED,
) : BiometricVerifier {
    private val challengeRandom = Random(seed)
    private val lock = Any()

    @Volatile
    private var activeChallenge: VoiceChallenge? = null

    /** The challenge awaiting a response, or null if none is active. */
    fun pendingChallenge(): VoiceChallenge? = synchronized(lock) { activeChallenge }

    /**
     * Issue a fresh random liveness challenge. Replaces any active
     * challenge — the deployment must play the NEW prompt.
     */
    fun issueChallenge(): VoiceChallenge = synchronized(lock) {
        val c = phrases[challengeRandom.nextInt(phrases.size)]
        activeChallenge = c
        c
    }

    /**
     * Speaker enrollment from free-speech samples. Phrase references for
     * liveness are enrolled separately via [enrollPhrases] — the ceremony
     * needs labeled phrase audio, which unlabeled samples cannot provide.
     */
    override fun enroll(samples: List<AudioSample>): VoiceTemplate {
        val frames = samples.flatMap { checkedFrames(it) }
        return VoiceTemplate(
            speakerEmbedding = speakerModel.enroll(frames),
            phraseReferences = emptyMap(),
            sampleRateHz = Mfcc.SAMPLE_RATE,
        )
    }

    /**
     * Enroll labeled phrase references onto an existing template:
     * [refs] maps phraseId → audio of the enrolled person speaking it.
     * Returns a new template; the input is unchanged.
     */
    fun enrollPhrases(template: VoiceTemplate, refs: Map<String, AudioSample>): VoiceTemplate {
        val mfccRefs = refs.mapValues { (_, s) -> Mfcc.extract(s.pcm) }
            .filterValues { it.isNotEmpty() }
        return template.copy(phraseReferences = template.phraseReferences + mfccRefs)
    }

    override fun verify(sample: AudioSample, template: VoiceTemplate): VerificationResult {
        // Single-use challenge: read and clear atomically.
        val challenge = synchronized(lock) {
            val c = activeChallenge
            activeChallenge = null
            c
        }
        // --- input validation: fail closed, never throw ---
        if (sample.sampleRateHz != Mfcc.SAMPLE_RATE || sample.sampleRateHz != template.sampleRateHz)
            return reject(Double.NaN, VerificationResult.Liveness.NOT_LIVE)
        if (sample.pcm.isEmpty() || sample.pcm.any { !it.isFinite() })
            return reject(Double.NaN, VerificationResult.Liveness.NOT_LIVE)
        val frames = Mfcc.extract(sample.pcm)
        if (frames.isEmpty())
            return reject(Double.NaN, VerificationResult.Liveness.NOT_LIVE)

        // --- speaker check ---
        val score = speakerModel.score(frames, template.speakerEmbedding)
        val speakerOk = score.isFinite() && score >= acceptThreshold
        if (!speakerOk) return reject(score, VerificationResult.Liveness.NOT_LIVE)

        // --- liveness check: content must match the ACTIVE challenge ---
        if (challenge == null)
            return reject(score, VerificationResult.Liveness.NOT_CHECKED)
        val ref = template.phraseReferences[challenge.phraseId]
            ?: return reject(score, VerificationResult.Liveness.NOT_LIVE)
        val d = Dtw.distance(frames, ref)
        val live = d.isFinite() && d <= phraseThreshold
        return if (live) {
            VerificationResult(score, VerificationResult.Decision.ACCEPT, VerificationResult.Liveness.LIVE)
        } else {
            reject(score, VerificationResult.Liveness.NOT_LIVE)
        }
    }

    /** Frames for enrollment: invalid samples contribute nothing (fail-closed at verify). */
    private fun checkedFrames(s: AudioSample): List<DoubleArray> {
        if (s.sampleRateHz != Mfcc.SAMPLE_RATE) return emptyList()
        if (s.pcm.isEmpty() || s.pcm.any { !it.isFinite() }) return emptyList()
        return Mfcc.extract(s.pcm)
    }

    private fun reject(score: Double, liveness: VerificationResult.Liveness) =
        VerificationResult(score, VerificationResult.Decision.REJECT, liveness)

    companion object {
        /** Challenge phrase inventory: digit triples, prompted in words. */
        val defaultPhrases: List<VoiceChallenge> = listOf(
            VoiceChallenge("p1", "Please say: three seven one"),
            VoiceChallenge("p2", "Please say: nine two five"),
            VoiceChallenge("p3", "Please say: four eight six"),
            VoiceChallenge("p4", "Please say: one nine three"),
            VoiceChallenge("p5", "Please say: six four seven"),
            VoiceChallenge("p6", "Please say: two eight nine"),
            VoiceChallenge("p7", "Please say: five one four"),
            VoiceChallenge("p8", "Please say: seven three two"),
        )

        /** phraseId → digit sequence, shared with the synthetic fixture. */
        val phraseDigits: Map<String, IntArray> = mapOf(
            "p1" to intArrayOf(3, 7, 1),
            "p2" to intArrayOf(9, 2, 5),
            "p3" to intArrayOf(4, 8, 6),
            "p4" to intArrayOf(1, 9, 3),
            "p5" to intArrayOf(6, 4, 7),
            "p6" to intArrayOf(2, 8, 9),
            "p7" to intArrayOf(5, 1, 4),
            "p8" to intArrayOf(7, 3, 2),
        )
    }
}

// Named seed constant reads better than a hex literal at the call site.
private const val CHALLENGE_SEED: Long = 0xC4A11E
