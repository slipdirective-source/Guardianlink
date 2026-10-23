package guardianlink.v2.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.util.Log

/**
 * The one WORKING collector in this cut: device state.
 *
 * Emits normalized events (topic, claim) through [sink] for:
 *  - screen on / off / user-present (dynamic receivers — ACTION_SCREEN_ON
 *    and ACTION_SCREEN_OFF cannot be manifest-declared),
 *  - battery level, charging state, power connect/disconnect (the
 *    sticky ACTION_BATTERY_CHANGED gives the current state at start),
 *  - default-network availability and transport (wifi/cellular/other)
 *    via a ConnectivityManager network callback.
 *
 * No dangerous permissions are needed for any of this (PORT-MAP §d).
 * The collector knows nothing about the ledger: [sink] is wired by
 * SensingService to `core.admitSensor("device_state", ...)`, so a
 * sovereign pause/deny of the device_state collector stops this
 * collector's evidence at the harness boundary.
 */
class DeviceStateCollector(
    private val context: Context,
    private val sink: (topic: String, claim: String) -> Unit,
) {
    private var started = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> sink("state.device", "Screen on")
                Intent.ACTION_SCREEN_OFF -> sink("state.device", "Screen off")
                Intent.ACTION_USER_PRESENT -> sink("state.device", "Device unlocked (user present)")
            }
        }
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> sink("state.battery", "Charging started (power connected)")
                Intent.ACTION_POWER_DISCONNECTED -> sink("state.battery", "Charging stopped (power disconnected)")
            }
        }
    }

    fun start() {
        if (started) return
        started = true

        registerReceiverCompat(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
        )
        registerReceiverCompat(
            powerReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            },
        )

        // Current battery state from the sticky broadcast. A null
        // receiver registers nothing — it only reads the sticky intent —
        // so the plain two-arg form is correct here on every API level.
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (sticky != null) emitBattery(sticky)

        // Network state, live.
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = cm.getNetworkCapabilities(network)
                sink("state.network", "Network available (${transportOf(caps)})")
            }

            override fun onLost(network: Network) {
                sink("state.network", "Network lost")
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                sink("state.network", "Network changed (${transportOf(caps)})")
            }
        }
        networkCallback = callback
        try {
            cm.registerDefaultNetworkCallback(callback)
        } catch (t: Throwable) {
            // Honest degradation: no network events, and the failure is
            // logged rather than papered over with invented state.
            Log.w(TAG, "network callback registration failed; network events unavailable", t)
        }
        Log.i(TAG, "device-state collector started")
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { context.unregisterReceiver(screenReceiver) }
        runCatching { context.unregisterReceiver(powerReceiver) }
        val cm = context.getSystemService(ConnectivityManager::class.java)
        networkCallback?.let { cb -> runCatching { cm.unregisterNetworkCallback(cb) } }
        networkCallback = null
        Log.i(TAG, "device-state collector stopped")
    }

    private fun emitBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
        val claim = if (pct >= 0) {
            "Battery at $pct% (${if (charging) "charging" else "not charging"})"
        } else {
            "Battery state observed (level unavailable)"
        }
        sink("state.battery", claim)
    }

    private fun transportOf(caps: NetworkCapabilities?): String = when {
        caps == null -> "unknown transport"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        else -> "other transport"
    }

    /**
     * registerReceiver with the API 33+ export flag. The broadcasts used
     * here are system broadcasts (exempt from the flag requirement), but
     * NOT_EXPORTED is correct regardless: nothing outside the system
     * should be able to feed this collector.
     */
    private fun registerReceiverCompat(receiver: BroadcastReceiver?, filter: IntentFilter): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    companion object {
        private const val TAG = "GlV2DeviceState"
    }
}
