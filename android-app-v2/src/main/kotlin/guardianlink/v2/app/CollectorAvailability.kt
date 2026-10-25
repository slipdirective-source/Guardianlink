package guardianlink.v2.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import guardianlink.v2.core.CollectorStatus

/**
 * Permission-to-collector availability reconciliation.
 *
 * The registry's DENIED state is how the standing answers name a
 * missing capability (spec §4/§6.11). This helper keeps that state in
 * step with the OS grants, acting ONLY on transitions — a cold start
 * with a permission already denied must not mint a fresh "permission
 * denied" event every time:
 *
 *  - grant absent and collector not already DENIED → deny (named,
 *    with the reason, in the ledger);
 *  - grant present and collector DENIED → resume (the denial was a
 *    permission state, and the permission state changed);
 *  - anything else → no-op. A sovereign PAUSE is never overridden
 *    here: pause is a rail decision, not a permission fact.
 *
 * Called from GuardianLinkV2App.onCreate (after the state restore) and
 * from MainActivity whenever a permission result arrives. All core
 * access is serialized on the app lock by the caller contract — this
 * helper takes the lock itself so call sites cannot forget.
 */
object CollectorAvailability {

    fun refresh(app: GuardianLinkV2App) {
        val locationGranted = has(app, Manifest.permission.ACCESS_FINE_LOCATION) ||
            has(app, Manifest.permission.ACCESS_COARSE_LOCATION)
        val motionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            has(app, Manifest.permission.ACTIVITY_RECOGNITION)
        val notificationsGranted = androidx.core.app.NotificationManagerCompat
            .getEnabledListenerPackages(app).contains(app.packageName)
        val calendarGranted = has(app, Manifest.permission.READ_CALENDAR)
        val emailConfigured = EmailAccountStore(app).load() != null
        synchronized(app.coreLock) {
            reconcile(app, "location", locationGranted, "location permission not granted")
            reconcile(app, "motion", motionGranted, "activity recognition permission not granted")
            reconcile(app, "notifications", notificationsGranted, "notification access not granted")
            reconcile(app, "calendar", calendarGranted, "calendar permission not granted")
            reconcile(app, "email", emailConfigured, "No mailbox connected — add one in Settings → Connected email")
            // Finances ride the same notification grant (bank and
            // payment alerts) — same availability fact, own collector.
            reconcile(app, "finances", notificationsGranted, "notification access not granted")
        }
    }

    private fun reconcile(app: GuardianLinkV2App, collector: String, granted: Boolean, deniedReason: String) {
        val current = app.core.collectors.get(collector) ?: return
        when {
            !granted && current.status != CollectorStatus.DENIED ->
                app.core.denyCollector(collector, deniedReason)
            granted && current.status == CollectorStatus.DENIED ->
                app.core.collectors.resume(collector)
            else -> Unit // ACTIVE stays, PAUSED (sovereign) is never touched
        }
    }

    private fun has(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
