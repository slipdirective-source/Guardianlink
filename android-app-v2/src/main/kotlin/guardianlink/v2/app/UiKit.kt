package guardianlink.v2.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import guardianlink.v2.core.Delta

/**
 * The shared visual kit — one place where the app's dressed-up
 * surface is defined, so every screen wears the same clothes:
 * elevated cards instead of log panels, pill badges instead of
 * status strings, and the sovereign's chosen accent running through
 * all of it. Everything here reads its colors from GlPrefs, so dark
 * mode and accent choices apply uniformly.
 */
object UiKit {

    fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    fun rounded(context: Context, color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * context.resources.displayMetrics.density
        }

    fun card(context: Context, palette: GlPrefs.Palette, radiusDp: Float = 26f): GradientDrawable =
        rounded(context, palette.cardBg, radiusDp)

    /** A card container: palette surface, rounded, softly elevated. */
    fun cardView(context: Context, palette: GlPrefs.Palette, radiusDp: Float = 26f): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = card(context, palette, radiusDp)
            elevation = dp(context, 3).toFloat()
            setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, 0, 0, dp(context, 12)) }
        }

    /** Press feedback over a card without losing its background. */
    fun View.ripple(context: Context) {
        val out = TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)) {
            foreground = androidx.core.content.ContextCompat.getDrawable(context, out.resourceId)
        }
    }

    /** Same, callable from outside the kit. */
    fun addRipple(view: View, context: Context) {
        view.ripple(context)
    }

    /** Diagonal cosmic gradient — headers and hero surfaces. */
    fun headerGradient(context: Context, palette: GlPrefs.Palette): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.parseColor("#241060"),
                Color.parseColor("#4A21B0"),
                blend(Color.parseColor("#7C5CFF"), palette.userBubble, 0.45f),
            ),
        )

    /** The page-depth gradient: deep at the top, breathing room below. */
    fun pageGradient(context: Context, palette: GlPrefs.Palette): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(palette.pageBg, blend(palette.pageBg, Color.parseColor("#3B1B8F"), 0.28f)),
        )

    /** A user bubble with nebula drift: accent melting toward pink. */
    fun userGradient(context: Context, palette: GlPrefs.Palette, radii: FloatArray): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(palette.userBubble, blend(palette.userBubble, Color.parseColor("#FF5CA8"), 0.45f)),
        ).apply { cornerRadii = radii }

    /** Blend two colors: ratio 0 = all a, 1 = all b. */
    fun blend(a: Int, b: Int, ratio: Float): Int {
        val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * ratio).toInt()
        val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * ratio).toInt()
        val bl = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * ratio).toInt()
        return Color.rgb(r, g, bl)
    }

    /** Small rounded badge — the pill that replaces status strings. */
    fun pill(context: Context, text: String, bg: Int, fg: Int): TextView = TextView(context).apply {
        this.text = text
        textSize = 11f
        setTextColor(fg)
        background = rounded(context, bg, 999f)
        setPadding(dp(context, 9), dp(context, 3), dp(context, 9), dp(context, 3))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    /** The settled-ness of a standing answer, as a colored pill. */
    fun deltaPill(context: Context, palette: GlPrefs.Palette, delta: Delta): TextView = when (delta) {
        Delta.DISTINGUISHED -> pill(context, "● settled", blend(palette.cardBg, Color.parseColor("#2E7D32"), 0.18f), Color.parseColor("#1E6B1E"))
        Delta.EQUIVALENT -> pill(context, "● confirmed", blend(palette.cardBg, palette.userBubble, 0.20f), palette.userBubble)
        Delta.UNKNOWN -> pill(context, "● not settled yet", blend(palette.cardBg, Color.parseColor("#E8A33D"), 0.25f), Color.parseColor("#7A5200"))
        Delta.INAPPLICABLE -> pill(context, "● doesn't apply", blend(palette.cardBg, Color.GRAY, 0.18f), palette.textSecondary)
    }

    /** A grant's state, as a colored pill. */
    fun grantPill(context: Context, palette: GlPrefs.Palette, granted: Boolean): TextView =
        if (granted) {
            pill(context, "ON", blend(palette.cardBg, Color.parseColor("#2E7D32"), 0.18f), Color.parseColor("#1E6B1E"))
        } else {
            pill(context, "OFF", blend(palette.cardBg, Color.parseColor("#E8A33D"), 0.25f), Color.parseColor("#7A5200"))
        }

    /** Bottom navigation item: glyph over label, accent when active. */
    fun navItem(context: Context, palette: GlPrefs.Palette, glyph: String, label: String, active: Boolean, onClick: () -> Unit): LinearLayout {
        val color = if (active) palette.userBubble else palette.textSecondary
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, dp(context, 8), 0, dp(context, 8))
            addView(TextView(context).apply {
                text = glyph
                textSize = 19f
                gravity = Gravity.CENTER
                setTextColor(color)
            })
            addView(TextView(context).apply {
                text = label
                textSize = 11.5f
                gravity = Gravity.CENTER
                setTextColor(color)
            })
            if (active) {
                background = rounded(context, blend(palette.cardBg, palette.userBubble, 0.12f), 12f)
            }
            ripple(context)
            setOnClickListener { onClick() }
        }
    }

    /** The bottom tab bar itself: Chat / Vault / Settings. */
    fun bottomNav(context: Context, palette: GlPrefs.Palette, active: String, navigate: (String) -> Unit): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(palette.cardBg)
            elevation = dp(context, 8).toFloat()
            setPadding(dp(context, 10), dp(context, 4), dp(context, 10), dp(context, 4))
            addView(navItem(context, palette, "💬", "Chat", active == "chat") { navigate("chat") })
            addView(navItem(context, palette, "🗄", "Vault", active == "vault") { navigate("vault") })
            addView(navItem(context, palette, "⚙", "Settings", active == "settings") { navigate("settings") })
        }
}

/**
 * A deterministic starfield: the cosmic backdrop. Stars are
 * placed from a fixed seed so the sky is the same sky on every
 * draw (no shimmer, no animation cost) — a few bright, most
 * faint, a handful tinted with the sovereign's accent. Drawn
 * behind the chat; the page gradient shows through from the
 * root's own background.
 */
class StarfieldView(context: Context, private val accent: Int, private val dark: Boolean) : View(context) {

    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val stars: List<FloatArray> = run {
        val rnd = kotlin.random.Random(20261025)
        List(120) {
            floatArrayOf(rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat())
        }
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return
        for ((i, s) in stars.withIndex()) {
            val bright = s[2]
            paint.color = when {
                i % 17 == 0 -> accent
                dark -> Color.WHITE
                else -> Color.parseColor("#7C5CFF")
            }
            paint.alpha = if (dark) {
                (40 + bright * 170).toInt()
            } else {
                (18 + bright * 60).toInt()
            }
            val radius = 1f + s[3] * (if (i % 17 == 0) 3.2f else 1.8f)
            canvas.drawCircle(s[0] * w, s[1] * h, radius, paint)
        }
    }
}
