package guardianlink.v2.app

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import androidx.appcompat.app.AppCompatDelegate

/**
 * The sovereign's surface preferences — appearance and behavior of
 * the app itself, stored in plain SharedPreferences. These are UI
 * preferences, not governance state: they never touch the core, the
 * ledger, or any rail. Every setting here is honored by the screens
 * that present it; there are no decorative toggles.
 */
object GlPrefs {

    private const val FILE = "gl-prefs"
    private const val KEY_NIGHT = "night_mode" // 0 system, 1 light, 2 dark
    private const val KEY_ACCENT = "accent" // palette key below
    private const val KEY_TEXT = "text_size" // 0 small, 1 default, 2 large
    private const val KEY_AUTOSENSE = "auto_sense"

    val ACCENTS: List<Pair<String, Int>> = listOf(
        "Cosmos" to Color.parseColor("#7C5CFF"),
        "Stardust" to Color.parseColor("#00B8D9"),
        "Deep teal" to Color.parseColor("#0F5257"),
        "Indigo" to Color.parseColor("#3F51B5"),
        "Crimson" to Color.parseColor("#8E2434"),
        "Plum" to Color.parseColor("#5E2750"),
        "Graphite" to Color.parseColor("#37474F"),
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun nightMode(context: Context): Int = prefs(context).getInt(KEY_NIGHT, 0)

    fun setNightMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_NIGHT, mode).apply()
        applyNightMode(context)
    }

    fun applyNightMode(context: Context) {
        AppCompatDelegate.setDefaultNightMode(
            when (nightMode(context)) {
                1 -> AppCompatDelegate.MODE_NIGHT_NO
                2 -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            },
        )
    }

    fun accentName(context: Context): String =
        prefs(context).getString(KEY_ACCENT, ACCENTS[0].first) ?: ACCENTS[0].first

    fun accentColor(context: Context): Int =
        ACCENTS.firstOrNull { it.first == accentName(context) }?.second ?: ACCENTS[0].second

    fun setAccent(context: Context, name: String) {
        prefs(context).edit().putString(KEY_ACCENT, name).apply()
    }

    fun textSize(context: Context): Int = prefs(context).getInt(KEY_TEXT, 1)

    fun setTextSize(context: Context, size: Int) {
        prefs(context).edit().putInt(KEY_TEXT, size).apply()
    }

    /** Chat bubble text size in sp for the stored preference. */
    fun bubbleTextSp(context: Context): Float = when (textSize(context)) {
        0 -> 13.5f
        2 -> 17f
        else -> 15f
    }

    fun autoSense(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTOSENSE, false)

    fun setAutoSense(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTOSENSE, on).apply()
    }

    /** A signature of everything that changes how screens render; activities recreate when it moves. */
    fun renderSignature(context: Context): String =
        "${nightMode(context)}|${accentName(context)}|${textSize(context)}|${isDark(context)}"

    fun isDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** Resolved colors for the current mode + accent. */
    data class Palette(
        val pageBg: Int,
        val headerBg: Int,
        val headerText: Int,
        val headerSub: Int,
        val cardBg: Int,
        val appBubble: Int,
        val appBubbleText: Int,
        val userBubble: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val chipBg: Int,
        val chipText: Int,
    )

    fun palette(context: Context): Palette {
        val dark = isDark(context)
        val accent = accentColor(context)
        // Cosmic in both modes (his ruling, 2026-10-25: bubbly,
        // futuristic, cosmic — the flat slate/teal is retired).
        // Dark is deep space; light is nebula mist with the same
        // deep-violet header, so the identity holds either way.
        // The chosen accent still runs through user bubbles, pills,
        // and highlights.
        return if (dark) {
            Palette(
                pageBg = Color.parseColor("#0A0618"),
                headerBg = Color.parseColor("#1E1050"),
                headerText = Color.WHITE,
                headerSub = Color.parseColor("#CFC6FF"),
                cardBg = Color.parseColor("#1C1245"),
                appBubble = Color.parseColor("#251A5C"),
                appBubbleText = Color.parseColor("#F0EBFF"),
                userBubble = accent,
                textPrimary = Color.parseColor("#F2EFFF"),
                textSecondary = Color.parseColor("#A79FD1"),
                chipBg = Color.parseColor("#2A1C66"),
                chipText = Color.parseColor("#EDE8FF"),
            )
        } else {
            Palette(
                pageBg = Color.parseColor("#F4F1FF"),
                headerBg = Color.parseColor("#2A1666"),
                headerText = Color.WHITE,
                headerSub = Color.parseColor("#D9D2FF"),
                cardBg = Color.parseColor("#FDFCFF"),
                appBubble = Color.parseColor("#EAE5FF"),
                appBubbleText = Color.parseColor("#1B1245"),
                userBubble = accent,
                textPrimary = Color.parseColor("#1B1245"),
                textSecondary = Color.parseColor("#5E5890"),
                chipBg = Color.parseColor("#EAE5FF"),
                chipText = Color.parseColor("#2A1666"),
            )
        }
    }
}
