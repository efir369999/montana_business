package quest.montana.app

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.InputType
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Date

// ─────────────────────────── what the page knows of a person (iOS MTPeerAbout, MontanaSafety, SafetyStore) ───────────────────────────

/**
 * THE PERSON'S OWN WORDS ABOUT THEMSELVES (iOS MTPeerAbout): their bio and their link, told by the AB: word {b, l, at} and kept
 * here, the latest word winning. Only the web is ever opened from a link: a scheme other than https or http is not kept.
 */
object PeerAbout {
    const val BIO_LIMIT = 140
    const val LINK_LIMIT = 256

    class About(val bio: String, val link: String, val at: Double)

    fun of(ref: String): About? = runCatching {
        JSONObject(Prefs.str("about.$ref", "")).let { About(it.optString("b"), it.optString("l"), it.optDouble("at", 0.0)) }
    }.getOrNull()

    /** Their word arrived: an older word changes nothing. */
    fun note(ref: String, bio: String, link: String, at: Double) {
        val held = of(ref)
        if (held != null && held.at > at) return
        Prefs.setStr("about.$ref", JSONObject().put("b", bio.take(BIO_LIMIT)).put("l", url(link)?.toString() ?: "").put("at", at).toString())
    }

    /** A link as a person types it: «site.org» wears https; nothing but the web is a link here. */
    fun url(s: String): Uri? {
        val t = s.trim()
        if (t.isEmpty() || t.length > LINK_LIMIT) return null
        val u = Uri.parse(if (t.contains("://")) t else "https://$t")
        val scheme = u.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return null
        if (u.host?.contains('.') != true) return null
        return u
    }

    /** A link as a page shows it: the site and the path, without the scheme. */
    fun shown(u: Uri): String {
        var s = u.toString()
        for (p in listOf("https://", "http://")) if (s.lowercase().startsWith(p)) s = s.substring(p.length)
        return s.removeSuffix("/")
    }
}

/** THE BLOCK AND THE VERIFIED NUMBER (iOS MontanaSafety / SafetyStore): one owner each; every road asks here. */
object PeerSafety {
    fun isBlocked(ref: String) = Prefs.bool("block.$ref", false)
    fun setBlocked(ref: String, on: Boolean) = Prefs.setBool("block.$ref", on)
    fun isVerified(ref: String) = Prefs.bool("fpVerified.$ref", false)
    fun setVerified(ref: String, on: Boolean) = Prefs.setBool("fpVerified.$ref", on)

    /**
     * THE CORRESPONDENCE'S NUMBER (iOS MTPipe.fingerprint): 5200 rounds of SHA-256("mt-safety" ‖ 0 ‖ h) over the pipe's secret,
     * six groups of five digits. Both sides derive it from the same secret; a stranger in the middle would have had to replace
     * exactly that secret, and the number changes the moment they do.
     */
    fun fingerprint(ref: String): String? {
        val secret = Book.secret(ref)?.takeIf { it.size == 32 } ?: return null
        val domain = "mt-safety".toByteArray() + byteArrayOf(0)
        var h = secret
        repeat(5200) { h = MessageDigest.getInstance("SHA-256").digest(domain + h) }
        return (0 until 6).joinToString("") { i ->
            var v = 0L
            for (k in 0 until 5) v = (v shl 8) or (h[i * 5 + k].toLong() and 0xFF)
            "%05d".format(v % 100_000)
        }
    }
}

// ─────────────────────────── the panes (iOS MTMediaTab) ───────────────────────────

private enum class Tab(val title: Int, val icon: Int, val empty: Int) {
    WALL(R.string.pi_wall, R.drawable.ic_compose_photo, R.string.pi_wall_empty),
    MEDIA(R.string.pi_media, R.drawable.ic_photo, R.string.pi_media_empty),
    VIDEO(R.string.pi_video, R.drawable.ic_peer_video, R.string.pi_video_empty),
    FILES(R.string.pi_files, R.drawable.ic_set_doc, R.string.pi_files_empty),
    LINKS(R.string.pi_links, R.drawable.ic_link, R.string.pi_links_empty),
    MUSIC(R.string.pi_music, R.drawable.ic_music_note, R.string.pi_music_empty),
    VOICE(R.string.pi_voice, R.drawable.ic_mic, R.string.pi_voice_empty),
}

/** A letter of the conversation as the panes read it: its kind, its file, its manifest. */
private class Item(val m: Msg, val kind: String, val file: File?, val name: String?, val size: Long, val du: Double)

private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "aiff", "alac")

private fun itemOf(m: Msg): Item? {
    if (!m.text.startsWith(Marks.MEDIA)) return null
    val man = m.meta?.let { runCatching { JSONObject(it) }.getOrNull() } ?: Media.inline(m.text) ?: return null
    val f = m.file?.let { File(it) }?.takeIf { it.exists() }
    return Item(m, man.optString("k"), f, man.optString("n").ifEmpty { null }, man.optLong("sz"), man.optDouble("du", 0.0))
}
private fun Item.isMusic() = kind == "doc" && (name ?: file?.name ?: "").substringAfterLast('.', "").lowercase() in AUDIO_EXT

/** The links a letter carries: the platform's own finder, the web alone (iOS MTLinks). */
private fun linksIn(text: String): List<Uri> {
    if (Marks.isService(text) || text.length > 20_000) return emptyList()
    val out = mutableListOf<Uri>()
    val m = android.util.Patterns.WEB_URL.matcher(text)
    while (m.find()) PeerAbout.url(m.group())?.let { out.add(it) }
    return out
}

private fun select(tab: Tab, msgs: List<Msg>): List<Msg> = when (tab) {
    Tab.WALL -> emptyList()   // the wall is its owner's posts, not the letters
    Tab.MEDIA -> msgs.filter { itemOf(it)?.kind == "img" }
    Tab.VIDEO -> msgs.filter { itemOf(it)?.kind == "vid" }
    Tab.FILES -> msgs.filter { itemOf(it)?.let { i -> i.kind == "doc" && !i.isMusic() } == true }
    Tab.LINKS -> msgs.filter { linksIn(it.text).isNotEmpty() }
    Tab.MUSIC -> msgs.filter { itemOf(it)?.isMusic() == true }
    Tab.VOICE -> msgs.filter { itemOf(it)?.kind == "aud" }
}

// ─────────────────────────── the page (iOS MontanaPeerInfoScreen) ───────────────────────────

/**
 * THE CORRESPONDENT'S PAGE (iOS MontanaPeerInfoScreen): the face, their name, their bio and their link each on its own glass
 * bubble; the four plates — message, call, video, more; the fingerprint and the sharing of the contact; report and block; and
 * the strip of panes — the wall, then what the conversation carried: media, video, files, links, music, voices, newest on top,
 * cut by day. The whole page scrolls as one.
 */
fun peerInfoPage(act: MainActivity, ref: String, onClose: () -> Unit): View {
    val c: Context = act
    var name = Book.chat(ref)?.shown?.ifBlank { null } ?: c.getString(R.string.peer)
    val body = c.vstack(Gravity.NO_GRAVITY) { setPadding(0, dp(56), 0, dp(40)) }

    fun notYet() = Toast.makeText(c, R.string.compose_not_yet, Toast.LENGTH_SHORT).show()
    fun bubble(v: View): View = FrameLayout(c).apply {
        addView(v.apply {
            background = c.glassPlate().apply { cornerRadius = dp(22).toFloat() }
            setPadding(dp(16), dp(9), dp(16), dp(9))
        }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_HORIZONTAL))
    }

    lateinit var redrawAll: () -> Unit

    // ── the face: the circle; a press opens the square across the page, a second press the whole screen (iOS MTFaceDock) ──
    fun readFace() = Book.shownFace(ref).let { f -> if (f.exists()) BitmapFactory.decodeFile(f.path) else null }
    var bmp = readFace()
    var open = false
    val faceBox = FrameLayout(c)
    fun drawFace() {
        faceBox.removeAllViews()
        val w = c.resources.displayMetrics.widthPixels
        if (open && bmp != null) faceBox.addView(ImageView(c).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP },
            FrameLayout.LayoutParams(w, w))
        else faceBox.addView(c.avatar(bmp, name, 104), FrameLayout.LayoutParams(c.dp(104), c.dp(104), Gravity.CENTER_HORIZONTAL))
    }
    faceBox.setOnClickListener {
        if (bmp == null) return@setOnClickListener
        if (!open) { open = true; drawFace() }
        else {
            lateinit var close: () -> Unit
            close = act.overlay(FrameLayout(c).apply {
                setBackgroundColor(Color.BLACK)
                addView(ImageView(c).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_CENTER }, FrameLayout.LayoutParams(MATCH, MATCH))
                pressable { close() }
            })
        }
    }
    drawFace()

    // ── the head's lines ──
    val head = c.vstack(Gravity.CENTER_HORIZONTAL)
    fun drawHead() {
        name = Book.chat(ref)?.shown?.ifBlank { null } ?: c.getString(R.string.peer)
        bmp = readFace(); drawFace()
        head.removeAllViews()
        head.addView(faceBox, lp(MATCH, WRAP))
        head.addView(bubble(c.text(name, 20f, Color.WHITE, bold = true, center = true).apply { maxLines = 2 }),   // USER-DATA: their name
            lp().apply { topMargin = c.dp(10); marginStart = c.dp(18); marginEnd = c.dp(18) })
        if (PeerSafety.isBlocked(ref)) head.addView(bubble(c.text(c.getString(R.string.pi_blocked), 15f, SysColor.red, bold = true)),
            lp().apply { topMargin = c.dp(8) })
        val about = PeerAbout.of(ref)
        if (!about?.bio.isNullOrEmpty()) head.addView(bubble(c.text(about!!.bio, 17f, Color.WHITE, center = true).apply { setTextIsSelectable(true) }),   // USER-DATA: their bio
            lp().apply { topMargin = c.dp(8); marginStart = c.dp(18); marginEnd = c.dp(18) })
        PeerAbout.url(about?.link ?: "")?.let { u ->
            head.addView(bubble(c.text(PeerAbout.shown(u), 17f, MT.blue).apply {   // USER-DATA: their link
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                setOnClickListener { runCatching { act.startActivity(Intent(Intent.ACTION_VIEW, u)) } }
                setOnLongClickListener {
                    c.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("", u.toString())); true
                }
            }), lp().apply { topMargin = c.dp(8); marginStart = c.dp(18); marginEnd = c.dp(18) })
        }
        // THE NOTE I KEEP ON THEM (iOS MTPeerHeader note): grey, under their own lines
        Book.chat(ref)?.note?.takeIf { it.isNotEmpty() }?.let { n ->
            head.addView(bubble(c.text(n, 15f, MT.gray, center = true)),   // USER-DATA: my note on this person
                lp().apply { topMargin = c.dp(8); marginStart = c.dp(18); marginEnd = c.dp(18) })
        }
    }

    // ── the four plates (iOS MTPeerAction): message, voice call, video call, more; a blocked person is not called ──
    val actions = c.hstack { setPadding(dp(16), dp(14), dp(16), dp(6)) }
    fun drawActions() {
        actions.removeAllViews()
        fun plate(icon: Int, label: Int, work: (View) -> Unit) {
            if (actions.childCount > 0) actions.addView(View(c), lp(c.dp(10), 1))
            actions.addView(FrameLayout(c).apply {
                background = c.glassPlate().apply { cornerRadius = dp(22).toFloat() }
                contentDescription = c.getString(label)
                addView(c.icon(icon, Color.WHITE), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
                pressable { work(this) }
            }, lp(0, c.dp(48), 1f))
        }
        plate(R.drawable.ic_peer_message, R.string.pi_message) { onClose() }   // opened from the chat: back to it
        if (!PeerSafety.isBlocked(ref)) {
            plate(R.drawable.ic_peer_phone, R.string.pi_voice_call) { notYet() }
            plate(R.drawable.ic_peer_video, R.string.pi_video_call) { notYet() }
        }
        plate(R.drawable.ic_more_horiz, R.string.pi_more) { v ->
            PopupMenu(c, v).apply {
                menu.add(c.getString(R.string.pi_chat_background))
                setOnMenuItemClickListener { act.push { close -> wallpaperPicker(act, ref, close) }; true }   // iOS: More → Chat background
            }.show()
        }
    }

    // ── the rows (iOS MTInfoSectionsView): the fingerprint and the contact; then report and block ──
    fun row(icon: Int, title: String, detail: String? = null, red: Boolean = false, chevron: Boolean = false, work: () -> Unit): View = c.hstack {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(12), dp(14), dp(12))
        minimumHeight = dp(52)
        addView(c.icon(icon, if (red) SysColor.red else MT.gray), lp(dp(22), dp(22)).apply { marginEnd = dp(14) })
        addView(c.vstack(Gravity.NO_GRAVITY) {
            addView(c.text(title, 17f, if (red) SysColor.red else Color.WHITE))
            if (detail != null) addView(c.text(detail, 13f, MT.gray))
        }, lp(0, WRAP, 1f))
        if (chevron) addView(c.icon(R.drawable.ic_chevron_right, Color.rgb(89, 89, 89), 18), lp(dp(18), dp(18)))
        pressable(work)
    }
    fun glassGroup(vararg rows: View): View = c.vstack(Gravity.NO_GRAVITY) {
        background = c.glassPlate().apply { cornerRadius = dp(20).toFloat() }
        rows.forEachIndexed { i, r ->
            if (i > 0) addView(View(c).apply { setBackgroundColor(MT.hairline) }, lp(MATCH, 1).apply { marginStart = dp(52) })
            addView(r, lp())
        }
    }
    val sections = c.vstack(Gravity.NO_GRAVITY) { setPadding(dp(16), dp(10), dp(16), 0) }
    fun drawSections() {
        sections.removeAllViews()
        val verified = PeerSafety.isVerified(ref)
        val first = mutableListOf<View>()
        if (Book.secret(ref) != null) first.add(row(if (verified) R.drawable.ic_set_check_circle else R.drawable.ic_shield,
            c.getString(R.string.pi_fingerprint), c.getString(if (verified) R.string.pi_verified else R.string.pi_not_verified), chevron = true) {
            act.push { close -> safetyPage(act, ref, name) { close(); redrawAll() } }
        })
        first.add(row(R.drawable.ic_share, c.getString(R.string.pi_share_contact)) { notYet() })
        sections.addView(glassGroup(*first.toTypedArray()), lp())
        val blocked = PeerSafety.isBlocked(ref)
        sections.addView(glassGroup(
            row(R.drawable.ic_report, c.getString(R.string.pi_report), red = true) {
                act.push { close -> reportPage(act, ref, name) { close(); redrawAll() } }
            },
            row(R.drawable.ic_hand, c.getString(if (blocked) R.string.pi_unblock else R.string.pi_block), red = true) {
                if (blocked) { PeerSafety.setBlocked(ref, false); redrawAll() }
                else AlertDialog.Builder(c).setTitle(R.string.pi_block_q).setMessage(R.string.pi_block_note)
                    .setPositiveButton(R.string.pi_block_contact) { _, _ -> PeerSafety.setBlocked(ref, true); redrawAll() }
                    .setNegativeButton(R.string.cancel, null).show()
            }), lp().apply { topMargin = c.dp(22) })
    }

    // ── the strip and the panes ──
    var tab = Tab.WALL   // a page opens on its wall
    val strip = c.hstack { setPadding(dp(4), dp(4), dp(4), dp(4)); background = c.glassPlate() }
    val pane = c.vstack(Gravity.NO_GRAVITY) { setPadding(dp(16), 0, dp(16), 0) }
    lateinit var drawPane: () -> Unit
    fun drawStrip() {
        strip.removeAllViews()
        for (t in Tab.values()) strip.addView(c.text(c.getString(t.title), 16f, if (t == tab) Color.WHITE else MT.gray, bold = t == tab).apply {
            setPadding(dp(16), dp(9), dp(16), dp(9))
            if (t == tab) background = c.rounded(Color.argb(60, 255, 255, 255), 20)
            pressable { tab = t; drawStrip(); drawPane() }
        })
    }
    drawPane = {
        pane.removeAllViews()
        val msgs = select(tab, Book.chat(ref)?.msgs ?: emptyList())
        if (tab == Tab.WALL) {
            pane.addView(c.hstack {
                gravity = Gravity.CENTER_VERTICAL
                background = c.glassPlate()
                setPadding(dp(14), dp(10), dp(14), dp(10))
                addView(FrameLayout(c).apply {
                    background = c.glassPlate(oval = true)
                    addView(c.icon(R.drawable.ic_compose, Color.WHITE), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
                }, lp(dp(44), dp(44)).apply { marginEnd = dp(14) })
                addView(c.text(c.getString(R.string.pi_write_wall), 17f))
                pressable { notYet() }
            }, lp().apply { topMargin = c.dp(12) })
        }
        if (msgs.isEmpty()) pane.addView(c.vstack(Gravity.CENTER_HORIZONTAL) {
            setPadding(0, dp(36), 0, dp(36))
            addView(c.icon(tab.icon, MT.gray), lp(dp(42), dp(42)))
            addView(c.text(c.getString(tab.empty), 15f, MT.gray, center = true), lp().apply { topMargin = dp(10) })
        }, lp())
        else paneRows(act, tab, msgs.reversed(), pane)   // newest on top
    }

    redrawAll = { drawHead(); drawActions(); drawSections(); drawPane() }
    body.addView(head, lp())
    body.addView(actions, lp())
    body.addView(sections, lp())
    body.addView(HorizontalScrollView(c).apply {
        isHorizontalScrollBarEnabled = false
        setPadding(dp(16), 0, dp(16), 0); clipToPadding = false
        addView(strip)
    }, lp().apply { topMargin = c.dp(24) })
    body.addView(pane, lp())
    drawStrip()
    redrawAll()

    val listener: () -> Unit = { drawHead(); drawPane() }
    return FrameLayout(c).apply {
        setBackgroundColor(Color.BLACK)
        addView(CrestGround(c), FrameLayout.LayoutParams(MATCH, MATCH))
        addView(ScrollView(c).apply { isVerticalScrollBarEnabled = false; addView(body) }, FrameLayout.LayoutParams(MATCH, MATCH))
        // the back mark on its own round of glass, over the page
        addView(FrameLayout(c).apply {
            background = c.glassPlate(oval = true)
            addView(c.icon(R.drawable.ic_arrow_back_ios_new, Color.WHITE), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            pressable { onClose() }
        }, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.START).apply { setMargins(dp(14), dp(6), 0, 0) })
        // THE DOTS STAND TOP RIGHT (iOS MontanaEditMark: «Edit» behind the platform's three dots): the card's edit page
        addView(FrameLayout(c).apply {
            background = c.glassPlate(oval = true)
            contentDescription = c.getString(R.string.pe_edit)
            addView(c.icon(R.drawable.ic_more_horiz, Color.WHITE), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            pressable { act.push { close -> peerEditPage(act, ref, close) } }
        }, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(6), dp(14), 0) })
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { Book.listen(listener) }
            override fun onViewDetachedFromWindow(v: View) { Book.unlisten(listener) }
        })
    }
}

/** The day's word over a pane's group (iOS MTDayLabel): «Today», else the date. */
private fun dayWord(c: Context, at: Long) =
    if (DateUtils.isToday(at)) c.getString(R.string.today) else DateUtils.formatDateTime(c, at, DateUtils.FORMAT_SHOW_DATE)

/** A pane's rows, cut by day: tiles three to a row for media and video, glass rows for files, links, music and voices. */
private fun paneRows(act: MainActivity, tab: Tab, msgs: List<Msg>, into: LinearLayout) {
    val c: Context = act
    val days = msgs.groupBy { dayWord(c, it.at) }   // groupBy keeps the order of first appearance: newest day first
    for ((day, items) in days) {
        into.addView(c.text(day, 12f, MT.gray), lp().apply { topMargin = c.dp(14); bottomMargin = c.dp(6) })
        when (tab) {
            Tab.MEDIA, Tab.VIDEO -> items.chunked(3).forEach { three ->
                into.addView(c.hstack {
                    three.forEachIndexed { i, m -> addView(tile(act, m), lp(0, c.dp(120), 1f).apply { if (i > 0) marginStart = c.dp(3) }) }
                    repeat(3 - three.size) { addView(View(c), lp(0, c.dp(120), 1f).apply { marginStart = c.dp(3) }) }
                }, lp().apply { bottomMargin = c.dp(3) })
            }
            Tab.LINKS -> items.forEach { m -> linksIn(m.text).forEach { u ->
                into.addView(glassRow(c, R.drawable.ic_link, u.host ?: u.toString(), u.toString(), m) {
                    runCatching { act.startActivity(Intent(Intent.ACTION_VIEW, u)) }
                }, lp().apply { bottomMargin = c.dp(8) })
            } }
            Tab.FILES, Tab.MUSIC -> items.forEach { m ->
                val it = itemOf(m) ?: return@forEach
                val title = it.name ?: it.file?.name ?: c.getString(R.string.pi_file)
                into.addView(glassRow(c, if (tab == Tab.MUSIC) R.drawable.ic_play_fill else R.drawable.ic_set_doc,
                    if (tab == Tab.MUSIC) title.substringBeforeLast('.') else title,
                    android.text.format.Formatter.formatShortFileSize(c, it.size), m) {
                    it.file?.let { f -> openMedia(act, f, "doc") }
                }, lp().apply { bottomMargin = c.dp(8) })
            }
            Tab.VOICE -> items.forEach { m ->
                val it = itemOf(m) ?: return@forEach
                val s = it.du.toInt()
                into.addView(glassRow(c, R.drawable.ic_play_fill, c.getString(R.string.pi_voice_message), "%d:%02d".format(s / 60, s % 60), m) {
                    it.file?.let { f -> VoicePlayer.toggle(f.path, onProgress = { _, _ -> }, stopped = {}) }
                }, lp().apply { bottomMargin = c.dp(8) })
            }
            Tab.WALL -> {}
        }
    }
}

/** One tile: the picture's own face (drawn off the main thread), a play mark on a film; a tap opens it whole. */
private fun tile(act: MainActivity, m: Msg): View {
    val c: Context = act
    val it = itemOf(m)
    return FrameLayout(c).apply {
        setBackgroundColor(Color.argb(40, 255, 255, 255))
        val img = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        addView(img, FrameLayout.LayoutParams(MATCH, MATCH))
        val man = m.meta?.let { j -> runCatching { JSONObject(j) }.getOrNull() } ?: Media.inline(m.text)
        Thread {
            val pic = it?.file?.let { f -> Media.preview(c, f, it.kind, 360) } ?: Media.thumbOf(man)
            if (pic != null) MainThread.post { img.setImageBitmap(pic) }
        }.start()
        if (it?.kind == "vid") addView(c.icon(R.drawable.ic_play_fill, Color.WHITE), FrameLayout.LayoutParams(c.dp(28), c.dp(28), Gravity.CENTER))
        val f = it?.file
        if (f != null) setOnClickListener { _ -> openMedia(act, f, it.kind) }
    }
}

/** A row on the bar's own glass (iOS glassRow): the square plate with the glyph, the long plate with the words and the time. */
private fun glassRow(c: Context, icon: Int, title: String, detail: String, m: Msg, work: () -> Unit): View = c.hstack {
    gravity = Gravity.CENTER_VERTICAL
    addView(FrameLayout(c).apply {
        background = c.glassPlate().apply { cornerRadius = dp(16).toFloat() }
        addView(c.icon(icon, Color.rgb(204, 204, 204)), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
    }, lp(dp(56), dp(56)).apply { marginEnd = dp(10) })
    addView(c.hstack {
        gravity = Gravity.CENTER_VERTICAL
        background = c.glassPlate().apply { cornerRadius = dp(16).toFloat() }
        setPadding(dp(14), 0, dp(14), 0)
        addView(c.vstack(Gravity.NO_GRAVITY) {
            addView(c.text(title, 16f).apply { singleLineEllipsis() })   // USER-DATA
            addView(c.text(detail, 12f, MT.gray).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
        }, lp(0, WRAP, 1f))
        addView(c.text(android.text.format.DateFormat.getTimeFormat(c).format(Date(m.at)), 11f, MT.gray), lp(WRAP, WRAP).apply { marginStart = dp(8) })
    }, lp(0, dp(56), 1f))
    pressable(work)
}

// ─────────────────────────── the fingerprint (iOS SafetyNumberView) ───────────────────────────

/**
 * THE NUMBER OF THIS CORRESPONDENCE: my fingerprint as a code and as sixty digits in groups of five, to compare aloud or face
 * to face; «Verified» marks it compared. (Scanning their code from here comes with the fingerprint scanner.)
 */
private fun safetyPage(act: MainActivity, ref: String, name: String, onClose: () -> Unit): View {
    val c: Context = act
    val digits = PeerSafety.fingerprint(ref) ?: ""
    return settingsPage(act, c.getString(R.string.pi_fingerprint), onClose) {
        val status = c.text("", 15f, MT.gray, bold = true, center = true)
        val mark = c.text("", 17f, Color.BLACK, bold = true, center = true)
        fun draw() {
            val v = PeerSafety.isVerified(ref)
            status.text = c.getString(if (v) R.string.pi_fp_verified_mark else R.string.pi_not_verified)
            status.setTextColor(if (v) MT.green else MT.gray)
            mark.text = c.getString(if (v) R.string.pi_fp_unverify else R.string.pi_fp_verified)
            mark.setTextColor(if (v) Color.WHITE else Color.BLACK)
            mark.background = c.rounded(if (v) Color.argb(76, 142, 142, 147) else MT.accent, 10)
        }
        addView(status, lp().apply { topMargin = c.dp(14) })
        if (digits.isNotEmpty()) addView(QrView(c).apply { code = QrCode.encode("mt:fp:$digits", QrCode.Ecc.M) },
            LinearLayout.LayoutParams(c.dp(200), c.dp(200)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = c.dp(18) })
        addView(c.text(digits.chunked(5).joinToString(" "), 17f, Color.WHITE, center = true).apply { typeface = Typeface.MONOSPACE },
            lp().apply { topMargin = c.dp(18) })
        addView(c.text(c.getString(R.string.pi_fp_compare), 12f, MT.gray, center = true), lp().apply { topMargin = c.dp(12) })
        addView(mark.apply {
            setPadding(0, dp(12), 0, dp(12))
            pressable { PeerSafety.setVerified(ref, !PeerSafety.isVerified(ref)); draw() }
        }, lp().apply { topMargin = c.dp(18) })
        // THEIR QR READ BY THE CAMERA (iOS SafetyNumberView's scan): only a fingerprint of this shape is compared — any other
        // code keeps the camera looking; the same number verifies, a different one unverifies and is said (it is evidence)
        if (digits.isNotEmpty()) addView(c.text(c.getString(R.string.sn_scan), 17f, MT.accent, bold = true, center = true).apply {
            setPadding(0, dp(12), 0, dp(12))
            background = c.rounded(Color.TRANSPARENT, 10, MT.accent)
            pressable {
                act.push { close ->
                    scannerPage(act, close) { code ->
                        val payload = code.substringBefore('#').removePrefix("mt:fp:")
                        if (payload.length != digits.length || !payload.all { it in '0'..'9' }) return@scannerPage false
                        val same = payload == digits
                        PeerSafety.setVerified(ref, same)
                        act.onMain {
                            draw()
                            if (!same) android.app.AlertDialog.Builder(act).setTitle(R.string.sn_mismatch).setMessage(R.string.sn_mismatch_note)
                                .setPositiveButton(R.string.ok, null).show()
                        }
                        true
                    }
                }
            }
        }, lp().apply { topMargin = c.dp(10) })
        draw()
    }
}

// ─────────────────────────── the report (iOS MontanaReportSheet) ───────────────────────────

/** A report by mail to the network's address: the reason, «block this person too», the platform's own mail road. */
private fun reportPage(act: MainActivity, ref: String, name: String, onClose: () -> Unit): View {
    val c: Context = act
    val reasons = listOf("spam" to R.string.pi_spam, "abuse" to R.string.pi_abuse, "hate" to R.string.pi_hate,
        "illegal" to R.string.pi_illegal, "other" to R.string.pi_other)
    var reason = "spam"
    var blockToo = true
    val mail = "contact@montana.quest"
    return settingsPage(act, c.getString(R.string.pi_report), onClose, cross = true) {
        val rows = reasons.map { (key, words) ->
            c.hstack {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                addView(c.text(c.getString(words), 16f), lp(0, WRAP, 1f))
                addView(c.icon(R.drawable.ic_check, MT.accent, 20).apply { tag = key }, lp(dp(20), dp(20)))
            }
        }
        fun mark() = rows.forEach { r -> (r as LinearLayout).getChildAt(1).visibility = if (r.getChildAt(1).tag == reason) View.VISIBLE else View.INVISIBLE }
        rows.forEachIndexed { i, r -> r.pressable { reason = reasons[i].first; mark() } }
        mark()
        section(c.getString(R.string.pi_reason), null, *rows.toTypedArray())
        section(null, null, c.hstack {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(14), dp(8))
            addView(c.text(c.getString(R.string.pi_block_too), 16f), lp(0, WRAP, 1f))
            addView(Switch(c).apply { isChecked = true; setOnCheckedChangeListener { _, v -> blockToo = v } })
        })
        section(null, c.getString(R.string.pi_reports_go, mail), c.text(c.getString(R.string.pi_send_report), 16f, MT.blue).apply {
            setPadding(dp(16), dp(14), dp(16), dp(14))
            pressable {
                if (blockToo) PeerSafety.setBlocked(ref, true)
                val text = "Report ($reason)\nReported: $name\n\nBuild: ${versionFooter(c)}"
                val send = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).putExtra(Intent.EXTRA_EMAIL, arrayOf(mail))
                    .putExtra(Intent.EXTRA_SUBJECT, "Montana report ($reason)").putExtra(Intent.EXTRA_TEXT, text)
                val ok = runCatching { act.startActivity(send) }.isSuccess
                if (!ok) {
                    // no mail app: the report and the address are copied, to paste into any mail
                    c.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("", "$mail\n\n$text"))
                    Toast.makeText(c, mail, Toast.LENGTH_LONG).show()
                }
                onClose()
            }
        })
    }
}

/**
 * THE EDIT PAGE (iOS MontanaPeerInfoScreen editor): the face with «Edit» under it — the system's picker, then the circle's
 * crop; the name fields, prefilled with the name as it stands, and «Show original name» with the name they gave themselves
 * when my own stands over it; the note; «Restore original photo» while a picture of mine is on the card. The checkmark
 * saves (iOS MontanaDoneMark); the back mark leaves the card as it was.
 */
fun peerEditPage(act: MainActivity, ref: String, onClose: () -> Unit): View {
    val c: Context = act
    val chat = Book.chat(ref)
    val theirs = chat?.name?.trim().orEmpty()
    val shown = chat?.shown?.trim().orEmpty()
    var photo: ByteArray? = null          // picked just now
    var photoCleared = false              // «Restore original photo» pressed
    val body = c.vstack(Gravity.NO_GRAVITY) { setPadding(dp(16), dp(64), dp(16), dp(40)) }

    fun field(hintWords: Int, value: String, multi: Boolean = false) = EditText(c).apply {
        hint = c.getString(hintWords); setText(value)
        setHintTextColor(MT.gray); setTextColor(Color.WHITE); textSize = 17f
        background = null; setPadding(dp(16), dp(13), dp(16), dp(13))
        if (multi) { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; minLines = 1; maxLines = 5 }
        else { isSingleLine = true; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS }
    }
    fun group(vararg rows: View): LinearLayout = c.vstack(Gravity.NO_GRAVITY) {
        background = c.glassPlate().apply { cornerRadius = dp(20).toFloat() }
        rows.forEachIndexed { i, r ->
            if (i > 0) addView(View(c).apply { setBackgroundColor(MT.hairline) }, lp(MATCH, 1).apply { marginStart = dp(16) })
            addView(r, lp())
        }
    }

    // ── the face and «Edit» under it ──
    val faceBox = FrameLayout(c)
    lateinit var drawPhotoRows: () -> Unit
    fun drawFace() {
        faceBox.removeAllViews()
        val bmp = photo?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            ?: (if (photoCleared) Book.face(ref) else Book.shownFace(ref)).let { f -> if (f.exists()) BitmapFactory.decodeFile(f.path) else null }
        faceBox.addView(c.avatar(bmp, shown.ifBlank { "?" }, 100), FrameLayout.LayoutParams(c.dp(100), c.dp(100), Gravity.CENTER_HORIZONTAL))
    }
    fun pick() = act.pickPhoto { uri ->
        val image = uri?.let { SelfFace.decode(act, it) } ?: return@pickPhoto
        lateinit var close: () -> Unit
        close = act.overlay(cropPage(act, image) { jpeg ->
            close()
            if (jpeg != null) { photo = jpeg; photoCleared = false; drawFace(); drawPhotoRows() }
        })
    }
    drawFace()
    body.addView(faceBox, lp(MATCH, WRAP))
    body.addView(c.text(c.getString(R.string.pe_edit), 17f, MT.blue, center = true).apply {
        setPadding(dp(12), dp(10), dp(12), dp(10)); pressable { pick() }
    }, lp(WRAP, WRAP).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = c.dp(16) })

    // ── the name fields; their own name revealed under them when mine stands over it (iOS overName) ──
    val first = field(R.string.pe_first, shown.substringBefore(' '))
    val last = field(R.string.pe_last, if (shown.contains(' ')) shown.substringAfter(' ') else "")
    val overName = chat?.pin != null && theirs.isNotEmpty()
    val original = c.text(theirs, 13f, MT.gray).apply { setPadding(dp(16), dp(6), dp(16), 0); visibility = View.GONE }   // USER-DATA: their own name
    body.addView(if (overName) group(first, last, c.text(c.getString(R.string.pe_show_original), 17f, MT.blue).apply {
        setPadding(dp(16), dp(13), dp(16), dp(13))
        pressable { original.visibility = if (original.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
    }) else group(first, last), lp())
    body.addView(original, lp())

    // ── the note, and «Restore original photo» while a picture of mine stands ──
    val note = field(R.string.pe_note, chat?.note.orEmpty(), multi = true)
    val second = c.vstack(Gravity.NO_GRAVITY)
    drawPhotoRows = {
        second.removeAllViews()
        val mine = photo != null || (!photoCleared && Book.myFace(ref).exists())
        if (note.parent != null) (note.parent as ViewGroup).removeView(note)
        second.addView(if (!mine) group(note) else group(note, c.hstack {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            val f = Book.face(ref)
            addView(c.avatar(if (f.exists()) BitmapFactory.decodeFile(f.path) else null, theirs.ifBlank { "?" }, 36), lp(dp(36), dp(36)).apply { marginEnd = dp(12) })
            addView(c.text(c.getString(R.string.pe_restore_photo), 17f, MT.blue))
            pressable { photo = null; photoCleared = true; drawFace(); drawPhotoRows() }
        }), lp())
    }
    drawPhotoRows()
    body.addView(second, lp().apply { topMargin = c.dp(22) })

    fun keysAway() = c.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(body.windowToken, 0)
    return FrameLayout(c).apply {
        setBackgroundColor(Color.BLACK)
        addView(CrestGround(c), FrameLayout.LayoutParams(MATCH, MATCH))
        addView(ScrollView(c).apply { isVerticalScrollBarEnabled = false; addView(body) }, FrameLayout.LayoutParams(MATCH, MATCH))
        addView(c.text(c.getString(R.string.pe_edit), 17f, Color.WHITE, bold = true, center = true),
            FrameLayout.LayoutParams(WRAP, dp(44), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(6) })
        addView(FrameLayout(c).apply {
            background = c.glassPlate(oval = true)
            addView(c.icon(R.drawable.ic_arrow_back_ios_new, Color.WHITE), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            pressable { keysAway(); onClose() }
        }, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.START).apply { setMargins(dp(14), dp(6), 0, 0) })
        addView(FrameLayout(c).apply {
            background = c.glassPlate(oval = true)
            contentDescription = c.getString(R.string.pe_done)
            addView(c.icon(R.drawable.ic_check, Color.WHITE), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            pressable {
                keysAway()
                Book.saveCard(ref, first.text.toString(), last.text.toString(), note.text.toString(), photo, photoCleared)
                onClose()
            }
        }, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(6), dp(14), 0) })
        // the marks stay inside the system bars, and the keys push the page up rather than over it (as the chat's page does)
        setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom); insets
        }
    }
}
