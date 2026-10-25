package guardianlink.v2.app

import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The calendar collector: what the phone's calendar already knows.
 *
 * Polls [CalendarContract.Instances] for the window from an hour
 * ago to a week out and admits events as evidence (topic
 * "calendar.event") plus a single "what's next" claim (topic
 * "calendar") whenever the next event changes. Admission goes
 * through the governed admitSensor boundary under the "calendar"
 * collector; without READ_CALENDAR the collector names itself
 * unavailable instead of guessing.
 *
 * Deduplication is by instance key (event id + begin) in memory,
 * with the last "next" claim persisted so a restart does not
 * re-announce the same appointment.
 */
class CalendarCollector(
    private val context: Context,
    private val sink: (topic: String, claim: String) -> Unit,
    private val onUnavailable: (reason: String) -> Unit,
) {

    private val admitted = HashSet<String>()

    fun pollOnce() {
        val now = System.currentTimeMillis()
        val begin = now - 3_600_000L
        val end = now + 7 * 24 * 3_600_000L
        val events = query(begin, end)
        if (events == null) {
            onUnavailable("calendar permission not granted")
            return
        }
        var admittedNow = 0
        for (event in events) {
            val key = "${event.id}@${event.begin}"
            if (key in admitted) continue
            admitted += key
            sink("calendar.event", claimFor(event))
            admittedNow++
            if (admittedNow >= 25) break
        }
        val next = events.firstOrNull { it.begin >= now - 900_000L }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val nextClaim = next?.let { "Next on your calendar: ${claimBody(it)}" }
            ?: "Nothing on your calendar for the next week."
        if (prefs.getString(KEY_LAST_NEXT, null) != nextClaim) {
            sink("calendar", nextClaim)
            prefs.edit().putString(KEY_LAST_NEXT, nextClaim).apply()
        }
    }

    private data class CalEvent(
        val id: Long,
        val title: String,
        val begin: Long,
        val end: Long,
        val location: String?,
        val allDay: Boolean,
    )

    private fun query(begin: Long, end: Long): List<CalEvent>? {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { b ->
            ContentUris.appendId(b, begin)
            ContentUris.appendId(b, end)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.ALL_DAY,
        )
        return try {
            context.contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")
                ?.use { cursor ->
                    val out = ArrayList<CalEvent>()
                    while (cursor.moveToNext()) {
                        out += CalEvent(
                            id = cursor.getLong(0),
                            title = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: "(untitled event)",
                            begin = cursor.getLong(2),
                            end = cursor.getLong(3),
                            location = cursor.getString(4)?.takeIf { it.isNotBlank() },
                            allDay = cursor.getInt(5) != 0,
                        )
                    }
                    out
                } ?: emptyList()
        } catch (security: SecurityException) {
            null
        }
    }

    private fun claimFor(event: CalEvent): String = "Calendar: ${claimBody(event)}"

    private fun claimBody(event: CalEvent): String {
        val whenText = if (event.allDay) {
            SimpleDateFormat("EEE MMM d", Locale.US).format(Date(event.begin)) + " (all day)"
        } else {
            SimpleDateFormat("EEE MMM d, h:mm a", Locale.US).format(Date(event.begin))
        }
        return buildString {
            append("'").append(event.title).append("' — ").append(whenText)
            event.location?.let { append(" at ").append(it) }
        }
    }

    companion object {
        private const val PREFS = "gl-calendar"
        private const val KEY_LAST_NEXT = "last_next_claim"
    }
}
