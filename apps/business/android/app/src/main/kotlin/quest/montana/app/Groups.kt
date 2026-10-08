package quest.montana.app

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.BreakIterator

/**
 * GROUPS AND CHANNELS, CARRIED BY THE PHONES THEMSELVES (iOS MTGroup, MontanaGroup.swift). No server holds a group: its owner's
 * phone holds a pipe with every member and carries every word to the others over those pipes — a star through the owner. This
 * phone takes the owner's words: the invitation that stands the group here, the letters of its people, their answers (a
 * reaction, an edit, a deletion) and the owner taking this phone out. The word on the wire is iOS's own: the mark, then JSON
 * with iOS's short keys; a key a reader does not know is skipped by it.
 *
 * AN ORGANIZATION'S CHAT HAS NO CARRIER (Montana Business Sh.2, iOS 24a0c1a4; the checklist: every one to every one): it
 * stands on every phone the core says hears it (stand), no invitation; every phone writes its own letters to everyone in it
 * over its own pipe to them, and a letter is taken only from the pipe of one of its people -- nobody carries another's word.
 * Messenger groups keep their owner's star.
 */
object Groups {
    /** [P2P-COMPAT] iOS MTGroup.mark: a build that does not know it buries the word unread. */
    const val MARK = "​​GR:"
    // COMPAT-LOCAL: the feed's key and a speaker's reference name rows of this phone; neither rides the wire (iOS keyHead, speakerHead).
    private const val KEY_HEAD = "grp:"
    private const val SPEAKER_HEAD = "gm:"
    private const val OWNER_SEAT = "0"
    private const val GROUP = "g"
    private const val CHANNEL = "c"
    private const val STORE = "groups.held"
    private const val TITLE_LIMIT = 64
    private const val ABOUT_LIMIT = 255
    private const val NAME_LIMIT = 64
    private const val FACE_LIMIT = 24_000
    /** [I-14]: a group is bounded by the people its owner carries to (iOS peopleLimit). */
    private const val PEOPLE_LIMIT = 200
    private const val PIECE_CHARS = 4500   // iOS ChatStore.sendPieceChars
    private const val HEARD_LIMIT = 512
    /** The most of one's own letters a listener reached later is caught up with, per chat (Sh.2.1, iOS catchUpLimit). */
    private const val CATCH_UP_LIMIT = 200
    private val CROWNS = setOf(0x1F451, 0x2654, 0x2655, 0x265A, 0x265B)   // iOS MTCrown: only the Montana room wears a crown

    enum class Verdict { HELD, REFUSED, WAIT }

    class Member(val seat: String, val pipe: String)

    /** A group as this phone holds it (iOS MTGroupState): `owner` — the pipe to the owner, empty on the owner's phone; `me` — this phone's seat. */
    class State(
        val id: String, val kind: String, var title: String, var about: String, val owner: String, var me: String,
        val members: MutableList<Member>, var count: Int, val names: MutableMap<String, String>, val at: Long,
        var left: Boolean = false, var faceTag: String? = null,
        /**
         * Montana Business (Sh.2, iOS MTGroupState.org): the organization whose chat this group is. Such a group has no carrier:
         * it stands on every phone the core says hears it, every phone writes its own letters to everyone in it, and a letter is
         * taken only from the pipe of one of its people. Null -- a Messenger group, carried by its owner.
         */
        val org: String? = null,
        /** An organization's channel: the seats whose letters the channel speaks -- its author and the administrators (chat.rs). */
        val voices: List<String>? = null,
        /** The seats that may take another's letter away -- the administrators (chat.rs Delete). */
        val bosses: List<String>? = null,
        /** Every seat the core names in an organization's chat, reached or not (Sh.3, iOS everyone). */
        val everyone: List<String>? = null,
    ) {
        val mine: Boolean get() = owner.isEmpty()
        val mesh: Boolean get() = org != null
        fun json(): JSONObject = JSONObject().put("id", id).put("k", kind).put("ti", title).put("ds", about).put("ow", owner).put("me", me)
            .put("mb", JSONArray().apply { members.forEach { put(JSONObject().put("s", it.seat).put("p", it.pipe)) } })
            .put("c", count).put("nm", JSONObject(names.toMap())).put("at", at).put("lf", left).put("ft", faceTag ?: "")
            .apply { org?.let { put("org", it) }; voices?.let { put("vo", JSONArray(it)) }; bosses?.let { put("bo", JSONArray(it)) }
                everyone?.let { put("ev", JSONArray(it)) } }
        companion object {
            private fun seats(a: JSONArray?): List<String>? = a?.let { s -> List(s.length()) { s.getString(it) } }
            fun of(o: JSONObject) = State(o.getString("id"), o.getString("k"), o.optString("ti"), o.optString("ds"), o.optString("ow"),
                o.getString("me"),
                o.optJSONArray("mb")?.let { a -> MutableList(a.length()) { i -> a.getJSONObject(i).let { Member(it.getString("s"), it.getString("p")) } } } ?: mutableListOf(),
                o.optInt("c", 2),
                o.optJSONObject("nm")?.let { n -> n.keys().asSequence().associateWith { n.getString(it) }.toMutableMap() } ?: mutableMapOf(),
                o.optLong("at"), o.optBoolean("lf"), o.optString("ft").ifEmpty { null },
                o.optString("org").ifEmpty { null }, seats(o.optJSONArray("vo")), seats(o.optJSONArray("bo")), seats(o.optJSONArray("ev")))
        }
    }

    private val lock = Any()
    private var loaded = false
    /**
     * A STORE THAT WOULD NOT OPEN IS NOT AN EMPTY ONE (iOS MTGroup.unread): it is read again before it is used, nothing is written
     * over it, and a word that needs it waits unanswered — one group written over an unread store would write every other away.
     */
    private var unread = false
    private val groups = LinkedHashMap<String, State>()
    /** Letters taken away before they came (iOS deletedMids of a group's feed): their late copy lands nowhere. */
    private val gone = mutableListOf<String>()
    /** The names of the newest answers already applied: a repeat of a copy is applied once (iOS answersHeard). */
    private val heard = mutableListOf<String>()
    /**
     * A COPY OF A GROUP'S WORD ON ITS WAY TO ONE RECEIVER (iOS MTGroupCopy), by the copy's own name: whose row its receipt
     * moves -- the letter of mine it carries (empty for an invitation) -- and how many copies the letter left in.
     */
    private class Copy(val group: String, val letter: String, val own: Boolean, var of: Int, var held: Boolean, val at: Long,
                       /** The post a comment lives under (Sh.4, iOS MTGroupCopy.thread): its row stands in the post's own feed. */
                       val thread: String? = null) {
        fun json(): JSONObject = JSONObject().put("g", group).put("l", letter).put("o", own).put("n", of).put("h", held).put("at", at)
            .apply { thread?.let { put("th", it) } }
        companion object {
            fun of(o: JSONObject) = Copy(o.optString("g"), o.optString("l"), o.optBoolean("o"), o.optInt("n", 1), o.optBoolean("h"), o.optLong("at"),
                o.optString("th").ifEmpty { null })
        }
    }
    private val copies = LinkedHashMap<String, Copy>()
    /** A copy outlives the queue's own term by a day and is let go with it (iOS copyLife). */
    private const val COPY_LIFE = 8L * 24 * 3600 * 1000

    private fun ready(): Boolean {
        if (loaded && !unread) return true
        loaded = true
        groups.clear(); gone.clear(); copies.clear()
        val raw = DeviceVault.get(STORE)
        unread = raw == null && DeviceVault.has(STORE)
        if (raw != null) runCatching {
            val o = JSONObject(String(raw, Charsets.UTF_8))
            o.optJSONObject("g")?.let { g -> g.keys().forEach { k -> groups[k] = State.of(g.getJSONObject(k)) } }
            o.optJSONArray("gone")?.let { a -> for (i in 0 until a.length()) gone.add(a.getString(i)) }
            val edge = System.currentTimeMillis() - COPY_LIFE
            o.optJSONObject("copies")?.let { kept -> kept.keys().forEach { k -> Copy.of(kept.getJSONObject(k)).takeIf { edge < it.at }?.let { copies[k] = it } } }
        }.onFailure { groups.clear(); gone.clear(); copies.clear(); unread = true }
        if (unread) Log.i("Montana", "group_unread the groups' store did not open -- read again before use")
        return !unread
    }
    private fun save() {
        if (unread) return
        val g = JSONObject(); groups.forEach { (k, v) -> g.put(k, v.json()) }
        val kept = JSONObject(); copies.forEach { (k, v) -> kept.put(k, v.json()) }
        DeviceVault.set(STORE, JSONObject().put("g", g).put("gone", JSONArray(gone)).put("copies", kept).toString().toByteArray(Charsets.UTF_8))
    }
    /** The person leaves: their groups leave with them (iOS montanaSeedForgotten). */
    fun wipe() = synchronized(lock) { groups.clear(); gone.clear(); heard.clear(); copies.clear(); loaded = false; unread = false; DeviceVault.delete(STORE) }

    // ── names ──

    fun isKey(chat: String) = chat.startsWith(KEY_HEAD)
    fun key(id: String) = KEY_HEAD + id
    /** The group stands on this phone: born here, or its invitation came (iOS state(key) != nil). */
    fun holds(id: String): Boolean = state(key(id)) != null
    private fun state(key: String): State? = synchronized(lock) { if (isKey(key) && ready()) groups[key.removePrefix(KEY_HEAD).substringBefore('#')] else null }
    /**
     * THE DISCUSSION UNDER A POST (Sh.4, iOS MTGroup.thread): a feed of its own, named by the channel and the post's one letter
     * name -- «grp:ID#POST»; null for a group's own feed.
     */
    fun thread(key: String): String? = if (isKey(key) && '#' in key) key.substringAfter('#').takeIf { isLetterName(it) } else null
    fun threadKey(key: String, post: String) = key + "#" + post
    /** The feed a word of the group lands in: the group's own, or the discussion of the post it names (iOS feed). */
    private fun feed(id: String, th: String?) = th?.let { threadKey(key(id), it) } ?: key(id)
    /** Whether this feed is a discussion this phone may answer in (iOS comments): every listener of an organization's channel. */
    fun comments(key: String): Boolean { val g = state(key) ?: return false; return g.mesh && g.kind == CHANNEL && !g.left }
    /** The people in the group, the owner included — the head of the group's chat says this number (iOS people). */
    fun peopleLine(c: Context, key: String): String? = state(key)?.count?.let { c.resources.getQuantityString(R.plurals.gr_members, it, it) }
    /** Out of the group — it left, or the owner took it out: the chat says so instead of a field (iOS isOut). */
    fun isOut(key: String) = state(key)?.left == true
    /** Everyone writes in a group, the owner alone in a channel -- in an organization's channel its author and the administrators (iOS canWrite). */
    fun canWrite(key: String): Boolean {
        if (thread(key) != null) return comments(key)   // a post's discussion: every listener of the channel
        val g = state(key) ?: return false; return !g.left && writes(g)
    }
    private fun writes(g: State) = g.kind == GROUP || if (g.mesh) g.voices?.contains(g.me) == true else g.mine
    /**
     * Whether a pin from this phone is everyone's (Sh.3, iOS mayPin): in a Messenger group anyone's, in an organization's chat
     * the administrators' and, in a channel, its voices'; a pin by anyone else stays on their own phone.
     */
    fun mayPin(key: String): Boolean {
        val g = state(key) ?: return false
        if (g.left) return false
        if (!g.mesh) return true
        return g.bosses?.contains(g.me) == true || g.voices?.contains(g.me) == true
    }
    /** The seats and names of everyone an organization's chat names, by the organization's names (Sh.3, iOS shownSeats). */
    fun everyone(key: String): List<Pair<String, String>>? = state(key)?.takeIf { it.mesh }?.let { g ->
        g.everyone?.filter { it != g.me }?.map { it to (g.names[it] ?: "") }
    }
    /** The moment the group stood here: its row stands by it until its first letter (iOS stand puts the row at the top). */
    fun bornAt(key: String): Long = state(key)?.at ?: 0L

    private fun speaker(id: String, seat: String) = SPEAKER_HEAD + id + "/" + seat
    /**
     * The name a speaker's rows wear (iOS speakerName): the person as this phone knows them where it holds their pipe, else the
     * name they gave themselves in their last word.
     */
    fun speakerName(c: Context, ref: String?): String {
        val parts = ref?.takeIf { it.startsWith(SPEAKER_HEAD) }?.removePrefix(SPEAKER_HEAD)?.split("/", limit = 2)
        if (parts == null || parts.size != 2) return c.getString(R.string.gr_member)
        val g = state(key(parts[0])) ?: return c.getString(R.string.gr_member)
        val seat = parts[1]
        val pipe = if (!g.mine && seat == OWNER_SEAT) g.owner else g.members.firstOrNull { it.seat == seat }?.pipe
        val book = pipe?.takeIf { it.isNotEmpty() }?.let { Book.chat(SamePair.root(it))?.shown }
        return book?.ifBlank { null } ?: g.names[seat]?.ifEmpty { null } ?: c.getString(R.string.gr_member)
    }
    /** The line above a speaker's bubble (iOS speakerLine): in a group every letter of another says who wrote it; a channel speaks as itself. */
    fun speakerLine(c: Context, m: Msg, chat: String): String? =
        if (m.mine || m.from?.startsWith(SPEAKER_HEAD) != true || state(chat)?.kind != GROUP) null else speakerName(c, m.from)

    // ── birth and the words of this phone (iOS create, carry, spread, send, signal) ──

    /**
     * A GROUP OR A CHANNEL IS BORN ON THIS PHONE, its owner's (iOS MTGroup.create). The people are the pipes this phone holds,
     * each told by an invitation of their own; the row stands in the list at once. Montana Business: an organization's chat
     * names its group in its Open record before the group is born, so the group may be born under that id (32 lowercase hex);
     * otherwise a fresh one is minted. The group's key, or null when nobody can be carried to.
     */
    fun create(kind: String, title: String, pipes: List<String>, given: String? = null): String? = synchronized(lock) {
        val name = clean(title, TITLE_LIMIT)
        val ps = pipes.map { SamePair.root(it) }.filter { Book.secret(it) != null }.distinct().take(PEOPLE_LIMIT)
        if (!ready() || name.isEmpty() || ps.isEmpty() || (kind != GROUP && kind != CHANNEL)) {
            Log.i("Montana", "group_refused birth people=" + ps.size)
            return@synchronized null
        }
        val id = given?.takeIf { isLabel(it) && !groups.containsKey(it) } ?: mintId()
        val taken = mutableSetOf(OWNER_SEAT)
        val members = ps.map { pp -> var seat = mintId().take(8); while (seat in taken) seat = mintId().take(8); taken += seat; Member(seat, pp) }.toMutableList()
        val g = State(id, kind, name, "", "", OWNER_SEAT, members, members.size + 1, mutableMapOf(), System.currentTimeMillis())
        groups[id] = g
        save()
        stand(g, null)
        val me = clean(Prefs.userName, NAME_LIMIT)
        for (m in members) carry(JSONObject().put("t", "inv").put("g", id).put("k", kind).put("ti", name).put("c", g.count).put("me", m.seat)
            .apply { if (me.isNotEmpty()) put("n", me) }, m.pipe, "", false)
        Log.i("Montana", "group_born kind=" + kind + " people=" + g.count)
        key(id)
    }

    // ── an organization's chat (Sh.2, iOS MTGroup.stand, meshGroups, silence) ──

    /**
     * AN ORGANIZATION'S CHAT STANDS ON EVERY PHONE THAT HEARS IT (the author's word 06.10.2026 10:1x MSK: «a staff of a hundred»):
     * no invitation and no carrier. Its people are the core's (chat.people), each by the pipe this phone holds with them; who
     * speaks in a channel and who takes another's letter away is the core's rule (chat.rs). A group of the same id held the old
     * way (carried by its opener) becomes this. The row is laid when the chat first stands here or is renamed -- never again, so
     * a row the person deleted stays deleted until the chat's next letter. The group's key when its row was laid now, else null.
     */
    fun stand(org: String, id: String, kind: String, title: String, me: String, people: List<Member>, count: Int,
              voices: List<String>, bosses: List<String>, everyone: List<String>, names: Map<String, String>): String? = synchronized(lock) {
        if (!ready() || !isLabel(id) || !isSeat(me) || (kind != GROUP && kind != CHANNEL)) return@synchronized null
        val name = clean(title, TITLE_LIMIT)
        if (name.isEmpty()) return@synchronized null
        val held = groups[id]
        // the organization's names: a letter's self-given name does not rename a colleague (said)
        val g = State(id, kind, name, held?.about ?: "", "", me, people.take(PEOPLE_LIMIT).toMutableList(), count,
            names.toMutableMap(), held?.at ?: System.currentTimeMillis(), left = false, faceTag = held?.faceTag,
            org = org, voices = if (kind == CHANNEL) voices else null, bosses = bosses, everyone = everyone)
        if (held != null && held.json().toString() == g.json().toString()) return@synchronized null
        groups[id] = g
        save()
        Log.i("Montana", "group_mesh people=" + g.members.size + "/" + count + " kind=" + kind)
        if (held != null && held.mesh) {   // a pipe opened since: that listener is caught up with this phone's own letters
            val fresh = g.members.filter { m -> held.members.none { it.pipe == m.pipe } }
            if (fresh.isNotEmpty()) catchUp(g, fresh)
        }
        if (held?.mesh == true && held.title == name) return@synchronized null
        stand(g, null)
        key(id)
    }

    /**
     * A LISTENER REACHED LATER HEARS WHAT THIS PHONE SAID (Sh.2.1, iOS catchUp: the newcomer of a department, a phone whose pipe
     * opened after the letter): this phone's own letters of the last eight days -- the copies' own term -- go to them again under
     * the letters' own names, silent; nobody carries another's words, so every speaker catches the newcomer up on their own. A
     * letter the listener holds already is a repeat there and stands as it stood.
     */
    private fun catchUp(g: State, people: List<Member>) {
        val edge = System.currentTimeMillis() - COPY_LIFE
        val mine = (Book.chat(key(g.id))?.msgs?.toList() ?: emptyList())
            .filter { it.mine && isLetterName(it.mid) && edge < it.at && carries(it.text) }.takeLast(CATCH_UP_LIMIT)
        if (mine.isEmpty()) return
        val me = clean(Prefs.userName, NAME_LIMIT)
        for (m in mine) {
            val w = JSONObject().put("t", "say").put("g", g.id).put("k", g.kind).put("ti", g.title).put("id", m.mid).put("s", g.me).put("tx", m.text)
                .apply { if (me.isNotEmpty()) put("n", me); if (!m.qt.isNullOrEmpty()) put("qt", m.qt) }.put("o", 1)
            for (p in people) carry(w, p.pipe, "", false)
        }
        Log.i("Montana", "group_catchup letters=" + mine.size + " people=" + people.size)
    }

    /** The organization's chats this phone holds, by their ids. */
    fun meshGroups(org: String): List<String> = synchronized(lock) { if (!ready()) emptyList() else groups.values.filter { it.org == org }.map { it.id } }

    /** The chat no longer names this person (removed, another department): its rows stay readable, nothing is written in it. */
    fun silence(id: String) = synchronized(lock) {
        if (!ready()) return@synchronized
        val g = groups[id] ?: return@synchronized
        if (!g.mesh || g.left) return@synchronized
        g.left = true
        save()
        Book.edit(key(g.id)) {}   // the open chat trades its field for the note at once
        Log.i("Montana", "group_mesh silenced")
    }

    /** THE OWNER ADDS PEOPLE (iOS add): each new one is invited with a seat of their own, and everyone learns the new count. */
    fun add(key: String, pipes: List<String>) = synchronized(lock) {
        val g = state(key)?.takeIf { it.mine && !it.mesh } ?: return@synchronized   // an organization's chat: its people are the core's
        val held = g.members.map { SamePair.root(it.pipe) }.toSet()
        val taken = (g.members.map { it.seat } + OWNER_SEAT).toMutableSet()
        val fresh = mutableListOf<Member>()
        for (p0 in pipes) {
            val pp = SamePair.root(p0)
            if (Book.secret(pp) == null || pp in held || fresh.any { it.pipe == pp } || PEOPLE_LIMIT <= g.members.size + fresh.size) continue
            var seat = mintId().take(8)
            while (seat in taken) seat = mintId().take(8)
            taken += seat
            fresh += Member(seat, pp)
        }
        if (fresh.isEmpty()) return@synchronized
        g.members += fresh
        g.count = g.members.size + 1
        save()
        tellAll(g)
        Log.i("Montana", "group_added people=" + fresh.size + " count=" + g.count)
    }

    /** THE OWNER TAKES A PERSON OUT (iOS remove): they are told by their own word and stop being carried to; everyone left learns the count. */
    fun remove(key: String, seat: String) = synchronized(lock) {
        val g = state(key)?.takeIf { it.mine && !it.mesh } ?: return@synchronized
        val m = g.members.firstOrNull { it.seat == seat } ?: return@synchronized
        g.members.removeAll { it.seat == seat }
        g.count = g.members.size + 1
        save()
        carry(JSONObject().put("t", "out").put("g", g.id), m.pipe, "", false)
        tellAll(g)
        Log.i("Montana", "group_removed count=" + g.count)
    }

    /** The invitation's word again to every member (iOS tellAll): the title, the words about the group, the count; each keeps their seat. */
    private fun tellAll(g: State) {
        val me = clean(Prefs.userName, NAME_LIMIT)
        for (o in g.members) carry(JSONObject().put("t", "inv").put("g", g.id).put("k", g.kind).put("ti", g.title).put("c", g.count).put("me", o.seat)
            .apply { if (g.about.isNotEmpty()) put("ds", g.about); if (me.isNotEmpty()) put("n", me) }, o.pipe, "", false)
    }

    /** One copy to one receiver, under a name of its own; the row it carries stands in the group's feed under the letter's name. */
    private fun carry(w: JSONObject, pipe: String, letter: String, own: Boolean): String? {
        if (Book.secret(pipe) == null) return null
        val name = Marks.mintMid()
        copies[name] = Copy(w.optString("g"), letter, own, 1, false, System.currentTimeMillis(), w.optString("th").ifEmpty { null })
        save()
        // a word that is no letter and no invitation rides silent, and so does an old letter caught up (iOS carry: o = 1)
        val t = w.optString("t")
        // a comment under a post rides silent too (iOS carry: its people read it when they look)
        Post.send(pipe, name, MARK + w.toString(), silent = (t != "say" && t != "inv") || w.optInt("o") == 1 || w.has("th"))
        return name
    }

    /** A word to everyone this phone carries to in the group: the owner to every member but the speaker, a member to the owner. */
    private fun spread(w: JSONObject, g: State, except: String?, own: Boolean): Int {
        val targets = if (g.mine) g.members.filter { it.seat != except }.map { it.pipe } else listOf(g.owner)
        val names = targets.mapNotNull { carry(w, it, w.optString("id"), own) }
        names.forEach { copies[it]?.of = names.size }
        if (names.isNotEmpty()) save()
        return names.size
    }

    /**
     * A WORD OF MINE INTO A GROUP (Post.send of a group's key; iOS send and signal): a letter -- its row already stands in the
     * group's feed under its one name -- leaves as «say» to every receiver; an answer (a reaction, an edit of my words, a
     * deletion of my letter) as «sig». A channel takes the owner's letters alone. A letter that reached nobody goes red.
     */
    fun send(key: String, mid: String, text: String, qt: String?, qm: String?) {
        val letter = synchronized(lock) {
            val g = state(key) ?: return
            val th = thread(key)   // a comment under a post (Sh.4): every listener of an organization's channel
            if (g.left || (g.kind != GROUP && !g.mine) || (g.mesh && carries(text) && !writes(g) && !(th != null && comments(key)))) return
            val me = clean(Prefs.userName, NAME_LIMIT)
            if (carries(text)) {
                val w = JSONObject().put("t", "say").put("g", g.id).put("k", g.kind).put("ti", g.title).put("id", mid).put("s", g.me).put("tx", text)
                    .apply { if (me.isNotEmpty()) put("n", me); if (!qt.isNullOrEmpty()) put("qt", qt); if (!qm.isNullOrEmpty()) put("qm", qm)
                        if (th != null) put("th", th) }
                if (spread(w, g, null, true) == 0) Book.edit(key) { c -> c.msgs.find { it.mid == mid }?.advance(-1) }
                g.id
            } else {
                if (answers(text)) spread(JSONObject().put("t", "sig").put("g", g.id).put("id", mid).put("s", g.me).put("tx", text)
                    .apply { if (me.isNotEmpty()) put("n", me); if (th != null) put("th", th) }, g, null, false)
                null
            }
        }
        // an organization's chat: the letter's link in chain C (iOS MTBusiness.said)
        if (letter != null) Biz.said(letter, mid, text, comment = thread(key) != null)
    }

    /** The queue took a copy (Post.flush): my row has left this phone. True when the name was a group's copy. */
    fun copySent(name: String): Boolean = synchronized(lock) {
        val c = copies[name] ?: return@synchronized false
        if (!c.held) { c.held = true; save() }
        follow(c)
        true
    }
    /** A copy was receipted (the receiver's «delivered»): my row is delivered when every copy of its letter is. */
    fun copyDelivered(name: String): Boolean = synchronized(lock) {
        val c = copies.remove(name) ?: return@synchronized false
        save()
        follow(c)
        true
    }
    /** MY ROW STANDS WHERE ITS RECEIVERS SAY (iOS follow): delivered when every copy is receipted, sent once one left. */
    private fun follow(c: Copy) {
        if (!c.own || c.letter.isEmpty()) return
        val left = copies.values.count { it.own && it.group == c.group && it.letter == c.letter }
        Book.edit(feed(c.group, c.thread)) { ch -> ch.msgs.find { it.mine && it.mid == c.letter }?.advance(if (left == 0) 2 else 1) }
    }

    private fun mintId() = java.util.UUID.randomUUID().toString().replace("-", "").lowercase()

    // ── arrival ──

    /**
     * A GROUP'S WORD ARRIVED ON A PIPE (iOS MTGroup.handle, Post.place before any row): never a row of the pipe. Held or refused,
     * the copy is receipted by its own name on the pipe it came by; a word that must wait is not, and its copy knocks again.
     */
    fun handle(pipe: String, sid: String, text: String) {
        val w = runCatching { JSONObject(text.removePrefix(MARK)) }.getOrNull()
        val verdict = if (w != null && isLabel(w.optString("g"))) synchronized(lock) { take(w, pipe) }
            else Verdict.REFUSED.also { Log.i("Montana", "group_refused unreadable from=${pipe.take(10)}") }
        if (verdict != Verdict.WAIT) Post.receiptFor(pipe, sid)
    }

    private fun take(w: JSONObject, pipe: String): Verdict {
        if (!ready()) return Verdict.WAIT   // the copy knocks again once the store opens
        return when (val t = w.optString("t")) {
            "inv" -> invited(w, pipe)
            "say" -> said(w, pipe)
            "sig" -> answered(w, pipe)
            "out" -> dropped(w, pipe)
            "bye" -> parted(w, pipe)
            else -> Verdict.REFUSED.also { Log.i("Montana", "group_refused kind=${t.take(8)}") }   // a newer build's word: its road ends here
        }
    }

    /**
     * AN INVITATION (iOS invited): the group stands on this phone, owned by the pipe the word came by. A second invitation of the
     * same owner renews the title, the words, the count and the face; another pipe naming a group held here is refused.
     */
    private fun invited(w: JSONObject, pipe: String): Verdict {
        val owner = SamePair.root(pipe)
        val kind = w.optString("k")
        val title = clean(w.optString("ti"), TITLE_LIMIT)
        val seat = w.optString("me")
        if ((kind != GROUP && kind != CHANNEL) || title.isEmpty() || !isSeat(seat) || seat == OWNER_SEAT) return Verdict.REFUSED
        val count = w.optInt("c", 2).coerceIn(2, PEOPLE_LIMIT + 1)
        val ownerName = clean(w.optString("n"), NAME_LIMIT)
        val fc = w.optString("fc").ifEmpty { null }
        val tag = fc?.let { it.length.toString() + "-" + it.takeLast(16) }
        val id = w.getString("g")
        val had = groups[id]
        if (had != null) {
            if (had.mine || SamePair.root(had.owner) != owner) {
                Log.i("Montana", "group_refused invitation of a group held by another owner")
                return Verdict.REFUSED
            }
            // out of the group, a word of the old seat is one that was on its way; the owner adding this phone again gives a new seat
            if (had.left) { if (seat == had.me) return Verdict.REFUSED; had.left = false }
            had.title = title; had.about = clean(w.optString("ds"), ABOUT_LIMIT); had.count = count; had.me = seat
            if (ownerName.isNotEmpty()) had.names[OWNER_SEAT] = ownerName
            val newFace = tag != null && tag != had.faceTag
            if (newFace) had.faceTag = tag
            save()
            stand(had, if (newFace) face(fc) else null)
            return Verdict.HELD
        }
        val g = State(id, kind, title, clean(w.optString("ds"), ABOUT_LIMIT), owner, seat, mutableListOf(), count,
            if (ownerName.isEmpty()) mutableMapOf() else mutableMapOf(OWNER_SEAT to ownerName), System.currentTimeMillis(), faceTag = tag)
        groups[id] = g
        save()
        stand(g, face(fc))
        Log.i("Montana", "group_rx invitation kind=$kind people=$count from=${pipe.take(10)}")
        return Verdict.HELD
    }

    /** The group's row on the list (iOS stand): the one standing or archived, or a new one; a deleted group's row comes back with its next word. */
    private fun stand(g: State, face: ByteArray?) {
        val key = key(g.id)
        if (face != null) runCatching { Book.face(key).writeBytes(face) }
        Book.open(key, g.title, false)
        Book.edit(key) { it.name = g.title }
    }

    /**
     * A LETTER (iOS heard): it lands in the group's feed under its one name, worn by its speaker's seat. A member takes letters
     * from the owner's pipe alone; carrying a member's letter on to the others is the owner's phone's work.
     */
    private fun said(w: JSONObject, pipe: String): Verdict {
        val g = groups[w.getString("g")] ?: return Verdict.WAIT
        if (g.left) return Verdict.REFUSED   // out of the group: the word's road ends here
        val id = w.optString("id")
        val words = w.optString("tx")
        if (!isLetterName(id) || !carries(words)) return Verdict.REFUSED
        // the owner takes a letter only from a member it carries to -- in a group, never in a channel -- and carries it on;
        // a member takes letters from the owner's pipe alone. An organization's chat takes a letter from the pipe of one of its
        // people, in a channel from its voices (chat.rs Letter), and carries nothing on.
        val seat = if (g.mine) {
            val m = g.members.firstOrNull { SamePair.root(it.pipe) == SamePair.root(pipe) } ?: return Verdict.REFUSED
            // a comment under a post is every listener's (Sh.4, iOS heard); a post stays the channel's voices'
            val comment = g.mesh && g.kind == CHANNEL && isLetterName(w.optString("th"))
            if (g.kind != GROUP && !(g.mesh && (g.voices?.contains(m.seat) == true || comment))) return Verdict.REFUSED
            m.seat
        } else w.optString("s")
        if (!g.mine && (SamePair.root(g.owner) != SamePair.root(pipe) || !isSeat(seat) || seat == g.me)) return Verdict.REFUSED
        val name = clean(w.optString("n"), NAME_LIMIT)
        if (!g.mesh && name.isNotEmpty() && g.names[seat] != name) { g.names[seat] = name; save() }
        if (id in gone) return Verdict.HELD
        val th = w.optString("th").takeIf { g.mesh && isLetterName(it) }   // a comment: the post's own feed (iOS feed)
        val key = feed(g.id, th)
        if (Book.chat(key)?.msgs?.any { it.mid == id } == true) return Verdict.HELD   // a repeat: the row stands
        stand(g, null)
        if (th != null) Book.open(key, Book.ctx.getString(R.string.gr_comments), false)
        val from = speaker(g.id, seat)
        val at = Marks.birthMs(id) ?: System.currentTimeMillis()
        val quote = w.optString("qt").ifEmpty { null }
        val quoted = w.optString("qm").removePrefix("mid:").ifEmpty { null }
        var landed = false
        Book.edit(key) { c ->
            if (c.msgs.none { it.mid == id }) {
                c.msgs.add(Msg(id, words, false, at, qt = quote, qm = quoted, from = from)); c.msgs.sortBy { it.at }
                if (Book.openChat != key) c.unread++
                landed = true
            }
        }
        Log.i("Montana", "group_rx mid=$id letter landed=${if (landed) 1 else 0}")
        // the banner a running app owes its person: the group's title over who wrote and what (a channel speaks as itself)
        if (landed && g.mine && !g.mesh) spread(JSONObject().put("t", "say").put("g", g.id).put("k", g.kind).put("ti", g.title).put("id", id).put("s", seat).put("tx", words)
            .apply { if (name.isNotEmpty()) put("n", name); if (quote != null) put("qt", quote); w.optString("qm").ifEmpty { null }?.let { put("qm", it) } }, g, seat, false)
        // an old letter caught up (o = 1, Sh.2.1) lands in its place without a banner
        if (landed && Book.openChat != key && w.optInt("o") != 1 && th == null) Notify.letter(key, words, if (g.kind == GROUP) speakerName(Book.ctx, from) else null)
        return Verdict.HELD
    }

    /**
     * AN ANSWER (iOS answered): a reaction, an edit of one's own words, a deletion of one's own letter — applied in the group's
     * feed once by its name. Only a letter's own speaker changes it or takes it away; a deletion of a letter not landed yet
     * buries it before it comes. An organization's chat takes an answer from the pipe of one of its people, an administrator's
     * deletion of another's letter too (chat.rs Delete), and waits for a letter not landed here yet.
     */
    private fun answered(w: JSONObject, pipe: String): Verdict {
        val g = groups[w.getString("g")] ?: return Verdict.WAIT
        if (g.left) return Verdict.REFUSED
        val id = w.optString("id")
        val words = w.optString("tx")
        if (!isLetterName(id) || !answers(words)) return Verdict.REFUSED
        // the owner of a Messenger group takes a member's answer from that member's pipe and carries it on (iOS answered);
        // an organization's chat takes it from the pipe of one of its people; a member takes the owner's word
        val seat = (if (g.mine) g.members.firstOrNull { SamePair.root(it.pipe) == SamePair.root(pipe) }?.seat else w.optString("s"))
            ?: return Verdict.REFUSED
        if (!g.mine && (SamePair.root(g.owner) != SamePair.root(pipe) || !isSeat(seat) || seat == g.me)) return Verdict.REFUSED
        if (id in heard) return Verdict.HELD
        // a pin in an organization's chat is everyone's only from its administrators and a channel's voices (Sh.3, iOS mayPin)
        if (g.mesh && words.startsWith(PIN_MARK) && g.bosses?.contains(seat) != true && g.voices?.contains(seat) != true) {
            Log.i("Montana", "group_refused a pin from a seat that does not pin for everyone")
            return Verdict.REFUSED
        }
        val key = feed(g.id, w.optString("th").takeIf { g.mesh && isLetterName(it) })   // an answer in a post's discussion
        target(words)?.let { t ->
            val row = Book.chat(key)?.msgs?.firstOrNull { it.mid == t }
            if (row != null) {
                // an administrator takes away another's letter in an organization's chat (chat.rs Delete); an edit stays the speaker's
                val boss = g.mesh && words.startsWith(Marks.DELETE) && g.bosses?.contains(seat) == true
                if (author(row, g) != seat && !boss) {
                    Log.i("Montana", "group_refused an answer to another's letter seat=$seat")
                    return Verdict.REFUSED
                }
            } else if (t in gone) return Verdict.HELD
            else if (g.mesh) return Verdict.WAIT   // the letter has not landed here yet: the copy knocks again
            else if (words.startsWith(Marks.DELETE)) { bury(t); remember(id); return Verdict.HELD }
        }
        remember(id)
        apply(key, words)
        if (g.mine && !g.mesh) spread(JSONObject().put("t", "sig").put("g", g.id).put("id", id).put("s", seat).put("tx", words)
            .apply { w.optString("n").ifEmpty { null }?.let { put("n", it) } }, g, seat, false)
        return Verdict.HELD
    }

    private fun apply(key: String, words: String) {
        when {
            words.startsWith(Marks.DELETE) -> target(words)?.let { t -> Book.edit(key) { c -> c.msgs.removeAll { it.mid == t } } }
            words.startsWith(PIN_MARK) -> applyPin(key, words.removePrefix(PIN_MARK))   // a pin for everyone in the group (Sh.3)
            words.startsWith(Marks.EDIT) -> {
                val tx = runCatching { JSONObject(words.removePrefix(Marks.EDIT)).getString("tx") }.getOrNull()
                val t = target(words)
                if (tx != null && t != null) Book.edit(key) { c -> c.msgs.find { it.mid == t && !it.mine }?.let { it.text = tx; it.edited = true } }
            }
            words.startsWith(Marks.REACTION) -> runCatching {
                val o = JSONObject(words.removePrefix(Marks.REACTION)); val t = o.getString("sid").removePrefix("mid:"); val e = o.getString("e")
                Book.edit(key) { c -> c.msgs.find { it.mid == t }?.let { m -> if (o.optString("op") == "del") m.reactions.remove(e) else m.reactions.add(e) } }
            }
        }
    }

    /** A MEMBER LEFT (their «bye», iOS parted): the owner carries nothing more to them, and everyone left learns the new count. */
    private fun parted(w: JSONObject, pipe: String): Verdict {
        val g = groups[w.getString("g")] ?: return Verdict.REFUSED
        val m = (if (g.mine) g.members.firstOrNull { SamePair.root(it.pipe) == SamePair.root(pipe) } else null) ?: return Verdict.REFUSED
        g.members.removeAll { it.seat == m.seat }
        g.count = g.members.size + 1
        save()
        tellAll(g)
        Log.i("Montana", "group_rx a member left count=" + g.count)
        return Verdict.HELD
    }

    /** THE OWNER TOOK THIS PHONE OUT (iOS dropped): the rows stay readable, and the chat says so instead of a field. */
    private fun dropped(w: JSONObject, pipe: String): Verdict {
        val g = groups[w.getString("g")] ?: return Verdict.REFUSED
        if (g.mine || SamePair.root(g.owner) != SamePair.root(pipe)) return Verdict.REFUSED
        if (!g.left) {
            g.left = true
            save()
            Book.edit(key(g.id)) {}   // the open chat trades its field for the note at once
            Log.i("Montana", "group_rx taken out by the owner")
        }
        return Verdict.HELD
    }

    private fun bury(mid: String) {
        gone.add(mid)
        if (gone.size > HEARD_LIMIT) gone.subList(0, gone.size - HEARD_LIMIT).clear()
        save()
    }
    private fun remember(id: String) {
        heard.add(id)
        if (heard.size > HEARD_LIMIT) heard.subList(0, heard.size - HEARD_LIMIT).clear()
    }

    // ── measures ──

    /** The seat whose letter a row is: mine is this phone's seat, another's is named by its speaker's reference (iOS author). */
    private fun author(row: Msg, g: State): String? =
        if (row.mine) g.me else row.from?.takeIf { it.startsWith(speaker(g.id, "")) }?.removePrefix(speaker(g.id, ""))

    /** WHAT AN ANSWER MAY BE IN A GROUP (iOS answers): an emoji put on or taken off, a letter's new words, a letter taken away. */
    private fun answers(text: String): Boolean = when {
        text.startsWith(PIN_MARK) -> text.length <= 4096 &&
            runCatching { JSONObject(text.removePrefix(PIN_MARK)).optString("op") }.getOrNull().let { it == "pin" || it == "unpin" }
        text.startsWith(Marks.REACTION) -> text.length <= 2048 &&
            runCatching { JSONObject(text.removePrefix(Marks.REACTION)).optString("op") }.getOrNull().let { it == "add" || it == "del" }
        text.startsWith(Marks.EDIT) -> target(text) != null &&
            runCatching { JSONObject(text.removePrefix(Marks.EDIT)).getString("tx") }.getOrNull()?.let { carries(it) } == true
        else -> text.startsWith(Marks.DELETE) && target(text) != null
    }
    /** The bare name of the letter an edit or a deletion speaks of; null for a reaction (iOS target). */
    private fun target(text: String): String? {
        val sid = when {
            text.startsWith(Marks.EDIT) -> runCatching { JSONObject(text.removePrefix(Marks.EDIT)).optString("sid") }.getOrDefault("")
            text.startsWith(Marks.DELETE) -> text.removePrefix(Marks.DELETE)
            else -> ""
        }
        return sid.removePrefix("mid:").takeIf { isLetterName(it) }
    }
    /**
     * WHAT A GROUP CARRIES (iOS carries): a person's words and a sticker, never a service word. iOS also refuses a coin's, a game's
     * and a phone's own letter at the sender's door; this build reads none of them.
     */
    private fun carries(text: String): Boolean =
        text.isNotEmpty() && graphemes(text) <= PIECE_CHARS && (!Marks.isService(text) || text.startsWith(Marks.STICKER))

    private fun isLabel(s: String) = s.length == 32 && s.all { it in '0'..'9' || it in 'a'..'f' }
    private fun isSeat(s: String) = s.isNotEmpty() && s.length <= 16 && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    private fun isLetterName(s: String) = s.isNotEmpty() && s.length <= 80 && !s.startsWith("mid:") &&
        s.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }

    /** A name or a title as the group shows it (iOS clean): trimmed, without a crown, at most `limit` characters as a person counts them. */
    private fun clean(s: String, limit: Int): String {
        val plain = StringBuilder()
        var afterMark = false
        val t = s.trim()
        var i = 0
        while (i < t.length) {
            val point = t.codePointAt(i); i += Character.charCount(point)
            if (point in CROWNS) { afterMark = true; continue }
            if (afterMark && (point == 0xFE0F || point == 0xFE0E)) continue
            afterMark = false
            plain.appendCodePoint(point)
        }
        val p = plain.toString()
        if (p.length <= limit) return p
        val cut = BreakIterator.getCharacterInstance().apply { setText(p) }
        var end = 0
        repeat(limit) { val next = cut.next(); if (next == BreakIterator.DONE) return p.substring(0, end); end = next }
        return p.substring(0, end)
    }
    private fun graphemes(s: String): Int {
        if (s.length <= PIECE_CHARS) return s.length
        val cut = BreakIterator.getCharacterInstance().apply { setText(s) }
        var n = 0
        while (cut.next() != BreakIterator.DONE) n++
        return n
    }
    /** The face an invitation carries (iOS face): small, a picture, laid beside the group's row. */
    private fun face(b64: String?): ByteArray? {
        if (b64 == null || b64.length > FACE_LIMIT) return null
        val d = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() ?: return null
        return d.takeIf { BitmapFactory.decodeByteArray(it, 0, it.size) != null }
    }
}
