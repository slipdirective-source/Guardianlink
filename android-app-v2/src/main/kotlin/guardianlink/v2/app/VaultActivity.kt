package guardianlink.v2.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import guardianlink.v2.core.Delta
import guardianlink.v2.core.EvidenceEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
 */
class VaultActivity : AppCompatActivity() {

    private enum class Tab(val label: String) {
        ANSWERS("Answers"),
        LOCATION("Location"),
        MOTION("Motion"),
        DEVICE("Device"),
        LEDGER("Ledger"),
    }

    private var tab: Tab = Tab.ANSWERS
    private lateinit var tabRow: LinearLayout
    private lateinit var list: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val app: GuardianLinkV2App get() = application as GuardianLinkV2App

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

    // ------------------------------------------------------------------

    private fun renderTabs() {
        val palette = GlPrefs.palette(this)
        tabRow.removeAllViews()
        for (t in Tab.values()) {
            val selected = t == tab
            tabRow.addView(TextView(this).apply {
                text = t.label
                textSize = 13f
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
                    renderTabs()
                    render()
                }
            })
        }
    }

    private fun render() {
        val palette = GlPrefs.palette(this)
        list.removeAllViews()
        when (tab) {
            Tab.ANSWERS -> renderAnswers(palette)
            Tab.LOCATION -> renderEvents(palette, "No location records yet — start sensing with location granted and fixes will land here.") {
                it.topic == "location" || it.topic == "location.fix"
            }
            Tab.MOTION -> renderEvents(palette, "No motion records yet — start sensing and move the phone.") {
                it.topic == "state.motion"
            }
            Tab.DEVICE -> renderEvents(palette, "No device-state records yet — start sensing.") {
                it.topic.startsWith("state.") && it.topic != "state.motion"
            }
            Tab.LEDGER -> renderEvents(palette, "The ledger is empty.") { true }
        }
    }

    private fun renderAnswers(palette: GlPrefs.Palette) {
        val (answers, collectors, integrity) = synchronized(app.coreLock) {
            Triple(app.core.snapshot(), app.core.collectors.all(), app.core.verifyIntegrity())
        }
        for (q in answers) {
            val card = UiKit.cardView(this, palette, 14f)
            card.addView(TextView(this).apply {
                text = q.text.uppercase()
                textSize = 11.5f
                setTextColor(palette.textSecondary)
            })
            card.addView(TextView(this).apply {
                text = q.current.text
                textSize = 15f
                setTextColor(palette.textPrimary)
                setPadding(0, UiKit.dp(this@VaultActivity, 4), 0, 0)
            })
            val meta = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, UiKit.dp(this@VaultActivity, 8), 0, 0)
                addView(UiKit.deltaPill(this@VaultActivity, palette, q.current.delta))
                addView(TextView(this@VaultActivity).apply {
                    text = "  v${q.current.version} · updated ${shortTime(q.current.evaluatedAt)}"
                    textSize = 12f
                    setTextColor(palette.textSecondary)
                })
            }
            card.addView(meta)
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
        val ledgerOk = integrity["ledgerChain"] as? Boolean
        val auditOk = integrity["auditChain"] as? Boolean
        integrityCard.addView(bodyText("• Ledger chain: ${verdict(ledgerOk)}", palette))
        integrityCard.addView(bodyText("• Audit chain: ${verdict(auditOk)}", palette))
        integrityCard.addView(bodyText("• Events on record: ${integrity["events"]} · audit entries: ${integrity["auditEntries"]}", palette))
        list.addView(integrityCard)
    }

    private fun renderEvents(palette: GlPrefs.Palette, emptyText: String, keep: (EvidenceEvent) -> Boolean) {
        val events = synchronized(app.coreLock) { app.core.ledger.events() }
            .filter(keep)
            .asReversed()
            .take(if (tab == Tab.LEDGER) 120 else 80)
        if (events.isEmpty()) {
            emptyLine(emptyText, palette)
            return
        }
        for (e in events) {
            val card = UiKit.cardView(this, palette, 12f)
            card.setPadding(
                UiKit.dp(this, 14), UiKit.dp(this, 10),
                UiKit.dp(this, 14), UiKit.dp(this, 10),
            )
            (card.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, UiKit.dp(this, 8))
            card.addView(TextView(this).apply {
                text = e.claim
                textSize = 14f
                setTextColor(palette.textPrimary)
            })
            card.addView(TextView(this).apply {
                text = "${shortTime(e.assertedAt)} · ${e.topic} · ${e.source} · confidence ${(e.confidence * 100).toInt()}%"
                textSize = 11.5f
                setTextColor(palette.textSecondary)
                setPadding(0, UiKit.dp(this@VaultActivity, 3), 0, 0)
            })
            list.addView(card)
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
            this.text = text.uppercase()
            textSize = 13f
            setTextColor(GlPrefs.accentColor(this@VaultActivity))
            setPadding(0, 36, 0, 6)
        })
    }

    private fun line(text: String, palette: GlPrefs.Palette) {
        list.addView(TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(palette.textPrimary)
            setPadding(0, 4, 0, 4)
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

    private fun plainDelta(delta: Delta): String = when (delta) {
        Delta.DISTINGUISHED -> "settled"
        Delta.EQUIVALENT -> "confirmed"
        Delta.UNKNOWN -> "not settled yet"
        Delta.INAPPLICABLE -> "doesn't apply"
    }

    private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm:ss")

    private fun shortTime(iso: String): String = try {
        Instant.parse(iso).atZone(ZoneId.systemDefault()).format(timeFormat)
    } catch (e: Exception) {
        iso
    }

    private fun roundedBg(color: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = 20f * resources.displayMetrics.density
        }
}
