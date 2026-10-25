package guardianlink.v2.app

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Permissions pathways.
 *
 * GuardianLink runs on the phone's hardware, and every capability it
 * uses is a grant the sovereign gives — or doesn't. This screen is
 * the pathway for each one: what it is, why the system wants it, its
 * current state, and the shortest honest route to change that state
 * (a runtime prompt where Android allows one, the system Settings
 * screen where it doesn't). Nothing here requests anything silently,
 * and a denial is a respected answer: the affected collector names
 * itself unavailable in the Now card instead of working around it.
 */
class PermissionsActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val palette = GlPrefs.palette(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 28, 32, 28)
            setBackgroundColor(palette.pageBg)
        }
        root.addView(TextView(this).apply {
            text = "Permissions"
            textSize = 20f
            setTextColor(palette.textPrimary)
        })
        root.addView(TextView(this).apply {
            text = "Everything GuardianLink can sense is a grant from you. " +
                "Turn one off and the system names it unavailable — it never guesses around a no."
            textSize = 13f
            setTextColor(palette.textSecondary)
            setPadding(0, 8, 0, 16)
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            addView(list)
        }
        root.addView(scroll)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        buildRows()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        CollectorAvailability.refresh(application as GuardianLinkV2App)
        buildRows()
    }

    // ------------------------------------------------------------------

    private data class Row(
        val title: String,
        val why: String,
        val granted: Boolean,
        val statusText: String,
        val actionLabel: String?,
        val action: (() -> Unit)?,
    )

    private fun buildRows() {
        val palette = GlPrefs.palette(this)
        list.removeAllViews()
        for (row in rows()) {
            val panel = UiKit.cardView(this, palette, 14f)
            val titleRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(TextView(this@PermissionsActivity).apply {
                    text = row.title
                    textSize = 16f
                    setTextColor(palette.textPrimary)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(UiKit.grantPill(this@PermissionsActivity, palette, row.granted))
            }
            panel.addView(titleRow)
            panel.addView(TextView(this).apply {
                text = row.why
                textSize = 13f
                setTextColor(palette.textSecondary)
                setPadding(0, UiKit.dp(this@PermissionsActivity, 4), 0, 0)
            })
            panel.addView(TextView(this).apply {
                text = row.statusText
                textSize = 13f
                setTextColor(palette.textSecondary)
                setPadding(0, UiKit.dp(this@PermissionsActivity, 4), 0, 0)
            })
            if (row.action != null && row.actionLabel != null) {
                panel.addView(Button(this).apply {
                    text = row.actionLabel
                    isAllCaps = false
                    setTextColor(Color.WHITE)
                    background = UiKit.rounded(this@PermissionsActivity, palette.userBubble, 999f)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        UiKit.dp(this@PermissionsActivity, 42),
                    ).apply { setMargins(0, UiKit.dp(this@PermissionsActivity, 10), 0, 0) }
                    setOnClickListener { row.action.invoke() }
                })
            }
            list.addView(panel)
        }
    }

    private fun rows(): List<Row> {
        val out = mutableListOf<Row>()

        // -- Location (foreground) ------------------------------------
        val fgLocation = granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        out += Row(
            title = "Location",
            why = "Lets Position answer where you are, from the phone itself.",
            granted = fgLocation,
            statusText = if (fgLocation) "On" else "Off — Position names location unavailable.",
            actionLabel = if (fgLocation) null else "Allow",
            action = if (fgLocation) null else ({
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    REQ_LOCATION,
                )
            }),
        )

        // -- Location (background) ------------------------------------
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bgLocation = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            out += Row(
                title = "Location, all the time",
                why = "Keeps Position honest after the app closes. Android only grants this from Settings: " +
                    "Permissions → Location → “Allow all the time”.",
                granted = bgLocation,
                statusText = when {
                    bgLocation -> "On"
                    !fgLocation -> "Needs Location first (above)."
                    else -> "Off — sensing only answers while the app is in front of you."
                },
                actionLabel = if (bgLocation) null else "Open Settings",
                action = if (bgLocation) null else ({ openAppDetails() }),
            )
        }

        // -- Microphone ------------------------------------------------
        val mic = granted(Manifest.permission.RECORD_AUDIO)
        out += Row(
            title = "Microphone",
            why = "For voice assent — the governed-action check that compares your voice to the enrolled one. " +
                "Enrollment itself is a later step; this grant is what it will stand on.",
            granted = mic,
            statusText = if (mic) "On" else "Off — voice assent can't be offered.",
            actionLabel = if (mic) null else "Allow",
            action = if (mic) null else ({
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            }),
        )

        // -- Notifications ---------------------------------------------
        val notifications = NotificationManagerCompat.from(this).areNotificationsEnabled()
        val canRuntimeAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        out += Row(
            title = "Notifications",
            why = "While sensing runs in the background, Android requires a visible notice saying so. No notice, no sensing.",
            granted = notifications,
            statusText = if (notifications) "On" else "Off — background sensing can't start its notice.",
            actionLabel = if (notifications) null else if (canRuntimeAsk) "Allow" else "Open Settings",
            action = if (notifications) null else ({
                if (canRuntimeAsk) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
                } else {
                    openNotificationSettings()
                }
            }),
        )

        // -- Activity recognition --------------------------------------
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val motion = granted(Manifest.permission.ACTIVITY_RECOGNITION)
            out += Row(
                title = "Movement",
                why = "Activity recognition powers the motion collector — still, walking, in a vehicle — as evidence, not tracking for its own sake.",
                granted = motion,
                statusText = if (motion) "On" else "Off — the motion collector names itself unavailable.",
                actionLabel = if (motion) null else "Allow",
                action = if (motion) null else ({
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), REQ_MOTION)
                }),
            )
        }

        // -- Battery exemption -----------------------------------------
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryExempt = pm.isIgnoringBatteryOptimizations(packageName)
        out += Row(
            title = "Run without battery limits",
            why = "Samsung puts background apps to sleep hard. Exempting GuardianLink is what lets sensing survive the night with the screen off.",
            granted = batteryExempt,
            statusText = if (batteryExempt) "On" else "Off — sensing may be killed while the phone sleeps.",
            actionLabel = if (batteryExempt) null else "Open Settings",
            action = if (batteryExempt) null else ({ openBatterySettings() }),
        )

        // -- Usage access (optional, settings-only) --------------------
        val usage = usageAccessGranted()
        out += Row(
            title = "App usage (optional)",
            why = "A future collector: which apps you use, as context. Off by default, granted only in Settings, and the system does fine without it.",
            granted = usage,
            statusText = if (usage) "On" else "Off — nothing collects this.",
            actionLabel = if (usage) null else "Open Settings",
            action = if (usage) null else ({
                try {
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                } catch (e: Exception) {
                    openAppDetails()
                }
            }),
        )

        return out
    }

    // ------------------------------------------------------------------

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun usageAccessGranted(): Boolean = try {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        mode == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) {
        false
    }

    private fun openAppDetails() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            },
        )
    }

    private fun openNotificationSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                },
            )
        } catch (e: Exception) {
            openAppDetails()
        }
    }

    private fun openBatterySettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                },
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(this, "Open Settings → Apps → GuardianLink → Battery → Unrestricted.", Toast.LENGTH_LONG).show()
                openAppDetails()
            }
        }
    }

    companion object {
        private const val REQ_LOCATION = 3101
        private const val REQ_MIC = 3103
        private const val REQ_NOTIFICATIONS = 3104
        private const val REQ_MOTION = 3105
    }
}
