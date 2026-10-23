package guardianlink.v2.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Foreground sensing service (spec §3.1, PORT-MAP §d).
 *
 * This cut runs the device-state collector only. The service is typed
 * `specialUse` (the manifest declaration and its justification property
 * are the platform contract for a sensing FGS with no dedicated type);
 * when the location/motion collectors land, their types join the
 * manifest declaration and the startForeground call below extends to
 * exactly the types whose prerequisites are met.
 *
 * The service owns no state of its own: collector events are admitted
 * into the v0.2 core under GuardianLinkV2App.coreLock, through
 * `admitSensor("device_state", ...)`, so collector governance
 * (pause/deny) applies at the harness boundary. If that boundary
 * refuses (collector paused/denied by the sovereign), the refusal is
 * logged and the event is dropped — never rerouted around the gate.
 */
class SensingService : Service() {

    private var collector: DeviceStateCollector? = null

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
        val c = DeviceStateCollector(this) { topic, claim ->
            try {
                synchronized(app.coreLock) {
                    app.core.admitSensor("device_state", topic, claim, confidence = 0.95)
                }
            } catch (refused: IllegalStateException) {
                // The harness boundary refused this evidence (collector
                // paused/denied). Drop the event; never bypass.
                Log.w(TAG, "evidence refused at harness boundary: ${refused.message}")
            }
        }
        collector = c
        c.start()
        running = true
        setWasRunning(this, true)
        Log.i(TAG, "sensing up (device-state collector)")
        return START_STICKY
    }

    override fun onDestroy() {
        stopSensing()
        super.onDestroy()
    }

    private fun stopSensing() {
        collector?.stop()
        collector = null
        if (running) {
            running = false
            setWasRunning(this, false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            Log.i(TAG, "sensing down")
        }
        stopSelf()
    }

    private fun promoteToForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "GuardianLink sensing", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("GuardianLink v0.2")
            .setContentText("Sensing active: device state. Collection is visible and revocable in the app.")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .build()
        // startForeground MUST carry a type that is a subset of the
        // manifest-declared types (§d). The typed overload and the
        // SPECIAL_USE constant exist from API 34 / API 29 respectively;
        // below that, the two-arg form is the only option.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        private const val TAG = "GlV2Sensing"
        private const val CHANNEL_ID = "gl-v2-sensing"
        private const val NOTIF_ID = 2
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
 * flag is the authority here.
 */
class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!SensingService.wasRunning(context)) return
        context.startForegroundService(SensingService.startIntent(context))
        Log.i("GlV2Sensing", "sensing restarted after boot (was running)")
    }
}
