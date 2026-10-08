package quest.montana.app

import android.app.DatePickerDialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import java.util.Calendar

// ════════════════════════════════════════════════════════════
// MONTANA BUSINESS: THE PAYROLL REPORT (stage 10, iOS MTBusinessReports.swift, main 76026d6b)
// ════════════════════════════════════════════════════════════
// The payouts of a chosen period, as the organization's TimeChain holds them: per person the salary and the bonuses, and whether
// each payout is confirmed by the receiver's own receipt or only sent; beside them the time of the shifts begun in the period
// (9.1), confirmed or not -- seconds, never coins: how the seconds are paid is the author's word, not this page's. A payout's
// moment is its Pay record's (the core's pay[].at_ms); a payout the core names without a moment is counted nowhere and said so.
// The CSV is written on this phone into the export (BizExport) and handed to the platform's own sheet.

/** One person's line of the payroll (iOS MTBizPayrollLine): their payouts in the period, summed by kind and by state. */
class BizPayrollLine(val member: String, val name: String) {
    var salary = 0L; var bonus = 0L; var confirmed = 0L; var sent = 0L
    val pays = mutableListOf<BizPayment>()
    /** Closed shifts begun in the period, and of them those confirmed by somebody else. */
    var seconds = 0L; var secondsConfirmed = 0L
    val shifts = mutableListOf<BizShift>()
    val total: Long get() = salary + bonus
}

object BizPayroll {
    /** The period's payouts by person, the largest total first; and how many payouts carry no moment. */
    fun lines(v: BizView, lo: Long, hi: Long): Pair<List<BizPayrollLine>, Int> {
        val by = LinkedHashMap<String, BizPayrollLine>()
        var undated = 0
        for (p in v.pay) {
            val at = p.atMs
            if (at == null) { undated++; continue }
            if (at < lo || hi <= at) continue
            val l = by.getOrPut(p.member) { BizPayrollLine(p.member, v.member(p.member)?.name ?: "") }
            if (p.kind == 2) l.bonus += p.coins else l.salary += p.coins
            if (p.state == "confirmed") l.confirmed += p.coins else l.sent += p.coins
            l.pays += p
        }
        for (s in v.shifts) {
            if (s.running || s.openMs < lo || hi <= s.openMs) continue
            val l = by.getOrPut(s.member) { BizPayrollLine(s.member, v.member(s.member)?.name ?: "") }
            val sec = s.seconds ?: 0
            l.seconds += sec
            if (s.confirmedBy != null) l.secondsConfirmed += sec
            l.shifts += s
        }
        return by.values.sortedWith(compareByDescending<BizPayrollLine> { it.total }.thenByDescending { it.seconds }) to undated
    }

    /**
     * The CSV of the period (iOS MTBizPayroll.csv): one row per payout and per shift -- the moment (UTC, ISO 8601), the person, the
     * kind, a salary's period (ISO 8601 days, half-open), the coins (a payout) or the seconds (a shift), the state, the record; the header and the words in the person's
     * language; a field is quoted when it holds a comma, a quote or a break.
     */
    fun csv(c: Context, lines: List<BizPayrollLine>): String {
        val q = '"'
        fun field(s: String): String = if (s.contains(',') || s.contains(q) || s.contains('\n')) q + s.replace(q.toString(), q.toString() + q) + q else s
        fun iso(ms: Long) = java.time.Instant.ofEpochSecond(ms / 1000).toString()
        fun day(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
        // a salary pay's period as ISO 8601 names a half-open interval of days: 2026-10-01/2026-11-01 (contract 1.4); a bonus has none
        fun period(p: BizPayment): String { val f = p.fromMs; val t = p.toMs; return if (f != null && t != null) day(f) + "/" + day(t) else "" }
        val head = listOf(R.string.biz_rep_date, R.string.biz_rep_person, R.string.biz_rep_kind, R.string.biz_period, R.string.biz_coins,
            R.string.biz_rep_seconds, R.string.biz_rep_state, R.string.biz_rep_record).map { c.getString(it) }
        val rows = mutableListOf(head.joinToString(",") { field(it) })
        for (l in lines) {
            for (p in l.pays.sortedBy { it.atMs ?: 0 }) rows += listOf(iso(p.atMs ?: 0), l.name, c.getString(if (p.kind == 2) R.string.biz_bonus else R.string.biz_salary),
                period(p), p.coins.toString(), "", c.getString(if (p.state == "confirmed") R.string.biz_pay_confirmed else R.string.biz_pay_sent), p.record).joinToString(",") { field(it) }
            for (s in l.shifts.sortedBy { it.openMs }) rows += listOf(iso(s.openMs), l.name, c.getString(R.string.biz_rep_shift), "", "", (s.seconds ?: 0).toString(),
                c.getString(if (s.confirmedBy != null) R.string.biz_pay_confirmed else R.string.biz_rep_not_confirmed), s.openRecord).joinToString(",") { field(it) }
        }
        return rows.joinToString("\r\n") + "\r\n"
    }
}

/** THE PAYROLL'S ROW on the organization's page (administrators). */
/** THE PAYROLL'S ROW, at the foot of the organization's page beside the journal (administrators; iOS MTBizJournalSection, k0f). */
internal fun payrollRow(act: MainActivity, org: String): View =
    act.bizRow(R.drawable.ic_app_wallet, act.getString(R.string.biz_rep_payroll), chevron = true) { act.push { payrollPage(act, org, it) } }

private fun dayStart(ms: Long): Long = Calendar.getInstance().apply { timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
/** The «to» day is counted whole: its end, not its first instant. */
private fun dayEnd(ms: Long): Long = Calendar.getInstance().apply { timeInMillis = dayStart(ms); add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis

/**
 * THE PAYROLL FOR A PERIOD (iOS MTBizPayrollPage): the period chosen with the platform's own date pickers, the totals, a line per
 * person that opens their payouts, and the CSV through the platform's sheet.
 */
private fun payrollPage(act: MainActivity, org: String, onBack: () -> Unit): View {
    var from = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis.let { dayStart(it) }
    var to = System.currentTimeMillis()
    val sheet = BizSheet(act, act.getString(R.string.biz_rep_payroll), onBack)
    lateinit var live: Live<BizView?>
    /** The platform's own date picker; the chosen day comes back within its bounds. */
    fun pick(at: Long, min: Long?, max: Long?, done: (Long) -> Unit) {
        val cal = Calendar.getInstance().apply { timeInMillis = at }
        DatePickerDialog(act, { _, y, m, d -> done(Calendar.getInstance().apply { clear(); set(y, m, d) }.timeInMillis) },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).apply {
            min?.let { datePicker.minDate = it }
            max?.let { datePicker.maxDate = it }
        }.show()
    }
    live = Live(act, sheet, { Biz.view(org) }) { v ->
        v ?: return@Live
        val c = act
        val (lines, undated) = BizPayroll.lines(v, from, dayEnd(to))
        section(c.getString(R.string.biz_period), null,
            c.pickerRow(c.getString(R.string.biz_rep_start), BizText.day(from)) { pick(from, null, to) { from = dayStart(it); live.redraw() } },
            c.pickerRow(c.getString(R.string.biz_rep_end), BizText.day(to)) { pick(to, from, null) { to = it; live.redraw() } })
        fun sum(f: (BizPayrollLine) -> Long) = lines.sumOf(f)
        fun span(s: Long) = if (s == 0L) "0" else BizSupplyText.span(c, s * 1000)
        val totals = mutableListOf(
            // USER-DATA: sums of coins and spans of time
            c.bizLine(null, 0, c.getString(R.string.biz_salary), BizText.coins(sum { it.salary })),
            c.bizLine(null, 0, c.getString(R.string.biz_rep_bonuses), BizText.coins(sum { it.bonus })),
            c.bizLine(null, 0, c.getString(R.string.biz_pay_confirmed), BizText.coins(sum { it.confirmed })),
            c.bizLine(null, 0, c.getString(R.string.biz_rep_sent), BizText.coins(sum { it.sent })),
            c.bizLine(null, 0, c.getString(R.string.biz_rep_time), span(sum { it.seconds })),
            c.bizLine(null, 0, c.getString(R.string.biz_rep_time_confirmed), span(sum { it.secondsConfirmed })),
            c.bizRow(R.drawable.ic_share, c.getString(R.string.biz_rep_csv)) { if (lines.isNotEmpty()) saveCsv(act, v, lines, from, to) }.apply {
                if (lines.isEmpty()) { isEnabled = false; alpha = 0.4f }
            })
        // what «confirmed» means, said where the sums are read (iOS c98dccf2); then the payouts with no moment yet
        section(c.getString(R.string.biz_rep_total), c.getString(R.string.biz_rep_confirmed_means) + (if (0 < undated) "\n\n" + c.getString(R.string.biz_rep_undated) else ""),
            *totals.toTypedArray())
        val people = if (lines.isEmpty()) listOf(c.bizLine(null, 0, c.getString(R.string.biz_rep_none), titleColor = MT.gray))
            else lines.map { l ->
                c.hstack {
                    setPadding(dp(16), dp(8), dp(14), dp(8)); minimumHeight = dp(56); bizWhole()
                    addView(c.bizFace(l.name, 40), lp(dp(40), dp(40)))
                    gap(12)
                    addView(c.vstack(Gravity.NO_GRAVITY) {
                        addView(c.text(l.name, 16f), lp())   // USER-DATA: the person's name
                        // USER-DATA: the salary and the bonuses of the period, in coins, and the time on shifts
                        addView(c.text(BizText.coins(l.salary) + " + " + BizText.coins(l.bonus) + (if (l.seconds == 0L) "" else " · " + BizSupplyText.span(c, l.seconds * 1000)),
                            12f, MT.gray), lp())
                    }, lp(0, WRAP, 1f))
                    addView(c.text(BizText.coins(l.total), 15f), lp(WRAP, WRAP).apply { marginStart = dp(8) })   // USER-DATA: the person's total
                    addView(c.bizGlyph(R.drawable.ic_chevron_right, Color.rgb(89, 89, 89), 18), lp(sp(18), sp(18)).apply { marginStart = dp(6) })
                    pressable { act.push { personPage(act, l, it) } }
                }
            }
        section(c.getString(R.string.biz_people), null, *people.toTypedArray())
    }
    return sheet.page
}

/** The CSV goes out under the organization's name and the period, from the export, through the platform's sheet. */
private fun saveCsv(act: MainActivity, v: BizView, lines: List<BizPayrollLine>, from: Long, to: Long) {
    val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)   // NOT-UI: a file name, its days ISO 8601 (read the same in every country)
    val name = BizExport.fileName(v.name, "payroll")   // NOT-UI: a file name (iOS MTBizPlace.fileName)
    val text = BizPayroll.csv(act, lines)
    act.background {
        val f = BizExport.export(act, listOf((name + " " + day.format(java.util.Date(from)) + " " + day.format(java.util.Date(to)) + ".csv") to text.toByteArray(Charsets.UTF_8))).firstOrNull()
        act.onMain { if (f != null) BizExport.share(act, listOf(f), "text/csv") }
    }
}

/** One person's payouts in the period (iOS MTBizPayrollPerson): the moment, the kind, the coins, the state; their shifts. */
private fun personPage(act: MainActivity, l: BizPayrollLine, onBack: () -> Unit): View {
    val sheet = BizSheet(act, l.name, onBack)   // USER-DATA: the person's name
    val c = act
    sheet.body.apply {
        // the payout's day stands in its own row, as on the person's card (iOS c98dccf2)
        section(null, null, *l.pays.sortedByDescending { it.atMs ?: 0 }.map { p -> payRowOf(c, p) }.toTypedArray())
        if (l.shifts.isNotEmpty()) section(c.getString(R.string.biz_shifts), null, *l.shifts.sortedByDescending { it.openMs }.map { s ->
            c.hstack {
                setPadding(dp(16), dp(8), dp(14), dp(8)); minimumHeight = dp(48); bizWhole()
                addView(c.bizGlyph(R.drawable.ic_restore, Color.WHITE, 20), lp(sp(20), sp(20)).apply { marginEnd = dp(16) })
                addView(c.vstack(Gravity.NO_GRAVITY) {
                    addView(c.text(BizSupplyText.span(c, (s.seconds ?: 0) * 1000), 16f), lp())   // USER-DATA: the shift's length
                    addView(c.text(BizSupplyText.moment(s.openMs), 12f, MT.gray), lp())   // USER-DATA: the moment the shift began
                }, lp(0, WRAP, 1f))
                val ok = s.confirmedBy != null
                addView(c.bizGlyph(if (ok) R.drawable.ic_set_check_circle else R.drawable.ic_restore, Color.WHITE, 14), lp(sp(14), sp(14)).apply { marginEnd = dp(4) })
                addView(c.text(c.getString(if (ok) R.string.biz_pay_confirmed else R.string.biz_rep_not_confirmed), 12f, MT.gray))
            }
        }.toTypedArray())
    }
    return sheet.page
}
