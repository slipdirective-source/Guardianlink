package guardianlink.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import guardianlink.core.ConciergeInterface
import java.io.FileInputStream
import kotlin.concurrent.thread

/**
 * The Android execution layer's network substrate: a TUN interface whose
 * every packet is modeled as an Action and authorized through the
 * on-device NineGates before it is allowed toward egress.
 *
 * Packets that the gates reject are dropped and logged; packets that
 * execute go to the [EgressPlane] (demo: counted, not forwarded — a
 * userspace IP stack is integration scope).
 */
class GuardianVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null

    @Volatile
    private var running = false
    private var loopThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_STICKY
        promoteToForeground()
        try {
            tun = Builder()
                .setSession("GuardianLink")
                .addAddress("10.0.2.1", 24)
                .addDnsServer("8.8.8.8")
                .addRoute("0.0.0.0", 0)
                .setBlocking(true)
                .establish()
        } catch (t: Throwable) {
            Log.e(TAG, "TUN establish failed", t)
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        loopThread = thread(name = "gl-tun-loop", isDaemon = true) { packetLoop() }
        Log.i(TAG, "TUN up; governing packets")
        return START_STICKY
    }

    override fun onRevoke() {
        shutdown()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        running = false
        try { tun?.close() } catch (_: Exception) { }
        tun = null
        loopThread?.interrupt()
        loopThread = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.i(TAG, "TUN down")
    }

    private fun packetLoop() {
        val fd = tun?.fileDescriptor ?: return
        val input = FileInputStream(fd)
        val buf = ByteArray(32767)
        val app = application as GuardianApp
        while (running) {
            val n = try {
                input.read(buf)
            } catch (_: Exception) {
                if (running) Log.w(TAG, "TUN read error")
                break
            }
            if (n < IPV4_HEADER_LEN) continue
            // IPv4 header: version nibble 4, destination address bytes 16..19.
            if ((buf[0].toInt() shr 4) != 4) continue
            val dst = "${buf[16].toInt() and 0xFF}.${buf[17].toInt() and 0xFF}" +
                ".${buf[18].toInt() and 0xFF}.${buf[19].toInt() and 0xFF}"
            val packet = buf.copyOf(n)
            if (!app.isEngineReady()) {
                // Fail-closed: engine not up yet, packet cannot be
                // governed, so it is dropped.
                Log.w(TAG, "engine not initialized; packet dropped")
                continue
            }
            try {
                when (val result = app.dispatcher.submitTunPacket(dst, packet)) {
                    is ConciergeInterface.ExecutionResult.Executed ->
                        app.egress.send(packet)
                    is ConciergeInterface.ExecutionResult.Rejected ->
                        Log.w(TAG, "packet -> $dst rejected at gate ${result.atGate}: ${result.reason}")
                }
            } catch (t: Throwable) {
                // Fail-closed on the packet path: a dispatcher throw drops
                // the packet, never bypasses the gates.
                Log.e(TAG, "dispatcher threw; packet dropped", t)
            }
        }
    }

    private fun promoteToForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "GuardianLink tunnel", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("GuardianLink")
            .setContentText("Governed tunnel active")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .build()
        startForeground(NOTIF_ID, notification)
    }

    companion object {
        private const val TAG = "GuardianVpnService"
        private const val CHANNEL_ID = "gl-tunnel"
        private const val NOTIF_ID = 1
        private const val IPV4_HEADER_LEN = 20
    }
}
