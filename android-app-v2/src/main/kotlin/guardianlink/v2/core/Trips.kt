package guardianlink.v2.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Trip sessionization (his ask, 2026-10-25): from the raw fix
 * stream, carve trips and keep the numbers he'd ask for — top
 * speed, average speed, distance, duration.
 *
 * Pure math over fix tuples so it's testable off-device; the
 * Android layer feeds (lat, lon, gpsSpeed?, epochMs, accuracy?)
 * and receives a [TripSummary] when a trip closes. A summary is
 * admitted as derived evidence (topic trip.summary), so asking
 * "what was my top speed?" answers from the record.
 *
 * Honesty rules: fixes with poor accuracy (> 30 m) don't move
 * the numbers; a fix implying > 200 mph is a GPS jump and is
 * dropped, not celebrated as a top speed; top speed comes from
 * the fix's own Doppler speed when present (far steadier than
 * differentiating positions at a 15 s cadence). A trip needs
 * ≥ 150 m and ≥ 60 s to count — shuffling around the house is
 * not a trip. A trip closes after 8 minutes of stillness or a
 * 15-minute fix gap, and ends at the last moving fix.
 */
data class TripSummary(
    val startedAtMs: Long,
    val endedAtMs: Long,
    val distanceM: Double,
    val topSpeedMps: Double,
) {
    val durationMs: Long get() = endedAtMs - startedAtMs
    val avgSpeedMps: Double get() = if (durationMs > 0) distanceM / (durationMs / 1000.0) else 0.0

    fun claim(): String {
        val mins = (durationMs / 60_000).coerceAtLeast(1)
        return "Trip: ${round1(distanceM / MILE_M)} mi in $mins min. " +
            "Top speed ${mph(topSpeedMps)} mph; average ${mph(avgSpeedMps)} mph."
    }

    companion object {
        const val MILE_M = 1609.344
        fun mph(mps: Double): Int = (mps * 2.23694).toInt()
        fun round1(v: Double): String {
            val r = kotlin.math.round(v * 10) / 10.0
            return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
        }
    }
}

class TripTracker(private val onTripClosed: (TripSummary) -> Unit) {

    private var inTrip = false
    private var startMs = 0L
    private var lastLat = 0.0
    private var lastLon = 0.0
    private var lastFixMs = 0L
    private var lastMovingMs = 0L
    private var distanceM = 0.0
    private var topSpeedMps = 0.0
    private var stillSinceMs = 0L

    // Pending (pre-trip) movement: two moving fixes or 80 m of
    // displacement promote a shuffle into a trip.
    private var pendingCount = 0
    private var pendingLat = 0.0
    private var pendingLon = 0.0
    private var pendingStartMs = 0L

    fun onFix(lat: Double, lon: Double, gpsSpeedMps: Double?, epochMs: Long, accuracyM: Double?) {
        if (accuracyM != null && accuracyM > MAX_ACCURACY_M) return
        if (lastFixMs > 0 && epochMs - lastFixMs > GAP_CLOSE_MS && inTrip) {
            closeTrip(lastMovingMs)
        }
        if (!inTrip) {
            if (pendingCount == 0) {
                if (gpsSpeedMps != null && gpsSpeedMps < START_SPEED_MPS) return
                pendingCount = 1
                pendingLat = lat
                pendingLon = lon
                pendingStartMs = epochMs
                lastLat = lat; lastLon = lon; lastFixMs = epochMs
                return
            }
            val disp = haversine(pendingLat, pendingLon, lat, lon)
            val dtSec = (epochMs - pendingStartMs) / 1000.0
            val moving = (gpsSpeedMps != null && gpsSpeedMps >= START_SPEED_MPS) ||
                (dtSec > 0 && disp / dtSec >= START_SPEED_MPS)
            if (moving || disp >= PROMOTE_DISPLACEMENT_M) {
                inTrip = true
                startMs = pendingStartMs
                distanceM = disp
                topSpeedMps = gpsSpeedMps ?: 0.0
                lastMovingMs = epochMs
                stillSinceMs = 0L
                pendingCount = 0
            } else if (epochMs - pendingStartMs > PENDING_WINDOW_MS) {
                pendingCount = 0
            }
            lastLat = lat; lastLon = lon; lastFixMs = epochMs
            return
        }

        // In a trip.
        val dtSec = (epochMs - lastFixMs) / 1000.0
        val step = haversine(lastLat, lastLon, lat, lon)
        if (dtSec > 0 && step / dtSec > MAX_PLAUSIBLE_MPS) {
            // GPS jump: drop the fix entirely — don't move, don't count.
            return
        }
        val effectiveSpeed = gpsSpeedMps ?: if (dtSec > 0) step / dtSec else 0.0
        distanceM += step
        if (effectiveSpeed > topSpeedMps && effectiveSpeed <= MAX_PLAUSIBLE_MPS) {
            topSpeedMps = effectiveSpeed
        }
        if (effectiveSpeed >= STILL_SPEED_MPS) {
            lastMovingMs = epochMs
            stillSinceMs = 0L
        } else {
            if (stillSinceMs == 0L) stillSinceMs = epochMs
            if (epochMs - stillSinceMs >= STILL_CLOSE_MS) {
                closeTrip(lastMovingMs)
            }
        }
        lastLat = lat; lastLon = lon; lastFixMs = epochMs
    }

    private fun closeTrip(endMs: Long) {
        val summary = TripSummary(startMs, endMs, distanceM, topSpeedMps)
        inTrip = false
        pendingCount = 0
        distanceM = 0.0
        topSpeedMps = 0.0
        stillSinceMs = 0L
        if (summary.distanceM >= MIN_TRIP_DISTANCE_M && summary.durationMs >= MIN_TRIP_DURATION_MS) {
            onTripClosed(summary)
        }
    }

    /** Primitive snapshot so the app layer can persist an in-progress trip across restarts. */
    fun snapshot(): Map<String, String> = mapOf(
        "inTrip" to inTrip.toString(),
        "startMs" to startMs.toString(),
        "lastLat" to lastLat.toString(),
        "lastLon" to lastLon.toString(),
        "lastFixMs" to lastFixMs.toString(),
        "lastMovingMs" to lastMovingMs.toString(),
        "distanceM" to distanceM.toString(),
        "topSpeedMps" to topSpeedMps.toString(),
        "stillSinceMs" to stillSinceMs.toString(),
        "pendingCount" to pendingCount.toString(),
        "pendingLat" to pendingLat.toString(),
        "pendingLon" to pendingLon.toString(),
        "pendingStartMs" to pendingStartMs.toString(),
    )

    fun restore(s: Map<String, String>) {
        inTrip = s["inTrip"]?.toBoolean() ?: false
        startMs = s["startMs"]?.toLongOrNull() ?: 0L
        lastLat = s["lastLat"]?.toDoubleOrNull() ?: 0.0
        lastLon = s["lastLon"]?.toDoubleOrNull() ?: 0.0
        lastFixMs = s["lastFixMs"]?.toLongOrNull() ?: 0L
        lastMovingMs = s["lastMovingMs"]?.toLongOrNull() ?: 0L
        distanceM = s["distanceM"]?.toDoubleOrNull() ?: 0.0
        topSpeedMps = s["topSpeedMps"]?.toDoubleOrNull() ?: 0.0
        stillSinceMs = s["stillSinceMs"]?.toLongOrNull() ?: 0L
        pendingCount = s["pendingCount"]?.toIntOrNull() ?: 0
        pendingLat = s["pendingLat"]?.toDoubleOrNull() ?: 0.0
        pendingLon = s["pendingLon"]?.toDoubleOrNull() ?: 0.0
        pendingStartMs = s["pendingStartMs"]?.toLongOrNull() ?: 0L
    }

    companion object {
        const val MAX_ACCURACY_M = 30.0
        const val START_SPEED_MPS = 1.8 // ~4 mph
        const val STILL_SPEED_MPS = 0.9 // ~2 mph
        const val MAX_PLAUSIBLE_MPS = 90.0 // ~200 mph
        const val PROMOTE_DISPLACEMENT_M = 80.0
        const val PENDING_WINDOW_MS = 180_000L
        const val STILL_CLOSE_MS = 480_000L // 8 min
        const val GAP_CLOSE_MS = 900_000L // 15 min
        const val MIN_TRIP_DISTANCE_M = 150.0
        const val MIN_TRIP_DURATION_MS = 60_000L

        fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
            return 2 * r * asin(sqrt(a))
        }
    }
}
