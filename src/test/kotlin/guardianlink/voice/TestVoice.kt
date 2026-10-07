package guardianlink.voice

/**
 * Shared deterministic voice fixtures for tests.
 *
 * - [ubm]: trained ONCE (lazy) on a background population of synthetic
 *   speakers — deterministic given fixed seeds.
 * - [enrolled]: the "person" template — speaker model + phrase references
 *   for every challenge phrase.
 * - [anchor]: a FRESH (verifier, template) pair per call. The verifier is
 *   fresh because challenge state is per-instance; the UBM and template
 *   are shared and immutable.
 * - [acceptingSample]: issues a challenge on [v] and speaks the challenged
 *   phrase as the enrolled person — audio that passes verify().
 */
object TestVoice {
    /** The enrolled "person". */
    val PERSON = SyntheticVoice.Speaker(f0Hz = 120.0, formantScale = 1.0, seed = 7L)

    /** A different synthetic speaker — must be rejected. */
    val IMPOSTOR = SyntheticVoice.Speaker(f0Hz = 165.0, formantScale = 1.18, seed = 99L)

    private val backgroundSpeakers = listOf(
        SyntheticVoice.Speaker(f0Hz = 165.0, formantScale = 1.18, seed = 99L),
        SyntheticVoice.Speaker(f0Hz = 95.0, formantScale = 0.88, seed = 1234L),
        SyntheticVoice.Speaker(f0Hz = 140.0, formantScale = 1.07, seed = 555L),
        SyntheticVoice.Speaker(f0Hz = 105.0, formantScale = 0.94, seed = 4242L),
    )

    val phraseIds: List<String> = GmmUbmVoiceVerifier.phraseDigits.keys.toList()

    val ubm: Gmm by lazy {
        val frames = backgroundSpeakers.flatMap { sp ->
            phraseIds.flatMap { pid -> Mfcc.extract(SyntheticVoice.speak(sp, pid).pcm) }
        }
        Gmm.trainEm(frames, components = 8)
    }

    val enrolled: VoiceTemplate by lazy {
        val v = GmmUbmVoiceVerifier(ubm)
        val speakerSamples = phraseIds.map { pid -> SyntheticVoice.speak(PERSON, pid) }
        v.enrollPhrases(
            v.enroll(speakerSamples),
            phraseIds.associateWith { pid -> SyntheticVoice.speak(PERSON, pid) },
        )
    }

    /** Fresh (verifier, enrolled template) pair — one per engine under test. */
    fun anchor(): Pair<GmmUbmVoiceVerifier, VoiceTemplate> =
        GmmUbmVoiceVerifier(ubm) to enrolled

    /**
     * Audio that passes verify() on [v]: issues a challenge and speaks the
     * challenged phrase as the enrolled person.
     */
    fun acceptingSample(v: GmmUbmVoiceVerifier): AudioSample {
        val c = v.issueChallenge()
        return SyntheticVoice.speak(PERSON, c.phraseId)
    }

    /** Audio of the enrolled person speaking [phraseId] (no challenge issued). */
    fun personSays(phraseId: String): AudioSample = SyntheticVoice.speak(PERSON, phraseId)

    /** Audio of a different speaker (must be rejected). */
    fun impostorSays(phraseId: String): AudioSample = SyntheticVoice.speak(IMPOSTOR, phraseId)
}
