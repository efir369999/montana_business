package quest.montana.app

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

// ════════════════════════════════════════════════════════════
// MONTANA BUSINESS: THE PAGES (iOS MTBusinessViews.swift, page for page)
// ════════════════════════════════════════════════════════════
// The platform's own list, its sections and rows on the one-tone plate, the page's ground, the marks of the bar (the cross,
// the check): the Business looks as the Settings do. Every row is a button on all of itself; every word comes from the
// resources. The pages draw the core's view (MtBusiness.nativeView) and nothing computed beside it. What iOS pays in coins
// (the salary due, a bonus, a purchase, coins to a colleague) leaves its coin book as coin letters; this phone holds no book
// of coins yet, so those acts are not offered here, and the page says so.

/** Coins as the wallet writes them (iOS MTBizText.coins): thousands parted by commas, and the coin's ticker. */
object BizText {
    fun coins(n: Long): String {
        val digits = kotlin.math.abs(n).toString()
        val out = StringBuilder()
        digits.forEachIndexed { i, d -> if (i > 0 && (digits.length - i) % 3 == 0) out.append(','); out.append(d) }
        return (if (n < 0) "-" else "") + out + " " + TICKER
    }
    /** NOT-UI: the coin's ticker, a name of its own (iOS MTCoinBook.ticker). */
    const val TICKER = "\$TCM"
    fun day(ms: Long): String = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(ms))
    /**
     * A DUE PERIOD AS THE SYSTEM NAMES DATES, on UTC (iOS MTBizText.period) -- the core's own bounds [from, to) (contract 1.3), no
     * calendar counted here: a month «October 2026», a week its first and last days, a day its date. The kind is the period key's
     * own (period.rs: kind << 28 or index). Null where the core names no bounds (a core before 1.3).
     */
    fun period(c: Context, d: BizDue): String? {
        val from = d.fromMs ?: return null
        val to = d.toMs ?: return null
        if (to <= from) return null
        val loc = c.resources.configuration.locales[0]
        val utc = android.icu.util.TimeZone.getTimeZone("UTC")
        return when (d.periodKey shr 28) {
            3L -> android.icu.text.DateFormat.getInstanceForSkeleton("yLLLL", loc).apply { timeZone = utc }.format(java.util.Date(from))
            // the last instant inside [from, to)
            2L -> android.icu.text.DateIntervalFormat.getInstance("yMMMd", loc).apply { timeZone = utc }.format(android.icu.util.DateInterval(from, to - 1))
            else -> android.icu.text.DateFormat.getInstanceForSkeleton("yMMMd", loc).apply { timeZone = utc }.format(java.util.Date(from))
        }
    }
}

internal fun roleTitle(c: Context, word: String) = BizRole.of(word)?.let { c.getString(it.title) } ?: ""
internal fun periodTitle(c: Context, period: Int) = c.getString(when (period) { 1 -> R.string.biz_period_day; 2 -> R.string.biz_period_week; else -> R.string.biz_period_month })

/**
 * A CLIP THAT CARRIES A SECRET (an invitation's link): marked sensitive, so the system shows no preview of it after the copy
 * (ClipDescription.EXTRA_IS_SENSITIVE, Android 13; the same key read by the keyboards of the versions before). Laid only by
 * the person's touch.
 */
internal fun sensitiveClip(label: String, text: String): ClipData = ClipData.newPlainText(label, text).apply {
    description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
}

/** The organizations' alert (iOS mtBizWord, .alert("Organization") with one OK): the screen names no business (06.10). */
internal fun say(act: MainActivity, word: Int, title: Int = R.string.biz_org) =
    AlertDialog.Builder(act).setTitle(title).setMessage(word).setPositiveButton(R.string.ok, null).show()

/** A size that grows with the person's font (sp): a row's glyph keeps pace with its words. */
internal fun Context.sp(v: Number): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v.toFloat(), resources.displayMetrics).toInt()
internal fun View.sp(v: Number): Int = context.sp(v)

/**
 * THE GLYPH OF A BUSINESS ROW (iOS MTBizGlyph, k0d a040c85f): its side in sp, so it grows with the person's font and the rows
 * stay aligned at every size. A glyph that says what the row's words do not carries its word for TalkBack ([said], from the
 * resources); a glyph beside words that say it is hidden from TalkBack.
 */
internal fun Context.bizGlyph(res: Int, tint: Int, sizeSp: Int = 20, said: Int? = null): ImageView = ImageView(this).apply {
    setImageResource(res)
    imageTintList = ColorStateList.valueOf(tint)
    layoutParams = LinearLayout.LayoutParams(sp(sizeSp), sp(sizeSp))
    if (said != null) contentDescription = getString(said) else importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
}

/**
 * A ROW READ WHOLE (iOS accessibilityElement(children: .combine)): TalkBack stops on the row once and reads its words, its
 * state and its sum together.
 */
internal fun <T : View> T.bizWhole(): T = apply {
    if (Build.VERSION.SDK_INT >= 28) isScreenReaderFocusable = true else isFocusable = true
}

/** A person's face in a Business row (iOS MTBizFace): the name beside it speaks, so the face's letter is not read twice. */
internal fun Context.bizFace(name: String, sizeDp: Int): View =
    avatar(null, name, sizeDp).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }

/** A row of the Business's lists (iOS MTBizRowLabel): the glyph, the word; the whole row is the target. */
internal fun Context.bizRow(glyph: Int, title: String, chevron: Boolean = false, words: Int = Color.WHITE, detail: String? = null,
                             onTap: () -> Unit): View = hstack {
    setPadding(dp(16), dp(10), dp(14), dp(10)); minimumHeight = dp(48); bizWhole()
    addView(bizGlyph(glyph, Color.WHITE, 20), lp(sp(20), sp(20)).apply { marginEnd = dp(16) })
    addView(text(title, 16f, words), lp(0, WRAP, 1f))
    if (detail != null) addView(text(detail, 15f, MT.gray), lp(WRAP, WRAP).apply { marginStart = dp(8) })   // USER-DATA: a count the row stands for
    if (chevron) addView(bizGlyph(R.drawable.ic_chevron_right, Color.rgb(89, 89, 89), 18), lp(sp(18), sp(18)).apply { marginStart = dp(6) })
    pressable(onTap)
}

/**
 * AN ACT THE CORE WOULD REFUSE THIS PERSON NOW (iOS MTBizWhyNot, point 0, «no dead buttons»): no button in its place, the reason
 * instead -- one owner of the line, the glyph white (info.circle) and the words secondary, as tall as the row it stands for.
 */
internal fun Context.bizWhyNot(words: Int): View = hstack {
    setPadding(dp(16), dp(10), dp(14), dp(10)); minimumHeight = dp(48); bizWhole()
    addView(bizGlyph(R.drawable.ic_info, Color.WHITE, 13), lp(sp(13), sp(13)).apply { marginEnd = dp(6) })
    addView(text(getString(words), 12f, MT.gray), lp(0, WRAP, 1f))
}

/** A line that says, not does: a glyph or none, the words, the value on the right. */
internal fun Context.bizLine(glyph: Int?, tint: Int, title: String, value: String = "", titleColor: Int = Color.WHITE): View = hstack {
    setPadding(dp(16), dp(10), dp(14), dp(10)); minimumHeight = dp(48); bizWhole()
    if (glyph != null) addView(bizGlyph(glyph, tint, 20), lp(sp(20), sp(20)).apply { marginEnd = dp(16) })
    addView(text(title, 16f, titleColor), lp(0, WRAP, 1f))
    // the value wraps within half the row, so a long name never squeezes the words beside it to nothing
    if (value.isNotEmpty()) addView(text(value, 15f, MT.gray).apply { gravity = Gravity.END; maxWidth = resources.displayMetrics.widthPixels / 2 },
        lp(WRAP, WRAP).apply { marginStart = dp(8) })
}

/** A choice of the page (iOS Picker in a List): the words, the chosen value, the chevron; the system's list chooses. */
internal fun Context.pickerRow(title: String, value: String, onTap: () -> Unit): View = hstack {
    setPadding(dp(16), dp(10), dp(14), dp(10)); minimumHeight = dp(48); bizWhole()
    addView(text(title, 16f), lp(0, WRAP, 1f))
    addView(text(value, 16f, MT.gray).apply { gravity = Gravity.END; maxWidth = resources.displayMetrics.widthPixels / 2 }, lp(WRAP, WRAP).apply { marginStart = dp(8) })
    addView(bizGlyph(R.drawable.ic_chevron_down, MT.gray, 14), lp(sp(14), sp(14)).apply { marginStart = dp(6) })
    pressable(onTap)
}

/**
 * THE SHEET'S FIRST FIELD TAKES THE KEYBOARD AT ONCE (iOS mtBizFirstField, k0f), as the platform's own «New folder» does: no
 * touch to begin typing. The focus waits for the page to settle (0.4 s, as iOS); where the sheet asks one line, the keyboard's
 * Done does what the checkmark does ([submit]).
 */
internal fun EditText.bizFirstField(submit: (() -> Unit)? = null): EditText = apply {
    if (submit != null) {
        imeOptions = EditorInfo.IME_ACTION_DONE
        setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_DONE) { submit(); true } else false }
    }
    var asked = false
    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            if (asked) return
            asked = true
            postDelayed({
                if (isAttachedToWindow && requestFocus()) context.getSystemService(InputMethodManager::class.java)?.showSoftInput(this@bizFirstField, InputMethodManager.SHOW_IMPLICIT)
            }, 400)
        }
        override fun onViewDetachedFromWindow(v: View) {}
    })
}

/** One field of a sheet (iOS TextField in a List): the platform's own field on the row's plate. */
internal fun Context.bizField(hint: Int, digits: Boolean = false, initial: String = ""): EditText = EditText(this).apply {
    setText(initial); setSelection(initial.length)
    setHint(hint); setHintTextColor(MT.gray); setTextColor(Color.WHITE); textSize = 16f
    isSingleLine = true
    background = null
    setPadding(dp(16), dp(12), dp(16), dp(12)); minimumHeight = dp(48)
    inputType = if (digits) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
}

/** The system's list of one choice (the Picker's own menu). */
internal fun choose(act: MainActivity, title: String, words: List<String>, at: Int, done: (Int) -> Unit) {
    AlertDialog.Builder(act).setTitle(title)
        .setSingleChoiceItems(words.toTypedArray(), at) { d, i -> d.dismiss(); done(i) }
        .setNegativeButton(R.string.cancel, null).show()
}

/**
 * A PAGE OF THE BUSINESS (iOS NavigationStack and List on the page's ground): the bar with its marks — the back chevron or the
 * cross at the lead, the check at the end of a sheet — the title in the middle, the list that scrolls. [head] stands between
 * the bar and the list and does not scroll (iOS .searchable in the navigation bar's drawer, displayMode .always).
 */
internal class BizSheet(act: MainActivity, title: String, onLead: () -> Unit, cross: Boolean = false, head: View? = null,
                        private val done: (() -> Unit)? = null) {
    /** What the checkmark does, asked from the keyboard's Done too -- only while the checkmark stands ready. */
    fun submit() { if (doneMark?.isEnabled != false) done?.invoke() }
    /**
     * The checkmark stands dimmed until the sheet holds what it asks (iOS MTBizSheet ready, k0f), as the platform's own «Add»
     * does: a touch on it never meets a refusal for an empty name or a sum of nothing.
     */
    fun readyWhen(vararg fields: EditText, ready: () -> Boolean) {
        val mark = doneMark ?: return
        fun lay() { val on = ready(); mark.isEnabled = on; mark.alpha = if (on) 1f else 0.4f }
        fields.forEach { f ->
            f.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
                override fun afterTextChanged(s: Editable?) = lay()
            })
        }
        lay()
    }
    val title: TextView
    val doneMark: View?
    lateinit var body: LinearLayout
    val page: View

    init {
        val c: Context = act
        this.title = c.text(title, 17f, Color.WHITE, bold = true, center = true).apply { singleLineEllipsis() }
        doneMark = done?.let { d ->
            c.icon(R.drawable.ic_check, Color.WHITE).apply { setPadding(c.dp(12), c.dp(12), c.dp(12), c.dp(12)); contentDescription = c.getString(R.string.biz_done); pressable(d) }
        }
        val bar = FrameLayout(c).apply {
            setPadding(c.dp(8), 0, c.dp(8), 0)
            addView(c.icon(if (cross) R.drawable.ic_close else R.drawable.ic_arrow_back_ios_new, Color.WHITE).apply {
                setPadding(c.dp(12), c.dp(12), c.dp(12), c.dp(12)); pressable(onLead)
                contentDescription = c.getString(if (cross) R.string.cancel else R.string.back)
            }, FrameLayout.LayoutParams(c.dp(48), c.dp(48), Gravity.CENTER_VERTICAL or Gravity.START))
            addView(this@BizSheet.title, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER).apply { leftMargin = c.dp(56); rightMargin = c.dp(56) })
            doneMark?.let { addView(it, FrameLayout.LayoutParams(c.dp(48), c.dp(48), Gravity.CENTER_VERTICAL or Gravity.END)) }
        }
        page = FrameLayout(c).apply {
            setBackgroundColor(Color.BLACK)
            addView(c.chatGround(ChatWall.PAGE), FrameLayout.LayoutParams(MATCH, MATCH))   // the page's ground (iOS montanaPageGround)
            addView(c.vstack(Gravity.NO_GRAVITY) {
                addView(bar, lp(MATCH, c.dp(52)))
                if (head != null) addView(head, lp())
                addView(ScrollView(c).apply {
                    addView(c.vstack(Gravity.NO_GRAVITY) { setPadding(c.dp(16), 0, c.dp(16), c.dp(32)); body = this })
                }, lp(MATCH, 0, 1f))
            }, FrameLayout.LayoutParams(MATCH, MATCH))
        }
    }
}

/**
 * A PAGE THAT FOLLOWS THE ORGANIZATIONS (iOS @ObservedObject MTBusiness.shared): drawn again whenever a new picture lands
 * (Biz.listen) or the page's own choice changes (redraw); what it shows is read off the main thread from the picture, the rows
 * laid on it. Opened, it asks the Business's thread to draw every view again (the salary due moves with the clock).
 */
internal class Live<T>(val act: MainActivity, val sheet: BizSheet, val load: () -> T, val fill: LinearLayout.(T) -> Unit) {
    val redraw: () -> Unit = {
        act.background {
            val t = load()
            act.onMain { sheet.body.removeAllViews(); sheet.body.fill(t) }
        }
    }
    init {
        sheet.page.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { Biz.listen(redraw); redraw(); Biz.redraw() }
            override fun onViewDetachedFromWindow(v: View) { Biz.unlisten(redraw) }
        })
    }
}

/** One record of mine, on the Business's thread (iOS MTBusiness.shared.write); the answer on the main thread. */
@Suppress("UNUSED_PARAMETER")
internal fun bizWrite(act: MainActivity, org: String?, cmd: JSONObject, done: (String?) -> Unit = {}) = Biz.write(org, cmd, done)

/** A record whose refusal is said aloud (iOS: «The organization did not take this»). */
internal fun bizAct(act: MainActivity, org: String, cmd: JSONObject) = bizWrite(act, org, cmd) { if (it == null) say(act, R.string.biz_refused) }

// ─────────────────────────── the Business page ───────────────────────────

/**
 * THE BUSINESS PAGE (iOS MTBusinessPage, from the drawer): the number confirmed on this phone, the person's organizations, the
 * joins on their way, and the doors to found one or to join by a scanned code or a copied link.
 */
fun bizPage(act: MainActivity, onClose: () -> Unit): View {
    class Seen(val phone: BizPhone.Opened?, val orgs: List<BizView>, val joins: List<Biz.BizJoining>, val views: Map<String, BizView?>,
               val left: Map<String, String>, val greet: String?, val opening: Int)
    val sheet = BizSheet(act, act.getString(R.string.biz_orgs), onClose, cross = true)
    var greeting = false
    act.background { BizExport.sweepMedia(act); BizExport.sweepStale(act) }   // builds 1-7's media folder; exports handed out long ago
    Live(act, sheet, {
        val joins = Biz.joins()
        val orgs = Biz.orgs()
        Seen(Biz.phone(), orgs, joins, joins.filter { it.move }.associate { it.org to Biz.view(it.org) }, Biz.left(),
            Biz.greet().firstOrNull { g -> orgs.any { it.org == g } }, Biz.opening)
    }) { s ->
        val c = act
        val p = s.phone
        // USER-DATA: the person's own number, as the service confirmed it
        section(c.getString(R.string.biz_phone_word), null,
            if (p != null) c.bizLine(R.drawable.ic_set_check_circle, Color.WHITE, c.getString(R.string.biz_confirmed), p.e164)
            else c.bizRow(R.drawable.ic_peer_phone, c.getString(R.string.biz_confirm_number)) { act.push { phonePage(act, it) } })
        val rows = mutableListOf<View>()
        s.orgs.forEach { v -> rows += orgRow(c, v) { act.push { orgPage(act, v.org, it) } } }
        if (0 < s.opening) rows += waitRow(c, R.string.biz_opening_invite)
        s.joins.forEach { j ->
            val roster = s.views[j.org]?.takeIf { it.org.isNotEmpty() }
            rows += when {
                // a move: the roster came -- choose yourself; chosen -- waiting for the administrator's touch (iOS MTBizMoveRow)
                j.move && j.mine == null && roster != null ->
                    c.bizRow(R.drawable.ic_person, c.getString(R.string.biz_choose_self_in, roster.name), chevron = true) { act.push { chooseSelfPage(act, j.org, it) } }
                j.move && j.mine != null -> waitRow(c, R.string.biz_move_waiting)
                // the roster comes from the inviter's phone: the row says whom it waits for (K.6)
                else -> waitRow(c, R.string.biz_joining, R.string.biz_from_inviter)
            }
        }
        s.left.toSortedMap().forEach { (org, name) -> rows += leftRow(act, org, name) }
        rows += c.bizRow(R.drawable.ic_plus, c.getString(R.string.biz_create_org)) { act.push { foundPage(act, it) } }
        rows += c.bizRow(R.drawable.ic_qr_scan, c.getString(R.string.biz_scan_invite)) { scanJoin(act) }
        rows += c.bizRow(R.drawable.ic_content_copy, c.getString(R.string.biz_join_copied)) { joinCopied(act) }
        rows += c.bizRow(R.drawable.ic_restore, c.getString(R.string.biz_old_phone)) { act.push { movePage(act, it) } }
        // the newcomer is told what these rows are for (iOS k0f): create, or join by a code or a link; no header -- the page's
        // own title already says Organizations (iOS bd6c2415)
        section(null, if (s.orgs.isEmpty() && s.joins.isEmpty() && s.opening == 0) c.getString(R.string.biz_newcomer_footer) else null,
            *rows.toTypedArray())
        // THE FIRST DAY shows once after a join, over the Business page (8.1, iOS MTBizFirstDaySheet)
        val g = s.greet
        if (g != null && !greeting) {
            greeting = true
            act.push { close -> firstDayPage(act, g) { Biz.greeted(g); greeting = false; close() } }
        }
    }
    return sheet.page
}

/**
 * A ROW THAT WAITS FOR ANOTHER PHONE OR THE NETWORK (K.6, iOS MTBizWaitRow): the platform's spinner, what is awaited, and -- where
 * the wait hangs on somebody else -- whom it waits for; the page around it stays at work with what this phone holds.
 */
internal fun waitRow(c: Context, title: Int, detail: Int? = null): View = c.hstack {
    setPadding(dp(16), dp(10), dp(14), dp(10)); minimumHeight = dp(48); bizWhole()
    addView(ProgressBar(c), lp(sp(20), sp(20)).apply { marginEnd = dp(16) })
    addView(c.vstack(Gravity.NO_GRAVITY) {
        addView(c.text(c.getString(title), 16f, MT.gray), lp())
        if (detail != null) addView(c.text(c.getString(detail), 12f, MT.gray), lp())
    }, lp(0, WRAP, 1f))
}

/** An organization in the list (iOS MTBizOrgRow): its name and the person's role in it. */
private fun orgRow(c: Context, v: BizView, onTap: () -> Unit): View = c.hstack {
    setPadding(dp(16), dp(10), dp(14), dp(10)); minimumHeight = dp(48); bizWhole()
    addView(c.bizGlyph(R.drawable.ic_organization, Color.WHITE, 20), lp(sp(20), sp(20)).apply { marginEnd = dp(16) })
    addView(c.vstack(Gravity.NO_GRAVITY) {
        addView(c.text(v.name, 16f), lp())   // USER-DATA: the organization's name, as its owner wrote it
        addView(c.text(roleTitle(c, v.role), 12f, MT.gray), lp())
    }, lp(0, WRAP, 1f))
    addView(c.bizGlyph(R.drawable.ic_chevron_right, Color.rgb(89, 89, 89), 18), lp(sp(18), sp(18)).apply { marginStart = dp(6) })
    pressable(onTap)
}

/** The number's own page (iOS MTBizPhoneSheet): the door of the sign-in, for a person who came in by the Montana road. */
internal fun phonePage(act: MainActivity, onClose: () -> Unit): View {
    val sheet = BizSheet(act, act.getString(R.string.biz_phone_word), onClose, cross = true)
    sheet.body.apply {
        gap(18)
        addView(act.text(act.getString(R.string.biz_phone_footer), 15f, MT.gray, center = true), lp())
        gap(18)
        addView(BizPhoneDoor(act) { onClose() }.view, lp())
    }
    return sheet.page
}

/** FOUNDING AN ORGANIZATION (iOS MTBizFoundSheet): its name; the owner's own name and card ride the Genesis record. */
private fun foundPage(act: MainActivity, onClose: () -> Unit): View {
    val name = act.bizField(R.string.biz_org_name)
    var busy = false
    val sheet = BizSheet(act, act.getString(R.string.biz_new_org), onClose, cross = true) {
        val n = name.text.toString().trim()
        if (n.isNotEmpty() && !busy) {
            busy = true
            act.background {
                val card = Biz.myCard(act)
                Biz.found(n.take(256), card) { org -> busy = false; if (org != null) onClose() else say(act, R.string.biz_found_failed, R.string.biz_new_org) }
            }
        }
    }
    name.bizFirstField { sheet.submit() }
    sheet.readyWhen(name) { name.text.isNotBlank() }
    sheet.body.section(null, act.getString(R.string.biz_found_footer), name)
    return sheet.page
}

/** THE INVITATION'S CODE, READ BY THE APP'S OWN SCANNER (iOS MTBizScanSheet): a code that is not an organization's link is passed over. */
internal fun scanJoin(act: MainActivity, move: Boolean = false, then: () -> Unit = {}) = act.push { close ->
    lateinit var page: View
    page = scannerPage(act, close, act.getString(R.string.biz_scan_hint)) { text ->
        val t = text.trim()
        if (Biz.isJoin(t)) { act.onMain { bizJoin(act, t, move = move); then() }; true }
        else {
            // a code that is not an organization's invitation is said in the scanner's own line; the scanner keeps looking
            act.onMain { page.findViewWithTag<android.widget.TextView>(SCAN_HINT_TAG)?.text = act.getString(R.string.biz_link_unreadable) }
            false
        }
    }
    page
}

internal fun joinCopied(act: MainActivity, move: Boolean = false, then: () -> Unit = {}) {
    val clip = act.getSystemService(ClipboardManager::class.java).primaryClip
    val t = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(act)?.toString()?.trim().orEmpty()
    if (!Biz.isJoin(t)) { say(act, R.string.biz_copy_first); return }
    bizJoin(act, t, move = move); then()
    // ONCE READ, THE LINK'S SECRET LEAVES THE CLIPBOARD (iOS acceptCopied, 200308ac): a readable invitation only, and only while
    // the clipboard still holds what was read
    if (Biz.invitation(t) != null) forgetClip(act, t)
}

/**
 * AN INVITATION'S SECRET LEAVES THE CLIPBOARD (point 0, safety; iOS acceptCopied and MTBizInviteSheet.copy) -- only while the
 * clipboard still holds that very link: a copy another app made since is never touched. True: taken away; false: the clipboard
 * holds something else, nothing to take; null: this window has no focus, and the system lets no unfocused app read the
 * clipboard (Android 10) -- asked again once it has.
 */
internal fun forgetClip(act: MainActivity, link: String): Boolean? {
    val board = act.getSystemService(ClipboardManager::class.java) ?: return false
    if (!act.hasWindowFocus()) return null
    val held = board.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(act)?.toString()?.trim()
    if (held != link.trim()) return false
    if (Build.VERSION.SDK_INT >= 28) board.clearPrimaryClip() else board.setPrimaryClip(ClipData.newPlainText("", ""))
    return true
}

/**
 * THE INVITATION LINKS «Copy link» LAID ON THE CLIPBOARD (iOS MTBizInviteSheet.copy: ten minutes, long enough to paste it into a
 * chat, not long enough to lie there for whichever app reads the clipboard next week): each leaves it ten minutes after the
 * copy -- at the moment itself when the app has the focus then, else the first time the app has it again. Main thread.
 */
internal object CopiedLinks {
    private const val TERM_MS = 600_000L
    private val due = mutableMapOf<String, Long>()
    private var watched = java.lang.ref.WeakReference<View>(null)
    fun laid(act: MainActivity, link: String) {
        due[link] = System.currentTimeMillis() + TERM_MS
        val decor = act.window.decorView
        decor.postDelayed({ sweep(act) }, TERM_MS)
        if (watched.get() !== decor) {
            watched = java.lang.ref.WeakReference(decor)
            decor.viewTreeObserver.addOnWindowFocusChangeListener { has -> if (has) sweep(act) }
        }
    }
    private fun sweep(act: MainActivity) {
        val now = System.currentTimeMillis()
        for ((link, at) in due.toList()) if (at <= now && forgetClip(act, link) != null) due.remove(link)
    }
}

/**
 * A JOINING LINK OR ITS CODE (iOS MTBusiness.accept): the inviter is met by the card the link carries and asked for the roster;
 * the Join is written the moment it comes. [open] — the link came from outside, and the Business page opens over the app.
 */
fun bizJoin(act: MainActivity, link: String, open: Boolean = false, move: Boolean = false) {
    if (open) act.push { bizPage(act, it) }   // at once: the page says «Opening the invitation…» while the card is met (K.6)
    Biz.accept(act, link, move) { r ->
        when (r) {
            Biz.Accepted.Unreadable -> say(act, R.string.biz_link_unreadable)
            Biz.Accepted.NotOpened -> say(act, R.string.biz_link_not_opened)
            else -> {}
        }
    }
}

// ─────────────────────────── an organization ───────────────────────────

/**
 * AN ORGANIZATION'S PAGE (iOS MTBizOrgPage): its people (every row opens the person's card), the invitation, the salary due,
 * the person's own payouts, the departments, the open invitations and the shop — each section only where the person's role
 * may act in it (the core's «can»).
 */
private fun orgPage(act: MainActivity, org: String, onBack: () -> Unit): View {
    val sheet = BizSheet(act, "", onBack)
    Live(act, sheet, { Biz.view(org) }) { v ->
        v ?: return@Live
        sheet.title.text = v.name   // USER-DATA: the organization's name
        val c = act
        val chief = Biz.boss(v.role)
        // PEOPLE
        val people = v.members.filter { it.status != "removed" }.map { m -> memberRow(c, v, m) { act.push { memberPage(act, org, m.id, it) } } }.toMutableList()
        // fold.rs Invite: a manager invites into their own standing department only -- without one, the honest line instead
        if (v.invitableRoles.isNotEmpty()) people += c.bizRow(R.drawable.ic_plus, c.getString(R.string.biz_invite)) { act.push { invitePage(act, org, it) } }
        else if (v.may("invite")) people += c.bizWhyNot(R.string.biz_invite_no_dept)
        section(c.getString(R.string.biz_people), if (v.waiting > 0) c.getString(R.string.biz_waiting_records) else null, *people.toTypedArray())
        // THE CHATS (iOS MTBizChatsSection), THE NEW PHONES (7.3), THE FIRST DAY (8.1)
        chatsSection(act, org, v)
        movesSection(act, org, v)
        if (v.role != "none" && !chief) section(null, null, c.bizRow(R.drawable.ic_set_bell, c.getString(R.string.biz_first_day)) {
            act.push { close -> firstDayPage(act, org, close) }
        })
        // THE SALARY DUE (an administrator's)
        if (chief && v.salary.isNotEmpty()) {   // a new organization's page is not opened by an empty section (iOS k0f)
            // one row per person (iOS c98dccf2): the sum of their ended periods, how many, and their bounds
            val owed = v.due.map { it.member }.distinct()
            val due = if (owed.isEmpty()) listOf(c.bizLine(null, 0, c.getString(R.string.biz_nothing_due), titleColor = MT.gray))
                // USER-DATA: the employee's name; the periods due to them and their bounds; the coins due for them together
                else owed.map { m -> dueRow(c, v.member(m)?.name ?: "", v.due.filter { it.member == m }) }
            // an empty list says when a period comes due: once it has ended, on UTC boundaries
            section(c.getString(R.string.biz_salary_due), c.getString(if (owed.isEmpty()) R.string.biz_due_when else R.string.biz_coins_android), *due.toTypedArray())
        }
        // THE PAYROLL OF A PERIOD (10, iOS MTBizPayrollSection)
        // MY PAYOUTS (iOS mine): the newest first, in the receiver's own words
        val mine = v.pay.filter { it.member == v.me }.sortedByDescending { it.atMs ?: 0L }
        if (mine.isNotEmpty()) section(c.getString(R.string.biz_my_payouts), null, *mine.map { payRow(c, it, received = true) }.toTypedArray())
        // SHIFTS (9.1, iOS MTBizShiftsSection)
        shiftsSection(act, org, v)
        // SUPPLY (iOS supply): the orders, the stock, the catalogue
        supplySection(act, org, v)
        // THE DEPARTMENTS
        if (v.may("dept")) {
            val rows = v.depts.map { d ->
                // USER-DATA: a department's name; how many people it holds
                c.bizLine(R.drawable.ic_hub, Color.WHITE, d.name, v.members.count { it.dept == d.id && it.status == "active" }.toString()).apply {
                    pressable { confirmAct(act, d.name, R.string.biz_archive) { bizAct(act, org, BizCommand.dept(d.id, d.name, true)) } }
                }
            }.toMutableList()
            rows += c.bizRow(R.drawable.ic_plus, c.getString(R.string.biz_new_dept)) {
                act.push { namePage(act, R.string.biz_new_dept, R.string.biz_dept_name, it) { n, done -> Biz.freshTag()?.let { tag -> Biz.write(org, BizCommand.dept(tag, n, false), done) } ?: done(null) } }
            }
            section(c.getString(R.string.biz_depts), null, *rows.toTypedArray())
        }
        // THE OPEN INVITATIONS
        if (v.may("revoke")) {
            val open = v.invites.filter { it.state == "open" }
            if (open.isNotEmpty()) section(c.getString(R.string.biz_open_invites), null, *open.map { i ->
                c.bizLine(R.drawable.ic_email, Color.WHITE, roleTitle(c, i.role), c.getString(R.string.biz_until, BizText.day(i.expiresMs))).apply {
                    pressable { confirmAct(act, roleTitle(c, i.role), R.string.biz_revoke) { bizAct(act, org, BizCommand.revoke(i.id)) } }
                }
            }.toTypedArray())
        }
        // THE SHOP
        val offers = v.offers.filter { it.active || v.may("offer") }
        if (offers.isNotEmpty() || v.may("offer")) {
            val rows = offers.map { o -> offerRow(c, o).apply {
                // a withdrawn item takes no second withdrawal; offering it again would drop its count (stock 0 reads as no count)
                if (v.may("offer") && o.active) pressable { confirmAct(act, o.title, R.string.biz_withdraw) { bizAct(act, org, BizCommand.offer(o.id, o.title, o.price, o.stock, false)) } }
            } }.toMutableList<View>()
            if (v.may("offer")) rows += c.bizRow(R.drawable.ic_plus, c.getString(R.string.biz_new_item)) { act.push { offerPage(act, org, it) } }
            if (v.may("fulfil")) v.redeems.filter { it.state != "fulfilled" }.forEach { r ->
                // USER-DATA: the buyer's name and the item's name
                val what = (v.member(r.member)?.name ?: "") + " · " + (v.offers.firstOrNull { it.id == r.offer }?.title ?: "")
                rows += c.hstack {
                    setPadding(dp(16), dp(10), dp(14), dp(10)); minimumHeight = dp(48); bizWhole()
                    addView(c.bizGlyph(R.drawable.ic_cart, Color.WHITE, 20), lp(sp(20), sp(20)).apply { marginEnd = dp(16) })
                    addView(c.vstack(Gravity.NO_GRAVITY) {
                        addView(c.text(what, 16f), lp())
                        addView(c.text(c.getString(R.string.biz_paid_coins, BizText.coins(r.coins)), 12f, MT.gray), lp())
                    }, lp(0, WRAP, 1f))
                    pressable { confirmAct(act, what, R.string.biz_handed_over) { bizAct(act, org, BizCommand.fulfil(r.record)) } }
                }
            }
            section(c.getString(R.string.biz_shop), if (v.may("redeem") && !chief) c.getString(R.string.biz_coins_android) else null, *rows.toTypedArray())
        }
        // THE JOURNAL (7.4, iOS MTBizJournalSection)
        // THE RECORDS at the foot: the payroll for the administrators and the journal for everyone, one section (iOS k0f)
        section(null, null, *listOfNotNull(if (chief) payrollRow(act, org) else null,
            c.bizRow(R.drawable.ic_set_doc, c.getString(R.string.biz_journal), chevron = true) { act.push { journalPage(act, org, it) } }).toTypedArray())
    }
    return sheet.page
}

/** An act a row holds (iOS swipe action): the system's dialog names it once, the person confirms. */
internal fun confirmAct(act: MainActivity, title: String, word: Int, done: () -> Unit) {
    AlertDialog.Builder(act).setTitle(title)   // USER-DATA: the row's own name
        .setPositiveButton(word) { _, _ -> done() }
        .setNegativeButton(R.string.cancel, null).show()
}

/** An item of the shop (iOS the shop's row): its name, how many are left, its price in coins. */
private fun offerRow(c: Context, o: BizOffer): View = c.hstack {
    setPadding(dp(16), dp(10), dp(14), dp(10)); minimumHeight = dp(48); bizWhole()
    addView(c.bizGlyph(R.drawable.ic_cart, Color.WHITE, 20), lp(sp(20), sp(20)).apply { marginEnd = dp(16) })
    addView(c.vstack(Gravity.NO_GRAVITY) {
        addView(c.text(o.title, 16f, if (o.active) Color.WHITE else MT.gray), lp())   // USER-DATA: the item's name
        if (o.stock > 0) addView(c.text(c.getString(R.string.biz_left, o.stock.toString()), 12f, MT.gray), lp())
    }, lp(0, WRAP, 1f))
    addView(c.text(BizText.coins(o.price), 15f, MT.gray), lp(WRAP, WRAP).apply { marginStart = dp(8) })   // USER-DATA: the price
}

/** A person in the organization's list (iOS MTBizMemberRow): the face, the name, the job title and department, the role, the mark of a confirmed number. */
internal fun memberRow(c: Context, v: BizView, m: BizMember, onTap: () -> Unit): View = c.hstack {
    setPadding(dp(16), dp(8), dp(14), dp(8)); minimumHeight = dp(56); bizWhole()
    addView(c.bizFace(m.name, 40), lp(dp(40), dp(40)))
    gap(12)
    addView(c.vstack(Gravity.NO_GRAVITY) {
        // the name wraps and gives way to the seal: weighted inside a stack only as wide as they are
        addView(c.hstack {
            addView(c.text(m.name, 16f), lp(WRAP, WRAP, 1f))   // USER-DATA: the person's name
            // the state in words for TalkBack (iOS k0d: «Number confirmed»), the seal's shape for the eye
            if (m.phoneConfirmed) addView(c.bizGlyph(R.drawable.ic_set_check_circle, Color.WHITE, 14, said = R.string.biz_confirmed), lp(sp(14), sp(14)).apply { marginStart = dp(4) })
        }, lp(WRAP, WRAP))
        // USER-DATA: the job title and the department, as the administrators wrote them
        addView(c.text(listOf(m.title, v.dept(m.dept)?.name ?: "").filter { it.isNotEmpty() }.joinToString(" · "), 12f, MT.gray), lp())
    }, lp(0, WRAP, 1f))
    addView(c.text(roleTitle(c, m.role), 12f, MT.gray), lp(WRAP, WRAP).apply { marginStart = dp(8) })
    addView(c.bizGlyph(R.drawable.ic_chevron_right, Color.rgb(89, 89, 89), 18), lp(sp(18), sp(18)).apply { marginStart = dp(6) })
    pressable(onTap)
}

/**
 * One person's salary due (iOS MTBizOrgPage.due, contract 1.3): the person; beneath, every period due named by its own UTC
 * bounds -- or, where the core names no bounds and several have ended, how many (the language's own plural); the coins of them
 * all together.
 */
private fun dueRow(c: Context, name: String, ds: List<BizDue>): View = c.hstack {
    setPadding(dp(16), dp(10), dp(14), dp(10)); minimumHeight = dp(48); bizWhole()
    addView(c.vstack(Gravity.NO_GRAVITY) {
        addView(c.text(name, 16f), lp())
        // USER-DATA: the periods due, as the system names dates on UTC
        val named = ds.mapNotNull { BizText.period(c, it) }
        val line = if (named.isNotEmpty() && named.size == ds.size) named.joinToString(", ")
            else if (1 < ds.size) c.resources.getQuantityString(R.plurals.biz_periods, ds.size, ds.size) else ""
        if (line.isNotEmpty()) addView(c.text(line, 12f, MT.gray), lp())
    }, lp(0, WRAP, 1f))
    addView(c.text(BizText.coins(ds.sumOf { it.coins }), 15f, MT.gray), lp(WRAP, WRAP).apply { marginStart = dp(8) })
}

/** One period of a person's time (K.5, iOS MTBizMemberPage.worked): its closed shifts' hours, the confirmed part beneath; never coins. */
private fun worked(c: Context, title: Int, seconds: Long, confirmed: Long): View = c.hstack {
    setPadding(dp(16), dp(10), dp(14), dp(10)); minimumHeight = dp(48); bizWhole()
    addView(c.bizGlyph(R.drawable.ic_restore, Color.WHITE, 20), lp(sp(20), sp(20)).apply { marginEnd = dp(16) })
    addView(c.vstack(Gravity.NO_GRAVITY) {
        addView(c.text(c.getString(title), 16f), lp())
        addView(c.text(c.getString(R.string.biz_time_confirmed, BizSupplyText.hours(c, confirmed)), 12f, MT.gray), lp())
    }, lp(0, WRAP, 1f))
    // USER-DATA: the hours and minutes of the period's closed shifts
    addView(c.text(BizSupplyText.hours(c, seconds), 16f), lp(WRAP, WRAP).apply { marginStart = dp(8) })
}

/** One payout (iOS MTBizPayRow): the coins, the kind, and its state — sent, or confirmed by the receiver's own receipt. */
internal fun payRowOf(c: Context, p: BizPayment, whole: Boolean = true): View = payRow(c, p, whole)
/**
 * [whole] false: the row lies inside a larger one that TalkBack reads as one (the payroll's person, with the moment).
 * [received]: the receiver's own list (My payouts) -- its state in the receiver's words, on its way or received (their own phone
 * took the coins and signed the receipt), never «sent», which is the payer's word (iOS MTBizPayRow.received, 88ce69b2).
 */
private fun payRow(c: Context, p: BizPayment, whole: Boolean = true, received: Boolean = false): View = c.hstack {
    setPadding(dp(16), dp(10), dp(14), dp(10)); minimumHeight = dp(48); if (whole) bizWhole()
    addView(c.bizGlyph(R.drawable.ic_app_wallet, Color.WHITE, 20), lp(sp(20), sp(20)).apply { marginEnd = dp(16) })
    addView(c.vstack(Gravity.NO_GRAVITY) {
        addView(c.text(BizText.coins(p.coins), 16f), lp())   // USER-DATA: the coins of the payout
        // USER-DATA: the day the payout was written (the core's pay[].at_ms), in the system's words (iOS c98dccf2)
        addView(c.text(c.getString(if (p.kind == 2) R.string.biz_bonus else R.string.biz_salary) + (p.atMs?.let { " · " + BizText.day(it) } ?: ""),
            12f, MT.gray), lp())
    }, lp(0, WRAP, 1f))
    val confirmed = p.state == "confirmed"
    // the state by the glyph's shape (point 0): a white glyph, the words secondary
    addView(c.bizGlyph(if (confirmed) R.drawable.ic_set_check_circle else R.drawable.ic_send, Color.WHITE, 14), lp(sp(14), sp(14)).apply { marginEnd = dp(4) })
    addView(c.text(c.getString(when {
        confirmed -> if (received) R.string.biz_pay_received else R.string.biz_pay_confirmed
        else -> if (received) R.string.biz_pay_on_its_way else R.string.biz_pay_sent
    }), 12f, MT.gray))
}

// ─────────────────────────── a person ───────────────────────────

/**
 * A PERSON'S CARD IN THE ORGANIZATION (iOS MTBizMemberPage): who they are in it, and every act the viewer's role allows on them
 * — the role, the department and the job title, the salary, the pair's chat, the removal.
 */
internal fun memberPage(act: MainActivity, org: String, member: String, onBack: () -> Unit): View {
    val sheet = BizSheet(act, "", onBack)
    Live(act, sheet, { Biz.view(org)?.let { v -> v to Biz.pipe(member) } }) { seen ->
        val got = seen ?: return@Live
        val v = got.first
        val m = v.member(member) ?: return@Live
        val c = act
        sheet.title.text = m.name   // USER-DATA: the person's name
        val mine = v.me == member
        // the roles the viewer may give this person (fold.rs Role): the owner every role but the owner's, an administrator those below
        val roles = v.rolesFor(m)
        val head = mutableListOf<View>(c.hstack {
            setPadding(dp(16), dp(12), dp(14), dp(12)); bizWhole()
            addView(c.bizFace(m.name, 56), lp(dp(56), dp(56)))
            gap(14)
            addView(c.vstack(Gravity.NO_GRAVITY) {
                addView(c.text(m.name, 20f, Color.WHITE, bold = true), lp())   // USER-DATA: the person's name, as they wrote it
                addView(c.text(roleTitle(c, m.role), 16f, MT.gray), lp())
                // USER-DATA: the job title and the department
                addView(c.text(listOf(m.title, v.dept(m.dept)?.name ?: "").filter { it.isNotEmpty() }.joinToString(" · "), 12f, MT.gray), lp())
            }, lp(0, WRAP, 1f))
        })
        // the states of a person (iOS MTBizNote): the glyph white, its shape the state, the words secondary
        if (m.phoneConfirmed) head += c.bizLine(R.drawable.ic_set_check_circle, Color.WHITE, c.getString(R.string.biz_confirmed), titleColor = MT.gray)
        if (m.phoneMismatch) head += c.bizLine(R.drawable.ic_gpp_maybe, Color.WHITE, c.getString(R.string.biz_phone_mismatch), titleColor = MT.gray)
        if (m.status == "pending_device") head += c.bizLine(R.drawable.ic_restore, Color.WHITE, c.getString(R.string.biz_pending_device), titleColor = MT.gray)
        section(null, null, *head.toTypedArray())
        // TIME (K.5): closed shifts begun in this UTC week and month, in hours and minutes, the confirmed apart -- seconds, never coins
        v.timeOf(member)?.let { t ->
            section(c.getString(R.string.biz_time), c.getString(R.string.biz_time_footer),
                worked(c, R.string.biz_this_week, t.weekS, t.weekConfirmedS), worked(c, R.string.biz_this_month, t.monthS, t.monthConfirmedS))
        }
        if (roles.isNotEmpty()) section(null, null, c.pickerRow(c.getString(R.string.biz_role), roleTitle(c, m.role)) {
            choose(act, c.getString(R.string.biz_role), roles.map { c.getString(it.title) }, roles.indexOfFirst { it.word == m.role }) { i ->
                bizAct(act, org, BizCommand.role(member, roles[i].number))
            }
        })
        if (v.mayAssign(m)) section(null, null, c.bizRow(R.drawable.ic_badge, c.getString(R.string.biz_dept_and_position)) { act.push { placePage(act, org, member, it) } })
        else assignRefusal(v, m)?.let { section(null, null, c.bizWhyNot(it)) }
        if (Biz.boss(v.role)) {
            val rows = mutableListOf<View>()
            // USER-DATA: the salary in coins and its period
            v.salary.firstOrNull { it.member == member }?.let { s -> rows += c.bizLine(null, 0, c.getString(R.string.biz_salary), BizText.coins(s.coins) + " · " + periodTitle(c, s.period)) }
            if (v.maySalary(m)) rows += c.bizRow(R.drawable.ic_app_wallet, c.getString(R.string.biz_set_salary)) { act.push { salaryPage(act, org, member, it) } }
            v.pay.filter { it.member == member }.forEach { rows += payRow(c, it) }
            section(c.getString(R.string.biz_pay_section), c.getString(R.string.biz_coins_android), *rows.toTypedArray())
        }
        if (mine && v.may("profile")) section(null, c.getString(R.string.biz_renew_footer),
            c.bizRow(R.drawable.ic_restore, c.getString(R.string.biz_renew)) { renew(act, org) })
        val pipe = got.second
        if (!mine && pipe != null) section(null, null,
            c.bizRow(R.drawable.ic_peer_message, c.getString(R.string.biz_open_chat)) { act.push { close -> conversationPage(act, pipe, close) } })
        if (v.mayRemove(m)) section(null, null,
            c.text(c.getString(R.string.biz_remove), 16f, MT.red, center = true).apply {
                setPadding(dp(16), dp(12), dp(16), dp(12)); minimumHeight = dp(48)
                pressable {
                    AlertDialog.Builder(act).setTitle(R.string.biz_remove_q).setMessage(R.string.biz_remove_footer)
                        .setPositiveButton(R.string.biz_remove_short) { _, _ -> bizAct(act, org, BizCommand.remove(member)); onBack() }
                        .setNegativeButton(R.string.cancel, null).show()
                }
            })
    }
    return sheet.page
}

/**
 * Why a card has no «Department and position» for a viewer whose role places people at all (iOS MTBizView.assignRefusal, fold.rs
 * Assign); null where it has it, or where the role places nobody.
 */
private fun assignRefusal(v: BizView, m: BizMember): Int? {
    if (!v.may("assign") || m.status == "removed" || v.mayAssign(m)) return null
    if (m.id == v.me) return R.string.biz_assign_only_admin_you
    if (v.role == "manager" && v.myDept.isEmpty()) return R.string.biz_invite_no_dept
    if (v.role == "manager" && m.role == "employee") return R.string.biz_assign_own_dept
    if (v.role == "manager" && m.role == "manager") return R.string.biz_assign_only_admin
    return R.string.biz_assign_owner_or_self
}

/** The person's own name and card, written again into the roster (iOS renew, the Profile record). */
private fun renew(act: MainActivity, org: String) = act.background {
    val card = Biz.myCard(act)
    Biz.write(org, BizCommand.profile(Biz.myName().take(256), card)) { id -> if (id == null) say(act, R.string.biz_refused) }
}

// ─────────────────────────── the sheets ───────────────────────────

/**
 * THE INVITATION (iOS MTBizInviteSheet): the role and the department it gives, its term, and — when known — the number it is
 * meant for; the link comes out as a code to show, a line to copy, and the platform's own share to send it to the person.
 */
private fun invitePage(act: MainActivity, org: String, onClose: () -> Unit): View {
    var role = BizRole.EMPLOYEE
    var dept = ""
    var days = 7
    var link: String? = null
    var busy = false
    var copied = false
    val number = act.bizField(R.string.biz_phone_optional).apply { inputType = InputType.TYPE_CLASS_PHONE }
    lateinit var live: Live<BizView?>
    val sheet = BizSheet(act, act.getString(R.string.biz_invite), onClose, cross = true) {
        if (busy || link != null) return@BizSheet
        val n = number.text.toString().trim()
        val e164 = if (n.isEmpty()) null else BizCountries.e164(BizCountries.initial(act), n)
        if (n.isNotEmpty() && e164 == null) { say(act, R.string.biz_invite_failed, R.string.biz_invite); return@BizSheet }
        busy = true
        act.background {
            val card = Biz.myCard(act)
            Biz.invite(org, role, dept.ifEmpty { null }, days, e164, card) { made ->
                busy = false
                if (made == null) say(act, R.string.biz_invite_failed, R.string.biz_invite) else { link = made; live.redraw() }
            }
        }
    }
    live = Live(act, sheet, { Biz.view(org) }) { v ->
        val c = act
        val made = link
        sheet.doneMark?.visibility = if (made == null) View.VISIBLE else View.GONE
        if (made != null) {
            val qr = FrameLayout(c).apply {
                setPadding(dp(16), dp(16), dp(16), dp(16))
                addView(FrameLayout(c).apply {
                    background = c.rounded(Color.WHITE, 12)
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    addView(QrView(c).apply { code = QrCode.encode(made, QrCode.Ecc.M) }, FrameLayout.LayoutParams(dp(220), dp(220)))
                }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
            }
            val line = c.text(made, 12f, MT.gray).apply {   // USER-DATA: the invitation's own link
                // not selectable: the link carries the invitation's secret, and the one road to the clipboard marks it sensitive
                typeface = Typeface.MONOSPACE; maxLines = 3; setPadding(dp(16), 0, dp(16), dp(10))
            }
            section(null, c.getString(R.string.biz_link_footer), qr, line,
                c.bizRow(if (copied) R.drawable.ic_check else R.drawable.ic_content_copy, c.getString(if (copied) R.string.biz_copied else R.string.copy_link)) {
                    (c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(sensitiveClip(c.getString(R.string.biz_invite), made))
                    CopiedLinks.laid(act, made)   // the secret leaves the clipboard in ten minutes (iOS MTBizInviteSheet.copy)
                    copied = true; live.redraw()
                },
                c.bizRow(R.drawable.ic_share, c.getString(R.string.biz_send_to_person)) {
                    act.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, made), null))
                },
                c.bizRow(R.drawable.ic_set_doc, c.getString(R.string.biz_poster)) { poster(act, made, v?.name ?: "") })
            return@Live
        }
        // fold.rs Invite: the roles this viewer may give; a manager's invitation is into their own department, fixed
        val roles = v?.invitableRoles.orEmpty().ifEmpty { listOf(BizRole.EMPLOYEE) }
        if (role !in roles) role = BizRole.EMPLOYEE
        val rows = mutableListOf<View>(c.pickerRow(c.getString(R.string.biz_role), c.getString(role.title)) {
            choose(act, c.getString(R.string.biz_role), roles.map { c.getString(it.title) }, roles.indexOf(role)) { i -> role = roles[i]; live.redraw() }
        })
        val depts = v?.depts.orEmpty()
        if (v?.role == "manager") {
            dept = v.myDept
            v.dept(dept)?.let { rows += c.bizLine(null, 0, c.getString(R.string.biz_dept), it.name) }   // USER-DATA: the manager's own department
        } else if (depts.isNotEmpty()) {
            val names = listOf(c.getString(R.string.biz_no_dept)) + depts.map { it.name }   // USER-DATA: the departments' names
            val ids = listOf("") + depts.map { it.id }
            rows += c.pickerRow(c.getString(R.string.biz_dept), names[maxOf(0, ids.indexOf(dept))]) {
                choose(act, c.getString(R.string.biz_dept), names, ids.indexOf(dept)) { i -> dept = ids[i]; live.redraw() }
            }
        }
        val terms = listOf(1 to R.string.biz_days_1, 7 to R.string.biz_days_7, 30 to R.string.biz_days_30)
        rows += c.pickerRow(c.getString(R.string.biz_term), c.getString(terms.first { it.first == days }.second)) {
            choose(act, c.getString(R.string.biz_term), terms.map { c.getString(it.second) }, terms.indexOfFirst { it.first == days }) { i -> days = terms[i].first; live.redraw() }
        }
        section(null, c.getString(R.string.biz_invite_check_footer), *rows.toTypedArray())
        (number.parent as? LinearLayout)?.removeView(number)
        // fold.rs InvitePhone is the administrators': a manager's invitation carries no number
        if (v?.may("invite_phone") == true) section(null, c.getString(R.string.biz_phone_optional_footer), number)
    }
    return sheet.page
}

/** The salary of one person (iOS MTBizSalarySheet): coins for a period (a day, a week, a month, on UTC boundaries), from now. */
private fun salaryPage(act: MainActivity, org: String, member: String, onClose: () -> Unit): View {
    val coins = act.bizField(R.string.biz_coins, digits = true)
    var period = 3
    lateinit var periodRow: View
    val sheet = BizSheet(act, act.getString(R.string.biz_salary), onClose, cross = true) {
        val n = coins.text.toString().filter { it in '0'..'9' }.toLongOrNull()
        if (n == null || n <= 0) { say(act, R.string.biz_salary_failed, R.string.biz_salary); return@BizSheet }
        bizWrite(act, org, BizCommand.salary(member, n, period, Biz.now())) { if (it != null) onClose() else say(act, R.string.biz_salary_failed, R.string.biz_salary) }
    }
    fun makePeriodRow(): View = act.pickerRow(act.getString(R.string.biz_period), periodTitle(act, period)) {
        choose(act, act.getString(R.string.biz_period), (1..3).map { periodTitle(act, it) }, period - 1) { i ->
            period = i + 1
            val parent = periodRow.parent as LinearLayout
            val at = parent.indexOfChild(periodRow)
            parent.removeView(periodRow); periodRow = makePeriodRow(); parent.addView(periodRow, at, lp())
        }
    }
    periodRow = makePeriodRow()
    coins.bizFirstField()
    sheet.readyWhen(coins) { (coins.text.toString().filter { it in '0'..'9' }.toLongOrNull() ?: 0L) > 0L }
    sheet.body.section(null, act.getString(R.string.biz_salary_footer), coins, periodRow)
    // what is still due to this person under the salary in force stays due when a new one is set -- each salary owes its own
    // finished periods up to the next one's start (contract 1.3, iOS MTBizSalarySheet.stillDue)
    val stillDue = Biz.view(org)?.due?.filter { it.member == member }?.sumOf { it.coins } ?: 0L
    if (0L < stillDue) sheet.body.section(null, null, act.bizWhyNot(R.string.biz_salary_still_due))
    return sheet.page
}

/** A person's department and job title (iOS MTBizPlaceSheet). */
private fun placePage(act: MainActivity, org: String, member: String, onClose: () -> Unit): View {
    val title = act.bizField(R.string.biz_position)
    var dept: String? = null
    lateinit var live: Live<BizView?>
    val sheet = BizSheet(act, act.getString(R.string.biz_dept_and_position), onClose, cross = true) {
        val t = title.text.toString().trim().take(256)
        bizWrite(act, org, BizCommand.assign(member, dept?.ifEmpty { null } ?: BizCommand.NO_DEPT, t)) { if (it != null) onClose() else say(act, R.string.biz_refused) }
    }
    live = Live(act, sheet, { Biz.view(org) }) { v ->
        val c = act
        val m = v?.member(member)
        if (dept == null) { dept = m?.dept ?: ""; title.setText(m?.title ?: ""); title.setSelection(title.text.length) }
        // fold.rs Assign: a manager keeps the person in their own department; anyone else chooses among the standing ones or none
        val manager = v?.role == "manager"
        val depts = if (manager) listOfNotNull(v?.dept(v.myDept)) else v?.depts.orEmpty()
        val names = (if (manager) emptyList() else listOf(c.getString(R.string.biz_no_dept))) + depts.map { it.name }   // USER-DATA: the departments' names
        val ids = (if (manager) emptyList() else listOf("")) + depts.map { it.id }
        if (dept !in ids) dept = ids.firstOrNull() ?: ""   // an archived department is refused (dept_usable): never offered as the choice
        val at = maxOf(0, ids.indexOf(dept ?: ""))
        (title.parent as? LinearLayout)?.removeView(title)
        // a manager places people within their own department alone (iOS MTBizPlaceSheet): the department is said, not chosen
        val deptRow = if (manager) c.bizLine(null, 0, c.getString(R.string.biz_dept), names.getOrElse(at) { "" })   // USER-DATA: the manager's own department
            else c.pickerRow(c.getString(R.string.biz_dept), names[at]) {
                choose(act, c.getString(R.string.biz_dept), names, at) { i -> dept = ids[i]; live.redraw() }
            }
        section(null, null, deptRow, title)
    }
    return sheet.page
}

/**
 * One name to give (iOS MTBizNameSheet): a department, a channel, a department's chat -- the last comes with its department's
 * name, ready, the name the person's to change ([initial]). The act runs on the Business's thread.
 */
private fun namePage(act: MainActivity, title: Int, field: Int, onClose: () -> Unit, initial: String = "", act0: (String, (String?) -> Unit) -> Unit): View {
    val name = act.bizField(field, initial = initial)
    val sheet = BizSheet(act, act.getString(title), onClose, cross = true) {
        val n = name.text.toString().trim().take(256)
        if (n.isEmpty()) return@BizSheet   // the checkmark stands dimmed while the name is empty
        act0(n) { done -> if (done != null) onClose() else say(act, R.string.biz_refused) }
    }
    name.bizFirstField { sheet.submit() }
    sheet.readyWhen(name) { name.text.isNotBlank() }
    sheet.body.section(null, null, name)
    return sheet.page
}

// ─────────────────────────── the organization's chats ───────────────────────────

/**
 * THE CHATS OF THE ORGANIZATION on its page (iOS MTBizChatsSection): every chat this person hears, the whole row a button that
 * opens its feed; a new one by the right the core gave (open) -- a channel for the administrators, a department's chat for its
 * manager.
 */
private fun LinearLayout.chatsSection(act: MainActivity, org: String, v: BizView) {
    val c = act
    val rows = mutableListOf<View>()
    v.chats.forEach { ch ->
        val standing = Biz.standing(ch)
        val line = when {
            !standing -> c.getString(if (ch.author == v.me) R.string.biz_chat_unreachable else R.string.biz_chat_invite_way)
            else -> v.dept(ch.dept ?: "")?.name ?: c.getString(R.string.biz_whole_org)   // USER-DATA: the department's name
        }
        rows += c.hstack {
            setPadding(dp(16), dp(10), dp(14), dp(10)); minimumHeight = dp(48); bizWhole()
            addView(c.bizGlyph(if (ch.kind == "channel") R.drawable.ic_set_antenna else R.drawable.ic_bar_contacts, Color.WHITE, 20), lp(sp(20), sp(20)).apply { marginEnd = dp(16) })
            addView(c.vstack(Gravity.NO_GRAVITY) {
                addView(c.text(ch.name, 16f), lp())   // USER-DATA: the chat's name
                addView(c.text(line, 12f, MT.gray), lp())
            }, lp(0, WRAP, 1f))
            pressable { openChat(act, org, v, ch) }
        }
    }
    // chat.rs Open: a channel only by an administrator; a department's chat by an administrator for any standing department, by a
    // manager for their own (an archived one is refused)
    // AN OFFICE'S CHANNEL (Sh.4, iOS ff685299): the core opens a channel for one department as well (chat.rs Open, the
    // administrators); with departments the platform's dialog asks for whom -- the whole organization or a department
    if (v.mayChannel) rows += c.bizRow(R.drawable.ic_set_antenna, c.getString(R.string.biz_new_channel)) {
        val depts = v.chatDepts
        if (depts.isEmpty()) newChat(act, org, true, null)
        // USER-DATA: the departments' names
        else android.app.AlertDialog.Builder(act).setTitle(R.string.biz_channel_for)
            .setItems((listOf(c.getString(R.string.biz_whole_org)) + depts.map { it.name }).toTypedArray()) { _, i ->
                newChat(act, org, true, if (i == 0) null else depts[i - 1])
            }
            .setNegativeButton(R.string.cancel, null).show()
    }
    val chatDepts = v.chatDepts
    // one department: no question which one -- the name sheet opens at once (iOS k0f)
    if (chatDepts.size == 1) rows += c.bizRow(R.drawable.ic_bar_contacts, c.getString(R.string.biz_new_dept_chat)) { newChat(act, org, false, chatDepts[0]) }
    else if (chatDepts.isNotEmpty()) rows += c.bizRow(R.drawable.ic_bar_contacts, c.getString(R.string.biz_new_dept_chat)) {
        // USER-DATA: the departments' names
        android.app.AlertDialog.Builder(act).setTitle(R.string.biz_dept).setItems(chatDepts.map { it.name }.toTypedArray()) { _, i -> newChat(act, org, false, chatDepts[i]) }
            .setNegativeButton(R.string.cancel, null).show()
    }
    if (rows.isNotEmpty()) section(c.getString(R.string.biz_chats), c.getString(R.string.biz_chats_footer), *rows.toTypedArray())
}

/** A new chat: a channel, or a department's chat that comes named after its department (USER-DATA: the department's name). */
private fun newChat(act: MainActivity, org: String, channel: Boolean, dept: BizDept?) = act.push { close ->
    namePage(act, if (channel) R.string.biz_new_channel else R.string.biz_new_dept_chat, R.string.biz_chat_name, close, initial = dept?.name ?: "") { n, done ->
        Biz.openChat(org, channel, n, dept?.id, done)
    }
}

/** The chat's feed opens: the group stands here, or it is gathered now; else the honest word why it is not here yet. */
private fun openChat(act: MainActivity, org: String, v: BizView, ch: BizChat) {
    // a chat whose group is not named yet says so -- a row is never a dead button (iOS MTBizChatsSection.open)
    val group = ch.group ?: run { say(act, R.string.biz_chat_invite_way); return }
    act.background {
        val ready = Biz.standing(ch) || Biz.gather(org, ch.id) != null
        act.onMain {
            if (ready) act.push { close -> conversationPage(act, Groups.key(group), close) }
            else say(act, if (ch.author == v.me) R.string.biz_chat_unreachable else R.string.biz_chat_invite_way)
        }
    }
}

/** An item of the shop (iOS MTBizOfferSheet): its name, its price in coins, how many there are (none — without a count). */
private fun offerPage(act: MainActivity, org: String, onClose: () -> Unit): View {
    val title = act.bizField(R.string.biz_item_name)
    val price = act.bizField(R.string.biz_price, digits = true)
    val stock = act.bizField(R.string.biz_how_many, digits = true)
    val sheet = BizSheet(act, act.getString(R.string.biz_new_item), onClose, cross = true) {
        val t = title.text.toString().trim().take(256)
        val p = price.text.toString().filter { it in '0'..'9' }.toLongOrNull()
        val item = Biz.freshTag()
        if (t.isEmpty() || p == null || p <= 0 || item == null) { say(act, R.string.biz_refused); return@BizSheet }
        val s = stock.text.toString().filter { it in '0'..'9' }.toLongOrNull()?.coerceAtMost(0xFFFFFFFFL) ?: 0L
        bizWrite(act, org, BizCommand.offer(item, t, p, s, true)) { if (it != null) onClose() else say(act, R.string.biz_refused) }
    }
    title.bizFirstField()
    sheet.readyWhen(title, price) { title.text.isNotBlank() && (price.text.toString().filter { it in '0'..'9' }.toLongOrNull() ?: 0L) > 0L }
    sheet.body.section(null, null, title, price, stock)
    return sheet.page
}
