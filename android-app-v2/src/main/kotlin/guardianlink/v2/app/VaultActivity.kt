package guardianlink.v2.app

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
            setPadding(36, 32, 36, 24)
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

        tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 12, 16, 4)
        }

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 32)
        }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            addView(list)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.pageBg)
            addView(header)
            addView(tabRow)
            addView(scroll)
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
            tabRow.addView(Button(this).apply {
                text = t.label
                isAllCaps = false
                textSize = 13f
                setTextColor(if (selected) Color.WHITE else palette.chipText)
                background = roundedBg(if (selected) palette.userBubble else palette.chipBg)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f,
                ).apply { setMargins(4, 2, 4, 2) }
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
            list.addView(TextView(this).apply {
                text = q.text
                textSize = 13f
                setTextColor(palette.textSecondary)
                setPadding(0, 18, 0, 0)
            })
            list.addView(TextView(this).apply {
                text = q.current.text
                textSize = 15f
                setTextColor(palette.textPrimary)
            })
            list.addView(TextView(this).apply {
                text = "${plainDelta(q.current.delta)} · version ${q.current.version} · updated ${shortTime(q.current.evaluatedAt)}"
                textSize = 12f
                setTextColor(palette.textSecondary)
            })
        }

        sectionTitle("Collectors", palette)
        if (collectors.isEmpty()) {
            emptyLine("No collectors registered.", palette)
        }
        for (c in collectors) {
            val reason = c.statusReason?.let { " — $it" } ?: ""
            line("• ${c.name}: ${c.status.wire}$reason", palette)
        }

        sectionTitle("Record integrity", palette)
        val ledgerOk = integrity["ledgerChain"] as? Boolean
        val auditOk = integrity["auditChain"] as? Boolean
        line("• Ledger chain: ${verdict(ledgerOk)}", palette)
        line("• Audit chain: ${verdict(auditOk)}", palette)
        line("• Events on record: ${integrity["events"]} · audit entries: ${integrity["auditEntries"]}", palette)
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
            list.addView(TextView(this).apply {
                text = e.claim
                textSize = 14f
                setTextColor(palette.textPrimary)
                setPadding(0, 14, 0, 0)
            })
            list.addView(TextView(this).apply {
                text = "${shortTime(e.assertedAt)} · ${e.topic} · ${e.source} · confidence ${(e.confidence * 100).toInt()}%"
                textSize = 12f
                setTextColor(palette.textSecondary)
            })
        }
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
