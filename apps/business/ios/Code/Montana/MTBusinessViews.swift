import SwiftUI
import UniformTypeIdentifiers
import UIKit

// ════════════════════════════════════════════════════════════
// MONTANA BUSINESS: THE PAGES
// ════════════════════════════════════════════════════════════
// The platform's own list, its sections and rows on the one-tone glass (MTGlassRowPlate), the page's ground, the marks of the
// bar (the cross, the checkmark): the Business looks as the Settings do. Every row is a button on all of itself; every word
// comes from the catalogue. Android draws the same pages from the same view of the core (MtBusiness.nativeView).

/// THE BUSINESS'S WORDS FOR NUMBERS AND TIME, ONE OWNER (point 0 of the constitution): coins as the wallet writes them, a day, a moment, a span and
/// a running clock -- all in the person's language (MTLanguage.locale), never the system's region by accident.
enum MTBizText {
    static func coins(_ n: UInt64) -> String { MTCoinText.count(Int(clamping: n)) + " " + MTCoinBook.ticker }
    static func day(_ ms: UInt64) -> String {
        Date(timeIntervalSince1970: TimeInterval(ms) / 1000).formatted(Date.FormatStyle(date: .abbreviated, time: .omitted, locale: MTLanguage.locale))
    }
    static func when(_ ms: UInt64) -> String {
        Date(timeIntervalSince1970: TimeInterval(ms) / 1000)
            .formatted(Date.FormatStyle(date: .abbreviated, time: .shortened, locale: MTLanguage.locale))
    }
    /// A span in the system's own words for it (two units at most): the time a step stood, the length of a shift.
    static func span(_ ms: UInt64) -> String {
        Duration.milliseconds(Int64(clamping: ms))
            .formatted(.units(allowed: [.days, .hours, .minutes, .seconds], width: .abbreviated, maximumUnitCount: 2).locale(MTLanguage.locale))
    }
    /// Time on shifts in hours and minutes, however many hours: a week's or a month's work is not counted in days. The
    /// minute is cut down, never rounded up: worked time is never shown longer than it was.
    static func hours(_ seconds: UInt64) -> String {
        Duration.seconds(Int64(clamping: seconds))
            .formatted(.units(allowed: [.hours, .minutes], width: .abbreviated, fractionalPart: .hide(rounded: .down)).locale(MTLanguage.locale))
    }
    /// A DUE PERIOD AS THE SYSTEM NAMES DATES, on UTC -- the core's own bounds [from, to) (contract 1.3), no calendar counted
    /// here: a month «October 2026», a week its first and last days, a day its date. The kind is the period key's own
    /// (period.rs: kind << 28 | index). Nil where the core names no bounds (a core before 1.3).
    static func period(_ d: MTBizView.Due) -> String? {
        guard let from = d.from_ms, let to = d.to_ms, from < to else { return nil }
        let utc = TimeZone(identifier: "UTC") ?? .gmt
        let first = Date(timeIntervalSince1970: TimeInterval(from) / 1000)
        let last = Date(timeIntervalSince1970: TimeInterval(to - 1) / 1000)   // the last instant inside [from, to)
        switch d.period_key >> 28 {
        case 3: return first.formatted(Date.FormatStyle(locale: MTLanguage.locale, timeZone: utc).month(.wide).year())
        case 2: return Date.IntervalFormatStyle(date: .abbreviated, time: .omitted, locale: MTLanguage.locale, timeZone: utc).format(first..<last)
        default: return first.formatted(Date.FormatStyle(date: .abbreviated, time: .omitted, locale: MTLanguage.locale, timeZone: utc))
        }
    }
    /// A running clock, hours:minutes:seconds, as the platform's timers show one.
    static func clock(_ seconds: UInt64) -> String {
        Duration.seconds(Int64(clamping: seconds)).formatted(.time(pattern: .hourMinuteSecond).locale(MTLanguage.locale))
    }
    static func verdict(_ v: MTBusiness.Verdict) -> String? {
        switch v {
        case .done: return nil
        case .short: return "Not enough coins"
        case .noRoad: return "There is no chat with this person yet. Open a chat with them, then try again."
        case .refused: return "The organization did not take this. Check your rights in it."
        }
    }
}

/// THE BUSINESS'S ONE SHEET (point 0 of the constitution): the platform's navigation stack, its list on the page's ground, the cross at the top left
/// and -- where the sheet takes something -- the checkmark at the top right. Every form of the Business stands on it, so no
/// two of them can differ in their frame.
struct MTBizSheet<Content: View>: View {
    let title: LocalizedStringKey
    var done: (() -> Void)? = nil
    var busy = false
    /// The checkmark stands dimmed until the sheet holds what it asks, as the platform's own «Add» does: a touch on it
    /// never meets a refusal for an empty field.
    var ready = true
    @ViewBuilder var content: () -> Content
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            List { content() }
                .mtBizPage(title)
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) { MontanaCloseMark { dismiss() } }
                    if let done {
                        ToolbarItem(placement: .topBarTrailing) { MontanaDoneMark { done() }.disabled(busy || !ready) }
                    }
                }
        }
        .preferredColorScheme(.dark)
    }
}

/// THE PLATFORM'S CODE SCANNER WITH ONE LINE OF WORDS (point 0 of the constitution, one owner): the camera edge to edge, the line on the system's
/// material at the foot -- it takes no finger. A code is handed on trimmed; the screen that asked decides what it means.
struct MTBizScanner: View {
    let prompt: LocalizedStringKey
    let found: (String) -> Void
    var body: some View {
        ZStack {
            QRScannerView { code in found(code.trimmingCharacters(in: .whitespacesAndNewlines)) }.ignoresSafeArea()
            VStack {
                Spacer()
                Text(prompt).font(.subheadline).foregroundColor(.white).multilineTextAlignment(.center)
                    .padding(.horizontal, 16).padding(.vertical, 10)
                    .background(.regularMaterial, in: Capsule())
                    .padding(.bottom, 40)
            }
            .allowsHitTesting(false)
        }
    }
}

extension View {
    /// A page of the Business: the platform's list on the page's ground, its title inline.
    func mtBizPage(_ title: LocalizedStringKey) -> some View {
        scrollContentBackground(.hidden).montanaPageGround().navigationTitle(title).navigationBarTitleDisplayMode(.inline)
    }
    /// The same page under a name a person gave (an organisation, a member, a node).
    func mtBizPage(named name: String) -> some View {
        // USER-DATA: the name of the organisation, the member or the node the page stands for
        scrollContentBackground(.hidden).montanaPageGround().navigationTitle(Text(verbatim: name)).navigationBarTitleDisplayMode(.inline)
    }
    /// The organisation's refusal of a record, in the platform's alert.
    func mtBizRefused(_ shown: Binding<Bool>, _ title: LocalizedStringKey = "The organization did not take this. Check your rights in it.") -> some View {
        alert(title, isPresented: shown) { Button("OK", role: .cancel) {} }
    }
    /// THE SHEET'S FIRST FIELD TAKES THE KEYBOARD AT ONCE, as the platform's own «New Folder» does: no touch to begin typing;
    /// where the sheet asks one line, the keyboard's return key does what the checkmark does. The focus waits for the sheet to
    /// settle -- a field focused while its sheet still rises does not take the keyboard.
    func mtBizFirstField(_ focus: FocusState<Bool>.Binding, submit: (() -> Void)? = nil) -> some View {
        focused(focus)
            .submitLabel(submit == nil ? .return : .done)
            .onSubmit { submit?() }
            .task {
                try? await Task.sleep(nanoseconds: 400_000_000)
                focus.wrappedValue = true
            }
    }
    /// One word of the Business to the person, in the platform's alert; the alert ends the word.
    func mtBizWord(_ word: Binding<String?>, title: LocalizedStringKey = "Organization") -> some View {
        alert(title, isPresented: Binding(get: { word.wrappedValue != nil }, set: { if !$0 { word.wrappedValue = nil } })) {
            Button("OK", role: .cancel) { word.wrappedValue = nil }
        } message: { Text(LocalizedStringKey(word.wrappedValue ?? "")) }
    }
}

/// THE GLYPH OF A BUSINESS ROW (point 0, fourth pass: accessibility): white, semibold, at the body's text size -- it grows
/// with the person's Dynamic Type, and so does its column (ScaledMetric), so the rows stay aligned at every size. A glyph that
/// says something the row's words do not carries its word for VoiceOver (said); a glyph beside words that say it is hidden.
struct MTBizGlyph: View {
    let name: String
    var said: LocalizedStringKey? = nil
    @ScaledMetric(relativeTo: .body) private var column: CGFloat = 28
    init(_ name: String, said: LocalizedStringKey? = nil) {
        self.name = name
        self.said = said
    }
    var body: some View {
        let g = Image(systemName: name).font(.body.weight(.semibold)).foregroundColor(.white).frame(width: column)
        if let said { g.accessibilityLabel(Text(said)) } else { g.accessibilityHidden(true) }
    }
}

/// A ROW'S SMALL STATE LINE (point 0 of the constitution, one owner): the glyph white, as every glyph of the app (the author's word 05.10), the words
/// secondary; the state is told by the glyph's shape, never by a colour of its own.
struct MTBizNote: View {
    let glyph: String
    let text: Text
    var body: some View {
        Label { text.foregroundColor(.gray) } icon: { Image(systemName: glyph).foregroundColor(.white).accessibilityHidden(true) }
            .font(.caption)
            .accessibilityElement(children: .combine)   // VoiceOver reads the state's word, never the glyph's name
    }
}

/// THE REASONS IN THE BUTTONS' PLACE (point 0): the words a person reads where the core's rule (MTBizView's target rules)
/// leaves no act to draw.
extension MTBizView {
    /// The line a manager without an open department reads where their acts would stand.
    static let noDeptYet: LocalizedStringKey = "Once an administrator puts you in a department, you can invite and place its people and open its chat."

    /// Why a card has no «Department and position» for a viewer whose role places people at all; nil where it has it.
    func assignRefusal(_ m: Member) -> LocalizedStringKey? {
        guard may("assign"), m.status != "removed", !mayAssign(m) else { return nil }
        let target = MTBizRole(word: m.role)
        if m.member == me.member { return "Only an administrator can set your department and position." }
        if role == .manager, myDept == nil { return Self.noDeptYet }
        if role == .manager, target == .employee { return "You can place only the people of your own department." }
        if role == .manager, target == .manager { return "Only an administrator can set this person's department and position." }
        return "Only the owner or this person can set their department and position."
    }
}

/// AN ACT THE CORE WOULD REFUSE THIS PERSON NOW (point 0 of the constitution, «no dead buttons»): no button in its place, the
/// reason instead -- one owner of the line, the glyph white and the words secondary, as tall as the row it stands for.
struct MTBizWhyNot: View {
    let text: LocalizedStringKey
    init(_ text: LocalizedStringKey) { self.text = text }
    var body: some View {
        MTBizNote(glyph: "info.circle", text: Text(text)).frame(minHeight: 44, alignment: .leading)
    }
}

/// A ROW THAT WAITS FOR ANOTHER PHONE OR THE NETWORK (K.6): the platform's spinner, what is awaited, and -- where the wait
/// hangs on somebody else -- whom it waits for; the page around it stays at work with what this phone holds.
struct MTBizWaitRow: View {
    let title: LocalizedStringKey
    var detail: LocalizedStringKey? = nil
    static let fromInviter: LocalizedStringKey = "The people of the organization come from the inviter's phone as soon as it is reachable."
    var body: some View {
        HStack(spacing: 12) {
            ProgressView().tint(.white).frame(width: 28)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).foregroundColor(.gray)
                if let detail { Text(detail).font(.caption).foregroundColor(.gray) }
            }
            Spacer()
        }
        .frame(minHeight: 44)
        .accessibilityElement(children: .combine)
    }
}

/// One person's face in the Business's rows: the app's one face of a person, the letter derived by its one owner.
struct MTBizFace: View {
    let name: String
    var size: CGFloat = 40
    var body: some View {
        AvatarCircle(photoURL: nil, color: .black, initial: MontanaAvatar.initial(title: name, name: name), size: size)
            .frame(width: size, height: size)
    }
}

/// THE BUSINESS PAGE (from the drawer): the number confirmed on this phone, the person's organisations, the joins on their
/// way, and the doors to found one or to join by a copied link.
struct MTBusinessPage: View {
    @Environment(\.montanaClose) private var close
    @ObservedObject private var biz = MTBusiness.shared
    @State private var founding = false
    @State private var confirming = false
    @State private var scanning = false
    @State private var moving = false   // a new phone comes back to its place (7.3)
    @State private var phone: MTBizCore.Attest?
    @State private var word: String?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    if let p = phone {
                        HStack(spacing: 12) {
                            MTBizGlyph("checkmark.seal.fill")
                            Text("Number confirmed").foregroundColor(.white)
                            Spacer()
                            // USER-DATA: the person's own number, as the service confirmed it
                            Text(verbatim: p.e164).foregroundColor(.gray).monospacedDigit()
                        }
                        .frame(minHeight: 44)
                        .accessibilityElement(children: .combine)
                    } else {
                        Button { confirming = true } label: {
                            MTBizRowLabel(glyph: "phone.badge.checkmark", title: "Confirm your number")
                        }
                    }
                } header: { Text("Phone number") }
                .listRowBackground(MTGlassRowPlate())
                Section {
                    ForEach(biz.orgs) { v in
                        NavigationLink { MTBizOrgPage(org: v.id) } label: { MTBizOrgRow(view: v) }
                    }
                    if 0 < biz.opening {
                        MTBizWaitRow(title: "Opening the invitation…")
                    }
                    ForEach(biz.joins) { j in
                        if j.move == true {
                            MTBizMoveRow(join: j)
                        } else {
                            MTBizWaitRow(title: "Joining an organization…", detail: MTBizWaitRow.fromInviter)
                        }
                    }
                    ForEach(biz.left.filter { !$0.value.isEmpty }.sorted { $0.key < $1.key }, id: \.key) { e in
                        MTBizLeftRow(org: e.key, name: e.value)
                    }
                    Button { founding = true } label: { MTBizRowLabel(glyph: "plus.circle.fill", title: "Create an organization") }
                    Button { scanning = true } label: { MTBizRowLabel(glyph: "qrcode.viewfinder", title: "Scan an invitation code") }
                    Button { word = biz.acceptCopied() } label: { MTBizRowLabel(glyph: "doc.on.clipboard", title: "Join by a copied link") }
                    Button { moving = true } label: { MTBizRowLabel(glyph: "iphone.and.arrow.forward", title: "My place from an old phone") }
                } footer: {
                    if biz.orgs.isEmpty && biz.joins.isEmpty && biz.opening == 0 {
                        Text("Create your organization, or join one by its invitation: a code to scan or a link someone sent you.")
                    }
                }
                .listRowBackground(MTGlassRowPlate())
            }
            .mtBizPage("Organizations")
            .toolbar { ToolbarItem(placement: .topBarLeading) { MontanaCloseMark { close?() } } }
            .sheet(isPresented: $founding) { MTBizFoundSheet() }
            .sheet(item: Binding(get: { biz.greet.first.map(MTBizGreet.init) }, set: { if $0 == nil, let g = biz.greet.first { biz.greeted(g) } })) { g in
                MTBizFirstDaySheet(org: g.id)
            }
            .sheet(isPresented: $scanning) { MTBizScanSheet { u in scanning = false; biz.accept(u) } }
            .sheet(isPresented: $moving) { MTBizMoveSheet() }
            .sheet(isPresented: $confirming) { MTBizPhoneSheet { confirming = false; phone = MTBizPhone.confirmed() } }
            .mtBizWord($word)
            .onAppear {
                biz.redraw()
                biz.settle()
                phone = MTBizPhone.confirmed()
            }
        }
    }
}

/// THE INVITATION'S CODE, READ BY THE APP'S OWN SCANNER (MTBizScanner): a code that is not an organisation's link is passed
/// over in silence, the scanner keeps looking; the first one that is goes to the Business's one door.
struct MTBizScanSheet: View {
    var found: (URL) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var done = false
    var body: some View {
        NavigationStack {
            MTBizScanner(prompt: "Point at an organization's invitation code") { code in
                guard !done, let u = URL(string: code), MTBusiness.isJoin(u) else { return }
                done = true
                found(u)
            }
            .navigationTitle("Scan code")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarLeading) { MontanaCloseMark { dismiss() } } }
        }
        .preferredColorScheme(.dark)
    }
}

/// A row of the Business's lists: the glyph, the word, a count beside it where the row has one, the chevron; the whole row is
/// the target.
struct MTBizRowLabel: View {
    let glyph: String
    let title: LocalizedStringKey
    var detail: String? = nil
    var body: some View {
        HStack(spacing: 12) {
            MTBizGlyph(glyph)
            Text(title).foregroundColor(.white)
            Spacer()
            if let detail {
                // USER-DATA: a count the row stands for
                Text(verbatim: detail).foregroundColor(.gray).monospacedDigit()
            }
        }
        .frame(minHeight: 44)
        .accessibilityElement(children: .combine)
        .contentShape(Rectangle())
    }
}

/// An organisation in the list: its name and the person's role in it.
struct MTBizOrgRow: View {
    let view: MTBizView
    var body: some View {
        HStack(spacing: 12) {
            MTBizGlyph("building.2.fill")
            VStack(alignment: .leading, spacing: 2) {
                // USER-DATA: the organisation's name, as its owner wrote it
                Text(verbatim: view.org?.name ?? "").foregroundColor(.white)
                Text(view.role?.title ?? "").font(.caption).foregroundColor(.gray)
            }
            Spacer()
        }
        .frame(minHeight: 44)
        .accessibilityElement(children: .combine)
        .contentShape(Rectangle())
    }
}

/// The number's own sheet: the door of the sign-in, for a person who came in by the 24 words.
struct MTBizPhoneSheet: View {
    var done: () -> Void
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            VStack(spacing: 18) {
                Text("Your number confirms you to your organizations. It is seen by their administrators, never by colleagues.")
                    .font(.subheadline).foregroundColor(.gray).multilineTextAlignment(.center)
                MTBizPhoneDoor(onConfirmed: done)
                Spacer()
            }
            .padding(24)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .montanaPageGround()
            .navigationTitle("Phone number")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarLeading) { MontanaCloseMark { dismiss() } } }
        }
        .preferredColorScheme(.dark)
    }
}

/// FOUNDING AN ORGANISATION: its name; the owner's own name and card ride the Genesis record.
struct MTBizFoundSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var busy = false
    @State private var refused = false
    @FocusState private var typing: Bool
    var body: some View {
        MTBizSheet(title: "New organization", done: { found() }, busy: busy, ready: !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty) {
            Section {
                TextField("Organization name", text: $name).mtBizFirstField($typing) { found() }
            } footer: {
                Text("You become its owner. Your name and your Montana link go into it, so your people can reach you.")
            }
            .listRowBackground(MTGlassRowPlate())
        }
        .mtBizRefused($refused, "The organization could not be created")
    }
    private func found() {
        let n = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !n.isEmpty, !busy else { return }
        busy = true
        Task {
            let card = await Task.detached(priority: .userInitiated) { MontanaCard.offerPermanent() ?? "" }.value
            let org = await MTBusiness.shared.found(name: String(n.prefix(256)), card: card)
            busy = false
            if org != nil { dismiss() } else { refused = true }
        }
    }
}

/// AN ORGANISATION'S PAGE: its people (every row opens the person's card), the invitation, the salary due with its one
/// touch of payment, the person's own payouts, the departments, the open invitations and the shop -- each section only where
/// the person's role may act in it (the core's «can»).
struct MTBizOrgPage: View {
    let org: String
    @ObservedObject private var biz = MTBusiness.shared
    @State private var inviting = false
    @State private var naming = false
    @State private var offering = false
    @State private var buying: MTBizView.Offer?
    @State private var payingAll = false
    @ObservedObject private var book = MTLocalCoinLedger.shared
    @State private var word: String?

    private var v: MTBizView? { biz.views[org] }
    private var boss: Bool { v.map { MTBusiness.boss($0.me.role) } ?? false }

    var body: some View {
        List {
            if let v {
                people(v)
                MTBizChatsSection(org: org, view: v)
                MTBizMovesSection(org: org)
                MTBizFirstDaySection(org: org)
                // The salary due stands once a salary is set: a new organisation's page is not opened by an empty section.
                if boss, !v.salary.isEmpty { due(v) }
                mine(v)
                MTBizShiftsSection(org: org, view: v)
                supply(v)
                if v.may("dept") { depts(v) }
                if v.may("revoke") { invites(v) }
                shop(v)
                MTBizJournalSection(org: org)
            }
        }
        .mtBizPage(named: v?.org?.name ?? "")
        .sheet(isPresented: $inviting) { MTBizInviteSheet(org: org) }
        .sheet(isPresented: $naming) { MTBizNameSheet(title: "New department") { n in
            guard let tag = MTBusiness.freshTag() else { return false }
            return await MTBusiness.shared.write(org, MTBizCommand.dept(tag, name: n, archived: false)) != nil
        } }
        .sheet(isPresented: $offering) { MTBizOfferSheet(org: org) }
        .confirmationDialog("Buy", isPresented: Binding(get: { buying != nil }, set: { if !$0 { buying = nil } }), titleVisibility: .hidden) {
            if let o = buying {
                Button { buy(o) } label: { Text("Buy for \(MTBizText.coins(o.price))") }
            }
        }
        .mtBizWord($word)
        .onAppear { biz.redraw(); biz.settle() }
    }

    @ViewBuilder private func people(_ v: MTBizView) -> some View {
        Section {
            ForEach(v.members.filter { $0.status != "removed" }) { m in
                NavigationLink { MTBizMemberPage(org: org, member: m.member) } label: { MTBizMemberRow(member: m, view: v) }
            }
            if v.mayInvite {
                Button { inviting = true } label: { MTBizRowLabel(glyph: "person.badge.plus", title: "Invite") }
            } else if v.may("invite") {
                MTBizWhyNot(MTBizView.noDeptYet)
            }
        } header: { Text("People") } footer: {
            if 0 < v.waiting { Text("Some records wait for earlier ones to arrive.") }
        }
        .listRowBackground(MTGlassRowPlate())
    }

    /// THE SALARY DUE AT THE MONTH'S END (point 0, fourth pass): one row per person -- the sum of their finished periods and how
    /// many there are, where one period is not all -- and, beside a person this phone has no chat with yet, the plain reason
    /// their salary will wait; an empty list says when a period comes due.
    @ViewBuilder private func due(_ v: MTBizView) -> some View {
        Section {
            if v.due.isEmpty {
                Text("Nothing is due").foregroundColor(.gray).frame(minHeight: 44)
            } else {
                ForEach(dueMembers(v), id: \.self) { m in
                    let ds = v.due.filter { $0.member == m }
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            // USER-DATA: the employee's name
                            Text(verbatim: v.member(m)?.name ?? "").foregroundColor(.white)
                            let named = ds.compactMap(MTBizText.period)
                            if !named.isEmpty, named.count == ds.count {
                                // USER-DATA: the periods due, as the system names dates on UTC
                                Text(verbatim: named.joined(separator: ", ")).font(.caption).foregroundColor(.gray)
                            } else if 1 < ds.count {
                                Text("\(ds.count) periods").font(.caption).foregroundColor(.gray)
                            }
                            if biz.pipe(of: m) == nil {
                                MTBizNote(glyph: "bubble.left", text: Text("No chat with this person yet: their salary waits."))
                            }
                        }
                        Spacer()
                        // USER-DATA: the coins due to the person, every finished period together
                        Text(verbatim: MTBizText.coins(ds.reduce(UInt64(0)) { $0 &+ $1.coins })).foregroundColor(.gray).monospacedDigit()
                    }
                    .frame(minHeight: 44)
                    .accessibilityElement(children: .combine)
                }
                Button { payingAll = true } label: { MTBizRowLabel(glyph: "banknote.fill", title: "Pay everything due") }
            }
        } header: { Text("Salary due") } footer: {
            if v.due.isEmpty {
                Text("A period comes due once it has ended, on UTC boundaries.")
            } else {
                Text("Coins leave your own coin book as coin letters in each person's chat. A short balance pays nothing.")
            }
        }
        .listRowBackground(MTGlassRowPlate())
        // COINS LEAVE IN A BATCH ONLY ON A SECOND WORD (point 0, safety): one native sheet names the sum and how many people,
        // and its one button is the sum -- as «Buy for …» is; under the title, what the coin book holds.
        .confirmationDialog(payAllTitle(v), isPresented: $payingAll, titleVisibility: .visible) {
            Button { payDue() } label: { Text("Pay \(MTBizText.coins(dueTotal(v)))") }
        } message: {
            Text("In your coin book: \(MTBizText.coins(UInt64(max(0, book.balance))))")
        }
    }
    /// The people salary is due to, in the order the core names them, each once.
    private func dueMembers(_ v: MTBizView) -> [String] {
        var out: [String] = []
        for d in v.due where !out.contains(d.member) { out.append(d.member) }
        return out
    }
    private func dueTotal(_ v: MTBizView) -> UInt64 { v.due.reduce(UInt64(0)) { $0 &+ $1.coins } }
    private func dueCount(_ v: MTBizView) -> Int { Set(v.due.map(\.member)).count }
    /// «Pay 1 200 $TCM to 3 people» (the sum through MTBizText.coins and its ticker): the sum, and how many people it goes to
    /// in the language's own plural.
    private func payAllTitle(_ v: MTBizView) -> Text {
        let people = String(localized: "to \(dueCount(v)) people", bundle: MTLanguage.bundle)
        return Text("Pay \(MTBizText.coins(dueTotal(v))) \(people)")
    }

    @ViewBuilder private func mine(_ v: MTBizView) -> some View {
        let pays = v.pay.filter { $0.member == v.me.member }.sorted { ($0.at_ms ?? 0) > ($1.at_ms ?? 0) }   // the newest first
        if !pays.isEmpty {
            Section {
                ForEach(pays) { p in MTBizPayRow(pay: p, received: true) }
            } header: { Text("My payouts") }
            .listRowBackground(MTGlassRowPlate())
        }
    }

    @ViewBuilder private func depts(_ v: MTBizView) -> some View {
        Section {
            ForEach(v.depts) { d in
                HStack(spacing: 12) {
                    MTBizGlyph("folder.fill")
                    // USER-DATA: a department's name
                    Text(verbatim: d.name).foregroundColor(.white)
                    Spacer()
                    // USER-DATA: how many people the department holds
                    Text(verbatim: String(v.members.filter { $0.dept == d.id && $0.active }.count)).foregroundColor(.gray)
                }
                .frame(minHeight: 44)
                .accessibilityElement(children: .combine)
                .swipeActions {
                    Button(role: .destructive) {
                        Task { await MTBusiness.shared.write(org, MTBizCommand.dept(d.id, name: d.name, archived: true)) }
                    } label: { Label("Archive", systemImage: "archivebox") }
                }
            }
            Button { naming = true } label: { MTBizRowLabel(glyph: "folder.badge.plus", title: "New department") }
        } header: { Text("Departments") }
        .listRowBackground(MTGlassRowPlate())
    }

    @ViewBuilder private func invites(_ v: MTBizView) -> some View {
        let open = v.invites.filter { $0.state == "open" }
        if !open.isEmpty {
            Section {
                ForEach(open) { i in
                    HStack(spacing: 12) {
                        MTBizGlyph("envelope.open.fill")
                        Text(MTBizRole(word: i.role)?.title ?? "").foregroundColor(.white)
                        Spacer()
                        Text("Until \(MTBizText.day(i.expires_ms))").foregroundColor(.gray).font(.caption)
                    }
                    .frame(minHeight: 44)
                    .accessibilityElement(children: .combine)
                    .swipeActions {
                        Button(role: .destructive) {
                            Task { await MTBusiness.shared.write(org, MTBizCommand.revoke(invite: i.id)) }
                        } label: { Label("Revoke", systemImage: "xmark") }
                    }
                }
            } header: { Text("Open invitations") }
            .listRowBackground(MTGlassRowPlate())
        }
    }

    @ViewBuilder private func shop(_ v: MTBizView) -> some View {
        let offers = v.offers.filter { $0.active || v.may("offer") }
        if !offers.isEmpty || v.may("offer") {
            Section {
                ForEach(offers) { o in
                    Group {
                        if v.mayBuy(o) {
                            Button { buying = o } label: { offerRow(o) }
                        } else {
                            offerRow(o)
                        }
                    }
                    .swipeActions {
                        if v.may("offer"), o.active {
                            Button(role: .destructive) {
                                Task {
                                    await MTBusiness.shared.write(org, MTBizCommand.offer(item: o.id, title: o.title, price: o.price,
                                                                                         stock: UInt32(clamping: o.stock), active: false))
                                }
                            } label: { Label("Withdraw", systemImage: "xmark") }
                        }
                    }
                }
                if v.may("offer") {
                    Button { offering = true } label: { MTBizRowLabel(glyph: "plus.circle.fill", title: "New item") }
                }
                if v.may("fulfil") {
                    ForEach(v.redeems.filter { !$0.fulfilled }) { r in
                        HStack(spacing: 12) {
                            MTBizGlyph("shippingbox.fill")
                            VStack(alignment: .leading, spacing: 2) {
                                // USER-DATA: the buyer's name and the item's name
                                Text(verbatim: (v.member(r.member)?.name ?? "") + " · " + (v.offers.first { $0.id == r.offer }?.title ?? ""))
                                    .foregroundColor(.white)
                                Text("Paid \(MTBizText.coins(r.coins))").font(.caption).foregroundColor(.gray)
                            }
                            Spacer()
                        }
                        .frame(minHeight: 44)
                        .accessibilityElement(children: .combine)
                        .swipeActions {
                            Button {
                                Task { await MTBusiness.shared.write(org, MTBizCommand.fulfil(record: r.record)) }
                            } label: { Label("Handed over", systemImage: "checkmark") }
                        }
                    }
                }
            } header: { Text("Shop") } footer: {
                if v.buys { Text("A purchase pays the owner from your coin book.") }
            }
            .listRowBackground(MTGlassRowPlate())
        }
    }

    /// One offer of the shop: its name, how many are left where they are counted, its price.
    private func offerRow(_ o: MTBizView.Offer) -> some View {
        HStack(spacing: 12) {
            MTBizGlyph("bag.fill")
            VStack(alignment: .leading, spacing: 2) {
                // USER-DATA: the item's name, as the administrator wrote it
                Text(verbatim: o.title).foregroundColor(o.active ? .white : .gray)
                if 0 < o.stock { Text("\(String(o.stock)) left").font(.caption).foregroundColor(.gray) }
            }
            Spacer()
            // USER-DATA: the item's price in coins
            Text(verbatim: MTBizText.coins(o.price)).foregroundColor(.gray).monospacedDigit()
        }
        .frame(minHeight: 44)
        .accessibilityElement(children: .combine)
        .contentShape(Rectangle())
    }

    private func payDue() {
        Task { word = MTBizText.verdict(await MTBusiness.shared.payDue(org)) ?? "Paid" }
    }
    private func buy(_ o: MTBizView.Offer) {
        buying = nil
        Task { word = MTBizText.verdict(await MTBusiness.shared.buy(org, offer: o, qty: 1)) ?? "Bought" }
    }
}

/// A person in the organisation's list: the face, the name, the job title and department, the role, the mark of a confirmed
/// number.
struct MTBizMemberRow: View {
    let member: MTBizView.Member
    let view: MTBizView
    var body: some View {
        HStack(spacing: 12) {
            MTBizFace(name: member.name)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 4) {
                    // USER-DATA: the person's name, as they wrote it
                    Text(verbatim: member.name).foregroundColor(.white)
                    if member.phone_confirmed {
                        Image(systemName: "checkmark.seal.fill").font(.caption).foregroundColor(.white).accessibilityLabel(Text("Number confirmed"))
                    }
                }
                // USER-DATA: the job title and the department, as the administrators wrote them
                Text(verbatim: [member.title, view.dept(member.dept)?.name ?? ""].filter { !$0.isEmpty }.joined(separator: " · "))
                    .font(.caption).foregroundColor(.gray)
            }
            Spacer()
            Text(MTBizRole(word: member.role)?.title ?? "").font(.caption).foregroundColor(.gray)
        }
        .frame(minHeight: 44)
        .accessibilityElement(children: .combine)
        .contentShape(Rectangle())
    }
}

/// One payout: the coins, the kind, the day of its period, and its state -- sent, or confirmed by the receiver's own receipt.
struct MTBizPayRow: View {
    let pay: MTBizView.Pay
    /// The receiver's own list (My payouts): its state in the receiver's words -- on its way, or received (their own phone
    /// took the coins and signed the receipt) -- never «sent», which is the payer's word.
    var received = false
    var body: some View {
        HStack(spacing: 12) {
            MTBizGlyph(pay.kind == MTBizCommand.bonusPay ? "gift.fill" : "banknote.fill")
            VStack(alignment: .leading, spacing: 2) {
                // USER-DATA: the coins of the payout
                Text(verbatim: MTBizText.coins(pay.coins)).foregroundColor(.white).monospacedDigit()
                HStack(spacing: 4) {
                    Text(pay.kind == MTBizCommand.bonusPay ? "Bonus" : "Salary")
                    // USER-DATA: the day the payout was written (the core's pay[].at_ms), in the system's words
                    if let at = pay.at_ms { Text(verbatim: "· " + MTBizText.day(at)) }
                }
                .font(.caption).foregroundColor(.gray)
            }
            Spacer()
            MTBizNote(glyph: pay.confirmed ? "checkmark.circle.fill" : "paperplane.fill",
                      text: Text(pay.confirmed ? (received ? "Received" : "Confirmed") : (received ? "On its way" : "Sent")))
        }
        .frame(minHeight: 44)
        .accessibilityElement(children: .combine)
    }
}

/// A PERSON'S CARD IN THE ORGANISATION: who they are in it, and every act the viewer's role allows on them -- the role, the
/// department and the job title, the salary and a bonus, coins as a colleague, the pair's chat, the removal.
struct MTBizMemberPage: View {
    let org: String
    let member: String
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var biz = MTBusiness.shared
    @State private var salary = false
    @State private var bonus = false
    @State private var giving = false
    @State private var placing = false
    @State private var removing = false
    @State private var word: String?

    private var v: MTBizView? { biz.views[org] }
    private var m: MTBizView.Member? { v?.member(member) }
    private var mine: Bool { v?.me.member == member }
    /// The roles the viewer may give this person: the owner gives every role but the owner's, an administrator those below.
    private var roles: [MTBizRole] {
        guard let v, let m, !mine, m.role != MTBizRole.owner.word else { return [] }
        switch v.role {
        case .owner: return [.admin, .manager, .employee]
        case .admin: return m.role == MTBizRole.admin.word ? [] : [.manager, .employee]
        default: return []
        }
    }

    var body: some View {
        List {
            if let v, let m {
                Section {
                    HStack(spacing: 14) {
                        MTBizFace(name: m.name, size: 56)
                        VStack(alignment: .leading, spacing: 4) {
                            // USER-DATA: the person's name, as they wrote it
                            Text(verbatim: m.name).font(.title3.bold()).foregroundColor(.white)
                            Text(MTBizRole(word: m.role)?.title ?? "").foregroundColor(.gray)
                            // USER-DATA: the job title and the department
                            Text(verbatim: [m.title, v.dept(m.dept)?.name ?? ""].filter { !$0.isEmpty }.joined(separator: " · "))
                                .font(.caption).foregroundColor(.gray)
                        }
                        Spacer()
                    }
                    .padding(.vertical, 6)
                    if m.phone_confirmed {
                        Label { Text("Number confirmed") } icon: { Image(systemName: "checkmark.seal.fill").foregroundColor(.white) }
                    }
                    if m.phone_mismatch == true {
                        Label { Text("The number does not match the invitation") } icon: { Image(systemName: "exclamationmark.triangle.fill").foregroundColor(.white) }
                    }
                    if m.status == "pending_device" {
                        // The place moved to a new key (Rekey); the new phone has not bound the number yet (the core's pending_device).
                        Label { Text("The new phone has not confirmed the number yet") } icon: { Image(systemName: "iphone.badge.play") }
                    }
                }
                .listRowBackground(MTGlassRowPlate())

                if let w = v.worked(member) {
                    Section {
                        worked("This week", w.week_s, w.week_confirmed_s)
                        worked("This month", w.month_s, w.month_confirmed_s)
                    } header: { Text("Time on shifts") } footer: {
                        Text("Closed shifts begun this week and this month, on the salary's UTC boundaries; a week begins on Monday.")
                    }
                    .listRowBackground(MTGlassRowPlate())
                }

                if !roles.isEmpty {
                    Section {
                        Picker("Role", selection: Binding(get: { MTBizRole(word: m.role) ?? .employee }, set: { r in
                            Task { await MTBusiness.shared.write(org, MTBizCommand.role(member: member, role: r)) }
                        })) {
                            ForEach(roles) { r in Text(r.title).tag(r) }
                        }
                    }
                    .listRowBackground(MTGlassRowPlate())
                }

                if v.mayAssign(m) {
                    Section {
                        Button { placing = true } label: { MTBizRowLabel(glyph: "person.text.rectangle", title: "Department and position") }
                    }
                    .listRowBackground(MTGlassRowPlate())
                } else if let why = v.assignRefusal(m) {
                    Section { MTBizWhyNot(why) }.listRowBackground(MTGlassRowPlate())
                }

                if MTBusiness.boss(v.me.role) {
                    Section {
                        if let s = v.salary.first(where: { $0.member == member }) {
                            HStack {
                                Text("Salary").foregroundColor(.white)
                                Spacer()
                                // USER-DATA: the salary in coins and its period
                                Text(verbatim: MTBizText.coins(s.coins) + " · " + (MTBizPeriod(rawValue: s.period)?.title ?? ""))
                                    .foregroundColor(.gray).monospacedDigit()
                            }
                            .frame(minHeight: 44)
                            .accessibilityElement(children: .combine)
                        }
                        Button { salary = true } label: { MTBizRowLabel(glyph: "calendar.badge.clock", title: "Set salary") }
                        Button { bonus = true } label: { MTBizRowLabel(glyph: "gift.fill", title: "Pay a bonus") }
                        ForEach(v.pay.filter { $0.member == member }) { p in MTBizPayRow(pay: p) }
                    } header: { Text("Pay") }
                    .listRowBackground(MTGlassRowPlate())
                }

                if mine, v.may("profile") {
                    Section {
                        Button { renew() } label: { MTBizRowLabel(glyph: "arrow.triangle.2.circlepath", title: "Update my name and link") }
                    } footer: { Text("Your name and Montana link as the organization shows them to its people.") }
                    .listRowBackground(MTGlassRowPlate())
                }

                if !mine {
                    Section {
                        Button { giving = true } label: { MTBizRowLabel(glyph: "dollarsign.circle.fill", title: "Send coins") }
                        if let p = biz.pipe(of: member) {
                            Button { MontanaOutsideOpen.chat(p) } label: { MTBizRowLabel(glyph: "message.fill", title: "Open chat") }
                        }
                    }
                    .listRowBackground(MTGlassRowPlate())
                }

                if v.may("remove"), !mine, m.role != MTBizRole.owner.word, roles.count != 0 || v.role == .owner {
                    Section {
                        Button(role: .destructive) { removing = true } label: {
                            Text("Remove from the organization").foregroundColor(.red).frame(maxWidth: .infinity, minHeight: 44)
                        }
                    }
                    .listRowBackground(MTGlassRowPlate())
                }
            }
        }
        .mtBizPage(named: m?.name ?? "")
        .sheet(isPresented: $salary) { MTBizSalarySheet(org: org, member: member) }
        .sheet(isPresented: $bonus) {
            MTBizCoinsSheet(title: "Bonus", note: true, ask: { sum in (Text("Bonus: \(m?.name ?? "")"), Text("Pay \(sum)")) }) { coins, note in
                await MTBusiness.shared.pay(org, member: member, coins: coins, kind: MTBizCommand.bonusPay, periodKey: 0, note: note)
            }
        }
        .sheet(isPresented: $giving) {
            MTBizCoinsSheet(title: "Send coins", note: false, ask: { sum in (Text("Coins: \(m?.name ?? "")"), Text("Send \(sum)")) }) { coins, _ in
                MTBusiness.shared.give(Int(clamping: coins), to: member)
            }
        }
        .sheet(isPresented: $placing) { MTBizPlaceSheet(org: org, member: member) }
        .alert("Remove from the organization?", isPresented: $removing) {
            Button("Remove", role: .destructive) {
                Task { await MTBusiness.shared.write(org, MTBizCommand.remove(member: member)) }
                dismiss()
            }
            Button("Cancel", role: .cancel) {}
        } message: { Text("The person leaves the roster. What their phone has already seen stays with it.") }
    }
}

extension MTBizMemberPage {
    /// One period of the person's time (K.5): the hours of their closed shifts, the confirmed part beneath -- as the core
    /// counted them (time[]); never coins.
    func worked(_ title: LocalizedStringKey, _ seconds: UInt64, _ confirmed: UInt64) -> some View {
        HStack(spacing: 12) {
            MTBizGlyph("clock.fill")
            VStack(alignment: .leading, spacing: 2) {
                Text(title).foregroundColor(.white)
                Text("Confirmed: \(MTBizText.hours(confirmed))").font(.caption).foregroundColor(.gray)
            }
            Spacer()
            // USER-DATA: the hours and minutes of the period's closed shifts
            Text(verbatim: MTBizText.hours(seconds)).foregroundColor(.white).monospacedDigit()
        }
        .frame(minHeight: 44)
        .accessibilityElement(children: .combine)
    }
    /// The person's own name and card, written again into the roster (Profile): what the organisation shows of them.
    func renew() {
        let name = String(E2E.myDisplayName().prefix(256))
        Task {
            let card = await Task.detached(priority: .userInitiated) { MontanaCard.offerPermanent() ?? "" }.value
            await MTBusiness.shared.write(org, MTBizCommand.profile(name: name, card: card))
        }
    }
}

/// THE INVITATION: the role and the department it gives, its term, and -- when known -- the number it is meant for; the link
/// comes out as a code to show, a line to copy, and the platform's own share sheet to send it to the person's number.
struct MTBizInviteSheet: View {
    let org: String
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var biz = MTBusiness.shared
    @State private var role = MTBizRole.employee
    @State private var dept = ""
    @State private var days = 7
    @State private var number = ""
    @State private var link: String?
    @State private var busy = false
    @State private var refused = false
    @State private var copied = false

    private var v: MTBizView? { biz.views[org] }
    private var roles: [MTBizRole] {
        switch v?.role {
        case .owner: return [.admin, .manager, .employee]
        case .admin: return [.manager, .employee]
        default: return [.employee]
        }
    }

    var body: some View {
        MTBizSheet(title: "Invite", done: link == nil ? { mint() } : nil, busy: busy) {
            if let link {
                Section {
                    HStack { Spacer(); MontanaQRCode(payload: Data(link.utf8), side: 220, corner: 12); Spacer() }
                        .padding(.vertical, 8)
                    // USER-DATA: the invitation's own link
                    Text(verbatim: link).font(.caption.monospaced()).foregroundColor(.gray).lineLimit(3).textSelection(.enabled)
                    Button { MTBizInviteSheet.copy(link); copied = true } label: {
                        MTBizRowLabel(glyph: copied ? "checkmark" : "doc.on.doc", title: copied ? "Copied" : "Copy link")
                    }
                    Button { MTShare.present([link]) } label: { MTBizRowLabel(glyph: "square.and.arrow.up", title: "Send to the person") }
                    Button { if let img = MTBizPoster.image(link: link, org: v?.org?.name ?? "") { MTShare.present([img]) } } label: {
                        MTBizRowLabel(glyph: "doc.richtext", title: "Poster to print")
                    }
                } footer: {
                    Text("Whoever opens this link joins the organization with the role it gives. It works once.")
                }
                .listRowBackground(MTGlassRowPlate())
            } else {
                Section {
                    Picker("Role", selection: $role) { ForEach(roles) { r in Text(r.title).tag(r) } }
                    if let v, v.invitesIntoOwnDept {
                        // USER-DATA: the manager's own department, the only one their invitation can give
                        LabeledContent { Text(verbatim: v.dept(v.myDept)?.name ?? "") } label: { Text("Department") }
                    } else if let v, !v.depts.isEmpty {
                        Picker("Department", selection: $dept) {
                            Text("No department").tag("")
                            ForEach(v.depts) { d in Text(verbatim: d.name).tag(d.id) }   // USER-DATA: a department's name
                        }
                    }
                    Picker("Valid for", selection: $days) {
                        Text("1 day").tag(1)
                        Text("7 days").tag(7)
                        Text("30 days").tag(30)
                    }
                } footer: {
                    Text("The checkmark makes the invitation: a code to show and a link to send.")
                }
                .listRowBackground(MTGlassRowPlate())
                if v?.may("invite_phone") == true {
                    Section {
                        TextField("Phone number (optional)", text: $number).keyboardType(.phonePad).textContentType(.telephoneNumber)
                    } footer: {
                        Text("With a number, joining by another number is marked to the administrators.")
                    }
                    .listRowBackground(MTGlassRowPlate())
                }
            }
        }
        .mtBizRefused($refused, "The invitation could not be created")
    }
    /// THE INVITATION'S SECRET ON THE PASTEBOARD (point 0, safety): only by the touch of «Copy link», on this phone alone (never
    /// the Universal Clipboard to the person's other devices), and for ten minutes -- long enough to paste it into a chat, not
    /// long enough to lie there for whichever app reads the pasteboard next week.
    static func copy(_ link: String) {
        UIPasteboard.general.setItems([[UTType.utf8PlainText.identifier: link]],
                                      options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(600)])
    }
    private func mint() {
        guard !busy else { return }
        let n = v?.may("invite_phone") == true ? number.trimmingCharacters(in: .whitespaces) : ""
        let e164 = n.isEmpty ? nil : MTBizCountries.e164(MTBizCountries.initial, n)
        if !n.isEmpty, e164 == nil { refused = true; return }
        let into = v?.invitesIntoOwnDept == true ? v?.myDept : (dept.isEmpty ? nil : dept)
        busy = true
        Task {
            let card = await Task.detached(priority: .userInitiated) { MontanaCard.offerPermanent() ?? "" }.value
            let made = await MTBusiness.shared.invite(org, role: role, dept: into, days: days, e164: e164, card: card)
            busy = false
            link = made
            if made == nil { refused = true }
        }
    }
}

/// The salary of one person: coins for a period (a day, a week, a month, on UTC boundaries), from this moment.
struct MTBizSalarySheet: View {
    let org: String
    let member: String
    @Environment(\.dismiss) private var dismiss
    @State private var coins = ""
    @State private var period = MTBizPeriod.month
    @State private var refused = false
    @FocusState private var typing: Bool
    @ObservedObject private var biz = MTBusiness.shared
    /// What is still due to this person under the salary in force: it stays due when a new one is set -- each salary owes its
    /// own finished periods up to the next one's start (contract 1.3).
    private var stillDue: UInt64 {
        (biz.views[org]?.due ?? []).filter { $0.member == member }.reduce(UInt64(0)) { $0 &+ $1.coins }
    }
    var body: some View {
        MTBizSheet(title: "Salary", done: { set() }, ready: 0 < (UInt64(coins.filter { $0.isASCII && $0.isNumber }) ?? 0)) {
            Section {
                TextField("Coins", text: $coins).keyboardType(.numberPad).mtBizFirstField($typing)
                Picker("Period", selection: $period) { ForEach(MTBizPeriod.allCases) { p in Text(p.title).tag(p) } }
                    .pickerStyle(.segmented)
            } footer: {
                Text("Each period comes due on the organization's page once it ends; the one the salary starts in counts whole.")
            }
            .listRowBackground(MTGlassRowPlate())
            if 0 < stillDue {
                Section {
                    MTBizNote(glyph: "info.circle", text: Text("What is due under the previous salary stays due."))
                }
                .listRowBackground(MTGlassRowPlate())
            }
        }
        .mtBizRefused($refused, "The salary was not set")
    }
    private func set() {
        guard let c = UInt64(coins.filter { $0.isASCII && $0.isNumber }), 0 < c else { refused = true; return }
        Task {
            if await MTBusiness.shared.write(org, MTBizCommand.salary(member: member, coins: c, period: period, from: MTBusiness.nowMs)) != nil {
                dismiss()
            } else {
                refused = true
            }
        }
    }
}

/// Coins, and a note when the payment carries one: a bonus, or coins to a colleague. The coins leave only on a second word: the
/// checkmark asks one native question whose title names the receiver and whose button is the sum. The act's own verdict is said.
struct MTBizCoinsSheet: View {
    let title: LocalizedStringKey
    let note: Bool
    let ask: (String) -> (title: Text, button: Text)
    let act: @MainActor (UInt64, String) async -> MTBusiness.Verdict
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var book = MTLocalCoinLedger.shared
    @State private var coins = ""
    @State private var words = ""
    @State private var word: String?
    @State private var asking = false
    @FocusState private var typing: Bool
    private var sum: UInt64 { UInt64(coins.filter { $0.isASCII && $0.isNumber }) ?? 0 }
    var body: some View {
        let q = ask(MTBizText.coins(sum))
        MTBizSheet(title: title, done: { asking = 0 < sum }, ready: 0 < sum) {
            Section {
                // USER-DATA: the coin book's balance, a number
                LabeledContent { Text(verbatim: MTCoinText.count(book.balance)).monospacedDigit() } label: { Text("Coins") }
                TextField("Amount", text: $coins).keyboardType(.numberPad).mtBizFirstField($typing)
                if note { TextField("Note", text: $words) }
            }
            .listRowBackground(MTGlassRowPlate())
        }
        .confirmationDialog(q.title, isPresented: $asking, titleVisibility: .visible) {
            Button { go() } label: { q.button }
        }
        .mtBizWord($word)
    }
    private func go() {
        guard let c = UInt64(coins.filter { $0.isASCII && $0.isNumber }), 0 < c else { word = "Enter the coins"; return }
        let note = String(words.prefix(256))
        Task { if let w = MTBizText.verdict(await act(c, note)) { word = w } else { dismiss() } }
    }
}

/// A person's department and job title.
struct MTBizPlaceSheet: View {
    let org: String
    let member: String
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var biz = MTBusiness.shared
    @State private var dept = ""
    @State private var title = ""
    @State private var refused = false
    var body: some View {
        MTBizSheet(title: "Department and position", done: { set() }) {
            Section {
                if let v = biz.views[org], v.role == .manager {
                    // USER-DATA: the manager's own department: a manager places people within it alone
                    LabeledContent { Text(verbatim: v.dept(v.myDept)?.name ?? "") } label: { Text("Department") }
                } else {
                    Picker("Department", selection: $dept) {
                        Text("No department").tag("")
                        ForEach(biz.views[org]?.depts ?? []) { d in Text(verbatim: d.name).tag(d.id) }   // USER-DATA: a department's name
                    }
                }
                TextField("Position", text: $title)
            }
            .listRowBackground(MTGlassRowPlate())
        }
        .mtBizRefused($refused)
        .onAppear {
            let m = biz.views[org]?.member(member)
            // An archived department reads as none: the core takes only an open one (unknown_dept).
            dept = biz.views[org]?.dept(m?.dept)?.id ?? ""
            title = m?.title ?? ""
        }
    }
    private func set() {
        let t = String(title.trimmingCharacters(in: .whitespacesAndNewlines).prefix(256))
        let v = biz.views[org]
        let d = v?.role == .manager ? v?.myDept : (dept.isEmpty ? nil : dept)
        Task {
            if await MTBusiness.shared.write(org, MTBizCommand.assign(member: member, dept: d, title: t)) != nil { dismiss() } else { refused = true }
        }
    }
}

/// One name to give: a department, a channel, a department's chat -- the last one comes with its department's name, ready.
struct MTBizNameSheet: View {
    let title: LocalizedStringKey
    var field: LocalizedStringKey = "Department name"
    var initial = ""
    let act: @MainActor (String) async -> Bool
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var refused = false
    @FocusState private var typing: Bool
    private var given: String { String(name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(256)) }
    var body: some View {
        MTBizSheet(title: title, done: { give() }, ready: !given.isEmpty) {
            Section { TextField(field, text: $name).mtBizFirstField($typing) { give() } }.listRowBackground(MTGlassRowPlate())
        }
        .mtBizRefused($refused)
        .onAppear { if name.isEmpty { name = initial } }
    }
    private func give() {
        let n = given
        guard !n.isEmpty else { return }
        Task { if await act(n) { dismiss() } else { refused = true } }
    }
}

/// An item of the shop: its name, its price in coins, how many there are (none -- without a count).
struct MTBizOfferSheet: View {
    let org: String
    @Environment(\.dismiss) private var dismiss
    @State private var title = ""
    @State private var price = ""
    @State private var stock = ""
    @State private var refused = false
    @FocusState private var typing: Bool
    var body: some View {
        MTBizSheet(title: "New item", done: { set() },
                   ready: !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && 0 < (UInt64(price.filter { $0.isASCII && $0.isNumber }) ?? 0)) {
            Section {
                TextField("Item name", text: $title).mtBizFirstField($typing)
                TextField("Price in coins", text: $price).keyboardType(.numberPad)
                TextField("How many (optional)", text: $stock).keyboardType(.numberPad)
            }
            .listRowBackground(MTGlassRowPlate())
        }
        .mtBizRefused($refused)
    }
    private func set() {
        let t = String(title.trimmingCharacters(in: .whitespacesAndNewlines).prefix(256))
        guard !t.isEmpty, let p = UInt64(price.filter { $0.isASCII && $0.isNumber }), 0 < p, let item = MTBusiness.freshTag() else { refused = true; return }
        let s = UInt32(clamping: UInt64(stock.filter { $0.isASCII && $0.isNumber }) ?? 0)
        Task {
            if await MTBusiness.shared.write(org, MTBizCommand.offer(item: item, title: t, price: p, stock: s, active: true)) != nil { dismiss() } else { refused = true }
        }
    }
}
