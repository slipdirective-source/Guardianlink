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
            text = "Run governed engine action — pause location (assent ceremony)"
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
            engineLine = app.engineStatus
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
     * The real assent ceremony, end to end, for one governed action
     * (pause the location collector):
     *
     * 1. A core proposal is created and the core's own assent recorded
     *    (the conversational layer's assent record — Gate 6's port
     *    reads it).
     * 2. bridge.begin translates it and signs the Gate 1 origin; the
     *    rendering is what a full UI would put in front of the
     *    sovereign for review — here it is shown in the reply line.
     * 3. bridge.confirm IS the sovereign's confirm: it produces the
     *    AssentCodec assent signature at that moment.
     * 4. The thread waits out the fixed-rail cooling window, then
     *    bridge.submit assembles the bundle and submits through the
     *    concierge with EMPTY voice audio — no voice is enrolled in
     *    this build, so voice evidence is absent, not faked.
     *
     * Expected outcome in this build: the ceremony runs and the engine
     * halts at Gate 3 — "engine execution halts at Gate 3 (ZK proof
     * system not yet deployed)" — with the halt written back into the
     * ledger verbatim. That is the honest posture until Caleb rules on
     * Gate 3; nothing about this flow pretends otherwise.
     */
    private fun tryEngineAction() {
        val bridge = app.bridge
        if (bridge == null) {
            replyView.text = "Engine not up: ${app.engineStatus}"
            return
        }
        replyView.text = "Ceremony running…"
        thread(name = "gl-v2-engine-ceremony", isDaemon = true) {
            val text = runDemoCeremony(bridge)
            runOnUiThread {
                replyView.text = text
                renderedSignature = "" // the write-back changed standing answers
                renderAnswers()
            }
        }
    }

    private fun runDemoCeremony(bridge: GovernedActionBridge): String {
        val requestText = "Stop collecting location"
        // 1. Core proposal + the core's own assent record.
        val proposal = synchronized(app.coreLock) {
            val p = app.core.actions.propose(
                guardianlink.v2.core.ProposalInput(
                    actionType = "pause_collector",
                    target = "location",
                    expectedEffect = "The location collector pauses.",
                    reversibility = guardianlink.v2.core.Reversibility.REVERSIBLE,
                    consequenceClass = guardianlink.v2.core.ConsequenceClass.LOCAL_LOW,
                    requiredAuthority = guardianlink.v2.core.RequiredAuthority.FRESH_ASSENT,
                    requestedBy = "sovereign",
                ),
            )
            app.core.actions.recordAssent(p.id)
            p
        }
        app.persistCore()
        // 2. Begin: translation + Gate 1 origin signature + rendering.
        val request = guardianlink.v2.core.CeremonyRequest(
            proposalId = proposal.id,
            requestText = requestText,
            parseTrees = 1,
            intentVector = app.intentDistribution(guardianlink.v2.core.Intent.ACT),
            currentVector = app.intentDistribution(guardianlink.v2.core.Intent.ACT),
            measurements = app.ceremonyMeasurements(requestText),
        )
        val prepared = when (val b = bridge.begin(request)) {
            is guardianlink.v2.core.CeremonyBegin.Refused -> return "Ceremony refused to start: ${b.reason}"
            is guardianlink.v2.core.CeremonyBegin.Prepared -> b.ceremony
        }
        // 3. Confirm = the sovereign's assent action, signature produced now.
        val earliest = bridge.confirm(prepared)
        // 4. Respect the cooling rail, then submit with absent voice evidence.
        val waitMs = earliest - app.clock.nowMs()
        if (waitMs > 0) Thread.sleep(waitMs)
        val outcome = bridge.submit(prepared, guardianlink.voice.AudioSample(DoubleArray(0)))
        app.persistCore()
        return when (outcome) {
            is guardianlink.v2.core.CeremonyOutcome.Refused ->
                "Ceremony refused before submission: ${outcome.reason}"
            is guardianlink.v2.core.CeremonyOutcome.Submitted ->
                "Shown for assent: ${prepared.rendering}\n${outcome.userMessage}\n(write-back event ${outcome.writeBackEventId})"
        }
    }

    companion object {
        private const val REFRESH_MS = 1000L
        private const val REQ_SENSING_PERMISSIONS = 2002
    }
}
