package guardianlink.v2.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * The summon path — GuardianLink as the phone's assistant.
 *
 * The honest platform facts, stated once: the literal words
 * "OK Google" belong to Google's assistant, and Android gives no
 * other app a way to claim them. What Android DOES give is the
 * assistant role: when GuardianLink is the device's default
 * assistant, the system summon — long-press home or power, the
 * corner swipe, the headset action — from ANY app, over anything
 * else on screen, opens GuardianLink's voice session. That is the
 * sanctioned "agent, everywhere" path, and it is what these classes
 * implement.
 *
 * The session does not build a second interface. It hands off to
 * MainActivity with the voice-summon extra, and MainActivity opens
 * its speech recognizer into the same conversation pipeline typed
 * text uses: a spoken question is answered like a typed one, and a
 * spoken command that needs assent goes through the same governed
 * ceremony. One agent, one doorway — the summon just removes the
 * trip to the home screen.
 */
class GlVoiceInteractionService : VoiceInteractionService()

class GlSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = GlSession(this)
}

class GlSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_VOICE_SUMMON, true)
        }
        context.startActivity(intent)
        finish()
    }
}

/**
 * The assistant role requires a recognition service component. This
 * one does not pretend to listen: speech recognition in GuardianLink
 * is delegated to the platform's engine (the same one the keyboard
 * uses) from inside MainActivity, so this stub answers with an
 * error rather than fake-hearing. It exists to satisfy the role's
 * contract, named honestly.
 */
class GlRecognitionService : RecognitionService() {
    override fun onStartListening(intent: Intent?, listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback?) {}

    override fun onStopListening(listener: Callback?) {}
}
