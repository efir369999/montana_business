import SwiftUI
import UIKit

// ════════════════════════════════════════════════════════════
// MONTANA BUSINESS: MANAGING THE PLACES (stages 7.3 and 7.4)
// ════════════════════════════════════════════════════════════
// A NEW PHONE (7.3): the person had a place on an old phone and comes back on a new one with a new key. The number is confirmed
// for the new key (MTBA), an administrator's invitation brings the roster, the person chooses themselves, and the
// administrator sees «a new phone: the name, the number» and moves the place with one touch (Rekey); the new phone then binds
// its number (PhoneBind). The coin book does not move: it comes back only from the 24 words. A REMOVED PERSON'S PHONE lets the
// organisation go (MTBusiness.dropRemoved) and says so in one line.

/// THE NEW PHONES WAITING FOR AN ADMINISTRATOR (on the organisation's page): one row per request, the name the person chose,
/// the number the service confirmed for the new key and whether it is the member's own number; the row's one touch moves the
/// place, its swipe declines.
struct MTBizMovesSection: View {
    let org: String
    @ObservedObject private var biz = MTBusiness.shared
    @State private var refused = false

    var body: some View {
        let waiting = biz.moves.filter { $0.org == org }
        if let v = biz.views[org], MTBusiness.boss(v.me.role), !waiting.isEmpty {
            Section {
                ForEach(waiting) { m in
                    // The touch stands only where the core takes the Rekey from this viewer (fold.rs): an administrator does not
                    // move another administrator's place, nor the owner's.
                    Group {
                        if v.mayRekey(m.member) {
                            Button { Task { if !(await biz.confirmMove(m)) { refused = true } } } label: { row(m, v, movable: true) }
                        } else {
                            row(m, v, movable: false)
                        }
                    }
                    .swipeActions {
                        Button(role: .destructive) { biz.declineMove(m) } label: { Label("Decline", systemImage: "xmark") }
                    }
                }
            } header: { Text("New phones") } footer: {
                Text("One touch moves the place to the new phone; the old phone loses it.")
            }
            .listRowBackground(MTGlassRowPlate())
            .mtBizRefused($refused)
        }
    }

    private func row(_ m: MTBizLocal.Move, _ v: MTBizView, movable: Bool) -> some View {
        let member = v.member(m.member)
        // The number the service confirmed for the new key, against the member's own confirmed number, where the view shows it.
        let same: Bool? = member?.phone.map { $0 == m.e164 }
        return HStack(spacing: 12) {
            MTBizFace(name: member?.name ?? "")
            VStack(alignment: .leading, spacing: 2) {
                // USER-DATA: the person's name, as the roster holds it
                Text(verbatim: member?.name ?? "").foregroundColor(.white)
                // USER-DATA: the number the service confirmed for the new phone
                Text(verbatim: m.e164).font(.caption).foregroundColor(.gray).monospacedDigit()
                switch same {
                case true?: MTBizNote(glyph: "checkmark.seal.fill", text: Text("The number matches"))
                case false?: MTBizNote(glyph: "exclamationmark.triangle.fill", text: Text("The number does not match"))
                case nil: MTBizNote(glyph: "questionmark.circle", text: Text("No number to compare with"))
                }
                if !movable {
                    MTBizNote(glyph: "info.circle", text: Text("Only the owner, or this person on the old phone, can move this place."))
                }
            }
            Spacer()
            if movable { Image(systemName: "iphone.and.arrow.forward").foregroundColor(.white).accessibilityHidden(true) }
        }
        .frame(minHeight: 44)
        .accessibilityElement(children: .combine)
        .contentShape(Rectangle())
    }
}

/// THE NEW PHONE'S WAY BACK TO ITS PLACE (the Business page): the number first, then the administrator's invitation -- its code
/// or its copied link -- opened as a move.
struct MTBizMoveSheet: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var biz = MTBusiness.shared
    @State private var scanning = false
    @State private var confirming = false
    @State private var phone = MTBizPhone.confirmed()
    @State private var word: String?

    var body: some View {
        MTBizSheet(title: "A new phone") {
            Section {
                if phone == nil {
                    Button { confirming = true } label: { MTBizRowLabel(glyph: "phone.badge.checkmark", title: "Confirm your number") }
                } else {
                    Button { scanning = true } label: { MTBizRowLabel(glyph: "qrcode.viewfinder", title: "Scan an invitation code") }
                    Button { word = biz.acceptCopied(move: true); if word == nil { dismiss() } } label: {
                        MTBizRowLabel(glyph: "doc.on.clipboard", title: "Join by a copied link")
                    }
                }
            } footer: {
                Text("Ask an administrator of your organization for an invitation, open it here and choose yourself. The administrator moves your place to this phone with one touch.")
            }
            .listRowBackground(MTGlassRowPlate())
        }
        .sheet(isPresented: $scanning) { MTBizScanSheet { u in scanning = false; biz.accept(u, move: true); dismiss() } }
        .sheet(isPresented: $confirming) { MTBizPhoneSheet { confirming = false; phone = MTBizPhone.confirmed() } }
        .mtBizWord($word)
    }
}

/// A move on its way (the Business page's row in place of «Joining»): the roster has not come yet; it came -- choose yourself;
/// chosen -- waiting for the administrator's touch.
struct MTBizMoveRow: View {
    let join: MTBizLocal.Joining
    @ObservedObject private var biz = MTBusiness.shared
    var body: some View {
        if join.mine != nil {
            MTBizWaitRow(title: "Waiting for an administrator to confirm this phone")
        } else if let v = biz.views[join.org], v.org != nil {
            NavigationLink { MTBizChooseSelfPage(org: join.org) } label: {
                MTBizRowLabel(glyph: "person.crop.circle.badge.questionmark", title: "Choose yourself in \(v.org?.name ?? "")")
            }
        } else {
            MTBizWaitRow(title: "Joining an organization…", detail: MTBizWaitRow.fromInviter)
        }
    }
}

/// THE ROSTER, TO CHOOSE ONESELF IN: the touch asks the administrator to move that place to this phone.
struct MTBizChooseSelfPage: View {
    let org: String
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var biz = MTBusiness.shared
    @State private var refused = false

    var body: some View {
        List {
            if let v = biz.views[org] {
                Section {
                    ForEach(v.members.filter { $0.status != "removed" }) { m in
                        Button { Task { if await biz.askMove(org, member: m.member) { dismiss() } else { refused = true } } } label: {
                            MTBizMemberRow(member: m, view: v)
                        }
                    }
                } header: { Text("Which one is you?") } footer: {
                    Text("The administrator sees your choice beside the number this phone confirmed.")
                }
                .listRowBackground(MTGlassRowPlate())
            }
        }
        .mtBizPage(named: biz.views[org]?.org?.name ?? "")
        .mtBizRefused($refused, "Confirm your number first")
    }
}

/// THE ONE LINE OF A REMOVED PERSON'S PHONE: the organisation let them go, and its records are erased here.
struct MTBizLeftRow: View {
    let org: String
    let name: String
    var body: some View {
        HStack(spacing: 12) {
            MTBizGlyph("person.crop.circle.badge.xmark")
            Text("You were removed from \(name). Its records are erased from this phone.").font(.subheadline).foregroundColor(.gray)
            Spacer()
        }
        .frame(minHeight: 44)
        .accessibilityElement(children: .combine)
        .swipeActions {
            Button { MTBusiness.shared.hideLeft(org) } label: { Label("Hide", systemImage: "eye.slash") }
        }
    }
}

// -- the journal (7.4) --------------------------------------------------------------------------------------------------

/// The words of the journal: a record's kind as the commands name it, in the person's language. The supply chain's steps are
/// the order page's own words (MTBizSupplyText.step).
enum MTBizJournalText {
    static func title(_ kind: String) -> LocalizedStringKey? {
        switch kind {
        case "genesis": return "Organization founded"
        case "invite": return "Invitation"
        case "join": return "Joined"
        case "role": return "Role"
        case "dept": return "Department"
        case "assign": return "Department and position"
        case "remove": return "Removed from the organization"
        case "rekey": return "Place moved to a new phone"
        case "revoke": return "Invitation revoked"
        case "profile": return "Name and link"
        case "item": return "Product"
        case "node": return "Node"
        case "node_staff": return "People of the node"
        case "phone_bind": return "Number confirmed"
        case "invite_phone": return "Invitation by number"
        case "open": return "Chat opened"
        case "letter": return "Letter"
        case "edit": return "Letter edited"
        case "delete": return "Letter deleted"
        default:
            let step = MTBizSupplyText.step(kind)
            return step.glyph == "circle" ? nil : step.title
        }
    }
    static func glyph(_ chain: String) -> String {
        switch chain {
        case "R": return "person.3.fill"
        case "H": return "person.text.rectangle.fill"
        case "S": return "shippingbox.fill"
        case "C": return "bubble.left.and.bubble.right.fill"
        default: return "circle"
        }
    }
}

/// THE RECORDS' ROWS at the foot of the organisation's page: the payroll for the administrators and the journal for everyone,
/// in one section -- two rows that stood in two sections of their own.
struct MTBizJournalSection: View {
    let org: String
    @ObservedObject private var biz = MTBusiness.shared
    var body: some View {
        Section {
            NavigationLink { MTBizJournalPage(org: org) } label: { MTBizRowLabel(glyph: "list.bullet.rectangle.portrait.fill", title: "Journal") }
        }
        .listRowBackground(MTGlassRowPlate())
    }
}

/// THE ORGANISATION'S JOURNAL (7.4): the records this person may see -- the roster's and their own closed lanes -- the newest
/// first, each with its kind, its signer, its moment and the fold's verdict where it is not «applied». The core hands the last
/// two hundred (mt_biz_view journal); the whole chains go to Files through the platform's own sheet.
struct MTBizJournalPage: View {
    let org: String
    @ObservedObject private var biz = MTBusiness.shared
    private var v: MTBizView? { biz.views[org] }

    var body: some View {
        List {
            if let v {
                Section {
                    Button { save() } label: { MTBizRowLabel(glyph: "square.and.arrow.up", title: "Save the TimeChains to Files") }
                } footer: { Text("The organization's TimeChains as this phone holds them: the signed records themselves, to keep. For a table, save the payroll as CSV.") }
                .listRowBackground(MTGlassRowPlate())
                Section {
                    if let entries = v.journal {
                        if entries.isEmpty {
                            Text("No records yet").foregroundColor(.gray).frame(minHeight: 44)
                        }
                        ForEach(entries.sorted { $0.at_ms > $1.at_ms }) { e in row(e, v) }
                    } else {
                        Text("The journal comes with the next update of the app's core.").foregroundColor(.gray).frame(minHeight: 44)
                    }
                } footer: { Text("The last records you may see: the roster's and your own.") }
                .listRowBackground(MTGlassRowPlate())
            }
        }
        .mtBizPage("Journal")
        .onAppear { biz.redraw() }
    }

    private func row(_ e: MTBizView.Entry, _ v: MTBizView) -> some View {
        HStack(alignment: .top, spacing: 12) {
            MTBizGlyph(MTBizJournalText.glyph(e.chain)).padding(.top, 2)
            VStack(alignment: .leading, spacing: 2) {
                if let t = MTBizJournalText.title(e.kind) {
                    Text(t).foregroundColor(.white)
                } else {
                    // USER-DATA: a record's kind as the core names it, for a kind this build has no word for
                    Text(verbatim: e.kind).foregroundColor(.white)
                }
                // USER-DATA: who signed the record and when
                Text(verbatim: [v.member(e.author)?.name ?? "", MTBizText.when(e.at_ms)].filter { !$0.isEmpty }.joined(separator: " · "))
                    .font(.caption).foregroundColor(.gray)
                if e.outcome == "rejected" {
                    MTBizNote(glyph: "xmark.octagon", text: Text("Refused by the organization's rules"))
                } else if e.outcome == "waiting" {
                    MTBizNote(glyph: "hourglass", text: Text("Waits for an earlier record"))
                }
            }
            Spacer()
        }
        .padding(.vertical, 2)
        .frame(minHeight: 44)
        .accessibilityElement(children: .combine)
    }

    /// The chains go out as they lie on this phone, under the organisation's name, through the platform's own sheet.
    private func save() {
        Task {
            guard let urls = await MTBusiness.shared.chainFiles(org), !urls.isEmpty else { return }
            MTShare.present(urls)
        }
    }
}
