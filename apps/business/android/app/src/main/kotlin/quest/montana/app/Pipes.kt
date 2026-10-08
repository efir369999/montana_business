package quest.montana.app

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

// ─────────────────────────── the wire (iOS MTNodeWire, MTPipe) ───────────────────────────

/**
 * THE FROZEN FORMULAS OF THE NODE WIRE, byte for byte as iOS writes them (MTNodeWire): every label and key is
 * SHA-256(domain ‖ 0x00 ‖ parts…), a window laid little-endian in eight bytes. The seal itself is the core's
 * (mt_e2e_seal_blob); nothing here is a cipher. `agreesWithCanon` holds the iOS vectors: a drift of one byte
 * and no letter leaves.
 */
object Wire {
    fun sha(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run { parts.forEach { update(it) }; digest() }
    private fun d(domain: String) = domain.toByteArray() + byteArrayOf(0)
    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    fun le8(w: Long) = ByteArray(8) { i -> (w ushr (8 * i)).toByte() }
    fun minute() = System.currentTimeMillis() / 60_000
    fun day() = System.currentTimeMillis() / 86_400_000

    /** The letter body key: SHA-256("mt-pipe-key" ‖ 0 ‖ secret ‖ W_LE). */
    fun bodyKey(secret: ByteArray, w: Long) = sha(d("mt-pipe-key"), secret, le8(w))
    /** The conversation's daily label at the node. */
    fun convW(secret: ByteArray, day: Long) = hex(sha(d("mt-wake-conv"), secret, le8(day)))
    /** The daily label of a handed-out invitation (the first letter's box). */
    fun rdvConvW(inv: ByteArray, day: Long) = hex(sha(d("mt-rdv-wake"), inv, le8(day)))
    /** The subscription tag standing in for the device at the node. */
    fun subId(conv: String, ref: String) = hex(sha(d("mt-wake-sub"), conv.toByteArray(), byteArrayOf(0), ref.toByteArray()))
    /** The first-meeting letter's key, derived from the invitation (iOS rdvLetterSecret). */
    fun rdvLetterSecret(inv: ByteArray) = sha(d("mt-rdv-letter"), inv)
    /** The local name of a correspondence: SHA-256(secret)[0..16] in hex (iOS MTPipeBook.reference). */
    fun reference(secret: ByteArray) = hex(sha(secret).copyOf(16))

    fun seal(key: ByteArray, plain: ByteArray): ByteArray? {
        val nonce = MtBindings.nativeRandom(12) ?: return null
        return MtBindings.nativeSealBlob(key, nonce, plain)
    }
    fun open(key: ByteArray, sealed: ByteArray): ByteArray? = MtBindings.nativeOpenBlob(key, sealed)

    /** A boxed letter wears the SENDER's minute, the node names the minute it took it: the walk goes back a day. */
    fun openBoxed(sealed: ByteArray, secret: ByteArray, atS: Long): ByteArray? {
        val w0 = atS / 60
        for (w in longArrayOf(w0, w0 - 1, w0 + 1)) open(bodyKey(secret, w), sealed)?.let { return it }
        var w = w0 - 2
        repeat(1439) { open(bodyKey(secret, w), sealed)?.let { return it }; w-- }
        return null
    }

    /** mid‖0‖text‖0‖name‖0‖glyph‖0… — up to seven fields, the last one whole (iOS parseLetterFields). */
    fun fields(plain: ByteArray, from: Int = 0): List<String> {
        val out = ArrayList<String>(7)
        var i = from
        while (out.size < 7) {
            var z = i
            while (z < plain.size && plain[z] != 0.toByte()) z++
            out.add(String(plain, i, z - i, Charsets.UTF_8))
            if (z >= plain.size) break
            i = z + 1
        }
        while (out.size < 7) out.add("")
        return out
    }

    /** One size for every envelope — the node cannot tell letter lengths apart (iOS envelopeSize). */
    const val ENVELOPE = 2048

    /** The iOS vectors (MontanaWakePush.agreesWithCanon, MTPipe body key). */
    fun agreesWithCanon(): Boolean {
        val s32 = ByteArray(32) { it.toByte() }
        return subId("mt-conv-Q7", "mtAddrZ93kLmNoPq") == "e5438bfedc8b1fdcdadfb9f4d3995fac7722c0074f3a20ccd3e911ce5beb35de"
            && subId("ab", "c") == "a279974b24ff1b84299330076b5f5858684d56733458de21b152aa8521c7a230"
            && subId("a", "bc") == "c04b7d58e4c7aaca35800d119fd6fde021f95f7d3c9438e3ca46cd855afe6a8a"
            && convW(s32, 29737) == "43eaf2811da7947166d4e851e52c4ab89e09c8c92247370d3c734ef08ca1e6ea"
            && convW(s32, 29738) == "f5f2941435d76c0a80dd229722ef42382695e88fdd1fabf89d71f317bef05430"
            && hex(bodyKey(s32, 0x0102030405060708)) == "e55fbc1e0f95c4fe012b476c4dd1a51c9455e24641248f4f1557ed57f8c14f35"
            && hex(bodyKey(s32, 1000)) == "e25542e65a84d1a5b5c72a13172ecb62319c18f21a59978a53e7dac2c22a966a"
    }

    /** One POST of JSON to a door: the code and the body (a door that does not answer is -1). */
    fun post(door: String, path: String, body: JSONObject, timeout: Int = 8000): Pair<Int, String?> = try {
        val raw = body.toString().toByteArray(Charsets.UTF_8)
        val conn = URL(door + path).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"; conn.doOutput = true
        conn.connectTimeout = timeout; conn.readTimeout = timeout
        conn.setRequestProperty("content-type", "application/json")
        conn.setFixedLengthStreamingMode(raw.size)
        conn.outputStream.use { it.write(raw) }
        val code = conn.responseCode
        val text = if (code == 200) conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } else null
        conn.disconnect()
        code to text
    } catch (_: Exception) { -1 to null }

    /** A blob onto the node (iOS putBlob): POST /blob-put {bid, data}; true once a door holds it. */
    fun putBlob(bid: String, data: ByteArray, cargo: String? = null, last: Boolean = false): Boolean = MontanaCard.doors.any { door ->
        val body = JSONObject().put("bid", bid).put("data", Base64.encodeToString(data, Base64.NO_WRAP))
        if (cargo != null) { body.put("cg", cargo); if (last) body.put("last", true) }
        post(door, "/blob-put", body, 60_000).first == 200
    }

    /** A blob of the node (iOS getBlob): POST /blob-get {bid} → {data}. */
    fun getBlob(bid: String): ByteArray? {
        for (door in MontanaCard.doors) {
            val (code, text) = post(door, "/blob-get", JSONObject().put("bid", bid))
            if (code == 200 && text != null) runCatching { JSONObject(text).optString("data").takeIf { it.isNotEmpty() }?.let { return Base64.decode(it, Base64.DEFAULT) } }
        }
        return null
    }
}

// ─────────────────────────── the words a letter can carry (iOS ContentView marks) ───────────────────────────

object Marks {
    const val READ = "​⁣"          // «read», + the birth millisecond of the newest letter covered
    const val DELIVERED = "​⁤"     // «delivered», + the letter's mid
    const val REACTION = "​​RC:"   // {sid, txt, e, op}
    const val DELETE = "​​DL:"     // + "mid:" + the letter's mid
    const val EDIT = "​​ED:"       // {sid, tx}
    const val NAME = "​​NM:"       // + the display name
    const val AVATAR = "\u200B\u200BAV:"     // + the face as base64 JPEG; empty — «no face»
    const val TYPING = "​​TY:"
    const val VOICE = "​​VC:"
    const val MEDIA = "​​MD:"
    const val STICKER = "⁣⁣"
    const val LONG = "⁣LB:"
    const val ABOUT = "\u200B\u200BAB:"   // + {b, l, at}: their bio and link (iOS aboutMark)
    const val PLAYED = "\u200B\u200BPL:"   // + the mid of a voice of theirs that was played here (iOS playedMark)
    const val SAME_ASK = "\u200B\u200BSM:"   // + base64 {t: [32 tags]}: \u00ABdo we already share a pipe?\u00BB (iOS sameAskMark)
    const val SAME_YES = "\u200B\u200BSY:"   // + base64 {p: proof or noise}: the answer (iOS sameYesMark)
    /** A word of the wire, not a row of the feed. */
    fun isService(t: String) = t.startsWith("​") || t.startsWith("⁣")

    /** A letter's name: t<birth ms>-<UUID> (iOS ChatStore.mintMid). */
    private var lastMs = 0L
    @Synchronized fun mintMid(): String {
        var ms = System.currentTimeMillis()
        if (ms <= lastMs) ms = lastMs + 1
        lastMs = ms
        return "t$ms-" + UUID.randomUUID().toString().uppercase()
    }
    fun birthMs(mid: String): Long? {
        val core = mid.removePrefix("mid:")
        if (!core.startsWith("t")) return null
        val dash = core.indexOf('-').takeIf { it > 1 } ?: return null
        return core.substring(1, dash).toLongOrNull()
    }
}

// ─────────────────────────── the book of correspondences (iOS MTPipeBook + ChatStore) ───────────────────────────

/**
 * One letter of the feed. `state` is the rung of the ladder (iOS DeliveryStatus.rung): 0 sending, 1 sent, 2 delivered, 3 read;
 * -1 not sent. `statusAt` — the moment the rung was reached; `heard` — a voice played (theirs here, or mine there).
 */
class Msg(
    val mid: String, var text: String, val mine: Boolean, val at: Long,
    var state: Int = 0, var edited: Boolean = false,
    val qt: String? = null, val qm: String? = null,
    val reactions: MutableList<String> = mutableListOf(), var myReact: String? = null,
    var file: String? = null, var meta: String? = null,   // a media letter: the assembled file, and its manifest once known
    var statusAt: Long = 0, var heard: Boolean = false,
    var lp: String? = null,   // the link's card (iOS linkPreview, LinkPreview.kt): the body's seventh field
    val from: String? = null,   // a group's speaker (iOS Message.senderRef, Groups.speakerName); null in a pipe's own feed
) {
    fun json(): JSONObject = JSONObject().put("m", mid).put("t", text).put("o", mine).put("at", at).put("s", state).put("e", edited)
        .put("qt", qt ?: "").put("qm", qm ?: "").put("r", JSONArray(reactions)).put("mr", myReact ?: "")
        .put("f", file ?: "").put("mm", meta ?: "").put("sa", statusAt).put("h", heard).put("lp", lp ?: "").put("sr", from ?: "")

    /** The moment the letter's rung was reached, or its birth (iOS Message.statusMoment). */
    val statusMoment: Long get() = if (statusAt > 0) statusAt else at

    /**
     * THE ONE DOOR TO THE RUNG (iOS ChatStore.advance): only forward; «read» only from «delivered» — reading what was never
     * received is impossible, and so is showing it; «not sent» only from «sending» or «sent» — the proven does not turn red.
     */
    fun advance(next: Int): Boolean {
        val ok = when (next) {
            -1 -> state == 0 || state == 1
            3 -> state == 2
            else -> next > state
        }
        if (ok) { state = next; statusAt = System.currentTimeMillis() }
        return ok
    }

    companion object {
        fun of(o: JSONObject) = Msg(o.getString("m"), o.getString("t"), o.getBoolean("o"), o.getLong("at"), o.optInt("s"), o.optBoolean("e"),
            o.optString("qt").ifEmpty { null }, o.optString("qm").ifEmpty { null },
            o.optJSONArray("r")?.let { a -> MutableList(a.length()) { a.getString(it) } } ?: mutableListOf(),
            o.optString("mr").ifEmpty { null }, o.optString("f").ifEmpty { null }, o.optString("mm").ifEmpty { null },
            o.optLong("sa"), o.optBoolean("h"), o.optString("lp").ifEmpty { null }, o.optString("sr").ifEmpty { null })
    }
}

/**
 * `name` — the word the person gave themselves (their NM:, the card); `pin` — the name my hand set over it (iOS
 * MTNameBook.manual), and `note` — what I keep on them (iOS the card's note). What the screens show is `shown`.
 */
class Chat(val ref: String, var name: String, var perm: Boolean = false, var unread: Int = 0, val msgs: MutableList<Msg> = mutableListOf(),
           var pin: String? = null, var note: String = "") {
    fun json(): JSONObject = JSONObject().put("ref", ref).put("n", name).put("p", perm).put("u", unread)
        .put("pn", pin ?: "").put("nt", note)
        .put("msgs", JSONArray().apply { msgs.forEach { put(it.json()) } })
    val last: Msg? get() = msgs.lastOrNull()
    /** My word stands over theirs (iOS MTNameBook.display: mine, then declared). */
    val shown: String get() = pin?.ifBlank { null } ?: name
    companion object {
        fun of(o: JSONObject) = Chat(o.getString("ref"), o.optString("n"), o.optBoolean("p"), o.optInt("u"),
            o.optJSONArray("msgs")?.let { a -> MutableList(a.length()) { Msg.of(a.getJSONObject(it)) } } ?: mutableListOf(),
            o.optString("pn").ifEmpty { null }, o.optString("nt"))
    }
}

/**
 * THE BOOK (iOS MTPipeBook + ChatStore): the secret of each correspondence, filed under its local name, and the feed of it.
 * Everything is sealed in the device vault; the secrets never leave this phone. Listeners are the open screens.
 */
object Book {
    private const val SECRETS = "pipeSecrets"
    private const val INDEX = "chatIndex"
    private val lock = Any()
    private var secrets: MutableMap<String, ByteArray>? = null
    private val chats = LinkedHashMap<String, Chat>()
    private var loaded = false
    private val listeners = mutableListOf<() -> Unit>()
    var openChat: String? = null   // the chat on the screen: its letters are read as they land
    lateinit var ctx: Context

    fun listen(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun unlisten(l: () -> Unit) { synchronized(listeners) { listeners.remove(l) } }
    private fun changed() { val ls = synchronized(listeners) { listeners.toList() }; MainThread.post { ls.forEach { it() } } }

    private fun sec(): MutableMap<String, ByteArray> {
        secrets?.let { return it }
        val m = DeviceVault.get(SECRETS)?.let { raw ->
            runCatching { JSONObject(String(raw, Charsets.UTF_8)).let { o -> o.keys().asSequence().associateWith { Base64.decode(o.getString(it), Base64.NO_WRAP) } } }.getOrNull()
        }?.toMutableMap() ?: mutableMapOf()
        secrets = m; return m
    }
    private fun ensure() {
        if (loaded) return
        loaded = true
        val idx = DeviceVault.get(INDEX)?.let { runCatching { JSONArray(String(it, Charsets.UTF_8)) }.getOrNull() } ?: JSONArray()
        for (i in 0 until idx.length()) {
            val ref = idx.getString(i)
            DeviceVault.get("chat:$ref")?.let { raw -> runCatching { Chat.of(JSONObject(String(raw, Charsets.UTF_8))) }.getOrNull() }?.let { chats[ref] = it }
        }
    }
    private fun saveIndex() { DeviceVault.set(INDEX, JSONArray(chats.keys.toList()).toString().toByteArray()) }
    private fun save(c: Chat) { DeviceVault.set("chat:${c.ref}", c.json().toString().toByteArray()) }

    fun secret(ref: String): ByteArray? = synchronized(lock) { sec()[ref] }
    fun refs(): List<String> = synchronized(lock) { sec().keys.toList() }

    /** A correspondence is born from the secret its first letter produced (iOS MTPipeBook.establish). */
    fun establish(secret: ByteArray): String? = synchronized(lock) {
        val ref = Wire.reference(secret)
        val m = sec()
        m[ref]?.let { return if (it.contentEquals(secret)) ref else null }
        m[ref] = secret
        val o = JSONObject(); m.forEach { (k, v) -> o.put(k, Base64.encodeToString(v, Base64.NO_WRAP)) }
        if (!DeviceVault.set(SECRETS, o.toString().toByteArray())) { m.remove(ref); return null }
        ref
    }

    fun all(): List<Chat> = synchronized(lock) { ensure(); chats.values.sortedByDescending { it.last?.at ?: 0 } }
    fun chat(ref: String): Chat? = synchronized(lock) { ensure(); chats[ref] }

    fun open(ref: String, name: String, perm: Boolean): Chat = synchronized(lock) {
        ensure()
        chats[ref] ?: Chat(ref, name, perm).also { chats[ref] = it; save(it); saveIndex() }
    }.also { changed() }

    /** Any change to a chat: written, and the screens told. */
    fun edit(ref: String, work: (Chat) -> Unit) {
        synchronized(lock) { ensure(); val c = chats[ref] ?: return; work(c); save(c) }
        changed()
    }

    /** A chat deleted here (and its pipe with it — iOS MTPipeBook.forget), with every pipe folded into it. */
    fun forget(ref: String) {
        val dead = listOf(ref) + SamePair.folded(ref)
        synchronized(lock) {
            ensure(); chats.remove(ref); DeviceVault.delete("chat:$ref"); saveIndex()
            val m = sec(); dead.forEach { m.remove(it); Meeting.forget(it) }
            val o = JSONObject(); m.forEach { (k, v) -> o.put(k, Base64.encodeToString(v, Base64.NO_WRAP)) }
            DeviceVault.set(SECRETS, o.toString().toByteArray())
        }
        SamePair.drop(dead)
        face(ref).delete(); myFace(ref).delete()
        ChatWall.forget(ref)
        changed()
    }

    /** Two pipes proven to be one person: whichever conversations they speak for become one (iOS joinSamePerson). */
    fun join(pipe: String, proven: String) {
        val a = SamePair.root(pipe); val b = SamePair.root(proven)
        if (a != b) fold(a, b)
    }

    /**
     * THE FOLD (iOS foldConversation): the newer conversation's letters join the older one's feed, the newer row leaves,
     * the meeting books open the older one, and the newer pipe forwards into it until it dies.
     */
    private fun fold(newer: String, older: String) {
        if (newer == older || SamePair.merged(older) != null || SamePair.merged(newer) != null) return
        synchronized(lock) {
            ensure()
            val n = chats.remove(newer)
            val o = chats[older] ?: Chat(older, n?.name ?: "", n?.perm ?: false).also { chats[older] = it }
            if (n != null) {
                val had = o.msgs.map { it.mid }.toSet()
                o.msgs.addAll(n.msgs.filter { it.mid !in had }); o.msgs.sortBy { it.at }
                o.unread += n.unread
                if (o.name.isEmpty()) o.name = n.name
                if (o.pin == null) o.pin = n.pin
                if (o.note.isEmpty()) o.note = n.note
                o.perm = o.perm || n.perm
            }
            save(o); DeviceVault.delete("chat:$newer"); saveIndex()
        }
        val nf = face(newer); val of = face(older)
        if (nf.exists()) { if (!of.exists()) nf.renameTo(of) else nf.delete() }
        val nm = myFace(newer); val om = myFace(older)
        if (nm.exists()) { if (!om.exists()) nm.renameTo(om) else nm.delete() }
        SamePair.noteMerged(newer, older)
        Meeting.repoint(newer, older)
        if (openChat == newer) openChat = older
        changed()
    }

    /** The face the person published (their AV:, the card's face, the pipe's face). */
    fun face(ref: String) = File(File(ctx.filesDir, "faces").apply { mkdirs() }, "$ref.jpg")
    /** The picture my hand set on their card (iOS MTNameBook.setManualPhoto): it wins over theirs while it stands. */
    fun myFace(ref: String) = File(File(ctx.filesDir, "faces").apply { mkdirs() }, "$ref.mine.jpg")
    /** The one face of a person, resolved in one place: mine, then theirs (iOS MTNameBook avatar resolution). */
    fun shownFace(ref: String): File = myFace(ref).takeIf { it.exists() } ?: face(ref)

    /**
     * THE EDIT PAGE'S «DONE» (iOS mtHandProfileEdit → MTNameBook.setManual/setManualPhoto): a name equal to their own word
     * clears the pin instead of setting it, an empty field restores their name; the photo — bytes set by hand, or null to
     * give theirs back.
     */
    fun saveCard(ref: String, first: String, last: String, note: String, photo: ByteArray?, photoCleared: Boolean) {
        edit(ref) { c ->
            val full = (first.trim() + " " + last.trim()).trim()
            c.pin = if (full.isEmpty() || full == c.name.trim()) null else full
            c.note = note.trim()
        }
        if (photo != null) myFace(ref).writeBytes(photo) else if (photoCleared) myFace(ref).delete()
        changed()
    }

    /** The person leaves: every correspondence leaves with them. */
    fun wipe() {
        synchronized(lock) {
            ensure(); chats.keys.forEach { DeviceVault.delete("chat:$it") }; chats.clear()
            DeviceVault.delete(INDEX); DeviceVault.delete(SECRETS); secrets = null
        }
        File(ctx.filesDir, "faces").deleteRecursively()
        DeviceVault.delete(Post.OUTBOX)
        Meeting.wipe(); SamePair.wipe(); Groups.wipe()
        changed()
    }
}

object MainThread {
    private val h = android.os.Handler(android.os.Looper.getMainLooper())
    fun post(r: () -> Unit) { h.post(r) }
    fun later(ms: Long, r: Runnable) { h.postDelayed(r, ms) }
}

// ─────────────────────────── the post: the node box both ways (iOS MontanaWakePush box + delivery engine) ───────────────────────────

/**
 * THE POST. A letter goes to the node's box under the conversation's daily label, sealed under the pipe key of the minute it
 * was sealed in (iOS knockLetter); the receiver reads the box by its labels (iOS fetchBox), opens what is its own, and tells
 * the node to bury what it took (box-del). The first letter of a meeting lies under the invitation's label, sealed under the
 * invitation's letter key, and carries the encapsulation that begets the pipe (iOS wakeRdv / drainInbox «rdv»).
 * The node reads zero bytes: it sees labels that change every day and envelopes of one size.
 */
object Post {
    const val OUTBOX = "outbox"
    private val outLock = Any()
    private val fetchLock = Any()
    private var fetching = false

    // ── sending ──
    /**
     * Queue a letter and knock at once; a letter that no door took waits in the outbox and rides the next round. [silent] -- the
     * node wakes the receiver without a bell (iOS enqueue silent: a group's service word, an old letter caught up).
     */
    fun send(to0: String, mid: String, text: String, qt: String? = null, qm: String? = null, lp: String? = null, silent: Boolean = false) {
        // a group's feed has no pipe: its words leave as the group's copies to each receiver (Groups.send, iOS MTGroup.send);
        // a link's card that follows a letter is not carried into a group
        if (Groups.isKey(to0)) { if (lp == null) Groups.send(to0, mid, text, qt, qm); return }
        val to = if (SamePair.speaksOfPipe(text)) to0 else SamePair.root(to0)   // a folded pipe's words go by its conversation's pipe
        synchronized(outLock) {
            val a = outbox()
            for (i in 0 until a.length()) if (a.getJSONObject(i).optString("m") == mid) {
                // one letter, one place in the queue; its card, come later, joins the place it already holds (iOS attachPreview)
                if (lp != null) { a.getJSONObject(i).put("lp", lp); DeviceVault.set(OUTBOX, a.toString().toByteArray()) }
                return
            }
            a.put(JSONObject().put("to", to).put("m", mid).put("t", text).put("qt", qt ?: "").put("qm", qm ?: "").put("lp", lp ?: "")
                .apply { if (silent) put("sl", true) })
            DeviceVault.set(OUTBOX, a.toString().toByteArray())
        }
        Thread { flush() }.start()
    }
    private fun outbox(): JSONArray = DeviceVault.get(OUTBOX)?.let { runCatching { JSONArray(String(it, Charsets.UTF_8)) }.getOrNull() } ?: JSONArray()

    /** Every waiting letter knocks once more; the ones a door boxed leave the queue. */
    fun flush() {
        val items = synchronized(outLock) { outbox() }
        val done = mutableSetOf<String>()
        for (i in 0 until items.length()) {
            val o = items.getJSONObject(i)
            val to = o.getString("to"); val mid = o.getString("m")
            val secret = Book.secret(to)
            if (secret == null) { done.add(mid); continue }   // the pipe is gone — nobody to deliver to
            // WHILE THE INTRODUCTION IS UNANSWERED every letter a person wrote rides the invitation's box with the ciphertext;
            // the words of the wire (receipts, reads, name, face) wait for the pipe to stand (iOS first_hold).
            val intro = Meeting.first(to)
            val text = o.getString("t")
            val sent = if (intro != null) {
                // the question «do we already share a pipe?» is itself the silent first letter that carries the ciphertext (iOS MTSamePair.ask)
                if (Marks.isService(text) && !text.startsWith(Marks.MEDIA) && !text.startsWith(Marks.VOICE) && !text.startsWith(Marks.SAME_ASK)) false
                else {
                    val words = if (text.toByteArray().size > 300) sealLong(mid, text) else text
                    words != null && Meeting.knockFirst(secret, intro.first, intro.second, mid, words)
                }
            } else knock(secret, mid, text, o.optString("qt").ifEmpty { null }, o.optString("qm").ifEmpty { null }, o.optString("lp").ifEmpty { null },
                o.optBoolean("sl"))
            if (sent) {
                done.add(mid)
                Book.edit(SamePair.root(to)) { c -> c.msgs.find { it.mid == mid }?.advance(1) }   // a letter queued on a pipe since folded settles in the conversation
                Groups.copySent(mid)   // a group's copy: its letter's row in the group's feed has left this phone
            }
        }
        if (done.isEmpty()) return
        synchronized(outLock) {
            val a = outbox(); val keep = JSONArray()
            for (i in 0 until a.length()) a.getJSONObject(i).let { if (it.getString("m") !in done) keep.put(it) }
            DeviceVault.set(OUTBOX, keep.toString().toByteArray())
        }
    }

    /** ONE KNOCK (iOS MTNodeWire.knockLetter): the body is sealed afresh at every door, the first door that boxes it ends the walk. */
    private fun knock(secret: ByteArray, mid: String, text0: String, qt: String?, qm: String?, lp: String? = null, silent: Boolean = false): Boolean {
        val twin = MontanaSeed.twin ?: return false
        // A LETTER TOO LONG FOR ONE ENVELOPE rides as a blob under a key of its own, the envelope carries the reference (iOS sealLongLetter).
        val text = if (text0.toByteArray().size > Wire.ENVELOPE - 400) sealLong(mid, text0) ?: return false else text0
        var body = mid.toByteArray() + 0 + text.toByteArray() + 0 + Prefs.userName.trim().toByteArray() + 0 + byteArrayOf(0)
        val quoted = !qt.isNullOrEmpty()
        if (quoted) {
            val tail = qt!!.take(200).toByteArray() + 0 + (qm ?: "").toByteArray() + 0
            if (body.size + tail.size <= Wire.ENVELOPE) body += tail
        }
        // THE LINK'S CARD IS THE SEVENTH FIELD (iOS wireTail / wireTailAfterQuote): after the quote pair, or after two empty
        // fields where the quote would stand; the envelope carries it without its picture, and only when it fits — decoration
        lp?.let { LinkCard.parse(it)?.json(withPicture = false) }?.takeIf { it.toByteArray().size > 64 }?.let { card ->
            val tail = (if (quoted) ByteArray(0) else byteArrayOf(0, 0)) + card.toByteArray()
            if (body.size + tail.size <= Wire.ENVELOPE) body += tail
        }
        if (body.size > Wire.ENVELOPE) return false
        val plain = body + ByteArray(Wire.ENVELOPE - body.size)
        val cw = Wire.convW(secret, Wire.day())
        for (door in MontanaCard.doors) {
            val env = Wire.seal(Wire.bodyKey(secret, Wire.minute()), plain) ?: return false
            val (code, _) = Wire.post(door, "/wake", JSONObject().put("conv", cw).put("from_id", Wire.subId(cw, twin)).put("mid", mid)
                .put("env", Base64.encodeToString(env, Base64.NO_WRAP)).apply { if (silent) put("silent", true) })   // a background wake, no banner
            if (code == 200) return true
        }
        return false
    }
    private operator fun ByteArray.plus(b: Int) = this + byteArrayOf(b.toByte())

    /** mid‖0‖text sealed under a fresh key, laid on the node under SHA-256 of the seal (iOS blobIdHex); the reference once per letter. */
    private fun sealLong(mid: String, text: String): String? {
        Prefs.str("longRef.$mid", "").takeIf { it.isNotEmpty() }?.let { return it }
        val mk = MtBindings.nativeRandom(32) ?: return null
        val sealed = Wire.seal(mk, mid.toByteArray() + 0 + text.toByteArray()) ?: return null
        val bid = Wire.hex(Wire.sha(sealed))
        if (!Wire.putBlob(bid, sealed)) return null
        val link = Marks.LONG + JSONObject().put("r", bid).put("k", Base64.encodeToString(mk, Base64.NO_WRAP))
        Prefs.setStr("longRef.$mid", link)
        return link
    }

    /** The words behind a long letter's reference: the words, null — later (no door answered), "" — gone for good. */
    private fun fetchLong(text: String): String? {
        val o = runCatching { JSONObject(text.removePrefix(Marks.LONG)) }.getOrNull() ?: return ""
        val mk = runCatching { Base64.decode(o.getString("k"), Base64.DEFAULT) }.getOrNull() ?: return ""
        val sealed = Wire.getBlob(o.optString("r")) ?: return null
        val plain = Wire.open(mk, sealed) ?: return ""
        val sep = plain.indexOf(0).takeIf { it >= 0 } ?: return ""
        return String(plain, sep + 1, plain.size - sep - 1, Charsets.UTF_8)
    }

    /**
     * MY NAME AND MY FACE GO TO EVERY CORRESPONDENT (iOS sendNameIfNeeded, sendFace): once, and again whenever they change.
     * The face rides as AV:+base64 JPEG — a long letter by construction; an empty AV: says «no face».
     */
    fun announce(ref: String) {
        val name = Prefs.userName.trim()
        if (name.isNotEmpty() && Prefs.str("annName.$ref", "") != name) {
            Prefs.setStr("annName.$ref", name); send(ref, Marks.mintMid(), Marks.NAME + name)
        }
        MyAbout.sendIfNeeded(ref)
        val face = SelfFace.bytes(Book.ctx) ?: ByteArray(0)
        val tag = Wire.hex(Wire.sha(face)).take(16)
        if (Prefs.str("annFace.$ref", "") != tag) {
            Prefs.setStr("annFace.$ref", tag)
            send(ref, Marks.mintMid(), Marks.AVATAR + if (face.isEmpty()) "" else Base64.encodeToString(face, Base64.NO_WRAP))
        }
    }

    // ── receiving ──
    /** ONE PICKUP AT A TIME (iOS fetchBox): the labels of every live invitation and every pipe, across the box's term. */
    fun fetch() {
        MainThread.post { Presence.watchApp(Book.ctx) }
        synchronized(fetchLock) { if (fetching) return; fetching = true }
        try { fetchOnce() } catch (e: Exception) { Log.w("Montana", "box fetch: ${e.javaClass.simpleName}") }
        finally { synchronized(fetchLock) { fetching = false } }
        SamePair.askUnasked()
        Book.refs().filter { SamePair.merged(it) == null }.forEach { announce(it) }   // a folded pipe only forwards
        Media.fetchPending(Book.ctx)
        flush()
    }

    private fun fetchOnce() {
        if (!Wire.agreesWithCanon()) { Log.e("Montana", "box: the wire disagrees with the canon — nothing is read"); return }
        val twin = MontanaSeed.twin ?: return
        val secretOf = HashMap<String, ByteArray>()
        val invOf = HashMap<String, String>()
        val chatOf = HashMap<String, String>()
        val w0 = Wire.day()
        for (inv in MontanaCard.outstandingInvites()) {
            val s = Wire.rdvLetterSecret(inv)
            for (w in (w0 - 8)..(w0 + 1)) { val cw = Wire.rdvConvW(inv, w); secretOf[cw] = s; invOf[cw] = MontanaCard.b64url(inv) }
        }
        for (ref in Book.refs()) {
            val s = Book.secret(ref) ?: continue
            for (w in (w0 - 8)..(w0 + 1)) { val cw = Wire.convW(s, w); secretOf[cw] = s; chatOf[cw] = ref }
        }
        val subs = secretOf.keys.toList()
        if (subs.isEmpty()) return
        val taken = mutableListOf<String>()
        for (chunk in subs.chunked(128)) {
            val page = JSONObject().put("subs", JSONArray(chunk)).put("sids", JSONArray(chunk.map { Wire.subId(it, twin) }))
            val seen = HashSet<String>()
            val rows = mutableListOf<JSONObject>()
            // THE READ SWEEPS EVERY STORE: a letter lies on the one node whose door took it.
            for (door in MontanaCard.doors) {
                val (code, text) = Wire.post(door, "/fetch", page, 6000)
                if (code != 200 || text == null) continue
                val ls = runCatching { JSONObject(text).optJSONArray("letters") }.getOrNull() ?: continue
                for (i in 0 until ls.length()) ls.getJSONObject(i).let { if (seen.add(it.optString("m"))) rows.add(it) }
            }
            for (l in rows) {
                val cw = l.optString("c"); val e = l.optString("e"); val m = l.optString("m")
                val secret = secretOf[cw] ?: continue
                val sealed = runCatching { Base64.decode(e, Base64.DEFAULT) }.getOrNull() ?: continue
                val plain = Wire.openBoxed(sealed, secret, l.optLong("at")) ?: continue
                val ok = invOf[cw]?.let { inv -> first(plain, inv) } ?: chatOf[cw]?.let { ref -> letter(ref, plain) } ?: false
                if (ok && m.isNotEmpty()) taken.add(m)
            }
        }
        if (taken.isNotEmpty()) {
            val body = JSONObject().put("mids", JSONArray(taken))
            for (door in MontanaCard.doors) Wire.post(door, "/box-del", body, 5000)
        }
    }

    /**
     * THE FIRST LETTER OF A MEETING (iOS drainInbox «rdv» + MontanaCard.accept): ct[1088] ‖ mid‖0 text‖0 name‖0 glyph‖0 conf‖0.
     * The pipe is born from it, the chat stands with the scanner's name, the face laid beside the pipe is read, and the letter
     * is answered «delivered» — the scanner's proof that the pipe stands at both ends.
     */
    private fun first(plain: ByteArray, inv64: String): Boolean {
        if (plain.size <= 1092) return false
        val ct = plain.copyOf(1088)
        val f = Wire.fields(plain, 1088)
        val mid = f[0]; var text = f[1]
        if (mid.isEmpty() || text.isEmpty()) return false
        // a long first letter (the question of MTSamePair always is one) rides as a blob; unfolded before the pipe is born
        if (text.startsWith(Marks.LONG)) text = fetchLong(text)?.ifEmpty { text } ?: return false
        val (secret, root) = MontanaCard.acceptFirst(ct, inv64, f[4]) ?: return true   // not ours to open: buried, never retried
        val ref = Book.establish(secret) ?: return false
        val name = f[2].takeIf { it.isNotBlank() && it.length <= 64 } ?: ""
        val fresh = Book.chat(ref) == null
        Book.open(ref, name, MontanaCard.isPermanentRoot(root))
        if (fresh) Thread { readPipeFace(ref, secret) }.start()
        place(ref, mid, text, name, null, null)
        announce(ref)
        return true
    }

    /** A letter under a pipe's label: a person's word becomes a row, a word of the wire changes the feed. */
    private fun letter(ref: String, plain: ByteArray): Boolean {
        val f = Wire.fields(plain)
        if (f[0].isEmpty() || f[1].isEmpty()) return false
        Meeting.firstDone(ref)   // a word sealed under the pipe by the other side: the introduction is over
        var text = f[1]
        if (text.startsWith(Marks.LONG)) text = fetchLong(text)?.ifEmpty { text } ?: return false   // later: the letter waits in the box
        place(ref, f[0], text, f[2], f[4].ifEmpty { null }, f[5].ifEmpty { null }, f[6].ifEmpty { null })
        return true
    }

    private fun place(ref: String, mid: String, text: String, name: String, qt: String?, qm: String?, lp: String? = null) {
        val sid = mid
        // A FOLDED PIPE FORWARDS (iOS MTSamePair): a word still on its way over a pipe folded into a conversation lands in that
        // conversation; a letter of theirs into it says our answer may have been lost, and it is said again.
        val speaksFor = SamePair.root(ref)
        if (speaksFor != ref && !SamePair.speaksOfPipe(text)) {
            val person = !Marks.isService(text) || text.startsWith(Marks.VOICE) || text.startsWith(Marks.MEDIA) || text.startsWith(Marks.STICKER)
            if (person) SamePair.remind(ref)
            return place(speaksFor, mid, text, name, qt, qm, lp)
        }
        when {
            // ONE PERSON, ONE CONVERSATION (iOS MTSamePair): the scanner's question over a pipe just born from my card —
            // answered, and folded when I share an older pipe with them — and the owner's answer to my own question.
            text.startsWith(Marks.SAME_ASK) -> {
                receipt(ref, sid)
                val q = SamePair.shared(text.removePrefix(Marks.SAME_ASK), ref)
                SamePair.answer(q, ref)
                if (q != null) Book.join(ref, q)
            }
            text.startsWith(Marks.SAME_YES) -> {
                receipt(ref, sid)
                SamePair.settle(ref)
                SamePair.proven(text.removePrefix(Marks.SAME_YES), ref)?.let { Book.join(ref, it) }
            }
            text.startsWith(Marks.DELIVERED) -> { val d = text.removePrefix(Marks.DELIVERED); if (!Groups.copyDelivered(d)) Book.edit(ref) { c -> c.msgs.find { it.mid == d && it.mine }?.advance(2) } }
            text.startsWith(Marks.READ) -> {
                val upTo = text.removePrefix(Marks.READ).toLongOrNull()
                Presence.noteSeen(ref, System.currentTimeMillis())   // reading is being there (not a word a phone says by itself)
                // the door lets «read» only onto what was delivered — the bulk word never paints a letter they do not have
                Book.edit(ref) { c -> c.msgs.filter { it.mine && (upTo == null || (Marks.birthMs(it.mid) ?: 0) <= upTo) }.forEach { it.advance(3) } }
            }
            // A VOICE OF MINE WAS PLAYED THERE (iOS playedMark): their own playing — the only road to «Listened»; the word itself
            // proves their build says it, so from now on a mere «read» of a voice stays «delivered».
            // THEIR BIO AND LINK AS STATE (iOS aboutMark): applied if it is the latest word, receipted either way.
            text.startsWith(Marks.ABOUT) -> {
                runCatching {
                    val o = JSONObject(text.removePrefix(Marks.ABOUT))
                    PeerAbout.note(ref, o.optString("b"), o.optString("l"), o.optDouble("at", System.currentTimeMillis() / 1000.0))
                }
                Book.edit(ref) {}
                receipt(ref, sid)
            }
            text.startsWith(Marks.PLAYED) -> {
                val d = text.removePrefix(Marks.PLAYED)
                Prefs.setBool("plcap_$ref", true)
                Book.edit(ref) { c -> c.msgs.find { it.mid == d && it.mine }?.heard = true }
            }
            text.startsWith(Marks.DELETE) -> { val d = text.removePrefix(Marks.DELETE).removePrefix("mid:"); Book.edit(ref) { c -> c.msgs.removeAll { it.mid == d } } }
            text.startsWith(Marks.EDIT) -> runCatching {
                val o = JSONObject(text.removePrefix(Marks.EDIT)); val d = o.getString("sid").removePrefix("mid:")
                Book.edit(ref) { c -> c.msgs.find { it.mid == d && !it.mine }?.let { it.text = o.getString("tx"); it.edited = true } }
            }
            text.startsWith(Marks.REACTION) -> runCatching {
                val o = JSONObject(text.removePrefix(Marks.REACTION)); val d = o.getString("sid").removePrefix("mid:"); val e = o.getString("e")
                Book.edit(ref) { c -> c.msgs.find { it.mid == d }?.let { m -> if (o.optString("op") == "del") m.reactions.remove(e) else if (e !in m.reactions) m.reactions.add(e) } }
            }
            // THEIR PIN OR UNPIN OF A LETTER (iOS pinMark → applyPinFromControl): silent, the plate follows
            text.startsWith(PIN_MARK) -> applyPin(ref, text.removePrefix(PIN_MARK))
            // THE CONVERSATION WIPED AT BOTH (iOS convDelMark): the receipt settles their queue, then it is buried here too
            text.startsWith(CONVDEL_MARK) -> { receipt(ref, sid); buryChat(ref) }
            // THE ORGANIZATION'S CHAINS (Montana Business, Biz.receive): taken by the core on the Business's thread, receipted once taken
            text.startsWith(Biz.MARK) -> Biz.receive(ref, text.removePrefix(Biz.MARK)) { receipt(ref, sid) }
            text.startsWith(Marks.NAME) -> {
                val n = text.removePrefix(Marks.NAME).trim()
                if (n.isNotEmpty() && n.length <= 64) Book.edit(ref) { it.name = n }
                receipt(ref, sid)
            }
            text.startsWith(Marks.AVATAR) -> {
                val b64 = text.removePrefix(Marks.AVATAR)
                val face = if (b64.isEmpty()) null else runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
                if (face == null || face.isEmpty()) Book.face(ref).delete() else if (face.size <= 262_144) Book.face(ref).writeBytes(face)
                Book.edit(ref) {}
                receipt(ref, sid)
            }
            // A GROUP'S WORD (iOS MTGroup.handle): an invitation, a letter or an answer of a group its owner carries over the pipes —
            // it lands in the group's own feed, never as a row of this pipe, and its copy is receipted by its own name
            text.startsWith(Groups.MARK) -> Groups.handle(ref, sid, text)
            Marks.isService(text) && !text.startsWith(Marks.VOICE) && !text.startsWith(Marks.MEDIA) && !text.startsWith(Marks.STICKER) && !text.startsWith(Marks.LONG) -> {}
            // A BLOCKED PERSON'S LETTERS DO NOT REACH THE FEED (iOS MontanaSafety: the block is enforced on every road).
            PeerSafety.isBlocked(ref) -> {}
            else -> {
                var isNew = false
                Presence.noteSeen(ref, Marks.birthMs(mid))   // a person's own word stamps the moment it was written (iOS append)
                Book.edit(ref) { c ->
                    val had = c.msgs.find { it.mid == mid }
                    // THE SAME LETTER AGAIN, NOW WITH ITS CARD (iOS attachPreview's copy): the card joins the row, nothing else moves
                    if (had != null && lp != null && LinkCard.parse(lp) != null && had.lp == null) had.lp = lp
                    if (had == null) {
                        val at = Marks.birthMs(mid) ?: System.currentTimeMillis()
                        c.msgs.add(Msg(mid, text, false, at, qt = qt, qm = qm, lp = lp?.takeIf { LinkCard.parse(it) != null })); c.msgs.sortBy { it.at }
                        if (Book.openChat != ref) c.unread++
                        if (c.name.isEmpty() && name.isNotBlank()) c.name = name
                        isNew = true
                    }
                }
                // A MEDIA LETTER IS ANSWERED ONLY ONCE ITS FILE IS ASSEMBLED (iOS: the receipt waits for the file).
                if (text.startsWith(Marks.MEDIA)) Media.fetch(Book.ctx, ref, mid, text) else receipt(ref, sid)
                if (isNew && Book.openChat == ref) markRead(ref)
                if (isNew && Book.openChat != ref) Notify.letter(ref, text)
            }
        }
    }

    /** «Delivered» for a letter of theirs — under its own name, so the queue holds one per letter (iOS receiptMid). */
    private fun receipt(ref: String, mid: String) = send(ref, "rcpt-$mid", Marks.DELIVERED + mid)
    fun receiptFor(ref: String, mid: String) = receipt(ref, mid)

    /**
     * A VOICE OF THEIRS WAS PLAYED HERE (iOS notePlayed): its sender is told once, silently. Opening the chat plays nothing
     * and says nothing.
     */
    fun notePlayed(mid: String) {
        val ref = Book.refs().firstOrNull { r -> Book.chat(r)?.msgs?.any { it.mid == mid } == true } ?: return
        var fresh = false
        Book.edit(ref) { c -> c.msgs.find { it.mid == mid && !it.mine && !it.heard }?.let { it.heard = true; fresh = true } }
        if (fresh) send(ref, Marks.mintMid(), Marks.PLAYED + mid)
    }

    /** «Read» covering the newest letter of theirs on the screen, by their birth millisecond (iOS sendReadMark). */
    fun markRead(ref: String) {
        val c = Book.chat(ref) ?: return
        if (!Prefs.bool("readReceiptsEnabled", true)) { Book.edit(ref) { it.unread = 0 }; return }
        val upTo = c.msgs.filter { !it.mine }.mapNotNull { Marks.birthMs(it.mid) }.maxOrNull()
        Book.edit(ref) { it.unread = 0 }
        send(ref, Marks.mintMid(), Marks.READ + (upTo?.toString() ?: ""))
    }

    /** THE FACE BESIDE THE PIPE (iOS readPipeFace): asked at once and twice more across eight seconds. */
    private fun readPipeFace(ref: String, secret: ByteArray) {
        val bid = Wire.hex(Wire.sha("mt-pipe-f".toByteArray() + 0, secret))
        val key = Wire.sha("mt-pipe-fk".toByteArray() + 0, secret)
        for (pause in longArrayOf(0, 2000, 6000)) {
            if (pause > 0) Thread.sleep(pause)
            val sealed = Wire.getBlob(bid) ?: continue
            val face = Wire.open(key, sealed) ?: return
            if (face.isEmpty() || face.size > 262_144) return
            Book.face(ref).writeBytes(face)
            Book.edit(ref) {}
            return
        }
    }
}

/**
 * MY WORDS ABOUT MYSELF (iOS MTPeerAbout.myBio/myLink + E2E.sendAboutIfNeeded): the bio trimmed to 140, the link only when
 * it is a web link; to every correspondent as one silent AB: {b, l, at} — again whenever they change, never twice for the
 * same words, and nothing at all while nothing was ever said and nothing is said now.
 */
object MyAbout {
    const val BIO_LIMIT = 140
    fun bio() = Prefs.str("profileBio", "").trim().take(BIO_LIMIT)
    fun link() = PeerAbout.url(Prefs.str("profileLink", ""))?.toString() ?: ""
    private fun tag(b: String, l: String) = if (b.isEmpty() && l.isEmpty()) "0" else Wire.hex(Wire.sha((b + "\n" + l).toByteArray())).take(16)
    fun sendIfNeeded(ref: String) {
        if (PeerSafety.isBlocked(ref) || SamePair.merged(ref) != null) return
        val b = bio(); val l = link(); val t = tag(b, l)
        val held = Prefs.str("annAbout.$ref", "")
        if (held == t || (held.isEmpty() && t == "0")) return
        Prefs.setStr("annAbout.$ref", t)
        Post.send(ref, Marks.mintMid(), Marks.ABOUT + JSONObject().put("b", b).put("l", l).put("at", System.currentTimeMillis() / 1000.0))
    }
    fun broadcast() { Book.refs().forEach { sendIfNeeded(it) } }
}
