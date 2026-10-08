package quest.montana.app

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

// ─────────────────────────── the live lane and the presence ladder (iOS MontanaWakePush signal lane, E2E beacons, ChatStore ladder) ───────────────────────────

/**
 * THE SIGNAL LANE (iOS MontanaWakePush.postSignal / fetchSignals, epoch «chat»): a word that lives an instant — typing, «in
 * my chat», «in the app» — never takes a place in the letters' queue. It is sealed under the pipe's key of the minute as a
 * letter is, as peer‖0‖epoch‖0‖deflate(text), and posted to the doors' /signal under the conversation's daily label; the
 * other side holds a long question on /signal-fetch and hears it the millisecond it lands. A bare «box» on the lane says the
 * box holds a letter of ours — it is fetched at once.
 */
object Signal {
    private fun deflate(b: ByteArray): ByteArray {
        val d = Deflater(Deflater.DEFAULT_COMPRESSION, true)   // raw DEFLATE, as Apple's .zlib writes it (no header)
        d.setInput(b); d.finish()
        val out = ByteArrayOutputStream(); val buf = ByteArray(512)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end(); return out.toByteArray()
    }
    private fun inflate(b: ByteArray): ByteArray? = runCatching {
        val i = Inflater(true); i.setInput(b)
        val out = ByteArrayOutputStream(); val buf = ByteArray(512)
        var guard = 0
        while (!i.finished() && guard++ < 1000) { val n = i.inflate(buf); if (n == 0 && (i.needsInput() || i.needsDictionary())) break; out.write(buf, 0, n) }
        i.end(); out.toByteArray()
    }.getOrNull()

    /** One word on the lane to every door: per-node stores do not talk, and the other side asks whichever it asks. */
    fun post(ref: String, text: String) {
        val twin = MontanaSeed.twin ?: return
        val secret = Book.secret(ref) ?: return
        Thread {
            val body = twin.toByteArray() + byteArrayOf(0) + "chat".toByteArray() + byteArrayOf(0) + deflate(text.toByteArray())
            val sealed = Wire.seal(Wire.bodyKey(secret, Wire.minute()), body) ?: return@Thread
            val cw = Wire.convW(secret, Wire.day())
            val req = JSONObject().put("conv", cw).put("from_id", Wire.subId(cw, twin)).put("env", Base64.encodeToString(sealed, Base64.NO_WRAP))
            for (door in MontanaCard.doors) Wire.post(door, "/signal", req, 8000)
        }.start()
    }

    /** The words that landed for `ref`: opened by the minute (this one and its neighbours), unpacked; «box» hints fetch the box. */
    private fun open(ref: String, envs: org.json.JSONArray, onWord: (String) -> Unit) {
        val secret = Book.secret(ref) ?: return
        for (k in 0 until envs.length()) {
            val e = envs.optString(k)
            if (e == "box") { Thread { Post.fetch() }.start(); continue }
            val sealed = runCatching { Base64.decode(e, Base64.DEFAULT) }.getOrNull() ?: continue
            val w0 = Wire.minute()
            val plain = listOf(w0, w0 - 1, w0 + 1).firstNotNullOfOrNull { w -> Wire.open(Wire.bodyKey(secret, w), sealed) } ?: continue
            val a = plain.indexOf(0); if (a < 0) continue
            val b = (a + 1 until plain.size).firstOrNull { plain[it] == 0.toByte() } ?: continue
            if (String(plain, a + 1, b - a - 1, Charsets.UTF_8) != "chat") continue   // a call's lane: not ours yet
            val text = inflate(plain.copyOfRange(b + 1, plain.size))?.toString(Charsets.UTF_8)?.takeIf { it.isNotEmpty() } ?: continue
            onWord(text)
        }
    }

    /**
     * THE LONG QUESTION while a chat stands open (iOS fetchSignals): each door asked with a wait of twelve seconds — the doors'
     * gate cuts at fifteen — and asked again the moment it answers. The lane lives while `alive()` says so.
     */
    fun listen(ref: String, alive: () -> Boolean, onWord: (String) -> Unit) {
        val twin = MontanaSeed.twin ?: return
        for (door in MontanaCard.doors) Thread {
            var fails = 0
            while (alive()) {
                val secret = Book.secret(ref) ?: return@Thread
                val cw = Wire.convW(secret, Wire.day())
                val (code, text) = Wire.post(door, "/signal-fetch", JSONObject().put("conv", cw).put("from_id", Wire.subId(cw, twin)).put("wait", 12), 15_000)
                if (code != 200 || text == null) { fails++; Thread.sleep(minOf(10_000L, 1000L * fails)); continue }
                fails = 0
                runCatching { JSONObject(text).optJSONArray("envs") }.getOrNull()?.let { envs -> if (envs.length() > 0) open(ref, envs, onWord) }
            }
        }.start()
    }
}

/**
 * THE PRESENCE LADDER (iOS ChatStore presenceWord): «typing…» lives five seconds, «in chat» and «online» one life of 45 —
 * two beats of the 20-second heartbeat and a margin; a departure word ends it at once; a word older than its life is history
 * and lights nothing (the lane hands over a minute of words when a chat opens). When no live word stands, the stamp: «last
 * seen…» — exact when both share it, the coarse class when either hides.
 */
object Presence {
    const val TYPING = "​​TY:"
    const val WATCH = "​​WA:"   // «1» my chat with you is on my screen, «0» left it
    const val APP = "​​AP:"     // «1» the app is on my screen, «0» left it; «0B» — gone for good
    private const val TYPING_LIFE = 5_000L
    private const val WORD_LIFE = 45_000L
    const val BEAT = 20_000L

    private val typingUntil = HashMap<String, Long>()
    private val inChatUntil = HashMap<String, Long>()
    private val onlineUntil = HashMap<String, Long>()
    private val listeners = mutableListOf<() -> Unit>()
    fun listen(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun unlisten(l: () -> Unit) { synchronized(listeners) { listeners.remove(l) } }
    private fun changed() {
        val ls = synchronized(listeners) { listeners.toList() }
        MainThread.post { ls.forEach { it() } }
    }

    val sharing get() = Prefs.bool("presenceSharing", true)
    /** The moment a word was said, as iOS says it (E2E.saidTail): «T» + seconds + «.» + three digits of milliseconds. */
    fun saidTail(now: Long = System.currentTimeMillis()) = "T" + (now / 1000) + "." + (1000 + now % 1000).toString().drop(1)
    private val saidRe = Regex("T(\\d{9,11})\\.(\\d{3})")
    private fun said(payload: String): Long? = saidRe.find(payload)?.let { m -> m.groupValues[1].toLong() * 1000 + m.groupValues[2].toLong() }

    // ── what I say ──
    fun sayTyping(ref: String) { if (sharing) Signal.post(ref, TYPING + saidTail()) }
    fun sayChat(ref: String, open: Boolean) { if (sharing) Signal.post(ref, WATCH + (if (open) "1" else "0") + saidTail()) }
    fun sayApp(open: Boolean) {
        if (!sharing) return
        for (ref in Book.refs()) if (SamePair.merged(ref) == null && !PeerSafety.isBlocked(ref)) Signal.post(ref, APP + (if (open) "1" else "0") + saidTail())
    }

    // ── what I hear ──
    /** A word of the lane from `ref` (iOS append's presence branches + liveWordMoves). */
    fun hear(ref: String, text: String) {
        if (PeerSafety.isBlocked(ref)) return   // a blocked person reaches nothing
        // THEIR LIVE DRAFT (iOS handleMeshDraft): its bubble, and «typing…» when it is a keystroke said now
        if (text.startsWith(LiveDraft.MARK)) { if (LiveDraft.hear(ref, text)) hear(ref, TYPING + saidTail()); return }
        val now = System.currentTimeMillis()
        val at = said(text)
        val age = at?.let { (now - it).coerceAtLeast(0) } ?: 0L
        when {
            text.startsWith(TYPING) -> {
                if (age > TYPING_LIFE + 2000) { noteSeen(ref, at); return }   // a keystroke a minute old is not typing now
                typingUntil[ref] = now + TYPING_LIFE - age
                inChatUntil[ref] = now + WORD_LIFE - age; onlineUntil[ref] = now + WORD_LIFE - age   // typing proves the chat is on their screen
                MainThread.later(TYPING_LIFE - age + 50) { changed() }
            }
            text.startsWith(WATCH) || text.startsWith(APP) -> {
                val chat = text.startsWith(WATCH)
                val payload = text.removePrefix(if (chat) WATCH else APP)
                Prefs.setBool("phide_$ref", payload.drop(1).contains('h'))
                val open = payload.startsWith("1")
                if (age <= WORD_LIFE) {
                    if (open) {
                        if (chat) inChatUntil[ref] = now + WORD_LIFE - age
                        onlineUntil[ref] = now + WORD_LIFE - age
                        MainThread.later(WORD_LIFE - age + 50) { changed() }
                    } else {
                        inChatUntil.remove(ref); typingUntil.remove(ref)
                        if (!chat) onlineUntil.remove(ref)
                    }
                }
                if (!chat && payload.drop(1).startsWith("B")) Prefs.setBool("peerGone.$ref", true)
                else if (open) Prefs.remove("peerGone.$ref")
            }
            else -> return
        }
        noteSeen(ref, at ?: now)
        changed()
    }

    /** THE ONE WRITER OF THE STAMP (iOS noteSeen): a moment never later than now, never earlier than the one held. */
    fun noteSeen(ref: String, atMs: Long?) {
        val ts = minOf(atMs ?: return, System.currentTimeMillis())
        if (ts <= seenAt(ref)) return
        Prefs.setStr("seenAt.$ref", ts.toString())
        changed()
    }

    private fun seenAt(ref: String) = Prefs.str("seenAt.$ref", "0").toLongOrNull() ?: 0L

    /** THE MOMENT BEHIND THE LINE, as a number to sort by (iOS presenceStamp): a live word above every stamp, «gone» at the bottom. */
    fun stamp(ref: String): Long {
        val now = System.currentTimeMillis()
        if ((typingUntil[ref] ?: 0) > now) return now + 3
        if ((inChatUntil[ref] ?: 0) > now) return now + 2
        if ((onlineUntil[ref] ?: 0) > now) return now + 1
        if (Prefs.bool("peerGone.$ref", false)) return 1
        return seenAt(ref)
    }

    /** The one presence line (iOS presenceWord): the words and whether they are live (green). null — nothing is known. */
    fun word(c: Context, ref: String): Pair<String, Boolean>? {
        val now = System.currentTimeMillis()
        if ((typingUntil[ref] ?: 0) > now) return c.getString(R.string.pr_typing) to true
        if ((inChatUntil[ref] ?: 0) > now) return c.getString(R.string.pr_in_chat) to true
        if ((onlineUntil[ref] ?: 0) > now) return c.getString(R.string.pr_online) to true
        if (Prefs.bool("peerGone.$ref", false)) return c.getString(R.string.pr_seen_long_ago) to false
        val seen = seenAt(ref).takeIf { it > 0 } ?: return null
        val exact = sharing && !Prefs.bool("phide_$ref", false)
        return (if (exact) phrase(c, seen) else coarse(c, seen)) to false
    }
    /** iOS MontanaSeen.coarse: the class, never the minute. */
    private fun coarse(c: Context, ts: Long): String {
        val d = System.currentTimeMillis() - ts
        return c.getString(when {
            d < 3 * 86_400_000L -> R.string.pr_seen_recently
            d < 7 * 86_400_000L -> R.string.pr_seen_week
            d < 30 * 86_400_000L -> R.string.pr_seen_month
            else -> R.string.pr_seen_long_ago
        })
    }
    /** iOS MontanaSeen.phrase: minutes within the hour (never under one), «today at», «yesterday at», else the day. */
    private fun phrase(c: Context, ts: Long): String {
        val diff = System.currentTimeMillis() - ts
        if (diff < 3_600_000L) { val m = maxOf(1, (diff / 60_000L).toInt()); return c.resources.getQuantityString(R.plurals.pr_minutes, m, m) }
        val time = android.text.format.DateFormat.getTimeFormat(c).format(java.util.Date(ts))
        if (android.text.format.DateUtils.isToday(ts)) return c.getString(R.string.pr_today_at, time)
        if (android.text.format.DateUtils.isToday(ts + 86_400_000L)) return c.getString(R.string.pr_yesterday_at, time)
        return android.text.format.DateUtils.formatDateTime(c, ts, android.text.format.DateUtils.FORMAT_SHOW_DATE)
    }

    // ── the app's own beat (iOS appBeacon + the 20-second heartbeat) ──
    private var appOpen = false
    private val beat = object : Runnable {
        override fun run() { if (appOpen) { Thread { sayApp(true) }.start(); MainThread.later(BEAT, this) } }
    }
    fun appShown() { if (appOpen) return; appOpen = true; MainThread.post { beat.run() } }
    fun appHidden() { if (!appOpen) return; appOpen = false; Thread { sayApp(false) }.start() }
    fun log(s: String) = Log.d("Montana", "presence: $s")

    /**
     * «The app is on my screen» follows the activity's own life (iOS: the scene's phase) — watched from here, so no other
     * file carries it: registered once, on the first pickup of the box (which runs only while the app stands).
     */
    private var watching = false
    fun watchApp(c: Context) {
        if (watching) return
        watching = true
        (c.applicationContext as android.app.Application).registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: android.app.Activity) = appShown()
            override fun onActivityPaused(a: android.app.Activity) = appHidden()
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityStarted(a: android.app.Activity) {}
            override fun onActivityStopped(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
        // on the screen only when an activity really stands in front — a pickup from a background job must not say «online»;
        // the callbacks above say it when the activity resumes later
        val me = android.app.ActivityManager.RunningAppProcessInfo().also { android.app.ActivityManager.getMyMemoryState(it) }
        if (me.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) appShown()
    }
}
