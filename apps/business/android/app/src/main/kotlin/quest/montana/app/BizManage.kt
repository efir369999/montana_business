package quest.montana.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import java.io.File

// ════════════════════════════════════════════════════════════
// MONTANA BUSINESS: MANAGING THE PLACES, THE JOURNAL, THE FIRST DAY, THE SHIFTS (stages 7.3, 7.4, 8.1, 9.1)
// ════════════════════════════════════════════════════════════
// iOS MTBusinessManage.swift, MTBusinessFirstDay.swift and MTBusinessShifts.swift (main e8dee3e1), page for page.
// A NEW PHONE (7.3): the person had a place on an old phone and comes back on a new one with a new key. The number is confirmed
// for the new key (MTBA), an administrator's invitation brings the roster, the person chooses themselves, and the administrator
// sees «a new phone: the name, the number» and moves the place with one touch (Rekey); the new phone then binds its number
// (PhoneBind). The coin book does not move: it comes back only from the 24 words. A REMOVED PERSON'S PHONE lets the organization
// go (Biz.dropRemoved) and says so in one line.

// ─────────────────────────── a new phone (7.3) ───────────────────────────

/** THE NEW PHONE'S WAY BACK TO ITS PLACE (iOS MTBizMoveSheet): the number first, then the administrator's invitation, opened as a move. */
internal fun movePage(act: MainActivity, onClose: () -> Unit): View {
    val sheet = BizSheet(act, act.getString(R.string.biz_new_phone), onClose, cross = true)
    Live(act, sheet, { Biz.phone() }) { phone ->
        val c = act
        val rows = if (phone == null) listOf(c.bizRow(R.drawable.ic_peer_phone, c.getString(R.string.biz_confirm_number)) { act.push { phonePage(act, it) } })
            else listOf(c.bizRow(R.drawable.ic_qr_scan, c.getString(R.string.biz_scan_invite)) { scanJoin(act, move = true) { onClose() } },
                c.bizRow(R.drawable.ic_content_copy, c.getString(R.string.biz_join_copied)) { joinCopied(act, move = true) { onClose() } })
        section(null, c.getString(R.string.biz_move_footer), *rows.toTypedArray())
    }
    return sheet.page
}

/** THE ROSTER, TO CHOOSE ONESELF IN (iOS MTBizChooseSelfPage): the touch asks the administrator to move that place to this phone. */
internal fun chooseSelfPage(act: MainActivity, org: String, onBack: () -> Unit): View {
    val sheet = BizSheet(act, "", onBack)
    Live(act, sheet, { Biz.view(org) }) { v ->
        v ?: return@Live
        val c = act
        sheet.title.text = v.name   // USER-DATA: the organization's name
        section(c.getString(R.string.biz_which_you), c.getString(R.string.biz_which_you_footer), *v.members.filter { it.status != "removed" }.map { m ->
            memberRow(c, v, m) { Biz.askMove(org, m.id) { asked -> if (asked) onBack() else say(act, R.string.biz_confirm_first) } }
        }.toTypedArray())
    }
    return sheet.page
}

/** THE ONE LINE OF A REMOVED PERSON'S PHONE (iOS MTBizLeftRow): the organization let them go; its records are erased here. */
internal fun leftRow(act: MainActivity, org: String, name: String): View = act.hstack {
    setPadding(dp(16), dp(10), dp(14), dp(10)); minimumHeight = dp(48); bizWhole()
    addView(act.bizGlyph(R.drawable.ic_person, Color.WHITE, 20), lp(sp(20), sp(20)).apply { marginEnd = dp(16) })
    addView(act.text(act.getString(R.string.biz_removed_from, name), 14f, MT.gray), lp(0, WRAP, 1f))   // USER-DATA: the organization's name
    pressable { confirmAct(act, name, R.string.biz_hide) { Biz.hideLeft(org) } }
}

/**
 * THE NEW PHONES WAITING FOR AN ADMINISTRATOR (iOS MTBizMovesSection): one row per request -- the name the person chose, the
 * number the service confirmed for the new key and whether it is the member's own; the row's touch moves the place, a long
 * press declines (iOS: its swipe).
 */
internal fun LinearLayout.movesSection(act: MainActivity, org: String, v: BizView) {
    if (!Biz.boss(v.role)) return
    val waiting = Biz.moves().filter { it.org == org }
    if (waiting.isEmpty()) return
    val c = act
    section(c.getString(R.string.biz_new_phones), c.getString(R.string.biz_new_phones_footer), *waiting.map { m ->
        val member = v.member(m.member)
        // the number the service confirmed for the new key, against the member's own confirmed number where the view shows it
        val same = member?.phone?.let { it == m.e164 }
        val movable = v.mayRekey(m.member)
        c.hstack {
            setPadding(dp(16), dp(8), dp(14), dp(8)); minimumHeight = dp(56); bizWhole()
            addView(c.bizFace(member?.name ?: "", 40), lp(dp(40), dp(40)))
            gap(12)
            addView(c.vstack(Gravity.NO_GRAVITY) {
                addView(c.text(member?.name ?: "", 16f), lp())   // USER-DATA: the person's name
                addView(c.text(m.e164, 12f, MT.gray), lp())   // USER-DATA: the number the service confirmed for the new phone
                // the match told by the glyph's shape (point 0): white, the words secondary
                val (word, glyph) = when (same) {
                    true -> R.string.biz_number_matches to R.drawable.ic_set_check_circle
                    false -> R.string.biz_number_differs to R.drawable.ic_gpp_maybe
                    null -> R.string.biz_number_none to R.drawable.ic_set_help
                }
                addView(c.hstack {
                    addView(c.bizGlyph(glyph, Color.WHITE, 12), lp(sp(12), sp(12)).apply { marginEnd = dp(4) })
                    addView(c.text(c.getString(word), 12f, MT.gray))
                }, lp())
                // fold.rs Rekey: an administrator moves their own place and those below; an administrator's or the owner's, only the owner
                if (!movable) addView(c.hstack {   // iOS MTBizNote(glyph: "info.circle")
                    addView(c.bizGlyph(R.drawable.ic_info, Color.WHITE, 12), lp(sp(12), sp(12)).apply { marginEnd = dp(4) })
                    addView(c.text(c.getString(R.string.biz_move_owner_only), 12f, MT.gray), lp(0, WRAP, 1f))
                }, lp())
            }, lp(0, WRAP, 1f))
            if (movable) {
                addView(c.bizGlyph(R.drawable.ic_restore, Color.WHITE, 20), lp(sp(20), sp(20)))
                pressable { Biz.confirmMove(m) { moved -> if (!moved) say(act, R.string.biz_refused) } }
            }
            setOnLongClickListener { confirmAct(act, member?.name ?: m.e164, R.string.biz_decline) { Biz.declineMove(m) }; true }
        }
    }.toTypedArray())
}

// ─────────────────────────── the journal (7.4) ───────────────────────────

/** The words of the journal (iOS MTBizJournalText): a record's kind as the commands name it; the supply steps are the order page's own. */
private fun journalTitle(kind: String): Int? = when (kind) {
    "genesis" -> R.string.biz_j_genesis
    "invite" -> R.string.biz_j_invite
    "join" -> R.string.biz_j_join
    "role" -> R.string.biz_role
    "dept" -> R.string.biz_dept
    "assign" -> R.string.biz_dept_and_position
    "remove" -> R.string.biz_j_remove
    "rekey" -> R.string.biz_j_rekey
    "revoke" -> R.string.biz_j_revoke
    "offer" -> R.string.biz_j_offer
    "profile" -> R.string.biz_j_profile
    "item" -> R.string.biz_product
    "node" -> R.string.biz_j_node
    "node_staff" -> R.string.biz_node_people
    "phone_bind" -> R.string.biz_confirmed
    "invite_phone" -> R.string.biz_j_invite_phone
    "salary" -> R.string.biz_salary
    "pay" -> R.string.biz_j_pay
    "receipt" -> R.string.biz_j_receipt
    "redeem" -> R.string.biz_j_redeem
    "fulfil" -> R.string.biz_handed_over
    "open" -> R.string.biz_j_open
    "letter" -> R.string.biz_j_letter
    "edit" -> R.string.biz_j_edit
    "delete" -> R.string.biz_j_delete
    else -> BizSupplyText.step(kind).takeIf { it.first != R.drawable.ic_dot }?.second
}
private fun journalGlyph(chain: String) = when (chain) {
    "R" -> R.drawable.ic_bar_contacts
    "H" -> R.drawable.ic_badge
    "S" -> R.drawable.ic_archive
    "C" -> R.drawable.ic_peer_message
    else -> R.drawable.ic_dot
}

/**
 * THE ORGANIZATION'S JOURNAL (iOS MTBizJournalPage, 7.4): the records this person may see -- the roster's and their own closed
 * lanes -- the newest first, each with its kind, its signer, its moment and the fold's verdict where it is not «applied». The
 * core hands the last two hundred; the whole chains go out through the platform's own sheet.
 */
internal fun journalPage(act: MainActivity, org: String, onBack: () -> Unit): View {
    val sheet = BizSheet(act, act.getString(R.string.biz_journal), onBack)
    Live(act, sheet, { Biz.view(org) }) { v ->
        v ?: return@Live
        val c = act
        section(null, c.getString(R.string.biz_save_chains_footer), c.bizRow(R.drawable.ic_share, c.getString(R.string.biz_save_chains)) {
            Biz.chainFiles(act, org) { files -> BizExport.share(act, files, "application/octet-stream") }
        })
        val entries = v.journal
        val rows = when {
            entries == null -> listOf(c.bizLine(null, 0, c.getString(R.string.biz_journal_later), titleColor = MT.gray))
            entries.isEmpty() -> listOf(c.bizLine(null, 0, c.getString(R.string.biz_no_records), titleColor = MT.gray))
            else -> entries.sortedByDescending { it.atMs }.map { e ->
                c.hstack {
                    gravity = Gravity.TOP
                    setPadding(dp(16), dp(8), dp(14), dp(8)); minimumHeight = dp(48); bizWhole()
                    addView(c.bizGlyph(journalGlyph(e.chain), Color.WHITE, 20), lp(sp(20), sp(20)).apply { marginEnd = dp(16); topMargin = dp(2) })
                    addView(c.vstack(Gravity.NO_GRAVITY) {
                        // USER-DATA: a record's kind as the core names it, for a kind this build has no word for
                        addView(c.text(journalTitle(e.kind)?.let { c.getString(it) } ?: e.kind, 16f), lp())
                        // USER-DATA: who signed the record and when
                        addView(c.text(listOf(v.member(e.author)?.name ?: "", BizSupplyText.moment(e.atMs)).filter { it.isNotEmpty() }.joinToString(" · "), 12f, MT.gray), lp())
                        // the fold's verdict by the glyph's shape (point 0): white, the words secondary
                        val verdict = when (e.outcome) { "rejected" -> R.drawable.ic_close to R.string.biz_j_rejected; "waiting" -> R.drawable.ic_restore to R.string.biz_j_waiting; else -> null }
                        verdict?.let { (glyph, word) -> addView(c.hstack {
                            addView(c.bizGlyph(glyph, Color.WHITE, 12), lp(sp(12), sp(12)).apply { marginEnd = dp(4) })
                            addView(c.text(c.getString(word), 12f, MT.gray))
                        }, lp()) }
                    }, lp(0, WRAP, 1f))
                }
            }
        }
        section(null, c.getString(R.string.biz_journal_footer), *rows.toTypedArray())
    }
    return sheet.page
}

// ─────────────────────────── the first day (8.1) ───────────────────────────

/** The one a member turns to (iOS MTBizFirstDay.manager): the manager of their department, else the owner (never themselves). */
private fun managerOf(me: BizMember, v: BizView): BizMember? {
    val active = v.members.filter { it.status == "active" && it.id != me.id }
    if (me.dept.isNotEmpty()) active.firstOrNull { it.role == "manager" && it.dept == me.dept }?.let { return it }
    return active.firstOrNull { it.role == "owner" }
}

/** THE FIRST DAY OF A MEMBER (iOS MTBizFirstDaySheet): who I am in the organization, whom I answer to, and the next steps. */
internal fun firstDayPage(act: MainActivity, org: String, onClose: () -> Unit): View {
    val sheet = BizSheet(act, act.getString(R.string.biz_first_day), onClose, cross = true)
    Live(act, sheet, { Biz.view(org) }) { v ->
        val me = v?.member(v.me) ?: return@Live
        val c = act
        val head = mutableListOf<View>(c.vstack(Gravity.NO_GRAVITY) {
            setPadding(dp(16), dp(14), dp(16), dp(12))
            addView(c.bizGlyph(R.drawable.ic_organization, Color.WHITE, 34), lp(sp(34), sp(34)))
            gap(6)
            addView(c.text(v.name, 22f, Color.WHITE, bold = true), lp())   // USER-DATA: the organization's name, as its owner wrote it
            addView(c.text(c.getString(R.string.biz_welcome), 14f, MT.gray), lp())
        })
        head += c.bizLine(null, 0, c.getString(R.string.biz_role), roleTitle(c, v.role))
        v.dept(me.dept)?.let { head += c.bizLine(null, 0, c.getString(R.string.biz_dept), it.name) }   // USER-DATA: the department's name
        if (me.title.isNotEmpty()) head += c.bizLine(null, 0, c.getString(R.string.biz_position), me.title)   // USER-DATA: the position
        section(null, null, *head.toTypedArray())
        managerOf(me, v)?.let { boss ->
            // the one to turn to is a manager only when they are one: without a department's manager, the owner (iOS 88ce69b2)
            section(c.getString(if (boss.role == "manager") R.string.biz_your_manager else R.string.biz_who_to_turn_to), c.getString(R.string.biz_manager_footer),
                memberRow(c, v, boss) { act.push { memberPage(act, org, boss.id, it) } })
        }
        val next = mutableListOf<View>()
        if (!me.phoneConfirmed) next += c.bizRow(R.drawable.ic_peer_phone, c.getString(R.string.biz_confirm_number)) { act.push { phonePage(act, it) } }
        next += c.bizRow(R.drawable.ic_key, c.getString(R.string.biz_save_words), chevron = true) { act.push { seedPage(act, it) } }
        if (v.keptNodes.isNotEmpty()) next += c.bizRow(R.drawable.ic_archive, c.getString(R.string.biz_node_orders), chevron = true) { act.push { ordersPage(act, org, it) } }
        next += c.bizRow(R.drawable.ic_badge, c.getString(R.string.biz_my_card), chevron = true) { act.push { memberPage(act, org, me.id, it) } }
        // the payouts in their own sentence, then the words' one honest line, the same as in Settings (iOS 1503f137)
        section(c.getString(R.string.biz_next), c.getString(R.string.biz_coins_android) + "\n\n" + c.getString(R.string.biz_save_words_footer), *next.toTypedArray())
        v.salary.firstOrNull { it.member == me.id }?.let { s ->
            // USER-DATA: the salary in coins and its period
            section(null, null, c.bizLine(null, 0, c.getString(R.string.biz_salary), BizText.coins(s.coins) + " · " + periodTitle(c, s.period)))
        }
    }
    return sheet.page
}

/**
 * THE INVITATION'S POSTER (iOS MTBizPoster, 8.1): the link as a large code, the organization's name above it and one line under
 * it -- an A4 page (1240 by 1754, 150 to the inch) in the system's own type, through the platform's sheet: print, save, send.
 */
internal fun poster(act: MainActivity, link: String, org: String) {
    val q = QrCode.encode(link, QrCode.Ecc.M) ?: return
    val w = 1240; val h = 1754
    val page = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(page)
    canvas.drawColor(Color.WHITE)
    fun block(text: String, size: Float, color: Int, bold: Boolean, x: Int, y: Int, width: Int) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; this.color = color; typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL) }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width).setAlignment(Layout.Alignment.ALIGN_CENTER).build()
        canvas.save(); canvas.translate(x.toFloat(), y.toFloat()); layout.draw(canvas); canvas.restore()
    }
    block(org, 72f, Color.BLACK, true, 80, 140, w - 160)   // USER-DATA: the organization's name
    val module = 900 / q.size
    val side = module * q.size
    val ox = (w - side) / 2f; val oy = 400 + (900 - side) / 2f
    val ink = Paint().apply { color = Color.BLACK; isAntiAlias = false }
    for (y in 0 until q.size) for (x in 0 until q.size) if (q.dark(x, y))
        canvas.drawRect(ox + x * module, oy + y * module, ox + (x + 1) * module, oy + (y + 1) * module, ink)
    block(act.getString(R.string.biz_poster_line, org), 40f, Color.DKGRAY, false, 120, 1380, w - 240)
    val png = java.io.ByteArrayOutputStream().also { page.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    val f = BizExport.export(act, listOf("poster.png" to png)).firstOrNull() ?: return   // NOT-UI: a file name
    BizExport.share(act, listOf(f), "image/png")
}

// ─────────────────────────── the shifts (9.1) ───────────────────────────

private fun clock(seconds: Long) = String.format(java.util.Locale.ROOT, "%d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60)

/**
 * SHIFTS (iOS MTBizShiftsSection, 9.1, the economy of time): a shift is kept by its member -- opened and closed on their own phone
 * -- and confirmed by somebody else, an administrator or the manager of the member's department, with one touch. The seconds
 * are counted by the core from the records' own times; how they are paid is not decided here.
 */
internal fun LinearLayout.shiftsSection(act: MainActivity, org: String, v: BizView) {
    val c = act
    val me = v.me
    val mine = v.shifts.firstOrNull { it.member == me && it.running }
    val done = v.shifts.filter { it.member == me && !it.running }.sortedByDescending { it.openMs }.take(5)
    val toConfirm = if (v.may("shift_confirm")) v.shifts.filter { it.member != me && !it.running && it.confirmedBy == null }.sortedBy { it.openMs } else emptyList()
    if (!v.may("shift_open") && toConfirm.isEmpty()) return
    // who confirms this person's own shift (fold.rs ShiftConfirm, iOS confirmer): the manager of their department where there is
    // one, and any administrator -- said by name of role, so a closed shift never just «waits»
    val myDept = v.member(me)?.dept ?: ""
    val managed = myDept.isNotEmpty() && v.members.any { it.status == "active" && it.id != me && it.role == "manager" && it.dept == myDept }
    val confirmer = if (managed) R.string.biz_wait_manager_or_admin else R.string.biz_wait_admin
    val rows = mutableListOf<View>()
    if (mine != null) {
        val running = c.text("", 15f, MT.gray)
        val tick = object : Runnable {
            override fun run() {
                running.text = clock(maxOf(0L, (System.currentTimeMillis() - mine.openMs) / 1000))   // USER-DATA: the running time of the open shift
                if (running.isAttachedToWindow) running.postDelayed(this, 1000)
            }
        }
        running.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { tick.run() }
            override fun onViewDetachedFromWindow(view: View) { view.removeCallbacks(tick) }
        })
        rows += c.hstack {
            setPadding(dp(16), dp(10), dp(14), dp(10)); minimumHeight = dp(48); bizWhole()
            addView(c.bizGlyph(R.drawable.ic_restore, Color.WHITE, 20), lp(sp(20), sp(20)).apply { marginEnd = dp(16) })
            addView(c.text(c.getString(R.string.biz_on_shift), 16f), lp(0, WRAP, 1f))
            addView(running, lp(WRAP, WRAP))
        }
        rows += c.bizRow(R.drawable.ic_pause_fill, c.getString(R.string.biz_end_shift)) { bizAct(act, org, BizCommand.shiftClose("")) }
    } else if (v.may("shift_open")) rows += c.bizRow(R.drawable.ic_play_fill, c.getString(R.string.biz_start_shift)) { bizAct(act, org, BizCommand.shiftOpen()) }
    done.forEach { rows += shiftRow(c, it, null, false, confirmer) }
    toConfirm.forEach { s -> rows += shiftRow(c, s, v.member(s.member)?.name ?: "", true, confirmer).apply { pressable { bizAct(act, org, BizCommand.shiftConfirm(s.closeRecord ?: "")) } } }
    section(c.getString(R.string.biz_shifts), c.getString(R.string.biz_shifts_footer), *rows.toTypedArray())
}

/** [confirmer]: whom one's own closed shift waits for (iOS MTBizShiftsSection.confirmer). */
private fun shiftRow(c: Context, s: BizShift, name: String?, confirming: Boolean, confirmer: Int): View = c.hstack {
    setPadding(dp(16), dp(8), dp(14), dp(8)); minimumHeight = dp(48); bizWhole()
    // a confirmed shift is told by the seal alone, so the seal speaks its word (iOS k0d: «Confirmed»)
    addView(c.bizGlyph(if (s.confirmedBy != null) R.drawable.ic_set_check_circle else if (confirming) R.drawable.ic_check else R.drawable.ic_restore,
        Color.WHITE, 20, said = if (s.confirmedBy != null) R.string.biz_pay_confirmed else null), lp(sp(20), sp(20)).apply { marginEnd = dp(16) })
    addView(c.vstack(Gravity.NO_GRAVITY) {
        if (name != null) addView(c.text(name, 16f), lp())   // USER-DATA: the member's name
        // USER-DATA: when the shift began
        addView(c.text(BizSupplyText.moment(s.openMs), if (name == null) 16f else 12f, if (name == null) Color.WHITE else MT.gray), lp())
        if (confirming) addView(c.text(c.getString(R.string.biz_tap_confirm), 12f, MT.gray), lp())
        else if (s.confirmedBy == null) addView(c.text(c.getString(confirmer), 12f, MT.gray), lp())
    }, lp(0, WRAP, 1f))
    addView(c.text(clock(s.seconds ?: 0), 15f, MT.gray))   // USER-DATA: the shift's length
}
