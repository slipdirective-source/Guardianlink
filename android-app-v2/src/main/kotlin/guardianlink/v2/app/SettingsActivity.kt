package guardianlink.v2.app

import android.Manifest
import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.io.File
import kotlin.system.exitProcess

/**
 * Settings — the app's one hub for everything that isn't the
 * conversation itself, laid out the way every normal app lays it
 * out: cards of real controls. Appearance, behavior, permissions
 * (one level down, where they belong), data, and about. Every
 * control here is wired to real behavior; nothing is decorative.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout
    private val app: GuardianLinkV2App get() = application as GuardianLinkV2App

    private val assistantRoleLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { build() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(8))
        }
        // Build stamp: which build is actually running, readable at
        // a glance — install confusion costs more than the line.
        root.addView(TextView(this).apply {
            text = "Build: 2026-10-25 · brain + framing + clean surfaces + trips"
            textSize = 12f
            setTextColor(GlPrefs.palette(this@SettingsActivity).textSecondary)
            setPadding(0, 0, 0, dp(10))
        })
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            addView(root)
        }
        val palette = GlPrefs.palette(this)
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.pageBg)
            addView(
                LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundColor(palette.headerBg)
                    elevation = dp(4).toFloat()
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    addView(TextView(this@SettingsActivity).apply {
                        text = "Settings"
                        textSize = 20f
                        setTextColor(palette.headerText)
                    })
                },
            )
            addView(scroll)
            addView(UiKit.bottomNav(this@SettingsActivity, palette, "settings") { target ->
                when (target) {
                    "chat" -> finish()
                    "vault" -> {
                        startActivity(Intent(this@SettingsActivity, VaultActivity::class.java))
                        finish()
                    }
                }
            })
        }
        setContentView(screen)
        build()
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun dp(v: Int): Int = UiKit.dp(this, v)

    private fun build() {
        val palette = GlPrefs.palette(this)
        root.setBackgroundColor(palette.pageBg)
        root.removeAllViews()

        // ---------------- Appearance ----------------
        section("Appearance")
        val appearance = UiKit.cardView(this, palette)
        appearance.addView(rowLabel("Theme"))
        val night = GlPrefs.nightMode(this)
        appearance.addView(optionPills(
            listOf("System" to 0, "Light" to 1, "Dark" to 2), night,
        ) { mode -> GlPrefs.setNightMode(this, mode); recreate() })
        appearance.addView(rowLabel("Accent color"))
        appearance.addView(swatchRow(palette))
        appearance.addView(rowLabel("Chat text size"))
        val size = GlPrefs.textSize(this)
        appearance.addView(optionPills(
            listOf("Small" to 0, "Default" to 1, "Large" to 2), size,
        ) { value -> GlPrefs.setTextSize(this, value); build() })
        root.addView(appearance)

        // ---------------- Behavior ----------------
        section("Behavior")
        val behavior = UiKit.cardView(this, palette)
        val autoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@SettingsActivity).apply {
                    text = "Start sensing when the app opens"
                    textSize = 15f
                    setTextColor(palette.textPrimary)
                })
                addView(TextView(this@SettingsActivity).apply {
                    text = "Collection still follows your permission grants, every time."
                    textSize = 12.5f
                    setTextColor(palette.textSecondary)
                })
            })
            addView(SwitchCompat(this@SettingsActivity).apply {
                isChecked = GlPrefs.autoSense(this@SettingsActivity)
                setOnCheckedChangeListener { _, checked -> GlPrefs.setAutoSense(this@SettingsActivity, checked) }
            })
        }
        behavior.addView(autoRow)
        root.addView(behavior)

        navCard(
            "Send a checkup notification now",
            "The bullet-point summary of your standing answers, as a notification.",
        ) {
            MetricsNotifier.post(this, app)
            Toast.makeText(this, "Checkup sent — pull down your notifications.", Toast.LENGTH_SHORT).show()
        }
        assistantCard(palette)

        // ---------------- Connected email ----------------
        section("Connected email")
        emailCard(palette)

        // ---------------- Brain ----------------
        section("Brain")
        brainCard(palette)

        // ---------------- Wake phrase ----------------
        section("Wake phrase")
        wakeCard(palette)

        // ---------------- Permissions ----------------
        section("Permissions")
        navCard("Permissions", permissionsSummary()) {
            startActivity(Intent(this, PermissionsActivity::class.java))
        }

        // ---------------- Data ----------------
        section("Data")
        navCard("Copy diagnostics", "Version, engine posture, key and Gate 3 state — for pasting anywhere.") {
            copyDiagnostics()
        }
        navCard("Erase everything", "Deletes the encrypted state, audit anchors, and clock file, then restarts empty. No undo.", danger = true) {
            confirmErase()
        }

        // ---------------- About ----------------
        section("About")
        val about = UiKit.cardView(this, palette)
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
        about.addView(bodyLine("GuardianLink v0.2 · version $version", palette))
        about.addView(bodyLine(app.engineStatus, palette))
        val keyId = synchronized(app.coreLock) { app.core.operatorKeyId }
        about.addView(bodyLine("Operator key: ${keyId?.take(16) ?: "not bound yet"}…", palette))
        root.addView(about)
    }

    // ------------------------------------------------------------------
    // Pieces

    private fun section(title: String) {
        root.addView(TextView(this).apply {
            text = title.uppercase()
            textSize = 12.5f
            setTextColor(GlPrefs.accentColor(this@SettingsActivity))
            setPadding(dp(4), dp(18), 0, dp(8))
        })
    }

    private fun rowLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(GlPrefs.palette(this@SettingsActivity).textSecondary)
        setPadding(0, dp(10), 0, dp(6))
    }

    private fun bodyLine(text: String, palette: GlPrefs.Palette): TextView = TextView(this).apply {
        this.text = text
        textSize = 13.5f
        setTextColor(palette.textSecondary)
        setPadding(0, dp(3), 0, dp(3))
    }

    private fun optionPills(options: List<Pair<String, Int>>, selected: Int, onPick: (Int) -> Unit): LinearLayout {
        val palette = GlPrefs.palette(this)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, dp(6))
        }
        for ((label, value) in options) {
            val isSel = value == selected
            row.addView(Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 13.5f
                setTextColor(if (isSel) Color.WHITE else palette.chipText)
                background = UiKit.rounded(
                    this@SettingsActivity,
                    if (isSel) palette.userBubble else palette.chipBg,
                    999f,
                )
                layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                    setMargins(dp(3), 0, dp(3), 0)
                }
                setOnClickListener { onPick(value) }
            })
        }
        return row
    }

    private fun swatchRow(palette: GlPrefs.Palette): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(6))
        }
        val current = GlPrefs.accentName(this)
        for ((name, color) in GlPrefs.ACCENTS) {
            val selected = name == current
            row.addView(TextView(this).apply {
                text = if (selected) "✓" else ""
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(color)
                    if (selected) setStroke(dp(2), palette.textPrimary)
                }
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                    setMargins(dp(4), 0, dp(4), 0)
                }
                setOnClickListener {
                    GlPrefs.setAccent(this@SettingsActivity, name)
                    build()
                }
            })
        }
        row.addView(TextView(this).apply {
            text = current
            textSize = 13.5f
            setTextColor(palette.textPrimary)
            setPadding(dp(8), 0, 0, 0)
        })
        return row
    }

    /** A tappable card row: title, subtitle, chevron. */
    private fun navCard(title: String, subtitle: String, danger: Boolean = false, action: () -> Unit) {
        val palette = GlPrefs.palette(this)
        val card = UiKit.cardView(this, palette, 14f)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@SettingsActivity).apply {
                text = title
                textSize = 15.5f
                setTextColor(if (danger) Color.parseColor("#B3261E") else palette.textPrimary)
            })
            addView(TextView(this@SettingsActivity).apply {
                text = subtitle
                textSize = 12.5f
                setTextColor(palette.textSecondary)
                setPadding(0, dp(2), 0, 0)
            })
        })
        row.addView(TextView(this).apply {
            text = "›"
            textSize = 22f
            setTextColor(palette.textSecondary)
        })
        card.addView(row)
        UiKit.addRipple(card, this)
        card.setOnClickListener { action() }
        root.addView(card)
    }

    /**
     * The assistant role, as a card: the sanctioned way an app
     * becomes the thing the system summon opens from anywhere. The
     * literal words "OK Google" stay Google's — Android offers no
     * app a claim on them, and this card says so instead of
     * pretending. What the role gives is the gesture.
     */
    private fun assistantCard(palette: GlPrefs.Palette) {
        val card = UiKit.cardView(this, palette, 14f)
        card.addView(TextView(this).apply {
            text = "Summon from anywhere"
            textSize = 15.5f
            setTextColor(palette.textPrimary)
        })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            when {
                roleManager == null || !roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT) ->
                    card.addView(bodyLine("This phone doesn't expose the assistant role to apps.", palette))
                roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT) ->
                    card.addView(bodyLine("GuardianLink is your default assistant ✓ — summon it with your phone's assistant gesture from any app and it opens already listening.", palette))
                else -> {
                    card.addView(bodyLine("As your default assistant, GuardianLink takes the system summon (long-press home or power, or the corner swipe) from any app — no trip back to the home screen.", palette))
                    card.addView(Button(this).apply {
                        text = "Make GuardianLink my assistant"
                        isAllCaps = false
                        setTextColor(Color.WHITE)
                        background = UiKit.rounded(this@SettingsActivity, palette.userBubble, 999f)
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(44),
                        ).apply { setMargins(0, dp(10), 0, 0) }
                        setOnClickListener {
                            assistantRoleLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT))
                        }
                    })
                }
            }
        } else {
            card.addView(Button(this).apply {
                text = "Open assistant settings"
                isAllCaps = false
                setOnClickListener {
                    startActivity(Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS))
                }
            })
        }
        card.addView(bodyLine("One honest limit: the words \"OK Google\" belong to Google's assistant and no other app can claim them. The summon gesture is the route Android actually gives — the words you'd speak after it are yours.", palette))
        root.addView(card)
    }

    /**
     * The connected mailbox: address + IMAP app password, sealed in
     * the Keystore-backed store. Sync admits new mail as evidence;
     * the chat answers "what's in my email" from the record.
     */
    private var brainDownloading = false
    private var brainProgressPct = -1

    /**
     * The Brain card (his "make it easy from the app", 2026-10-25):
     * one tap downloads our own on-device brain — no key, no
     * account, nothing leaves the phone. The cloud key lane sits
     * below it as an optional upgrade, not the front door.
     */
    private fun brainCard(palette: GlPrefs.Palette) {
        val card = UiKit.cardView(this, palette, 14f)
        val store = LlmConfigStore(this)
        val config = store.load()
        val present = BrainStore.isPresent(this)
        card.addView(TextView(this).apply {
            text = "GuardianLink's own brain"
            textSize = 15.5f
            setTextColor(palette.textPrimary)
        })
        val status = TextView(this).apply {
            textSize = 13.5f
            setTextColor(palette.textSecondary)
            setPadding(0, dp(6), 0, 0)
        }
        status.text = when {
            brainDownloading -> "Downloading the brain…"
            present -> "On board: ${BrainStore.MODEL_LABEL}, running entirely on this phone. Any phrasing you use gets read here first — nothing you say leaves the device."
            else -> "Not downloaded yet. Until then, the fixed phrase forms carry simple commands. Download the brain and it reads any phrasing — proposes the action, shows it to you, fires only on your yes. Same as a cloud model, but ours: no key, no account, no network."
        }
        card.addView(status)

        // Prefer-on-device switch.
        val switchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        switchRow.addView(TextView(this).apply {
            text = "Prefer the on-device brain"
            textSize = 14f
            setTextColor(palette.textPrimary)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        switchRow.addView(SwitchCompat(this).apply {
            isChecked = BrainPrefs.preferLocal(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                BrainPrefs.setPreferLocal(this@SettingsActivity, checked)
            }
        })
        card.addView(switchRow)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        if (!present) {
            row.addView(Button(this).apply {
                text = if (brainDownloading) "Downloading…" else "Download the brain (1.6 GB)"
                isAllCaps = false
                isEnabled = !brainDownloading
                setTextColor(Color.WHITE)
                background = UiKit.rounded(this@SettingsActivity, palette.userBubble, 999f)
                layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
                setOnClickListener { startBrainDownload(status) }
            })
        } else {
            row.addView(Button(this).apply {
                text = "Test the brain"
                isAllCaps = false
                setTextColor(Color.WHITE)
                background = UiKit.rounded(this@SettingsActivity, palette.userBubble, 999f)
                layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(0, 0, dp(6), 0) }
                setOnClickListener {
                    Toast.makeText(this@SettingsActivity, "Asking the brain to read a test sentence… (first read warms it up)", Toast.LENGTH_SHORT).show()
                    kotlin.concurrent.thread(isDaemon = true) {
                        val result = CompositeLanguagePort(LocalBrainPort(this@SettingsActivity), CloudLanguagePort(this@SettingsActivity)).testReading()
                        runOnUiThread { Toast.makeText(this@SettingsActivity, result, Toast.LENGTH_LONG).show() }
                    }
                }
            })
            row.addView(Button(this).apply {
                text = "Remove"
                isAllCaps = false
                setTextColor(palette.userBubble)
                background = UiKit.rounded(this@SettingsActivity, palette.chipBg, 999f)
                layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
                setOnClickListener {
                    BrainStore.remove(this@SettingsActivity)
                    build()
                }
            })
        }
        card.addView(row)

        // ---- Cloud lane: optional upgrade, no longer the front door. ----
        card.addView(TextView(this).apply {
            text = "Cloud brain (optional)"
            textSize = 14f
            setTextColor(palette.textPrimary)
            setPadding(0, dp(16), 0, 0)
        })
        card.addView(bodyLine(
            if (config == null) "Not connected. A bigger cloud model (OpenAI, Grok, OpenRouter — your own key) can read in the brain's place when the brain is off or unsure how to read something."
            else "Connected: ${config.model} via ${config.baseUrl}. Readings are recorded as agent-derived, same as the on-device brain's.",
            palette,
        ))
        val cloudRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        cloudRow.addView(Button(this).apply {
            text = if (config == null) "Connect a cloud model" else "Change model"
            isAllCaps = false
            setTextColor(palette.userBubble)
            background = UiKit.rounded(this@SettingsActivity, palette.chipBg, 999f)
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(0, 0, dp(6), 0) }
            setOnClickListener { showLlmDialog(config) }
        })
        if (config != null) {
            cloudRow.addView(Button(this).apply {
                text = "Disconnect"
                isAllCaps = false
                setTextColor(palette.userBubble)
                background = UiKit.rounded(this@SettingsActivity, palette.chipBg, 999f)
                layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
                setOnClickListener {
                    LlmConfigStore(this@SettingsActivity).clear()
                    build()
                }
            })
        }
        card.addView(cloudRow)
        root.addView(card)
    }

    /** Streams the weights to a .part file, verifies the pinned byte size, then renames into place. */
    private fun startBrainDownload(status: TextView) {
        if (brainDownloading) return
        brainDownloading = true
        status.text = "Downloading the brain… 0%"
        kotlin.concurrent.thread(isDaemon = true) {
            var ok = false
            var err: String? = null
            try {
                val conn = java.net.URL(BrainStore.MODEL_URL).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: BrainStore.EXPECTED_BYTES
                val part = BrainStore.partFile(this@SettingsActivity)
                conn.inputStream.use { input ->
                    part.outputStream().use { output ->
                        val buf = ByteArray(1 shl 18)
                        var read = 0L
                        var n: Int
                        while (input.read(buf).also { n = it } >= 0) {
                            output.write(buf, 0, n)
                            read += n
                            val pct = (read * 100 / total).toInt().coerceIn(0, 100)
                            if (pct != brainProgressPct) {
                                brainProgressPct = pct
                                runOnUiThread { status.text = "Downloading the brain… $pct%" }
                            }
                        }
                    }
                }
                if (part.length() == BrainStore.EXPECTED_BYTES) {
                    ok = part.renameTo(BrainStore.modelFile(this@SettingsActivity))
                    if (!ok) err = "The download is complete but couldn't be moved into place."
                } else {
                    err = "The download arrived at ${part.length()} bytes instead of the expected ${BrainStore.EXPECTED_BYTES} — discarded rather than trusted. Try again on a solid connection."
                    part.delete()
                }
            } catch (e: Exception) {
                err = "Download failed: ${e.message ?: "network trouble"} — tap Download to try again."
            }
            brainDownloading = false
            brainProgressPct = -1
            runOnUiThread {
                build()
                if (ok) Toast.makeText(this@SettingsActivity, "Brain on board. Say anything in chat — it reads here now.", Toast.LENGTH_LONG).show()
                else if (err != null) Toast.makeText(this@SettingsActivity, err, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showLlmDialog(existing: LlmConfig?) {
        val palette = GlPrefs.palette(this)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        fun field(hint: String, initial: String, password: Boolean = false): android.widget.EditText =
            android.widget.EditText(this).apply {
                this.hint = hint
                setText(initial)
                textSize = 15f
                if (password) {
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
            }
        val base = field("Base URL", existing?.baseUrl ?: "https://api.openai.com/v1")
        val model = field("Model", existing?.model ?: "gpt-4o-mini")
        val key = field("API key", "", password = true)
        val presets = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(6))
        }
        for ((label, presetBase, presetModel) in listOf(
            Triple("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
            Triple("Grok", "https://api.x.ai/v1", "grok-4-fast"),
            Triple("OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-4o-mini"),
        )) {
            presets.addView(Button(this).apply {
                text = label
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(2), 0, dp(2), 0) }
                setOnClickListener {
                    base.setText(presetBase)
                    model.setText(presetModel)
                }
            })
        }
        form.addView(presets)
        form.addView(base)
        form.addView(model)
        form.addView(key)
        form.addView(TextView(this).apply {
            text = "Leave the key blank to keep the saved one. Presets fill the endpoint and a sensible default model — both stay editable."
            textSize = 12.5f
            setTextColor(palette.textSecondary)
            setPadding(0, dp(8), 0, 0)
        })
        AlertDialog.Builder(this)
            .setTitle("Connect a language model")
            .setView(form)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val b = base.text.toString().trim()
                val m = model.text.toString().trim()
                val k = key.text.toString().trim().ifBlank { existing?.apiKey ?: "" }
                if (b.isBlank() || m.isBlank() || k.isBlank()) {
                    Toast.makeText(this, "Base URL, model, and API key are all required.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                LlmConfigStore(this).save(LlmConfig(b, m, k))
                build()
            }
            .show()
    }

    private fun wakeCard(palette: GlPrefs.Palette) {
        val card = UiKit.cardView(this, palette, 14f)
        card.addView(TextView(this).apply {
            text = "Hands-free summon"
            textSize = 15.5f
            setTextColor(palette.textPrimary)
        })
        val phrase = WakePrefs.phrase(this)
        val enabled = WakePrefs.enabled(this)
        val modelReady = WakeWordEngine.modelInstalled(this)
        card.addView(bodyLine("Say \"$phrase\" and GuardianLink opens listening — the phrase is spotted on this phone (Vosk, offline); no audio leaves the device and nothing is recorded before the phrase. You can change the phrase to any handle you like.", palette))
        val micGranted = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        card.addView(bodyLine(
            when {
                !enabled -> "Off."
                !modelReady -> "On, but the offline voice model (~40 MB) isn't downloaded yet — tap Get model."
                !micGranted -> "On, but the microphone grant is missing — it's under Settings → Permissions."
                WakeWordEngine.lastError != null && SensingService.isRunning ->
                    "On, but the ear failed to start: ${WakeWordEngine.lastError}. Toggle it off and on to retry."
                WakeWordEngine.isRunning -> "On — listening now for \"$phrase\"."
                !SensingService.isRunning -> "On — the ear starts when sensing starts. Tap Start sensing on the chat screen."
                else -> "On — sensing is running but the ear hasn't started. Toggle it off and on."
            },
            palette,
        ))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        row.addView(Button(this).apply {
            text = if (enabled) "Turn off" else "Turn on"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = UiKit.rounded(this@SettingsActivity, palette.userBubble, 999f)
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(0, 0, dp(6), 0) }
            setOnClickListener {
                WakePrefs.setEnabled(this@SettingsActivity, !enabled)
                if (SensingService.isRunning) {
                    startService(SensingService.refreshIntent(this@SettingsActivity))
                }
                // Give the engine a beat to start/fail, then show the live truth.
                android.os.Handler(mainLooper).postDelayed({ build() }, 600)
                build()
            }
        })
        row.addView(Button(this).apply {
            text = "Set phrase"
            isAllCaps = false
            setTextColor(palette.userBubble)
            background = UiKit.rounded(this@SettingsActivity, palette.chipBg, 999f)
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(6), 0, dp(6), 0) }
            setOnClickListener { showWakePhraseDialog(phrase) }
        })
        if (!modelReady) {
            row.addView(Button(this).apply {
                text = "Get model"
                isAllCaps = false
                setTextColor(palette.userBubble)
                background = UiKit.rounded(this@SettingsActivity, palette.chipBg, 999f)
                layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
                setOnClickListener {
                    Toast.makeText(this@SettingsActivity, "Downloading the offline voice model (~40 MB)…", Toast.LENGTH_SHORT).show()
                    WakeWordEngine.downloadModel(this@SettingsActivity) { ok ->
                        runOnUiThread {
                            Toast.makeText(
                                this@SettingsActivity,
                                if (ok) "Voice model ready." else "Download failed — check the connection and try again.",
                                Toast.LENGTH_LONG,
                            ).show()
                            if (ok && SensingService.isRunning) {
                                startService(SensingService.refreshIntent(this@SettingsActivity))
                            }
                            build()
                        }
                    }
                }
            })
        }
        card.addView(row)
        root.addView(card)
    }

    private fun showWakePhraseDialog(current: String) {
        val input = android.widget.EditText(this).apply {
            setText(current)
            textSize = 15f
            hint = "Wake phrase, e.g. Hey Guard"
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Wake phrase")
            .setView(form)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val phrase = input.text.toString().trim()
                if (phrase.split(Regex("\\s+")).filter { it.isNotEmpty() }.size >= 2) {
                    WakePrefs.setPhrase(this, phrase)
                    if (SensingService.isRunning) {
                        startService(SensingService.refreshIntent(this))
                    }
                    build()
                } else {
                    Toast.makeText(this, "Use at least two words — short phrases false-trigger.", Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun emailCard(palette: GlPrefs.Palette) {
        val card = UiKit.cardView(this, palette, 14f)
        val store = EmailAccountStore(this)
        val account = store.load()
        card.addView(TextView(this).apply {
            text = "Mailbox"
            textSize = 15.5f
            setTextColor(palette.textPrimary)
        })
        if (account == null) {
            card.addView(bodyLine("Not connected. Connect a mailbox and GuardianLink reads new mail into your record — sender, subject, and a snippet — so your questions can draw on it.", palette))
            card.addView(bodyLine("For Gmail: turn on 2-Step Verification, create an App Password (Google Account → Security → App passwords), and paste it here. The password is sealed on this phone and used only for IMAP sign-in.", palette))
        } else {
            card.addView(bodyLine("Connected: ${account.address} via ${account.host}:${account.port}", palette))
            val lastSync = EmailSync.lastSyncAt(this)
            if (lastSync > 0) {
                val whenText = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.US)
                    .format(java.util.Date(lastSync))
                card.addView(bodyLine("Last sync: $whenText", palette))
            }
            EmailSync.lastError(this)?.let { card.addView(bodyLine(it, palette)) }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        row.addView(Button(this).apply {
            text = if (account == null) "Connect a mailbox" else "Change mailbox"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = UiKit.rounded(this@SettingsActivity, palette.userBubble, 999f)
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(0, 0, dp(6), 0) }
            setOnClickListener { showEmailDialog(account) }
        })
        if (account != null) {
            row.addView(Button(this).apply {
                text = "Fetch now"
                isAllCaps = false
                setTextColor(palette.userBubble)
                background = UiKit.rounded(this@SettingsActivity, palette.chipBg, 999f)
                layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(6), 0, 0, 0) }
                setOnClickListener {
                    Toast.makeText(this@SettingsActivity, "Fetching mail…", Toast.LENGTH_SHORT).show()
                    kotlin.concurrent.thread(name = "gl-email-manual", isDaemon = true) {
                        val result = EmailSync.syncNow(this@SettingsActivity, app)
                        runOnUiThread {
                            Toast.makeText(
                                this@SettingsActivity,
                                result.error ?: "${result.admitted} new message(s) admitted.",
                                Toast.LENGTH_LONG,
                            ).show()
                            build()
                        }
                    }
                }
            })
        }
        card.addView(row)
        if (account != null) {
            card.addView(TextView(this).apply {
                text = "Disconnect and forget the credential"
                textSize = 13.5f
                setTextColor(Color.parseColor("#B3261E"))
                setPadding(0, dp(12), 0, 0)
                setOnClickListener {
                    EmailAccountStore(this@SettingsActivity).clear()
                    EmailSync.forgetSeen(this@SettingsActivity)
                    CollectorAvailability.refresh(app)
                    build()
                }
            })
        }
        root.addView(card)
    }

    private fun showEmailDialog(existing: EmailAccount?) {
        val palette = GlPrefs.palette(this)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        fun field(hint: String, initial: String, password: Boolean = false): android.widget.EditText =
            android.widget.EditText(this).apply {
                this.hint = hint
                setText(initial)
                textSize = 15f
                if (password) {
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
            }
        val address = field("Email address", existing?.address ?: "")
        val host = field("IMAP server", existing?.host ?: "imap.gmail.com")
        val password = field("App password", "", password = true)
        form.addView(address)
        form.addView(host)
        form.addView(password)
        form.addView(TextView(this).apply {
            text = "Gmail: imap.gmail.com · Outlook: outlook.office365.com · Yahoo: imap.mail.yahoo.com. Leave the password blank to keep the saved one."
            textSize = 12.5f
            setTextColor(palette.textSecondary)
            setPadding(0, dp(8), 0, 0)
        })
        AlertDialog.Builder(this)
            .setTitle("Connect a mailbox")
            .setView(form)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val addr = address.text.toString().trim()
                val h = host.text.toString().trim().ifBlank { "imap.gmail.com" }
                val pw = password.text.toString().trim()
                    .ifBlank { existing?.password ?: "" }
                if (addr.isBlank() || pw.isBlank()) {
                    Toast.makeText(this, "Address and app password are both required.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                EmailAccountStore(this).save(EmailAccount(addr, h, 993, pw.replace(" ", "")))
                CollectorAvailability.refresh(app)
                Toast.makeText(this, "Mailbox saved — fetching…", Toast.LENGTH_SHORT).show()
                kotlin.concurrent.thread(name = "gl-email-first", isDaemon = true) {
                    EmailSync.syncNow(this, app)
                    runOnUiThread { build() }
                }
                build()
            }
            .show()
    }

    // ------------------------------------------------------------------

    private fun permissionsSummary(): String {
        var total = 0
        var on = 0
        fun count(granted: Boolean) {
            total++
            if (granted) on++
        }
        count(
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED,
        )
        count(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        count(NotificationManagerCompat.from(this).areNotificationsEnabled())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            count(ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED)
        }
        count(NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName))
        count(ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED)
        count(ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED)
        count(ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED)
        count(ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED)
        count(
            try {
                val enabled = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
                enabled.split(':').any { it.startsWith(packageName) }
            } catch (e: Exception) {
                false
            },
        )
        return "$on of $total core grants are on. Each has its own pathway — what it's for, its state, and the shortest route to change it."
    }

    private fun copyDiagnostics() {
        val keyId = synchronized(app.coreLock) { app.core.operatorKeyId }
        val answers = synchronized(app.coreLock) { app.core.snapshot().size }
        val text = buildString {
            appendLine("GuardianLink v0.2 diagnostics")
            appendLine("Engine: ${app.engineStatus}")
            appendLine("Persistence: ${app.persistenceStatus}")
            appendLine("Standing answers: $answers")
            appendLine("Operator key: ${keyId ?: "unbound"}")
            appendLine("Gate 3 native bridge: ${if (NativeZkBridge.isAvailable()) "available" else "unavailable"}")
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("GuardianLink diagnostics", text))
        Toast.makeText(this, "Diagnostics copied.", Toast.LENGTH_SHORT).show()
    }

    private fun confirmErase() {
        AlertDialog.Builder(this)
            .setTitle("Erase everything?")
            .setMessage("The encrypted state, audit anchors, and clock file on this phone will be deleted and the app will restart empty. This cannot be undone.")
            .setNegativeButton("Keep my data", null)
            .setPositiveButton("Erase") { _, _ -> eraseEverything() }
            .show()
    }

    private fun eraseEverything() {
        stopService(SensingService.stopIntent(this))
        try {
            filesDir.listFiles()?.forEach { it.deleteRecursively() }
            File(cacheDir, "gl").deleteRecursively()
        } catch (e: Exception) {
            // Best effort per file; the restart still proceeds and any
            // survivor is named by the persistence layer on next boot.
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
        exitProcess(0)
    }
}
