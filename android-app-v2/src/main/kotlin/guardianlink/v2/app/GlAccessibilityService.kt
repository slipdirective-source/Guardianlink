package guardianlink.v2.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * The fingers.
 *
 * An accessibility service is the one sanctioned way Android lets
 * an app act inside other apps: read the window, tap, type,
 * scroll, go back/home. This service does NOTHING on its own — it
 * holds no event logic and starts no work. It exists so that
 * [DeviceControl], called only after a governed proposal clears
 * policy and the sovereign's assent, has hands to execute with.
 *
 * Enablement is the sovereign's, in system settings (Settings →
 * Permissions → Device control in-app names the pathway). Android
 * shows its own warning there; the service description repeats
 * the posture: act only when commanded, record every outcome.
 */
class GlAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Deliberately inert: this service observes nothing on its
        // own initiative. Reads happen only when DeviceControl is
        // invoked by an assented action.
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    companion object {
        @Volatile
        var instance: GlAccessibilityService? = null
            private set

        fun isEnabled(): Boolean = instance != null
    }
}
