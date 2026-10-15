package guardianlink.android

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import guardianlink.core.ConciergeInterface
import guardianlink.gates.Action
import kotlin.concurrent.thread

/**
 * Scaffold launcher: VPN permission, tunnel start/stop, voice
 * enrollment entry, and a demo governed action through the dispatcher.
 *
 * UI is programmatic (no XML layouts) to keep the scaffold small.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) startTunnel()
            else status.text = "VPN permission denied."
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { text = "GuardianLink scaffold" }

        val startButton = Button(this).apply {
            text = "Start governed tunnel"
            setOnClickListener { requestVpn() }
        }
        val stopButton = Button(this).apply {
            text = "Stop tunnel"
            setOnClickListener {
                stopService(Intent(this@MainActivity, GuardianVpnService::class.java))
                status.text = "Tunnel stopped."
            }
        }
        val enrollButton = Button(this).apply {
            text = "Enroll voice"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, VoiceEnrollmentActivity::class.java))
            }
        }
        val demoButton = Button(this).apply {
            text = "Run demo governed action"
            setOnClickListener { runDemoAction() }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(startButton)
            addView(stopButton)
            addView(enrollButton)
            addView(demoButton)
        })
    }

    private fun requestVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermission.launch(intent) else startTunnel()
    }

    private fun startTunnel() {
        startForegroundService(Intent(this, GuardianVpnService::class.java))
        status.text = "Tunnel starting…"
    }

    private fun runDemoAction() {
        val app = application as GuardianApp
        if (!app::dispatcher.isInitialized) {
            status.text = "Engine still initializing… try again in a few seconds."
            return
        }
        status.text = "Submitting demo action…"
        thread(name = "gl-demo", isDaemon = true) {
            val result = try {
                app.dispatcher.submit(Action.Write("demo", mapOf("hello" to "governed")))
            } catch (t: Throwable) {
                runOnUiThread { status.text = "Demo failed: ${t.message}" }
                return@thread
            }
            val text = when (result) {
                is ConciergeInterface.ExecutionResult.Executed ->
                    "Demo EXECUTED. Substrate records: ${app.dispatcher.currentSubstrate().records.keys}"
                is ConciergeInterface.ExecutionResult.Rejected ->
                    "Demo REJECTED at gate ${result.atGate}: ${result.reason}"
            }
            runOnUiThread { status.text = text }
        }
    }
}
