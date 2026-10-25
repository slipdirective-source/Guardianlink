package guardianlink.v2.app

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * The notification listener: context the phone already surfaces.
 *
 * Mail and messaging apps on this phone are signed in with the
 * sovereign's own accounts and sessions — cookies this app neither
 * has nor should reach for. What Android DOES offer, behind a
 * grant the sovereign controls, is the notification stream those
 * apps themselves post. This listener admits mail/message
 * notifications as evidence (topic "inbox.notification") through
 * the governed boundary: pause or deny the "notifications"
 * collector and the stream stops at the gate.
 *
 * Deliberately narrow: only known mail/messaging/calendar packages
 * or notifications the system itself categorised as email/message;
 * group summaries and ongoing/background notifications are skipped;
 * identical title+text inside ten minutes is admitted once.
 */
class GlNotificationListener : NotificationListenerService() {

    private val recent = HashMap<Int, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        try {
            val pkg = n.packageName ?: return
            if (pkg == packageName) return
            val notification = n.notification ?: return
            if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
            if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return
            val category = notification.category
            val isFinance = pkg in FINANCE_PACKAGES
            val interesting = isFinance || pkg in KNOWN_PACKAGES ||
                category == Notification.CATEGORY_EMAIL ||
                category == Notification.CATEGORY_MESSAGE
            if (!interesting) return

            val extras = notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT))
                ?.toString()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            if (title.isBlank() && text.isBlank()) return

            val key = (pkg + "|" + title + "|" + text).hashCode()
            val now = System.currentTimeMillis()
            val last = recent[key]
            if (last != null && now - last < DEDUPE_MS) return
            recent[key] = now
            if (recent.size > 200) recent.clear()

            val appLabel = labelFor(pkg)
            val claim = buildString {
                append(appLabel).append(" notification")
                if (title.isNotBlank()) append(": ").append(title)
                if (text.isNotBlank()) append(" — ").append(text)
            }.take(320)

            val app = application as? GuardianLinkV2App ?: return
            synchronized(app.coreLock) {
                if (isFinance) {
                    // Money movement gets its own collector and
                    // topic: a bank alert is a finance record, not
                    // inbox noise.
                    app.core.admitSensor("finances", "finance.alert", claim, confidence = 0.9)
                } else {
                    app.core.admitSensor("notifications", "inbox.notification", claim, confidence = 0.9)
                }
            }
            app.persistCore()
        } catch (refused: IllegalStateException) {
            // Collector paused/denied at the harness boundary: drop.
            Log.w(TAG, "notification evidence refused at the boundary: ${refused.message}")
        } catch (t: Throwable) {
            Log.w(TAG, "notification handling failed", t)
        }
    }

    private fun labelFor(pkg: String): String = try {
        val info = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(info).toString()
    } catch (e: Exception) {
        pkg.substringAfterLast('.')
    }

    companion object {
        private const val TAG = "GlV2Notify"
        private const val DEDUPE_MS = 10 * 60_000L

        private val KNOWN_PACKAGES = setOf(
            "com.google.android.gm", // Gmail
            "com.microsoft.office.outlook",
            "com.yahoo.mobile.client.android.mail",
            "ch.protonmail.android",
            "eu.faircode.email",
            "com.samsung.android.email.provider",
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "org.thoughtcrime.securesms", // Signal
            "com.whatsapp",
            "com.facebook.orca", // Messenger
            "org.telegram.messenger",
            "com.discord",
            "com.Slack",
            "com.google.android.calendar",
        )

        /** Banks, card networks, and payment apps whose alerts ARE the finance record. */
        private val FINANCE_PACKAGES = setOf(
            "com.chase.sig.android",
            "com.infonow.bofa",
            "com.bankofamerica.digitalwallet",
            "com.wf.wellsfargomobile",
            "com.citi.citimobile",
            "com.usaa.mobile.android.usaa",
            "com.americanexpress.android.acctsvcs.us",
            "com.discoverfinancial.mobile",
            "com.capitalone.mobile",
            "com.squareup.cash", // Cash App
            "com.paypal.android.p2pmobile",
            "com.venmo",
            "com.coinbase.android",
            "com.robinhood.android",
            "com.fidelity.android",
            "com.schwab.mobile.retail",
            "com.etrade.mobilepro.activity",
        )
    }
}
