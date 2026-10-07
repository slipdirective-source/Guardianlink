package guardianlink.voice

import kotlin.math.*
import kotlin.random.Random

/**
 * Deterministic formant-based synthetic voices — a DEMO/TEST FIXTURE, not a
 * biometric. It generates labeled PCM so the pipeline (MFCC → GMM-UBM →
 * challenge/DTW → Gate 7) can be exercised without microphone capture or
 * real voice data. It proves the machinery works; it proves nothing about
 * real-speaker accuracy. Do not cite synthetic-voice test results as
 * biometric performance claims.
 *
 * Model: each "phoneme" is a vowel-like formant triple (F1, F2, F3). A
 * speaker is a fundamental frequency plus a formant-scale factor (vocal
 * tract length proxy). Speaking a phrase renders each phoneme as a
 * harmonic series shaped by formant resonances, with vibrato and a
 * raised-cosine amplitude envelope. Deterministic given (speaker, phrase):
 * same inputs → bit-identical PCM.
 */
object SyntheticVoice {
    /** A synthetic speaker: pitch + vocal-tract scale + stream seed. */
    data class Speaker(val f0Hz: Double, val formantScale: Double, val seed: Long)

    /**
     * Ten vowel-like phonemes as (F1, F2, F3) in Hz at scale 1.0.
     * Indexed by digit so phrases are digit strings.
     */
    val PHONEMES: Array<DoubleArray> = arrayOf(
        doubleArrayOf(730.0, 1090.0, 2440.0), // 0
        doubleArrayOf(270.0, 2290.0, 3010.0), // 1
        doubleArrayOf(300.0, 870.0, 2240.0),  // 2
        doubleArrayOf(530.0, 1840.0, 2480.0), // 3
        doubleArrayOf(570.0, 840.0, 2410.0),  // 4
        doubleArrayOf(440.0, 1800.0, 2600.0), // 5
        doubleArrayOf(660.0, 1720.0, 2410.0), // 6
        doubleArrayOf(310.0, 2000.0, 2800.0), // 7
        doubleArrayOf(520.0, 1500.0, 2500.0), // 8
        doubleArrayOf(380.0, 2100.0, 2900.0), // 9
    )

    /** Phoneme duration in ms. */
    const val PHONEME_MS = 120

    /** Formant resonance bandwidth in Hz. */
    private const val FORMANT_BW = 120.0

    /** Harmonics rendered per phoneme. */
    private const val HARMONICS = 14

    /**
     * Render [speaker] speaking [phraseId] (digit triple per
     * [GmmUbmVoiceVerifier.phraseDigits]) at 16 kHz mono.
     */
    fun speak(
        speaker: Speaker,
        phraseId: String,
        sampleRateHz: Int = Mfcc.SAMPLE_RATE,
    ): AudioSample {
        val digits = GmmUbmVoiceVerifier.phraseDigits[phraseId]
            ?: throw IllegalArgumentException("unknown phrase $phraseId")
        return speakDigits(speaker, digits, sampleRateHz)
    }

    /** Render [speaker] speaking an arbitrary digit sequence. */
    fun speakDigits(
        speaker: Speaker,
        digits: IntArray,
        sampleRateHz: Int = Mfcc.SAMPLE_RATE,
    ): AudioSample {
        val rng = Random(speaker.seed)
        // Tiny deterministic per-utterance detune so repeated renders are
        // not bit-identical while staying the same voice (jitter << JND).
        val f0 = speaker.f0Hz * (1.0 + (rng.nextDouble() - 0.5) * 0.004)
        val segLen = (PHONEME_MS * sampleRateHz / 1000)
        val pcm = DoubleArray(segLen * digits.size)
        for ((s, digit) in digits.withIndex()) {
            val formants = PHONEMES[digit].map { it * speaker.formantScale }.toDoubleArray()
            val off = s * segLen
            for (n in 0 until segLen) {
                val t = n.toDouble() / sampleRateHz
                // Vibrato: ±0.6% at 5 Hz — keeps frames from being
                // pathologically stationary without changing identity.
                val instF0 = f0 * (1.0 + 0.006 * sin(2.0 * PI * 5.0 * t))
                var v = 0.0
                for (h in 1..HARMONICS) {
                    val f = h * instF0
                    if (f > sampleRateHz / 2.0) break
                    var gain = 0.0
                    for (ff in formants) {
                        val d = (f - ff) / FORMANT_BW
                        gain += 1.0 / (1.0 + d * d)
                    }
                    // 1/h glottal rolloff.
                    v += gain * sin(2.0 * PI * f * t + h) / h
                }
                // Raised-cosine edges per phoneme: no clicks at boundaries.
                val edge = min(n, segLen - 1 - n).toDouble() / (0.01 * sampleRateHz)
                val env = min(1.0, edge).let { e -> 0.5 * (1.0 - cos(PI * e.coerceIn(0.0, 1.0))) }
                pcm[off + n] = 0.25 * v * env
            }
        }
        // Normalize to [-1, 1].
        val peak = pcm.maxOfOrNull { abs(it) } ?: 0.0
        if (peak > 0) for (i in pcm.indices) pcm[i] /= peak
        return AudioSample(pcm, sampleRateHz)
    }
}
