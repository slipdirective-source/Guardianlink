package guardianlink.v2.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Trip sessionization: a synthetic drive becomes a trip with a
 * top speed, an average, and a distance — and asking about it
 * answers from the admitted record.
 */
class TripTest {

    private fun drive(tracker: TripTracker, startMs: Long) {
        // ~10 minutes due north at ~60 mph worth of fixes every
        // 30 s: each step is 0.0072 deg of latitude (~800 m).
        var t = startMs
        var lat = 38.0
        tracker.onFix(lat, 85.0, 26.8, t, 8.0)
        repeat(20) {
            t += 30_000
            lat += 0.0072
            tracker.onFix(lat, 85.0, 26.8, t, 8.0)
        }
        // Park: 9 minutes of still fixes close the trip.
        repeat(18) {
            t += 30_000
            tracker.onFix(lat, 85.0, 0.0, t, 8.0)
        }
    }

    @Test
    fun `a drive closes with sane top and average speeds`() {
        var summary: TripSummary? = null
        val tracker = TripTracker { summary = it }
        drive(tracker, 1_000_000L)
        val s = summary ?: error("trip never closed")
        // 20 steps * ~800 m ≈ 9.9 mi in 10 minutes of motion.
        assertTrue(s.distanceM in 14_000.0..18_500.0, "distance ${s.distanceM}")
        assertEquals(59, TripSummary.mph(s.topSpeedMps)) // 26.8 m/s
        assertTrue(TripSummary.mph(s.avgSpeedMps) in 50..62, "avg ${TripSummary.mph(s.avgSpeedMps)}")
        assertTrue(s.claim().contains("Top speed 59 mph"), s.claim())
    }

    @Test
    fun `shuffling around the house is not a trip`() {
        var closed = 0
        val tracker = TripTracker { closed++ }
        var t = 2_000_000L
        repeat(30) {
            t += 30_000
            tracker.onFix(38.0 + (it % 2) * 0.0001, 85.0, 0.3, t, 6.0)
        }
        assertEquals(0, closed)
    }

    @Test
    fun `asking about top speed answers from the record`() {
        val h = GuardianLink()
        h.admit(
            AdmitInput(
                kind = EventKind.DERIVED_INFERENCE,
                topic = "trip.summary",
                claim = "Trip: 9.9 mi in 10 min. Top speed 59 mph; average 55 mph.",
                source = "trip_tracker",
                confidence = 0.9,
            ),
        )
        val answer = h.say("what was my top speed on my last trip?")
        assertTrue(answer.reply.contains("Top speed 59 mph"), answer.reply)
        val none = GuardianLink().say("what was my top speed?")
        assertTrue(none.reply.contains("No completed trip on record yet"), none.reply)
    }
}
