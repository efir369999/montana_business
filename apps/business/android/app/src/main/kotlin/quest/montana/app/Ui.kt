package quest.montana.app

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView

// ─────────── colours (iOS MontanaNetFrames.swift / ContentView.swift) ───────────
object MT {
    val gold = Color.rgb(217, 158, 56)          // Color(red: 0.85, green: 0.62, blue: 0.22)
    val gray = Color.rgb(142, 142, 147)         // iOS .gray
    val plate = Color.rgb(31, 31, 31)           // iOS Color(white: 0.12)
    val orange = Color.rgb(255, 159, 10)        // iOS .orange (dark)
    val red = Color.rgb(255, 69, 58)            // iOS .red (dark)
    val green = Color.rgb(48, 209, 88)          // iOS .green (dark)
    val blue = Color.rgb(10, 132, 255)          // iOS .systemBlue (dark), MontanaOctagon.platformBlue
    val barGlyph = Color.rgb(204, 204, 204)     // iOS MontanaOctagon.barGlyph: Color(white: 0.8)
    val accent = Color.WHITE                    // iOS AccentColor (dark): white -- the accent of every glyph and word (05.10)
    /**
     * THE AUTHOR'S BURGUNDY, ONE OWNER (iOS MontanaOctagon.burgundy, 17e24a42): the colour of his own file, never a red of ours.
     * Measured 06.10.2026 on iOS Assets.xcassets/MontanaBurgundy.imageset/Montana_Burgundy.png (853 by 1844, 8-bit RGB, no
     * embedded profile, read as sRGB): the whole file is one hue, 345 degrees; the mean of every pixel is R 71, G 2, B 16 —
     * nearly black under a door's tint — and the mean of its brightest tenth (by R+G+B) is R 168.2, G 11.0, B 50.3: the file's
     * own burgundy at the brightness the eye names red. The door «By phone number» of Montana Business wears it as its tint
     * (the author's word 06.10: «like our Montana button, only in our Burgundy red»).
     */
    val burgundy = Color.rgb(168, 11, 50)
    val hairline = Color.argb(20, 255, 255, 255)
    fun withAlpha(c: Int, a: Float) = Color.argb((a * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))
}

fun Context.dp(v: Number): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
fun View.dp(v: Number): Int = context.dp(v)

// ─────────── layout parameters, written once ───────────
fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
    LinearLayout.LayoutParams(w, h, weight)
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

/** A vertical stack (SwiftUI VStack). */
fun Context.vstack(gravity: Int = Gravity.CENTER_HORIZONTAL, build: LinearLayout.() -> Unit = {}) =
    LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; this.gravity = gravity; build() }

/** A horizontal stack (SwiftUI HStack). */
fun Context.hstack(build: LinearLayout.() -> Unit = {}) =
    LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; build() }

/** Empty room that grows (SwiftUI Spacer()). */
fun LinearLayout.spacer() = addView(View(context), lp(if (orientation == LinearLayout.VERTICAL) MATCH else 0, if (orientation == LinearLayout.VERTICAL) 0 else MATCH, 1f))

/** A fixed gap (SwiftUI Spacer().frame(height:)). */
fun LinearLayout.gap(size: Int) = addView(View(context), if (orientation == LinearLayout.VERTICAL) lp(MATCH, dp(size)) else lp(dp(size), 1))

fun Context.text(s: CharSequence, sizeSp: Float = 17f, color: Int = Color.WHITE, bold: Boolean = false, center: Boolean = false) =
    TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        if (center) gravity = Gravity.CENTER
    }

fun Context.rounded(color: Int, radiusDp: Number, strokeColor: Int? = null) = GradientDrawable().apply {
    setColor(color); cornerRadius = dp(radiusDp).toFloat()
    if (strokeColor != null) setStroke(dp(1), strokeColor)
}

fun Context.icon(res: Int, tint: Int, sizeDp: Int = 24) = ImageView(this).apply {
    setImageResource(res)
    imageTintList = ColorStateList.valueOf(tint)
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
}

/** Dims while pressed (iOS: .opacity(isPressed ? 0.55 : 1)). */
fun View.pressable(onClick: () -> Unit): View {
    isClickable = true
    setOnClickListener { onClick() }
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> v.alpha = if (v.isEnabled) 0.55f else v.alpha
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> v.alpha = if (v.isEnabled) 1f else 0.4f
        }
        false
    }
    return this
}

/**
 * THE PATH'S MAIN ACT (iOS MTLoginDoorStyle(tint: MontanaOctagon.platformBlue), the author's word 29.09 23:25: «every act of the
 * path is a door -- the main act on the platform's blue glass, the second on its clear glass; gold is the sign's alone»): the
 * 52dp capsule of the doors, tinted with the platform's blue, the word white and semibold in its middle, the press 0.97.
 */
class DoorButton(ctx: Context, title: String, onClick: () -> Unit) : LinearLayout(ctx) {
    private val label = ctx.text(title, 17f, Color.WHITE, bold = true)
    private val spinner = ProgressBar(ctx).apply {
        indeterminateTintList = ColorStateList.valueOf(Color.WHITE); visibility = View.GONE
    }
    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER
        background = ctx.doorPlate(MT.blue)
        minimumHeight = dp(52)
        setPadding(dp(18), 0, dp(18), 0)
        addView(spinner, LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) })
        addView(label)
        pressable(onClick)
    }
    fun setTitle(s: String) { label.text = s }
    fun setBusy(busy: Boolean) { spinner.visibility = if (busy) View.VISIBLE else View.GONE; setOn(!busy) }
    /** Enabled or dimmed to 0.4, as iOS dims a door not yet open (MTLoginDoorStyle: opacity 0.4). */
    fun setOn(on: Boolean) { isEnabled = on; alpha = if (on) 1f else 0.4f }
}

/** The path's second act (iOS MTLoginDoorStyle(), the clear glass): the same capsule, the platform's faint rim, the word white. */
fun Context.outlineButton(title: String, onClick: () -> Unit) =
    text(title, 17f, Color.WHITE, bold = true, center = true).apply {
        background = doorPlate(null)
        minimumHeight = dp(52)
        gravity = Gravity.CENTER
        setPadding(dp(18), 0, dp(18), 0)
        pressable(onClick)
    }

/**
 * THE DOOR'S PLATE, ONE OWNER (iOS MTLoginDoorStyle face, the pre-glass material): a 52dp capsule on a thin dark material; a
 * tinted door wears its colour over the material as the glass's tint (0.28) and as its rim (0.45), a clear door's rim is white
 * at 0.6 x 0.45.
 */
fun Context.doorPlate(tint: Int?): GradientDrawable = GradientDrawable().apply {
    cornerRadius = dp(26).toFloat()
    setColor(if (tint == null) Color.argb(150, 28, 28, 30) else Color.argb(150,
        28 + ((Color.red(tint) - 28) * 0.28f).toInt(), 28 + ((Color.green(tint) - 28) * 0.28f).toInt(), 30 + ((Color.blue(tint) - 30) * 0.28f).toInt()))
    setStroke(dp(1), if (tint == null) MT.withAlpha(Color.WHITE, 0.6f * 0.45f) else MT.withAlpha(tint, 0.45f))
}

/** iOS Button("Back").font(.caption).foregroundColor(.gray) */
fun Context.backLink(onClick: () -> Unit) =
    text(getString(R.string.back), 12f, MT.gray, center = true).apply { setPadding(dp(16), dp(8), dp(16), dp(8)); pressable(onClick) }

/** A row with the words on the left and the system switch on the right (SwiftUI Toggle). */
fun Context.toggleRow(title: String, onChange: (Boolean) -> Unit): LinearLayout {
    val sw = Switch(this).apply {
        thumbTintList = ColorStateList.valueOf(Color.WHITE)
        trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(MT.green, Color.rgb(57, 57, 61)))   // iOS Toggle: the system's green, no tint of ours
        setOnCheckedChangeListener { _, on -> onChange(on) }
    }
    return hstack {
        addView(text(title, 15f), lp(0, WRAP, 1f))
        addView(sw)
        setOnClickListener { sw.toggle() }
    }
}

/** The page's glass plate: rows on a rounded dark card (iOS MTGlassRowPlate inside a List section). */
fun Context.plate(build: LinearLayout.() -> Unit) = vstack(Gravity.NO_GRAVITY) {
    background = rounded(MT.plate, 12)
    build()
}

fun LinearLayout.divider() = addView(View(context).apply { setBackgroundColor(MT.hairline) }, lp(MATCH, 1).apply { marginStart = dp(16) })

/** The face (iOS AvatarCircle): the photo, or the name's first character, bold and white, on a black circle. */
fun Context.avatar(face: Bitmap?, name: String, sizeDp: Int): View {
    val box = FrameLayout(this)
    box.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.BLACK) }
    box.clipToOutline = true
    box.outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(v: View, o: Outline) = o.setOval(0, 0, v.width, v.height)
    }
    val inner: View = when {
        face != null -> ImageView(this).apply { setImageBitmap(face); scaleType = ImageView.ScaleType.CENTER_CROP }
        name.isNotBlank() -> text(name.trim().let { it.substring(0, it.offsetByCodePoints(0, 1)) }, sizeDp * 0.42f, Color.WHITE, bold = true, center = true)
        else -> icon(R.drawable.ic_add_a_photo, Color.WHITE, (sizeDp * 0.36f).toInt())
    }
    val p = if (inner is ImageView && face == null) FrameLayout.LayoutParams(dp(sizeDp * 0.36f), dp(sizeDp * 0.36f), Gravity.CENTER)
            else FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER)
    if (inner is TextView) inner.gravity = Gravity.CENTER
    box.addView(inner, p)
    box.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    return box
}

/**
 * THE AUTHOR'S CREST AS THE APP NAMES ITSELF (iOS MTAppCrest, the author's word 06.10.2026 13:5x MSK: «fix the logo, the icon,
 * the sign-in page and the ones after it, so that all is right»). His file byte for byte (app_icon_glass_art) carries its own
 * rounded tile on a black field (x 66..1164, y 58..1159 of 1254); shown whole it stood as an icon inside an icon, and on a
 * coloured ground as a black square. Drawn here is the tile alone: the file scaled 1.17 about the tile's centre, cut by the
 * icon's own shape (a corner of 22.37 % of the side) -- the cut the launcher's adaptive icon makes too
 * (ic_launcher_glass_foreground); the two move together.
 */
class CrestView(c: Context) : View(c) {
    private val art = c.getDrawable(R.drawable.app_icon_glass_art)
    init {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                val side = minOf(v.width - v.paddingLeft - v.paddingRight, v.height - v.paddingTop - v.paddingBottom)
                val l = v.paddingLeft + (v.width - v.paddingLeft - v.paddingRight - side) / 2
                val t = v.paddingTop + (v.height - v.paddingTop - v.paddingBottom - side) / 2
                o.setRoundRect(l, t, l + side, t + side, side * CORNER)
            }
        }
        clipToOutline = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    override fun onDraw(canvas: android.graphics.Canvas) {
        val w = (width - paddingLeft - paddingRight).toFloat()
        val h = (height - paddingTop - paddingBottom).toFloat()
        val s = minOf(w, h) * SCALE
        val left = paddingLeft + (w - s) / 2f + s * SHIFT_X
        val top = paddingTop + (h - s) / 2f + s * SHIFT_Y
        art?.setBounds(left.toInt(), top.toInt(), (left + s).toInt(), (top + s).toInt())
        art?.draw(canvas)
    }
    companion object {
        const val SCALE = 1.17f
        const val SHIFT_X = 12f / 1254f
        const val SHIFT_Y = 18.5f / 1254f
        const val CORNER = 0.2237f
    }
}

/** The logo with its soft halo breathing behind it (iOS intro: Color.accentColor -- white -- 2.3 s, autoreverse). */
fun Context.glowingLogo(logoDp: Int, haloDp: Int, periodMs: Long): FrameLayout {
    val halo = View(this).apply {
        background = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = dp(haloDp / 2).toFloat()
            colors = intArrayOf(MT.withAlpha(MT.accent, 0.55f), Color.TRANSPARENT)
        }
    }
    val logo = CrestView(this)
    ValueAnimator.ofFloat(0f, 1f).apply {
        duration = periodMs; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener { a ->
            val g = a.animatedValue as Float
            halo.alpha = 0.5f + 0.5f * g
            halo.scaleX = 0.92f + 0.16f * g; halo.scaleY = halo.scaleX
        }
        start()
    }
    return FrameLayout(this).apply {
        addView(halo, FrameLayout.LayoutParams(dp(haloDp), dp(haloDp), Gravity.CENTER))
        addView(logo, FrameLayout.LayoutParams(dp(logoDp), dp(logoDp), Gravity.CENTER))
        clipChildren = false
    }
}

fun TextView.singleLineEllipsis() { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }

/**
 * The door of the first screen (iOS MTLoginDoorStyle, the pre-glass face): a 52dp capsule on a thin dark material with the
 * platform's faint rim; the glyph at the left, the word in the middle, the chevron at the right; the whole capsule is the
 * target and gives under the finger (0.97). A tinted door (iOS MTLoginDoorStyle(tint:)) wears its colour over the material
 * as the glass's tint (0.28) and as its rim (the door's own colour at 0.45); a clear door's rim is white at 0.6, at 0.45.
 */
fun Context.loginDoor(glyph: Int, word: String, tint: Int? = null, onClick: () -> Unit): View =
    loginDoor(ImageView(this).apply { setImageResource(glyph) }, word, tint, onClick)

/** A door with no glyph (iOS a Text on MTLoginDoorStyle): the word alone in the middle of the capsule. */
fun Context.loginDoor(glyphView: View?, word: String, tint: Int? = null, onClick: () -> Unit): View {
    val door = hstack {
        background = doorPlate(tint)   // one owner of the door's face
        setPadding(dp(18), 0, dp(18), 0)
        if (glyphView != null) addView(glyphView, lp(dp(26), dp(26)))
        addView(text(word, 17f, Color.WHITE, bold = true, center = true).apply { singleLineEllipsis() }, lp(0, WRAP, 1f))
        if (glyphView != null) addView(icon(R.drawable.ic_chevron_right, MT.withAlpha(Color.WHITE, 0.55f), 20))
        minimumHeight = dp(52)
    }
    door.isClickable = true
    door.setOnClickListener { onClick() }
    door.setOnTouchListener { v, e ->
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(120).start()
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }
        false
    }
    return door
}

/**
 * THE PLATFORM'S PROMINENT BUTTON (iOS .borderedProminent, .buttonBorderShape(.capsule), .controlSize(.large), tinted): a capsule
 * filled with [tint], the glyph and the word white and semibold in its middle; the whole capsule is the target and gives under
 * the finger (0.97), as the doors do.
 */
fun Context.prominentDoor(glyph: Int, word: String, tint: Int, onClick: () -> Unit): View {
    val door = hstack {
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { cornerRadius = dp(26).toFloat(); setColor(tint) }
        setPadding(dp(18), 0, dp(18), 0)
        addView(icon(glyph, Color.WHITE, 20).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, lp(dp(20), dp(20)).apply { marginEnd = dp(8) })
        addView(text(word, 17f, Color.WHITE, bold = true).apply { singleLineEllipsis() })
        minimumHeight = dp(52)
    }
    door.isClickable = true
    door.setOnClickListener { onClick() }
    door.setOnTouchListener { v, e ->
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(120).start()
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }
        false
    }
    return door
}

/**
 * Words with links written as iOS writes them in its catalogue — `[the words](url)` — so one string
 * serves both platforms. The links are drawn in [linkColor]; a tap hands the url to [onLink].
 */
fun Context.linkedText(markup: String, sizeSp: Float, color: Int, linkColor: Int, onLink: (String) -> Unit): TextView {
    val out = android.text.SpannableStringBuilder()
    val re = Regex("""\[([^\]]+)]\(([^)]+)\)""")
    var at = 0
    for (m in re.findAll(markup)) {
        out.append(markup, at, m.range.first)
        val start = out.length
        out.append(m.groupValues[1])
        val url = m.groupValues[2]
        out.setSpan(object : android.text.style.ClickableSpan() {
            override fun onClick(w: View) = onLink(url)
            override fun updateDrawState(ds: android.text.TextPaint) { ds.color = linkColor; ds.isUnderlineText = false }
        }, start, out.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        at = m.range.last + 1
    }
    out.append(markup, at, markup.length)
    return text(out, sizeSp, color, center = true).apply {
        movementMethod = android.text.method.LinkMovementMethod.getInstance()
        highlightColor = Color.TRANSPARENT
    }
}
