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
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.io.File
import kotlin.system.exitProcess

/**
 * Settings — the app's one hub for everything that isn't the
 * conversation itself, the way every normal app lays it out:
 * appearance, behavior, permissions (one level down, where they
 * belong), data, and about. Every control here is wired to real
 * behavior; nothing is a decorative toggle.
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
            setPadding(36, 32, 36, 32)
        }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            addView(root)
        }
        setContentView(scroll)
        build()
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun build() {
        val palette = GlPrefs.palette(this)
        root.setBackgroundColor(palette.pageBg)
        root.removeAllViews()

        root.addView(TextView(this).apply {
            text = "Settings"
            textSize = 22f
            setTextColor(palette.textPrimary)
            setPadding(0, 0, 0, 8)
        })

        // ---------------- Appearance ----------------
        section("Appearance")
        label("Theme")
        val night = GlPrefs.nightMode(this)
        root.addView(buttonRow(
            listOf("System" to 0, "Light" to 1, "Dark" to 2).map { (name, mode) ->
                (if (mode == night) "✓ $name" else name) to {
                    GlPrefs.setNightMode(this, mode)
                    recreate()
                }
            },
        ))
        label("Accent color")
        val accent = GlPrefs.accentName(this)
        for ((name, color) in GlPrefs.ACCENTS) {
            root.addView(Button(this).apply {
                text = if (name == accent) "✓ $name" else name
                isAllCaps = false
                setTextColor(Color.WHITE)
                background = roundedBg(color)
                setOnClickListener {
                    GlPrefs.setAccent(this@SettingsActivity, name)
                    build()
                }
            })
        }
        label("Chat text size")
        val size = GlPrefs.textSize(this)
        root.addView(buttonRow(
            listOf("Small" to 0, "Default" to 1, "Large" to 2).map { (name, value) ->
                (if (value == size) "✓ $name" else name) to {
                    GlPrefs.setTextSize(this, value)
                    build()
                }
            },
        ))

        // ---------------- Behavior ----------------
        section("Behavior")
        root.addView(Button(this).apply {
            text = "Start sensing when the app opens: ${if (GlPrefs.autoSense(this@SettingsActivity)) "On" else "Off"}"
            isAllCaps = false
            setOnClickListener {
                GlPrefs.setAutoSense(this@SettingsActivity, !GlPrefs.autoSense(this@SettingsActivity))
                build()
            }
        })
        root.addView(Button(this).apply {
            text = "Send a checkup notification now"
            isAllCaps = false
            setOnClickListener {
                MetricsNotifier.post(this@SettingsActivity, app)
                Toast.makeText(this@SettingsActivity, "Checkup sent — pull down your notifications.", Toast.LENGTH_SHORT).show()
            }
        })
        note("While sensing runs, a checkup also posts on its own whenever an answer changes — bullet points, at most one a minute.")
        label("Summon from anywhere")
        assistantBlock()

        // ---------------- Permissions ----------------
        section("Permissions")
        note(permissionsSummary())
        root.addView(Button(this).apply {
            text = "Open permissions →"
            isAllCaps = false
            setOnClickListener {
                startActivity(Intent(this@SettingsActivity, PermissionsActivity::class.java))
            }
        })

        // ---------------- Data ----------------
        section("Data")
        root.addView(Button(this).apply {
            text = "Copy diagnostics"
            isAllCaps = false
            setOnClickListener { copyDiagnostics() }
        })
        root.addView(Button(this).apply {
            text = "Erase everything"
            isAllCaps = false
            setTextColor(Color.parseColor("#B3261E"))
            setOnClickListener { confirmErase() }
        })
        note("Erase deletes the encrypted state, the audit anchors, and the clock file from this phone, then restarts the app empty. There is no undo.")

        // ---------------- About ----------------
        section("About")
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
        note("GuardianLink v0.2 (version $version)")
        note(app.engineStatus)
        val keyId = synchronized(app.coreLock) { app.core.operatorKeyId }
        note("Operator key: ${keyId?.take(16) ?: "not bound yet"}…")
    }

    // ------------------------------------------------------------------

    /**
     * The assistant role: the sanctioned way an app becomes the
     * thing the system summon opens from anywhere. The literal words
     * "OK Google" stay Google's — Android offers no app a claim on
     * them, and this screen says so instead of pretending. What the
     * role gives is the gesture: long-press home or power (or the
     * corner swipe) opens GuardianLink already listening, over any
     * app, and spoken words run through the same pipeline as typed.
     */
    private fun assistantBlock() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            when {
                roleManager == null || !roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT) ->
                    note("This phone doesn't expose the assistant role to apps.")
                roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT) ->
                    note("GuardianLink is your default assistant ✓ — summon it with your phone's assistant gesture from any app and it opens already listening.")
                else -> {
                    root.addView(Button(this).apply {
                        text = "Make GuardianLink my assistant"
                        isAllCaps = false
                        setOnClickListener {
                            assistantRoleLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT))
                        }
                    })
                    note("As your default assistant, GuardianLink takes the system summon (long-press home or power, or the corner swipe) from any app — no trip back to the home screen.")
                }
            }
        } else {
            root.addView(Button(this).apply {
                text = "Open assistant settings"
                isAllCaps = false
                setOnClickListener {
                    startActivity(Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS))
                }
            })
        }
        note("One honest limit: the words \"OK Google\" belong to Google's assistant and no other app can claim them. The summon gesture is the route Android actually gives — the words you'd speak after it are yours.")
    }

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
        return "$on of $total core grants are on. Each one has its own pathway — what it's for, its state, and the shortest route to change it."
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

    // ------------------------------------------------------------------

    private fun section(title: String) {
        root.addView(TextView(this).apply {
            text = title.uppercase()
            textSize = 13f
            setTextColor(GlPrefs.accentColor(this@SettingsActivity))
            setPadding(0, 36, 0, 6)
        })
    }

    private fun label(text: String) {
        root.addView(TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(GlPrefs.palette(this@SettingsActivity).textSecondary)
            setPadding(0, 12, 0, 4)
        })
    }

    private fun note(text: String) {
        root.addView(TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(GlPrefs.palette(this@SettingsActivity).textSecondary)
            setPadding(0, 6, 0, 6)
        })
    }

    private fun buttonRow(options: List<Pair<String, () -> Unit>>): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((labelText, action) in options) {
            row.addView(Button(this).apply {
                text = labelText
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { action() }
            })
        }
        return row
    }

    private fun roundedBg(color: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = 24f * resources.displayMetrics.density
        }
}
