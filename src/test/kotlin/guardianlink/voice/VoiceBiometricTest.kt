package guardianlink.voice

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Voice biometric adapter tests — all audio is deterministic synthetic
 * (SyntheticVoice), so no microphone or real voice data is needed. The
 * DSP is real: these tests exercise actual MFCC extraction, EM-trained
 * GMM-UBM scoring, and DTW liveness — not stubs.
 */
class VoiceBiometricTest {

    // ------------------------------------------------------------------
    // MFCC: real signal processing, deterministic
    // ------------------------------------------------------------------

    @Test
    fun mfccExtracts39DimFiniteFrames() {
        val audio = SyntheticVoice.speak(TestVoice.PERSON, "p1")
        val frames = Mfcc.extract(audio.pcm)
        assertTrue(frames.isNotEmpty(), "expected frames")
        for (f in frames) {
            assertEquals(Mfcc.DIM, f.size)
            assertTrue(f.all { it.isFinite() }, "non-finite MFCC coefficient")
        }
    }

    @Test
    fun mfccIsDeterministic() {
        val a = Mfcc.extract(SyntheticVoice.speak(TestVoice.PERSON, "p1").pcm)
        val b = Mfcc.extract(SyntheticVoice.speak(TestVoice.PERSON, "p1").pcm)
        assertEquals(a.size, b.size)
        for (i in a.indices) assertTrue(a[i].contentEquals(b[i]), "MFCC not deterministic at frame $i")
    }

    @Test
    fun mfccDistinguishesSpeakersAndPhrases() {
        val p1a = Mfcc.extract(SyntheticVoice.speak(TestVoice.PERSON, "p1").pcm)
        val p1b = Mfcc.extract(SyntheticVoice.speak(TestVoice.IMPOSTOR, "p1").pcm)
        val p2a = Mfcc.extract(SyntheticVoice.speak(TestVoice.PERSON, "p2").pcm)
        // Different speakers, same phrase: frame sequences differ.
        assertTrue(Dtw.distance(p1a, p1b) > 1.0, "speakers indistinguishable")
        // Same speaker, different phrase: content differs.
        assertTrue(Dtw.distance(p1a, p2a) > 1.0, "phrases indistinguishable")
        // Same speaker, same phrase: identical (deterministic synth).
        assertEquals(0.0, Dtw.distance(p1a, p1a))
    }

    @Test
    fun mfccShortAudioYieldsNoFrames() {
        assertTrue(Mfcc.extract(DoubleArray(100)).isEmpty(), "sub-frame audio must yield no frames")
    }

    // ------------------------------------------------------------------
    // GMM-UBM speaker model: real scoring
    // ------------------------------------------------------------------

    @Test
    fun gmmUbmAcceptsEnrolledSpeakerRejectsImpostor() {
        val model = GmmUbmSpeakerModel(TestVoice.ubm)
        val enrollFrames = TestVoice.phraseIds.flatMap { pid ->
            Mfcc.extract(SyntheticVoice.speak(TestVoice.PERSON, pid).pcm)
        }
        val emb = model.enroll(enrollFrames)
        val same = Mfcc.extract(TestVoice.personSays("p3").pcm)
        val other = Mfcc.extract(TestVoice.impostorSays("p3").pcm)
        val sameScore = model.score(same, emb)
        val otherScore = model.score(other, emb)
        assertTrue(sameScore > 0.0, "enrolled speaker LLR should be positive, was $sameScore")
        assertTrue(otherScore < 0.0, "impostor LLR should be negative, was $otherScore")
    }

    @Test
    fun gmmUbmScoreIsFailClosedOnBadInput() {
        val model = GmmUbmSpeakerModel(TestVoice.ubm)
        val emb = model.enroll(listOf(DoubleArray(Mfcc.DIM) { 0.1 }))
        assertEquals(Double.NEGATIVE_INFINITY, model.score(emptyList(), emb))
        // Wrong embedding kind (neural vector into a GMM model): -inf, never throw.
        val frames = Mfcc.extract(TestVoice.personSays("p1").pcm)
        assertEquals(
            Double.NEGATIVE_INFINITY,
            model.score(frames, SpeakerEmbedding.Vector(DoubleArray(16) { 0.1 })),
        )
    }

    // ------------------------------------------------------------------
    // Verifier: enrollment, challenge-response liveness, fail-closed
    // ------------------------------------------------------------------

    private fun freshAnchor() = TestVoice.anchor()

    @Test
    fun fullCeremonyAcceptsEnrolledSpeaker() {
        val (v, t) = freshAnchor()
        val challenge = v.issueChallenge()
        val sample = SyntheticVoice.speak(TestVoice.PERSON, challenge.phraseId)
        val r = v.verify(sample, t)
        assertEquals(VerificationResult.Decision.ACCEPT, r.decision)
        assertEquals(VerificationResult.Liveness.LIVE, r.liveness)
        assertTrue(r.score.isFinite() && r.score > 0.0, "score=${r.score}")
    }

    @Test
    fun impostorIsRejected() {
        val (v, t) = freshAnchor()
        val challenge = v.issueChallenge()
        val r = v.verify(TestVoice.impostorSays(challenge.phraseId), t)
        assertEquals(VerificationResult.Decision.REJECT, r.decision)
    }

    @Test
    fun wrongPhraseFailsLiveness() {
        val (v, t) = freshAnchor()
        val challenge = v.issueChallenge()
        // Right voice, wrong content: speaker check passes, liveness must not.
        val wrong = TestVoice.phraseIds.first { it != challenge.phraseId }
        val r = v.verify(TestVoice.personSays(wrong), t)
        assertEquals(VerificationResult.Decision.REJECT, r.decision)
        assertEquals(VerificationResult.Liveness.NOT_LIVE, r.liveness)
        assertTrue(r.score > 0.0, "sanity: the speaker check itself passed (score=${r.score})")
    }

    @Test
    fun verifyWithoutChallengeIsRejected() {
        val (v, t) = freshAnchor()
        // No issueChallenge call: liveness cannot be established.
        val r = v.verify(TestVoice.personSays("p1"), t)
        assertEquals(VerificationResult.Decision.REJECT, r.decision)
        assertEquals(VerificationResult.Liveness.NOT_CHECKED, r.liveness)
    }

    @Test
    fun challengeIsSingleUse() {
        val (v, t) = freshAnchor()
        val challenge = v.issueChallenge()
        val sample = SyntheticVoice.speak(TestVoice.PERSON, challenge.phraseId)
        val first = v.verify(sample, t)
        assertEquals(VerificationResult.Decision.ACCEPT, first.decision)
        // Same audio, same (now consumed) challenge: must not verify again.
        val second = v.verify(sample, t)
        assertEquals(VerificationResult.Decision.REJECT, second.decision)
        assertEquals(VerificationResult.Liveness.NOT_CHECKED, second.liveness)
    }

    @Test
    fun hostileAudioIsRejectedNeverThrows() {
        val (v, t) = freshAnchor()
        v.issueChallenge()
        val bad = listOf(
            AudioSample(doubleArrayOf(), 16000), // empty
            AudioSample(doubleArrayOf(Double.NaN, 0.1, -0.2), 16000), // non-finite
            AudioSample(doubleArrayOf(Double.POSITIVE_INFINITY), 16000), // infinite
            AudioSample(DoubleArray(16000) { 0.01 }, 8000), // wrong rate
        )
        for (s in bad) {
            val r = v.verify(s, t)
            assertEquals(VerificationResult.Decision.REJECT, r.decision, "hostile sample accepted: $s")
        }
    }

    @Test
    fun templateMismatchIsRejected() {
        // A template enrolled for someone else fails against this speaker.
        val (v, _) = freshAnchor()
        val otherTemplate = v.enrollPhrases(
            v.enroll(listOf(TestVoice.impostorSays("p1"))),
            mapOf("p1" to TestVoice.impostorSays("p1")),
        )
        val challenge = v.issueChallenge()
        val r = v.verify(TestVoice.personSays(challenge.phraseId), otherTemplate)
        assertEquals(VerificationResult.Decision.REJECT, r.decision)
    }

    @Test
    fun enrollIsDeterministic() {
        val (v, _) = freshAnchor()
        val samples = listOf(TestVoice.personSays("p1"), TestVoice.personSays("p2"))
        val t1 = v.enroll(samples)
        val t2 = v.enroll(samples)
        val e1 = t1.speakerEmbedding as SpeakerEmbedding.GmmModel
        val e2 = t2.speakerEmbedding as SpeakerEmbedding.GmmModel
        for (k in e1.model.weights.indices) {
            assertTrue(e1.model.weights.contentEquals(e2.model.weights))
            assertTrue(e1.model.means[k].contentEquals(e2.model.means[k]))
        }
    }
}
