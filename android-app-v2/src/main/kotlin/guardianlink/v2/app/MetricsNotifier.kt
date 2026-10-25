package guardianlink.v2.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import guardianlink.v2.core.Delta

/**
 * The metrics checkup, delivered the way the sovereign reads his own
 * checkups: a notification of bullet points.
 *
 * Each standing answer becomes one line — question, current answer,
 * and how settled it is, in plain words. Posted while sensing runs
 * whenever the answers actually change (throttled, so a busy phone
 * doesn't buzz itself to death), and on demand from Settings. The
 * notification is a rendering of the same standing answers the Now
 * card shows — never a second, divergent account.
 */
object MetricsNotifier {

    private const val CHANNEL_ID = "gl-checkup"
    private const val NOTIF_ID = 4201

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "GuardianLink checkup", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Bullet-point summary of your standing answers as they change."
            },
        )
    }

    /** Bullet lines for the current standing answers. Pure rendering of the snapshot. */
    fun lines(app: GuardianLinkV2App): List<String> {
        val answers = synchronized(app.coreLock) { app.core.snapshot() }
        return answers.map { q ->
            "• ${q.text} — ${q.current.text} (${plainDelta(q.current.delta)})"
        }
    }

    fun post(context: Context, app: GuardianLinkV2App) {
        ensureChannel(context)
        val lines = lines(app)
        if (lines.isEmpty()) return
        val style = NotificationCompat.InboxStyle()
        for (line in lines.take(6)) style.addLine(line)
        if (lines.size > 6) style.addLine("• …and ${lines.size - 6} more in the app")
        style.setBigContentTitle("GuardianLink checkup")
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("GuardianLink checkup")
            .setContentText(lines.first().removePrefix("• "))
            .setStyle(style)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID, notification)
        } catch (e: SecurityException) {
            // Notifications not granted: the checkup exists in the app;
            // a missing grant is a respected answer, not an error.
        }
    }

    private fun plainDelta(delta: Delta): String = when (delta) {
        Delta.DISTINGUISHED -> "settled"
        Delta.EQUIVALENT -> "confirmed"
        Delta.UNKNOWN -> "not settled yet"
        Delta.INAPPLICABLE -> "doesn't apply"
    }
}
