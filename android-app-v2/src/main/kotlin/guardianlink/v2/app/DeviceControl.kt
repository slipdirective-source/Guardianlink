package guardianlink.v2.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import guardianlink.v2.core.ActionProposal
import guardianlink.v2.core.DeviceOutcome

/**
 * Device control: the governed handler for screen-level actions —
 * open an app, tap what's on screen, type into a field, press
 * back/home, read the screen aloud into the record.
 *
 * Like [PhoneActions], this owns only the effect and reports what
 * it observed. The hands are [GlAccessibilityService]; without the
 * sovereign's accessibility grant, every hand action stops and
 * names the pathway. Ambiguity (several matches) is a refusal with
 * the matches named — the system does not guess which button the
 * sovereign meant.
 */
object DeviceControl {

    fun handle(context: Context, proposal: ActionProposal): DeviceOutcome {
        val appContext = context.applicationContext
        return when (proposal.actionType) {
            "device_open_app" -> openApp(appContext, proposal.parameters["app"] as? String ?: proposal.target)
            "device_tap" -> tap(appContext, proposal.parameters["text"] as? String ?: proposal.target)
            "device_type" -> type(appContext, proposal.parameters["text"] as? String ?: "")
            "device_press" -> press(proposal.parameters["key"] as? String ?: proposal.target)
            "read_screen" -> readScreen(appContext)
            else -> throw IllegalStateException("not a device-control action: ${proposal.actionType}")
        }
    }

    private fun service(): GlAccessibilityService =
        GlAccessibilityService.instance
            ?: throw IllegalStateException(
                "Device control is not enabled — turn on GuardianLink under Settings → Permissions → Device control (accessibility) first. Nothing was touched.",
            )

    // ------------------------------------------------------------------

    private fun openApp(context: Context, appName: String): DeviceOutcome {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches = pm.queryIntentActivities(intent, 0)
            .mapNotNull { ri ->
                val label = ri.loadLabel(pm)?.toString() ?: return@mapNotNull null
                if (label.equals(appName, ignoreCase = true) || label.startsWith(appName, ignoreCase = true)) {
                    label to ri.activityInfo.packageName
                } else {
                    null
                }
            }
            .distinctBy { it.second }
        when {
            matches.isEmpty() -> throw IllegalStateException("No installed app matches \"$appName\".")
            matches.size > 1 -> throw IllegalStateException(
                "\"$appName\" matches ${matches.size} apps (${matches.joinToString(", ") { it.first }}) — say which one.",
            )
        }
        val (label, pkg) = matches[0]
        val launch = pm.getLaunchIntentForPackage(pkg)
            ?: throw IllegalStateException("$label has no launchable screen.")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        return DeviceOutcome("Opened $label.", mapOf("package" to pkg))
    }

    private fun tap(context: Context, text: String): DeviceOutcome {
        val svc = service()
        val root = svc.rootInActiveWindow
            ?: throw IllegalStateException("No active window to tap in.")
        val matches = ArrayList<AccessibilityNodeInfo>()
        collectMatches(root, text, matches)
        if (matches.isEmpty()) {
            throw IllegalStateException("Nothing on screen matches \"$text\" — say \"read my screen\" if you want me to look first.")
        }
        if (matches.size > 3) {
            throw IllegalStateException("${matches.size} things on screen match \"$text\" — be more specific.")
        }
        val node = matches[0]
        var target: AccessibilityNodeInfo? = node
        var hops = 0
        while (target != null && !target.isClickable && hops < 6) {
            target = target.parent
            hops++
        }
        if (target != null && target.isClickable) {
            if (!target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                throw IllegalStateException("Found \"$text\" but the tap was refused by the screen.")
            }
            return DeviceOutcome("Tapped \"$text\".")
        }
        // Not clickable up the tree: tap its center by gesture.
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) throw IllegalStateException("Found \"$text\" but it has no tappable area.")
        val path = Path().apply { moveTo(bounds.centerX().toFloat(), bounds.centerY().toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        if (!svc.dispatchGesture(gesture, null, null)) {
            throw IllegalStateException("The tap gesture could not be delivered.")
        }
        return DeviceOutcome("Tapped \"$text\" (gesture, at its on-screen position).")
    }

    private fun collectMatches(node: AccessibilityNodeInfo?, query: String, out: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        val label = node.text?.toString() ?: node.contentDescription?.toString()
        if (!label.isNullOrBlank() && label.contains(query, ignoreCase = true) && node.isVisibleToUser) {
            out += node
        }
        for (i in 0 until node.childCount) collectMatches(node.getChild(i), query, out)
    }

    private fun type(context: Context, text: String): DeviceOutcome {
        if (text.isBlank()) throw IllegalStateException("Nothing to type — the command carried no text.")
        val svc = service()
        val focused = svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val field = focused?.takeIf { it.isEditable }
            ?: findEditable(svc.rootInActiveWindow)
            ?: throw IllegalStateException("No text field is focused or visible — open the field first, then tell me to type.")
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            throw IllegalStateException("The field refused the text.")
        }
        return DeviceOutcome("Typed: \"$text\"")
    }

    private fun findEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            findEditable(node.getChild(i))?.let { return it }
        }
        return null
    }

    private fun press(key: String): DeviceOutcome {
        val svc = service()
        val (action, label) = when (key.lowercase()) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK to "Back"
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME to "Home"
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS to "Recents"
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS to "Notifications"
            else -> throw IllegalStateException("Unknown key: $key")
        }
        if (!svc.performGlobalAction(action)) {
            throw IllegalStateException("The $label press was refused by the system.")
        }
        return DeviceOutcome("Pressed $label.")
    }

    private fun readScreen(context: Context): DeviceOutcome {
        val svc = service()
        val root = svc.rootInActiveWindow
            ?: throw IllegalStateException("No active window to read.")
        val appLabel = try {
            val pkg = root.packageName?.toString()
            if (pkg == null) "the current app" else {
                val info = context.packageManager.getApplicationInfo(pkg, 0)
                context.packageManager.getApplicationLabel(info).toString()
            }
        } catch (e: Exception) {
            "the current app"
        }
        val fragments = ArrayList<String>()
        collectText(root, fragments)
        val text = fragments.distinct().joinToString(" · ").take(700)
        if (text.isBlank()) {
            return DeviceOutcome("On screen ($appLabel): no readable text — the window may be an image, a game, or a secure surface.")
        }
        return DeviceOutcome("On screen ($appLabel): $text")
    }

    private fun collectText(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        if (node == null || out.size >= 48) return
        val label = (node.text?.toString() ?: node.contentDescription?.toString())?.trim()
        if (!label.isNullOrBlank() && node.isVisibleToUser) out += label
        for (i in 0 until node.childCount) collectText(node.getChild(i), out)
    }
}
