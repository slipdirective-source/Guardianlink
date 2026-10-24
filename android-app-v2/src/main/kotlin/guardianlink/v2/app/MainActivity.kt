package guardianlink.v2.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import guardianlink.gates.Action
import kotlin.concurrent.thread

/**
 * Live Now surface (spec §3.6), first cut — programmatic UI, no Compose.
 *
 * Layout: status line, the five standing answers (refreshed live from
 * the core), one conversational input wired to the v0.2 classifier +
 * local governed actions, sensing start/stop, and an explicit
 * "engine posture" line so the fail-closed state of governed engine
 * execution is visible instead of implied.
 *
 * The refresh loop re-renders whenever any standing answer's version
 * changes, including changes caused by the sensing service admitting
 * evidence in the background — the answers are maintained continuously,
 * this view just renders them.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var answersView: TextView
    private lateinit var replyView: TextView
    private lateinit var input: EditText

    private val handler = Handler(Looper.getMainLooper())
    private var renderedSignature: String = ""

    private val refreshLoop = object : Runnable {
        override fun run() {
            renderAnswers()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply {
            textSize = 15f
            text = "GuardianLink v0.2 — Live Now"
        }
        answersView = TextView(this).apply {
            textSize = 14f
            setPadding(0, 16, 0, 16)
        }
        replyView = TextView(this).apply { textSize = 14f }
        input = EditText(this).apply {
            hint = "Say it naturally — report, ask, or act…"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val sendButton = Button(this).apply {
            text = "Send"
            setOnClickListener { submitConversation() }
        }
        val startSensing = Button(this).apply {
            text = "Start sensing"
            setOnClickListener { startSensing() }
        }
        val stopSensing = Button(this).apply {
            text = "Stop sensing"
            setOnClickListener {
                startService(SensingService.stopIntent(this@MainActivity))
                replyView.text = "Sensing stop requested."
            }
        }
        val engineButton = Button(this).apply {
            text = "Try governed engine action (fails closed in this cut)"
            setOnClickListener { tryEngineAction() }
        }

        val answersScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            addView(answersView)
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(status)
            addView(answersScroll)
            addView(replyView)
            addView(input)
            addView(sendButton)
            addView(startSensing)
            addView(stopSensing)
            addView(engineButton)
        })
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshLoop)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshLoop)
        // Save point: persist the full core state to the encrypted store.
        // persistCore refuses when startup verification failed, so a
        // corrupt store is never paved over by a later pause.
        app.persistCore()
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_SENSING_PERMISSIONS) return
        // A grant that just landed revives a DENIED collector; a denial
        // keeps it named-unavailable. CollectorAvailability acts only on
        // transitions, and a sovereign pause is never overridden.
        CollectorAvailability.refresh(app)
        val denied = permissions.filterIndexed { i, _ ->
            grantResults.getOrNull(i) != PackageManager.PERMISSION_GRANTED
        }
        replyView.text = if (denied.isEmpty()) {
            "Permissions granted. Collectors reconcile on the next sensing start (or immediately, in Position)."
        } else {
            "Denied: ${denied.joinToString(", ") { it.substringAfterLast('.') }}. " +
                "The affected collectors are named as unavailable in Position — nothing is fabricated for them."
        }
        renderedSignature = "" // force re-render with the new collector states
        renderAnswers()
    }

    private val app: GuardianLinkV2App get() = application as GuardianLinkV2App

    /** Render the five standing answers + posture lines if anything changed. */
    private fun renderAnswers() {
        val answers: List<guardianlink.v2.core.StandingQuestion>
        val engineLine: String
        synchronized(app.coreLock) {
            answers = app.core.snapshot()
            engineLine = when {
                !app.engineReady -> "Engine: initializing… (core is live; governed engine execution not yet up)"
                else -> "Engine: up — ProductionVerifiers wired, evidence FAILS CLOSED: governed engine execution is unavailable in this cut and refuses rather than fabricating evidence."
            }
        }
        val signature = answers.joinToString("|") { "${it.id}:${it.current.version}" } + engineLine + app.persistenceStatus
        if (signature == renderedSignature) return
        renderedSignature = signature
        val sb = StringBuilder()
        for (q in answers) {
            sb.append("▸ ${q.text}  (v${q.current.version}, ${q.current.delta.wire}, ${q.current.basis.wire})\n")
            sb.append(q.current.text).append("\n\n")
        }
        sb.append(engineLine).append("\n")
        sb.append(app.persistenceStatus).append("\n")
        sb.append("Unavailable collectors are named in Position above — no data is fabricated for them.")
        answersView.text = sb.toString()
    }

    private fun submitConversation() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.text.clear()
        replyView.text = "…"
        thread(name = "gl-v2-conversation", isDaemon = true) {
            val result = synchronized(app.coreLock) { app.core.say(text) }
            app.persistCore() // save point: a completed conversation turn
            runOnUiThread {
                replyView.text = result.reply
                renderedSignature = "" // force re-render
                renderAnswers()
            }
        }
    }

    private fun startSensing() {
        // Runtime permission flow (§d): POST_NOTIFICATIONS (API 33+, the
        // FGS notification must be visible), fine/coarse location (the
        // location collector), and ACTIVITY_RECOGNITION (API 29+, the
        // motion collector). Everything missing is requested in one
        // trip; whatever is denied degrades its collector honestly
        // (named in Position) instead of blocking the others.
        //
        // ACCESS_BACKGROUND_LOCATION is deliberately NOT in this
        // request: on API 30+ it must be asked for separately, after
        // foreground location is granted, and the system ignores it when
        // bundled (§d). A location FGS started from this visible UI
        // keeps working after the UI leaves the foreground without it;
        // background location becomes its own guided step when a
        // collector actually needs it.
        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        wanted.add(Manifest.permission.ACCESS_FINE_LOCATION)
        wanted.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            wanted.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQ_SENSING_PERMISSIONS)
        }
        startForegroundService(SensingService.startIntent(this))
        replyView.text = "Sensing started. Collectors report as they can; missing permissions are named in Position."
    }

    /**
     * Deliberately demonstrates the fail-closed boundary: the bridge is
     * real, the action is a valid governed Write — and the evidence
     * provider refuses, so nothing executes and the UI says exactly that.
     */
    private fun tryEngineAction() {
        val bridge = app.bridge
        if (bridge == null) {
            replyView.text = "Engine still initializing — try again shortly."
            return
        }
        thread(name = "gl-v2-engine-demo", isDaemon = true) {
            val text = when (val r = bridge.submit(Action.Write("v2/demo", mapOf("hello" to "governed")))) {
                is GovernedActionBridge.BridgeResult.Executed ->
                    "EXECUTED (unexpected in this cut) — substrate: ${r.newSubstrate.records.keys}"
                is GovernedActionBridge.BridgeResult.Rejected ->
                    "Rejected at gate ${r.atGate}: ${r.reason}"
                is GovernedActionBridge.BridgeResult.DeniedEvidenceUnavailable ->
                    "Refused (fail-closed): ${r.reason}"
            }
            runOnUiThread { replyView.text = text }
        }
    }

    companion object {
        private const val REFRESH_MS = 1000L
        private const val REQ_SENSING_PERMISSIONS = 2002
    }
}
