package guardianlink.v2.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import guardianlink.v2.core.EvidenceEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * The data vault.
 *
 * The sovereign's rule: track everything, surface only what's
 * relevant. So the main page carries the conversation and the
 * standing answers in plain words — and the raw metrics live here,
 * behind tabs, readable whenever he wants them: every location fix
 * with its coordinates and accuracy, every motion classification,
 * every device-state change, and the full ledger underneath, with
 * its chain verification stated plainly.
 *
 * Everything on this screen is a read of the same core the chat
 * reads. There is no second copy of the data to drift.
 *
 * SMOOTHNESS-ADDENDUM rework (2026-10-28): the previous build did
 * everything on the main thread, on a 3-second loop — recomputing
 * chain verification (a SHA-256 per event) and rebuilding every
 * card whether or not anything had changed. On a device holding
 * weeks of data that is precisely an ANR: slow, glitchy, then
 * "isn't responding". Now: reads are prepared off the main thread,
 * the screen rebuilds only when the record actually changed, chain
 * verification runs in the background and lands when it lands, and
 * the Answers surface is composed prose with quiet captions — no
 * version chips, no settle badges, no machinery.
 */
class VaultActivity : AppCompatActivity() {

    private enum class Tab(val label: String) {
        ANSWERS("Answers"),
        INBOX("Inbox"),
        HEALTH("Health"),
        MONEY("Money"),
        LOCATION("Places"),
        MOTION("Motion"),
        DEVICE("Device"),
        LEDGER("Ledger"),
    }

    private var tab: Tab = Tab.ANSWERS
    private lateinit var tabRow: LinearLayout
    private lateinit var list: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val app: GuardianLinkV2App get() = application as GuardianLinkV2App

    /** Off-main-thread preparation: filtering, copying, verifying. */
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "gl-vault-io").apply { isDaemon = true }
    }

    /** What the screen currently shows, as a change signature. */
    @Volatile
    private var renderedSignature: String = ""

    /** Monotonic render token: a stale background read never overwrites a newer tab. */
    @Volatile
    private var renderToken: Int = 0

    /** Chain verification, computed in the background; null until it lands. */
    @Volatile
    private var integrity: Map<String, Any?>? = null

    @Volatile
    private var integrityCheckedAtMs: Long = 0L

    private val refreshLoop = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 3_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val palette = GlPrefs.palette(this)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.headerBg)
            elevation = UiKit.dp(this@VaultActivity, 4).toFloat()
            setPadding(UiKit.dp(this@VaultActivity, 16), UiKit.dp(this@VaultActivity, 14), UiKit.dp(this@VaultActivity, 16), UiKit.dp(this@VaultActivity, 14))
            addView(TextView(this@VaultActivity).apply {
                text = "Data vault"
                textSize = 20f
                setTextColor(palette.headerText)
            })
            addView(TextView(this@VaultActivity).apply {
                text = "Everything tracked. Nothing hidden. Raw metrics live here — the main page stays conversation."
                textSize = 13f
                setTextColor(palette.headerSub)
                setPadding(0, 6, 0, 0)
            })
        }

        // Segmented control: a pill track with the active tab filled.
        tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = UiKit.rounded(this@VaultActivity, palette.chipBg, 999f)
            setPadding(UiKit.dp(this@VaultActivity, 4), UiKit.dp(this@VaultActivity, 4), UiKit.dp(this@VaultActivity, 4), UiKit.dp(this@VaultActivity, 4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                setMargins(UiKit.dp(this@VaultActivity, 14), UiKit.dp(this@VaultActivity, 12), UiKit.dp(this@VaultActivity, 14), UiKit.dp(this@VaultActivity, 2))
            }
        }

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiKit.dp(this@VaultActivity, 14), UiKit.dp(this@VaultActivity, 10), UiKit.dp(this@VaultActivity, 14), UiKit.dp(this@VaultActivity, 24))
        }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            addView(list)
        }

        val nav = UiKit.bottomNav(this, palette, "vault") { target ->
            when (target) {
                "chat" -> finish()
                "settings" -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    finish()
                }
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.pageBg)
            addView(header)
            addView(tabRow)
            addView(scroll)
            addView(nav)
        }
        setContentView(root)
        renderTabs()
        render()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshLoop)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshLoop)
        super.onPause()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------------

    private fun renderTabs() {
        val palette = GlPrefs.palette(this)
        tabRow.removeAllViews()
        for (t in Tab.values()) {
            val selected = t == tab
            tabRow.addView(TextView(this).apply {
                text = t.label
                textSize = 11.5f
                gravity = Gravity.CENTER
                setTextColor(if (selected) Color.WHITE else palette.chipText)
                if (selected) background = UiKit.rounded(this@VaultActivity, palette.userBubble, 999f)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    UiKit.dp(this@VaultActivity, 36),
                    1f,
                )
                setOnClickListener {
                    tab = t
                    renderedSignature = ""
                    renderTabs()
                    render()
                }
            })
        }
    }

    /**
     * A cheap fingerprint of what would be drawn: the tab, the
     * ledger's size and head, and the standing answers' versions.
     * When it hasn't changed, the screen is already correct and the
     * refresh loop does nothing — no rebuild, no re-layout, no jank.
     */
    private fun signature(): String = synchronized(app.coreLock) {
        val events = app.core.ledger.events()
        val head = events.lastOrNull()?.hash ?: "empty"
        tab.name + "|" + events.size + "|" + head + "|" +
            app.core.snapshot().joinToString(",") { "${it.id}:${it.current.version}" }
    }

    private fun render() {
        val sig = signature()
        if (sig == renderedSignature) return
        renderedSignature = sig
        val palette = GlPrefs.palette(this)
        when (tab) {
            Tab.ANSWERS -> renderAnswers(palette)
            Tab.INBOX -> renderEventsAsync(palette, "Nothing in the inbox yet — connect a mailbox in Settings, grant notification access, and mail context lands here.") {
                it.topic.startsWith("email") || it.topic.startsWith("inbox") || it.topic.startsWith("calendar")
            }
            Tab.HEALTH -> renderEventsAsync(palette, "No health readings yet — grant Health Connect access in Settings → Permissions and readings from your watch and phone land here.") {
                it.topic.startsWith("health")
            }
            Tab.MONEY -> renderEventsAsync(palette, "No money movement on record yet — bank and payment alerts arrive through notification access, and receipts through your connected mailbox.") {
                it.topic.startsWith("finance")
            }
            // Places shows place prose. The raw fixes (coordinates,
            // accuracy, provider) are metrics: they live behind the
            // Ledger tab only — nowhere else in the app (§3).
            Tab.LOCATION -> renderEventsAsync(palette, "No location records yet — start sensing with location granted and fixes will land here.") {
                it.topic == "location"
            }
            Tab.MOTION -> renderEventsAsync(palette, "No motion records yet — start sensing and move the phone.") {
                it.topic == "state.motion"
            }
            Tab.DEVICE -> renderEventsAsync(palette, "No device-state records yet — start sensing.") {
                it.topic.startsWith("state.") && it.topic != "state.motion"
            }
            Tab.LEDGER -> renderEventsAsync(palette, "The ledger is empty.") { true }
        }
    }

    private fun renderAnswers(palette: GlPrefs.Palette) {
        // The snapshot is the questions' CACHED currents — reading
        // it is cheap and always was. What was never cheap is what
        // used to ride along with it here: full chain verification
        // on every 3-second render. That now runs in the background
        // (see kickIntegrityCheck) and lands when it lands.
        val (answers, collectors) = synchronized(app.coreLock) {
            Pair(app.core.snapshot(), app.core.collectors.all())
        }
        list.removeAllViews()
        for (q in answers) {
            val card = UiKit.cardView(this, palette, 14f)
            card.addView(TextView(this).apply {
                // A quiet caption, in the question's own words — not
                // a shouted header, no version chip, no settle badge.
                text = q.text
                textSize = 11.5f
                setTextColor(palette.textSecondary)
            })
            card.addView(TextView(this).apply {
                text = q.current.text
                textSize = 15f
                setTextColor(palette.textPrimary)
                setPadding(0, UiKit.dp(this@VaultActivity, 4), 0, 0)
            })
            card.addView(TextView(this).apply {
                text = "Updated ${shortTime(q.current.evaluatedAt)}"
                textSize = 11f
                setTextColor(palette.textSecondary)
                setPadding(0, UiKit.dp(this@VaultActivity, 6), 0, 0)
            })
            list.addView(card)
        }

        sectionTitle("Collectors", palette)
        val collectorsCard = UiKit.cardView(this, palette, 14f)
        if (collectors.isEmpty()) {
            collectorsCard.addView(bodyText("No collectors registered.", palette))
        }
        for (c in collectors) {
            val reason = c.statusReason?.let { " — $it" } ?: ""
            collectorsCard.addView(bodyText("• ${c.name}: ${c.status.wire}$reason", palette))
        }
        list.addView(collectorsCard)

        sectionTitle("Record integrity", palette)
        val integrityCard = UiKit.cardView(this, palette, 14f)
        val current = integrity
        if (current == null) {
            integrityCard.addView(bodyText("Checking the chains…", palette))
        } else {
            val ledgerOk = current["ledgerChain"] as? Boolean
            val auditOk = current["auditChain"] as? Boolean
            integrityCard.addView(bodyText("• Ledger chain: ${verdict(ledgerOk)}", palette))
            integrityCard.addView(bodyText("• Audit chain: ${verdict(auditOk)}", palette))
            integrityCard.addView(bodyText("• Events on record: ${current["events"]} · audit entries: ${current["auditEntries"]}", palette))
        }
        list.addView(integrityCard)
        kickIntegrityCheck()
    }

    /**
     * Verify both chains OFF the render path, at most once a minute,
     * and repaint just this screen when the result lands (through
     * the signature gate: the next refresh picks it up only if the
     * Answers tab is showing and nothing newer replaced it).
     */
    private fun kickIntegrityCheck() {
        val nowMs = System.currentTimeMillis()
        if (integrity != null && nowMs - integrityCheckedAtMs < 60_000) return
        if (integrityCheckedAtMs != 0L && nowMs - integrityCheckedAtMs < 5_000) return
        integrityCheckedAtMs = nowMs
        val token = renderToken
        io.execute {
            val result = synchronized(app.coreLock) { app.core.verifyIntegrity() }
            integrity = result
            integrityCheckedAtMs = System.currentTimeMillis()
            runOnUiThread {
                if (!isFinishing && !isDestroyed && token == renderToken && tab == Tab.ANSWERS) {
                    renderedSignature = ""
                    render()
                }
            }
        }
    }

    /**
     * Event tabs: the read (copy + filter + cap) happens on the io
     * thread; only view construction touches the main thread, and
     * only for the newest render token.
     */
    private fun renderEventsAsync(palette: GlPrefs.Palette, emptyText: String, keep: (EvidenceEvent) -> Boolean) {
        val token = ++renderToken
        val cap = if (tab == Tab.LEDGER) 120 else 80
        io.execute {
            val events = synchronized(app.coreLock) { app.core.ledger.events() }
                .filter(keep)
                .asReversed()
                .take(cap)
            runOnUiThread {
                if (isFinishing || isDestroyed || token != renderToken) return@runOnUiThread
                list.removeAllViews()
                if (events.isEmpty()) {
                    emptyLine(emptyText, palette)
                    return@runOnUiThread
                }
                for (e in events) {
                    val card = UiKit.cardView(this@VaultActivity, palette, 12f)
                    card.setPadding(
                        UiKit.dp(this@VaultActivity, 14), UiKit.dp(this@VaultActivity, 10),
                        UiKit.dp(this@VaultActivity, 14), UiKit.dp(this@VaultActivity, 10),
                    )
                    (card.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, UiKit.dp(this@VaultActivity, 8))
                    card.addView(TextView(this@VaultActivity).apply {
                        text = e.claim
                        textSize = 14f
                        setTextColor(palette.textPrimary)
                    })
                    card.addView(TextView(this@VaultActivity).apply {
                        text = "${shortTime(e.assertedAt)} · ${e.topic} · ${e.source} · confidence ${(e.confidence * 100).toInt()}%"
                        textSize = 11.5f
                        setTextColor(palette.textSecondary)
                        setPadding(0, UiKit.dp(this@VaultActivity, 3), 0, 0)
                    })
                    list.addView(card)
                }
            }
        }
    }

    private fun bodyText(text: String, palette: GlPrefs.Palette): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(palette.textPrimary)
        setPadding(0, UiKit.dp(this@VaultActivity, 3), 0, UiKit.dp(this@VaultActivity, 3))
    }

    // ------------------------------------------------------------------

    private fun sectionTitle(text: String, palette: GlPrefs.Palette) {
        list.addView(TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(GlPrefs.accentColor(this@VaultActivity))
            setPadding(0, 36, 0, 6)
        })
    }

    private fun emptyLine(text: String, palette: GlPrefs.Palette) {
        list.addView(TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(palette.textSecondary)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 48, 0, 0)
        })
    }

    private fun verdict(ok: Boolean?): String = when (ok) {
        true -> "verified ✓"
        false -> "FAILED ✗ — chain does not verify"
        null -> "unknown"
    }

    private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm:ss")

    private fun shortTime(iso: String): String = try {
        Instant.parse(iso).atZone(ZoneId.systemDefault()).format(timeFormat)
    } catch (e: Exception) {
        iso
    }
}
