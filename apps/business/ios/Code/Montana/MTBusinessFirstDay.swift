import SwiftUI
import UIKit

// ════════════════════════════════════════════════════════════
// MONTANA BUSINESS: THE FIRST DAY (stage 8.1)
// ════════════════════════════════════════════════════════════
// A person who has just joined sees once, on the Business page, where they stand: the organisation, their role, department and
// position, the manager to turn to, and what to do next -- each next step a row that does it. The invitation's poster is the
// same link as a large code with the organisation's name, for the platform's own share sheet (print, save, send).

/// An organisation to greet: the Business page raises the first day over itself once after a join.
struct MTBizGreet: Identifiable { let id: String }

/// THE FIRST DAY OF A MEMBER: who I am in the organisation, whom I answer to, and the next steps.
struct MTBizFirstDaySheet: View {
    let org: String
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var biz = MTBusiness.shared
    @State private var confirming = false
    private var v: MTBizView? { biz.views[org] }

    var body: some View {
        MTBizSheet(title: "Your first day") {
            if let v, let me = v.member(v.me.member) {
                Section {
                    VStack(alignment: .leading, spacing: 6) {
                        Image(systemName: "building.2.fill").font(.largeTitle).foregroundColor(.white).accessibilityHidden(true)
                        // USER-DATA: the organisation's name, as its owner wrote it
                        Text(verbatim: v.org?.name ?? "").font(.title2.bold()).foregroundColor(.white)
                        Text("Welcome. This is your place in the organization.").font(.subheadline).foregroundColor(.gray)
                    }
                    .padding(.vertical, 8)
                    LabeledContent { Text(v.role?.title ?? "") } label: { Text("Role") }
                    if let d = v.dept(me.dept) {
                        // USER-DATA: the department's name
                        LabeledContent { Text(verbatim: d.name) } label: { Text("Department") }
                    }
                    if !me.title.isEmpty {
                        // USER-DATA: the position, as the administrators wrote it
                        LabeledContent { Text(verbatim: me.title) } label: { Text("Position") }
                    }
                }
                .listRowBackground(MTGlassRowPlate())
                if let boss = MTBizFirstDay.manager(of: me, in: v) {
                    Section {
                        NavigationLink { MTBizMemberPage(org: org, member: boss.member) } label: { MTBizMemberRow(member: boss, view: v) }
                    } header: {
                        // The one to turn to is a manager only when they are one: without a department's manager, the owner.
                        Text(boss.role == MTBizRole.manager.word ? "Your manager" : "Who to turn to")
                    } footer: {
                        Text("Questions about the work, the salary and the shop go to this person.")
                    }
                    .listRowBackground(MTGlassRowPlate())
                }
                Section {
                    if !me.phone_confirmed {
                        Button { confirming = true } label: { MTBizRowLabel(glyph: "phone.badge.checkmark", title: "Confirm your number") }
                    }
                    NavigationLink { SeedShowView().montanaMotionMeter() } label: { MTBizRowLabel(glyph: "key.horizontal.fill", title: "Save 24 words") }
                    if !v.keptNodes.isEmpty {
                        NavigationLink { MTBizOrdersPage(org: org) } label: { MTBizRowLabel(glyph: "shippingbox.fill", title: "Orders of your nodes") }
                    }
                    NavigationLink { MTBizMemberPage(org: org, member: me.member) } label: { MTBizRowLabel(glyph: "person.text.rectangle", title: "My card in the organization") }
                } header: { Text("What to do next") } footer: {
                    VStack(alignment: .leading, spacing: 6) {
                        // The coin letter comes from whoever pays it (MTBusiness.pay: an administrator's own phone), not always the owner.
                        Text("Your payouts come as coin letters in your chat with whoever pays them: the owner or an administrator.")
                        // The words' one honest line, the same as in Settings: what they bring back, and what the number alone does.
                        Text("On a new phone, the 24 words bring back your coins and your saved history; your number alone brings back only your role in your organizations.")
                    }
                }
                .listRowBackground(MTGlassRowPlate())
                if let s = v.salary.first(where: { $0.member == me.member }) {
                    Section {
                        LabeledContent {
                            // USER-DATA: the salary in coins and its period
                            Text(verbatim: MTBizText.coins(s.coins) + " · " + (MTBizPeriod(rawValue: s.period)?.title ?? "")).monospacedDigit()
                        } label: { Text("Salary") }
                    }
                    .listRowBackground(MTGlassRowPlate())
                }
            }
        }
        .sheet(isPresented: $confirming) { MTBizPhoneSheet { confirming = false } }
    }
}

/// The first day's row on the organisation's page, for a member who is not an administrator: the same sheet, any time.
struct MTBizFirstDaySection: View {
    let org: String
    @ObservedObject private var biz = MTBusiness.shared
    @State private var open = false
    var body: some View {
        if let v = biz.views[org], v.role != nil, !MTBusiness.boss(v.me.role) {
            Section {
                Button { open = true } label: { MTBizRowLabel(glyph: "sun.horizon.fill", title: "Your first day") }
            }
            .listRowBackground(MTGlassRowPlate())
            .sheet(isPresented: $open) { MTBizFirstDaySheet(org: org) }
        }
    }
}

enum MTBizFirstDay {
    /// The one a member turns to: the manager of their department, else the owner (never themselves).
    static func manager(of me: MTBizView.Member, in v: MTBizView) -> MTBizView.Member? {
        let active = v.members.filter { $0.active && $0.member != me.member }
        if me.dept != nil, let m = active.first(where: { $0.role == MTBizRole.manager.word && $0.dept == me.dept }) { return m }
        return active.first { $0.role == MTBizRole.owner.word }
    }
}

/// THE INVITATION'S POSTER (8.1): the link as a large code, the organisation's name above it and one line under it -- an A4
/// page in the system's own type, for the platform's sheet: print it, save it, send it.
enum MTBizPoster {
    static func image(link: String, org: String) -> UIImage? {
        let page = CGSize(width: 1240, height: 1754)   // A4 at 150 points to the inch
        guard let code = MontanaQR.image(Data(link.utf8), side: 900, scale: 1, correction: "M") else { return nil }
        let title = org
        let line = String(localized: "Scan the code with the phone's camera to join \(org).", bundle: MTLanguage.bundle)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1   // the page is its own pixels: 1240 by 1754
        return UIGraphicsImageRenderer(size: page, format: format).image { ctx in
            UIColor.white.setFill()
            ctx.fill(CGRect(origin: .zero, size: page))
            let center = NSMutableParagraphStyle()
            center.alignment = .center
            let head: [NSAttributedString.Key: Any] = [.font: UIFont.systemFont(ofSize: 72, weight: .bold), .foregroundColor: UIColor.black,
                                                       .paragraphStyle: center]
            let body: [NSAttributedString.Key: Any] = [.font: UIFont.systemFont(ofSize: 40, weight: .regular), .foregroundColor: UIColor.darkGray,
                                                       .paragraphStyle: center]
            (title as NSString).draw(in: CGRect(x: 80, y: 140, width: page.width - 160, height: 200), withAttributes: head)
            code.draw(in: CGRect(x: (page.width - 900) / 2, y: 400, width: 900, height: 900))
            (line as NSString).draw(in: CGRect(x: 120, y: 1380, width: page.width - 240, height: 200), withAttributes: body)
        }
    }
}
