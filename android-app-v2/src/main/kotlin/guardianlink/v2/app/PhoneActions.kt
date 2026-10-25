package guardianlink.v2.app

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import guardianlink.v2.core.ActionProposal
import guardianlink.v2.core.DeviceOutcome
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.mail.Message
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/**
 * The phone's effectors: the device-action handler the core calls
 * AFTER policy and assent have cleared a proposal. This object owns
 * only the effect — placing the call, sending the text, sending the
 * mail — and reports what it observed back as the outcome. Every
 * refusal is explicit (missing permission, unknown contact, no
 * connected mailbox): a failed effect is an observed failure in the
 * record, never a fake success.
 *
 * Contact resolution reads the phone's contacts under READ_CONTACTS;
 * a name that matches several people fails loudly with the matches
 * named, rather than guessing which one the sovereign meant.
 */
object PhoneActions {

    fun handle(context: Context, proposal: ActionProposal): DeviceOutcome {
        val appContext = context.applicationContext
        if (proposal.actionType.startsWith("device_") || proposal.actionType == "read_screen") {
            return DeviceControl.handle(appContext, proposal)
        }
        return when (proposal.actionType) {
            "phone_call" -> call(appContext, proposal.parameters["to"] as? String ?: proposal.target)
            "send_text" -> text(
                appContext,
                proposal.parameters["to"] as? String ?: proposal.target,
                proposal.parameters["body"] as? String ?: "",
            )
            "send_email" -> email(
                appContext,
                proposal.parameters["to"] as? String ?: proposal.target,
                proposal.parameters["subject"] as? String ?: "Message from GuardianLink",
                proposal.parameters["body"] as? String ?: "",
            )
            else -> throw IllegalStateException("not a device action: ${proposal.actionType}")
        }
    }

    // ------------------------------------------------------------------

    private fun call(context: Context, who: String): DeviceOutcome {
        val number = resolveNumber(context, who)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            throw IllegalStateException(
                "Placing the call directly needs the phone-call grant — it's in Settings → Permissions. " +
                    "The number is ready: $number.",
            )
        }
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(number)}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return DeviceOutcome("Calling $who — the call screen is up.", mapOf("number" to number))
    }

    private fun text(context: Context, who: String, body: String): DeviceOutcome {
        if (body.isBlank()) throw IllegalStateException("The text has no body — nothing to send.")
        val number = resolveNumber(context, who)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            throw IllegalStateException(
                "Sending a text needs the text-messages grant — it's in Settings → Permissions.",
            )
        }
        val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        } ?: throw IllegalStateException("No SMS manager is available on this phone.")

        val action = "guardianlink.v2.app.SMS_SENT"
        val latch = CountDownLatch(1)
        val results = intArrayOf(Int.MIN_VALUE)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                results[0] = resultCode
                latch.countDown()
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, IntentFilter(action))
        }
        try {
            val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val sentIntent = PendingIntent.getBroadcast(context, who.hashCode(), Intent(action), piFlags)
            val parts = sms.divideMessage(body)
            if (parts.size <= 1) {
                sms.sendTextMessage(number, null, body, sentIntent, null)
            } else {
                // One sent-intent per part (the platform signature
                // takes no nulls); the latch resolves on the first
                // carrier answer.
                val intents = ArrayList<PendingIntent>()
                for (i in parts.indices) {
                    intents.add(
                        PendingIntent.getBroadcast(context, who.hashCode() + i, Intent(action), piFlags),
                    )
                }
                sms.sendMultipartTextMessage(number, null, parts, intents, null)
            }
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw IllegalStateException("The carrier gave no delivery confirmation within 15 seconds — the send is unconfirmed.")
            }
            if (results[0] != android.app.Activity.RESULT_OK) {
                throw IllegalStateException("The carrier refused the text (result ${results[0]}).")
            }
        } finally {
            try { context.unregisterReceiver(receiver) } catch (e: Exception) { /* already gone */ }
        }
        return DeviceOutcome("Text sent to $who: \"$body\"", mapOf("number" to number))
    }

    private fun email(context: Context, to: String, subject: String, body: String): DeviceOutcome {
        val account = EmailAccountStore(context).load()
            ?: throw IllegalStateException("No mailbox is connected — connect one in Settings → Connected email first.")
        val smtpHost = when {
            account.host.contains("gmail") -> "smtp.gmail.com"
            account.host.contains("office365") || account.host.contains("outlook") -> "smtp.office365.com"
            account.host.contains("yahoo") -> "smtp.mail.yahoo.com"
            account.host.startsWith("imap.") -> "smtp." + account.host.removePrefix("imap.")
            account.host.startsWith("imap") -> "smtp" + account.host.removePrefix("imap")
            else -> account.host
        }
        val props = Properties().apply {
            put("mail.smtp.host", smtpHost)
            put("mail.smtp.port", "587")
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.connectiontimeout", "15000")
            put("mail.smtp.timeout", "25000")
        }
        val session = Session.getInstance(props)
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(account.address))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
            setSubject(subject)
            setText(body)
        }
        Transport.send(message, account.address, account.password)
        return DeviceOutcome("Email sent to $to from ${account.address} — \"$subject\".")
    }

    // ------------------------------------------------------------------

    /**
     * A phone number directly, or a contact name resolved against
     * the phone's contacts (exact-then-prefix display-name match).
     */
    private fun resolveNumber(context: Context, who: String): String {
        val trimmed = who.trim()
        val digits = trimmed.filter { it.isDigit() || it == '+' }
        if (digits.count { it.isDigit() } >= 7 && trimmed.all { it.isDigit() || it in "+-(). " }) {
            return trimmed
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            throw IllegalStateException(
                "\"$who\" is a name, and looking it up needs the contacts grant — it's in Settings → Permissions. " +
                    "Or say the number directly.",
            )
        }
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val matches = ArrayList<Pair<String, String>>()
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0) ?: continue
                val number = cursor.getString(1) ?: continue
                if (name.equals(trimmed, ignoreCase = true) || name.startsWith(trimmed, ignoreCase = true)) {
                    matches += name to number
                }
            }
        }
        val distinct = matches.distinctBy { it.second.filter { c -> c.isDigit() } }
        return when {
            distinct.isEmpty() -> throw IllegalStateException("No contact matches \"$who\" on this phone.")
            distinct.size == 1 -> distinct[0].second
            else -> throw IllegalStateException(
                "\"$who\" matches ${distinct.size} numbers (${distinct.joinToString(", ") { "${it.first} ${it.second}" }}) — say which one, or give the number.",
            )
        }
    }
}
