package guardianlink.trajectory

import java.time.Instant
import kotlin.math.abs

class TrajectoryEstimator(
    private val minBaselineSamples: Int = 30,
    private val varianceFloor: Double = 1e-6,
    private val madThreshold: Double = 3.5,
    private val consecutiveConfirmation: Int = 3,
    private val provisionalTtlMs: Long = 24L * 60 * 60 * 1000
) {
    enum class Confidence { LOW, MEDIUM, HIGH }
    enum class LabelStatus { PROVISIONAL, CONFIRMED, EXPIRED }

    data class Label(
        val value: Double,
        val confidence: Confidence,
        val status: LabelStatus,
        val createdAtMs: Long
    )

    data class ShiftResult(val shiftDetected: Boolean, val consecutiveCount: Int)

    private val baseline = mutableListOf<Double>()
    private var pendingShiftStreak = 0

    fun addBaselineSample(value: Double) {
        baseline.add(value)
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

    private fun mad(values: List<Double>, med: Double): Double {
        val deviations = values.map { abs(it - med) }
        return median(deviations)
    }

    fun currentLabel(now: Instant = Instant.now()): Label {
        val med = if (baseline.isEmpty()) 0.0 else median(baseline)
        val confidence = when {
            baseline.size < minBaselineSamples -> Confidence.LOW
            baseline.size < minBaselineSamples * 3 -> Confidence.MEDIUM
            else -> Confidence.HIGH
        }
        val status = if (baseline.size < minBaselineSamples) LabelStatus.PROVISIONAL else LabelStatus.CONFIRMED
        return Label(med, confidence, status, now.toEpochMilli())
    }

    fun isExpired(label: Label, now: Instant = Instant.now()): Boolean =
        label.status == LabelStatus.PROVISIONAL && (now.toEpochMilli() - label.createdAtMs) > provisionalTtlMs

    fun checkShift(newSample: Double): ShiftResult {
        if (baseline.size < minBaselineSamples) {
            pendingShiftStreak = 0
            return ShiftResult(shiftDetected = false, consecutiveCount = 0)
        }
        val med = median(baseline)
        val deviation = mad(baseline, med)
        val effectiveDeviation = maxOf(deviation, varianceFloor)

        val z = abs(newSample - med) / effectiveDeviation
        val exceedsThreshold = z > madThreshold

        pendingShiftStreak = if (exceedsThreshold) pendingShiftStreak + 1 else 0

        val confirmed = pendingShiftStreak >= consecutiveConfirmation
        if (confirmed) pendingShiftStreak = 0

        return ShiftResult(shiftDetected = confirmed, consecutiveCount = pendingShiftStreak)
    }
}