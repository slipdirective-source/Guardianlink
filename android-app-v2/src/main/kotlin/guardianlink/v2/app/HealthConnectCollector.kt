package guardianlink.v2.app

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The health collector: Health Connect as the sensor bus.
 *
 * On Android, watches and health apps (Galaxy Watch via Samsung
 * Health, Fitbit, Google Fit) publish into Health Connect; this
 * collector reads what the sovereign granted there — steps, heart
 * rate, sleep, blood oxygen, weight — and admits summaries as
 * evidence (topic "health.*") through the governed boundary.
 *
 * Absence is named, never papered over: Health Connect missing,
 * permissions ungranted, or simply no data yet each produce an
 * honest unavailable/no-data path. Polls dedupe by claim text, so
 * an unchanged reading is admitted once, not every cycle.
 */
class HealthConnectCollector(
    private val context: Context,
    private val sink: (topic: String, claim: String) -> Unit,
    private val onUnavailable: (reason: String) -> Unit,
) {

    private var lastClaims: Set<String> = emptySet()

    fun pollOnce() {
        try {
            val status = HealthConnectClient.getSdkStatus(context)
            if (status != HealthConnectClient.SDK_AVAILABLE) {
                onUnavailable("Health Connect is not available on this phone")
                return
            }
            val client = HealthConnectClient.getOrCreate(context)
            runBlocking {
                val granted = client.permissionController.getGrantedPermissions()
                if (!granted.containsAll(PERMISSIONS)) {
                    onUnavailable("Health Connect permissions not granted — grant them in Settings → Permissions")
                    return@runBlocking
                }
                val claims = ArrayList<Pair<String, String>>()
                val zone = ZoneId.systemDefault()
                val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant()
                val now = Instant.now()

                // Steps today (aggregate across every source app/watch).
                val steps = client.aggregate(
                    AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(todayStart, now)),
                )[StepsRecord.COUNT_TOTAL]
                if (steps != null) claims += "health.steps" to "Steps today: $steps"

                // Latest heart-rate sample.
                val heart = client.readRecords(
                    ReadRecordsRequest(HeartRateRecord::class, TimeRangeFilter.between(todayStart, now), ascendingOrder = false, pageSize = 1),
                ).records.firstOrNull()?.samples?.lastOrNull()
                if (heart != null) {
                    claims += "health.heart" to "Heart rate: ${heart.beatsPerMinute} bpm (latest reading today)"
                }

                // Most recent sleep session in the last two days.
                val sleep = client.readRecords(
                    ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(now.minus(Duration.ofDays(2)), now), ascendingOrder = false, pageSize = 1),
                ).records.firstOrNull()
                if (sleep != null) {
                    val minutes = Duration.between(sleep.startTime, sleep.endTime).toMinutes()
                    claims += "health.sleep" to "Last sleep: ${minutes / 60}h ${minutes % 60}m"
                }

                // Latest blood oxygen.
                val spo2 = client.readRecords(
                    ReadRecordsRequest(OxygenSaturationRecord::class, TimeRangeFilter.between(now.minus(Duration.ofDays(2)), now), ascendingOrder = false, pageSize = 1),
                ).records.firstOrNull()
                if (spo2 != null) {
                    claims += "health.oxygen" to "Blood oxygen: ${spo2.percentage.value.toInt()}% (latest reading)"
                }

                // Latest weight.
                val weight = client.readRecords(
                    ReadRecordsRequest(WeightRecord::class, TimeRangeFilter.between(now.minus(Duration.ofDays(30)), now), ascendingOrder = false, pageSize = 1),
                ).records.firstOrNull()
                if (weight != null) {
                    val lb = weight.weight.inKilograms * 2.20462
                    claims += "health.weight" to "Weight: ${"%.1f".format(lb)} lb (latest reading)"
                }

                val texts = claims.map { it.second }.toSet()
                for ((topic, claim) in claims) {
                    if (claim !in lastClaims) sink(topic, claim)
                }
                lastClaims = texts
                if (claims.isEmpty()) {
                    Log.i(TAG, "health poll: granted, but no readings in range yet")
                }
            }
        } catch (refused: IllegalStateException) {
            // Collector paused/denied at the harness boundary.
            Log.w(TAG, "health evidence refused at the boundary: ${refused.message}")
        } catch (t: Throwable) {
            Log.w(TAG, "health poll failed", t)
            onUnavailable("Health Connect read failed: ${t.message?.take(80)}")
        }
    }

    companion object {
        private const val TAG = "GlV2Health"

        val PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(OxygenSaturationRecord::class),
            HealthPermission.getReadPermission(WeightRecord::class),
        )
    }
}
