package guardianlink.voice

import kotlin.math.*

/**
 * Real MFCC feature extraction over mono PCM.
 *
 * Pipeline: pre-emphasis → 25 ms framing / 10 ms shift → Hamming window →
 * 512-point radix-2 FFT → 26-channel mel filterbank (0–8 kHz) → log →
 * DCT-II → 13 cepstral coefficients → per-utterance cepstral mean
 * normalization → delta + delta-delta regression → 39-dimensional frames.
 *
 * Deterministic: no randomness anywhere in this object. Pure functions of
 * the input PCM.
 */
object Mfcc {
    /** The only sample rate this implementation accepts. */
    const val SAMPLE_RATE = 16000

    /** Frame length: 25 ms. */
    const val FRAME_SAMPLES = 400

    /** Frame shift: 10 ms. */
    const val SHIFT_SAMPLES = 160

    /** FFT size (next power of two ≥ frame length). */
    const val FFT_SIZE = 512

    /** Mel filterbank channels. */
    const val FILTERS = 26

    /** Cepstral coefficients kept. */
    const val COEFFS = 13

    /** Final frame dimension: 13 static + 13 delta + 13 delta-delta. */
    const val DIM = 39

    private const val PRE_EMPHASIS = 0.97
    private const val LOG_FLOOR = 1e-10

    /**
     * Extract 39-dim MFCC frames from PCM. Returns an empty list when the
     * audio is shorter than one frame — the caller fails closed on that.
     */
    fun extract(pcm: DoubleArray): List<DoubleArray> {
        if (pcm.size < FRAME_SAMPLES) return emptyList()
        // 1. Pre-emphasis: lift high frequencies flattened by glottal rolloff.
        val emphasized = DoubleArray(pcm.size)
        emphasized[0] = pcm[0]
        for (n in 1 until pcm.size) emphasized[n] = pcm[n] - PRE_EMPHASIS * pcm[n - 1]
        // 2–3. Framing + Hamming window.
        val frameCount = 1 + (emphasized.size - FRAME_SAMPLES) / SHIFT_SAMPLES
        val window = hamming(FRAME_SAMPLES)
        val statics = ArrayList<DoubleArray>(frameCount)
        val re = DoubleArray(FFT_SIZE)
        val im = DoubleArray(FFT_SIZE)
        for (f in 0 until frameCount) {
            val off = f * SHIFT_SAMPLES
            re.fill(0.0); im.fill(0.0)
            for (n in 0 until FRAME_SAMPLES) re[n] = emphasized[off + n] * window[n]
            fft(re, im)
            // 4. Power spectrum, first half.
            val power = DoubleArray(FFT_SIZE / 2 + 1)
            for (k in power.indices) power[k] = (re[k] * re[k] + im[k] * im[k]) / FFT_SIZE
            // 5–6. Mel filterbank energies → log → DCT-II.
            statics.add(dct(logFilterbank(power)))
        }
        // 7. Cepstral mean normalization: removes convolutional channel bias.
        val mean = DoubleArray(COEFFS)
        for (fr in statics) for (c in 0 until COEFFS) mean[c] += fr[c] / statics.size
        for (fr in statics) for (c in 0 until COEFFS) fr[c] -= mean[c]
        // 8. Deltas: first- and second-order regression over ±2 frames,
        // edges clamped (a one-frame utterance yields zero deltas).
        val deltas = regressionDeltas(statics)
        val deltaDeltas = regressionDeltas(deltas)
        return List(statics.size) { i -> statics[i] + deltas[i] + deltaDeltas[i] }
    }

    /**
     * Regression delta of each frame within [seq]: d[t] =
     * Σ_{n=1..2} n·(c[t+n] − c[t−n]) / (2·Σ n²), edges clamped.
     */
    private fun regressionDeltas(seq: List<DoubleArray>): List<DoubleArray> {
        if (seq.isEmpty()) return emptyList()
        val denom = 2.0 * (1 + 4) // 2·Σ n² for n = 1..2
        return List(seq.size) { t ->
            DoubleArray(seq[t].size) { d ->
                var num = 0.0
                for (n in 1..2) {
                    val ahead = seq[min(t + n, seq.size - 1)][d]
                    val behind = seq[max(t - n, 0)][d]
                    num += n * (ahead - behind)
                }
                num / denom
            }
        }
    }

    private fun hamming(n: Int): DoubleArray =
        DoubleArray(n) { i -> 0.54 - 0.46 * cos(2.0 * PI * i / (n - 1)) }

    /** In-place radix-2 Cooley–Tukey FFT. [re]/[im] length must be a power of two. */
    internal fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wRe = cos(ang)
            val wIm = sin(ang)
            var i = 0
            while (i < n) {
                var wr = 1.0
                var wi = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val xr = re[i + k + len / 2]; val xi = im[i + k + len / 2]
                    val vr = xr * wr - xi * wi
                    val vi = xr * wi + xi * wr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val nwr = wr * wRe - wi * wIm
                    wi = wr * wIm + wi * wRe
                    wr = nwr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Triangular mel filterbank matrix, lazily built once (deterministic). */
    private val melBank: Array<DoubleArray> by lazy {
        val lowMel = hzToMel(0.0)
        val highMel = hzToMel(SAMPLE_RATE / 2.0)
        val points = DoubleArray(FILTERS + 2) { i ->
            melToHz(lowMel + i * (highMel - lowMel) / (FILTERS + 1))
        }
        // FFT bin center frequencies.
        val bins = DoubleArray(FFT_SIZE / 2 + 1) { k -> k * (SAMPLE_RATE / 2.0) / (FFT_SIZE / 2) }
        Array(FILTERS) { m ->
            DoubleArray(bins.size) { k ->
                val f = bins[k]
                when {
                    f < points[m] || f > points[m + 2] -> 0.0
                    f <= points[m + 1] -> (f - points[m]) / (points[m + 1] - points[m])
                    else -> (points[m + 2] - f) / (points[m + 2] - points[m + 1])
                }
            }
        }
    }

    private fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)
    private fun melToHz(mel: Double): Double = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

    private fun logFilterbank(power: DoubleArray): DoubleArray =
        DoubleArray(FILTERS) { m ->
            var e = 0.0
            val row = melBank[m]
            for (k in power.indices) e += power[k] * row[k]
            ln(max(e, LOG_FLOOR))
        }

    /** DCT-II, keeping the first [COEFFS] coefficients. */
    private fun dct(logEnergies: DoubleArray): DoubleArray =
        DoubleArray(COEFFS) { k ->
            var s = 0.0
            for (n in logEnergies.indices)
                s += logEnergies[n] * cos(PI * k * (2 * n + 1) / (2.0 * FILTERS))
            s
        }
}
