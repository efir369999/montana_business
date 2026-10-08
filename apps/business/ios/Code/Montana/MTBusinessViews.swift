import SwiftUI
import UniformTypeIdentifiers
import UIKit

// ════════════════════════════════════════════════════════════
// MONTANA BUSINESS: THE PAGES
// ════════════════════════════════════════════════════════════
// The platform's own list, its sections and rows on the one-tone glass (MTGlassRowPlate), the page's ground, the marks of the
// bar (the cross, the checkmark): the Business looks as the Settings do. Every row is a button on all of itself; every word
// comes from the catalogue. Android draws the same pages from the same view of the core (MtBusiness.nativeView).

/// THE BUSINESS'S WORDS FOR TIME, ONE OWNER (point 0 of the constitution): a day, a moment, a span and a running clock -- all in
/// the person's language (MTLanguage.locale), never the system's region by accident.
enum MTBizText {
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
    /// A running clock, hours:minutes:seconds, as the platform's timers show one.
    static func clock(_ seconds: UInt64) -> String {
        Duration.seconds(Int64(clamping: seconds)).formatted(.time(pattern: .hourMinuteSecond).locale(MTLanguage.locale))
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

/// AN ORGANISATION'S PAGE: its people (every row opens the person's card), the invitation, the departments and the open
/// invitations -- each section only where the person's role may act in it (the core's «can»).
struct MTBizOrgPage: View {
    let org: String
    @ObservedObject private var biz = MTBusiness.shared
    @State private var inviting = false
    @State private var naming = false
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
                MTBizShiftsSection(org: org, view: v)
                supply(v)
                if v.may("dept") { depts(v) }
                if v.may("revoke") { invites(v) }
                MTBizJournalSection(org: org)
            }
        }
        .mtBizPage(named: v?.org?.name ?? "")
        .sheet(isPresented: $inviting) { MTBizInviteSheet(org: org) }
        .sheet(isPresented: $naming) { MTBizNameSheet(title: "New department") { n in
            guard let tag = MTBusiness.freshTag() else { return false }
            return await MTBusiness.shared.write(org, MTBizCommand.dept(tag, name: n, archived: false)) != nil
        } }
        .mtBizWord($word)
        .onAppear { biz.redraw() }
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

/// A PERSON'S CARD IN THE ORGANISATION: who they are in it, and every act the viewer's role allows on them -- the role, the
/// department and the job title, the pair's chat, the removal.
struct MTBizMemberPage: View {
    let org: String
    let member: String
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var biz = MTBusiness.shared
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
                        Text("Closed shifts begun this week and this month, on UTC boundaries; a week begins on Monday.")
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

                if mine, v.may("profile") {
                    Section {
                        Button { renew() } label: { MTBizRowLabel(glyph: "arrow.triangle.2.circlepath", title: "Update my name and link") }
                    } footer: { Text("Your name and Montana link as the organization shows them to its people.") }
                    .listRowBackground(MTGlassRowPlate())
                }

                if !mine {
                    Section {
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
