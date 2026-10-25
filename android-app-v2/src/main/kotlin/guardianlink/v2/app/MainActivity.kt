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
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
    private lateinit var chatLog: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var chipRegion: HorizontalScrollView
    private var keyboardUp = false
    private lateinit var input: EditText

    private val handler = Handler(Looper.getMainLooper())
    private var renderedSignature: String = ""
    private var renderSigAtCreate: String = ""
    private var seeded = false

    private val refreshLoop = object : Runnable {
        override fun run() {
            renderPosture()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    private val app: GuardianLinkV2App get() = application as GuardianLinkV2App

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val palette = GlPrefs.palette(this)
        postureLine = TextView(this).apply {
            textSize = 13f
            setTextColor(palette.headerSub)
            text = "Starting…"
        }
        val title = TextView(this).apply {
            textSize = 20f
            setTextColor(palette.headerText)
            text = "GuardianLink"
        }
        val badge = TextView(this).apply {
            text = "G"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(palette.headerBg)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(palette.headerText)
            }
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        }
        val titleColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(dp(12), 0, 0, 0) }
            addView(title)
            addView(postureLine)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = UiKit.headerGradient(this@MainActivity, palette)
            elevation = dp(4).toFloat()
            setPadding(dp(16), dp(10), dp(16), dp(10))
            addView(badge)
            addView(titleColumn)
        }

        chatLog = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 10, 16, 10)
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
        chips.addView(chip("Start sensing") { startSensing() })
        chips.addView(chip("Stop sensing") {
            startService(SensingService.stopIntent(this@MainActivity))
            addBubble("Sensing stop requested.", fromUser = false)
        })
        chips.addView(chip("Pause location (governed)") { tryEngineAction() })
        val chipRow = HorizontalScrollView(this).apply { addView(chips) }
        chipRegion = chipRow

        input = EditText(this).apply {
            hint = "Message GuardianLink…"
            background = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
            setPadding(dp(16), dp(10), dp(8), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val mic = Button(this).apply {
            text = "🎤"
            isAllCaps = false
            background = UiKit.rounded(this@MainActivity, UiKit.blend(palette.cardBg, palette.userBubble, 0.15f), 999f)
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply { setMargins(dp(4), 0, dp(4), 0) }
            setOnClickListener { startVoiceCommand() }
        }
        val send = Button(this).apply {
            text = "Send"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = UiKit.rounded(this@MainActivity, palette.userBubble, 999f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(46),
            )
            setOnClickListener { submitConversation() }
        }
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = UiKit.rounded(this@MainActivity, palette.cardBg, 999f)
            elevation = dp(4).toFloat()
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(dp(14), dp(6), dp(14), dp(10)) }
            addView(input)
            addView(mic)
            addView(send)
        }

        val nav = UiKit.bottomNav(this, palette, "chat") { target ->
            when (target) {
                "vault" -> openVault()
                "settings" -> openSettings()
            }
        }

        // Cosmic stage: page-depth gradient at the root, a fixed
        // starfield over it, and the content floating transparent
        // on top — the sky stays put while the conversation moves.
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(chatScroll)
            addView(chipRow)
            addView(inputRow)
            addView(nav)
        }
        setContentView(android.widget.FrameLayout(this).apply {
            background = UiKit.pageGradient(this@MainActivity, palette)
            addView(
                StarfieldView(this@MainActivity, palette.userBubble, GlPrefs.isDark(this@MainActivity)),
                android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            addView(
                content,
                android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        })

        content.viewTreeObserver.addOnGlobalLayoutListener {
            val rect = android.graphics.Rect()
            content.getWindowVisibleDisplayFrame(rect)
            val up = content.height - rect.height() > content.height * 0.15 && content.height > 0
            if (up != keyboardUp) {
                keyboardUp = up
                chipRegion.visibility = if (up) View.GONE else View.VISIBLE
                if (up) chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }

        if (!seeded) {
            seeded = true
            seedConversation()
        }
        renderPosture()
        renderSigAtCreate = GlPrefs.renderSignature(this)
        if (GlPrefs.autoSense(this)) {
            startSensing()
        }
        handleVoiceSummon(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleVoiceSummon(intent)
    }

    /**
     * A system summon (assistant role) lands here: the agent comes up
     * already listening. If the microphone grant isn't there, the
     * pathway is named instead of failing silently — a bubble, and
     * the route to grant it under Settings → Permissions.
     */
    private fun handleVoiceSummon(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_VOICE_SUMMON, false) != true) return
        intent.removeExtra(EXTRA_VOICE_SUMMON)
        addBubble("You summoned me — I'm listening.", fromUser = false)
        startVoiceCommand()
    }

    private var recognizer: SpeechRecognizer? = null

    /**
     * Voice in, through the platform's speech engine, out through the
     * exact pipeline typed text takes: the words land in the input
     * box, submit like a typed message, and anything that needs
     * assent goes through the same governed ceremony. Voice is a
     * keyboard here, not a second authority.
     */
    private fun startVoiceCommand() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            addBubble(
                "Voice needs the microphone grant. It's under Settings → Permissions — " +
                    "turn on Microphone and I'll take spoken questions and commands.",
                fromUser = false,
            )
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            addBubble("This phone has no speech recognition engine available, so voice input can't run here.", fromUser = false)
            return
        }
        recognizer?.destroy()
        val sr = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                if (text.isNotEmpty()) {
                    input.setText(text)
                    submitConversation()
                } else {
                    addBubble("I didn't catch that — try again, or type it.", fromUser = false)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!text.isNullOrEmpty()) input.setText(text)
            }

            override fun onError(error: Int) {
                if (error != SpeechRecognizer.ERROR_NO_MATCH) {
                    addBubble("Voice input stopped (engine error $error). Typing still works.", fromUser = false)
                }
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val listenIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask GuardianLink, or give a command")
        }
        try {
            sr.startListening(listenIntent)
            addBubble("Listening…", fromUser = false)
        } catch (e: Exception) {
            addBubble("Voice input couldn't start: ${e.message}", fromUser = false)
        }
    }

    override fun onResume() {
        super.onResume()
        // Appearance is set in Settings; if it changed while we were
        // away, rebuild the screen under the new rendering rather than
        // straddling two palettes.
        if (renderSigAtCreate.isNotEmpty() && GlPrefs.renderSignature(this) != renderSigAtCreate) {
            recreate()
            return
        }
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
                    "You can revisit them any time under Settings → Permissions (Settings tab, below).",
                fromUser = false,
            )
        }
        renderedSignature = ""
        renderPosture()
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
                renderPosture()
            }
        }
    }

    private fun addBubble(text: String, fromUser: Boolean) {
        // Follow the conversation only when the sovereign is already
        // at the bottom; if they've scrolled up to read, new data
        // arriving must not yank the thread out from under them.
        val child = chatScroll.getChildAt(0)
        val nearBottom = child == null ||
            chatScroll.scrollY + chatScroll.height >= child.height - dp(96)
        val palette = GlPrefs.palette(this)
        val bubble = TextView(this).apply {
            this.text = text
            textSize = GlPrefs.bubbleTextSp(this@MainActivity)
            setPadding(dp(16), dp(11), dp(16), dp(11))
            // Tailed bubbles: three generous corners and one small
            // one at the sender's side — the shape every chat app
            // uses, instead of identical log rectangles.
            val r = 25f * resources.displayMetrics.density
            val tail = 8f * resources.displayMetrics.density
            val radii = if (fromUser) {
                floatArrayOf(r, r, r, r, tail, tail, r, r)
            } else {
                floatArrayOf(r, r, r, r, r, r, tail, tail)
            }
            if (fromUser) {
                setTextColor(Color.WHITE)
                background = UiKit.userGradient(this@MainActivity, palette, radii)
            } else {
                setTextColor(palette.appBubbleText)
                elevation = dp(2).toFloat()
                background = GradientDrawable().apply {
                    setColor(palette.cardBg)
                    cornerRadii = radii
                }
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
        if (nearBottom) {
            chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    // ------------------------------------------------------------------
    // Header posture line
    // ------------------------------------------------------------------

    /**
     * The header's one quiet line: posture only. The standing
     * answers themselves are NOT rendered here — they are the
     * machine's reasoning, surfaced inside conversation when
     * relevant and kept in the Vault for audit (his correction,
     * 2026-10-25: "not a billboard").
     */
    private fun renderPosture() {
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

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun openVault() {
        startActivity(Intent(this, VaultActivity::class.java))
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
                renderPosture()
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

    private fun chip(label: String, action: () -> Unit): Button {
        val palette = GlPrefs.palette(this)
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 13f
            setTextColor(palette.userBubble)
            background = GradientDrawable().apply {
                setColor(palette.cardBg)
                cornerRadius = 999f
                setStroke(dp(1), palette.userBubble)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(38),
            ).apply { setMargins(dp(4), dp(2), dp(4), dp(2)) }
            setOnClickListener { action() }
        }
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * resources.displayMetrics.density
    }

    companion object {
        private const val REFRESH_MS = 1000L
        private const val REQ_SENSING_PERMISSIONS = 2002

        /** Set by the assistant-role session when the system summon opens us. */
        const val EXTRA_VOICE_SUMMON = "guardianlink.v2.app.extra.VOICE_SUMMON"
    }
}
