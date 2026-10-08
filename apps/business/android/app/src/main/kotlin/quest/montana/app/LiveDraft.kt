package quest.montana.app

import android.content.Context
import android.graphics.Color
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import org.json.JSONObject

/**
 * THE LIVE DRAFT (iOS E2E.sendDraftWord / handleMeshDraft, LiveDraftBubble): the words in my field appear on their screen as
 * I type them — a grey bubble at the feed's foot with the caret where I hold it — and theirs on mine. The word is
 * «⁣mtdraft:» + base64 {t, c, n[, rm][, ck]}: the text, the caret, the moment it was said (strictly growing), the letter it
 * answers, «ck» for a checkpoint of a closing chat. It rides the signal lane (at most four a second, the ending always);
 * an empty text is the ending. «Live chat» in Privacy (liveTypingEnabled) silences my words and hides theirs.
 */
object LiveDraft {
    const val MARK = "⁣mtdraft:"
    class Draft(val text: String, val caret: Int, val replyMid: String)

    private val said = HashMap<String, String>()        // the last text of mine standing on their screen
    private val sentAt = HashMap<String, Long>()
    private val pending = HashMap<String, Runnable>()
    private val heardAt = HashMap<String, Long>()       // the moment of the newest word of theirs shown
    private val shown = HashMap<String, Draft>()
    private val listeners = mutableListOf<() -> Unit>()
    fun listen(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun unlisten(l: () -> Unit) { synchronized(listeners) { listeners.remove(l) } }
    private fun changed() { val ls = synchronized(listeners) { listeners.toList() }; MainThread.post { ls.forEach { it() } } }

    private val on get() = Prefs.bool("liveTypingEnabled", true)
    private val h = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastStamp = 0L
    /** The moment a word was said, strictly growing (iOS draftStamp): two words in one millisecond keep their order. */
    @Synchronized private fun stamp(): Long { lastStamp = maxOf(System.currentTimeMillis(), lastStamp + 1); return lastStamp }

    // ── what I say ──
    /** The field as it stands; an empty text ends the draft (and is always said, whatever the switch). */
    fun say(ref: String, text: String, caret: Int = -1, replyMid: String = "", checkpoint: Boolean = false) {
        val ending = text.isEmpty()
        if (!ending && !on) return
        if (PeerSafety.isBlocked(ref)) return
        if (!checkpoint && said[ref] == text) return        // the wire already says exactly this
        if (ending && said[ref] == null) return              // nothing of mine stands there
        said[ref] = text
        pending.remove(ref)?.let { h.removeCallbacks(it) }
        val now = System.currentTimeMillis()
        val due = 250 - (now - (sentAt[ref] ?: 0))
        val speak = Runnable {
            sentAt[ref] = System.currentTimeMillis(); pending.remove(ref)
            val body = JSONObject().put("t", text).put("c", caret).put("n", stamp())
            if (replyMid.isNotEmpty()) body.put("rm", replyMid)
            if (checkpoint) body.put("ck", 1)
            Signal.post(ref, MARK + Base64.encodeToString(body.toString().toByteArray(), Base64.NO_WRAP))
            if (ending) said.remove(ref)
        }
        // four a second fit the node's window; the ending is never held back
        if (ending || due <= 0) speak.run() else { pending[ref] = speak; h.postDelayed(speak, due) }
    }

    // ── what I hear ──
    /** A draft word of theirs off the lane. true — a keystroke said now (it lights «typing…»). */
    fun hear(ref: String, word: String): Boolean {
        val j = runCatching { JSONObject(String(Base64.decode(word.removePrefix(MARK), Base64.DEFAULT), Charsets.UTF_8)) }.getOrNull() ?: return false
        val n = j.optLong("n", 0)
        if (n > 0) { if (n <= (heardAt[ref] ?: 0)) return false; heardAt[ref] = n }   // an older word is an echo of the road
        if (!on) return false
        val text = j.optString("t")
        // WORDS THAT ALREADY BECAME A LETTER ARE NOT A DRAFT (iOS draft_ghost): a word equal to a letter of theirs just landed
        val landed = text.isNotEmpty() && Book.chat(SamePair.root(ref))?.msgs?.takeLast(5)?.any { !it.mine && it.text == text } == true
        if (text.isEmpty() || landed) shown.remove(ref) else shown[ref] = Draft(text, j.optInt("c", -1), j.optString("rm"))
        changed()
        val fresh = n == 0L || System.currentTimeMillis() - n <= 7_000
        return text.isNotEmpty() && !landed && !j.has("ck") && fresh
    }
    fun of(ref: String): Draft? = shown[ref]
}

/**
 * THEIR WORDS AS THEY TYPE THEM (iOS LiveDraftBubble): the correspondent's side, a grey bubble without a tail, the caret
 * «│» exactly where they hold it; the quote of the letter they answer above the words.
 */
fun Context.liveDraftBubble(d: LiveDraft.Draft, chat: Chat?): View = FrameLayout(this).apply {
    val caret = if (d.caret < 0 || d.caret > d.text.length) d.text.length else d.caret
    addView(vstack(Gravity.NO_GRAVITY) {
        background = rounded(Color.rgb(33, 33, 33), 17, Color.argb(90, 255, 255, 255))   // iOS: grey 0.13 with a light rim, no tail
        setPadding(dp(13), dp(8), dp(13), dp(8))
        if (d.replyMid.isNotEmpty()) chat?.msgs?.find { it.mid == d.replyMid }?.let { q ->
            addView(hstack {
                addView(View(context).apply { setBackgroundColor(MT.accent) }, lp(dp(3), dp(16)).apply { marginEnd = dp(6) })
                addView(text(letterWords(context, q), 12f, Color.argb(180, 255, 255, 255)).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })   // USER-DATA
            }, lp().apply { bottomMargin = dp(2) })
        }
        addView(text(d.text.substring(0, caret) + "│" + d.text.substring(caret), 17f, Color.WHITE).apply { maxWidth = dp(260) })   // USER-DATA: their draft
    }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.START))
}
