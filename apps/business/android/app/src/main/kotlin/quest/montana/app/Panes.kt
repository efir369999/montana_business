package quest.montana.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout

// ─────────────────────────── the pages under the bar that wait for the network ───────────────────────────

/** A page with nothing in it yet: its words at the centre, as each iOS page says it. */
private fun emptyPane(c: Context, words: Int, above: View? = null): View = FrameLayout(c).apply {
    addView(c.vstack {
        if (above != null) { addView(above, lp(c.dp(56), c.dp(56))); gap(12) }
        addView(c.text(c.getString(words), if (above != null) 15f else 17f, MT.gray, center = true))
    }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
}

/** THE CALLS (iOS CallsTabView): the log of calls, a day row before each day; none yet — the calls come with the network. */
fun callsPane(c: Context) = emptyPane(c, R.string.no_calls)

/** THE FEED under the logo (iOS MTFeedTabView): the posts on my people's walls, by time; none yet — the walls come with the network. */
fun feedPane(c: Context) = emptyPane(c, R.string.no_posts)

/** THE GALLERY (iOS GalleryTabView): the photos and videos of every conversation; none yet — they come with the chats. */
fun galleryPane(c: Context) = emptyPane(c, R.string.no_photos, PetalsGlyph(c))

/** The system's own photos glyph (iOS MontanaPetalsGlyph): eight petals of the spectrum around one centre. */
class PetalsGlyph(c: Context) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height).toFloat()
        val cx = width / 2f; val cy = height / 2f
        val petal = RectF(cx - s * 0.15f, cy - s * 0.19f - s * 0.31f, cx + s * 0.15f, cy - s * 0.19f + s * 0.31f)
        for (i in 0 until 8) {
            paint.color = Color.HSVToColor((0.85f * 255).toInt(), floatArrayOf(i * 45f, 0.72f, 0.96f))
            canvas.save()
            canvas.rotate(i * 45f, cx, cy)
            canvas.drawOval(petal, paint)
            canvas.restore()
        }
    }
}
