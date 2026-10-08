import SwiftUI

// ════════════════════════════════════════════════════════════
// MONTANA BUSINESS: SHIFTS (stage 9.1, the economy of time)
// ════════════════════════════════════════════════════════════
// A shift is kept by its member -- opened and closed on their own phone -- and confirmed by somebody else: an administrator or
// the manager of the member's department. Its records lie on the member's own lane of shifts in chain H, apart from the lane
// of their pay, so the manager holds the hours and never the pay. The seconds are counted by the core from the records' own
// times; how they are paid is not decided here.

struct MTBizShiftsSection: View {
    let org: String
    let view: MTBizView
    @State private var refused = false

    private var me: String { view.me.member }
    private var mineOpen: MTBizView.Shift? { view.shiftList.first { $0.member == me && $0.open } }
    private var mineDone: [MTBizView.Shift] {
        Array(view.shiftList.filter { $0.member == me && !$0.open }.sorted { $1.open_ms < $0.open_ms }.prefix(5))
    }
    /// Who confirms this person's own shift (fold.rs ShiftConfirm): the manager of their department where there is one, and
    /// any administrator -- said by name of role, so a closed shift never just «waits».
    private var confirmer: LocalizedStringKey {
        let dept = view.member(me)?.dept
        let managed = dept != nil && view.members.contains {
            $0.active && $0.member != me && $0.role == MTBizRole.manager.word && $0.dept == dept
        }
        return managed ? "Waits for your manager or an administrator to confirm" : "Waits for an administrator to confirm"
    }
    private var toConfirm: [MTBizView.Shift] {
        guard view.may("shift_confirm") else { return [] }
        return view.shiftList.filter { $0.member != me && !$0.open && $0.confirmed_by == nil }.sorted { $0.open_ms < $1.open_ms }
    }

    var body: some View {
        if view.may("shift_open") || !toConfirm.isEmpty {
            Section {
                if let s = mineOpen {
                    TimelineView(.periodic(from: .now, by: 1)) { ctx in
                        HStack(spacing: 12) {
                            MTBizGlyph("clock.fill")
                            Text("On shift").foregroundColor(.white)
                            Spacer()
                            // USER-DATA: the running time of the open shift
                            Text(verbatim: MTBizText.clock(Self.since(s.open_ms, ctx.date))).foregroundColor(.gray).monospacedDigit()
                        }
                        .frame(minHeight: 44)
                        .accessibilityElement(children: .combine)
                    }
                    Button { act(MTBizCommand.shiftClose(note: "")) } label: { MTBizRowLabel(glyph: "stop.circle", title: "End shift") }
                } else if view.may("shift_open") {
                    Button { act(MTBizCommand.shiftOpen()) } label: { MTBizRowLabel(glyph: "play.circle", title: "Start shift") }
                }
                ForEach(mineDone) { s in row(s, name: nil, confirming: false) }
                ForEach(toConfirm) { s in
                    Button { act(MTBizCommand.shiftConfirm(s.close_record ?? "")) } label: {
                        row(s, name: view.member(s.member)?.name, confirming: true)
                    }
                }
            } header: { Text("Shifts") } footer: {
                Text("A shift's seconds are counted from its own records; it is confirmed by somebody other than the one who worked it.")
            }
            .listRowBackground(MTGlassRowPlate())
            .mtBizRefused($refused)
        }
    }

    private func row(_ s: MTBizView.Shift, name: String?, confirming: Bool) -> some View {
        HStack(spacing: 12) {
            MTBizGlyph(s.confirmed_by != nil ? "checkmark.seal.fill" : (confirming ? "checkmark.circle" : "clock"),
                       said: s.confirmed_by != nil ? "Confirmed" : nil)
            VStack(alignment: .leading, spacing: 2) {
                if let name { Text(verbatim: name).foregroundColor(.white) }   // USER-DATA: the member's name
                // USER-DATA: when the shift began
                Text(verbatim: MTBizText.when(s.open_ms)).font(name == nil ? .body : .caption).foregroundColor(name == nil ? .white : .gray)
                if confirming {
                    Text("Tap to confirm").font(.caption).foregroundColor(.gray)
                } else if s.confirmed_by == nil {
                    Text(confirmer).font(.caption).foregroundColor(.gray)
                }
            }
            Spacer()
            Text(verbatim: MTBizText.clock(s.seconds ?? 0)).foregroundColor(.gray).monospacedDigit()   // USER-DATA: the shift's length
        }
        .frame(minHeight: 44)
        .accessibilityElement(children: .combine)
        .contentShape(Rectangle())
    }

    @MainActor private func act(_ cmd: [String: Any]) {
        Task { if await MTBusiness.shared.write(org, cmd) == nil { refused = true } }
    }

    static func since(_ ms: UInt64, _ now: Date) -> UInt64 {
        let t = UInt64(max(0, now.timeIntervalSince1970 * 1000))
        return ms < t ? (t - ms) / 1000 : 0
    }
}
