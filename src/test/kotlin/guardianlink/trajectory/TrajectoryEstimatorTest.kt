package guardianlink.trajectory

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
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
    fun testCheckShiftInsuffientBaseline() {
        estimator.addBaselineSample(100.0)
        val shift = estimator.checkShift(150.0)
        assertFalse(shift.shiftDetected)
        assertEquals(0, shift.consecutiveCount)
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
        val shift = estimator.checkShift(500.0)  // massive outlier
        assertTrue(shift.shiftDetected)
    }

    @Test
    fun testCheckShiftConsecutiveThreshold() {
        repeat(5) { estimator.addBaselineSample(100.0) }
        
        // Need 3 consecutive to trigger
        var shift = estimator.checkShift(500.0)
        assertEquals(1, shift.consecutiveCount)
        assertFalse(shift.shiftDetected)
        
        shift = estimator.checkShift(500.0)
        assertEquals(2, shift.consecutiveCount)
        assertFalse(shift.shiftDetected)
        
        shift = estimator.checkShift(500.0)
        assertEquals(0, shift.consecutiveCount)  // resets after confirmation
        assertTrue(shift.shiftDetected)
    }

    @Test
    fun testCheckShiftStreakResetsOnNormal() {
        repeat(5) { estimator.addBaselineSample(100.0) }
        
        estimator.checkShift(500.0)  // 1
        estimator.checkShift(500.0)  // 2
        val shift = estimator.checkShift(101.0)  // normal — streak resets
        
        assertEquals(0, shift.consecutiveCount)
        assertFalse(shift.shiftDetected)
    }

    @Test
    fun testLabelExpiration() {
        val oldLabel = TrajectoryEstimator.Label(
            value = 100.0,
            confidence = TrajectoryEstimator.Confidence.LOW,
            status = TrajectoryEstimator.LabelStatus.PROVISIONAL,
            createdAtMs = System.currentTimeMillis() - (25 * 60 * 60 * 1000)  // 25 hours ago
        )
        assertTrue(estimator.isExpired(oldLabel))
        
        val freshLabel = TrajectoryEstimator.Label(
            value = 100.0,
            confidence = TrajectoryEstimator.Confidence.LOW,
            status = TrajectoryEstimator.LabelStatus.PROVISIONAL,
            createdAtMs = System.currentTimeMillis() - (1 * 60 * 60 * 1000)  // 1 hour ago
        )
        assertFalse(estimator.isExpired(freshLabel))
    }
}
