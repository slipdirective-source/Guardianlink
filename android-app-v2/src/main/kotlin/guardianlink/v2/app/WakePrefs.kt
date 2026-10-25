package guardianlink.v2.app

import android.content.Context

/** Wake-phrase preferences: plain prefs — a phrase is config, not a secret. */
object WakePrefs {
    private const val FILE = "gl-wake"
    private const val KEY_PHRASE = "phrase"
    private const val KEY_ENABLED = "enabled"

    fun phrase(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_PHRASE, "Hey Guard") ?: "Hey Guard"

    fun setPhrase(context: Context, phrase: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_PHRASE, phrase).apply()
    }

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, value).apply()
    }
}
