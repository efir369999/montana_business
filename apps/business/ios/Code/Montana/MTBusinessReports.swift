import SwiftUI
import UIKit

// ════════════════════════════════════════════════════════════
// MONTANA BUSINESS: THE PAYROLL REPORT (stage 10)
// ════════════════════════════════════════════════════════════
// The payouts of a chosen period, as the organisation's TimeChain holds them: per person the salary and the bonuses, and
// whether each payout is confirmed by the receiver's own receipt or only sent; beside them the time of the shifts begun in the
// period (9.1), confirmed or not -- seconds, never coins: how the seconds are paid is the author's word, not this page's. A payout's moment is its Pay record's (the
// core's pay[].at_ms); a payout the core names without a moment is counted nowhere and said so. The CSV is written on this
// phone and handed to the platform's own sheet.

/// One person's line of the payroll: their payouts in the period, summed by kind and by state.
struct MTBizPayrollLine: Identifiable {
    let member: String
    let name: String
    var salary: UInt64 = 0
    var bonus: UInt64 = 0
    var confirmed: UInt64 = 0
    var sent: UInt64 = 0
    var pays: [MTBizView.Pay] = []
    var seconds: UInt64 = 0           // closed shifts begun in the period
    var secondsConfirmed: UInt64 = 0  // of them, confirmed by somebody else
    var shifts: [MTBizView.Shift] = []
    var id: String { member }
    var total: UInt64 { salary + bonus }
}

enum MTBizPayroll {
    /// The period's payouts by person, the largest total first; and how many payouts carry no moment.
    static func lines(_ v: MTBizView, from: Date, to: Date) -> (lines: [MTBizPayrollLine], undated: Int) {
        let lo = UInt64(max(0, from.timeIntervalSince1970 * 1000))
        let hi = UInt64(max(0, to.timeIntervalSince1970 * 1000))
        var by: [String: MTBizPayrollLine] = [:]
        var undated = 0
        for p in v.pay {
            guard let at = p.at_ms else { undated += 1; continue }
            guard lo <= at, at < hi else { continue }
            var l = by[p.member] ?? MTBizPayrollLine(member: p.member, name: v.member(p.member)?.name ?? "")
            if p.kind == MTBizCommand.bonusPay { l.bonus += p.coins } else { l.salary += p.coins }
            if p.confirmed { l.confirmed += p.coins } else { l.sent += p.coins }
            l.pays.append(p)
            by[p.member] = l
        }
        for s in v.shiftList where !s.open && lo <= s.open_ms && s.open_ms < hi {
            var l = by[s.member] ?? MTBizPayrollLine(member: s.member, name: v.member(s.member)?.name ?? "")
            let sec = s.seconds ?? 0
            l.seconds += sec
            if s.confirmed_by != nil { l.secondsConfirmed += sec }
            l.shifts.append(s)
            by[s.member] = l
        }
        return (by.values.sorted { ($0.total, $0.seconds) > ($1.total, $1.seconds) }, undated)
    }
    /// The CSV of the period: one row per payout and per shift -- the moment (UTC, ISO 8601), the person, the kind, the period a
    /// salary payout pays (ISO 8601 days on UTC, half-open: 2026-10-01/2026-11-01; empty for a bonus and a shift), the coins (a
    /// payout) or the seconds (a shift), the state, the record. The header and the words in the person's language; a field is quoted when it holds a comma, a quote or a break.
    static func csv(_ lines: [MTBizPayrollLine]) -> String {
        func field(_ s: String) -> String {
            let needs = s.contains(",") || s.contains("\"") || s.contains("\n")
            return needs ? "\"" + s.replacingOccurrences(of: "\"", with: "\"\"") + "\"" : s
        }
        let b = MTLanguage.bundle
        let head = [String(localized: "Date", bundle: b), String(localized: "Person", bundle: b), String(localized: "Kind", bundle: b),
                    String(localized: "Period", bundle: b), String(localized: "Coins", bundle: b), String(localized: "Seconds", bundle: b),
                    String(localized: "State", bundle: b), String(localized: "Record", bundle: b)]
        var rows = [head.map(field).joined(separator: ",")]
        let iso = ISO8601DateFormatter()
        // A period by the core's own bounds (pay[].from_ms, to_ms): its first day and the day it ends, ISO 8601 on UTC.
        let day = ISO8601DateFormatter()
        day.formatOptions = [.withFullDate]
        day.timeZone = TimeZone(identifier: "UTC")
        func period(_ p: MTBizView.Pay) -> String {
            guard let from = p.from_ms, let to = p.to_ms, from < to else { return "" }
            return day.string(from: Date(timeIntervalSince1970: TimeInterval(from) / 1000)) + "/"
                + day.string(from: Date(timeIntervalSince1970: TimeInterval(to) / 1000))
        }
        for l in lines {
            for p in l.pays.sorted(by: { ($0.at_ms ?? 0) < ($1.at_ms ?? 0) }) {
                let when = iso.string(from: Date(timeIntervalSince1970: TimeInterval(p.at_ms ?? 0) / 1000))
                let kind = p.kind == MTBizCommand.bonusPay ? String(localized: "Bonus", bundle: b) : String(localized: "Salary", bundle: b)
                let state = p.confirmed ? String(localized: "Confirmed", bundle: b) : String(localized: "Sent", bundle: b)
                rows.append([when, l.name, kind, period(p), String(p.coins), "", state, p.record].map(field).joined(separator: ","))
            }
            for s in l.shifts.sorted(by: { $0.open_ms < $1.open_ms }) {
                let when = iso.string(from: Date(timeIntervalSince1970: TimeInterval(s.open_ms) / 1000))
                let state = s.confirmed_by != nil ? String(localized: "Confirmed", bundle: b) : String(localized: "Not confirmed", bundle: b)
                rows.append([when, l.name, String(localized: "Shift", bundle: b), "", "", String(s.seconds ?? 0), state, s.open_record]
                    .map(field).joined(separator: ","))
            }
        }
        return rows.joined(separator: "\r\n") + "\r\n"
    }
}

/// THE PAYROLL FOR A PERIOD: the period chosen with the platform's own date pickers, the totals, a line per person that opens
/// their payouts, and the CSV through the platform's sheet.
struct MTBizPayrollPage: View {
    let org: String
    @ObservedObject private var biz = MTBusiness.shared
    @State private var from = Calendar.current.dateInterval(of: .month, for: Date())?.start ?? Date()
    @State private var to = Date()
    private var v: MTBizView? { biz.views[org] }

    var body: some View {
        List {
            if let v {
                let got = MTBizPayroll.lines(v, from: from, to: dayEnd(to))
                Section {
                    DatePicker("Period start", selection: $from, in: ...to, displayedComponents: .date)
                    DatePicker("Period end", selection: $to, in: from..., displayedComponents: .date)
                } header: { Text("Period") }
                .listRowBackground(MTGlassRowPlate())
                Section {
                    total("Salary", got.lines.reduce(0) { $0 + $1.salary })
                    total("Bonuses", got.lines.reduce(0) { $0 + $1.bonus })
                    total("Confirmed", got.lines.reduce(0) { $0 + $1.confirmed })
                    total("Sent, not confirmed yet", got.lines.reduce(0) { $0 + $1.sent })
                    time("Time on shifts", got.lines.reduce(0) { $0 + $1.seconds })
                    time("Of it confirmed", got.lines.reduce(0) { $0 + $1.secondsConfirmed })
                    Button { save(got.lines, v) } label: { MTBizRowLabel(glyph: "square.and.arrow.up", title: "Save as CSV") }
                        .disabled(got.lines.isEmpty)
                } header: { Text("Total") } footer: {
                    VStack(alignment: .leading, spacing: 6) {
                        // What «confirmed» means, said where the admin reads the sums (the receipts, point 0, fourth pass).
                        Text("A payout is confirmed once the receiver's phone has taken its coins and signed a receipt.")
                        if 0 < got.undated {
                            Text("Some payouts carry no moment yet and are not counted; they come with the next update of the app's core.")
                        }
                    }
                }
                .listRowBackground(MTGlassRowPlate())
                Section {
                    if got.lines.isEmpty {
                        Text("No payouts in this period").foregroundColor(.gray).frame(minHeight: 44)
                    }
                    ForEach(got.lines) { l in
                        NavigationLink { MTBizPayrollPerson(line: l) } label: {
                            HStack(spacing: 12) {
                                MTBizFace(name: l.name)
                                VStack(alignment: .leading, spacing: 2) {
                                    // USER-DATA: the person's name
                                    Text(verbatim: l.name).foregroundColor(.white)
                                    // USER-DATA: the salary and the bonuses of the period, in coins, and the time on shifts
                                    Text(verbatim: MTBizText.coins(l.salary) + " + " + MTBizText.coins(l.bonus)
                                         + (l.seconds == 0 ? "" : " · " + MTBizText.span(l.seconds * 1000)))
                                        .font(.caption).foregroundColor(.gray)
                                }
                                Spacer()
                                // USER-DATA: the person's total of the period
                                Text(verbatim: MTBizText.coins(l.total)).foregroundColor(.white).monospacedDigit()
                            }
                            .frame(minHeight: 44)
                            .accessibilityElement(children: .combine)
                            .contentShape(Rectangle())
                        }
                    }
                } header: { Text("People") }
                .listRowBackground(MTGlassRowPlate())
            }
        }
        .mtBizPage("Payroll")
        .onAppear { biz.redraw() }
    }

    private func total(_ title: LocalizedStringKey, _ coins: UInt64) -> some View {
        LabeledContent {
            // USER-DATA: a sum of coins
            Text(verbatim: MTBizText.coins(coins)).monospacedDigit()
        } label: { Text(title) }
        .frame(minHeight: 44)
    }
    private func time(_ title: LocalizedStringKey, _ seconds: UInt64) -> some View {
        LabeledContent {
            // USER-DATA: a span of time, in the system's words
            Text(verbatim: seconds == 0 ? "0" : MTBizText.span(seconds * 1000)).monospacedDigit()
        } label: { Text(title) }
        .frame(minHeight: 44)
    }
    /// The «to» day is counted whole: its end, not its first instant.
    private func dayEnd(_ d: Date) -> Date {
        Calendar.current.dateInterval(of: .day, for: d)?.end ?? d
    }
    private func save(_ lines: [MTBizPayrollLine], _ v: MTBizView) {
        // The period in the file's name as ISO 8601 days (2026-10-01): one reading in every country, sorted by name as by time.
        let day = Date.ISO8601FormatStyle(timeZone: .current).year().month().day()
        let span = from.formatted(day) + " " + to.formatted(day)
        let file = MTBizPlace.fileName(v.org?.name ?? "", or: "payroll") + " " + span + ".csv"   // NOT-UI: a file name
        let urls = MTBizPlace.export([(file, Data(MTBizPayroll.csv(lines).utf8))])
        if !urls.isEmpty { MTShare.present(urls) }
    }
}

/// One person's payouts in the period: the moment, the kind, the coins, the state.
struct MTBizPayrollPerson: View {
    let line: MTBizPayrollLine
    var body: some View {
        List {
            Section {
                // The payout's day stands in its own row (MTBizPayRow), as on the person's card.
                ForEach(line.pays.sorted { ($0.at_ms ?? 0) > ($1.at_ms ?? 0) }) { p in MTBizPayRow(pay: p) }
            }
            .listRowBackground(MTGlassRowPlate())
            if !line.shifts.isEmpty {
                Section {
                    ForEach(line.shifts.sorted { $0.open_ms > $1.open_ms }) { s in
                        HStack(spacing: 12) {
                            MTBizGlyph("clock.fill")
                            VStack(alignment: .leading, spacing: 2) {
                                // USER-DATA: the shift's length, in the system's words
                                Text(verbatim: MTBizText.span((s.seconds ?? 0) * 1000)).foregroundColor(.white).monospacedDigit()
                                // USER-DATA: the moment the shift began
                                Text(verbatim: MTBizText.when(s.open_ms)).font(.caption).foregroundColor(.gray)
                            }
                            Spacer()
                            MTBizNote(glyph: s.confirmed_by != nil ? "checkmark.circle.fill" : "hourglass",
                                      text: Text(s.confirmed_by != nil ? "Confirmed" : "Not confirmed"))
                        }
                        .frame(minHeight: 44)
                        .accessibilityElement(children: .combine)
                    }
                } header: { Text("Shifts") }
                .listRowBackground(MTGlassRowPlate())
            }
        }
        .mtBizPage(named: line.name)
    }
}
