package guardianlink.v2.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import guardianlink.v2.core.AdmitInput
import guardianlink.v2.core.CollectorStatus
import guardianlink.v2.core.EventKind

/**
 * Foreground sensing service (spec §3.1, PORT-MAP §d).
 *
 * Runs three collectors: device state (working since the first cut),
 * location ([LocationManager]-based, framework APIs only), and motion
 * ([SensorManager]-based). The service is declared `specialUse` +
 * `location` + `health` in the manifest; the typed startForeground call
 * passes exactly the subset of types whose prerequisites are met right
 * now (§d's subset rule): specialUse always, location only with a
 * location grant, health only with the activity-recognition grant.
 *
 * The service owns no state of its own: collector events are admitted
 * into the v0.2 core under GuardianLinkV2App.coreLock. Location and
 * device-state events go through `admitSensor(...)`; motion events are
 * DERIVED (classifications and step deltas), so they enter as
 * derived-inference events through an explicit assertCollecting gate —
 * in both cases collector governance (pause/deny) applies at the
 * harness boundary, and a refusal drops the event. It is never
 * rerouted around the gate. A collector that cannot start (permission
 * absent, no provider/sensor) names itself unavailable at that same
 * boundary via [GuardianLink.denyCollector], so the standing answers
 * degrade honestly instead of presenting fabricated completeness.
 */
class SensingService : Service() {

    private var deviceStateCollector: DeviceStateCollector? = null
    private var locationCollector: LocationCollector? = null
    private var motionCollector: MotionCollector? = null

    @Volatile
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSensing()
            return START_NOT_STICKY
        }
        if (running) return START_STICKY
        promoteToForeground()
        val app = application as GuardianLinkV2App

        val deviceState = DeviceStateCollector(this) { topic, claim ->
            admitObservation(app, "device_state", topic, claim, confidence = 0.95)
        }
        deviceStateCollector = deviceState
        deviceState.start()

        val location = LocationCollector(
            context = this,
            sink = { topic, claim, confidence -> admitObservation(app, "location", topic, claim, confidence) },
            onUnavailable = { reason -> markUnavailable(app, "location", reason) },
        )
        locationCollector = location
        location.start()

        val motion = MotionCollector(
            context = this,
            sink = { topic, claim, confidence -> admitDerived(app, topic, claim, confidence) },
            onUnavailable = { reason -> markUnavailable(app, "motion", reason) },
        )
        motionCollector = motion
        motion.start()

        running = true
        setWasRunning(this, true)
        Log.i(TAG, "sensing up (device state, location, motion — each as permitted)")
        return START_STICKY
    }

    override fun onDestroy() {
        stopSensing()
        super.onDestroy()
    }

    private fun stopSensing() {
        deviceStateCollector?.stop()
        deviceStateCollector = null
        locationCollector?.stop()
        locationCollector = null
        motionCollector?.stop()
        motionCollector = null
        if (running) {
            running = false
            setWasRunning(this, false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            Log.i(TAG, "sensing down")
        }
        stopSelf()
    }

    /** Observation path: the collector's evidence enters through the governed admitSensor gate. */
    private fun admitObservation(app: GuardianLinkV2App, collector: String, topic: String, claim: String, confidence: Double) {
        try {
            synchronized(app.coreLock) {
                app.core.admitSensor(collector, topic, claim, confidence = confidence)
            }
            maybePostCheckup(app)
        } catch (refused: IllegalStateException) {
            // The harness boundary refused this evidence (collector
            // paused/denied). Drop the event; never bypass.
            Log.w(TAG, "evidence refused at harness boundary: ${refused.message}")
        }
    }

    @Volatile
    private var lastCheckupAtMs: Long = 0L

    @Volatile
    private var lastCheckupSignature: String = ""

    /**
     * The metrics checkup as a notification: whenever sensing changes
     * a standing answer, the sovereign gets the bullet-point summary —
     * throttled to at most one a minute and only on an actual change,
     * so the phone reports movement, not noise.
     */
    private fun maybePostCheckup(app: GuardianLinkV2App) {
        val now = System.currentTimeMillis()
        if (now - lastCheckupAtMs < CHECKUP_MIN_INTERVAL_MS) return
        val signature = synchronized(app.coreLock) {
            app.core.snapshot().joinToString("|") { "${it.id}:${it.current.version}" }
        }
        if (signature == lastCheckupSignature) return
        lastCheckupSignature = signature
        lastCheckupAtMs = now
        MetricsNotifier.post(this, app)
        app.persistCore()
    }

    /**
     * Derived-inference path (motion): same boundary discipline as
     * [admitObservation], but the event kind tells the truth about what
     * a classification is — DERIVED_INFERENCE from source sensor:motion,
     * never a raw sensor observation.
     */
    private fun admitDerived(app: GuardianLinkV2App, topic: String, claim: String, confidence: Double) {
        try {
            synchronized(app.coreLock) {
                app.core.collectors.assertCollecting("motion")
                app.core.admit(
                    AdmitInput(
                        kind = EventKind.DERIVED_INFERENCE,
                        topic = topic,
                        claim = claim,
                        source = "sensor:motion",
                        confidence = confidence,
                    ),
                )
            }
            maybePostCheckup(app)
        } catch (refused: IllegalStateException) {
            Log.w(TAG, "derived evidence refused at harness boundary: ${refused.message}")
        }
    }

    /**
     * Name a collector unavailable in the harness (transition-only: if
     * it is already DENIED, the fact is already in the ledger — do not
     * mint a duplicate marker on every service start).
     */
    private fun markUnavailable(app: GuardianLinkV2App, collector: String, reason: String) {
        try {
            synchronized(app.coreLock) {
                if (app.core.collectors.get(collector)?.status != CollectorStatus.DENIED) {
                    app.core.denyCollector(collector, reason)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "could not mark collector $collector unavailable", t)
        }
        Log.w(TAG, "collector $collector unavailable: $reason")
    }

    private fun promoteToForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "GuardianLink sensing", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("GuardianLink v0.2")
            .setContentText("Sensing active: device state, location, motion (each as permitted). Collection is visible and revocable in the app.")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .build()
        // startForeground MUST carry types that are a subset of the
        // manifest-declared types (§d), and each type's prerequisites
        // must hold: location needs a location grant, health needs the
        // activity-recognition grant. specialUse is always available.
        // The typed overload exists from API 29, but the SPECIAL_USE
        // constant only from API 34 — below 34, the two-arg form.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
                hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
            ) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            if (hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
            }
            startForeground(NOTIF_ID, notification, types)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "GlV2Sensing"
        private const val CHANNEL_ID = "gl-v2-sensing"
        private const val NOTIF_ID = 2
        private const val CHECKUP_MIN_INTERVAL_MS = 60_000L
        const val ACTION_STOP = "guardianlink.v2.app.action.STOP_SENSING"

        private const val PREFS = "gl-v2-sensing"
        private const val KEY_WAS_RUNNING = "was_running"

        /** Persistent "sensing was on" flag so BootReceiver can restart after reboot. */
        fun setWasRunning(context: Context, value: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_WAS_RUNNING, value)
                .apply()
        }

        fun wasRunning(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_WAS_RUNNING, false)

        fun startIntent(context: Context): Intent = Intent(context, SensingService::class.java)

        fun stopIntent(context: Context): Intent =
            Intent(context, SensingService::class.java).setAction(ACTION_STOP)
    }
}

/**
 * Restarts sensing after reboot when — and only when — it was running
 * before the reboot. BOOT_COMPLETED is one of the enumerated FGS
 * background-start exemptions (PORT-MAP §d); restarting unsensing the
 * user had stopped would violate the collection rails, so the persisted
 * flag is the authority here. Note the while-in-use consequence (§d):
 * after a reboot the location collector can only run if its grant
 * posture permits a boot-started location FGS (background location or
 * the specialUse cover); when it cannot, the collector reports itself
 * unavailable in the standing answers rather than failing the service.
 */
class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!SensingService.wasRunning(context)) return
        context.startForegroundService(SensingService.startIntent(context))
        Log.i("GlV2Sensing", "sensing restarted after boot (was running)")
    }
}
