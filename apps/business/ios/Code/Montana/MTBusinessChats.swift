import CoreLocation
import MapKit
import SwiftUI

// ════════════════════════════════════════════════════════════
// MONTANA BUSINESS: THE ORGANISATION'S CHATS AND CHANNELS (chain C, the checklist's contract v1.1, stage 7.2)
// ════════════════════════════════════════════════════════════
// A chat of the organisation rides a Messenger group or channel (MTGroup): the same rows, receipts and sealed archive, the
// same feed on the chats page -- but no carrier (Sh.2, the checklist: every one to every one). Chain C beside it proves who
// said what and when, and who hears it comes from the roster -- the core decides both, and this file repeats no rule of the
// core. Opening writes the Open record first, naming a group id drawn here; the group then stands on every phone the core
// says hears the chat, with everyone that phone reaches among the people the core names, and every phone writes its own
// letters to all of them. Every letter of mine into such a group writes its Letter link (MTGroup.send); the core makes the
// letter's hash from its one name and its words.

extension MTBusiness {
    /// The organisation's chat a Messenger group carries: the organisation and the chat as its view shows it.
    func orgChat(group id: String) -> (org: String, chat: MTBizView.Chat)? {
        for (org, v) in views {
            if let c = v.chatList.first(where: { $0.group == id }) { return (org, c) }
        }
        return nil
    }

    /// A NEW CHAT OF THE ORGANISATION: a channel (the administrators' voice to everyone) or a department's chat. Nil -- the
    /// core refused it, and nothing is born.
    @MainActor
    func openChat(_ org: String, channel: Bool, name: String, dept: String?) async -> String? {
        guard let group = Self.freshTag(), let lane = MTBizCore.random(32)?.montanaHexString else { return nil }
        var cmd: [String: Any] = ["kind": "open", "chat": lane, "chat_kind": channel ? 2 : 1, "name": name, "group": group]
        if let dept { cmd["dept"] = dept }
        guard await write(org, cmd) != nil else { return nil }
        gather(org, chat: lane)
        return lane
    }

    /// THE CHAT'S GROUP STANDS ON THIS PHONE (Sh.2): wherever the core says the person hears the chat -- opened here or on any
    /// other phone -- with everyone this phone reaches among its people. Asked again from the chat's row.
    @MainActor @discardableResult
    func gather(_ org: String, chat lane: String) -> Chat? {
        guard let store = ChatStore.live, let v = views[org], let c = v.chatList.first(where: { $0.id == lane }) else { return nil }
        return standChat(org, c, in: v, store: store)
    }

    /// One chat as the core draws it, laid on MTGroup: the people this phone reaches (each by their pipe), the count of all of
    /// them, who speaks in a channel (its author and the administrators, chat.rs Letter) and who takes another's letter away
    /// (the administrators, chat.rs Delete). Seats are the members' own names, shortened: every phone names a person alike.
    @MainActor @discardableResult
    private func standChat(_ org: String, _ c: MTBizView.Chat, in v: MTBizView, store: ChatStore) -> Chat? {
        guard let group = c.group, c.people.contains(v.me.member) else { return nil }
        let people = c.people.filter { $0 != v.me.member }.compactMap { m in pipe(of: m).map { MTGroupMember(seat: Self.seat(m), pipe: $0) } }
        let bosses = v.members.filter { Self.boss($0.role) }.map { Self.seat($0.member) }.sorted()
        let voices = Array(Set(bosses + [Self.seat(c.author)])).sorted()
        // Every person of the chat and the name the organisation knows them by (Sh.3: the chat's page lists them all).
        let everyone = c.people.map { Self.seat($0) }.sorted()
        var names: [String: String] = [:]
        for m in v.members where c.people.contains(m.member) { names[Self.seat(m.member)] = m.name }
        return MTGroup.shared.stand(org: org, id: group, kind: c.kind == "channel" ? .channel : .group, title: c.name,
                                    me: Self.seat(v.me.member), people: people, count: c.people.count,
                                    voices: voices, bosses: bosses, everyone: everyone, names: names, store: store)
    }
    /// A member's seat in every chat of the organisation: the first sixteen letters of their id, the same on every phone.
    static func seat(_ member: String) -> String { String(member.lowercased().prefix(16)) }

    /// A LETTER OF MINE INTO A GROUP THAT CARRIES AN ORGANISATION'S CHAT: its link in chain C. A group that carries none writes
    /// nothing, and neither does a chat whose lane has not reached this phone yet -- the letter itself has left either way.
    /// A comment under a channel's post is a letter of its own kind (chat.rs LETTER_COMMENT, Sh.4): every listener's.
    @MainActor
    func said(group id: String, mid: String, text: String, comment: Bool = false) {
        guard let (org, chat) = orgChat(group: id) else { return }
        var cmd: [String: Any] = ["kind": "letter", "chat": chat.id, "mid": mid, "text": text]
        if comment { cmd["letter_kind"] = 2 }
        post(org, cmd)   // written on the Business's queue; the letter has left
    }

    /// THE CHATS FOLLOW THE ROSTER, on every phone: each chat the core says this person hears stands with the people it names
    /// and this phone reaches -- a pipe opened since is in at once; whoever the core names no more (removed, moved to another
    /// department) is out of it, so a letter of the department never leaves for a phone that left it. A chat that no longer
    /// names this person falls silent here: its rows stay, nothing is written in it.
    @MainActor
    func tend(_ org: String) {
        guard let store = ChatStore.live, let v = views[org] else { return }
        for c in v.chatList { standChat(org, c, in: v, store: store) }
        let heard = Set(v.chatList.filter { $0.people.contains(v.me.member) }.compactMap(\.group))
        for id in MTGroup.shared.meshGroups(org: org) where !heard.contains(id) { MTGroup.shared.silence(id, store: store) }
    }

    /// The chat's group stands on this phone: opened here, or its invitation came.
    func standing(_ c: MTBizView.Chat) -> Bool {
        c.group.map { MTGroup.shared.state(MTGroup.key(of: $0)) != nil } ?? false
    }
}

/// THE CHATS OF THE ORGANISATION on its page: every chat this person hears, the whole row a button that opens its feed; a new
/// one by the right the core gave (open) -- a channel for the administrators, a department's chat for its manager.
struct MTBizChatsSection: View {
    let org: String
    let view: MTBizView
    @State private var naming: Kind?
    @State private var choosing = false
    @State private var channelFor = false   // a channel for the whole organisation or one office (Sh.4)
    @State private var chosenDept: String?
    @State private var word: String?

    enum Kind: Int, Identifiable {
        case channel, dept
        var id: Int { rawValue }
    }

    private var boss: Bool { MTBusiness.boss(view.me.role) }
    /// A manager opens the chat of their own open department alone (chat.rs Open); the administrators choose any.
    private var myDept: String? { view.role == .manager ? view.myDept : nil }
    private var canOpen: Bool { view.may("open") && (boss || myDept != nil) }

    var body: some View {
        // No chat and none to open: no empty section with a header alone (a member before any chat is opened).
        if !view.chatList.isEmpty || canOpen {
            Section {
                ForEach(view.chatList) { c in
                    Button { open(c) } label: { row(c) }
                }
                if view.may("open") {
                    if boss {
                        // AN OFFICE'S CHANNEL (Sh.4, the author's word 06.10 10:1x «channels»): the core opens a channel for one
                        // department as well (chat.rs Open: the administrators); with no department there is no question.
                        Button {
                            chosenDept = nil
                            if view.depts.isEmpty { naming = .channel } else { channelFor = true }
                        } label: { MTBizRowLabel(glyph: "megaphone", title: "New channel") }
                    }
                    if boss && !view.depts.isEmpty {
                        // One department: no question which one -- the name sheet opens at once.
                        Button {
                            if view.depts.count == 1, let d = view.depts.first { chosenDept = d.id; naming = .dept } else { choosing = true }
                        } label: { MTBizRowLabel(glyph: "person.3", title: "New department chat") }
                    } else if let d = myDept {
                        Button { chosenDept = d; naming = .dept } label: { MTBizRowLabel(glyph: "person.3", title: "New department chat") }
                    }
                }
            } header: { Text("Chats") } footer: {
                Text("Every letter of these chats is a link of the organization's TimeChain: who wrote it and when.")
            }
            .listRowBackground(MTGlassRowPlate())
            .confirmationDialog("Channel", isPresented: $channelFor, titleVisibility: .visible) {
                Button { chosenDept = nil; naming = .channel } label: { Text("The whole organization") }
                ForEach(view.depts) { d in
                    // USER-DATA: the department's name
                    Button { chosenDept = d.id; naming = .channel } label: { Text(verbatim: d.name) }
                }
            }
            .confirmationDialog("Department", isPresented: $choosing, titleVisibility: .visible) {
                ForEach(view.depts) { d in
                    // USER-DATA: the department's name
                    Button { chosenDept = d.id; naming = .dept } label: { Text(verbatim: d.name) }
                }
            }
            .sheet(item: $naming) { k in
                // A department's chat comes named after its department; the name stays the person's to change.
                MTBizNameSheet(title: k == .channel ? "New channel" : "New department chat", field: "Chat name",
                               initial: view.dept(chosenDept)?.name ?? "") { name in
                    await MTBusiness.shared.openChat(org, channel: k == .channel, name: name, dept: chosenDept) != nil
                }
            }
            .mtBizWord($word)
        }
    }

    private func row(_ c: MTBizView.Chat) -> some View {
        HStack(spacing: 12) {
            MTBizGlyph(c.kind == "channel" ? "megaphone.fill" : "person.3.fill")
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: c.name).foregroundColor(.white)   // USER-DATA: the chat's name
                line(for: c).font(.caption).foregroundColor(.gray)
            }
            Spacer()
        }
        .frame(minHeight: 44)
        .contentShape(Rectangle())
    }

    /// Under the name: whom the chat is for, or why its feed is not here yet.
    private func line(for c: MTBizView.Chat) -> Text {
        if !MTBusiness.shared.standing(c) {
            return c.author == view.me.member ? Text("Nobody of this chat is reachable yet") : Text("The group's invitation is on its way")
        }
        if let d = view.dept(c.dept) { return Text(verbatim: d.name) }   // USER-DATA: the department's name
        return Text("The whole organization")
    }

    @MainActor private func open(_ c: MTBizView.Chat) {
        // A chat whose group is not named yet says so -- a row is never a dead button (point 0 of the constitution).
        guard let g = c.group else { word = "The group's invitation is on its way"; return }
        if !MTBusiness.shared.standing(c), MTBusiness.shared.gather(org, chat: c.id) == nil {
            word = c.author == view.me.member ? "Nobody of this chat is reachable yet" : "The group's invitation is on its way"
            return
        }
        NotificationCenter.default.post(name: .openChatRequest, object: nil, userInfo: ["address": MTGroup.key(of: g)])
    }
}

// ════════════════════════════════════════════════════════════
// A CHAT FOLLOWS ITS PEOPLE ON THE MAP (the author's word 06.10.2026 14:3x MSK: «in the business add to the groups' settings a
// checkbox to track the group's employees by geolocation -- for couriers, logisticians, agents, representatives»)
// ════════════════════════════════════════════════════════════
// The switch is the core's (chain C, Track): the chat's author or an administrator turns it, and every phone of the chat reads it
// from the view. While it stands, each of the chat's people who does not speak for it tells where they stand -- to its author and
// its administrators alone, phone to phone, by the Business's own word (g); no chain, no file and no server holds it: the last
// place of each person lives in the memory of those who follow, for the map. The person followed sees it said on the chat's page,
// and the system's own location mark while the app works in the background.

/// One person's last place as their phone told it.
struct MTBizSpot: Equatable {
    var member: String
    var la: Double
    var lo: Double
    var ac: Double
    var at: UInt64
    /// The app sent it by itself: a window passed without the person's own place (TrackWindow) -- said on the map, never hidden.
    var auto: Bool = false
}

@MainActor
final class MTBizTrack: NSObject, ObservableObject, CLLocationManagerDelegate {
    static let shared = MTBizTrack()
    /// The last place of each followed person, by chat: what the map draws.
    @Published private(set) var spots: [String: [String: MTBizSpot]] = [:]
    private let manager = CLLocationManager()
    private var running = false
    private var told: CLLocation?
    private var toldAt = Date.distantPast
    /// A place is told again after a minute or fifty metres, whichever comes first: a courier's road, not a heartbeat.
    static let interval: TimeInterval = 60
    static let distance: CLLocationDistance = 50
    /// THE WINDOWS (the author's word 06.10.2026 15:1x MSK: «a requirement can be set on the employees to send the geolocation in a
    /// given time window, otherwise the app itself sends it at random in the next time window»): window k of a chat is the k-th
    /// span of its window_s seconds on the clock. The person sends their place in it by their own touch; a window that passes
    /// without it gets its place from the app, at a moment drawn at random inside the next window -- the person cannot time it --
    /// and that place says it was the app's. The person's own place in the next window makes the app's needless.
    private var last: CLLocation?
    private var seen: [String: Int64] = [:]       // a chat's last window this phone saw
    private var sentIn: [String: Int64] = [:]     // the window the person last sent their own place in
    private var autoAt: [String: Date] = [:]      // the app's moment for a window that passed without it
    private var ticker: Timer?
    static let windows: [UInt64] = [0, 900, 1800, 3600, 7200, 14400]

    /// THE PERSON SAYS YES BEFORE THEIR PLACE LEAVES (App Review 5.1.2(i), 08.10.2026: «you may not use, transmit, or share
    /// someone's personal data without first obtaining their permission»): a chat that follows its people asks each of them once;
    /// only a yes lets the place go to the chat's managers -- as the person moves, in the background, or by itself in a window. A
    /// no is kept, and the person turns it either way on the chat's page at any time; a touch on «Send my location» is the
    /// person's own word for that one place.
    static let consentKey = "biz.track.consent"   // the person's answer for each followed chat
    /// When each chat was last asked: a question the system refused (no window) or the person left open is asked again later.
    private var askedAt: [String: Date] = [:]
    static func answer(_ chat: String) -> Bool? { (UserDefaults.standard.dictionary(forKey: consentKey) as? [String: Bool])?[chat] }
    static func setAnswer(_ chat: String, _ yes: Bool) {
        var all = (UserDefaults.standard.dictionary(forKey: consentKey) as? [String: Bool]) ?? [:]
        all[chat] = yes
        UserDefaults.standard.set(all, forKey: consentKey)
        MontanaP2PTrace.mark("biz_track", "consent=\(yes ? 1 : 0)")
        Task { @MainActor in MTBizTrack.shared.tend() }
    }
    /// The followed chats this person said yes to: the only ones their place goes to by itself.
    private func allowed() -> [(org: String, chat: MTBizView.Chat)] {
        MTBusiness.shared.followedChats().filter { Self.answer($0.chat.id) == true }
    }
    private func ask(_ c: MTBizView.Chat) {
        // ASKED ONLY TO A PERSON WHO SEES IT (the critic 08.10): never from the background, and again after ten minutes without an
        // answer -- a question the system refused for want of a window is not lost until the next launch.
        guard UIApplication.shared.applicationState == .active else { return }
        if let at = askedAt[c.id], Date().timeIntervalSince(at) < 600 { return }
        askedAt[c.id] = Date()
        let a = UIAlertController(title: String(localized: "Share your location with this chat's managers?", bundle: MTLanguage.bundle),
                                  message: String(localized: "«\(c.name)» asks its people for their location. If you allow it, your location is sent to the chat's managers while you are a member — also in the background, with the location indicator shown — and by itself once in each reporting window. You can stop at any time on the chat's page.", bundle: MTLanguage.bundle),
                                  preferredStyle: .alert)
        a.addAction(UIAlertAction(title: String(localized: "Don't share", bundle: MTLanguage.bundle), style: .cancel) { _ in Self.setAnswer(c.id, false) })
        a.addAction(UIAlertAction(title: String(localized: "Share my location", bundle: MTLanguage.bundle), style: .default) { _ in Self.setAnswer(c.id, true) })
        MTTop.present(a, kind: "biz_track_consent")
    }

    /// THE PHONE TELLS ITS PLACE WHILE A CHAT FOLLOWS ITS PERSON AND THE PERSON SAID YES: asked after every picture of the
    /// organisations (MTBusiness.apply) and at every answer.
    func tend() {
        for (_, c) in MTBusiness.shared.followedChats() where Self.answer(c.id) == nil { ask(c) }
        let followed = !allowed().isEmpty
        if followed && !running { start() }
        if !followed && running { stop() }
        // a chat no longer followed, or one this phone no longer speaks for, keeps nobody's place
        let windowed = allowed().filter { 0 < ($0.chat.track_window_s ?? 0) }
        if !windowed.isEmpty && ticker == nil {
            ticker = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { _ in Task { @MainActor in MTBizTrack.shared.tick() } }
        }
        if windowed.isEmpty, let t = ticker { t.invalidate(); ticker = nil }
        let lanes = Set(windowed.map(\.chat.id))
        seen = seen.filter { lanes.contains($0.key) }; sentIn = sentIn.filter { lanes.contains($0.key) }; autoAt = autoAt.filter { lanes.contains($0.key) }
        tick()
        let watching = MTBusiness.shared.watchedChats()
        if spots.keys.contains(where: { !watching.contains($0) }) { spots = spots.filter { watching.contains($0.key) } }
    }
    private func start() {
        running = true
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
        manager.distanceFilter = Self.distance
        manager.activityType = .otherNavigation
        manager.pausesLocationUpdatesAutomatically = true
        if manager.authorizationStatus == .notDetermined { manager.requestWhenInUseAuthorization() }
        let modes = Bundle.main.object(forInfoDictionaryKey: "UIBackgroundModes") as? [String] ?? []
        if modes.contains("location") {
            manager.allowsBackgroundLocationUpdates = true
            manager.showsBackgroundLocationIndicator = true   // the person followed sees it, by the system's own mark
        }
        manager.startUpdatingLocation()
        MontanaP2PTrace.mark("biz_track", "on")
    }
    private func stop() {
        running = false
        manager.stopUpdatingLocation()
        told = nil
        MontanaP2PTrace.mark("biz_track", "off")
    }
    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let l = locations.last else { return }
        Task { @MainActor in self.moved(l) }
    }
    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        Task { @MainActor in if self.running { self.manager.startUpdatingLocation() } }
    }
    private func moved(_ l: CLLocation) {
        guard running, 0 <= l.horizontalAccuracy else { return }
        last = l
        // the place follows the person only in a chat with no window; a windowed chat hears it by the person's touch or the app's draw
        let following = Set(allowed().filter { ($0.chat.track_window_s ?? 0) == 0 }.map(\.chat.id))
        guard !following.isEmpty else { return }
        if let t = told, Date().timeIntervalSince(toldAt) < Self.interval, l.distance(from: t) < Self.distance { return }
        told = l
        toldAt = Date()
        MTBusiness.shared.tell(la: l.coordinate.latitude, lo: l.coordinate.longitude, ac: l.horizontalAccuracy,
                               at: UInt64(max(0, l.timestamp.timeIntervalSince1970) * 1000), only: following)
    }
    /// Every half minute while a windowed chat follows this person: a window that passed without the person's place gets the app's
    /// moment in the next one; a moment come sends.
    func tick() {
        let now = Date().timeIntervalSince1970
        for (_, c) in allowed() {
            let w = Double(c.track_window_s ?? 0)
            guard 0 < w else { continue }
            let k = Int64(now / w)
            if let was = seen[c.id], was < k {
                if (sentIn[c.id] ?? -1) < was, autoAt[c.id] == nil {
                    autoAt[c.id] = Date(timeIntervalSince1970: Double(k) * w + Double.random(in: 0..<w))
                    MontanaP2PTrace.mark("biz_track", "window missed -- the app sends at random in the next")
                }
            }
            seen[c.id] = k
            if let at = autoAt[c.id], at.timeIntervalSince1970 <= now {
                autoAt[c.id] = nil
                send(c.id, auto: true)
            }
        }
    }
    /// THE PERSON'S OWN PLACE, by their touch on the chat's page: it answers this window and makes the app's draw needless.
    func sendNow(_ lane: String) {
        guard let c = MTBusiness.shared.followedChats().first(where: { $0.chat.id == lane })?.chat, let w = c.track_window_s, 0 < w else { return }
        sentIn[lane] = Int64(Date().timeIntervalSince1970 / Double(w))
        autoAt[lane] = nil
        send(lane, auto: false)
    }
    private func send(_ lane: String, auto: Bool) {
        guard let l = last ?? manager.location else { MontanaP2PTrace.mark("biz_track", "no place yet"); return }
        MTBusiness.shared.tell(la: l.coordinate.latitude, lo: l.coordinate.longitude, ac: max(0, l.horizontalAccuracy),
                               at: UInt64(max(0, Date().timeIntervalSince1970) * 1000), auto: auto, only: [lane])
    }
    /// The end of this window, and whether the person's own place already answers it.
    func due(_ lane: String, window: UInt64) -> (end: Date, sent: Bool) {
        let w = Double(window)
        let k = Int64(Date().timeIntervalSince1970 / w)
        return (Date(timeIntervalSince1970: Double(k + 1) * w), sentIn[lane] == k)
    }
    static func word(_ s: UInt64) -> LocalizedStringKey {
        switch s {
        case 0: return "Off"
        case 900: return "Every 15 minutes"
        case 1800: return "Every 30 minutes"
        case 3600: return "Every hour"
        case 7200: return "Every 2 hours"
        case 14400: return "Every 4 hours"
        default: return "Every \(Int(s / 60)) minutes"
        }
    }
    static func phrase(_ s: UInt64) -> String {
        switch s {
        case 0: return String(localized: "Off", bundle: MTLanguage.bundle)
        case 900: return String(localized: "Every 15 minutes", bundle: MTLanguage.bundle)
        case 1800: return String(localized: "Every 30 minutes", bundle: MTLanguage.bundle)
        case 3600: return String(localized: "Every hour", bundle: MTLanguage.bundle)
        case 7200: return String(localized: "Every 2 hours", bundle: MTLanguage.bundle)
        case 14400: return String(localized: "Every 4 hours", bundle: MTLanguage.bundle)
        default: return String(localized: "Every \(Int(s / 60)) minutes", bundle: MTLanguage.bundle)
        }
    }
    /// The voices choose the window from the platform's own sheet of actions, raised by the one road of every modal (MTTop).
    static func chooseWindow(org: String, chat: String) {
        let sheet = UIAlertController(title: String(localized: "Place window", bundle: MTLanguage.bundle), message: nil, preferredStyle: .actionSheet)
        for s in windows {
            sheet.addAction(UIAlertAction(title: phrase(s), style: .default) { _ in
                Task { await MTBusiness.shared.write(org, MTBizCommand.trackWindow(chat: chat, seconds: s)) }
            })
        }
        sheet.addAction(UIAlertAction(title: String(localized: "Cancel", bundle: MTLanguage.bundle), style: .cancel))
        MTTop.present(sheet, kind: "biz_track_window")
    }
    /// A place a colleague told this phone (MTBusiness, word g): the newer of two places stands.
    func heard(chat: String, _ s: MTBizSpot) {
        if let old = spots[chat]?[s.member], s.at <= old.at { return }
        spots[chat, default: [:]][s.member] = s
    }

    /// THE CHAT'S PAGE SAYS IT (MontanaPeerInfoScreen): to those who speak for an organisation's chat, the switch and the map; to a
    /// person the chat follows, that it does -- the row opens the system's own settings of the app's location. Nothing for any
    /// other chat.
    static func rows(_ key: String) -> [MTInfoRow] {
        guard let id = MTGroup.shared.state(key)?.id, let (org, c) = MTBusiness.shared.orgChat(group: id),
              let v = MTBusiness.shared.views[org] else { return [] }
        let on = c.track == true
        if MTBusiness.speaks(for: c, in: v) {
            var rows = [MTInfoRow(id: "track.header", kind: .header("LOCATION")),
                        MTInfoRow(id: "track.switch", kind: .toggle(title: "Track employees' location", icon: "location.fill", isOn: Binding(
                            get: { MTBusiness.shared.views[org]?.chatList.first { $0.id == c.id }?.track == true },
                            set: { want in Task { await MTBusiness.shared.write(org, MTBizCommand.track(chat: c.id, on: want)) } })))]
            if on {
                rows.append(MTInfoRow(id: "track.window", kind: .disclosure(title: "Place window", detail: word(c.track_window_s ?? 0), icon: "clock.fill",
                                                                              tint: .blue, action: { chooseWindow(org: org, chat: c.id) })))
                rows.append(MTInfoRow(id: "track.map", kind: .disclosure(title: "Map", detail: "Where the chat's people are", icon: "map.fill",
                                                                           tint: .blue, action: { MTBizTrackMap.present(org: org, chat: c.id, title: c.name) })))
            }
            return rows
        }
        guard on else { return [] }
        // THE PERSON'S OWN SWITCH (5.1.2(i), 08.10.2026): their yes or no for this chat, turned here at any time.
        var rows = [MTInfoRow(id: "track.header", kind: .header("LOCATION")),
                    MTInfoRow(id: "track.shared", kind: .toggle(title: "Share my location with the managers", icon: "location.fill", isOn: Binding(
                        get: { MTBizTrack.answer(c.id) == true },
                        set: { MTBizTrack.setAnswer(c.id, $0) })))]
        if let w = c.track_window_s, 0 < w {
            let d = MTBizTrack.shared.due(c.id, window: w)
            rows.append(MTInfoRow(id: "track.send", kind: .disclosure(title: "Send my location",
                                                                      detail: d.sent ? "Sent in this window" : "Send your place by \(d.end.formatted(date: .omitted, time: .shortened))",
                                                                      icon: "location.circle.fill", tint: .blue, action: { MTBizTrack.shared.sendNow(c.id) })))
        }
        return rows
    }
}

/// THE MAP OF A FOLLOWED CHAT: the platform's own map, each person at their last place with their name and how long ago it was.
struct MTBizTrackMap: View {
    let org: String
    let chat: String
    let title: String
    @ObservedObject private var track = MTBizTrack.shared
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let spots = (track.spots[chat] ?? [:]).values.sorted { $0.member < $1.member }
        NavigationStack {
            Map {
                ForEach(spots, id: \.member) { s in
                    // USER-DATA: the colleague's name, as the organisation knows them
                    Annotation(name(s.member), coordinate: CLLocationCoordinate2D(latitude: s.la, longitude: s.lo)) {
                        VStack(spacing: 2) {
                            Image(systemName: "person.circle.fill").font(.title).foregroundStyle(.white, .blue)
                            Text(Date(timeIntervalSince1970: Double(s.at) / 1000), style: .relative).font(.caption2)
                            if s.auto { Text("Sent by the app").font(.caption2).foregroundStyle(.secondary) }
                        }
                    }
                }
            }
            .overlay(alignment: .bottom) {
                if spots.isEmpty {
                    Text("Nobody has told their place yet").font(.footnote)
                        .padding(.horizontal, 14).padding(.vertical, 10)
                        .background(.thinMaterial, in: Capsule())
                        .padding(.bottom, 24)
                }
            }
            .navigationTitle(Text(verbatim: title))   // USER-DATA: the chat's name
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { MontanaCallMark(glyph: "xmark", label: "Close") { dismiss() } }
            }
        }
    }
    private func name(_ member: String) -> String {
        MTBusiness.shared.views[org]?.members.first { $0.member == member }?.name ?? ""
    }
    /// The map rises by the one road of every modal (MTTop): the chat's page is the platform's own and holds no state of the Business.
    static func present(org: String, chat: String, title: String) {
        MTTop.present(MontanaHost.make(MTBizTrackMap(org: org, chat: chat, title: title)), kind: "biz_track_map")
    }
}
