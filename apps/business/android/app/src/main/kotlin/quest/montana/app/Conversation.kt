package quest.montana.app

import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.Date

// ─────────────────────────── what a row says of a letter (iOS MTRowLetter) ───────────────────────────

/** The words a letter shows in the list and the banner: its text, or the name of what it carries. */
fun letterWords(c: Context, t: String, meta: String? = null): String = when {
    t.startsWith(Marks.VOICE) -> c.getString(R.string.voice_message)
    t.startsWith(Marks.MEDIA) -> mediaWords(c, meta?.let { runCatching { JSONObject(it) }.getOrNull() } ?: Media.inline(t))
    t.startsWith(Marks.STICKER) -> t.removePrefix(Marks.STICKER)
    t.startsWith(Marks.LONG) -> c.getString(R.string.long_letter)
    else -> t
}
/** The same words for a letter of the book: its manifest, once read, names what it carries. */
fun letterWords(c: Context, m: Msg): String = letterWords(c, m.text, m.meta)

/**
 * THE WORDS OF A MEDIA LETTER (iOS MTRowLetter.mediaWords, mediaPreview): one answer for the row, the banner and the reply
 * strip, read from the manifest — a round note is a video message, as a voice is a voice message; a sticker is a sticker, a
 * moving picture a GIF. A manifest not read yet (sealed in its blob) is «Media».
 */
fun mediaWords(c: Context, man: JSONObject?): String {
    if (man == null) return c.getString(R.string.lw_media)
    val cap = man.optString("cap").trim()
    val name = man.optString("n").ifEmpty { man.optString("name") }
    return when (man.optString("k")) {
        "img" -> if (name == Stickers.CARD) c.getString(R.string.lw_sticker) else "📷 " + cap.ifEmpty { c.getString(R.string.lw_photo) }
        "vid", "video" -> "📹 " + if (man.optBoolean("r")) c.getString(R.string.lw_video_message) else cap.ifEmpty { c.getString(R.string.lw_video) }
        "aud" -> c.getString(R.string.voice_message)
        "doc" -> if (name.lowercase().endsWith(".gif")) c.getString(R.string.lw_gif) else "📄 " + name.ifEmpty { c.getString(R.string.file) }
        else -> c.getString(R.string.lw_media)
    }
}

/** The row's words without the glyph the vocabulary puts first, when a thumbnail stands there (iOS MontanaRowWords.bare). */
fun bareWords(p: String): String = listOf("🎤 ", "📹 ", "📷 ", "📄 ", "📞 ").firstOrNull { p.startsWith(it) }?.let { p.removePrefix(it) } ?: p

private val AUDIO_NAMES = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "aif", "aiff")

/**
 * THE ROW'S ONE WORD FOR WHAT A LETTER CARRIES (iOS ChatStore.rowMediaKind): the voice, the round note, the video, the photo,
 * music, a file — by the manifest, once the file is here — and the link of a letter of words. The thumbnail draws by it.
 */
fun rowKind(m: Msg): String? {
    if (!m.text.startsWith(Marks.MEDIA)) return if (LinkPreview.webURLs(m.text).isNotEmpty()) "link" else null
    val f = m.file?.let { java.io.File(it) }?.takeIf { it.exists() } ?: return null
    val man = m.meta?.let { runCatching { JSONObject(it) }.getOrNull() } ?: Media.inline(m.text)
    return when (man?.optString("k")) {
        "aud" -> "aud"
        "vid", "video" -> if (man.optBoolean("r")) "vnote" else "vid"
        "img" -> "img"
        "doc" -> if (man.optString("n").substringAfterLast('.', "").lowercase() in AUDIO_NAMES || f.extension.lowercase() in AUDIO_NAMES) "music" else "doc"
        else -> null
    }
}

private val rowPosters = android.util.LruCache<String, android.graphics.Bitmap>(48)

/** The small picture of a letter: the face its manifest carries, else the file's own first frame, kept while the list lives. */
private fun rowPoster(c: Context, m: Msg, kind: String): android.graphics.Bitmap? {
    rowPosters.get(m.mid)?.let { return it }
    val man = m.meta?.let { runCatching { JSONObject(it) }.getOrNull() } ?: Media.inline(m.text)
    val pic = Media.thumbOf(man)
        ?: m.file?.let { java.io.File(it) }?.takeIf { it.exists() }?.let { Media.preview(c, it, if (kind == "img") "img" else "vid", 96) }
    pic?.let { rowPosters.put(m.mid, it) }
    return pic
}

/**
 * ONE THUMBNAIL OF WHAT A LETTER CARRIES (iOS MTLetterThumb; side 20 in the list row): the voice's round of glass with the
 * quiet prism sheen (MTVoiceRound), the round note's window with its poster (MontanaNoteFrame), the photo and the video's
 * poster, and the quiet plate with the system's glyph for music, a file, a link.
 */
fun Context.letterThumb(m: Msg, kind: String, sideDp: Int = 20): View {
    val side = dp(sideDp)
    fun plate(glyph: Int) = FrameLayout(this).apply {
        background = rounded(Color.rgb(41, 41, 41), sideDp * 0.22f)
        addView(icon(glyph, MT.gray), FrameLayout.LayoutParams(side / 2, side / 2, Gravity.CENTER))
    }
    return when (kind) {
        "aud" -> FrameLayout(this).apply {
            background = android.graphics.drawable.LayerDrawable(arrayOf(glassPlate(oval = true), GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                gradientType = GradientDrawable.SWEEP_GRADIENT
                colors = intArrayOf(Color.argb(56, 50, 173, 230), Color.argb(56, 175, 82, 222), Color.argb(56, 255, 45, 85), Color.argb(56, 50, 173, 230))
                setStroke(1, Color.argb(89, 255, 255, 255))
            }))
            addView(icon(R.drawable.ic_play_fill, Color.WHITE), FrameLayout.LayoutParams(side * 2 / 5, side * 2 / 5, Gravity.CENTER))
        }
        "vnote", "img", "vid" -> {
            val pic = rowPoster(this, m, kind)
            if (pic == null && kind != "vnote") plate(if (kind == "img") R.drawable.ic_photo else R.drawable.ic_peer_video)
            else ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                if (pic != null) setImageBitmap(pic) else setBackgroundColor(Color.BLACK)
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, o: android.graphics.Outline) =
                        if (kind == "vnote") o.setOval(0, 0, v.width, v.height) else o.setRoundRect(0, 0, v.width, v.height, v.width * 0.22f)
                }
                clipToOutline = true
            }
        }
        "music" -> plate(R.drawable.ic_play_fill)
        "link" -> plate(R.drawable.ic_link)
        else -> plate(R.drawable.ic_set_doc)
    }
}

/** The face of a correspondent: the face laid beside the pipe, else the drawn initial (iOS MontanaAvatar). */
fun Context.peerFace(ref: String, name: String, sizeDp: Int): View {
    val f = Book.shownFace(ref)
    val bmp = if (f.exists()) BitmapFactory.decodeFile(f.path) else null
    return avatar(bmp, name.ifBlank { "?" }, sizeDp)
}

// ─────────────────────────── the banner (iOS MontanaNotify) ───────────────────────────

object Notify {
    private const val CHANNEL = "letters"
    fun letter(ref: String, text: String, who: String? = null) {
        val c = Book.ctx
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (!Prefs.bool("notifMessages", true)) return
        if (ChatMarks.isMuted(ref)) return   // muted: no banner and no sound (iOS willPresent «why=muted»)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, c.getString(R.string.channel_letters), NotificationManager.IMPORTANCE_HIGH))
        val chat = Book.chat(ref)
        val title = if (Prefs.bool("notifSender", true)) chat?.shown?.ifBlank { null } ?: c.getString(R.string.peer) else "Montana"
        val shown = if (Prefs.bool("notifPreview", true)) letterWords(c, text) else c.getString(R.string.new_message)
        val body = if (who.isNullOrEmpty()) shown else "$who: $shown"   // a group's letter says who wrote it (iOS presentGroup)
        val open = PendingIntent.getActivity(c, ref.hashCode(),
            c.packageManager.getLaunchIntentForPackage(c.packageName)?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) ?: Intent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)   // USER-DATA: the correspondent's name
            .setContentText(body)     // USER-DATA: the letter
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { nm.notify(ref.hashCode(), n) }
    }
    fun clear(ref: String) { Book.ctx.getSystemService(NotificationManager::class.java)?.cancel(ref.hashCode()) }
}

// ─────────────────────────── the chats as rows (iOS MTChatListView rows) ───────────────────────────

/** One conversation in the list: the face, the name, the last letter, its time and the unread count. */
fun chatRow(act: MainActivity, chat: Chat): View {
    val c: Context = act
    val last = chat.last
    return c.hstack {
        setPadding(dp(16), dp(10), dp(16), dp(10))
        addView(c.peerFace(chat.ref, chat.shown, 54), lp(dp(54), dp(54)).apply { marginEnd = dp(12) })
        addView(c.vstack(Gravity.NO_GRAVITY) {
            addView(c.hstack {
                addView(c.text(chat.shown.ifBlank { c.getString(R.string.peer) }, 17f, Color.WHITE, bold = true).apply { singleLineEllipsis() }, lp(0, WRAP, 1f))   // USER-DATA: the name
                // a muted chat wears the struck bell after its name (iOS ChatRow muted)
                if (ChatMarks.isMuted(chat.ref)) addView(c.icon(R.drawable.ic_bell_off, MT.gray, 14), lp(dp(14), dp(14)).apply { marginEnd = dp(6) })
                if (last != null) {
                    // the ladder's dots left of the hour, only when the last word was mine; a letter that did not go, the red mark
                    if (last.mine) addView(if (last.state == -1) c.failedMark() else c.deliveryDots(Ladder.of(chat.ref, last).first),
                        lp(WRAP, WRAP).apply { marginEnd = dp(6) })
                    addView(c.text(rowTime(c, last.at), 13f, MT.gray))
                }
            }, lp())
            addView(c.hstack {
                // THE THUMBNAIL BEFORE THE WORDS (iOS MTLetterThumb, side 20, spacing 6): the voice's round and the round note's
                // poster stand in the row as in the player bar; the words then carry no glyph of their own
                val kind = last?.let { rowKind(it) }
                if (last != null && kind != null) addView(c.letterThumb(last, kind), lp(dp(20), dp(20)).apply { marginEnd = dp(6); gravity = Gravity.CENTER_VERTICAL })
                val words = last?.let { letterWords(c, it) } ?: ""
                addView(c.text(if (kind != null) bareWords(words) else words, 15f, MT.gray).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }, lp(0, WRAP, 1f))   // USER-DATA: the last letter
                if (chat.unread > 0) addView(c.text(chat.unread.toString(), 13f, Color.WHITE, bold = true, center = true).apply {
                    // a muted chat's number is grey, as iOS draws it
                    background = c.rounded(if (ChatMarks.isMuted(chat.ref)) MT.gray else MT.blue, 11); setPadding(dp(7), dp(1), dp(7), dp(1)); minWidth = dp(22)
                }, lp(WRAP, WRAP).apply { marginStart = dp(6) })
                // the hand mark «unread» where nothing is unread: the row's dot (iOS forcedUnread)
                else if (ChatMarks.handMark(chat.ref)) addView(c.icon(R.drawable.ic_dot, MT.blue, 12), lp(dp(12), dp(12)).apply { marginStart = dp(6) })
                // a pinned chat with nothing unread shows its pin (iOS ChatRow pinned)
                if (chat.unread == 0 && !ChatMarks.handMark(chat.ref) && ChatMarks.isPinned(chat.ref))
                    addView(c.icon(R.drawable.ic_pin, MT.gray, 16), lp(dp(16), dp(16)).apply { marginStart = dp(6) })
            }, lp())
        }, lp(0, WRAP, 1f))
        pressable { act.push { close -> conversationPage(act, chat.ref, close) } }
        // the list sets the long press itself (ChatList.listRow: the person's menu)
    }
}

// ─────────────────────────── the delivery ladder (iOS MTDeliveryLine, MTDeliveryDots, MTPlayed) ───────────────────────────

/**
 * THE LADDER OF ONE'S OWN LETTER: sending → sent → delivered → read, each rung with its one word. A voice of mine is played,
 * not read: its third rung is the correspondent's own playing («Listened»); a correspondent whose build has said «played»
 * once keeps a mere «read» of a voice at «delivered» — the chat opened is no playing.
 */
object Ladder {
    private fun isVoice(m: Msg) = m.text.startsWith(Marks.MEDIA) &&
        ((m.meta?.let { runCatching { JSONObject(it) }.getOrNull() } ?: Media.inline(m.text))?.optString("k") == "aud")

    /** The rung the dots stand on (0…3) and the word's resource. */
    fun of(ref: String, m: Msg): Pair<Int, Int> {
        if (m.state == -1) return 0 to R.string.ladder_failed
        if (isVoice(m)) {
            if (m.heard) return 3 to R.string.ladder_listened
            if (m.state == 3 && Prefs.bool("plcap_$ref", false)) return 2 to R.string.ladder_delivered
        }
        return m.state to when (m.state) {
            0 -> R.string.ladder_sending; 1 -> R.string.ladder_sent; 2 -> R.string.ladder_delivered; else -> R.string.ladder_read
        }
    }

    /** The rung's moment: the hour today, the day and the hour before (iOS MTDeliveryLine.moment). */
    fun moment(c: Context, at: Long): String {
        val t = android.text.format.DateFormat.getTimeFormat(c).format(Date(at))
        return if (DateUtils.isToday(at)) t else DateUtils.formatDateTime(c, at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH) + " " + t
    }
}

/** THE THREE DOTS (iOS MTDeliveryDots): five points each, five apart; green once their rung is reached, dark grey before. */
fun Context.deliveryDots(rung: Int): View = hstack {
    gravity = Gravity.CENTER_VERTICAL
    for (k in 1..3) addView(View(context).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (rung >= k) MT.green else Color.rgb(77, 77, 77)) }
    }, lp(dp(5), dp(5)).apply { if (k > 1) marginStart = dp(5) })
}

/** THE LADDER LINE (iOS MTDeliveryLine): the dots, the one word of the rung and — over the menu — the moment it was reached. */
fun Context.deliveryLine(ref: String, m: Msg, withMoment: Boolean = false): View = hstack {
    gravity = Gravity.CENTER_VERTICAL
    val (rung, word) = Ladder.of(ref, m)
    addView(deliveryDots(rung))
    addView(text(getString(word), 12f, Color.WHITE, bold = true), lp(WRAP, WRAP).apply { marginStart = dp(5) })
    if (withMoment) addView(text(Ladder.moment(context, m.statusMoment), 12f, Color.WHITE), lp(WRAP, WRAP).apply { marginStart = dp(5) })
}

/** THE RED MARK of a letter that did not go (iOS MTFailedMark): the bubble's corner and the chat row wear the same one. */
fun Context.failedMark(): View = text("!", 9f, Color.WHITE, bold = true, center = true).apply {
    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(SysColor.red) }
    layoutParams = LinearLayout.LayoutParams(dp(12), dp(12))
    gravity = Gravity.CENTER
    setPadding(0, 0, 0, 0)
}

// ─────────────────────────── the conversation (iOS ChatConversationView) ───────────────────────────

/**
 * THE CONVERSATION (iOS ChatConversationView): the bar with the back mark, the person's name and face; the letters, newest at
 * the foot, a day's plate over each new day, each letter with its time, its ticks when it is one's own, «edited» when it was,
 * the quote it answers and the reactions it wears; the field on glass with the send artwork. A long press on a letter opens its
 * menu as iOS draws it: the quick reactions over the letter and the actions under it — Reply, Copy, Edit (one's own), Delete
 * (for me / for everyone). The feed follows the book live: a letter that lands, a receipt, a reaction redraw it.
 */
private var noteRing: NoteRing? = null
private var noteTime: TextView? = null

/** `jump` — a letter found by the search: the chat opens on it, and it glows once (iOS openChat(jump:)). */
fun conversationPage(act: MainActivity, ref: String, onClose: () -> Unit, jump: String? = null): View {
    val c: Context = act
    val group = Groups.isKey(ref)   // a group's feed: no pipe, no presence, no doors (iOS MTGroup.isKey)
    val column = c.vstack(Gravity.NO_GRAVITY) { setPadding(dp(10), dp(8), dp(10), dp(8)) }
    val draftRow = FrameLayout(c).apply { setPadding(c.dp(10), 0, c.dp(10), c.dp(8)); visibility = View.GONE }
    val scroll = ScrollView(c).apply {
        isVerticalScrollBarEnabled = false
        isFillViewport = true
        // the feed, and under it their live draft (iOS LiveDraftBubble at the feed's foot)
        addView(FrameLayout(c).apply { addView(c.vstack(Gravity.NO_GRAVITY) { addView(column, lp()); addView(draftRow, lp()) }, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM)) })
    }
    fun toBottom() = scroll.post { scroll.scrollTo(0, scroll.getChildAt(0)?.height ?: 0) }

    var replyTo: Msg? = null
    var editing: Msg? = null
    // THE CHAT'S SELECTION (iOS selectingMsgs, selectedMsgs): «Select» in a letter's menu begins it
    var selecting = false
    val picked = linkedSetOf<String>()
    lateinit var drawFoot: () -> Unit

    val field = EditText(c).apply { setText(Prefs.str("draft.$ref", "")) }
    fun hideKeys() = c.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(field.windowToken, 0)
    fun showKeys() { field.requestFocus(); c.getSystemService(InputMethodManager::class.java).showSoftInput(field, 0) }

    // THE LINE OVER THE FIELD (iOS the reply/edit strip): what the next letter answers, or which letter is being changed.
    val stripTitle = c.text("", 13f, MT.blue, bold = true)
    val stripWords = c.text("", 14f, Color.WHITE).apply { singleLineEllipsis() }
    val strip = c.hstack {
        visibility = View.GONE
        setPadding(dp(16), dp(6), dp(8), dp(2))
        addView(View(c).apply { setBackgroundColor(MT.blue) }, lp(dp(3), dp(34)).apply { marginEnd = dp(8) })
        addView(c.vstack(Gravity.NO_GRAVITY) { addView(stripTitle); addView(stripWords) }, lp(0, WRAP, 1f))
        addView(c.icon(R.drawable.ic_close, MT.gray).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            pressable { if (editing != null) field.setText(""); replyTo = null; editing = null; this@hstack.visibility = View.GONE }
        }, lp(dp(36), dp(36)))
    }
    fun showStrip() {
        val r = replyTo; val e = editing
        strip.visibility = if (r == null && e == null) View.GONE else View.VISIBLE
        if (e != null) { stripTitle.text = c.getString(R.string.editing); stripWords.text = letterWords(c, e) }
        else if (r != null) {
            stripTitle.text = if (r.mine) Prefs.userName.ifBlank { c.getString(R.string.reply) } else Book.chat(ref)?.shown?.ifBlank { null } ?: c.getString(R.string.peer)
            stripWords.text = letterWords(c, r)   // USER-DATA
        }
    }

    // ── the feed ──
    fun redraw() {
        val chat = Book.chat(ref) ?: return
        column.removeAllViews()
        var lastDay = -1L
        for (m in chat.msgs) {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = m.at }
            val day = cal.get(java.util.Calendar.YEAR) * 1000L + cal.get(java.util.Calendar.DAY_OF_YEAR)
            if (day != lastDay) {
                lastDay = day
                val words = if (DateUtils.isToday(m.at)) c.getString(R.string.today) else DateUtils.formatDateTime(c, m.at, DateUtils.FORMAT_SHOW_DATE)
                column.addView(FrameLayout(c).apply {
                    addView(c.text(words, 13f, Color.WHITE).apply { background = c.glassPlate(); setPadding(dp(10), dp(3), dp(10), dp(3)) },
                        FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
                }, lp().apply { topMargin = c.dp(8); bottomMargin = c.dp(8) })
            }
            val b = letterBubble(c, m, chat)
            if (selecting) {
                // WHILE THE CHAT SELECTS a letter is a choice: a circle on the left, a tap chooses (iOS the selection rows)
                val chosen = m.mid in picked
                column.addView(c.hstack {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(FrameLayout(c).apply {
                        background = if (chosen) c.rounded(MT.accent, 11) else c.rounded(Color.TRANSPARENT, 11, Color.WHITE)
                        if (chosen) addView(c.icon(R.drawable.ic_check, Color.BLACK, 14), FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER))
                    }, lp(dp(22), dp(22)).apply { marginEnd = dp(8) })
                    addView(b.apply { isClickable = false }, lp(0, WRAP, 1f))
                    pressable { if (!picked.remove(m.mid)) picked.add(m.mid); redraw(); drawFoot() }
                }, lp().apply { topMargin = c.dp(3) })
                continue
            }
            // THE LADDER UNDER EVERY OWN BUBBLE (the author's word 29.09: «under every message, each on its own»): each letter its own rung
            if (m.mine) (b.getChildAt(0) as LinearLayout).addView(c.deliveryLine(ref, m),
                LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = c.dp(3); marginEnd = c.dp(4) })
            b.getChildAt(0).setOnLongClickListener { letterMenu(act, ref, m, onReply = { replyTo = m; editing = null; showStrip(); showKeys() },
                onEdit = { editing = m; replyTo = null; field.setText(m.text); field.setSelection(field.text.length); showStrip(); showKeys() },
                onForward = { hideKeys(); act.push { close -> forwardPage(act, ref, listOf(m), close) { to -> close(); if (to != ref) { onClose(); act.push { c2 -> conversationPage(act, to, c2) } } } } },
                onSelect = { hideKeys(); selecting = true; picked.clear(); picked.add(m.mid); redraw(); drawFoot() }, group = group); true }
            // the pull to the left answers, as the menu's «Reply»; a group's feed takes no reply until its field writes into the group
            if (!group) b.onReply = { replyTo = m; editing = null; showStrip(); showKeys() }
            b.tag = m.mid
            column.addView(b, lp().apply { topMargin = c.dp(3) })
        }
    }

    // ── sending (iOS sendMessage) ──
    var typedAt = 0L
    field.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
        override fun afterTextChanged(s: Editable?) {
            if (editing == null) Prefs.setStr("draft.$ref", s?.toString() ?: "")
            // «TYPING…» TO THEM (iOS: a word on the lane, at most every three seconds, only while there are words)
            if (!s.isNullOrBlank() && System.currentTimeMillis() - typedAt > 3000) { typedAt = System.currentTimeMillis(); Presence.sayTyping(ref) }
            // MY WORDS AS I TYPE THEM (iOS sendDraft): the field and its caret; emptied — sent or erased — the draft ends
            if (editing == null) LiveDraft.say(ref, s?.toString()?.takeIf { it.isNotBlank() } ?: "", field.selectionEnd, replyTo?.mid ?: "")
        }
    })
    val bar = ChatInputBar(act, field) {
        val words = field.text.toString().trim()
        field.setText("")
        val e = editing
        if (e != null) {
            // THE NEW WORDS OF A LETTER ALREADY SENT (iOS editMessage): the row changes here, the peer is told by ED:{sid,tx}.
            editing = null
            if (words != e.text) {
                Book.edit(ref) { ch -> ch.msgs.find { it.mid == e.mid }?.let { it.text = words; it.edited = true } }
                Post.send(ref, Marks.mintMid(), Marks.EDIT + JSONObject().put("sid", e.mid).put("tx", words))
            }
        } else {
            val r = replyTo
            replyTo = null
            val mid = Marks.mintMid()
            val qt = r?.let { letterWords(c, it).take(200) }
            Book.edit(ref) { it.msgs.add(Msg(mid, words, true, Marks.birthMs(mid) ?: System.currentTimeMillis(), qt = qt, qm = r?.mid)) }
            Post.send(ref, mid, words, qt, r?.mid)
            LinkPreview.attend(ref, mid, words, qt, r?.mid)   // a link's card follows the letter (iOS MTLinkCards.attend)
        }
        showStrip()
        toBottom()
    }
    // THE GALLERY AND THE FILE KEYS SEND FOR REAL (iOS PhotosPicker / fileImporter → MontanaMedia): read, sealed, laid, lettered.
    fun sendPicked(asDoc: Boolean): (android.net.Uri?) -> Unit = { uri ->
        if (uri != null) Thread {
            val p = Media.read(c, uri, asDoc)
            if (p == null) MainThread.post { android.widget.Toast.makeText(c, R.string.media_failed, android.widget.Toast.LENGTH_LONG).show() }
            else if (asDoc) { Media.send(c, ref, p, ""); MainThread.post { toBottom() } }   // a file leaves as it is (iOS fileImporter)
            // A PICTURE OR A VIDEO GETS ITS CAPTION FIRST (iOS AttachSheet): the words already typed stand in it (the author's word 19.09)
            else MainThread.post {
                hideKeys()
                val draft = if (editing == null) field.text.toString().trim() else ""
                lateinit var close: () -> Unit
                close = act.overlay(captionPage(act, p, draft, onCancel = { close() }) { caption ->
                    close()
                    // THE DRAFT BECOMES THE CAPTION (iOS consumeDraftAsCaption): what was typed leaves the field with the picture
                    if (draft.isNotEmpty() && caption.startsWith(draft)) { field.setText(""); Prefs.setStr("draft.$ref", "") }
                    Thread { Media.send(c, ref, p, caption); MainThread.post { toBottom() } }.start()
                })
            }
        }.start()
    }
    // THE CAMERA BESIDE THE PHOTOS (iOS MTGalleryPick: the camera's tile, «All photos»): a shot goes the picked picture's road
    bar.onGallery = { hideKeys(); cameraOrPhotos(act) { how -> if (how == null) act.pickVisual(sendPicked(false)) else Capture.shoot(act, video = how) { u -> if (u != null) sendPicked(false)(u) } } }
    // «SEND LATER» (iOS: the send key's hold): the words leave the field and wait for their moment (Schedule.kt)
    bar.onSendLater = { v -> sendLaterMenu(act, v, ref, { field.text.toString() }) { field.setText(""); Prefs.setStr("draft.$ref", "") } }
    // A CONTACT OF THE PHONE (iOS ContactPicker): its name and number leave as one letter
    bar.onContact = { hideKeys(); act.pickContact { u -> u?.let { contactLetter(c, it) }?.let { words -> sendWords(ref, words); toBottom() } } }
    bar.onFile = { act.openDocument(sendPicked(true)) }
    bar.onStickers = { hideKeys(); Stickers.panel(act, ref) }   // a hold on the emoji key (Stickers.kt)
    // THE VOICE (iOS the held mark): the tape starts under the finger and leaves as a media letter of kind «aud».
    // THE SWELL WHILE SPEAKING (iOS the recording row): the red mark, the running time and the wave that rises with the voice.
    val liveWave = WaveView(c).apply { played = Color.WHITE }
    val liveTime = c.text("0:00", 15f, Color.WHITE)
    val dot = View(c).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(SysColor.red) } }
    // THE CONTROLS OF A LOCKED TAPE (iOS MTHoldOverlayView barRow): each the bar's round glass on a 44-point target
    fun ctl(icon: Int, label: Int, work: () -> Unit): FrameLayout = FrameLayout(c).apply {
        background = c.glassPlate(oval = true); contentDescription = c.getString(label)
        addView(c.icon(icon, Color.WHITE), FrameLayout.LayoutParams(c.dp(20), c.dp(20), Gravity.CENTER))
        visibility = View.GONE
        pressable(work)
    }
    fun paint(key: FrameLayout, pausedNow: Boolean) {
        (key.getChildAt(0) as? ImageView)?.let { it.setImageResource(if (pausedNow) R.drawable.ic_record_circle else R.drawable.ic_pause_fill)
            it.imageTintList = android.content.res.ColorStateList.valueOf(if (pausedNow) SysColor.red else Color.WHITE) }
    }
    val voiceBin = ctl(R.drawable.ic_close, R.string.cancel) { bar.cancelRecording() }
    lateinit var voicePause: FrameLayout
    voicePause = ctl(R.drawable.ic_pause_fill, R.string.rec_pause) { VoiceTape.togglePause(); paint(voicePause, VoiceTape.paused) }
    val recStrip = c.hstack {
        visibility = View.GONE
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(6), dp(18), dp(6))
        addView(voiceBin, lp(dp(40), dp(40)).apply { marginEnd = dp(10) })
        addView(dot, lp(dp(10), dp(10)).apply { marginEnd = dp(8) })
        addView(liveTime, lp(WRAP, WRAP).apply { marginEnd = dp(12) })
        addView(liveWave, lp(0, WRAP, 1f))
        addView(voicePause, lp(dp(40), dp(40)).apply { marginStart = dp(10) })
    }
    // THE LOCK OVER THE KEY (iOS lockPlate): the padlock and the chevron above the held key; the chevron folds as the finger rises
    val padlock = c.icon(R.drawable.ic_lock_open, Color.WHITE)
    val chevron = c.icon(R.drawable.ic_chevron_up, Color.WHITE)
    val lockPlate = c.vstack(Gravity.CENTER_HORIZONTAL) {
        background = c.glassPlate().apply { cornerRadius = dp(18).toFloat(); setColor(Color.argb(170, 40, 40, 44)) }
        setPadding(0, dp(14), 0, dp(14))
        addView(padlock, lp(dp(24), dp(24)))
        addView(chevron, lp(dp(18), dp(18)).apply { topMargin = dp(8) })
        visibility = View.GONE
    }
    var noteControls: View? = null
    var plateHome: FrameLayout? = null
    fun plateLp() = FrameLayout.LayoutParams(c.dp(52), WRAP, Gravity.BOTTOM or Gravity.END).apply { bottomMargin = c.dp(150); marginEnd = c.dp(8) }
    /** The plate stands where the tape is watched: over the chat for a voice, over the note's window for a note. */
    fun movePlate(to: FrameLayout?) { val t = to ?: return; if (lockPlate.parent !== t) { (lockPlate.parent as? android.view.ViewGroup)?.removeView(lockPlate); t.addView(lockPlate, plateLp()) } }
    fun endStrip() { recStrip.visibility = View.GONE; VoiceTape.onLevel = null; dot.animate().cancel() }
    bar.onRecLift = { l ->
        lockPlate.visibility = View.VISIBLE; lockPlate.translationY = -c.dp(9) * l
        padlock.setImageResource(if (l >= 1f) R.drawable.ic_lock else R.drawable.ic_lock_open)
        chevron.alpha = 1f - l
    }
    bar.onRecLocked = {
        lockPlate.visibility = View.GONE
        voiceBin.visibility = View.VISIBLE; voicePause.visibility = View.VISIBLE; paint(voicePause, false)
        noteControls?.visibility = View.VISIBLE
    }
    bar.onRecIdle = {
        lockPlate.visibility = View.GONE
        voiceBin.visibility = View.GONE; voicePause.visibility = View.GONE
        noteControls = null
        movePlate(plateHome)
    }
    bar.onVoiceStart = {
        val on = VoiceTape.start(act)
        if (on) {
            liveWave.bars = FloatArray(0); liveWave.progress = 0f
            recStrip.visibility = View.VISIBLE
            dot.animate().alpha(0.2f).setDuration(600).withEndAction { dot.alpha = 1f }.start()
            VoiceTape.onLevel = { a ->
                liveWave.push(a)
                val s = VoiceTape.seconds.toInt(); liveTime.text = "%d:%02d".format(s / 60, s % 60)
                dot.alpha = if ((VoiceTape.levels.size / 20) % 2 == 0) 1f else 0.3f
            }
        }
        on
    }
    // THE ROUND NOTE UNDER THE FINGER (iOS MontanaVideoNoteHold): the window over the feed, the ring for the time; a tap turns
    // the camera; let go to send, slide left to drop. What the window shows is the file itself.
    var note: NoteRecorder? = null
    var noteClose: (() -> Unit)? = null
    val noteTick = object : Runnable {
        override fun run() { val n = note ?: return; noteRing?.progress = (n.elapsed / NoteRecorder.MAX_SECONDS).toFloat(); val s = n.elapsed.toInt(); noteTime?.text = "%d:%02d".format(s / 60, s % 60); MainThread.later(100, this) }
    }
    bar.onNoteStart = start@{
        if (c.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED ||
            !VoiceTape.granted(c)) {
            act.requestPermissions(arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO), 8); return@start false
        }
        val tv = TextureView(c)
        val ring = NoteRing(c); val time = c.text("0:00", 15f, Color.WHITE)
        noteRing = ring; noteTime = time
        val side = c.dp(280)
        lateinit var notePause: FrameLayout
        notePause = ctl(R.drawable.ic_pause_fill, R.string.rec_pause) { note?.togglePause(); paint(notePause, note?.paused == true) }
        val controls = c.hstack {
            gravity = Gravity.CENTER
            visibility = View.GONE   // stands once the tape is locked (iOS: the bin, the flip, the pause, and the arrow that sends)
            listOf(ctl(R.drawable.ic_close, R.string.cancel) { bar.cancelRecording() },
                   ctl(R.drawable.ic_camera_rotate, R.string.rec_flip) { note?.flip() },
                   // BOTH CAMERAS (iOS the dual seat): only where the hardware streams a back and a front camera together
                   *(if (NoteRecorder.dualPair(c) != null) arrayOf(ctl(R.drawable.ic_dual_camera, R.string.rec_dual) { note?.toggleDual() }) else emptyArray()),
                   notePause,
                   ctl(R.drawable.ic_arrow_circle_up, R.string.send) { bar.stopRecording() }).forEachIndexed { i, k ->
                k.visibility = View.VISIBLE
                addView(k, lp(dp(48), dp(48)).apply { if (i > 0) marginStart = dp(22) })
            }
        }
        noteControls = controls
        val page = FrameLayout(c).apply {
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            addView(controls, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { bottomMargin = c.dp(60) })
            addView(ring, FrameLayout.LayoutParams(side + c.dp(16), side + c.dp(16), Gravity.CENTER))
            addView(tv.round().apply { setOnClickListener { note?.flip() } }, FrameLayout.LayoutParams(side, side, Gravity.CENTER))
            addView(time.apply { setShadowLayer(6f, 0f, 0f, Color.BLACK) }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER).apply { topMargin = side / 2 + c.dp(30) })
        }
        noteClose = act.overlay(page)
        movePlate(page)
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                val r = NoteRecorder(c, tv)
                r.onMax = { MainThread.post { bar.stopRecording() } }
                if (r.start()) { note = r; MainThread.later(100, noteTick) } else { noteClose?.invoke(); noteClose = null }
            }
            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
        }
        true
    }
    bar.onNoteEnd = { dropped ->
        val r = note; note = null
        Thread {
            val got = r?.stop(keep = !dropped)
            MainThread.post { noteClose?.invoke(); noteClose = null }
            got?.let { (f, secs) -> Media.send(c, ref, Media.Picked(f.readBytes(), "vid", "mp4", null, secs, round = true), ""); f.delete(); MainThread.post { toBottom() } }
        }.start()
    }
    bar.onVoiceEnd = { dropped ->
        val wave = VoiceTape.waveform()
        endStrip()
        VoiceTape.stop(keep = !dropped)?.let { (f, secs) ->
            Thread { Media.send(c, ref, Media.Picked(f.readBytes(), "aud", "m4a", null, secs, wave), ""); f.delete(); MainThread.post { toBottom() } }.start()
        }
    }

    // ── the bar: the back mark, the face and the name; the face's press offers the chat's own actions ──
    val chat0 = Book.chat(ref)
    val title = c.text(chat0?.shown?.ifBlank { null } ?: c.getString(R.string.peer), 17f, Color.WHITE, bold = true).apply { singleLineEllipsis() }   // USER-DATA
    val faceSlot = FrameLayout(c)
    // THE PRESENCE LINE UNDER THE NAME (iOS presenceWord): «typing…» / «in chat» / «online» in green, else «last seen…»
    val presenceLine = c.text("", 12f, MT.gray).apply { singleLineEllipsis(); visibility = View.GONE }
    fun drawPresence() {
        if (group) {   // a group has no presence of one person: its people's count (iOS the group chat's head)
            presenceLine.text = Groups.peopleLine(c, ref) ?: ""
            presenceLine.setTextColor(MT.gray)
            presenceLine.visibility = if (presenceLine.text.isEmpty()) View.GONE else View.VISIBLE
            return
        }
        val w = Presence.word(c, ref)
        presenceLine.visibility = if (w == null) View.GONE else View.VISIBLE
        presenceLine.text = w?.first ?: ""
        presenceLine.setTextColor(if (w?.second == true) SysColor.green else MT.gray)
    }
    drawPresence()
    fun drawHead() {
        val ch = Book.chat(ref) ?: return
        title.text = ch.shown.ifBlank { c.getString(R.string.peer) }
        faceSlot.removeAllViews(); faceSlot.addView(c.peerFace(ref, ch.shown, 36), FrameLayout.LayoutParams(c.dp(36), c.dp(36)))
    }
    drawHead()
    val top = FrameLayout(c).apply {
        setPadding(dp(8), 0, dp(8), 0)
        addView(c.icon(R.drawable.ic_arrow_back_ios_new, Color.WHITE).apply { setPadding(dp(10), dp(10), dp(10), dp(10)); pressable { hideKeys(); onClose() } },
            FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER_VERTICAL or Gravity.START))
        addView(c.vstack(Gravity.CENTER_HORIZONTAL) {
            addView(title, lp(WRAP, WRAP))
            addView(presenceLine, lp(WRAP, WRAP))
        }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER).apply { marginStart = dp(56); marginEnd = dp(56) })
        addView(faceSlot.apply {
            // the face opens the correspondent's page (iOS: the chat's header → MontanaPeerInfoScreen)
            setOnClickListener { if (!group) { hideKeys(); act.push { close -> peerInfoPage(act, ref, close) } } }   // a group's face opens no person's page
            setOnLongClickListener {
                AlertDialog.Builder(c).setMessage(R.string.delete_chat_q)
                    .setPositiveButton(R.string.delete) { _, _ -> hideKeys(); onClose(); Book.forget(ref) }
                    .setNegativeButton(R.string.cancel, null).show(); true
            }
        }, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER_VERTICAL or Gravity.END).apply { marginEnd = dp(8) })
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(52))
    }

    val groupFoot = FrameLayout(c).apply { visibility = View.GONE }.also { if (group) fillGroupFoot(c, ref, it) }
    var lane = false   // the live lane stands while the page does
    fun drawDraft() {
        val d = LiveDraft.of(ref)
        val was = draftRow.visibility == View.VISIBLE
        draftRow.removeAllViews()
        if (d == null) { draftRow.visibility = View.GONE; return }
        draftRow.addView(c.liveDraftBubble(d, Book.chat(ref)), FrameLayout.LayoutParams(MATCH, WRAP))
        draftRow.visibility = View.VISIBLE
        if (!was) toBottom()
    }
    val draftListener: () -> Unit = { drawDraft() }
    val presenceListener: () -> Unit = { drawPresence() }
    lateinit var chatBeat: Runnable
    chatBeat = Runnable { if (lane) { Presence.sayChat(ref, true); MainThread.later(Presence.BEAT, chatBeat) } }   // the 20-second beat (iOS P-109)
    var folded = false
    val listener: () -> Unit = listener@{
        // THIS CHAT WAS FOLDED INTO THE OLDER ONE WITH THE SAME PERSON (iOS foldConversation → openChatRequest): the older opens instead.
        val into = SamePair.root(ref)
        if (into != ref && Book.chat(ref) == null) {
            if (!folded) { folded = true; hideKeys(); onClose(); act.push { close -> conversationPage(act, into, close) } }
            return@listener
        }
        val before = column.childCount
        redraw(); drawHead()
        if (group) { drawPresence(); fillGroupFoot(c, ref, groupFoot) }
        if (column.childCount != before) toBottom()
        if ((Book.chat(ref)?.unread ?: 0) > 0) Thread { Post.markRead(ref) }.start()
    }

    // THE EYE CARRIED TO A LETTER (the pinned plate's tap, the search's jump): scrolled a third down and lit for a moment
    fun jumpTo(mid: String) {
        val found = column.findViewWithTag<View>(mid) ?: return
        scroll.post {
            scroll.smoothScrollTo(0, (found.top + column.top - scroll.height / 3).coerceAtLeast(0))
            found.setBackgroundColor(Color.argb(60, 255, 255, 255))
            found.postDelayed({ found.background = null }, 1200)
        }
    }
    // THE FOOT WHILE THE CHAT SELECTS (iOS the selection bar): Forward and Delete for the chosen letters, in place of the field
    val foot = FrameLayout(c).apply { visibility = View.GONE }
    drawFoot = {
        foot.removeAllViews()
        foot.visibility = if (selecting) View.VISIBLE else View.GONE
        bar.visibility = if (selecting || group) View.GONE else View.VISIBLE
        if (selecting) {
            strip.visibility = View.GONE
            fun done() { selecting = false; picked.clear(); redraw(); drawFoot() }
            fun chosen() = Book.chat(ref)?.msgs?.filter { it.mid in picked } ?: emptyList()
            foot.addView(letterSelectionFoot(act, picked.size, onClose = { done() },
                onForward = {
                    val letters = chosen().filter { canForward(it) }
                    done()
                    if (letters.isNotEmpty()) act.push { close -> forwardPage(act, ref, letters, close) { to -> close(); if (to != ref) { onClose(); act.push { c2 -> conversationPage(act, to, c2) } } } }
                },
                onDelete = {
                    val letters = chosen()
                    AlertDialog.Builder(c).setTitle(R.string.delete_message_q)
                        .setItems(arrayOf(c.getString(R.string.delete_for_everyone), c.getString(R.string.delete_for_me))) { _, which ->
                            val mids = letters.map { it.mid }.toSet()
                            Book.edit(ref) { ch -> ch.msgs.removeAll { it.mid in mids } }
                            if (which == 0) mids.forEach { Post.send(ref, Marks.mintMid(), Marks.DELETE + "mid:" + it) }
                            done()
                        }
                        .setNegativeButton(R.string.cancel, null).show()
                }), FrameLayout.LayoutParams(MATCH, WRAP))
        } else showStrip()
    }

    return FrameLayout(c).apply {
        setBackgroundColor(Color.BLACK)
        addView(c.chatGround(ref), FrameLayout.LayoutParams(MATCH, MATCH))   // the chat's own ground, or the pages' crest (iOS MTWallpaper)
        addView(c.vstack(Gravity.NO_GRAVITY) {
            addView(top)
            // THE PINNED PLATE under the header (iOS the pinned bar): shown while a letter of this chat is pinned
            addView(pinnedPlate(act, ref) { mid -> jumpTo(mid) }, lp().apply { marginStart = dp(10); marginEnd = dp(10); bottomMargin = dp(4) })
            addView(scroll, lp(MATCH, 0, 1f))
            addView(strip, lp())
            addView(recStrip, lp())
            addView(bar.apply { if (group) visibility = View.GONE }, lp())
            addView(foot, lp())
            addView(groupFoot, lp())
        }, FrameLayout.LayoutParams(MATCH, MATCH))
        plateHome = this; addView(lockPlate, plateLp())
        setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.requestApplyInsets()
                Book.openChat = ref; Book.listen(listener); Notify.clear(ref)
                Presence.listen(presenceListener); lane = true
                LiveDraft.listen(draftListener); drawDraft()
                Presence.sayChat(ref, true); MainThread.later(Presence.BEAT, chatBeat)
                Signal.listen(ref, alive = { lane }) { w -> Presence.hear(ref, w) }
                if ((Book.chat(ref)?.unread ?: 0) > 0) Thread { Post.markRead(ref) }.start()
            }
            override fun onViewDetachedFromWindow(v: View) {
                if (Book.openChat == ref) Book.openChat = null; Book.unlisten(listener)
                Presence.unlisten(presenceListener); LiveDraft.unlisten(draftListener)
                // words left in the field stand on their screen as a checkpoint, frozen, not typing (iOS «ck»)
                field.text.toString().takeIf { it.isNotBlank() }?.let { LiveDraft.say(ref, it, field.selectionEnd, checkpoint = true) }
                if (lane) { lane = false; Presence.sayChat(ref, false) }   // leaving says so at once (iOS: the departure word)
            }
        })
        scroll.addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob -> if (b - t < ob - ot) toBottom() }
        redraw()
        val found = jump?.let { column.findViewWithTag<View>(it) }
        if (found == null) toBottom() else scroll.post {
            scroll.scrollTo(0, (found.top + column.top - scroll.height / 3).coerceAtLeast(0))
            found.setBackgroundColor(Color.argb(60, 255, 255, 255))
            found.postDelayed({ found.background = null }, 1200)
        }
    }
}

/** One letter: the quote it answers, its words, «edited» and the time; its reactions under it (iOS MessageBubble). */
/**
 * A LETTER PULLED TO THE LEFT IS ANSWERED (iOS MTReplySwipe + SwipeReplyRow): the two first points decide — a stroke to the
 * right or an upright one is the scroll's, a flat one to the left is ours; the letter rides by the finger, the round glass with
 * the reply arrow comes in from beyond the edge, growing and clearing; past the threshold (60 for one's own, 45 for theirs)
 * the pull stiffens and the phone knocks once; let go armed — the reply strip opens. The row slides back either way.
 */
class ReplySwipe(c: Context) : FrameLayout(c) {
    var onReply: (() -> Unit)? = null
    var mine = false
    private var badge: View? = null
    private var x0 = 0f; private var y0 = 0f
    private var validated = false; private var failed = false; private var armed = false
    private val slop = dp(2).toFloat()

    private fun threshold() = dp(if (mine) 60 else 45).toFloat()
    /** The pull past the threshold stiffens (iOS band: range 100, k 0.4). */
    private fun band(off: Float, start: Float): Float {
        if (off < start) return off
        val range = dp(100).toFloat()
        return start + (1 - 1 / ((off - start) * 0.4f / range + 1)) * range
    }
    private fun badge(): View = badge ?: FrameLayout(context).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(90, 120, 120, 128)); setStroke(dp(1), Color.argb(38, 255, 255, 255)) }
        addView(context.icon(R.drawable.ic_reply, Color.WHITE), LayoutParams(dp(17), dp(17), Gravity.CENTER))
        alpha = 0f
        this@ReplySwipe.addView(this, LayoutParams(dp(34), dp(34), Gravity.END or Gravity.CENTER_VERTICAL))
    }.also { badge = it }

    private fun ride(pull: Float) {
        val tx = band(pull, threshold()); val p = (pull / threshold()).coerceAtMost(1f)
        getChildAt(0)?.translationX = -tx
        badge().apply { translationX = dp(43) - tx; alpha = p; scaleX = 0.6f + 0.4f * p; scaleY = scaleX }
    }
    private fun settle() {
        val content = getChildAt(0) ?: return
        val from = -content.translationX
        if (from == 0f) return
        android.animation.ValueAnimator.ofFloat(from, 0f).apply {
            duration = 240; interpolator = android.view.animation.DecelerateInterpolator(1.6f)
            addUpdateListener { v -> val tx = v.animatedValue as Float; content.translationX = -tx
                badge?.let { b -> b.translationX = dp(43) - tx; val p = (tx / threshold()).coerceIn(0f, 1f); b.alpha = p; b.scaleX = 0.6f + 0.4f * p; b.scaleY = b.scaleX } }
            start()
        }
    }

    /** The first points of a touch: whether it is a pull to the left. */
    private fun judge(e: android.view.MotionEvent) {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY; validated = false; failed = false; armed = false }
            android.view.MotionEvent.ACTION_MOVE -> if (!validated && !failed) {
                // two-point validation — quick enough to win the race against the scroll's own pan
                val dx = e.rawX - x0; val dy = e.rawY - y0
                if (dx > slop) failed = true
                else if (kotlin.math.abs(dy) > slop && kotlin.math.abs(dy) > kotlin.math.abs(dx) * 2) failed = true
                else if (kotlin.math.abs(dx) > slop && kotlin.math.abs(dy) * 2 < kotlin.math.abs(dx)) {
                    validated = true; parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
        }
    }

    override fun onInterceptTouchEvent(e: android.view.MotionEvent): Boolean {
        if (onReply == null) return false
        judge(e)
        return validated
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (onReply == null) return false
        // A PULL BESIDE THE BUBBLE ANSWERS TOO (iOS: the letter's line, not the bubble's face): the row keeps the touch
        // that no bubble took, and judges it itself.
        if (!validated) { judge(e); return !failed }
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_MOVE -> {
                val pull = (x0 - e.rawX).coerceAtLeast(0f)
                ride(pull)
                if (pull >= threshold() && !armed) {
                    armed = true
                    performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 34) android.view.HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
                                          else android.view.HapticFeedbackConstants.LONG_PRESS)
                }
            }
            android.view.MotionEvent.ACTION_UP -> { if (armed) onReply?.invoke(); settle(); validated = false }
            android.view.MotionEvent.ACTION_CANCEL -> { settle(); validated = false }
        }
        return true
    }
}

/**
 * THE GROUP'S FOOT IN PLACE OF THE FIELD (iOS composeSlot): out of the group — the note that its letters stay to read
 * (groupOutNote); a channel's subscriber — the platform's bell, the channel's sound off or on, the same switch as the list's
 * swipe (channelMuteBar).
 */
private fun fillGroupFoot(c: Context, ref: String, into: FrameLayout) {
    into.removeAllViews()
    if (Groups.isOut(ref)) into.addView(c.text(c.getString(R.string.gr_out_note), 13f, MT.gray, center = true).apply {
        setPadding(c.dp(20), c.dp(12), c.dp(20), c.dp(12))
    }, FrameLayout.LayoutParams(MATCH, WRAP))
    else if (!Groups.canWrite(ref)) {
        val muted = ChatMarks.isMuted(ref)
        into.addView(c.icon(if (muted) R.drawable.ic_bell_off else R.drawable.ic_set_bell, Color.WHITE).apply {
            setPadding(c.dp(12), c.dp(12), c.dp(12), c.dp(12))
            contentDescription = c.getString(if (muted) R.string.cl_unmute else R.string.cl_mute)
            pressable { ChatMarks.toggleMute(ref); fillGroupFoot(c, ref, into) }
        }, FrameLayout.LayoutParams(c.dp(44), c.dp(44), Gravity.CENTER).apply { topMargin = c.dp(6); bottomMargin = c.dp(6) })
    }
    into.visibility = if (into.childCount > 0) View.VISIBLE else View.GONE
}

private fun letterBubble(c: Context, m: Msg, chat: Chat): ReplySwipe = ReplySwipe(c).apply {
    mine = m.mine
    val mine = m.mine
    addView(c.vstack(if (mine) Gravity.END else Gravity.START) {
        // THE SPEAKER OVER A GROUP'S LETTER (iOS speakerLine): every letter of another says who wrote it; a channel speaks as itself
        Groups.speakerLine(c, m, chat.ref)?.let { who ->
            addView(c.text(who, 12f, MT.gray, bold = true).apply { singleLineEllipsis(); setPadding(c.dp(8), c.dp(6), 0, c.dp(2)) }, lp(WRAP, WRAP))   // USER-DATA: the speaker's name
        }
        addView(c.vstack(Gravity.END) {
            background = if (Stickers.bare(m)) null else BubbleStyle.drawable(context, mine)   // a sticker stands without a bubble
            setPadding(dp(12), dp(7), dp(12), dp(6))
            // A FORWARDED LETTER (iOS isForwarded): one grey line over its words, no name and no bar
            if (m.qt == FORWARDED_QUOTE) addView(c.text(c.getString(R.string.ld_forwarded), 13f, BubbleStyle.time(mine)), lp(WRAP, WRAP).apply { bottomMargin = dp(3) })
            else if (m.qt != null) {
                val whose = m.qm?.let { q -> chat.msgs.find { it.mid == q } }
                addView(c.hstack {
                    addView(View(c).apply { setBackgroundColor(BubbleStyle.text(mine)) }, lp(dp(2), MATCH).apply { marginEnd = dp(6) })
                    addView(c.vstack(Gravity.NO_GRAVITY) {
                        addView(c.text(if (whose?.mine == true) Prefs.userName.ifBlank { "·" } else if (Groups.isKey(chat.ref)) Groups.speakerName(c, whose?.from) else chat.shown.ifBlank { c.getString(R.string.peer) }, 13f, BubbleStyle.text(mine), bold = true))
                        addView(c.text(m.qt, 13f, BubbleStyle.time(mine)).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; maxWidth = dp(240) })   // USER-DATA
                    })
                }, lp(WRAP, WRAP).apply { bottomMargin = dp(4) })
            }
            if (Stickers.body(c, m, this)) Unit else if (m.text.startsWith(Marks.MEDIA)) mediaBody(c, m, this)
            else addView(c.text(letterWords(c, m.text), 16f, BubbleStyle.text(mine)).apply { maxWidth = dp(260); setTextIsSelectable(false) }, lp(WRAP, WRAP))   // USER-DATA: the letter
            // THE LINK'S CARD UNDER THE WORDS (iOS the link card in the bubble), drawn from the letter's own bytes
            LinkCard.parse(m.lp)?.let { addView(linkCardView(c, it, mine), lp(WRAP, WRAP)) }
            addView(c.hstack {
                gravity = Gravity.CENTER_VERTICAL
                if (m.edited) addView(c.text(c.getString(R.string.edited) + " ", 11f, BubbleStyle.time(mine)))
                addView(c.text(android.text.format.DateFormat.getTimeFormat(c).format(Date(m.at)), 11f, BubbleStyle.time(mine)))
                // no ticks in the bubble (iOS 15.47): the words stand under the last own letter; only «not sent» marks the corner
                if (mine && m.state == -1) addView(c.failedMark(), lp(dp(12), dp(12)).apply { marginStart = dp(4) })
            }, lp(WRAP, WRAP))
        }, lp(WRAP, WRAP))
        // THE COMMENTS UNDER A POST (Sh.4, iOS commentsRow): under every letter of an organization's channel, how many, and the
        // tap opens the post's own feed -- every listener of the channel writes there; the whole row is the target
        if (Groups.thread(chat.ref) == null && Groups.comments(chat.ref)) {
            val thread = Groups.threadKey(chat.ref, m.mid)
            val count = Book.chat(thread)?.msgs?.size ?: 0
            addView(c.hstack {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = c.dp(44)
                setPadding(c.dp(8), 0, c.dp(8), 0)
                addView(c.icon(R.drawable.ic_comments, MT.gray, 16), lp(c.dp(16), c.dp(16)).apply { marginEnd = c.dp(8) })
                addView(c.text(c.getString(R.string.gr_comments), 14f, MT.gray))
                // USER-DATA: how many comments stand under the post
                if (count > 0) addView(c.text(" " + count, 14f, MT.gray))
                pressable {
                    (c as? MainActivity)?.let { act ->
                        Book.open(thread, c.getString(R.string.gr_comments), false)
                        act.push { close -> conversationPage(act, thread, close) }
                    }
                }
            }, lp(WRAP, WRAP))
        }
        if (m.reactions.isNotEmpty()) addView(c.hstack {
            m.reactions.groupingBy { it }.eachCount().forEach { (e, n) ->
                addView(c.text(if (n > 1) "$e $n" else e, 14f, Color.WHITE).apply {
                    background = c.rounded(if (e == m.myReact) Color.argb(120, 10, 133, 255) else Color.argb(70, 255, 255, 255), 12)
                    setPadding(dp(7), dp(2), dp(7), dp(2))
                }, lp(WRAP, WRAP).apply { marginEnd = dp(4) })
            }
        }, lp(WRAP, WRAP).apply { topMargin = dp(2) })
    }, FrameLayout.LayoutParams(WRAP, WRAP, if (mine) Gravity.END else Gravity.START))
}

/**
 * THE LETTER'S MENU (iOS MontanaMessageMenu): the room dims, the quick reactions stand over the letter, the actions under it —
 * Reply · Copy · Edit (one's own words) · Delete, which asks «for me» or «for everyone».
 */
private fun letterMenu(act: MainActivity, ref: String, m: Msg, onReply: () -> Unit, onEdit: () -> Unit, onForward: () -> Unit, onSelect: () -> Unit, group: Boolean = false) {
    val c: Context = act
    lateinit var close: () -> Unit
    // A GROUP'S LETTER (Groups): what this phone keeps of it — copy, forward, delete here; what would tell the group waits for its own word
    val canEdit = m.mine && !group && !Marks.isService(m.text)
    val actions = c.vstack(Gravity.NO_GRAVITY) { background = c.rounded(Color.argb(235, 44, 44, 46), 14) }
    fun row(words: String, red: Boolean = false, work: () -> Unit) {
        if (actions.childCount > 0) actions.addView(View(c).apply { setBackgroundColor(Color.argb(40, 255, 255, 255)) }, lp(MATCH, 1))
        actions.addView(c.text(words, 17f, if (red) SysColor.red else Color.WHITE).apply { setPadding(dp(16), dp(12), dp(16), dp(12)); pressable { close(); work() } }, lp())
    }
    fun fillActions() {
        actions.removeAllViews()
        // THE RUNG AND ITS MOMENT over the actions (iOS MontanaMessageMenu ladder): the same line as under the bubble, with the time
        if (m.mine) actions.addView(c.deliveryLine(ref, m, withMoment = true).apply { setPadding(dp(16), dp(11), dp(16), dp(11)) }, lp())
        if (!group) row(c.getString(R.string.reply)) { onReply() }
        if (!Marks.isService(m.text)) row(c.getString(R.string.copy)) {
            c.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("", m.text))
        }
        if (canEdit) row(c.getString(R.string.edit)) { onEdit() }
        // PIN · FORWARD (iOS MontanaMessageMenu): the pin asks «for both / for me», the unpin is told at once
        if (!group && (!Marks.isService(m.text) || m.text.startsWith(Marks.MEDIA)))
            row(c.getString(if (MsgPins.isPinned(ref, m.mid)) R.string.ld_unpin else R.string.ld_pin)) { pinOrUnpin(act, ref, m) }
        if (canForward(m)) row(c.getString(R.string.ld_forward)) { onForward() }
        actions.addView(View(c).apply { setBackgroundColor(Color.argb(40, 255, 255, 255)) }, lp(MATCH, 1))
        actions.addView(c.text(c.getString(R.string.delete), 17f, SysColor.red).apply {
            setPadding(dp(16), dp(12), dp(16), dp(12))
            pressable {
                // «Delete message?» — for me, for everyone (any bubble, mine or theirs: the mid is the same on both sides).
                actions.removeAllViews()
                actions.addView(c.text(c.getString(R.string.delete_message_q), 13f, MT.gray, center = true).apply { setPadding(dp(16), dp(10), dp(16), dp(6)) }, lp())
                if (!group) row(c.getString(R.string.delete_for_everyone), red = true) {
                    Book.edit(ref) { ch -> ch.msgs.removeAll { it.mid == m.mid } }
                    Post.send(ref, Marks.mintMid(), Marks.DELETE + "mid:" + m.mid)
                }
                row(c.getString(R.string.delete_for_me), red = true) { Book.edit(ref) { ch -> ch.msgs.removeAll { it.mid == m.mid } } }
                row(c.getString(R.string.cancel)) {}
            }
        }, lp())
        // «SELECT» stands apart at the foot (iOS: after a wider gap)
        if (!group) actions.addView(View(c).apply { setBackgroundColor(Color.argb(40, 255, 255, 255)) }, lp(MATCH, c.dp(6)))
        if (!group) actions.addView(c.text(c.getString(R.string.ld_select), 17f, Color.WHITE).apply { setPadding(dp(16), dp(12), dp(16), dp(12)); pressable { close(); onSelect() } }, lp())
    }
    fillActions()
    // THE QUICK REACTIONS (iOS MTReactions.common): a press puts it on or takes it off; the peer hears RC:{sid,txt,e,op}.
    val reactions = HorizontalScrollView(c).apply {
        isHorizontalScrollBarEnabled = false
        background = c.rounded(Color.argb(235, 44, 44, 46), 24)
        addView(c.hstack {
            setPadding(dp(8), dp(4), dp(8), dp(4))
            for (e in listOf("👍", "❤️", "🔥", "😂", "😮", "😢", "🎉", "🙏", "👏")) addView(c.text(e, 28f).apply {
                setPadding(dp(6), dp(4), dp(6), dp(4))
                pressable {
                    close()
                    var added = false
                    val prev = m.myReact
                    Book.edit(ref) { ch ->
                        ch.msgs.find { it.mid == m.mid }?.let { t ->
                            t.myReact?.let { old -> t.reactions.remove(old) }
                            if (prev == e) t.myReact = null else { t.reactions.add(e); t.myReact = e; added = true }
                        }
                    }
                    fun tell(emoji: String, add: Boolean) = Post.send(ref, Marks.mintMid(), Marks.REACTION +
                        JSONObject().put("sid", "mid:" + m.mid).put("txt", m.text.take(200)).put("e", emoji).put("op", if (add) "add" else "del"))
                    if (prev != null) tell(prev, false)
                    if (added) tell(e, true)
                }
            })
        })
    }
    val chat = Book.chat(ref)
    val copy = if (chat != null) letterBubble(c, m, chat) else View(c)
    val page = FrameLayout(c).apply {
        setBackgroundColor(Color.argb(150, 0, 0, 0))
        isClickable = true
        pressable { close() }
        addView(c.vstack(if (m.mine) Gravity.END else Gravity.START) {
            setPadding(dp(14), 0, dp(14), 0)
            if (!group) { addView(reactions, lp(WRAP, WRAP)); gap(8) }
            addView(copy, lp())
            gap(8)
            addView(actions, lp(dp(240), WRAP))
        }, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
    }
    close = act.overlay(page)
}


/**
 * A MEDIA LETTER IN ITS BUBBLE (iOS MessageBubble media): the picture itself, a film's first frame under the play mark, or
 * a file's name and size; the small face that rode the manifest stands while the file is on its way; the caption under it.
 * A press opens it whole: a picture or a film in the app, a file in the system's viewer.
 */
private fun mediaBody(c: Context, m: Msg, into: LinearLayout) {
    val man = m.meta?.let { runCatching { JSONObject(it) }.getOrNull() } ?: Media.inline(m.text)
    val f = m.file?.let { java.io.File(it) }?.takeIf { it.exists() }
    val byExt = when (f?.extension?.lowercase()) { "jpg", "jpeg", "png", "gif", "webp", "heic", "heif" -> "img"; "mp4", "mov", "m4v", "webm", "3gp" -> "vid"; else -> "doc" }
    val kind = man?.optString("k")?.ifEmpty { null } ?: byExt
    val tint = BubbleStyle.text(m.mine)
    if (kind == "aud") {
        val wv = man?.optString("wv")?.takeIf { it.isNotEmpty() }?.let { runCatching { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }.getOrNull() }
        voiceBody(c, m, man?.optDouble("du", 0.0) ?: 0.0, into, wv?.let { b -> FloatArray(b.size) { (b[it].toInt() and 0xFF) / 255f } })
        return
    }
    if (kind == "vid" && man?.optBoolean("r") == true) { noteBody(c, m, f, man, into); return }
    if (kind == "img" || kind == "vid") {
        val pic = (f?.let { Media.preview(c, it, kind, 800) } ?: Media.thumbOf(man))
        into.addView(FrameLayout(c).apply {
            val w = c.dp(240)
            val h = if (pic != null) (w * pic.height / maxOf(1, pic.width)).coerceIn(c.dp(120), c.dp(320)) else c.dp(180)
            addView(ImageView(c).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                if (pic != null) setImageBitmap(pic) else setBackgroundColor(Color.argb(60, 255, 255, 255))
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, v.dp(12).toFloat())
                }
                clipToOutline = true
            }, FrameLayout.LayoutParams(w, h))
            if (f == null) addView(android.widget.ProgressBar(c), FrameLayout.LayoutParams(c.dp(36), c.dp(36), Gravity.CENTER))
            else if (kind == "vid") addView(c.icon(R.drawable.ic_play_fill, Color.WHITE).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(140, 0, 0, 0)) }
                setPadding(c.dp(12), c.dp(12), c.dp(12), c.dp(12))
            }, FrameLayout.LayoutParams(c.dp(52), c.dp(52), Gravity.CENTER))
            if (f != null) setOnClickListener { (c as? MainActivity)?.let { a -> openMedia(a, f, kind) } }
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = c.dp(4) })
    } else {
        val name = man?.optString("n")?.ifEmpty { null } ?: ("file." + (man?.optString("e") ?: ""))
        val size = man?.optLong("sz") ?: 0L
        into.addView(c.hstack {
            gravity = Gravity.CENTER_VERTICAL
            addView(FrameLayout(c).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(70, 255, 255, 255)) }
                if (f == null) addView(android.widget.ProgressBar(c), FrameLayout.LayoutParams(c.dp(26), c.dp(26), Gravity.CENTER))
                else addView(c.icon(R.drawable.ic_set_doc, tint), FrameLayout.LayoutParams(c.dp(22), c.dp(22), Gravity.CENTER))
            }, lp(c.dp(44), c.dp(44)).apply { marginEnd = c.dp(10) })
            addView(c.vstack(Gravity.NO_GRAVITY) {
                addView(c.text(name, 15f, tint, bold = true).apply { maxWidth = c.dp(190); singleLineEllipsis() })   // USER-DATA: the file's name
                addView(c.text(android.text.format.Formatter.formatShortFileSize(c, size), 13f, BubbleStyle.time(m.mine)))
            })
            if (f != null) setOnClickListener { (c as? MainActivity)?.let { a -> openMedia(a, f, kind) } }
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = c.dp(4) })
    }
    if (m.state == -1) into.addView(c.text(c.getString(R.string.media_failed), 12f, SysColor.red))
    man?.optString("cap")?.takeIf { it.isNotEmpty() }?.let { into.addView(c.text(it, 16f, tint).apply { maxWidth = c.dp(240) }) }   // USER-DATA: the caption
}

/** THE FILE WHOLE: a picture on black, a film in the platform's own player; any other file to the system's viewer. */
internal fun openMedia(act: MainActivity, f: java.io.File, kind: String) {
    val c: Context = act
    if (kind == "doc") { openDocument(act, f); return }   // a document opens in its own page (iOS DocPreview, DocView.kt)
    if (kind != "img" && kind != "vid") {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(Media.uri(c, f), c.contentResolver.getType(Media.uri(c, f)) ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { act.startActivity(Intent.createChooser(view, null)) }
        return
    }
    // A PICTURE OPENS AMONG THE CONVERSATION'S PICTURES (iOS PhotoPresenter): pages, pinch, a pull down to close (Viewer.kt)
    if (kind == "img") { openPictures(act, f); return }
    lateinit var close: () -> Unit
    val page = FrameLayout(c).apply {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        if (kind == "img") addView(ImageView(c).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(Media.preview(c, f, "img", 2400))
        }, FrameLayout.LayoutParams(MATCH, MATCH))
        else addView(android.widget.VideoView(c).apply {
            setVideoURI(android.net.Uri.fromFile(f))
            setMediaController(android.widget.MediaController(c).also { it.setAnchorView(this) })
            setOnPreparedListener { start() }
        }, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))
        addView(c.icon(R.drawable.ic_close, Color.WHITE).apply { setPadding(c.dp(12), c.dp(12), c.dp(12), c.dp(12)); pressable { close() } },
            FrameLayout.LayoutParams(c.dp(48), c.dp(48), Gravity.TOP or Gravity.START).apply { setMargins(c.dp(8), c.dp(8), 0, 0) })
        addView(c.icon(R.drawable.ic_share, Color.WHITE).apply {
            setPadding(c.dp(12), c.dp(12), c.dp(12), c.dp(12))
            pressable {
                val send = Intent(Intent.ACTION_SEND).setType(c.contentResolver.getType(Media.uri(c, f)) ?: "*/*")
                    .putExtra(Intent.EXTRA_STREAM, Media.uri(c, f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                act.startActivity(Intent.createChooser(send, null))
            }
        }, FrameLayout.LayoutParams(c.dp(48), c.dp(48), Gravity.TOP or Gravity.END).apply { setMargins(0, c.dp(8), c.dp(8), 0) })
    }
    close = act.overlay(page)
}


/** A ROUND NOTE IN ITS BUBBLE (iOS videoNoteBubble): the circle with the note's own first frame; a press plays it large, with sound. */
private fun noteBody(c: Context, m: Msg, f: java.io.File?, man: JSONObject?, into: LinearLayout) {
    val side = c.dp(220)
    val pic = f?.let { Media.preview(c, it, "vid", 600) } ?: Media.thumbOf(man)
    into.addView(FrameLayout(c).apply {
        addView(ImageView(c).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (pic != null) setImageBitmap(pic) else setBackgroundColor(Color.argb(60, 255, 255, 255))
        }.round(), FrameLayout.LayoutParams(side, side))
        if (f == null) addView(android.widget.ProgressBar(c), FrameLayout.LayoutParams(c.dp(36), c.dp(36), Gravity.CENTER))
        else setOnClickListener { (c as? MainActivity)?.let { a -> playNote(a, f) } }
    }, LinearLayout.LayoutParams(side, side).apply { bottomMargin = c.dp(4) })
    // A note stands without the bubble's plate: the circle is its own shape.
    (into.background as? android.graphics.drawable.Drawable)?.let { into.background = null; into.setPadding(0, 0, 0, 0) }
}

/** The note large, with sound, the ring for its time; a tap or its end closes it (iOS MontanaNotePlayback's own window). */
private fun playNote(act: MainActivity, f: java.io.File) {
    val c: Context = act
    lateinit var close: () -> Unit
    val side = c.dp(300)
    val ring = NoteRing(c)
    val tv = TextureView(c)
    var player: android.media.MediaPlayer? = null
    val page = FrameLayout(c).apply {
        setBackgroundColor(Color.argb(200, 0, 0, 0))
        isClickable = true
        pressable { close() }
        addView(ring, FrameLayout.LayoutParams(side + c.dp(16), side + c.dp(16), Gravity.CENTER))
        addView(tv.round(), FrameLayout.LayoutParams(side, side, Gravity.CENTER))
    }
    tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
            player = runCatching { android.media.MediaPlayer().apply {
                setDataSource(f.path); setSurface(android.view.Surface(st)); prepare(); start()
                setOnCompletionListener { close() }
            } }.getOrNull()
            val tick = object : Runnable { override fun run() { val p = player ?: return; runCatching { ring.progress = p.currentPosition.toFloat() / maxOf(1, p.duration) }; MainThread.later(50, this) } }
            MainThread.later(50, tick)
        }
        override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}
        override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean { player?.runCatching { stop(); release() }; player = null; return true }
        override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
    }
    val done = act.overlay(page)
    close = { player?.runCatching { stop(); release() }; player = null; done() }
}


/**
 * THE PICTURE WITH ITS CAPTION (iOS AttachSheet's album page: «under the album stand the caption field and the send button»):
 * the picked picture or the video's first frame across the page, the field «Caption…» under it, prefilled with what was
 * typed, and the chat's own send key. The cross leaves without sending. iOS picks in its own grid; Android's picker is the
 * system's, so the caption comes on this page right after it.
 */
fun captionPage(act: MainActivity, p: Media.Picked, draft: String, onCancel: () -> Unit, onSend: (String) -> Unit): View {
    val c: Context = act
    val preview: android.graphics.Bitmap? = when (p.kind) {
        "img" -> runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(p.bytes, 0, p.bytes.size, o)
            val side = maxOf(o.outWidth, o.outHeight); var s = 1
            while (side / (s * 2) >= 2048) s *= 2
            BitmapFactory.decodeByteArray(p.bytes, 0, p.bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
        }.getOrNull()
        "vid" -> runCatching {
            val f = java.io.File(c.cacheDir, "cap-preview.${p.ext}").apply { writeBytes(p.bytes) }
            android.media.MediaMetadataRetriever().run { setDataSource(f.path); val b = frameAtTime; release(); f.delete(); b }
        }.getOrNull()
        else -> null
    }
    val field = EditText(c).apply {
        hint = c.getString(R.string.caption_hint); setText(draft); setSelection(text.length)
        setHintTextColor(MT.gray); setTextColor(Color.WHITE); textSize = 17f
        background = c.glassPlate().apply { cornerRadius = c.dp(20).toFloat() }
        setPadding(c.dp(16), c.dp(9), c.dp(16), c.dp(9))
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        maxLines = 5
    }
    return FrameLayout(c).apply {
        setBackgroundColor(Color.BLACK)
        addView(ImageView(c).apply { setImageBitmap(preview); scaleType = ImageView.ScaleType.FIT_CENTER }, FrameLayout.LayoutParams(MATCH, MATCH))
        if (p.kind == "vid") addView(c.icon(R.drawable.ic_play_fill, Color.WHITE), FrameLayout.LayoutParams(c.dp(56), c.dp(56), Gravity.CENTER))
        addView(FrameLayout(c).apply {
            background = c.glassPlate(oval = true); contentDescription = c.getString(R.string.cancel)
            addView(c.icon(R.drawable.ic_close, Color.WHITE), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            pressable { onCancel() }
        }, FrameLayout.LayoutParams(c.dp(44), c.dp(44), Gravity.TOP or Gravity.START).apply { setMargins(c.dp(14), c.dp(6), 0, 0) })
        addView(c.hstack {
            gravity = Gravity.BOTTOM
            setPadding(dp(10), dp(8), dp(10), dp(10))
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            addView(field, lp(0, WRAP, 1f))
            addView(ImageView(c).apply {
                setImageResource(R.drawable.send_button); scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = c.getString(R.string.send)
                pressable { onSend(field.text.toString().trim()) }
            }, lp(dp(40), dp(40)).apply { marginStart = dp(8) })
        }, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom); insets
        }
    }
}
