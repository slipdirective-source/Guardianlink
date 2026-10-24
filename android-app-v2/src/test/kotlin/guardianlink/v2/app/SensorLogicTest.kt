package guardianlink.v2.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure decision logic of the location and motion collectors. The
 * collectors themselves are Android-coupled; everything they DECIDE —
 * provider choice, confidence from accuracy, claim text, still and
 * moving transitions, step delta math — lives in android-free
 * functions and is covered here on the JVM.
 */
class SensorLogicTest {

    @Test
    fun `location confidence follows the fix accuracy bands`() {
        assertEquals(0.5, LocationLogic.confidenceForAccuracy(null))
        assertEquals(0.5, LocationLogic.confidenceForAccuracy(0.0))
        assertEquals(0.95, LocationLogic.confidenceForAccuracy(5.0))
        assertEquals(0.9, LocationLogic.confidenceForAccuracy(20.0))
        assertEquals(0.8, LocationLogic.confidenceForAccuracy(50.0))
        assertEquals(0.65, LocationLogic.confidenceForAccuracy(300.0))
        assertEquals(0.5, LocationLogic.confidenceForAccuracy(2000.0))
    }

    @Test
    fun `location provider choice respects permissions and enabled providers`() {
        assertEquals(
            LocationLogic.PROVIDER_GPS,
            LocationLogic.chooseProvider(hasFinePermission = true, hasCoarsePermission = true, gpsEnabled = true, networkEnabled = true),
        )
        assertEquals(
            LocationLogic.PROVIDER_NETWORK,
            LocationLogic.chooseProvider(hasFinePermission = true, hasCoarsePermission = false, gpsEnabled = false, networkEnabled = true),
        )
        assertEquals(
            LocationLogic.PROVIDER_NETWORK,
            LocationLogic.chooseProvider(hasFinePermission = false, hasCoarsePermission = true, gpsEnabled = true, networkEnabled = true),
            "coarse only never selects GPS",
        )
        assertNull(
            LocationLogic.chooseProvider(hasFinePermission = false, hasCoarsePermission = true, gpsEnabled = true, networkEnabled = false),
            "coarse permission with only GPS enabled has no lawful provider",
        )
        assertNull(
            LocationLogic.chooseProvider(hasFinePermission = false, hasCoarsePermission = false, gpsEnabled = true, networkEnabled = true),
            "no permission means no provider",
        )
        assertNull(
            LocationLogic.chooseProvider(hasFinePermission = true, hasCoarsePermission = true, gpsEnabled = false, networkEnabled = false),
            "all providers disabled means no provider",
        )
    }

    @Test
    fun `location claim carries coordinates provider and honest accuracy`() {
        val claim = LocationLogic.formatClaim(38.123456, -85.765432, 12.4, LocationLogic.PROVIDER_GPS)
        assertTrue(claim.contains("38.12346"), "latitude at five decimals: $claim")
        assertTrue(claim.contains("-85.76543"), "longitude at five decimals: $claim")
        assertTrue(claim.contains("gps"), "provider named: $claim")
        assertTrue(claim.contains("±12m"), "accuracy stated: $claim")
        val unknown = LocationLogic.formatClaim(38.0, -85.0, null, LocationLogic.PROVIDER_NETWORK)
        assertTrue(unknown.contains("accuracy unknown"), "missing accuracy is named, not invented: $unknown")
    }

    @Test
    fun `motion classifier reports transitions only and settles back to still`() {
        val classifier = MotionClassifier()
        repeat(20) {
            assertNull(classifier.onSample(0.1), "a still device never reports a transition")
        }
        assertEquals(MotionState.STILL, classifier.state)

        var movingAt: MotionState? = null
        repeat(8) {
            val t = classifier.onSample(3.0)
            if (t != null) movingAt = t
        }
        assertEquals(MotionState.MOVING, movingAt, "sustained activity flips the state to moving exactly once")
        assertEquals(MotionState.MOVING, classifier.state)
        repeat(4) {
            assertNull(classifier.onSample(3.0), "continued movement is not re-reported")
        }

        var stillAt: MotionState? = null
        repeat(8) {
            val t = classifier.onSample(0.1)
            if (t != null) stillAt = t
        }
        assertEquals(MotionState.STILL, stillAt, "sustained quiet flips the state back to still")
        assertEquals(MotionState.STILL, classifier.state)
    }

    @Test
    fun `step tracker baselines first reading emits at the threshold and survives a counter reset`() {
        val tracker = StepDeltaTracker(emitThreshold = 25)
        assertNull(tracker.onReading(1000), "the first reading is a baseline, not a delta")
        assertNull(tracker.onReading(1010), "ten steps stay below the emit threshold")
        assertEquals(30L, tracker.onReading(1030), "accumulated deltas emit once they reach the threshold")
        assertNull(tracker.onReading(1035), "the accumulator restarted after emitting")
        assertNull(tracker.onReading(12), "a lower reading is a counter reset and only re-baselines")
        assertEquals(30L, tracker.onReading(42), "deltas accumulate from the new baseline after a reset")
        assertNull(tracker.onReading(42), "an unchanged reading emits nothing")
    }
}
