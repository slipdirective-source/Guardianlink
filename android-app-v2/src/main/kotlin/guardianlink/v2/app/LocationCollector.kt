package guardianlink.v2.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.Executors

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

    /**
     * Compose a Maps-style place from a reverse-geocoded address's
     * parts, most specific first: street, then city/town (or the
     * township/neighborhood when no city is named), then county, then
     * state. Blank parts drop out and consecutive duplicates collapse
     * (a city that is its own county seat repeats itself in the raw
     * fields). Returns null when nothing usable was resolved — the
     * caller falls back to coordinates and says so.
     */
    fun formatPlaceName(
        street: String?,
        locality: String?,
        subLocality: String?,
        county: String?,
        state: String?,
    ): String? {
        val parts = listOfNotNull(
            street?.trim()?.takeIf { it.isNotEmpty() },
            (locality ?: subLocality)?.trim()?.takeIf { it.isNotEmpty() },
            county?.trim()?.takeIf { it.isNotEmpty() },
            state?.trim()?.takeIf { it.isNotEmpty() },
        )
        val deduped = parts.filterIndexed { i, p -> i == 0 || !p.equals(parts[i - 1], ignoreCase = true) }
        return if (deduped.isEmpty()) null else deduped.joinToString(", ")
    }

    /**
     * The claim admitted for a fix when a place was resolved: the
     * place leads (that's what a person means by "where"), the raw
     * fix follows in parentheses as the evidence underneath it. With
     * no place, the coordinates-only claim stands and the absence is
     * named rather than papered over.
     */
    fun formatPlaceClaim(
        place: String?,
        latitude: Double,
        longitude: Double,
        accuracyMeters: Double?,
        provider: String,
        lastKnown: Boolean,
    ): String {
        val fix = formatClaim(latitude, longitude, accuracyMeters, provider)
            .removePrefix("Location fix: ")
            .let { "fix $it" }
        val suffix = if (lastKnown) " (last known fix)" else ""
        return if (place.isNullOrBlank()) {
            "Location: place name unavailable ($fix)$suffix"
        } else {
            "You are near $place ($fix)$suffix"
        }
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
    private val placeResolver = PlaceResolver(context)

    /** Newest fix time already admitted; a slower geocode result for an older fix never regresses Position. */
    @Volatile
    private var lastDeliveredFixTime: Long = 0L

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
                admit(last, provider, lastKnown = true)
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
        admit(location, provider, lastKnown = false)
    }

    /**
     * Admit a fix, resolving its place first. Resolution is async (the
     * platform geocoder), so delivery checks the fix is still the
     * newest one seen — a slow answer about somewhere you've left
     * never overwrites where you are.
     */
    private fun admit(location: Location, provider: String, lastKnown: Boolean) {
        val acc = accuracyOf(location)
        val confidence = LocationLogic.confidenceForAccuracy(acc)
        placeResolver.resolve(location) { place ->
            val fixTime = location.time
            if (fixTime > 0L && fixTime < lastDeliveredFixTime) return@resolve
            if (fixTime > 0L) lastDeliveredFixTime = fixTime
            sink(
                "location",
                LocationLogic.formatPlaceClaim(place, location.latitude, location.longitude, acc, provider, lastKnown),
                confidence,
            )
        }
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


/**
 * Reverse geocoder over the framework [Geocoder] — no Play services,
 * same dependency posture as the collector itself.
 *
 * Turns a fix into the place a person would name: street, town or
 * township, county, state. Results are cached by proximity (a fix
 * within ~150 m of the last resolved one reuses its place; the
 * geocoder is a shared on-device service and can be slow). Every
 * failure path — no backend on the device, an error, an empty
 * result — delivers null, and the claim then stands on coordinates
 * with the absence named. A place is never invented.
 */
class PlaceResolver(context: Context) {

    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "gl-v2-geocode").apply { isDaemon = true }
    }

    private var cachedFix: Location? = null
    private var cachedPlace: String? = null

    /** Resolve [location]'s place and deliver it (or null) on an arbitrary thread. */
    fun resolve(location: Location, deliver: (String?) -> Unit) {
        val cached = cachedFix
        val place = cachedPlace
        if (cached != null && place != null && location.distanceTo(cached) <= CACHE_RADIUS_M) {
            deliver(place)
            return
        }
        if (!Geocoder.isPresent()) {
            deliver(null)
            return
        }
        val geocoder = Geocoder(appContext, Locale.US)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                geocoder.getFromLocation(
                    location.latitude,
                    location.longitude,
                    1,
                    object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            deliver(finish(location, addresses.firstOrNull()))
                        }

                        override fun onError(errorMessage: String?) {
                            Log.w(TAG, "reverse geocode failed: $errorMessage")
                            deliver(null)
                        }
                    },
                )
            } catch (e: Exception) {
                Log.w(TAG, "reverse geocode threw", e)
                deliver(null)
            }
        } else {
            executor.execute {
                val addresses = try {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(location.latitude, location.longitude, 1)
                } catch (e: Exception) {
                    Log.w(TAG, "reverse geocode failed", e)
                    null
                }
                deliver(finish(location, addresses?.firstOrNull()))
            }
        }
    }

    private fun finish(location: Location, address: Address?): String? {
        val place = address?.let {
            val street = when {
                it.thoroughfare != null ->
                    listOfNotNull(it.subThoroughfare, it.thoroughfare).joinToString(" ")
                it.featureName != null && it.featureName != it.locality -> it.featureName
                else -> null
            }
            LocationLogic.formatPlaceName(
                street = street,
                locality = it.locality,
                subLocality = it.subLocality,
                county = it.subAdminArea,
                state = it.adminArea,
            )
        }
        if (place != null) {
            cachedFix = Location(location)
            cachedPlace = place
        }
        return place
    }

    companion object {
        private const val TAG = "GlV2Geocode"
        private const val CACHE_RADIUS_M = 150f
    }
}
