package guardianlink.trajectory

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrajectoryEstimatorTest {
    private val estimator = TrajectoryEstimator(minBaselineSamples = 3)

    @Test
    fun testAddBaselineSample() {
        estimator.addBaselineSample(100.0)
        estimator.addBaselineSample(101.0)
        estimator.addBaselineSample(102.0)

        val label = estimator.currentLabel()
        assertEquals(101.0, label.value)  // median of [100, 101, 102]
    }

    @Test
    fun testLabelConfidenceLow() {
        estimator.addBaselineSample(100.0)
        val label = estimator.currentLabel()
        assertEquals(TrajectoryEstimator.Confidence.LOW, label.confidence)
        assertEquals(TrajectoryEstimator.LabelStatus.PROVISIONAL, label.status)
    }

    @Test
    fun testLabelConfidenceMedium() {
        repeat(5) { estimator.addBaselineSample(100.0 + it) }
        val label = estimator.currentLabel()
        assertEquals(TrajectoryEstimator.Confidence.MEDIUM, label.confidence)
        assertEquals(TrajectoryEstimator.LabelStatus.PROVISIONAL, label.status)
    }

    @Test
    fun testLabelConfidenceHigh() {
        repeat(10) { estimator.addBaselineSample(100.0 + it % 3) }
        val label = estimator.currentLabel()
        assertEquals(TrajectoryEstimator.Confidence.HIGH, label.confidence)
        assertEquals(TrajectoryEstimator.LabelStatus.CONFIRMED, label.status)
    }

    @Test
    fun testUnstableBaselineStaysProvisionalDespiteCount() {
        // 10 samples (>= 3*min) but 30% are wild outliers: count alone must
        // not confer CONFIRMED.
        repeat(7) { estimator.addBaselineSample(100.0) }
        repeat(3) { estimator.addBaselineSample(500.0) }
        val label = estimator.currentLabel()
        assertEquals(TrajectoryEstimator.LabelStatus.PROVISIONAL, label.status)
    }

    @Test
    fun testCheckShiftInsuffientBaseline() {
        estimator.addBaselineSample(100.0)
        val shift = estimator.checkShift(150.0)
        assertFalse(shift.shiftDetected)
        assertEquals(0, shift.anomalyCount)
    }

    @Test
    fun testCheckShiftNoOutlier() {
        repeat(5) { estimator.addBaselineSample(100.0 + it * 0.5) }
        val shift = estimator.checkShift(101.0)  // close to baseline
        assertFalse(shift.shiftDetected)
    }

    @Test
    fun testCheckShiftDetectsOutlier() {
        repeat(5) { estimator.addBaselineSample(100.0) }

        // A single outlier does not confirm...
        var shift = estimator.checkShift(500.0)
        assertFalse(shift.shiftDetected)
        // ...three anomalies inside the window confirm the shift.
        estimator.checkShift(500.0)
        shift = estimator.checkShift(500.0)
        assertTrue(shift.shiftDetected)
    }

    @Test
    fun testCheckShiftWindowCounts() {
        repeat(5) { estimator.addBaselineSample(100.0) }

        var shift = estimator.checkShift(500.0)
        assertEquals(1, shift.anomalyCount)
        assertFalse(shift.shiftDetected)

        shift = estimator.checkShift(500.0)
        assertEquals(2, shift.anomalyCount)
        assertFalse(shift.shiftDetected)

        shift = estimator.checkShift(500.0)
        assertEquals(0, shift.anomalyCount)  // resets after confirmation
        assertTrue(shift.shiftDetected)
    }

    @Test
    fun testInterleavedNormalsDoNotEvadeShiftDetection() {
        // The old consecutive-streak rule was evadable by interleaving
        // normal values; windowed counting must still confirm.
        repeat(5) { estimator.addBaselineSample(100.0) }

        assertFalse(estimator.checkShift(500.0).shiftDetected)  // anomaly 1
        assertFalse(estimator.checkShift(100.0).shiftDetected)  // normal
        assertFalse(estimator.checkShift(500.0).shiftDetected)  // anomaly 2
        assertFalse(estimator.checkShift(100.0).shiftDetected)  // normal
        val shift = estimator.checkShift(500.0)                 // anomaly 3 in window of 5
        assertTrue(shift.shiftDetected, "interleaved normals evaded shift detection")
    }

    @Test
    fun testNonFiniteSamplesRejected() {
        assertFailsWith<IllegalArgumentException> { estimator.addBaselineSample(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { estimator.addBaselineSample(Double.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { estimator.addBaselineSample(Double.NEGATIVE_INFINITY) }
        repeat(5) { estimator.addBaselineSample(100.0) }
        assertFailsWith<IllegalArgumentException> { estimator.checkShift(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { estimator.checkShift(Double.POSITIVE_INFINITY) }
        // Baseline untouched by the rejected samples: still functional.
        assertEquals(100.0, estimator.currentLabel().value)
    }

    @Test
    fun testOversizedSampleRejected() {
        assertFailsWith<IllegalArgumentException> { estimator.addBaselineSample(1e10) }
        assertFailsWith<IllegalArgumentException> { estimator.addBaselineSample(-1e10) }
    }

    @Test
    fun testBaselineIsBoundedSlidingWindow() {
        val e = TrajectoryEstimator(minBaselineSamples = 3, maxBaselineSamples = 10)
        repeat(25) { e.addBaselineSample(it.toDouble()) }
        // Oldest 15 evicted; median of 15..24 is 19.5.
        assertEquals(19.5, e.currentLabel().value)
    }

    @Test
    fun testConfirmedLabelsExpire() {
        var now = 1_000_000L
        val e = TrajectoryEstimator(
            minBaselineSamples = 3, clock = { now },
            provisionalTtlMs = 1_000L, confirmedTtlMs = 2_000L
        )
        repeat(10) { e.addBaselineSample(100.0 + it % 3) }
        val label = e.currentLabel()
        assertEquals(TrajectoryEstimator.LabelStatus.CONFIRMED, label.status)
        assertFalse(e.isExpired(label))
        now += 2_001L
        assertTrue(e.isExpired(label), "CONFIRMED label never expired")
    }

    @Test
    fun testProvisionalLabelsExpire() {
        var now = 1_000_000L
        val e = TrajectoryEstimator(
            minBaselineSamples = 3, clock = { now }, provisionalTtlMs = 1_000L
        )
        e.addBaselineSample(100.0)
        val label = e.currentLabel()
        assertEquals(TrajectoryEstimator.LabelStatus.PROVISIONAL, label.status)
        assertFalse(e.isExpired(label))
        now += 1_001L
        assertTrue(e.isExpired(label))
    }

    @Test
    fun testCallerTimeCannotExtendValidity() {
        // isExpired takes no caller timestamp: the injected clock decides.
        // A caller holding a stale label object cannot make it look fresh.
        var now = 1_000_000L
        val e = TrajectoryEstimator(
            minBaselineSamples = 3, clock = { now }, provisionalTtlMs = 1_000L
        )
        e.addBaselineSample(100.0)
        val label = e.currentLabel()
        now += 2_000L
        assertTrue(e.isExpired(label))
    }
}
