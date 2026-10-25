package guardianlink.v2.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import guardianlink.v2.core.Delta
import kotlin.concurrent.thread

/**
 * GuardianLink surface — chat first.
 *
 * The conversation IS the interface (corrected product model): a
 * message thread with the sovereign, a compact "Now" card holding the
 * five standing answers in plain language, quick-action chips for the
 * governed paths, and a permissions pathway one tap away. The engine's
 * rigor stays underneath: governed actions still run the full assent
 * ceremony and still halt — named, in plain words — wherever a check
 * cannot pass. No gate jargon is shown unless it is the answer.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var postureLine: TextView
    private lateinit var nowCard: LinearLayout
    private lateinit var chatLog: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var input: EditText

    private val handler = Handler(Looper.getMainLooper())
    private var renderedSignature: String = ""
    private var seeded = false

    private val refreshLoop = object : Runnable {
        override fun run() {
            renderNow()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    private val app: GuardianLinkV2App get() = application as GuardianLinkV2App

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        postureLine = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.DKGRAY)
            text = "Starting…"
        }
        val title = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.BLACK)
            text = "GuardianLink"
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 28, 32, 8)
            addView(title)
            addView(postureLine)
        }

        nowCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 20, 28, 20)
            background = rounded(Color.parseColor("#F4F6F7"), 18f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(24, 8, 24, 8) }
        }

        chatLog = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 12, 20, 12)
        }
        chatScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            addView(chatLog)
        }

        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 4, 16, 4)
        }
        chips.addView(chip("Permissions") { openPermissions() })
        chips.addView(chip("Start sensing") { startSensing() })
        chips.addView(chip("Stop sensing") {
            startService(SensingService.stopIntent(this@MainActivity))
            addBubble("Sensing stop requested.", fromUser = false)
        })
        chips.addView(chip("Pause location (governed)") { tryEngineAction() })
        val chipRow = HorizontalScrollView(this).apply { addView(chips) }

        input = EditText(this).apply {
            hint = "Message GuardianLink…"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val send = Button(this).apply {
            text = "Send"
            setOnClickListener { submitConversation() }
        }
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(20, 6, 20, 16)
            addView(input)
            addView(send)
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(nowCard)
            addView(chatScroll)
            addView(chipRow)
            addView(inputRow)
        })

        if (!seeded) {
            seeded = true
            seedConversation()
        }
        renderNow()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshLoop)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshLoop)
        app.persistCore()
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_SENSING_PERMISSIONS) return
        CollectorAvailability.refresh(app)
        val denied = permissions.filterIndexed { i, _ ->
            grantResults.getOrNull(i) != PackageManager.PERMISSION_GRANTED
        }
        if (denied.isEmpty()) {
            addBubble("Permissions granted — the collectors they feed come back on line.", fromUser = false)
        } else {
            addBubble(
                "Still off: ${denied.joinToString(", ") { it.substringAfterLast('.') }}. " +
                    "Anything that needs those stays named as unavailable — nothing is faked for them. " +
                    "You can revisit them any time from the Permissions chip.",
                fromUser = false,
            )
        }
        renderedSignature = ""
        renderNow()
    }

    // ------------------------------------------------------------------
    // Conversation
    // ------------------------------------------------------------------

    private fun seedConversation() {
        val opening = when {
            app.persistenceError != null ->
                "I'm up, but your saved state couldn't be read, so nothing was loaded and nothing will be overwritten. " +
                    "This session starts fresh and says so."
            else ->
                "I'm up. Ask me anything I'm keeping track of, tell me what happened, or give me something to do. " +
                    "The card above is what I currently know — it updates as things change."
        }
        addBubble(opening, fromUser = false)
        addBubble(
            "Governed actions run every check before they act. Voice assent isn't enrolled yet, so an action that needs it will stop at the voice check and tell you — try the “Pause location (governed)” chip to watch it happen.",
            fromUser = false,
        )
    }

    private fun submitConversation() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.text.clear()
        addBubble(text, fromUser = true)
        thread(name = "gl-v2-conversation", isDaemon = true) {
            val result = synchronized(app.coreLock) { app.core.say(text) }
            app.persistCore()
            runOnUiThread {
                addBubble(result.reply, fromUser = false)
                renderedSignature = ""
                renderNow()
            }
        }
    }

    private fun addBubble(text: String, fromUser: Boolean) {
        val bubble = TextView(this).apply {
            this.text = text
            textSize = 15f
            setPadding(28, 20, 28, 20)
            if (fromUser) {
                setTextColor(Color.WHITE)
                background = rounded(Color.parseColor("#0F5257"), 28f)
            } else {
                setTextColor(Color.parseColor("#14181A"))
                background = rounded(Color.parseColor("#E9EDEF"), 28f)
            }
        }
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = if (fromUser) Gravity.END else Gravity.START
            setMargins(8, 6, 8, 6)
        }
        chatLog.addView(bubble, params)
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ------------------------------------------------------------------
    // Now card (standing answers, plain language)
    // ------------------------------------------------------------------

    private fun renderNow() {
        val answers = synchronized(app.coreLock) { app.core.snapshot() }
        val signature = answers.joinToString("|") { "${it.id}:${it.current.version}" } +
            app.engineStatus + app.persistenceStatus + app.engineReady
        if (signature == renderedSignature) return
        renderedSignature = signature

        postureLine.text = when {
            app.persistenceError != null -> "Running fresh — saved state failed its checks and was left untouched."
            app.engineReady -> "On. Governed actions ready; sensing follows your permissions."
            else -> "Starting the governed engine… the conversation is already live."
        }

        nowCard.removeAllViews()
        nowCard.addView(TextView(this).apply {
            text = "NOW"
            textSize = 12f
            setTextColor(Color.parseColor("#0F5257"))
        })
        for (q in answers) {
            nowCard.addView(TextView(this).apply {
                text = q.text
                textSize = 13f
                setTextColor(Color.DKGRAY)
                setPadding(0, 12, 0, 0)
            })
            nowCard.addView(TextView(this).apply {
                text = "${q.current.text}  ·  ${plainDelta(q.current.delta)}"
                textSize = 14f
                setTextColor(Color.parseColor("#14181A"))
            })
        }
    }

    private fun plainDelta(delta: Delta): String = when (delta) {
        Delta.DISTINGUISHED -> "settled"
        Delta.EQUIVALENT -> "confirmed by more than one source"
        Delta.UNKNOWN -> "not settled yet"
        Delta.INAPPLICABLE -> "doesn't apply right now"
    }

    // ------------------------------------------------------------------
    // Sensing + permissions
    // ------------------------------------------------------------------

    private fun openPermissions() {
        startActivity(Intent(this, PermissionsActivity::class.java))
    }

    private fun startSensing() {
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
        addBubble(
            "Sensing started. Collectors report as they can; anything still missing a permission is named in the Now card instead of being guessed at.",
            fromUser = false,
        )
    }

    // ------------------------------------------------------------------
    // Governed action (assent ceremony, presented in chat)
    // ------------------------------------------------------------------

    private fun tryEngineAction() {
        val bridge = app.bridge
        if (bridge == null) {
            addBubble("The governed engine isn't up yet: ${app.engineStatus}", fromUser = false)
            return
        }
        addBubble("Pause location", fromUser = true)
        addBubble("Running it through the full ceremony — every check, in order…", fromUser = false)
        thread(name = "gl-v2-engine-ceremony", isDaemon = true) {
            val lines = runDemoCeremony(bridge)
            runOnUiThread {
                for (line in lines) addBubble(line, fromUser = false)
                renderedSignature = ""
                renderNow()
            }
        }
    }

    private fun runDemoCeremony(bridge: GovernedActionBridge): List<String> {
        val requestText = "Stop collecting location"
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
        val request = guardianlink.v2.core.CeremonyRequest(
            proposalId = proposal.id,
            requestText = requestText,
            parseTrees = 1,
            intentVector = app.intentDistribution(guardianlink.v2.core.Intent.ACT),
            currentVector = app.intentDistribution(guardianlink.v2.core.Intent.ACT),
            measurements = app.ceremonyMeasurements(requestText),
        )
        val prepared = when (val b = bridge.begin(request)) {
            is guardianlink.v2.core.CeremonyBegin.Refused ->
                return listOf("The ceremony refused to start: ${b.reason}")
            is guardianlink.v2.core.CeremonyBegin.Prepared -> b.ceremony
        }
        val shown = "Here is the exact action your assent covers:\n${prepared.rendering}"
        val earliest = bridge.confirm(prepared)
        val waitMs = earliest - app.clock.nowMs()
        if (waitMs > 0) Thread.sleep(waitMs)
        val outcome = bridge.submit(prepared, guardianlink.voice.AudioSample(DoubleArray(0)))
        app.persistCore()
        val tail = when (outcome) {
            is guardianlink.v2.core.CeremonyOutcome.Refused ->
                "Refused before submission: ${outcome.reason}"
            is guardianlink.v2.core.CeremonyOutcome.Submitted -> plainOutcome(outcome.userMessage)
        }
        return listOf(shown, tail)
    }

    /** Translate the engine's outcome line into plain words, keeping its facts. */
    private fun plainOutcome(message: String): String = when {
        message.contains("theta_voice") || message.contains("gate 7", ignoreCase = true) ->
            "It stopped at the voice check — no voice is enrolled yet, so there is nothing to match against, and nothing ran. " +
                "That's the system working as designed: absent evidence halts, it never waves through. (Engine record: $message)"
        else -> message
    }

    // ------------------------------------------------------------------

    private fun chip(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun rounded(color: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * resources.displayMetrics.density
    }

    companion object {
        private const val REFRESH_MS = 1000L
        private const val REQ_SENSING_PERMISSIONS = 2002
    }
}
