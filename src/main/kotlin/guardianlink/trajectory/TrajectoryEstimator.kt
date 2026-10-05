package guardianlink.trajectory

import kotlin.math.abs

/**
 * Baseline / shift estimator with MAD anomaly detection.
 *
 * ADVERSARIAL NOTES — every item below is fail-closed:
 *
 * - Non-finite samples (NaN, ±Infinity) are REJECTED at ingestion. A single
 *   NaN would poison median/MAD comparisons (NaN is never < or > anything)
 *   and blind shift detection; infinities would warp the baseline.
 * - Bounded sample policy: the baseline is a sliding window of at most
 *   [maxBaselineSamples] (oldest evicted), and every sample must satisfy
 *   |value| <= [maxSampleMagnitude]. Unbounded growth and unbounded values
 *   let hostile input exhaust memory or inflate MAD until nothing is
 *   anomalous.
 * - Shift confirmation counts anomalies in a trailing window
 *   ([anomalyWindowSize]), not strictly-consecutive streaks. The old
 *   3-consecutive rule was evadable by interleaving normal values between
 *   anomalies; 3 anomalies in any 5-sample window now confirm.
 * - CONFIRMED is never count-alone: it additionally requires the baseline
 *   to be self-consistent (outlier fraction <= [maxOutlierFraction]) AND
 *   it expires after [confirmedTtlMs]. Count reaching a threshold never
 *   meant the samples were authentic.
 * - Time comes from the injected [clock], never from a caller-supplied
 *   timestamp: callers cannot extend a label's validity by passing an old
 *   `now`.
 *
 * TRUST BOUNDARY: sample AUTHENTICITY is out of scope — a caller that feeds
 * fabricated-but-plausible samples gets plausible labels. Statistical
 * self-consistency raises the cost of poisoning; real provenance (signed,
 * attested sensor streams) REQUIRES adapters outside this module.
 */
class TrajectoryEstimator(
    private val minBaselineSamples: Int = 30,
    private val maxBaselineSamples: Int = 1000,
    private val maxSampleMagnitude: Double = 1e9,
    private val varianceFloor: Double = 1e-6,
    private val madThreshold: Double = 3.5,
    private val anomalyWindowSize: Int = 5,
    private val anomaliesToConfirm: Int = 3,
    private val maxOutlierFraction: Double = 0.10,
    private val provisionalTtlMs: Long = 24L * 60 * 60 * 1000,
    private val confirmedTtlMs: Long = 7L * 24 * 60 * 60 * 1000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(minBaselineSamples >= 1) { "minBaselineSamples must be >= 1" }
        require(maxBaselineSamples >= minBaselineSamples) { "maxBaselineSamples must be >= minBaselineSamples" }
        require(maxSampleMagnitude > 0 && maxSampleMagnitude.isFinite()) { "maxSampleMagnitude must be positive finite" }
        require(anomalyWindowSize >= anomaliesToConfirm && anomaliesToConfirm >= 1) {
            "need 1 <= anomaliesToConfirm <= anomalyWindowSize"
        }
        require(maxOutlierFraction in 0.0..1.0) { "maxOutlierFraction must be in [0, 1]" }
    }

    enum class Confidence { LOW, MEDIUM, HIGH }
    enum class LabelStatus { PROVISIONAL, CONFIRMED, EXPIRED }

    data class Label(
        val value: Double,
        val confidence: Confidence,
        val status: LabelStatus,
        val createdAtMs: Long
    )

    data class ShiftResult(val shiftDetected: Boolean, val anomalyCount: Int)

    private val baseline = ArrayDeque<Double>()
    private val anomalyWindow = ArrayDeque<Boolean>()

    /**
     * Ingest one baseline sample. Rejects non-finite and out-of-magnitude
     * values loudly — silently dropping hostile input would hide an attack.
     */
    fun addBaselineSample(value: Double) {
        require(value.isFinite()) { "non-finite baseline sample rejected (fail-closed)" }
        require(abs(value) <= maxSampleMagnitude) {
            "sample magnitude ${abs(value)} exceeds bound $maxSampleMagnitude"
        }
        baseline.addLast(value)
        while (baseline.size > maxBaselineSamples) baseline.removeFirst()
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

    private fun mad(values: List<Double>, med: Double): Double =
        median(values.map { abs(it - med) })

    private fun isOutlier(x: Double, med: Double, scale: Double): Boolean =
        abs(x - med) / scale > madThreshold

    /** Fraction of baseline samples that are MAD-outliers vs the baseline itself. */
    private fun outlierFraction(): Double {
        if (baseline.isEmpty()) return 0.0
        val med = median(baseline.toList())
        val scale = maxOf(mad(baseline.toList(), med), varianceFloor)
        return baseline.count { isOutlier(it, med, scale) }.toDouble() / baseline.size
    }

    fun currentLabel(): Label {
        val now = clock()
        val med = if (baseline.isEmpty()) 0.0 else median(baseline.toList())
        val confidence = when {
            baseline.size < minBaselineSamples -> Confidence.LOW
            baseline.size < minBaselineSamples * 3 -> Confidence.MEDIUM
            else -> Confidence.HIGH
        }
        // CONFIRMED is never count-alone: the baseline must also be
        // self-consistent. A poisoned-but-numerous baseline stays PROVISIONAL.
        val stable = outlierFraction() <= maxOutlierFraction
        val status =
            if (baseline.size >= minBaselineSamples * 3 && stable) LabelStatus.CONFIRMED
            else LabelStatus.PROVISIONAL
        return Label(med, confidence, status, now)
    }

    /**
     * Labels expire: PROVISIONAL after [provisionalTtlMs], CONFIRMED after
     * [confirmedTtlMs]. Time comes from the injected clock — callers cannot
     * extend validity by supplying timestamps.
     */
    fun isExpired(label: Label): Boolean {
        if (label.status == LabelStatus.EXPIRED) return true
        val ttl = if (label.status == LabelStatus.CONFIRMED) confirmedTtlMs else provisionalTtlMs
        return clock() - label.createdAtMs > ttl
    }

    /**
     * Score one new sample against the baseline. Confirms a shift when
     * [anomaliesToConfirm] anomalies land inside the trailing
     * [anomalyWindowSize]-sample window — interleaved normal values do not
     * evade detection. Non-finite samples are rejected, not scored.
     */
    fun checkShift(newSample: Double): ShiftResult {
        require(newSample.isFinite()) { "non-finite shift sample rejected (fail-closed)" }
        if (baseline.size < minBaselineSamples) {
            return ShiftResult(shiftDetected = false, anomalyCount = 0)
        }
        val med = median(baseline.toList())
        val scale = maxOf(mad(baseline.toList(), med), varianceFloor)

        anomalyWindow.addLast(isOutlier(newSample, med, scale))
        while (anomalyWindow.size > anomalyWindowSize) anomalyWindow.removeFirst()

        val hits = anomalyWindow.count { it }
        val confirmed = hits >= anomaliesToConfirm
        if (confirmed) anomalyWindow.clear()
        return ShiftResult(shiftDetected = confirmed, anomalyCount = if (confirmed) 0 else hits)
    }
}
