package guardianlink.voice

/**
 * Mono PCM audio captured during the assent ceremony.
 *
 * [pcm] is normalized to [-1.0, 1.0]. [sampleRateHz] must be
 * [Mfcc.SAMPLE_RATE] — resampling is the deployment's job (microphone
 * capture, resampling, and the challenge ceremony all live outside the
 * engine; the adapter consumes PCM). Non-finite samples, empty audio, or
 * a wrong rate fail closed inside [BiometricVerifier.verify], never here:
 * the constructor only rejects a non-positive rate so adversarial evidence
 * can be constructed and observed halting at Gate 7.
 */
data class AudioSample(
    val pcm: DoubleArray,
    val sampleRateHz: Int = Mfcc.SAMPLE_RATE,
) {
    init {
        require(sampleRateHz > 0) { "sampleRateHz must be positive" }
    }

    /** Duration in seconds. */
    val durationSec: Double get() = pcm.size.toDouble() / sampleRateHz

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioSample) return false
        return sampleRateHz == other.sampleRateHz && pcm.contentEquals(other.pcm)
    }

    override fun hashCode(): Int = 31 * sampleRateHz + pcm.contentHashCode()
}
