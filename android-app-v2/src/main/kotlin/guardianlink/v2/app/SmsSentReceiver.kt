package guardianlink.v2.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The carrier's answer to an SMS send, delivered to a MANIFEST
 * receiver — not a runtime-registered one.
 *
 * Why: the sent-confirmation PendingIntent is fired by the
 * telephony stack, and a runtime receiver registered
 * RECEIVER_NOT_EXPORTED never sees it on current Android — the
 * send went out (or not) but the confirmation had no lawful
 * door back in, so every send timed out as "unconfirmed"
 * (found on-device 2026-10-25, consistent on every attempt).
 * An explicit-component PendingIntent to a non-exported manifest
 * receiver is the canonical door: the PendingIntent carries this
 * app's identity, so the system delivers it here and nowhere else.
 *
 * The receiver itself is a postbox: it hands (token → resultCode)
 * to [PhoneActions], which holds the waiting latch. Tokens are
 * one-shot UUIDs minted per send.
 */
class SmsSentReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val token = intent.getStringExtra(EXTRA_TOKEN) ?: return
        PhoneActions.onSmsSentResult(token, resultCode)
    }

    companion object {
        const val EXTRA_TOKEN = "guardianlink.v2.app.extra.SMS_TOKEN"
    }
}
