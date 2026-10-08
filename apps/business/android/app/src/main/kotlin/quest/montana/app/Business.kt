package quest.montana.app

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// ─────────────────────────── the organization as the core sees it (the contract's view JSON) ───────────────────────────

/** A member of the view (members[]): `id` is the member id in hex, stable through a change of key. */
class BizMember(val id: String, val name: String, val role: String, val dept: String, val title: String, val card: String,
                val status: String, val joinedMs: Long, val phoneConfirmed: Boolean, val phoneMismatch: Boolean,
                /** The number of the member's confirmed PhoneBind, where the viewer may see it (the member and the administrators). */
                val phone: String?)
class BizDept(val id: String, val name: String)
class BizInvite(val id: String, val role: String, val dept: String, val expiresMs: Long, val state: String)
class BizSalary(val member: String, val coins: Long, val period: Int, val fromMs: Long)
class BizPayment(val record: String, val member: String, val coins: Long, val kind: Int, val periodKey: Long,
                 val coinRef: String, val state: String,
                 /** The Pay record's moment (the core's pay[].at_ms); a core without it leaves it null. */
                 val atMs: Long?,
                 /** A salary pay's period, [fromMs, toMs) on UTC boundaries (contract 1.4); null for a bonus, which has none. */
                 val fromMs: Long?, val toMs: Long?)
/**
 * A salary due; its coin letter has no name until its Pay record is written (coin_ref null). Its period's own moments [fromMs,
 * toMs) on UTC boundaries (contract 1.3): a screen names the period by them, never by a calendar of its own; null when the core
 * names none.
 */
class BizDue(val member: String, val coins: Long, val periodKey: Long, val coinRef: String?, val fromMs: Long?, val toMs: Long?)
/** A person's time (K.5, time[]): closed shifts begun in the current UTC week and month, in seconds, the confirmed apart. */
class BizTime(val member: String, val weekS: Long, val weekConfirmedS: Long, val monthS: Long, val monthConfirmedS: Long)
/** A record the fold refused (rejected[]): the record and the core's reason. */
class BizRejected(val record: String, val reason: String)
class BizOffer(val id: String, val title: String, val price: Long, val stock: Long, val active: Boolean)
class BizRedeem(val record: String, val member: String, val offer: String, val qty: Long, val coins: Long, val state: String,
                /** The purchase's moment (redeems[].at_ms). */
                val atMs: Long)
// THE SUPPLY CHAIN AND THE CHATS (contract v1.1, iOS MTBizView): chain S, an order's lane; chain C, a chat's lane. A core
// without them writes none of these fields, and the view still opens: each list is empty then.
class BizLine(val item: String, val size: String, val qty: Long, val coins: Long)
class BizItem(val id: String, val title: String, val sizes: String, val unit: String, val active: Boolean) {
    /** The sizes as the administrator wrote them, one by one: «40, 41, 42». */
    val sizeList: List<String> get() = sizes.split(',', ';', ' ').filter { it.isNotEmpty() }
}
class BizNode(val id: String, val kind: String, val name: String, val place: String, val active: Boolean, val mine: Boolean, val staff: List<String>)
class BizPlace(val id: String, val lines: List<BizLine>, val packedAt: String, val holder: String?, val transitFrom: String?,
               val transitTo: String?, val condition: Long, val note: String, val photo: String?)
class BizStep(val record: String, val kind: String, val atMs: Long, val author: String, val node: String?, val place: String?,
              val detail: Long, val lines: List<BizLine>, val note: String)
/**
 * A STAND (K.5, the economy of time, core 3da0ada): time an order spent at one node -- at the supplier from the order to its first
 * packing, a place at a node from its packing or acceptance there to its handoff away; the road and the arrival are not stands.
 * [sinceMs] is null for the longest stand, which names no moment.
 */
class BizStand(val node: String, val sinceMs: Long?, val ms: Long)
class BizOrder(val id: String, val author: String, val atMs: Long, val from: String, val to: String, val source: String, val voice: String?,
               val text: String, val state: String, val lines: List<BizLine>, val places: List<BizPlace>, val steps: List<BizStep>,
               val people: List<String>,
               /** How long the order has been on its way: to its delivery or cancellation, else to now (way_ms). */
               val wayMs: Long,
               /** The longest it stood at one node (longest_stand, always written). */
               val longestStand: BizStand,
               /** The stand going on now, or null (standing). */
               val standing: BizStand?,
               /**
                * What of the order's lines no place holds yet, by product and size, in the order's own order (unpacked, contract 1.2):
                * the storekeeper's next box. Its lines carry no coins (0).
                */
               val unpacked: List<BizLine>) {
    /** Every node the order has named or passed, as its route, its places and its steps say (iOS path). */
    val path: List<String> get() {
        val named = mutableListOf(from, to)
        for (p in places) named += listOfNotNull(p.packedAt.ifEmpty { null }, p.holder, p.transitFrom, p.transitTo)
        named += steps.mapNotNull { it.node }
        return named.distinct()
    }
    val open: Boolean get() = state != "cancelled"
}
class BizStock(val node: String, val item: String, val size: String, val onHand: Long, val counter: Long, val sold: Long)
/** One record of the journal (7.4, iOS Entry): what the core shows of the last records the viewer may see. */
class BizEntry(val record: String, val chain: String, val kind: String, val atMs: Long, val author: String, val lane: String, val outcome: String)
/** A shift (9.1, iOS Shift): opened and closed by its member, confirmed by somebody else; its lane is the member's own lane of shifts. */
class BizShift(val member: String, val lane: String, val node: String?, val openRecord: String, val openMs: Long, val closeRecord: String?,
               val closeMs: Long?, val seconds: Long?, val note: String, val confirmedBy: String?, val people: List<String>) {
    val running: Boolean get() = closeRecord == null
}
/** A chat of the organization (contract 1.2): its letters ride the Messenger's group, the view carries none of them. */
class BizChat(val id: String, val kind: String, val name: String, val dept: String?, val group: String?, val author: String,
              val openedMs: Long, val people: List<String>)

/**
 * THE VIEW (mt_biz_view): everything a screen shows of an organization, as the core folded it for this viewer at this moment.
 * Read as it comes; nothing in it is computed here. Names of the fields are the contract's.
 */
class BizView(o: JSONObject) {
    val org: String; val name: String; val createdMs: Long
    val me: String; val role: String; val can: Set<String>
    val members: List<BizMember>
    val depts: List<BizDept>
    val invites: List<BizInvite>
    val salary: List<BizSalary>
    val pay: List<BizPayment>
    val due: List<BizDue>
    val offers: List<BizOffer>
    val redeems: List<BizRedeem>
    val rejected: List<BizRejected>
    val waiting: Int
    val head: String
    val items: List<BizItem>
    val nodes: List<BizNode>
    val orders: List<BizOrder>
    val stock: List<BizStock>
    val chats: List<BizChat>
    /** The journal (7.4); null when the core writes none (an older core). */
    val journal: List<BizEntry>?
    val shifts: List<BizShift>
    /** The time of each person whose shifts this viewer sees (K.5). */
    val time: List<BizTime>

    // ── VIEW-CONTRACT BEGIN (C-2.1): the view read key by key. Every key is read here by one reader of the companion below,
    //    by its literal name, each object of an array named by its lambda; no other read of the view's JSON stands here.
    //    scripts/biz_view_contract.py holds this block to the core's own sample (scripts/contracts/mt-biz-view.CORESHA.json)
    //    both ways, null only where the reader takes null. ──
    init {
        val g = o.objectOrEmpty("org")
        org = g.str("id"); name = g.str("name"); createdMs = g.long("created_ms")
        val m = o.objectOf("me")
        me = m.str("member"); role = m.str("role").ifEmpty { "none" }
        can = m.strings("can").toSet()
        members = o.objects("members") { mb -> BizMember(mb.str("member"), mb.str("name"), mb.str("role"), mb.tagOrNone("dept"),
            mb.str("title"), mb.str("card"), mb.str("status"), mb.long("joined_ms"), mb.bool("phone_confirmed"),
            mb.bool("phone_mismatch"), mb.strOrNull("phone")) }
        depts = o.objects("depts") { dp -> BizDept(dp.str("id"), dp.str("name")) }
        invites = o.objects("invites") { iv -> BizInvite(iv.str("id"), iv.str("role"), iv.tagOrNone("dept"), iv.long("expires_ms"), iv.str("state")) }
        salary = o.objects("salary") { sa -> BizSalary(sa.str("member"), sa.long("coins"), sa.int("period"), sa.long("from_ms")) }
        pay = o.objects("pay") { pa -> BizPayment(pa.str("record"), pa.str("member"), pa.long("coins"), pa.int("kind"),
            pa.long("period_key"), pa.str("coin_ref"), pa.str("state"), pa.longOrNull("at_ms"), pa.longOrNull("from_ms"), pa.longOrNull("to_ms")) }
        due = o.objects("due") { du -> BizDue(du.str("member"), du.long("coins"), du.long("period_key"), du.strOrNull("coin_ref"),
            du.longOrNull("from_ms"), du.longOrNull("to_ms")) }
        offers = o.objects("offers") { of -> BizOffer(of.str("id"), of.str("title"), of.long("price"), of.long("stock"), of.bool("active")) }
        redeems = o.objects("redeems") { rd -> BizRedeem(rd.str("record"), rd.str("member"), rd.str("offer"), rd.long("qty"),
            rd.long("coins"), rd.str("state"), rd.long("at_ms")) }
        rejected = o.objects("rejected") { rj -> BizRejected(rj.str("record"), rj.str("reason")) }
        waiting = o.int("waiting")
        head = o.str("head")
        items = o.objects("items") { im -> BizItem(im.str("id"), im.str("title"), im.str("sizes"), im.str("unit"), im.bool("active")) }
        nodes = o.objects("nodes") { nd -> BizNode(nd.str("id"), nd.str("kind"), nd.str("name"), nd.str("place"), nd.bool("active"),
            nd.bool("mine"), nd.strings("staff")) }
        orders = o.objects("orders") { od ->
            val ls = od.objectOf("longest_stand")
            val sd = od.objectOrNull("standing")
            BizOrder(od.str("id"), od.str("author"), od.long("at_ms"), od.str("from"), od.str("to"),
            od.str("source"), od.strOrNull("voice"), od.str("text"), od.str("state"), od.objects("lines", ::line),
            od.objects("places") { pl -> BizPlace(pl.str("id"), pl.objects("lines", ::line), pl.str("packed_at"), pl.strOrNull("holder"),
                pl.strOrNull("transit_from"), pl.strOrNull("transit_to"), pl.long("condition"), pl.str("note"), pl.strOrNull("photo")) },
            od.objects("steps") { st -> BizStep(st.str("record"), st.str("kind"), st.long("at_ms"), st.str("author"), st.strOrNull("node"),
                st.strOrNull("place"), st.long("detail"), st.objects("lines", ::line), st.str("note")) },
            od.strings("people"), od.long("way_ms"), BizStand(ls.str("node"), null, ls.long("ms")),
            if (sd == null) null else BizStand(sd.str("node"), sd.long("since_ms"), sd.long("ms")),
            od.objects("unpacked") { up -> BizLine(up.str("item"), up.str("size"), up.long("qty"), 0L) }) }
        time = o.objects("time") { tm -> BizTime(tm.str("member"), tm.long("week_s"), tm.long("week_confirmed_s"), tm.long("month_s"),
            tm.long("month_confirmed_s")) }
        stock = o.objects("stock") { sk -> BizStock(sk.str("node"), sk.str("item"), sk.str("size"), sk.long("on_hand"),
            sk.long("counter"), sk.long("sold")) }
        chats = o.objects("chats") { ch -> BizChat(ch.str("id"), ch.str("kind"), ch.str("name"), ch.strOrNull("dept"), ch.strOrNull("group"),
            ch.str("author"), ch.long("opened_ms"), ch.strings("people")) }
        journal = o.objectsOrNull("journal") { jn -> BizEntry(jn.str("record"), jn.str("chain"), jn.str("kind"),
            jn.long("at_ms"), jn.str("author"), jn.str("lane"), jn.str("outcome")) }
        shifts = o.objects("shifts") { sh -> BizShift(sh.str("member"), sh.str("lane"), sh.strOrNull("node"), sh.str("open_record"), sh.long("open_ms"),
            sh.strOrNull("close_record"), sh.longOrNull("close_ms"), sh.longOrNull("seconds"),
            sh.str("note"), sh.strOrNull("confirmed_by"), sh.strings("people")) }
    }
    /** One line of goods: of an order, of a place, of a step. */
    private fun line(ln: JSONObject) = BizLine(ln.str("item"), ln.str("size"), ln.long("qty"), ln.long("coins"))
    // ── VIEW-CONTRACT END ──

    fun item(id: String) = items.firstOrNull { it.id == id }
    fun node(id: String?) = id?.let { n -> nodes.firstOrNull { it.id == n } }
    fun order(id: String) = orders.firstOrNull { it.id == id }
    fun timeOf(member: String) = time.firstOrNull { it.member == member }
    fun nodeName(id: String?) = node(id)?.name ?: ""
    fun lineText(l: BizLine) = listOf(item(l.item)?.title ?: "", l.size, "×" + l.qty).filter { it.isNotEmpty() }.joinToString(" · ")
    fun linesText(ls: List<BizLine>) = ls.joinToString(", ") { lineText(it) }
    /** A node this viewer acts on: every node for an administrator, the nodes they staff for anyone else (the core's keeps). */
    fun keeps(node: String?): Boolean = node != null && (Biz.boss(role) || node(node)?.mine == true)
    val keptNodes: List<BizNode> get() = nodes.filter { it.active && keeps(it.id) }

    fun member(id: String) = members.firstOrNull { it.id == id }
    fun dept(id: String) = depts.firstOrNull { it.id == id }
    fun may(right: String) = right in can
    val active: List<BizMember> get() = members.filter { it.status != "removed" }
    val isAdmin: Boolean get() = role == "owner" || role == "admin"

    // ── NO DEAD BUTTONS (point 0): the core's own rules for this viewer -- mt-business fold.rs rule, chat.rs apply -- so a
    //    screen shows an act only where the core takes the record. Read from the view; the core still decides. ──
    private fun rank(word: String) = BizRole.of(word)?.number ?: Int.MAX_VALUE
    /** The viewer's own department while it stands (an archived one is refused by dept_usable), "" for none. */
    val myDept: String get() = member(me)?.dept?.takeIf { it.isNotEmpty() && dept(it) != null } ?: ""
    /** fold.rs Invite: the owner gives any role below the owner's, an administrator a manager's or an employee's, a manager an employee's in their own department. */
    val invitableRoles: List<BizRole> get() = if (!may("invite")) emptyList() else when (role) {
        "owner" -> listOf(BizRole.ADMIN, BizRole.MANAGER, BizRole.EMPLOYEE)
        "admin" -> listOf(BizRole.MANAGER, BizRole.EMPLOYEE)
        "manager" -> if (myDept.isNotEmpty()) listOf(BizRole.EMPLOYEE) else emptyList()
        else -> emptyList()
    }
    /** fold.rs Role: never one's own; the owner sets any other active member, an administrator a manager or an employee to either. */
    fun rolesFor(m: BizMember): List<BizRole> = if (!may("role") || m.status == "removed" || m.id == me) emptyList() else when (role) {
        "owner" -> listOf(BizRole.ADMIN, BizRole.MANAGER, BizRole.EMPLOYEE)
        "admin" -> if (rank(m.role) >= BizRole.MANAGER.number) listOf(BizRole.MANAGER, BizRole.EMPLOYEE) else emptyList()
        else -> emptyList()
    }
    /** fold.rs Assign: the owner anyone, an administrator themselves or those below, a manager the employees of their own department. */
    fun mayAssign(m: BizMember): Boolean = may("assign") && m.status != "removed" && when (role) {
        "owner" -> true
        "admin" -> m.id == me || rank(m.role) >= BizRole.MANAGER.number
        "manager" -> myDept.isNotEmpty() && m.role == "employee" && m.dept == myDept
        else -> false
    }
    /** fold.rs Remove: the owner anyone but themselves, an administrator a manager or an employee. */
    fun mayRemove(m: BizMember): Boolean = may("remove") && m.status != "removed" && when (role) {
        "owner" -> m.id != me
        "admin" -> rank(m.role) >= BizRole.MANAGER.number
        else -> false
    }
    /** fold.rs Rekey: the owner any active place, an administrator their own and those below. */
    fun mayRekey(id: String): Boolean = may("rekey") && member(id)?.takeIf { it.status != "removed" }?.let { m ->
        role == "owner" || (role == "admin" && (m.id == me || rank(m.role) >= BizRole.MANAGER.number))
    } == true
    /** fold.rs Salary: the administrators, for an active member. */
    fun maySalary(m: BizMember): Boolean = may("salary") && m.status != "removed"
    /** chat.rs Open: a channel is the administrators'; a department's chat theirs for any department, a manager's for their own. */
    val mayChannel: Boolean get() = may("open") && isAdmin
    val chatDepts: List<BizDept> get() = when {
        !may("open") -> emptyList()
        isAdmin -> depts
        role == "manager" -> listOfNotNull(dept(myDept))
        else -> emptyList()
    }

    private companion object {
        // THE READERS OF THE VIEW (C-2.1): one per kind of value, so the contract guard knows what each key is read as.
        /** A string the core always writes. */
        fun JSONObject.str(key: String): String = optString(key)
        /** A string the core may write as null: null here, never the word «null». */
        fun JSONObject.strOrNull(key: String): String? = if (!has(key) || isNull(key)) null else optString(key)
        /** A tag the core writes as null for none (a department): "" here. */
        fun JSONObject.tagOrNone(key: String): String = if (!has(key) || isNull(key)) "" else optString(key)
        fun JSONObject.long(key: String): Long = optLong(key)
        fun JSONObject.longOrNull(key: String): Long? = if (!has(key) || isNull(key)) null else optLong(key)
        fun JSONObject.int(key: String): Int = optInt(key)
        fun JSONObject.bool(key: String): Boolean = optBoolean(key)
        fun JSONObject.strings(key: String): List<String> = optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        /** An object the core always writes. */
        fun JSONObject.objectOf(key: String): JSONObject = optJSONObject(key) ?: JSONObject()
        /** An object the core may write as null (the organization of a viewer who holds none): empty here. */
        fun JSONObject.objectOrEmpty(key: String): JSONObject = optJSONObject(key) ?: JSONObject()
        /** An object the core may write as null, kept as null (an order's standing). */
        fun JSONObject.objectOrNull(key: String): JSONObject? = optJSONObject(key)
        fun <T> JSONObject.objects(key: String, f: (JSONObject) -> T): List<T> =
            optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(f) } } ?: emptyList()
        /** An array a core of an older contract does not write at all: null here. */
        fun <T> JSONObject.objectsOrNull(key: String, f: (JSONObject) -> T): List<T>? = if (!has(key)) null else objects(key, f)
    }
}

/** The roles by the contract's numbers: 0 owner, 1 administrator, 2 manager, 3 employee. */
enum class BizRole(val number: Int, val word: String, val title: Int) {
    OWNER(0, "owner", R.string.biz_role_owner), ADMIN(1, "admin", R.string.biz_role_admin),
    MANAGER(2, "manager", R.string.biz_role_manager), EMPLOYEE(3, "employee", R.string.biz_role_employee);
    companion object { fun of(word: String) = values().firstOrNull { it.word == word } }
}

/**
 * THE COMMANDS OF THE CONTRACT (mt_biz_author's JSON): one builder per kind, the fields by the contract's names, bytes in hex.
 * The one place the app spells a command; the core checks every field and every right.
 */
object BizCommand {
    private fun kind(k: String) = JSONObject().put("kind", k)
    fun genesis(name: String, ownerName: String, card: String) = kind("genesis").put("name", name).put("owner_name", ownerName).put("owner_card", card)
    fun invite(secret: String, role: Int, dept: String, expiresMs: Long, phoneBound: Boolean) =
        kind("invite").put("secret", secret).put("role", role).put("dept", dept).put("expires_ms", expiresMs).put("phone_bound", phoneBound)
    fun invitePhone(inviteId: String, e164: String) = kind("invite_phone").put("invite_id", inviteId).put("e164", e164)
    fun join(secret: String, name: String, card: String) = kind("join").put("secret", secret).put("name", name).put("card", card)
    fun role(member: String, role: Int) = kind("role").put("member", member).put("role", role)
    fun dept(id: String, name: String, archived: Boolean) = kind("dept").put("dept", id).put("name", name).put("archived", archived)
    fun assign(member: String, dept: String, title: String) = kind("assign").put("member", member).put("dept", dept).put("title", title)
    fun remove(member: String) = kind("remove").put("member", member)
    /** A member's place moved to a new key (a new phone): the key in hex, 1952 bytes. */
    fun rekey(member: String, key: String) = kind("rekey").put("member", member).put("key", key)
    fun shiftOpen() = kind("shift_open")
    fun shiftClose(note: String) = kind("shift_close").put("note", note)
    fun shiftConfirm(close: String) = kind("shift_confirm").put("record", close)
    fun revoke(inviteId: String) = kind("revoke").put("invite_id", inviteId)
    /** An invitation named by its secret (mt-business 471fb2a): the app that minted it holds the secret, never the label. */
    fun revokeSecret(secret: String) = kind("revoke").put("secret", secret)
    fun offer(item: String, title: String, price: Long, stock: Long, active: Boolean) =
        kind("offer").put("item", item).put("title", title).put("price", price).put("stock", stock).put("active", active)
    fun profile(name: String, card: String) = kind("profile").put("name", name).put("card", card)
    fun phoneBind(attest: String) = kind("phone_bind").put("attest", attest)
    fun salary(member: String, coins: Long, period: Int, fromMs: Long) =
        kind("salary").put("member", member).put("coins", coins).put("period", period).put("from_ms", fromMs)
    fun fulfil(record: String) = kind("fulfil").put("record", record)
    // THE SUPPLY CHAIN (contract v1.1, iOS MTBizCommand): the catalogue is the roster's (R), an order and its steps its own lane
    // of S, named by the order's 32-byte key; places, nodes and products are 16-byte tags.
    fun item(id: String, title: String, sizes: String, unit: String, active: Boolean) =
        kind("item").put("item", id).put("title", title).put("sizes", sizes).put("unit", unit).put("active", active)
    fun node(id: String, nodeKind: Int, name: String, place: String, active: Boolean) =
        kind("node").put("node", id).put("node_kind", nodeKind).put("name", name).put("place", place).put("active", active)
    fun nodeStaff(node: String, member: String, on: Boolean) = kind("node_staff").put("node", node).put("member", member).put("on", on)
    fun lines(ls: List<BizLine>) = JSONArray().apply {
        ls.forEach { put(JSONObject().put("item", it.item).put("size", it.size).put("qty", it.qty).put("coins", it.coins)) }
    }
    /** An order: by hand (source 1, the words as written) or by voice (source 2, the voice note's hash and its transcription). */
    fun order(key: String, from: String, to: String, ls: List<BizLine>, voice: String?, text: String) =
        kind("order").put("order", key).put("from", from).put("to", to).put("lines", lines(ls)).put("text", text)
            .put("source", if (voice == null) TEXT_SOURCE else VOICE_SOURCE).apply { if (voice != null) put("voice", voice) }
    fun confirm(order: String, node: String) = kind("confirm").put("order", order).put("node", node)
    fun pack(order: String, place: String, node: String, ls: List<BizLine>) =
        kind("pack").put("order", order).put("place", place).put("node", node).put("lines", lines(ls))
    fun handoff(order: String, place: String, from: String, to: String) =
        kind("handoff").put("order", order).put("place", place).put("from", from).put("to", to)
    fun accept(order: String, place: String, node: String, condition: Int, note: String, photo: String?) =
        kind("accept").put("order", order).put("place", place).put("node", node).put("condition", condition).put("note", note)
            .apply { if (photo != null) put("photo", photo) }
    fun scan(order: String, place: String, node: String) = kind("scan").put("order", order).put("place", place).put("node", node)
    fun shelf(order: String, node: String, ls: List<BizLine>) = kind("shelf").put("order", order).put("node", node).put("lines", lines(ls))
    fun sale(order: String, node: String, ls: List<BizLine>) = kind("sale").put("order", order).put("node", node).put("lines", lines(ls))
    fun issue(order: String, place: String?, issueKind: Int, note: String) =
        kind("issue").put("order", order).put("issue_kind", issueKind).put("note", note).apply { if (place != null) put("place", place) }
    fun cancel(order: String, note: String) = kind("cancel").put("order", order).put("note", note)
    // THE ORGANIZATION'S CHATS (contract v1.1, iOS MTBusinessChats): a chat's lane is its 32-byte key; its group a 16-byte tag.
    fun open(chat: String, channel: Boolean, name: String, dept: String?, group: String) =
        kind("open").put("chat", chat).put("chat_kind", if (channel) 2 else 1).put("name", name).put("group", group)
            .apply { if (dept != null) put("dept", dept) }
    fun letter(chat: String, mid: String, text: String, comment: Boolean = false) =
        kind("letter").put("chat", chat).put("mid", mid).put("text", text).apply { if (comment) put("letter_kind", 2) }
    /** An order's sources (the core's SOURCE_TEXT, SOURCE_VOICE). */
    const val TEXT_SOURCE = 1
    const val VOICE_SOURCE = 2
    /** A department of none: sixteen zero bytes. */
    val NO_DEPT = "0".repeat(32)
}

// ─────────────────────────── the organizations on this phone ───────────────────────────

/**
 * THE PERSON'S ORGANIZATIONS, CARRIED BY THE PHONES THEMSELVES (iOS MTBusiness, byte for byte on the wire): every member's
 * phone holds the roster (R), and the people's files (H) reach each employee's own lane and the administrators. The chains
 * lie sealed under the device key in the app's own folder (ORG.r, ORG.h); beside them this phone's own ledger of the roads
 * (whom it reaches each member by, the invitations it minted, the joins on their way, the last word carried to each). The
 * chains travel as one service word BZ: over the pair's own letters, which an older build buries unread.
 *
 * THE CORE NEVER RUNS ON THE MAIN THREAD (point 0, third pass; iOS k0c 0a1d9fef): the fold proves the ML-DSA signature of
 * every record. Every call of the core -- view, author, merge, heads, after, keep, slice, attest -- runs on the Business's one
 * thread («montana.business»), and that thread alone holds the chains, the phone's ledger, the keys, the views it draws and
 * the words to carry: one owner, the records of one organization in their order. The main thread gets back the finished
 * picture once per piece of work -- the views, the joins, the moves, the lines of removal, the first days, the roads, the
 * confirmed number -- and the words, which leave after their chains lie on disk; then its own chores (the chats follow the
 * roster, the cards are met). A touch never waits for the core: it hands its work over and hears the answer back on the main
 * thread. A background thread that needs an answer at once (the number's door, a word landing) waits for it there.
 */
object Biz {
    /** [P2P-COMPAT] iOS MTBusiness.mark: the Messenger's service prefix and the word BZ:. */
    const val MARK = "\u200B\u200BBZ:"
    const val DOMAIN = "montana.xxx"
    const val SCHEME = "montana-business"
    const val LINK_VERSION = "1"
    /** The chain a framed record belongs to: the byte after the u32 length and the magic (R 0x52, H 0x48). */
    private const val CHAIN_AT = 8
    private const val ROSTER: Byte = 0x52
    private const val STATE = "biz.state"
    private const val THREAD = "montana.business"   // NOT-UI: a thread's name
    private val listeners = mutableListOf<() -> Unit>()

    fun listen(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun unlisten(l: () -> Unit) { synchronized(listeners) { listeners.remove(l) } }

    fun now() = System.currentTimeMillis()
    fun hex(b: ByteArray) = Wire.hex(b)
    fun unhex(s: String): ByteArray? = if (s.length % 2 != 0 || s.any { it !in "0123456789abcdef" }) null
        else ByteArray(s.length / 2) { i -> s.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
    fun isHex(s: String, n: Int) = s.length == n && unhex(s) != null
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String?): ByteArray = s?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() } ?: ByteArray(0)
    /** The owner and the administrators: they hold the whole of the files and pay. */
    fun boss(role: String) = role == "owner" || role == "admin"
    /** A department's or an item's new name: sixteen fresh bytes from the core. */
    fun freshTag(): String? = MtBindings.nativeRandom(16)?.let { hex(it) }
    fun myName(): String = Prefs.userName.trim().ifEmpty { MontanaSeed.twin?.let { Callsign.of(Book.ctx, it) } ?: "" }
    /** My card: the Messenger's standing link of mine, so the members reach me by it (it may ask the network: off the main thread). */
    fun myCard(c: Context): String = MontanaCard.offerPermanent(c) ?: ""

    // ── the main thread's picture (laid by the Business's thread; no screen writes it) ──

    /** A join on its way (iOS Joining); a MOVE (7.3) -- the person had a place on an old phone and chooses themselves in the roster. */
    class BizJoining(val org: String, val move: Boolean, val mine: String?, val at: Double)
    /** A new phone asking this administrator to move a member's place to its key (iOS Move). */
    class Move(val org: String, val member: String, val key: String, val e164: String, val pipe: String, val at: Double,
               /** The secret of the invitation the move came through: revoked the moment the place moves (point 0). */
               val secret: String?) {
        val id: String get() = org + ":" + member
    }
    /** What the Business's thread hands the main thread after a piece of work (iOS Picture). */
    class Picture(val views: Map<String, BizView>, val joins: List<BizJoining>, val moves: List<Move>, val left: Map<String, String>,
                  val greet: List<String>, val roads: Map<String, String>, val phone: BizPhone.Opened?)
    @Volatile var picture = Picture(emptyMap(), emptyList(), emptyList(), emptyMap(), emptyList(), emptyMap(), null)
        private set
    private var redrawing = false   // main thread: a redraw on its way folds the ones asked meanwhile
    /**
     * Invitations being opened right now -- the inviter's card is being met (the Messenger's first contact, over the network): the
     * Business page says so while it lasts instead of standing silent (K.6, iOS MTBusiness.opening). Main thread.
     */
    @Volatile var opening = 0
        private set
    private fun changedHere() { val ls = synchronized(listeners) { listeners.toList() }; ls.forEach { it() } }

    /** The view of an organization as last drawn. */
    fun view(org: String): BizView? = picture.views[org]
    /**
     * The organizations the person holds a place in, the newest first (a roster that came for a move, before the place is moved,
     * is not one of them).
     */
    fun orgs(): List<BizView> = picture.views.values.filter { it.org.isNotEmpty() && it.role != "none" }.sortedByDescending { it.createdMs }
    /** The joins on their way (iOS joins): the organizations asked for and not yet come. */
    fun joins(): List<BizJoining> = picture.joins
    fun moves(): List<Move> = picture.moves
    /** The organizations that removed this person, by their names, while their line is not hidden. */
    fun left(): Map<String, String> = picture.left.filterValues { it.isNotEmpty() }
    /** The organizations joined whose first day has not been shown yet (8.1). */
    fun greet(): List<String> = picture.greet
    /** The number confirmed on this phone, as the core opened it. */
    fun phone(): BizPhone.Opened? = picture.phone
    /** The pipe this phone reaches a member by, when it holds one. */
    fun pipe(member: String): String? = picture.roads[member]?.takeIf { Book.secret(it) != null }

    // ── the Business's thread ──

    private val q = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, THREAD).apply { isDaemon = true } }
    /**
     * Where the words leave and the chores run, in the order the pictures landed: off the main thread too, since the post and the
     * groups write their own vaults. The core is never called here.
     */
    private val side = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, THREAD + ".side").apply { isDaemon = true } }
    private fun onQ() = Thread.currentThread().name == THREAD
    // what the Business's thread alone holds (iOS: chains, local, keyCache, drawn, outbox, chores)
    private class Chains(val r: ByteArray, val h: ByteArray)
    private val chainsQ = HashMap<String, Chains>()
    private var localQ = JSONObject()
    private var loadedQ = false
    private var keyCache: Pair<ByteArray, ByteArray>? = null
    private val drawnQ = HashMap<String, BizView>()
    private var phoneQ: BizPhone.Opened? = null
    private val outboxQ = mutableListOf<Pair<String, String>>()   // (pipe, text): carried once the chains lie on disk
    private val choresQ = mutableListOf<() -> Unit>()          // work after the picture lands (the groups, the receipts)

    /** The picture of this moment, taken on the Business's thread; the words and the chores go with it once. */
    private fun publishQ(): () -> Unit {
        val routes = localQ.optJSONObject("routes")
        val p = Picture(HashMap(drawnQ),
            localQ.optJSONObject("joining")?.let { all ->
                all.keys().asSequence().mapNotNull { all.optJSONObject(it) }.map { BizJoining(it.optString("org"), it.optBoolean("move"), it.optString("mine").ifEmpty { null }, it.optDouble("at")) }
                    .sortedByDescending { it.at }.toList()
            } ?: emptyList(),
            localQ.optJSONObject("moves")?.let { all ->
                all.keys().asSequence().mapNotNull { all.optJSONObject(it) }.map { Move(it.optString("org"), it.optString("member"), it.optString("key"),
                    it.optString("e164"), it.optString("pipe"), it.optDouble("at"), it.optString("secret").ifEmpty { null }) }.sortedBy { it.at }.toList()
            } ?: emptyList(),
            localQ.optJSONObject("left")?.let { l -> l.keys().asSequence().associateWith { l.optString(it) } } ?: emptyMap(),
            localQ.optJSONArray("first_day")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } } ?: emptyList(),
            routes?.let { r -> r.keys().asSequence().associateWith { r.optString(it) } } ?: emptyMap(),
            phoneQ)
        val words = outboxQ.toList(); outboxQ.clear()
        val chores = choresQ.toList(); choresQ.clear()
        return {
            picture = p
            if (words.isNotEmpty() || chores.isNotEmpty()) side.execute {
                for ((pipe, text) in words) Post.send(pipe, Marks.mintMid(), text)
                for (c in chores) runCatching { c() }.onFailure { Log.w("Montana", "biz chore: " + it.javaClass.simpleName) }
            }
            val ls = synchronized(listeners) { listeners.toList() }
            ls.forEach { it() }
        }
    }
    /**
     * A PIECE OF WORK on the Business's thread; the picture lands on the main thread, then the answer. Any thread may hand work
     * over; nobody waits for it.
     */
    private fun <T> job(work: () -> T, done: ((T?) -> Unit)? = null) {
        q.execute {
            loadQ()
            val out = try { work() } catch (e: Exception) { Log.w("Montana", "biz work: " + e.javaClass.simpleName); null }
            runCatching { forgetSpentQ() }
            val apply = publishQ()
            MainThread.post { apply(); done?.invoke(out) }
        }
    }
    /** A piece of work a background thread waits for (the number's door, a word landing); never asked on the main thread. */
    private fun <T> wait(work: () -> T): T? {
        if (onQ()) return work()
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) { Log.w("Montana", "biz: the main thread never waits for the core"); return null }
        return q.submit(java.util.concurrent.Callable {
            loadQ()
            val out = try { work() } catch (e: Exception) { Log.w("Montana", "biz work: " + e.javaClass.simpleName); null }
            runCatching { forgetSpentQ() }
            val apply = publishQ()
            MainThread.post { apply() }
            out
        }).get()
    }

    // ── the store (the Business's thread) ──
    private fun dir() = File(Book.ctx.filesDir, "business").apply { mkdirs() }
    private fun file(name: String) = File(dir(), name)
    /** A sealed file: empty when there is none, null when it is there and does not open (nothing is written over it). */
    private fun read(name: String): ByteArray? {
        val f = file(name)
        if (!f.exists()) return ByteArray(0)
        return DeviceVault.unseal(f.readBytes())
    }
    private fun write(name: String, bytes: ByteArray): Boolean {
        val sealed = DeviceVault.seal(bytes) ?: return false
        val next = file(name + ".next")
        next.writeBytes(sealed)
        return next.renameTo(file(name))
    }
    /** Everything read once from the store (iOS loadQ): the ledger and every chain, then every view drawn. */
    private fun loadQ() {
        if (loadedQ) return
        loadedQ = true
        localQ = DeviceVault.get(STATE)?.let { runCatching { JSONObject(String(it, Charsets.UTF_8)) }.getOrNull() } ?: JSONObject()
        chainsQ.clear()
        for (n in dir().list() ?: emptyArray()) {
            if (!n.endsWith(".r")) continue
            val org = n.removeSuffix(".r")
            if (!isHex(org, 64)) continue
            val r = read(n) ?: continue
            if (r.isEmpty()) continue   // an erased organization is empty
            chainsQ[org] = Chains(r, read(org + ".h") ?: ByteArray(0))
        }
        phoneQ = BizPhone.attestation()?.let { BizPhone.open(it) }
        drawAllQ()
    }
    private fun chains(org: String): Chains? = chainsQ[org]
    private fun save(org: String, c: Chains): Boolean {
        chainsQ[org] = c
        return write(org + ".r", c.r) && write(org + ".h", c.h)
    }
    /** What this phone knows beside the chains (iOS MTBizLocal), held on the Business's thread and written through. */
    private fun state(): JSONObject = localQ
    private fun saveState(o: JSONObject) { localQ = o; DeviceVault.set(STATE, o.toString().toByteArray()) }
    private fun JSONObject.obj(k: String): JSONObject = optJSONObject(k) ?: JSONObject().also { put(k, it) }

    /**
     * THE ORGANIZATION'S OWN MEDIA (iOS MTBizPlace.keep, 02a5a78d): an order's voice note, an acceptance's photo -- sealed under
     * the device key in a folder of the organization's own (ORG.media), named by its hash, so it leaves the phone with the
     * organization (dropRemoved).
     */
    fun keepMedia(org: String, name: String, bytes: ByteArray, done: (Boolean) -> Unit) = job({
        if (!isHex(org, 64) || name.contains('/')) false else {
            File(dir(), org + ".media").mkdirs()   // NOT-UI: a folder name
            write(org + ".media/" + name, bytes)
        }
    }) { done(it == true) }
    /** The organization's media let go (iOS eraseMedia): every file written empty by the same road, then the folder taken away. */
    private fun eraseMedia(org: String) {
        val folder = File(dir(), org + ".media")
        folder.listFiles()?.forEach { f -> runCatching { f.writeBytes(ByteArray(0)) }; f.delete() }
        folder.delete()
    }

    // ── me (the Business's thread) ──
    /** The keys of the person: derived from the words once a launch (MontanaSeed). */
    private fun keys(): Pair<ByteArray, ByteArray>? {
        keyCache?.let { return it }
        val k = MontanaSeed.mnemonic?.let { SeedKeys.from(it) } ?: return null
        return (k.pubkey to k.seckey).also { keyCache = it }
    }
    /** The person's public key, for a background thread that needs it now (the number's door). */
    fun myKeyNow(): ByteArray? = wait { keys()?.first }
    /** The store was laid again under the living (a new person seated by the number's door): everything is read again (iOS reread). */
    fun forgetKey() = job<Unit>({
        keyCache?.second?.fill(0); keyCache = null
        chainsQ.clear(); drawnQ.clear()
        loadedQ = false
        loadQ()
    })

    // ── the views (the Business's thread) ──
    private fun viewOf(c: Chains, pub: ByteArray): BizView? =
        MtBusiness.nativeView(c.r, c.h, pub, now())?.let { runCatching { BizView(JSONObject(it)) }.getOrNull() }
    /** The view as last drawn on the Business's thread, or drawn now. */
    private fun viewQ(org: String): BizView? = drawnQ[org] ?: drawQ(org)
    private fun drawQ(org: String): BizView? {
        val pub = keys()?.first ?: return null
        val c = chains(org) ?: return null
        val v = viewOf(c, pub) ?: return null
        drawnQ[org] = v
        return v
    }
    private fun drawAllQ() {
        drawnQ.clear()
        val pub = keys()?.first ?: return
        for ((org, c) in chainsQ) viewOf(c, pub)?.let { drawnQ[org] = it }
        for ((org, v) in drawnQ.toMap()) dropRemoved(org, v)
    }
    /**
     * EVERY VIEW DRAWN AGAIN (the salary due moves with the clock), on the Business's thread. Asked again while one is on its way,
     * the asking folds into it: no two redraws stand in a row.
     */
    fun redraw() {
        if (redrawing) return
        redrawing = true
        job<Unit>({ drawAllQ() }) { redrawing = false }
    }

    // ── writing ──

    /**
     * ONE RECORD OF MINE (iOS write): written on the Business's thread; the answer -- the organization, or null when the core
     * refused -- comes back on the main thread once the chain lies on disk and the picture is drawn.
     */
    fun write(org: String?, cmd: JSONObject, done: (String?) -> Unit = {}) = job({ writeQ(org, cmd) }, done)
    /**
     * ONE RECORD OF MINE: the core writes it from the chains this phone holds and refuses what the fold does not allow; it joins
     * its chain, the chain lies on disk before anyone is told, and the members hear it.
     */
    private fun writeQ(org: String?, cmd: JSONObject): String? {
        val (pub, sk) = keys() ?: return null
        val c = org?.let { chains(it) } ?: Chains(ByteArray(0), ByteArray(0))
        val rec = MtBusiness.nativeAuthor(sk, pub, c.r, c.h, cmd.toString(), now())
        if (rec == null) { Log.i("Montana", "biz_refused author kind=" + cmd.optString("kind") + " code=" + MtBusiness.nativeLastError()); return null }
        val roster = rec.size > CHAIN_AT && rec[CHAIN_AT] == ROSTER
        val merged = MtBusiness.nativeMerge(if (roster) c.r else c.h, rec) ?: return null
        val next = if (roster) Chains(merged, c.h) else Chains(c.r, merged)
        val v = viewOf(next, pub) ?: return null
        val o = v.org.ifEmpty { null } ?: org ?: return null
        if (!save(o, next)) return null
        drawnQ[o] = v
        spreadQ(o)
        return o
    }

    /** THE ORGANIZATION IS BORN on its owner's phone: the name, the owner's own name and card. */
    fun found(name: String, card: String, done: (String?) -> Unit) {
        val me = myName()
        job({ writeQ(null, BizCommand.genesis(name, me, card))?.also { bindPhoneQ(it) } }, done)
    }

    /**
     * AN INVITATION (iOS invite): its secret is drawn here and kept by this phone alone (the record names only its hash); the
     * link carries the organization, the secret and this person's card. A number given binds the invitation to it.
     */
    fun invite(org: String, role: BizRole, dept: String?, days: Int, e164: String?, card: String, done: (String?) -> Unit) = job({
        val secret = MtBindings.nativeRandom(32)?.let { hex(it) }
        val v = viewQ(org)
        if (secret == null || v == null) null else {
            val before = v.invites.map { it.id }.toSet()
            val expires = now() + maxOf(1, days) * 86_400_000L
            if (writeQ(org, BizCommand.invite(secret, role.number, dept ?: BizCommand.NO_DEPT, expires, e164 != null)) == null) null else {
                val st = state()
                st.obj("minted").put(secret, JSONObject().put("org", org).put("at", now() / 1000.0))
                saveState(st)
                if (e164 != null) viewQ(org)?.invites?.firstOrNull { it.id !in before }?.let { writeQ(org, BizCommand.invitePhone(it.id, e164)) }
                link(org, secret, card)
            }
        }
    }, done)
    fun link(org: String, secret: String, card: String) =
        "https://" + DOMAIN + "/b/join#" + LINK_VERSION + "." + org + "." + secret + "." + MontanaCard.b64url(card.toByteArray(Charsets.UTF_8))

    /**
     * AN INVITATION'S SECRET STAYS ONLY WHILE THE INVITATION CAN BE USED (K.0, iOS pruneMintedQ 8182ef2d): this phone keeps the
     * secret of each invitation it minted -- the record names only its hash -- to answer the one who asks with it. The core names
     * the invitation from its secret (nativeInviteId); one the view names anything but open (used, revoked, expired) leaves,
     * unless a new phone's move still waits on it, until that move ends. One the view does not show -- not arrived yet, a role
     * that no longer sees the invitations, an organization not drawn -- is judged by its own age: it leaves once no invitation
     * could still live, the longest term an invitation is given and a day. No secret is kept for ever.
     */
    private const val MINTED_LONGEST_S = 31 * 86_400.0
    private fun forgetSpentQ() {
        val st = state()
        val minted = st.optJSONObject("minted") ?: return
        if (minted.length() == 0) return
        val moving = st.optJSONObject("moves")?.let { m -> m.keys().asSequence().mapNotNull { m.optJSONObject(it)?.optString("secret") }.toSet() } ?: emptySet()
        val nowS = now() / 1000.0
        var spent = 0
        for (secret in minted.keys().asSequence().toList()) {
            if (secret in moving) continue
            val m = minted.optJSONObject(secret)
            val id = unhex(secret)?.let { MtBusiness.nativeInviteId(it) }?.let { hex(it) }
            val shown = m?.optString("org")?.let { drawnQ[it] }?.invites?.firstOrNull { it.id == id }
            val keep = if (id != null && shown != null) shown.state == "open" else m != null && nowS - m.optDouble("at", 0.0) < MINTED_LONGEST_S
            if (!keep) { minted.remove(secret); spent++ }
        }
        if (spent > 0) { saveState(st); Log.i("Montana", "biz_minted spent=" + spent) }
    }

    /** The number confirmed on this phone goes into each organization's files once (PhoneBind). */
    private fun bindPhoneQ(org: String) {
        val a = BizPhone.attestation() ?: return
        val v = viewQ(org) ?: return
        if (!v.may("phone_bind") || v.member(v.me)?.phoneConfirmed != false) return
        writeQ(org, BizCommand.phoneBind(hex(a)))
    }
    /**
     * A NUMBER CONFIRMED (the number's door, a background thread): kept only when the core proves it and it names this person's
     * key and the number asked for; then bound into every organization's files. True when kept.
     */
    fun keepPhone(a: ByteArray, e164: String): Boolean = wait {
        val pub = keys()?.first
        val o = BizPhone.open(a)
        if (pub == null || o == null || o.subject != BizPhone.subject(pub) || o.e164 != e164 || !BizPhone.store(a)) false else {
            phoneQ = o
            for (org in chainsQ.keys.toList()) bindPhoneQ(org)
            true
        }
    } == true

    /** AN ORDER (iOS order, 6.3): its key drawn here (32 fresh bytes, the lane of its TimeChain), the record written. */
    fun order(org: String, from: String, to: String, lines: List<BizLine>, voice: String?, text: String, done: (String?) -> Unit) = job({
        val key = MtBindings.nativeRandom(32)?.let { hex(it) }
        if (key == null || writeQ(org, BizCommand.order(key, from, to, lines, voice, text)) == null) null else key
    }, done)

    // ── joining ──

    private fun uri(link: String) = runCatching { android.net.Uri.parse(link.trim()) }.getOrNull()
    fun isJoin(link: String): Boolean {
        val u = uri(link) ?: return false
        val scheme = u.scheme?.lowercase() ?: ""; val host = u.host?.lowercase() ?: ""
        if (scheme == "https" && host == DOMAIN && u.path == "/b/join") return true
        return scheme == SCHEME && host == "b" && u.path == "/join"
    }
    fun isBack(link: String): Boolean {
        val u = uri(link) ?: return false
        val scheme = u.scheme?.lowercase() ?: ""; val host = u.host?.lowercase() ?: ""
        if (scheme == "https" && host == DOMAIN && u.path == "/b/back") return true
        return scheme == SCHEME && host == "b" && u.path == "/back"
    }

    /**
     * A joining link read (iOS invitation): the organization, the invitation's secret and the inviter's card -- https://SITE/b/join
     * #1.ORG.SECRET.CARD, ORG and SECRET in hex, CARD the inviter's card link in base64url; null when it cannot be read.
     */
    fun invitation(link: String): Triple<String, String, String>? {
        val parts = (uri(link)?.fragment ?: "").split('.')
        val card = if (parts.size == 4) MontanaCard.unb64url(parts[3])?.toString(Charsets.UTF_8) else null
        if (parts.size != 4 || parts[0] != LINK_VERSION || !isHex(parts[1], 64) || !isHex(parts[2], 64) || card.isNullOrEmpty()) return null
        return Triple(parts[1], parts[2], card)
    }

    sealed class Accepted {
        object Member : Accepted()
        object Asked : Accepted()
        object Unreadable : Accepted()
        object NotOpened : Accepted()
    }

    /**
     * A JOINING LINK OR ITS CODE (iOS accept): the inviter is met by the card the link carries -- the Messenger's own first
     * contact, on a thread of its own -- and asked for the roster over that pipe; the Join record is written the moment it comes.
     * The answer comes back on the main thread.
     */
    fun accept(c: Context, link: String, move: Boolean, done: (Accepted) -> Unit) {
        val (org, secret, card) = invitation(link) ?: run { MainThread.post { done(Accepted.Unreadable) }; return }
        if (view(org)?.let { it.role != "none" } == true) { MainThread.post { done(Accepted.Member) }; return }
        val name = myName()
        opening++; changedHere()
        val answer: (Accepted) -> Unit = { r -> opening--; changedHere(); done(r) }
        Thread {
            val mine = myCard(c)
            val met = Meeting.meet(c, card)
            if (met !is Meeting.Outcome.Opened) { MainThread.post { answer(Accepted.NotOpened) }; return@Thread }
            val root = SamePair.root(met.ref)
            job<Accepted>({
                val st = state()
                st.obj("joining").put(org, JSONObject().put("org", org).put("secret", secret).put("pipe", root).put("at", now() / 1000.0)
                    .put("name", name).put("card", mine).apply { if (move) put("move", true) })
                st.obj("left").remove(org)   // joined again: the organization is this person's once more
                saveState(st)
                // heads of nothing: the whole roster comes
                carry(JSONObject().put("t", "q").put("o", org).put("k", secret).heads(ByteArray(0), ByteArray(0)), root)
                Accepted.Asked
            }) { answer(it ?: Accepted.NotOpened) }
        }.start()
    }

    // ── the wire (the Business's thread) ──

    /**
     * SYNC BY HEADS (contract 1.1, iOS c90943e3): «s» and «q» carry the speaker's own heads -- of the roster (rh) and of the closed
     * stream (hh), base64 -- and the answer carries only what the speaker lacks (nativeAfter); a word without heads is taken as
     * before, and answered whole.
     */
    private fun JSONObject.heads(r: ByteArray, h: ByteArray): JSONObject = apply {
        headsText(r)?.let { put("rh", it) }
        headsText(h)?.let { put("hh", it) }
    }
    /** A stream's heads as the word carries them (base64); null when the core does not answer. */
    private fun headsText(stream: ByteArray): String? = MtBusiness.nativeHeads(stream)?.let { b64(it) }
    /** What the holder of the told heads lacks of a stream; null when no heads were told (or they cannot be read): answer whole. */
    private fun lacked(stream: ByteArray, heads: String?): ByteArray? {
        val h = heads?.takeIf { it.isNotEmpty() }?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() } ?: return null
        return MtBusiness.nativeAfter(stream, h)
    }
    /**
     * A word to carry: it waits on the Business's thread and leaves from the main thread once the chains lie on disk. Never to a
     * person this phone blocked (iOS carry: !ChatStore.refusesCold): a blocked person reaches nothing, and is reached by nothing.
     */
    private fun carry(w: JSONObject, pipe: String) {
        if (Book.secret(pipe) == null || PeerSafety.isBlocked(pipe)) return
        outboxQ += pipe to MARK + w.toString()
    }

    /**
     * THE CEILING OF ONE WORD (point 0, iOS wordCeiling): a word is measured before it is read -- the whole text, and so each of
     * its streams -- and a larger one is buried unread. Thirty-two mebibytes: the roster and the closed stream of an
     * organization of some thousands of records, as base64.
     */
    private const val WORD_CEILING = 33_554_432L
    /** The text's size in UTF-8, counted without a copy, and only as far as the ceiling. */
    private fun measured(s: String): Long {
        var n = 0L
        for (ch in s) {
            n += when { ch.code < 0x80 -> 1; ch.code < 0x800 -> 2; ch.isSurrogate() -> 2; else -> 3 }
            if (WORD_CEILING < n) return n
        }
        return n
    }

    /**
     * AN ORGANIZATION'S WORD ARRIVED ON A PIPE (Pipes.place): measured where it lands, read and taken on the Business's thread; its
     * receipt goes once it was taken or buried, after the picture (iOS: a chore).
     */
    fun receive(ref: String, body: String, receipt: () -> Unit) {
        val pipe = SamePair.root(ref)
        // A BLOCKED PERSON REACHES NOTHING (iOS ChatStore append: refuses(chat) before MTBusiness.handle): not an organization's
        // word, not a receipt -- on the pipe it came by, nor on the conversation that pipe speaks for
        if (PeerSafety.isBlocked(ref) || PeerSafety.isBlocked(pipe)) { Log.i("Montana", "biz_refused blocked"); return }
        if (WORD_CEILING < measured(MARK) + measured(body)) { Log.i("Montana", "biz_refused too large"); receipt(); return }
        job<Unit>({ try {
            val w = runCatching { JSONObject(body) }.getOrNull()
            when {
                w == null || !isHex(w.optString("o"), 64) -> Log.i("Montana", "biz_refused unreadable")
                // a member id, when the word names one, is 64 hex -- anything else is not this contract's word
                w.has("m") && !isHex(w.optString("m"), 64) -> Log.i("Montana", "biz_refused unreadable member")
                else -> when (w.optString("t")) {
                    "q" -> asked(w, pipe)
                    "s" -> synced(w, pipe)
                    "d" -> moving(w, pipe)
                    // a newer build's word: its road ends here; its kind told only in letters a-z (a peer's text never reaches the log)
                    else -> Log.i("Montana", "biz_refused kind=" + w.optString("t").take(8).filter { it in 'a'..'z' })
                }
            }
        } finally { choresQ += receipt } })
    }

    /** One who holds an invitation this phone minted asks for the roster: it goes to them, their pipe remembered for the Join. */
    private fun asked(w: JSONObject, pipe: String) {
        val k = w.optString("k"); val org = w.optString("o")
        val c = chains(org) ?: return
        val v = viewQ(org) ?: return
        val st = state()
        val m = st.optJSONObject("minted")?.optJSONObject(k) ?: return
        if (m.optString("org") != org) return
        if (m.optString("pipe") != pipe) {   // the same question again writes nothing; the roster goes to it again
            m.put("pipe", pipe)
            saveState(st)
        }
        val roster = lacked(c.r, w.optString("rh")) ?: c.r
        carry(JSONObject().put("t", "s").put("o", org).put("m", v.me).put("r", b64(roster)).heads(c.r, c.h), pipe)
    }

    /**
     * THE CHAINS OF ANOTHER MEMBER (iOS synced): merged by the core, the files written, what is new carried on. The pipe
     * becomes the road to a member in two cases only: the inviter of a join this phone is making, and a member who just joined
     * by an invitation this phone minted, on the pipe that asked with its secret. A word that names a member is never trusted
     * for a road by itself.
     */
    private fun synced(w: JSONObject, pipe: String) {
        val org = w.optString("o")
        val pub = keys()?.first ?: return
        val st0 = state()
        val joining = st0.optJSONObject("joining")?.optJSONObject(org)?.takeIf { SamePair.root(it.optString("pipe")) == pipe }
        if (st0.optJSONObject("left")?.has(org) == true && joining == null) return   // an organization that removed this person
        // NOT MINE AND NOT ON MY WAY IN (point 0): an organization this phone holds no place in and is not joining or moving into
        // is refused before a byte of the word is decoded, and nothing is written
        if (joining == null && viewQ(org)?.let { it.role != "none" } != true) { Log.i("Montana", "biz_refused not a member"); return }
        // ONLY THIS ORGANIZATION'S RECORDS ARE MERGED (core 2f6e012, nativeKeep): a stream is kept to the organization the word
        // names before the merge, so a record of another one never grows these chains
        val orgId = unhex(org) ?: return
        val inR = unb64(w.optString("r").ifEmpty { null }).let { if (it.isEmpty()) it else MtBusiness.nativeKeep(orgId, it) ?: ByteArray(0) }
        val inH = unb64(w.optString("h").ifEmpty { null }).let { if (it.isEmpty()) it else MtBusiness.nativeKeep(orgId, it) ?: ByteArray(0) }
        val before = drawnQ[org]?.members?.map { it.id }?.toSet() ?: emptySet()
        var added = false
        var c = chains(org) ?: (if (joining != null) Chains(ByteArray(0), ByteArray(0)) else null) ?: return
        if (inR.isNotEmpty()) MtBusiness.nativeMerge(c.r, inR)?.let { if (!it.contentEquals(c.r)) added = true; c = Chains(it, c.h) }
        if (inH.isNotEmpty()) MtBusiness.nativeMerge(c.h, inH)?.let { if (!it.contentEquals(c.h)) added = true; c = Chains(c.r, it) }
        // WHAT THE SPEAKER LACKS: by the heads it told (nativeAfter); a word of an older build tells none, and the merge the other
        // way round answers as before
        var back = ByteArray(0)
        val byHeads = lacked(c.r, w.optString("rh"))
        if (byHeads != null) back = byHeads
        else if (c.r.isNotEmpty()) MtBusiness.nativeMerge(inR, c.r)?.let { if (!it.contentEquals(inR)) back = c.r }
        if (c.r.isEmpty()) return
        val lacks = back.isNotEmpty()
        if (!added && joining == null) {
            // THE SAME WORD AGAIN (point 0): nothing new in it -- no chain is written, nothing drawn again; only the speaker's heads
            // are kept when they changed, and what it lacks is told back
            heard(w, org, pipe)
            if (lacks) tellBack(back, org, pipe)
            return
        }
        // only a word that brought a record writes the chains and draws the view (iOS synced: 0 < added); the roster of a join
        // that came again with nothing new is read as this phone already holds it
        val v = if (added) { if (!save(org, c)) return; viewOf(c, pub)?.also { drawnQ[org] = it } } else drawnQ[org]
        val m = w.optString("m")
        if (isHex(m, 64) && route(m) == null) {
            if (joining != null) bind(m, pipe)   // the inviter, met by the card the link carried
            else if (m !in before && v?.member(m) != null) {
                val st = state()
                val minted = st.obj("minted")
                val k = minted.keys().asSequence().firstOrNull { minted.optJSONObject(it)?.let { e -> e.optString("org") == org && e.optString("pipe") == pipe } == true }
                if (k != null) {
                    st.obj("routes").put(m, pipe)   // the member who joined by this phone's invitation, on the pipe that asked with its secret
                    minted.remove(k)
                    saveState(st)
                }
            }
        }
        heard(w, org, pipe)
        val move = joining?.optBoolean("move") == true
        when {
            joining != null && (v == null || v.role == "none") -> if (!move) joinNow(org)   // a move waits for the person's choice and the administrator's touch
            joining != null && move -> moved(org)
            added -> spreadQ(org)
        }
        if (v != null) dropRemoved(org, v)
        if (lacks) tellBack(back, org, pipe)
    }

    /**
     * THE SPEAKER'S HEADS ARE KEPT (iOS heard) for the member whose road this pipe is -- never for a member a word merely names --
     * and only when they changed: the same heads again write nothing.
     */
    private fun heard(w: JSONObject, org: String, pipe: String) {
        val m = w.optString("m"); val rh = w.optString("rh"); val hh = w.optString("hh")
        if (!isHex(m, 64) || rh.isEmpty() || hh.isEmpty() || route(m) != pipe) return
        val st = state()
        val had = st.optJSONObject("heads")?.optJSONObject(org)?.optJSONObject(m)
        if (had != null && had.optString("r") == rh && had.optString("h") == hh) return
        st.obj("heads").obj(org).put(m, JSONObject().put("r", rh).put("h", hh))
        saveState(st)
    }
    /**
     * What the speaker lacks goes back (iOS tellBack): to a member on their own road, by the one spread (their heads just kept);
     * to any other pipe, the roster it lacks, with this phone's heads.
     */
    private fun tellBack(back: ByteArray, org: String, pipe: String) {
        val routes = state().optJSONObject("routes")
        if (routes?.keys()?.asSequence()?.any { routes.optString(it) == pipe } == true) { spreadQ(org); return }
        val c = chains(org) ?: return
        carry(JSONObject().put("t", "s").put("o", org).apply { drawnQ[org]?.me?.takeIf { isHex(it, 64) }?.let { put("m", it) } }.put("r", b64(back)).heads(c.r, c.h), pipe)
    }

    /** The roster came for a join this phone is making: the Join record is written now, with the person's name and card. */
    private fun joinNow(org: String) {
        val j = state().optJSONObject("joining")?.optJSONObject(org) ?: return
        if (writeQ(org, BizCommand.join(j.optString("secret"), j.optString("name"), j.optString("card"))) == null) {
            Log.i("Montana", "biz_join the Join record was refused")
            return
        }
        val st = state()
        st.obj("joining").remove(org)
        val first = st.optJSONArray("first_day") ?: JSONArray().also { st.put("first_day", it) }
        if ((0 until first.length()).none { first.optString(it) == org }) first.put(org)   // the first day shows once (8.1)
        saveState(st)
        bindPhoneQ(org)
    }

    // ── a new phone and a removed one (7.3, iOS MTBusiness) ──

    /**
     * THE PERSON CHOSE THEMSELVES in the roster that came for a move: the administrator who minted the invitation is asked, on
     * the pipe the roster came by, to move that place to this phone's key -- with the key, the number's confirmation for it
     * (MTBA) and the invitation's secret.
     */
    fun askMove(org: String, member: String, done: (Boolean) -> Unit) = job({
        val pub = keys()?.first
        val a = BizPhone.attestation()
        val st = state()
        val j = st.optJSONObject("joining")?.optJSONObject(org)?.takeIf { it.optBoolean("move") }
        if (pub == null || a == null || j == null) false else {
            j.put("mine", member)
            saveState(st)
            carry(JSONObject().put("t", "d").put("o", org).put("m", member).put("k", j.optString("secret")).put("a", hex(a)).put("p", hex(pub)), j.optString("pipe"))
            true
        }
    }) { done(it == true) }

    /**
     * A NEW PHONE ASKS (the administrator's side): the secret is one this phone minted and the new phone asked with it on this
     * very pipe; the confirmation is the service's (the core proves its signature) and names the key the word carries. Only
     * then the request waits on the organization's page for one touch.
     */
    private fun moving(w: JSONObject, pipe: String) {
        val org = w.optString("o"); val k = w.optString("k"); val member = w.optString("m"); val p = w.optString("p"); val a = w.optString("a")
        // the key's length (1952 bytes, 3904 hex) and the confirmation's (8192 hex at most) are checked before either is decoded
        if (p.length != 3904 || 8192 < a.length) { Log.i("Montana", "biz_refused move unproven"); return }
        val had = state().optJSONObject("moves")?.optJSONObject(org + ":" + member)
        if (had != null && had.optString("key") == p && had.optString("pipe") == pipe) return   // the same request again writes nothing
        val minted = state().optJSONObject("minted")?.optJSONObject(k)
        val v = viewQ(org)
        val pub = unhex(p)
        val proof = unhex(a)?.let { BizPhone.open(it) }
        val proven = minted != null && minted.optString("org") == org && minted.optString("pipe") == pipe && v != null && boss(v.role) &&
            v.member(member)?.let { it.status != "removed" } == true && pub != null && pub.size == 1952 &&
            proof != null && proof.subject == BizPhone.subject(pub)
        if (!proven || proof == null) { Log.i("Montana", "biz_refused move unproven"); return }
        val st = state()
        st.obj("moves").put(org + ":" + member, JSONObject().put("org", org).put("member", member).put("key", p).put("e164", proof.e164)
            .put("pipe", pipe).put("at", now() / 1000.0).put("secret", k))
        saveState(st)
    }

    /**
     * ONE TOUCH: the Rekey record moves the place to the new key, and the road to the member is the new phone's pipe from now on
     * -- the roster and the member's own files go there at once. Refused, the old road stays.
     */
    fun confirmMove(m: Move, done: (Boolean) -> Unit) = job({
        val before = route(m.member)
        val st = state()
        st.obj("routes").put(m.member, SamePair.root(m.pipe))
        st.optJSONObject("sent")?.optJSONObject(m.org)?.remove(m.member)
        saveState(st)
        if (writeQ(m.org, BizCommand.rekey(m.member, m.key)) == null) {
            val back = state()
            if (before != null) back.obj("routes").put(m.member, before) else back.obj("routes").remove(m.member)
            saveState(back)
            false
        } else {
            val after = state(); after.obj("moves").remove(m.id); saveState(after)
            // the invitation the move came through is spent: nobody else walks in by it (the core names it from its secret)
            m.secret?.let { writeQ(m.org, BizCommand.revokeSecret(it)) }
            true
        }
    }) { done(it == true) }
    fun declineMove(m: Move) = job<Unit>({ val st = state(); st.obj("moves").remove(m.id); saveState(st) })
    /** The place is this phone's (the new phone's side): the move ends, the number is bound to the new key, the administrators hear it. */
    private fun moved(org: String) {
        val st = state(); st.obj("joining").remove(org); saveState(st)
        bindPhoneQ(org)
        spreadQ(org)
    }
    /** The first day of an organization was shown. */
    fun greeted(org: String) = job<Unit>({
        val st = state()
        st.optJSONArray("first_day")?.let { first ->
            st.put("first_day", JSONArray((0 until first.length()).map { first.optString(it) }.filter { it != org }))
            saveState(st)
        }
    })
    /** The line about a removed organization is hidden; the organization stays let go. */
    fun hideLeft(org: String) = job<Unit>({
        val st = state()
        if (st.optJSONObject("left")?.has(org) == true) { st.obj("left").put(org, ""); saveState(st) }
    })

    /**
     * THE PERSON WAS REMOVED (7.3, iOS dropRemoved): the fold names me removed, and this phone lets the organization go -- its two
     * chains are written empty, the memory of its roads and words is dropped, and one line says so on the Business page. Words of
     * that organization are refused from here on, unless the person joins it again. True when it was let go now.
     */
    private fun dropRemoved(org: String, v: BizView): Boolean {
        if (v.member(v.me)?.status != "removed") return false
        write(org + ".r", ByteArray(0))
        write(org + ".h", ByteArray(0))
        eraseMedia(org)   // the voice notes and photos of its orders leave with it
        chainsQ.remove(org)
        drawnQ.remove(org)
        val st = state()
        st.obj("left").put(org, v.name)
        st.optJSONObject("sent")?.remove(org)
        st.optJSONObject("joining")?.remove(org)
        st.optJSONObject("minted")?.let { m -> m.keys().asSequence().toList().filter { m.optJSONObject(it)?.optString("org") == org }.forEach { m.remove(it) } }
        st.optJSONObject("moves")?.let { m -> m.keys().asSequence().toList().filter { m.optJSONObject(it)?.optString("org") == org }.forEach { m.remove(it) } }
        saveState(st)
        Log.i("Montana", "biz_left removed")
        return true
    }

    /**
     * THE ORGANIZATION'S CHAINS AS FILES (7.4, iOS chainFiles): as they lie on this phone, under the organization's name, through
     * the one door out (BizExport: the cache, emptied before every export) for the platform's own sheet.
     */
    fun chainFiles(c: Context, org: String, done: (List<File>) -> Unit) = job({
        BizExport.sweepMedia(c)
        chains(org)?.let { ch ->
            // «Name roster.mtbiz», «Name closed.mtbiz» (iOS chainFiles): the organization's name made a file's name
            val base = BizExport.fileName(drawnQ[org]?.name ?: "", org.take(8))
            BizExport.export(c, listOf("roster" to ch.r, "closed" to ch.h).filter { it.second.isNotEmpty() }
                .map { (part, data) -> (base + " " + part + ".mtbiz") to data })   // NOT-UI: file names
        } ?: emptyList()
    }) { done(it ?: emptyList()) }

    private fun bind(member: String, pipe: String) { val st = state(); st.obj("routes").put(member, SamePair.root(pipe)); saveState(st) }
    private fun route(member: String): String? = state().optJSONObject("routes")?.optString(member)?.ifEmpty { null }
    private fun pipeQ(member: String): String? = route(member)?.takeIf { Book.secret(it) != null }

    /**
     * WHAT EACH MEMBER MAY HOLD, CARRIED ON (iOS spread): the roster to every member this phone reaches; of the closed stream
     * (H, S and C together), an administrator carries the whole to administrators, and everyone else gets the lanes that are
     * theirs (nativeSlice): their own lane of H from an administrator (an employee carries their own to the administrators),
     * every order whose path they stand on (orders[].people), every chat they hear (chats[].people). One word (BZ:), one road.
     * A word the member already got from here is not carried twice.
     */
    private fun spreadQ(org: String) {
        val v = viewQ(org) ?: return
        val c = chains(org) ?: return
        val me = v.me
        val chief = boss(v.role)
        val ownR = headsText(c.r); val ownH = headsText(c.h)
        val told = state().optJSONObject("heads")?.optJSONObject(org)
        var wrote = false
        for (them in v.members) {
            if (them.id == me || them.status == "removed") continue
            val pipe = pipeQ(them.id) ?: continue
            var files: ByteArray = if (chief && boss(them.role)) c.h else {
                val out = java.io.ByteArrayOutputStream()
                for (lane in lanes(them, v, chief)) unhex(lane)?.let { MtBusiness.nativeSlice(c.h, it) }?.let { out.write(it) }
                out.toByteArray()
            }
            // BY THEIR HEADS (contract 1.1): the slice first, then only what they lack of it; heads not known -- the whole
            var roster = c.r
            val t = told?.optJSONObject(them.id)
            if (t != null) {
                lacked(c.r, t.optString("r"))?.let { roster = it }
                lacked(files, t.optString("h"))?.let { files = it }
                if (roster.isEmpty() && files.isEmpty()) continue   // they hold all of it
            }
            val r = roster.takeIf { it.isNotEmpty() }?.let { b64(it) }
            val h = files.takeIf { it.isNotEmpty() }?.let { b64(it) }
            val digest = hex(Wire.sha(((r ?: "") + ":" + (h ?: "")).toByteArray(Charsets.UTF_8)))
            if (state().optJSONObject("sent")?.optJSONObject(org)?.optString(them.id) == digest) continue
            val w = JSONObject().put("t", "s").put("o", org).put("m", me)
            if (r != null) w.put("r", r)
            if (h != null) w.put("h", h)
            ownR?.let { w.put("rh", it) }
            ownH?.let { w.put("hh", it) }
            carry(w, pipe)
            state().obj("sent").obj(org).put(them.id, digest)
            wrote = true
        }
        if (wrote) saveState(state())
        meetUnreached(org, v)
        choresQ += { tend(org) }   // every chat stands here and follows the roster, once the picture is laid
    }

    /**
     * THE CHATS FOLLOW THE ROSTER, on every phone (iOS tend, Sh.2; a chore after the picture): each chat the core says this
     * person hears stands with the people it names and this phone reaches -- a pipe opened since is in at once; whoever the core
     * names no more (removed, moved to another department) is out of it, so a letter of the department never leaves for a phone
     * that left it. A chat that no longer names this person falls silent here: its rows stay, nothing is written in it.
     */
    private fun tend(org: String) {
        val v = view(org) ?: return
        for (c in v.chats) standChat(org, c, v)
        val heard = v.chats.filter { v.me in it.people }.mapNotNull { it.group }.toSet()
        for (id in Groups.meshGroups(org)) if (id !in heard) Groups.silence(id)
    }

    /** The closed lanes one member may hold, of those this phone carries (iOS lanes, the contract v1.1: each lane's people). */
    private fun lanes(them: BizMember, v: BizView, chief: Boolean): List<String> {
        val out = mutableListOf<String>()
        if (chief) out += them.id else if (boss(them.role)) out += v.me
        out += v.orders.filter { them.id in it.people }.map { it.id }
        out += v.chats.filter { them.id in it.people }.map { it.id }
        // the lanes of shifts the member holds (9.1): their own, and their department's when they manage it; one lane once
        for (lane in v.shifts.filter { them.id in it.people }.map { it.lane }) if (lane !in out) out += lane
        return out
    }

    // ── the organization's chats (iOS MTBusinessChats, chain C, stage 7.2; Sh.2) ──
    // A chat of the organization rides a Messenger group or channel (Groups): the same rows and receipts -- but no carrier
    // (Sh.2, the checklist: every one to every one). Chain C beside it proves who said what and when, and who hears it comes
    // from the roster -- the core decides both. Opening writes the Open record first, naming a group id drawn here; the group
    // then stands on every phone the core says hears the chat, with everyone that phone reaches among the people the core
    // names, and every phone writes its own letters to all of them.

    /** A NEW CHAT OF THE ORGANIZATION: a channel (the administrators' voice to everyone) or a department's chat. Null -- refused. */
    fun openChat(org: String, channel: Boolean, name: String, dept: String?, done: (String?) -> Unit) = job({
        val group = freshTag()
        val lane = MtBindings.nativeRandom(32)?.let { hex(it) }
        if (group == null || lane == null || writeQ(org, BizCommand.open(lane, channel, name, dept, group)) == null) null else {
            choresQ += { gather(org, lane) }   // the group is born once the picture names the chat
            lane
        }
    }, done)

    /**
     * THE CHAT'S GROUP STANDS ON THIS PHONE (iOS gather, Sh.2): wherever the core says the person hears the chat -- opened here or
     * on any other phone -- with everyone this phone reaches among its people (off the main thread: the group's store). Asked
     * again from the chat's row. Its key when its row was laid now, else null.
     */
    fun gather(org: String, lane: String): String? {
        val v = view(org) ?: return null
        val c = v.chats.firstOrNull { it.id == lane } ?: return null
        return standChat(org, c, v)
    }

    /**
     * One chat as the core draws it, laid on Groups (iOS standChat): the people this phone reaches (each by their pipe), the
     * count of all of them, who speaks in a channel (its author and the administrators, chat.rs Letter) and who takes another's
     * letter away (the administrators, chat.rs Delete). Seats are the members' own names, shortened: every phone names a person
     * alike.
     */
    private fun standChat(org: String, c: BizChat, v: BizView): String? {
        val group = c.group ?: return null
        if (v.me !in c.people) return null
        val people = c.people.filter { it != v.me }.mapNotNull { m -> pipe(m)?.let { Groups.Member(seat(m), it) } }
        val bosses = v.members.filter { boss(it.role) }.map { seat(it.id) }.sorted()
        val voices = (bosses + seat(c.author)).toSortedSet().toList()
        // every person of the chat and the name the organization knows them by (Sh.3: the chat names them all)
        val everyone = c.people.map { seat(it) }.sorted()
        val names = v.members.filter { it.id in c.people }.associate { seat(it.id) to it.name }
        return Groups.stand(org, group, if (c.kind == "channel") "c" else "g", c.name, seat(v.me), people, c.people.size, voices, bosses,
            everyone, names)
    }
    /** A member's seat in every chat of the organization (iOS MTBusiness.seat): the first sixteen letters of their id, alike on every phone. */
    fun seat(member: String): String = member.lowercase().take(16)

    /** The chat's group stands on this phone: opened here, or its invitation came. */
    fun standing(c: BizChat): Boolean = c.group?.let { Groups.holds(it) } ?: false

    /**
     * A LETTER OF MINE INTO A GROUP THAT CARRIES AN ORGANIZATION'S CHAT: its link in chain C (Groups.send). A group that carries
     * none writes nothing, and neither does a chat whose lane has not reached this phone yet -- the letter has left either way.
     */
    fun said(group: String, mid: String, text: String, comment: Boolean = false) = job<Unit>({
        for ((org, v) in drawnQ.toMap()) {
            val chat = v.chats.firstOrNull { it.group == group } ?: continue
            // a comment under a channel's post is a letter of its own kind (chat.rs LETTER_COMMENT, Sh.4)
            writeQ(org, BizCommand.letter(chat.id, mid, text, comment))
            break
        }
    })

    /**
     * THE MEMBERS THIS PHONE HAS NO ROAD TO ARE MET BY THEIR CARDS (iOS meetUnreached): an administrator meets every member, a
     * member meets the owner and everyone they share a chat with (Sh.2: every phone writes its own letters to every listener).
     * Once an hour at most for each; the meeting on a thread of its own, the road bound back here.
     */
    private fun meetUnreached(org: String, v: BizView) {
        val chief = boss(v.role)
        val nowS = now() / 1000.0
        for (t in v.members) {
            if (t.status != "active" || t.id == v.me || t.card.isEmpty() || pipeQ(t.id) != null) continue
            val shares = v.chats.any { v.me in it.people && t.id in it.people }
            if (!chief && t.role != "owner" && !shares) continue
            val st = state()
            val met = st.obj("met")
            if (nowS - met.optDouble(t.id, 0.0) <= 3600) continue
            met.put(t.id, nowS); saveState(st)
            val card = t.card; val member = t.id
            Thread {
                val m = Meeting.meet(Book.ctx, card)
                if (m is Meeting.Outcome.Opened) job<Unit>({ bind(member, m.ref); spreadQ(org) })
            }.start()
        }
    }

    /** The person leaves: every organization and the number leave this phone with them. */
    fun wipe() = job<Unit>({
        dir().deleteRecursively()
        DeviceVault.delete(STATE)
        BizPhone.forget()
        localQ = JSONObject()
        chainsQ.clear(); drawnQ.clear(); outboxQ.clear(); choresQ.clear()
        keyCache?.second?.fill(0); keyCache = null
        phoneQ = null
    })
}

// ─────────────────────────── the number: the service of montana.xxx/b/v1 ───────────────────────────

/**
 * THE SERVICE OF THE BUSINESS on the app's own site (iOS MTBizService): what roads it keeps, the start of a confirmation, the
 * wait for it. The roads' names and glyphs are the service's: no outer service is named in this app.
 */
object BizService {
    /**
     * The host of the confirmation service (iOS MONTANA_BIZ_SERVICE): it runs on a Montana node,
     * by the author's word 06.10.2026 11:5x MSK, under that node's own name.
     */
    const val HOST = "lau.montana.quest"
    const val BASE = "https://" + HOST + "/b/v1"
    /** One road as the service names it: its number, its title in the person's language, its glyph. */
    class Road(val channel: Int, val title: String, val glyph: String)
    /**
     * The start's answer: the nonce the outer link carries (no secret there) and the claim this phone alone holds -- kept in this
     * wait's memory only, never written, never logged -- and sent with every wait: the confirmation goes only to the claim.
     */
    class Started(val nonce: String, val claim: String, val link: String, val expiresMs: Long)
    sealed class Wait {
        class Confirmed(val attest: ByteArray) : Wait()
        object Again : Wait()
        object Expired : Wait()
        object Failed : Wait()
    }

    /** The wait now held, let go when the person comes back (cameBack); one at a time -- the number's door has one walk. */
    @Volatile private var held: HttpURLConnection? = null
    @Volatile private var letGo = false

    private fun call(method: String, path: String, body: JSONObject?, timeout: Int, hold: Boolean = false): Pair<Int, ByteArray?> = try {
        val conn = URL(BASE + path).openConnection() as HttpURLConnection
        if (hold) held = conn
        conn.requestMethod = method
        conn.useCaches = false
        conn.connectTimeout = 10_000; conn.readTimeout = timeout
        if (body != null) {
            val raw = body.toString().toByteArray(Charsets.UTF_8)
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setFixedLengthStreamingMode(raw.size)
            conn.outputStream.use { it.write(raw) }
        }
        val code = conn.responseCode
        val bytes = if (code == 200) conn.inputStream.use { it.readBytes() } else null
        conn.disconnect()
        code to bytes
    } catch (e: Exception) { Log.w("Montana", "biz service: " + e.javaClass.simpleName); -1 to null
    } finally { if (hold) held = null }

    /**
     * The person came back from the outer service (iOS MTBizPhoneFlow.cameBack): a wait held across the app's sleep may hang on a
     * connection that died with it, so it is let go and asked again at once, not after its 35 seconds; the service keeps the
     * answer for the claim until the term.
     */
    fun cameBack() {
        val c = held ?: return
        letGo = true
        runCatching { c.disconnect() }
    }

    /** The language the service's titles are asked in: the app's own (en, ru, zh-Hans), as iOS names it. */
    private fun lang(): String {
        val l = Book.ctx.resources.configuration.locales[0].language.lowercase()
        return when { l.startsWith("ru") -> "ru"; l.startsWith("zh") -> "zh-Hans"; else -> "en" }
    }

    /** The roads the service keeps now; none when it does not answer. */
    fun roads(): List<Road> {
        val (code, bytes) = call("GET", "/health", null, 10_000)
        if (code != 200 || bytes == null) return emptyList()
        val o = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull() ?: return emptyList()
        if (!o.optBoolean("ok")) return emptyList()
        val a = o.optJSONArray("paths") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { p ->
            val n = p.optInt("channel")
            val t = p.optJSONObject("title")?.let { m -> m.optString(lang()).ifEmpty { m.optString("en") }.ifEmpty { m.keys().asSequence().firstOrNull()?.let(m::optString) ?: "" } }
                ?: p.optString("title")
            if (n <= 0 || t.isEmpty()) null else Road(n, t, p.optString("glyph"))
        }
    }

    /** The confirmation begins: the number, the key it is for (SHA-256 of the key, hex), the road. */
    fun start(e164: String, subject: String, channel: Int): Started? {
        val (code, bytes) = call("POST", "/phone/start", JSONObject().put("e164", e164).put("subject", subject).put("channel", channel), 15_000)
        if (code != 200 || bytes == null) return null
        val o = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull() ?: return null
        val nonce = o.optString("nonce"); val claim = o.optString("claim"); val link = o.optString("link")
        if (nonce.isEmpty() || claim.isEmpty() || link.isEmpty()) return null   // a start without its claim is no start (iOS: Started.claim)
        return Started(nonce, claim, link, o.optLong("expires_ms"))
    }

    /**
     * One long wait (the service holds it 25 seconds): the confirmation, «not yet», or the term ran out. Only the claim takes the
     * confirmation away: the nonce alone, as the outer service saw it in the link, is answered «gone» (410).
     */
    fun wait(nonce: String, claim: String): Wait {
        letGo = false
        val (code, bytes) = call("GET", "/phone/wait?nonce=" + java.net.URLEncoder.encode(nonce, "UTF-8") +
            "&claim=" + java.net.URLEncoder.encode(claim, "UTF-8"), null, 35_000, hold = true)
        if (code == -1 && letGo) return Wait.Again   // let go by the person's return: asked again at once, without the pause
        return when (code) {
            200 -> bytes?.let { attestation(it) }?.let { Wait.Confirmed(it) } ?: Wait.Failed
            204 -> Wait.Again
            410 -> Wait.Expired
            else -> Wait.Failed
        }
    }

    /** The confirmation's bytes, whichever way the answer carries them: raw, hex, or hex under «attest» in JSON. */
    fun attestation(d: ByteArray): ByteArray? {
        if (d.size >= 4 && String(d, 0, 4, Charsets.US_ASCII) == "MTBA") return d
        val text = String(d, Charsets.UTF_8).trim()
        runCatching { JSONObject(text).optString("attest") }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return Biz.unhex(it.lowercase()) }
        return Biz.unhex(text.lowercase())
    }
}

/**
 * THE NUMBER CONFIRMED ON THIS PHONE (iOS MTBizPhone): the service's signed confirmation, kept sealed in the app's own vault;
 * the core reads it (the number, the key, the road, the moment) only once the service's pinned key proves the signature.
 */
object BizPhone {
    private const val FILE = "biz.phone.mtba"
    class Opened(val e164: String, val subject: String, val channel: Int, val atMs: Long)
    fun attestation(): ByteArray? = DeviceVault.get(FILE)
    /** The core reads a confirmation under the service's pinned key -- on the Business's thread alone (Biz). */
    internal fun open(a: ByteArray): Opened? = MtBusiness.nativeAttestOpen(a)?.let { t ->
        runCatching { JSONObject(t) }.getOrNull()?.let { Opened(it.optString("e164"), it.optString("subject"), it.optInt("channel"), it.optLong("at_ms")) }
    }
    /** The key a confirmation is given to: SHA-256 of the person's public key, hex. */
    fun subject(pub: ByteArray): String = Biz.hex(Wire.sha(pub))
    /** The confirmation laid in the vault, once Biz.keepPhone proved it for this key and this number. */
    internal fun store(a: ByteArray): Boolean = DeviceVault.set(FILE, a)
    internal fun forget() = DeviceVault.delete(FILE)
}

/**
 * THE COUNTRIES A NUMBER IS CHOSEN BY (iOS MTBizCountries): the region and its calling code (ITU-T E.164), written once; the
 * name is the system's own in the person's language, the flag the region's two letters.
 */
object BizCountries {
    class Country(val region: String, val code: String)
    private const val TABLE = "US 1,CA 1,RU 7,KZ 7,EG 20,ZA 27,GR 30,NL 31,BE 32,FR 33,ES 34,HU 36,IT 39,RO 40,CH 41,AT 43,GB 44,DK 45,SE 46,NO 47,PL 48,DE 49,PE 51,MX 52,CU 53,AR 54,BR 55,CL 56,CO 57,VE 58,MY 60,AU 61,ID 62,PH 63,NZ 64,SG 65,TH 66,JP 81,KR 82,VN 84,CN 86,TR 90,IN 91,PK 92,AF 93,LK 94,MM 95,IR 98,MA 212,DZ 213,TN 216,LY 218,SN 221,CI 225,GH 233,NG 234,CM 237,ET 251,KE 254,TZ 255,UG 256,ZW 263,PT 351,LU 352,IE 353,IS 354,AL 355,MT 356,CY 357,FI 358,BG 359,LT 370,LV 371,EE 372,MD 373,AM 374,BY 375,AD 376,MC 377,UA 380,RS 381,ME 382,HR 385,SI 386,BA 387,MK 389,CZ 420,SK 421,LI 423,GT 502,SV 503,HN 504,NI 505,CR 506,PA 507,BO 591,EC 593,PY 595,UY 598,HK 852,MO 853,KH 855,LA 856,BD 880,TW 886,MV 960,LB 961,JO 962,SY 963,IQ 964,KW 965,SA 966,YE 967,OM 968,AE 971,IL 972,BH 973,QA 974,MN 976,NP 977,TJ 992,TM 993,AZ 994,GE 995,KG 996,UZ 998"   // NOT-UI: regions and their calling codes
    val all: List<Country> = TABLE.split(',').mapNotNull { row -> row.split(' ').takeIf { it.size == 2 }?.let { Country(it[0], it[1]) } }
    fun initial(c: Context): Country {
        val r = c.resources.configuration.locales[0].country.uppercase().ifEmpty { "US" }
        return all.firstOrNull { it.region == r } ?: all[0]
    }
    fun name(c: Context, k: Country): String = java.util.Locale("", k.region).getDisplayCountry(c.resources.configuration.locales[0]).ifEmpty { k.region }
    fun flag(k: Country): String = String(k.region.map { Character.toChars(127_397 + it.code) }.flatMap { it.toList() }.toCharArray())
    /**
     * The number in the one form the service and the core accept: «+», the country's code and the national digits (a leading
     * trunk zero dropped, and Russia's and Kazakhstan's trunk 8); a number typed with its own «+» is taken as written.
     */
    fun e164(k: Country, typed: String): String? {
        val digits = typed.filter { it in '0'..'9' }
        val full = if (typed.trim().startsWith("+")) digits else {
            var n = digits.trimStart('0')
            if (k.code == "7" && n.length == 11 && n.startsWith("8")) n = n.substring(1)
            k.code + n
        }
        if (full.length !in 6..15 || full.startsWith("0")) return null
        return "+" + full
    }
}
