package guardianlink.v2.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Pure decision logic for the location collector — no `android.*`
 * types, so the JVM unit tests cover it directly. The collector below
 * is a thin platform shell over these functions.
 */
object LocationLogic {

    /** Provider names are the platform's own strings (LocationManager.GPS_PROVIDER == "gps"). */
    const val PROVIDER_GPS = "gps"
    const val PROVIDER_NETWORK = "network"

    /**
     * Confidence from a fix's reported accuracy radius. A fix that
     * claims no accuracy earns the floor, not the benefit of the doubt.
     */
    fun confidenceForAccuracy(accuracyMeters: Double?): Double = when {
        accuracyMeters == null || accuracyMeters <= 0.0 -> 0.5
        accuracyMeters <= 10.0 -> 0.95
        accuracyMeters <= 30.0 -> 0.9
        accuracyMeters <= 100.0 -> 0.8
        accuracyMeters <= 500.0 -> 0.65
        else -> 0.5
    }

    /**
     * Provider choice: GPS when fine location is granted and the GPS
     * provider is enabled; network when any location permission is
     * granted and the network provider is enabled; null when no lawful
     * provider exists (permission absent or all providers off).
     */
    fun chooseProvider(
        hasFinePermission: Boolean,
        hasCoarsePermission: Boolean,
        gpsEnabled: Boolean,
        networkEnabled: Boolean,
    ): String? = when {
        hasFinePermission && gpsEnabled -> PROVIDER_GPS
        (hasFinePermission || hasCoarsePermission) && networkEnabled -> PROVIDER_NETWORK
        hasFinePermission && networkEnabled -> PROVIDER_NETWORK
        else -> null
    }

    /** The claim text admitted for a fix. Coordinates at 5 decimals (~1 m) — fix-grade, not survey-grade. */
    fun formatClaim(latitude: Double, longitude: Double, accuracyMeters: Double?, provider: String): String {
        val acc = if (accuracyMeters != null && accuracyMeters > 0.0) {
            String.format(Locale.US, "accuracy ±%dm", accuracyMeters.toInt())
        } else {
            "accuracy unknown"
        }
        return String.format(Locale.US, "Location fix: %.5f, %.5f (%s, %s)", latitude, longitude, provider, acc)
    }
}

/**
 * Location collector (framework APIs only — PORT-MAP §f risk 5).
 *
 * Uses [LocationManager], NOT Google Play services' FusedLocation:
 * no GMS dependency is added, the build stays dependency-free beyond
 * androidx, and the collector works on devices without Play services.
 * The cost is real and recorded in the module README: framework
 * location is less power-optimized and can be slower to first fix than
 * Fused. Accuracy is taken from each fix's own reported radius —
 * never invented.
 *
 * Events go through [sink], which SensingService wires to
 * `core.admitSensor("location", ...)` — the same governed path the
 * device-state collector uses, so a sovereign pause/deny stops this
 * collector's evidence at the harness boundary. Permission-absent is
 * reported through [onUnavailable] so the harness names the missing
 * capability in the standing answers (the honest-degradation contract
 * the earlier stub implemented by hand).
 */
class LocationCollector(
    private val context: Context,
    private val sink: (topic: String, claim: String, confidence: Double) -> Unit,
    private val onUnavailable: (reason: String) -> Unit,
) {
    private var locationManager: LocationManager? = null
    private var activeProvider: String? = null
    private var started = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            emit(location, activeProvider ?: LocationLogic.PROVIDER_GPS)
        }

        override fun onProviderDisabled(provider: String) {
            Log.w(TAG, "location provider $provider disabled while collecting")
        }

        override fun onProviderEnabled(provider: String) {
            Log.i(TAG, "location provider $provider enabled")
        }
    }

    /** True when the collector is live. False means [onUnavailable] was told why. */
    fun start(): Boolean {
        if (started) return true
        val hasFine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) {
            onUnavailable("location permission not granted")
            return false
        }
        val lm = context.getSystemService(LocationManager::class.java)
        val provider = LocationLogic.chooseProvider(
            hasFinePermission = hasFine,
            hasCoarsePermission = hasCoarse,
            gpsEnabled = isEnabled(lm, LocationManager.GPS_PROVIDER),
            networkEnabled = isEnabled(lm, LocationManager.NETWORK_PROVIDER),
        )
        if (provider == null) {
            onUnavailable("no location provider is enabled on the device")
            return false
        }
        locationManager = lm
        activeProvider = provider
        try {
            lm.requestLocationUpdates(provider, MIN_TIME_MS, MIN_DISTANCE_M, listener, Looper.getMainLooper())
            // A last-known fix is real evidence with a real (if older)
            // timestamp; say so in the claim rather than dressing it up
            // as a fresh fix.
            lm.getLastKnownLocation(provider)?.let { last ->
                sink(
                    "location",
                    LocationLogic.formatClaim(last.latitude, last.longitude, accuracyOf(last), provider) +
                        " (last known fix)",
                    LocationLogic.confidenceForAccuracy(accuracyOf(last)),
                )
            }
        } catch (e: SecurityException) {
            // Permission was revoked between the check and registration.
            onUnavailable("location permission not granted")
            return false
        }
        started = true
        Log.i(TAG, "location collector started (provider=$provider)")
        return true
    }

    fun stop() {
        if (!started) return
        started = false
        try {
            locationManager?.removeUpdates(listener)
        } catch (e: Exception) {
            Log.w(TAG, "removeUpdates failed", e)
        }
        locationManager = null
        activeProvider = null
        Log.i(TAG, "location collector stopped")
    }

    private fun emit(location: Location, provider: String) {
        val acc = accuracyOf(location)
        sink(
            "location",
            LocationLogic.formatClaim(location.latitude, location.longitude, acc, provider),
            LocationLogic.confidenceForAccuracy(acc),
        )
    }

    private fun accuracyOf(location: Location): Double? =
        if (location.hasAccuracy()) location.accuracy.toDouble() else null

    private fun isEnabled(lm: LocationManager, provider: String): Boolean =
        try {
            lm.isProviderEnabled(provider)
        } catch (e: Exception) {
            false
        }

    companion object {
        private const val TAG = "GlV2Location"
        private const val MIN_TIME_MS = 15_000L
        private const val MIN_DISTANCE_M = 10f
    }
}
