import Foundation
import CryptoKit
import UIKit

// ════════════════════════════════════════════════════════════
// MONTANA BUSINESS: THE PERSON'S ORGANISATIONS, CARRIED BY THE PHONES THEMSELVES
// ════════════════════════════════════════════════════════════
// The author's words 06.10.2026: «the preparation of Montana for a Business OS, every function of a business, the setting up
// of the employees with their management and even the assignment of coins as a salary or an exchange». No server holds an
// organisation: every member's phone holds the roster (R), and the people's files (H) reach each employee's own lane and the
// administrators. The chains travel as one service word (BZ:) over the pair's own letters -- the road the groups' words
// ride (MTGroup) -- and a pay leaves as the Messenger's own coin letter (MTCoinSend), named biz: and the Pay record's id.

extension Notification.Name {
    /// The Business page opens (a joining link, a banner): the shell raises it over the tabs.
    static let montanaOpenBusiness = Notification.Name("montanaOpenBusiness")
}

/// THE PERSON'S ORGANISATIONS ON DISK: Application Support/Montana/Business, a folder of the person seated (SeedScope
/// seatFolders), parked with the person and lifted with them. One file per chain of an organisation (ORG.r, ORG.h), the
/// phone's own ledger of the roads (state.json) and the number's confirmation (phone.mtba).
enum MTBizPlace {
    static let dir: URL? = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first?
        .appendingPathComponent("Montana", isDirectory: true).appendingPathComponent("Business", isDirectory: true)
    /// THE FOLDER IS KEPT OUT OF THE DEVICE'S BACKUP (point 0, safety): no copy of Montana carries the Business -- the roster comes
    /// back from any member, the files from the administrators (tools/mt-copy-scope-check.py) -- and a device backup of it would
    /// hold the invitations' secrets, the number's confirmation and the organisations' records outside the phone. Its files are
    /// readable from the first unlock on (the words of an organisation arrive while the phone lies locked), written atomically.
    /// The flag is checked at every opening, never set once: a folder made while the flag failed would stay in the backup
    /// forever, and nobody would know (as Media's folder does).
    private static func folder() -> URL? {
        guard var d = dir else { return nil }
        let fm = FileManager.default
        if !fm.fileExists(atPath: d.path) {
            try? fm.createDirectory(at: d, withIntermediateDirectories: true,
                                    attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        }
        if (try? d.resourceValues(forKeys: [.isExcludedFromBackupKey]))?.isExcludedFromBackup != true {
            var rv = URLResourceValues()
            rv.isExcludedFromBackup = true
            try? d.setResourceValues(rv)
        }
        return d
    }
    static func url(_ name: String) -> URL? { folder()?.appendingPathComponent(name) }
    static func read(_ name: String) -> Data? { url(name).flatMap { try? Data(contentsOf: $0) } }
    @discardableResult
    static func write(_ name: String, _ d: Data) -> Bool {
        guard let u = url(name) else { return false }
        return (try? d.write(to: u, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])) != nil
    }
    static func names() -> [String] {
        guard let dir else { return [] }
        return (try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []
    }
    /// THE ORGANISATION'S OWN MEDIA: an order's voice note, an acceptance's photo -- kept in a folder of the organisation's own
    /// (ORG.media), so they leave the phone with the organisation (MTBusiness.dropRemoved). The file is named by its hash.
    static func keep(_ org: String, _ name: String, _ d: Data) -> URL? {
        guard MTBusiness.isHex(org, 64), let folder = url(org + ".media") else { return nil }   // NOT-UI: a folder name
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let u = folder.appendingPathComponent(name)
        return (try? d.write(to: u, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])) != nil ? u : nil
    }
    /// The organisation's media let go: every file written empty by the same road, then the folder taken away.
    static func eraseMedia(_ org: String) {
        guard MTBusiness.isHex(org, 64), let folder = url(org + ".media"),
              let names = try? FileManager.default.contentsOfDirectory(atPath: folder.path) else { return }
        for n in names { try? Data().write(to: folder.appendingPathComponent(n), options: .atomic) }
        try? FileManager.default.removeItem(at: folder)
    }
    /// SHA-256 of bytes, in hex -- the one hash of the Business's screens (a key's subject, a voice note, a photo, a word sent).
    static func hash(_ d: Data) -> String { Data(SHA256.hash(data: d)).montanaHexString }

    /// THE ONE DOOR OUT TO FILES (7.4, 10; point 0): an export -- an organisation's chains, a payroll -- is written into a folder of
    /// its own in the temporary folder, and that folder is emptied before every export, so no earlier copy of an
    /// organisation's records stays behind on the phone after it was handed to the platform's sheet.
    private static var exports: URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("BusinessExport", isDirectory: true)   // NOT-UI: a folder name
    }
    /// The last export let go -- at every launch, as before every export: the sheet that took it is long closed by then.
    static func clearExports() { try? FileManager.default.removeItem(at: exports) }
    static func export(_ files: [(name: String, data: Data)]) -> [URL] {
        let fm = FileManager.default
        let out = exports
        clearExports()
        guard (try? fm.createDirectory(at: out, withIntermediateDirectories: true)) != nil else { return [] }
        return files.compactMap { f in
            let u = out.appendingPathComponent(f.name)
            return (try? f.data.write(to: u, options: [.atomic, .completeFileProtection])) != nil ? u : nil
        }
    }
    /// A name a person gave, made a file's name: letters, digits and spaces, sixty-four at most; empty -- the fallback.
    static func fileName(_ s: String, or fallback: String) -> String {
        let kept = String(s.filter { $0.isLetter || $0.isNumber || $0 == " " }.prefix(64)).trimmingCharacters(in: .whitespaces)
        return kept.isEmpty ? fallback : kept
    }
}

/// What this phone knows beside the chains: whom it reaches each member by, the invitations it minted, the joins on their way,
/// the pays whose coins left and those still owed, and the last word it carried to each member.
struct MTBizLocal: Codable {
    /// An invitation this phone minted: its organisation, its moment, the pipe its holder asked on. Its id is the core's to name
    /// from the secret (MTBizCore.inviteId); an older state's «id» field is passed over by the decoder.
    struct Minted: Codable { var org: String; var at: Double; var pipe: String? }
    struct Joining: Codable, Identifiable {
        var org: String; var secret: String; var pipe: String; var at: Double; var name: String; var card: String
        /// A MOVE (7.3): the person had a place on an old phone; the roster comes, they choose themselves (mine), and an
        /// administrator moves the place to this phone's key instead of a Join.
        var move: Bool? = nil
        var mine: String? = nil
        var id: String { org }
    }
    /// A new phone asking this administrator to move a member's place to its key: the key, the number the service confirmed for
    /// it, the pipe it asked by.
    struct Move: Codable, Identifiable {
        var org: String; var member: String; var key: String; var e164: String; var pipe: String; var at: Double
        /// The secret of the invitation the move came through: revoked the moment the place moves (point 0).
        var secret: String? = nil
        var id: String { org + ":" + member }
    }
    var routes: [String: String] = [:]
    var minted: [String: Minted] = [:]
    var joining: [String: Joining] = [:]
    var sent: [String: [String: String]] = [:]
    var met: [String: Double] = [:]
    /// The heads a member last told this phone, per organisation (contract 1.1): what they hold, so only what they lack goes.
    struct Heads: Codable, Equatable { var r: String; var h: String }
    var heads: [String: [String: Heads]] = [:]
    var moves: [String: Move] = [:]
    var left: [String: String] = [:]   // an organisation that removed this person: its name, until the line is hidden
    var firstDay: [String] = []   // organisations joined whose first day has not been shown yet (8.1)
    init() {}
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        routes = try c.decodeIfPresent([String: String].self, forKey: .routes) ?? [:]
        minted = try c.decodeIfPresent([String: Minted].self, forKey: .minted) ?? [:]
        joining = try c.decodeIfPresent([String: Joining].self, forKey: .joining) ?? [:]
        sent = try c.decodeIfPresent([String: [String: String]].self, forKey: .sent) ?? [:]
        met = try c.decodeIfPresent([String: Double].self, forKey: .met) ?? [:]
        moves = try c.decodeIfPresent([String: Move].self, forKey: .moves) ?? [:]
        left = try c.decodeIfPresent([String: String].self, forKey: .left) ?? [:]
        firstDay = try c.decodeIfPresent([String].self, forKey: .firstDay) ?? []
        heads = try c.decodeIfPresent([String: [String: Heads]].self, forKey: .heads) ?? [:]
    }
}

/// The Business's word on the wire. «q» -- one who holds an invitation asks its minter for the roster (k: the invitation's
/// secret); «s» -- the chains one member carries to another (r: the roster, h: the part of the files the receiver may hold),
/// with the speaker's member id (m). Short keys; a key a reader does not know is skipped by it. SYNC BY HEADS (contract 1.1):
/// «s» and «q» carry the speaker's own heads -- of the roster (rh) and of the closed stream (hh), base64 -- and the answer
/// carries only what the speaker lacks (mt_biz_after); a word without heads is taken as before, and answered whole.
struct MTBizWord: Codable {
    var t: String
    var o: String
    var m: String? = nil
    var r: String? = nil
    var h: String? = nil
    var k: String? = nil
    var a: String? = nil   // «d»: the number's confirmation for the new key (MTBA, hex)
    var p: String? = nil   // «d»: the new key (hex)
    var rh: String? = nil  // the speaker's heads of the roster (base64)
    var hh: String? = nil  // the speaker's heads of the closed stream (base64)
    var c: String? = nil   // «g»: the followed chat's lane (hex)
    var la: Double? = nil  // «g»: where the speaker stands -- latitude, longitude, the accuracy in metres, the moment (ms)
    var lo: Double? = nil
    var ac: Double? = nil
    var at: UInt64? = nil
    var au: Bool? = nil    // «g»: the app sent this place by itself -- a window passed without the person's own
}

/// The one owner of the person's organisations on this phone: the chains, the views the core draws of them, the roads to the
/// members, the writing of records, the words on the wire, the pays and their receipts.
/// THE CORE NEVER RUNS ON THE MAIN THREAD (point 0, third pass): the fold proves the ML-DSA signature of every record, and an
/// organisation of thousands of records stood the screen still at every touch. Every call of the core -- view, author, merge,
/// heads, after, keep, slice -- runs on the Business's one serial queue, and that queue alone holds the chains and the
/// phone's ledger (chains, local): one owner, the records of one organisation in their order. The main thread gets back the
/// finished picture -- the views, the joins, the moves, the lines of removal, the first days, the roads -- and the words to
/// carry, which leave after their chains lie on disk, as before. A touch never waits for the core: what it needs to hear
/// comes back as async.
// @unchecked Sendable, said aloud: what the queue holds (chains, local, keyCache, drawn, held, outbox, chores) is touched on the
// Business's queue alone, and the picture (views, joins, moves, left, greet, roads and the folding flags) on the main thread alone.
final class MTBusiness: ObservableObject, @unchecked Sendable {
    static let shared = MTBusiness()
    /// [P2P-COMPAT] Every older build buries this word unread (mtUnknownServiceWord): an organisation's word is never their row.
    static let mark = "\u{200B}\u{200B}BZ:"
    /// The joining link's form: https://SITE/b/join#1.ORG.SECRET.CARD (ORG and SECRET in hex, CARD the inviter's card link in
    /// base64url); the part after the hash never reaches a server. The same link is the QR code.
    static let linkVersion = "1"
    static let joinPath = "/b/join"
    static let backPath = "/b/back"
    static let rosterByte: UInt8 = 0x52

    struct Chains { var r: Data; var h: Data }

    // -- the main thread's picture (published from the queue; no screen writes it) -----------------
    @Published private(set) var views: [String: MTBizView] = [:]
    @Published private(set) var joins: [MTBizLocal.Joining] = []
    @Published private(set) var moves: [MTBizLocal.Move] = []
    @Published private(set) var left: [String: String] = [:]
    @Published private(set) var greet: [String] = []   // the first days to show (8.1)
    /// Invitations being opened right now -- the inviter's card is being met (the Messenger's first contact, over the network):
    /// the Business page says so while it lasts, instead of standing silent (K.6).
    @Published private(set) var opening = 0
    /// The roads to the members as the queue last told them: whom a pipe speaks for, on the main thread.
    private(set) var roads: [String: String] = [:]
    private var redrawing = false   // a redraw on its way folds the ones asked meanwhile

    // -- the Business's queue and what it alone holds ----------------------------------------------
    private let q = DispatchQueue(label: "montana.business", qos: .userInitiated)
    private var chains: [String: Chains] = [:]
    private var local = MTBizLocal()
    private var keyCache: (pub: Data, sk: Data)?
    private var drawn: [String: MTBizView] = [:]   // the views as the queue knows them
    private var held: Set<String> = []           // the pipes the main thread found held when this work began
    private var outbox: [(MTBizWord, String)] = [] // words to carry once the chains lie on disk
    private var chores: [@MainActor () -> Void] = []   // main-thread work after the picture lands (groups, meetings, coins)

    private init() {
        q.async {
            MTBizPlace.clearExports()   // a copy handed to the platform's sheet before this launch does not stay behind
            self.loadQ()
            let p = self.picture()
            DispatchQueue.main.async { MainActor.assumeIsolated { self.apply(p) } }
        }
        NotificationCenter.default.addObserver(forName: .montanaSeedForgotten, object: nil, queue: .main) { _ in
            MTBusiness.shared.reread()   // another person's organisations are not this one's
        }
    }

    // -- the queue -----------------------------------------------------------------------------

    /// What the queue hands the main thread after a piece of work: the picture and what is left to do there.
    private struct Picture {
        var views: [String: MTBizView]
        var joins: [MTBizLocal.Joining]
        var moves: [MTBizLocal.Move]
        var left: [String: String]
        var greet: [String]
        var roads: [String: String]
        var words: [(MTBizWord, String)]
        var chores: [@MainActor () -> Void]
    }
    /// The picture of this moment, taken on the queue; the words and chores go with it once.
    private func picture() -> Picture {
        let p = Picture(views: drawn, joins: Array(local.joining.values).sorted { $0.at > $1.at },
                        moves: local.moves.values.sorted { $0.at < $1.at }, left: local.left, greet: local.firstDay,
                        roads: local.routes, words: outbox, chores: chores)
        outbox = []
        chores = []
        return p
    }
    @MainActor
    private func apply(_ p: Picture) {
        views = p.views
        joins = p.joins
        moves = p.moves
        left = p.left
        greet = p.greet
        roads = p.roads
        for (w, pipe) in p.words { carry(w, to: pipe) }
        for c in p.chores { c() }
        MainActor.assumeIsolated { MTBizTrack.shared.tend() }   // a chat that follows this person, or watches someone, starts or stops here
    }
    /// The pipes this phone holds among the roads, asked where the pipe book lives.
    @MainActor
    private func heldNow() -> Set<String> { Set(roads.values.filter { MontanaConv.holds($0) }) }
    /// A piece of work on the queue whose answer the touch awaits; the picture lands on the main thread before the answer.
    @MainActor
    private func run<T>(_ work: @escaping () -> T) async -> T {
        let held = heldNow()
        return await withCheckedContinuation { cont in
            q.async {
                self.held = held
                let out = work()
                let p = self.picture()
                DispatchQueue.main.async {
                    MainActor.assumeIsolated { self.apply(p) }
                    cont.resume(returning: out)
                }
            }
        }
    }
    /// A piece of work on the queue nobody waits for.
    @MainActor
    private func send(_ work: @escaping () -> Void) {
        let held = heldNow()
        q.async {
            self.held = held
            work()
            let p = self.picture()
            DispatchQueue.main.async { MainActor.assumeIsolated { self.apply(p) } }
        }
    }

    // -- the store (queue) ---------------------------------------------------------------------

    /// The store was laid again under the living (a seat lifted, a person forgotten): everything is read from it again -- on
    /// the queue, from whatever thread asks; the picture lands on the main thread.
    func reread() {
        q.async {
            self.keyCache = nil
            self.chains = [:]
            self.drawn = [:]
            self.loadQ()
            let p = self.picture()
            DispatchQueue.main.async { MainActor.assumeIsolated { self.apply(p) } }
        }
    }
    private func loadQ() {
        local = MTBizPlace.read("state.json").flatMap { try? JSONDecoder().decode(MTBizLocal.self, from: $0) } ?? MTBizLocal()
        for n in MTBizPlace.names() where n.hasSuffix(".r") {
            let org = String(n.dropLast(2))
            guard Self.isHex(org, 64), let r = MTBizPlace.read(n), !r.isEmpty else { continue }   // an erased organisation is empty
            chains[org] = Chains(r: r, h: MTBizPlace.read(org + ".h") ?? Data())
        }
        drawAllQ()
    }
    private func saveQ(_ org: String) {
        guard let c = chains[org] else { return }
        MTBizPlace.write(org + ".r", c.r)
        MTBizPlace.write(org + ".h", c.h)
    }
    private func saveLocalQ() {
        pruneMintedQ()
        if let d = try? JSONEncoder().encode(local) { MTBizPlace.write("state.json", d) }
    }
    /// AN INVITATION'S SECRET STAYS ONLY WHILE THE INVITATION CAN BE USED (point 0, safety): its id is the core's to name from
    /// the secret (mt_biz_invite_id), and one the view names used, revoked or expired leaves at the next writing of the state --
    /// unless a move still waits on it, until that move ends. An invitation the view does not show -- not arrived yet, a role
    /// that no longer sees the invitations, an organisation not drawn -- is judged by its own age: it leaves once no invitation
    /// could still live, the longest term an invitation is given and a day. No secret is kept for ever.
    static let mintedLongest: Double = 31 * 86_400
    private func pruneMintedQ() {
        let waiting = Set(local.moves.values.compactMap(\.secret))
        let now = Date().timeIntervalSince1970
        local.minted = local.minted.filter { secret, m in
            if waiting.contains(secret) { return true }
            guard let raw = Data(montanaHex: secret), let id = MTBizCore.inviteId(raw),
                  let inv = drawn[m.org]?.invites.first(where: { $0.id == id }) else {
                return now - m.at < Self.mintedLongest
            }
            return inv.state == "open"   // NOT-UI: the view's token
        }
    }
    static var nowMs: UInt64 { UInt64(max(0, Date().timeIntervalSince1970 * 1000)) }
    /// The keys of the person seated: read once from the vault the seed's own keys live in (MontanaSeed.keys).
    private func keysQ() -> (pub: Data, sk: Data)? {
        if let k = keyCache { return k }
        guard let k = MontanaSeed.keys() else { return nil }
        keyCache = (k.pub, k.sk)
        return keyCache
    }
    /// EVERY VIEW DRAWN AGAIN (the salary due moves with the clock) -- on the queue. Asked again while one is on its way, the
    /// asking folds into it: no two redraws stand in a row.
    @MainActor
    func redraw() {
        guard !redrawing else { return }
        redrawing = true
        send {
            self.drawAllQ()
            self.chores.append { [weak self] in self?.redrawing = false }
        }
    }
    private func drawAllQ() {
        guard let pub = keysQ()?.pub else { drawn = [:]; return }
        var next: [String: MTBizView] = [:]
        for (org, c) in chains {
            if let v = MTBizCore.view(roster: c.r, hr: c.h, viewer: pub, now: Self.nowMs) { next[org] = v }
        }
        drawn = next
        dropRemovedQ()
    }
    private func drawQ(_ org: String) {
        guard let pub = keysQ()?.pub, let c = chains[org] else { return }
        drawn[org] = MTBizCore.view(roster: c.r, hr: c.h, viewer: pub, now: Self.nowMs)
        dropRemovedQ()
    }
    /// The organisations the person holds a place in, the newest first (a roster that came for a move, before the place is
    /// moved, is not one of them).
    var orgs: [MTBizView] {
        views.values.filter { $0.org != nil && $0.role != nil }.sorted { ($0.org?.created_ms ?? 0) > ($1.org?.created_ms ?? 0) }
    }

    /// THE PERSON WAS REMOVED (7.3): the fold names me removed, and this phone lets the organisation go -- its two chains are
    /// written empty by the one road of the files (MTBizPlace.write), the memory of its roads and words is dropped, and one line
    /// says so on the Business page. Words of that organisation are refused from here on, unless the person joins it again.
    private func dropRemovedQ() {
        for (org, v) in drawn {
            guard let me = v.member(v.me.member), me.status == "removed" else { continue }
            MTBizPlace.write(org + ".r", Data())
            MTBizPlace.write(org + ".h", Data())
            MTBizPlace.eraseMedia(org)   // the voice notes and photos of its orders leave with it
            chains[org] = nil
            drawn[org] = nil
            local.left[org] = v.org?.name ?? ""
            local.sent[org] = nil
            local.joining[org] = nil
            local.minted = local.minted.filter { $0.value.org != org }
            local.moves = local.moves.filter { $0.value.org != org }
            saveLocalQ()
            MontanaP2PTrace.mark("biz_left", "removed org=\(String(org.prefix(8)))")
        }
    }
    /// THE ORGANISATION'S CHAINS AS FILES (7.4): as they lie on this phone, under the organisation's name, through the one
    /// door out (MTBizPlace.export) for the platform's own sheet (Files, AirDrop, a message).
    @MainActor
    func chainFiles(_ org: String) async -> [URL]? {
        await run {
            guard let c = self.chains[org] else { return nil }
            let base = MTBizPlace.fileName(self.drawn[org]?.org?.name ?? "", or: String(org.prefix(8)))
            let parts = [("roster", c.r), ("closed", c.h)].filter { !$0.1.isEmpty }   // NOT-UI: file names
            return MTBizPlace.export(parts.map { (base + " " + $0.0 + ".mtbiz", $0.1) })
        }
    }
    /// AN INVITATION LINK FROM THE PASTEBOARD (the Business page and a new phone's sheet): taken when it is one, else the
    /// word to say.
    /// Once read, the link's secret leaves the pasteboard -- only while the pasteboard still holds what was read (its change
    /// count unchanged since): a copy another app made since is never touched.
    @MainActor
    func acceptCopied(move: Bool = false) -> String? {
        let pb = UIPasteboard.general
        let seen = pb.changeCount
        guard let s = pb.string?.trimmingCharacters(in: .whitespacesAndNewlines), let u = URL(string: s), Self.isJoin(u) else {
            return "Copy the invitation link first, then tap here."
        }
        let readable = Self.invitation(u) != nil
        accept(u, move: move)
        if readable, pb.changeCount == seen { pb.items = [] }
        return nil
    }
    /// A joining link read: the organisation, the invitation's secret and the inviter's card; nil when it cannot be read.
    static func invitation(_ url: URL) -> (org: String, secret: String, card: String)? {
        let parts = (url.fragment ?? "").split(separator: ".", omittingEmptySubsequences: false).map(String.init)
        guard parts.count == 4, parts[0] == linkVersion, isHex(parts[1], 64), isHex(parts[2], 64),
              let cardBytes = Data(base64urlNoPad: parts[3]), let card = String(data: cardBytes, encoding: .utf8), !card.isEmpty else { return nil }
        return (parts[1], parts[2], card)
    }
    /// The line about a removed organisation is hidden; the organisation stays let go.
    @MainActor
    func hideLeft(_ org: String) {
        send {
            guard self.local.left[org] != nil else { return }
            self.local.left[org] = ""
            self.saveLocalQ()
        }
    }

    // -- writing -------------------------------------------------------------------------------

    /// A chain is frames of a u32 length (little-endian) and the record; the door hands one record, framed here once.
    static func framed(_ rec: Data) -> Data {
        guard rec.prefix(4) == Data("MTB1".utf8) else { return rec }
        var n = UInt32(rec.count).littleEndian
        var d = Data(bytes: &n, count: 4)
        d.append(rec)
        return d
    }
    /// The chain a framed record belongs to: the byte after the length and the magic (R 0x52, H 0x48).
    static func chainByte(_ frame: Data) -> UInt8? {
        8 < frame.count ? frame[frame.startIndex + 8] : nil
    }

    /// ONE RECORD OF MINE (the touch's own door): written on the queue; the answer -- the organisation, or nil when the core
    /// refused -- comes back once the chain lies on disk and the picture is drawn.
    @MainActor @discardableResult
    func write(_ org: String?, _ command: [String: Any]) async -> String? {
        await run { self.writeQ(org, command) }
    }
    /// ONE RECORD OF MINE: the core writes it from the chains this phone holds and refuses what the fold does not allow; it
    /// joins its chain, the chain lies on disk before anyone is told, and the members hear it.
    @discardableResult
    private func writeQ(_ org: String?, _ command: [String: Any]) -> String? {
        guard let k = keysQ() else { return nil }
        let c = org.flatMap { chains[$0] } ?? Chains(r: Data(), h: Data())
        let rec: Data
        switch MTBizCore.author(seckey: k.sk, pubkey: k.pub, roster: c.r, hr: c.h, command: command, at: Self.nowMs) {
        case .success(let d): rec = Self.framed(d)
        case .failure(let f):
            MontanaP2PTrace.mark("biz_refused", "author kind=\(command["kind"] as? String ?? "-") code=\(f.code)")
            return nil
        }
        var next = c
        let roster = Self.chainByte(rec) == Self.rosterByte
        switch MTBizCore.merge(roster ? c.r : c.h, rec) {
        case .success(let m): if roster { next.r = m.chain } else { next.h = m.chain }
        case .failure(let f):
            MontanaP2PTrace.mark("biz_refused", "merge own code=\(f.code)")
            return nil
        }
        guard let v = MTBizCore.view(roster: next.r, hr: next.h, viewer: k.pub, now: Self.nowMs), let id = v.org?.id ?? org else { return nil }
        chains[id] = next
        saveQ(id)
        drawn[id] = v
        MontanaP2PTrace.mark("biz_wrote", "kind=\(command["kind"] as? String ?? "-") org=\(String(id.prefix(8)))")
        spreadQ(id)
        return id
    }

    /// THE ORGANISATION IS BORN on its owner's phone: the name, the owner's own name and card (the Messenger's standing link
    /// of the owner, so a member reaches the owner by it). Nil -- refused, nothing written.
    @MainActor
    func found(name: String, card: String) async -> String? {
        let me = E2E.myDisplayName()
        let attest = MTBizPhone.attestation()
        return await run {
            guard let org = self.writeQ(nil, MTBizCommand.genesis(name: name, ownerName: me, card: card)) else { return nil }
            self.bindPhoneQ(org, attest)
            return org
        }
    }

    /// AN INVITATION: its secret is drawn here and kept by this phone alone (the record names only its hash); the link carries
    /// the organisation, the secret and this person's card. A number given binds the invitation to it (InvitePhone): a joining
    /// by another number is marked to the administrators.
    @MainActor
    func invite(_ org: String, role: MTBizRole, dept: String?, days: Int, e164: String?, card: String) async -> String? {
        await run {
            guard let secret = MTBizCore.random(32)?.montanaHexString, let v = self.drawn[org] else { return nil }
            let before = Set(v.invites.map(\.id))
            let expires = Self.nowMs + UInt64(max(1, days)) * 86_400_000
            guard self.writeQ(org, MTBizCommand.invite(secret: secret, role: role, dept: dept, expires: expires, phoneBound: e164 != nil)) != nil else { return nil }
            let made = self.drawn[org]?.invites.first(where: { !before.contains($0.id) })
            self.local.minted[secret] = MTBizLocal.Minted(org: org, at: Date().timeIntervalSince1970, pipe: nil)
            self.saveLocalQ()
            if let e164, let made {
                self.writeQ(org, MTBizCommand.invitePhone(invite: made.id, e164: e164))
            }
            return Self.link(org: org, secret: secret, card: card)
        }
    }
    static func link(org: String, secret: String, card: String) -> String {
        "https://" + MontanaContour.domain + joinPath + "#" + linkVersion + "." + org + "." + secret + "." + Data(card.utf8).base64urlNoPad
    }

    /// The number confirmed on this phone goes into the organisation's files once (PhoneBind), for the key that holds it.
    @MainActor
    func bindPhone(_ org: String) {
        let attest = MTBizPhone.attestation()
        send { self.bindPhoneQ(org, attest) }
    }
    private func bindPhoneQ(_ org: String, _ attest: Data?) {
        guard let a = attest ?? MTBizPhone.attestation(), let v = drawn[org], v.may("phone_bind"),
              let me = v.member(v.me.member), !me.phone_confirmed else { return }
        writeQ(org, MTBizCommand.phoneBind(attest: a))
    }

    // -- joining -------------------------------------------------------------------------------

    static func isJoin(_ url: URL) -> Bool {
        let scheme = url.scheme?.lowercased() ?? ""
        let host = url.host?.lowercased() ?? ""
        if scheme == "https", host == MontanaContour.domain, url.path == joinPath { return true }
        return !MontanaContour.urlScheme.isEmpty && scheme == MontanaContour.urlScheme && host == "b" && url.path == "/join"
    }
    static func isBack(_ url: URL) -> Bool {
        let scheme = url.scheme?.lowercased() ?? ""
        let host = url.host?.lowercased() ?? ""
        if scheme == "https", host == MontanaContour.domain, url.path == backPath { return true }
        return !MontanaContour.urlScheme.isEmpty && scheme == MontanaContour.urlScheme && host == "b" && url.path == "/back"
    }
    private static func verdict(_ key: String) {
        DispatchQueue.main.async { NotificationCenter.default.post(name: .montanaMeetVerdict, object: nil, userInfo: ["msg": key]) }
    }

    /// A JOINING LINK OR ITS CODE: the inviter is met by the card the link carries -- the Messenger's own first contact, the
    /// road a card link takes -- and asked for the roster over that pipe; the Join record is written the moment it comes.
    @MainActor
    func accept(_ url: URL, move: Bool = false) {
        guard let inv = Self.invitation(url) else {
            MontanaP2PTrace.mark("biz_join", "unreadable link")
            Self.verdict("This invitation link cannot be read.")
            return
        }
        let org = inv.org, secret = inv.secret, card = inv.card
        if let v = views[org], v.role != nil {
            NotificationCenter.default.post(name: .montanaOpenBusiness, object: nil)
            return
        }
        guard MontanaSeed.hasSeed else {
            UserDefaults.standard.set(url.absoluteString, forKey: MontanaMeeting.pendingInviteKey)
            Self.verdict("Create your identity first — the invitation will open right after.")
            return
        }
        let name = E2E.myDisplayName()
        MontanaP2PTrace.mark("biz_join", "meeting the inviter org=\(String(org.prefix(8)))")
        opening += 1
        NotificationCenter.default.post(name: .montanaOpenBusiness, object: nil)
        Task {
            let mine = await Task.detached(priority: .userInitiated) { MontanaCard.offerPermanent() ?? "" }.value
            let met = await MontanaMeeting.meet(card)
            await MainActor.run {
                self.opening -= 1
                guard case .opened(let pipe) = met else {
                    MontanaP2PTrace.mark("biz_join", "the inviter's card did not open")
                    Self.verdict("The invitation could not be opened. Check the link or try again.")
                    return
                }
                let root = MTSamePair.root(pipe)
                self.send {
                    self.local.joining[org] = MTBizLocal.Joining(org: org, secret: secret, pipe: root, at: Date().timeIntervalSince1970,
                                                                 name: name, card: mine, move: move ? true : nil)
                    self.local.left[org] = nil   // joined again: the organisation is this person's once more
                    self.saveLocalQ()
                    // heads of nothing: the whole roster comes
                    self.outbox.append((MTBizWord(t: "q", o: org, k: secret, rh: Self.headsText(Data()), hh: Self.headsText(Data())), root))
                }
                NotificationCenter.default.post(name: .montanaOpenBusiness, object: nil)
            }
        }
    }

    // -- the wire ------------------------------------------------------------------------------

    @MainActor
    private func carry(_ w: MTBizWord, to pipe: String) {
        guard MontanaConv.holds(pipe), !ChatStore.refusesCold(pipe),
              let d = try? JSONEncoder().encode(w), let js = String(data: d, encoding: .utf8) else { return }
        let name = ChatStore.mintMid().mid
        MontanaDeliveryEngine.shared.enqueue(to: pipe, chat: pipe, mid: name, text: Self.mark + js, silent: true, headless: true)
        MontanaP2PTrace.mark("biz_tx", mid: name, "t=\(w.t) to=\(String(pipe.prefix(10))) bytes=\(js.utf8.count)")
    }

    /// THE CEILING OF ONE WORD (point 0, second pass): a word is measured before it is read -- the whole text, and so each of
    /// its streams -- and a larger one is buried unread. Thirty-two mebibytes: the roster and the closed stream of an
    /// organisation of some thousands of records, as base64.
    static let wordCeiling = 33_554_432

    /// AN ORGANISATION'S WORD ARRIVED ON A PIPE (ChatStore's landing). True when it is ours to read: it is never a row of the
    /// pipe. The word is read and taken on the Business's queue; its receipt goes from the main thread once it was taken (or
    /// buried), by the one receipt door on the pipe it came by.
    @MainActor
    static func handle(_ text: String, from pipe: String, isFromMe: Bool, sid: String?, store: ChatStore) -> Bool {
        guard text.hasPrefix(mark) else { return false }
        guard !isFromMe else { return true }
        guard text.utf8.count <= wordCeiling else {
            MontanaP2PTrace.mark("biz_refused", "too large bytes=\(text.utf8.count) from=\(String(pipe.prefix(10)))")
            _ = store.sendDeliveryReceipt(pipe, msgId: sid, isFromMe: false, text: text, buried: true)
            return true
        }
        let root = MTSamePair.root(pipe)
        let body = String(text.dropFirst(mark.count))
        let biz = shared
        biz.send {
            var ours = false
            if let d = body.data(using: .utf8), let w = try? JSONDecoder().decode(MTBizWord.self, from: d), isHex(w.o, 64),
               w.m.map({ isHex($0, 64) }) ?? true {
                ours = biz.takeQ(w, from: root)
            } else {
                MontanaP2PTrace.mark("biz_refused", "unreadable from=\(String(pipe.prefix(10)))")
            }
            biz.chores.append { _ = store.sendDeliveryReceipt(pipe, msgId: sid, isFromMe: false, text: text, buried: !ours) }
        }
        return true
    }

    private func takeQ(_ w: MTBizWord, from pipe: String) -> Bool {
        switch w.t {
        case "q": return askedQ(w, by: pipe)
        case "s": return syncedQ(w, from: pipe)
        case "d": return movingQ(w, from: pipe)
        case "g": return placedQ(w, from: pipe)
        default:
            MontanaP2PTrace.mark("biz_refused", "kind=\(String(w.t.prefix(8)))")   // a newer build's word: its road ends here
            return false
        }
    }

    /// WHERE A COLLEAGUE STANDS (word g, MTBizTrack): taken for a chat the core says is followed, from one of its people by the
    /// road bound to them, by a phone that speaks for the chat; handed to the main thread's memory, never written.
    private func placedQ(_ w: MTBizWord, from pipe: String) -> Bool {
        guard let v = drawn[w.o], let lane = w.c, let c = v.chatList.first(where: { $0.id == lane }), c.track == true,
              Self.speaks(for: c, in: v), let who = local.routes.first(where: { $0.value == pipe })?.key, who == w.m,
              c.people.contains(who), let la = w.la, let lo = w.lo, (-90.0...90.0).contains(la), (-180.0...180.0).contains(lo) else {
            MontanaP2PTrace.mark("biz_refused", "place from=\(String(pipe.prefix(10)))")
            return false
        }
        let spot = MTBizSpot(member: who, la: la, lo: lo, ac: max(0, w.ac ?? 0), at: w.at ?? 0, auto: w.au == true)
        chores.append { MTBizTrack.shared.heard(chat: lane, spot) }
        return true
    }

    /// One who holds an invitation this phone minted asks for the roster: it goes to them, and their pipe is remembered as
    /// the one their Join record will come by.
    private func askedQ(_ w: MTBizWord, by pipe: String) -> Bool {
        guard let k = w.k, var m = local.minted[k], m.org == w.o, let c = chains[w.o], let v = drawn[w.o] else { return false }
        if m.pipe != pipe {   // the same question again writes nothing; the roster goes to it again
            m.pipe = pipe
            local.minted[k] = m
            saveLocalQ()
        }
        let roster = Self.lacked(c.r, by: w.rh) ?? c.r
        outbox.append((MTBizWord(t: "s", o: w.o, m: v.me.member, r: roster.base64EncodedString(), rh: Self.headsText(c.r), hh: Self.headsText(c.h)), pipe))
        MontanaP2PTrace.mark("biz_rx", "asked by an invitation org=\(String(w.o.prefix(8)))")
        return true
    }

    /// THE CHAINS OF ANOTHER MEMBER: merged by the core (a record already held is one record), the files written, and what is
    /// new carried on. The pipe becomes the road to a member in two cases only: the inviter of a join this phone is making
    /// (met by the card the link carried), and a member who just joined by an invitation this phone minted, on the pipe that
    /// asked with its secret. A word that names a member is never trusted for a road by itself.
    private func syncedQ(_ w: MTBizWord, from pipe: String) -> Bool {
        let org = w.o
        let joining = local.joining[org].flatMap { $0.pipe == pipe ? $0 : nil }
        guard local.left[org] == nil || joining != nil else { return false }   // an organisation that removed this person
        // NOT MINE AND NOT ON MY WAY IN (point 0, second pass): an organisation this phone holds no place in and is not joining
        // or moving into is refused before a byte of the word is decoded, and nothing is written.
        guard joining != nil || drawn[org]?.role != nil else {
            MontanaP2PTrace.mark("biz_refused", "not a member org=\(String(org.prefix(8)))")
            return false
        }
        guard var c = chains[org] ?? (joining != nil ? Chains(r: Data(), h: Data()) : nil) else { return false }
        // ONLY THIS ORGANISATION'S RECORDS ARE MERGED (core 2f6e012, mt_biz_keep): a stream is kept to the organisation the word
        // names before the merge, so a record of another one never grows these chains.
        guard let orgId = Data(montanaHex: org) else { return false }
        let inR = w.r.flatMap { Data(base64Encoded: $0) }.flatMap { MTBizCore.keep($0, org: orgId) } ?? Data()
        let inH = w.h.flatMap { Data(base64Encoded: $0) }.flatMap { MTBizCore.keep($0, org: orgId) } ?? Data()
        let before = Set(drawn[org]?.members.map(\.member) ?? [])
        var added = 0
        if !inR.isEmpty, case .success(let m) = MTBizCore.merge(c.r, inR) { c.r = m.chain; added += m.added }
        if !inH.isEmpty, case .success(let m) = MTBizCore.merge(c.h, inH) { c.h = m.chain; added += m.added }
        // WHAT THE SPEAKER LACKS: by the heads it told (mt_biz_after); a word of an older build tells none, and the second merge
        // the other way round answers as before.
        var back = Data()
        if let lacked = Self.lacked(c.r, by: w.rh) {
            back = lacked
        } else if !c.r.isEmpty, case .success(let other) = MTBizCore.merge(inR, c.r), 0 < other.added {
            back = c.r
        }
        let lacks = !back.isEmpty
        guard !c.r.isEmpty else { return false }
        if 0 < added {
            chains[org] = c
            saveQ(org)
            drawQ(org)
        } else if joining == nil {
            // THE SAME WORD AGAIN (point 0, second pass): nothing new in it -- no chain is written, nothing drawn again; only
            // the speaker's heads are kept when they changed, and what it lacks is told back.
            heardQ(w, org: org, from: pipe)
            if lacks { tellBackQ(back, org: org, to: pipe) }
            return true
        }
        if let m = w.m, Self.isHex(m, 64), local.routes[m] == nil {
            if joining != nil {
                bindQ(m, pipe)   // the inviter, met by the card the link carried
            } else if !before.contains(m), drawn[org]?.member(m) != nil,
                      let k = local.minted.first(where: { $0.value.org == org && $0.value.pipe == pipe })?.key {
                bindQ(m, pipe)   // the member who joined by this phone's invitation, on the pipe that asked with its secret
                local.minted[k] = nil
                saveLocalQ()
            }
        }
        heardQ(w, org: org, from: pipe)
        MontanaP2PTrace.mark("biz_rx", "chains org=\(String(org.prefix(8))) added=\(added) lacks=\(lacks ? 1 : 0)")
        if let j = joining, drawn[org]?.role == nil {
            if j.move != true { joinNowQ(org) }   // a move waits for the person's choice and the administrator's touch
        } else if let j = joining, j.move == true {
            movedQ(org)
        } else if 0 < added {
            spreadQ(org)
        }
        if lacks { tellBackQ(back, org: org, to: pipe) }
        return true
    }

    /// THE SPEAKER'S HEADS ARE KEPT for the member whose road this pipe is -- never for a member a word merely names -- and only
    /// when they changed: the same heads again write nothing.
    private func heardQ(_ w: MTBizWord, org: String, from pipe: String) {
        guard let m = w.m, local.routes[m] == pipe, let rh = w.rh, let hh = w.hh else { return }
        let told = MTBizLocal.Heads(r: rh, h: hh)
        guard local.heads[org]?[m] != told else { return }
        local.heads[org, default: [:]][m] = told
        saveLocalQ()
    }
    /// What the speaker lacks goes back: to a member on their own road, by the one spread (their heads just kept); to any other
    /// pipe, the roster it lacks.
    private func tellBackQ(_ back: Data, org: String, to pipe: String) {
        if local.routes.values.contains(pipe) {
            spreadQ(org)
        } else if let c = chains[org] {
            outbox.append((MTBizWord(t: "s", o: org, m: drawn[org]?.me.member, r: back.base64EncodedString(), rh: Self.headsText(c.r), hh: Self.headsText(c.h)), pipe))
        }
    }
    /// A stream's heads as the word carries them (base64); nil when the core does not answer.
    static func headsText(_ stream: Data) -> String? { MTBizCore.heads(stream)?.base64EncodedString() }
    /// What the holder of the told heads lacks of a stream; nil when no heads were told (or they cannot be read): answer whole.
    static func lacked(_ stream: Data, by heads: String?) -> Data? {
        guard let heads, let h = Data(base64Encoded: heads) else { return nil }
        return MTBizCore.after(stream, heads: h)
    }

    /// The roster came for a join this phone is making: the Join record is written now, with the person's name and card.
    private func joinNowQ(_ org: String) {
        guard let j = local.joining[org] else { return }
        guard writeQ(org, MTBizCommand.join(secret: j.secret, name: j.name, card: j.card)) != nil else {
            MontanaP2PTrace.mark("biz_join", "the Join record was refused org=\(String(org.prefix(8)))")
            return
        }
        local.joining[org] = nil
        if !local.firstDay.contains(org) { local.firstDay.append(org) }   // the first day shows once on the Business page (8.1)
        saveLocalQ()
        bindPhoneQ(org, nil)
        MontanaP2PTrace.mark("biz_join", "joined org=\(String(org.prefix(8)))")
    }

    // -- a new phone (7.3) -----------------------------------------------------------------------

    /// THE PERSON CHOSE THEMSELVES in the roster that came for a move: the administrator who minted the invitation is asked, on
    /// the pipe the roster came by, to move that place to this phone's key -- with the key, the number's confirmation for it
    /// (MTBA) and the invitation's secret.
    @MainActor
    func askMove(_ org: String, member: String) async -> Bool {
        guard let a = MTBizPhone.attestation() else { return false }
        return await run {
            guard var j = self.local.joining[org], j.move == true, let pub = self.keysQ()?.pub else { return false }
            j.mine = member
            self.local.joining[org] = j
            self.saveLocalQ()
            self.outbox.append((MTBizWord(t: "d", o: org, m: member, k: j.secret, a: a.montanaHexString, p: pub.montanaHexString), j.pipe))
            MontanaP2PTrace.mark("biz_move", "asked org=\(String(org.prefix(8)))")
            return true
        }
    }
    /// A NEW PHONE ASKS (the administrator's side): the secret is one this phone minted and the new phone asked with it on this
    /// very pipe; the confirmation is the service's (the core proves its signature) and names the key the word carries. Only
    /// then the request waits on the organisation's page for one touch.
    private func movingQ(_ w: MTBizWord, from pipe: String) -> Bool {
        guard let k = w.k, let minted = local.minted[k], minted.org == w.o, minted.pipe == pipe,
              let v = drawn[w.o], Self.boss(v.me.role), let member = w.m, let t = v.member(member), t.status != "removed",
              let p = w.p, p.count == MTBizCore.keySize * 2, let pub = Data(montanaHex: p),
              let a = w.a, a.count <= 8192, let attest = Data(montanaHex: a),
              let open = MTBizCore.attestOpen(attest), open.subject == MTBizPhone.subject(pub) else {
            MontanaP2PTrace.mark("biz_refused", "move unproven org=\(String(w.o.prefix(8)))")
            return false
        }
        let m = MTBizLocal.Move(org: w.o, member: member, key: p, e164: open.e164, pipe: pipe, at: Date().timeIntervalSince1970, secret: k)
        if let had = local.moves[m.id], had.key == m.key, had.pipe == m.pipe { return true }   // the same request again writes nothing
        local.moves[m.id] = m
        saveLocalQ()
        MontanaP2PTrace.mark("biz_move", "request org=\(String(w.o.prefix(8)))")
        return true
    }
    /// ONE TOUCH: the Rekey record moves the place to the new key, and the road to the member is the new phone's pipe from now
    /// on -- the roster and the member's own files go there at once. Refused, the old road stays.
    @MainActor
    func confirmMove(_ m: MTBizLocal.Move) async -> Bool {
        await run {
            let before = self.local.routes[m.member]
            self.local.routes[m.member] = m.pipe
            self.held.insert(m.pipe)
            self.local.sent[m.org]?[m.member] = nil
            guard self.writeQ(m.org, MTBizCommand.rekey(member: m.member, key: m.key)) != nil else {
                self.local.routes[m.member] = before
                self.saveLocalQ()
                return false
            }
            self.local.moves[m.id] = nil
            self.saveLocalQ()
            // The invitation the move came through is spent: nobody else walks in by it (the core names it from its secret).
            if let s = m.secret { self.writeQ(m.org, ["kind": "revoke", "secret": s]) }
            MontanaP2PTrace.mark("biz_move", "moved org=\(String(m.org.prefix(8)))")
            return true
        }
    }
    @MainActor
    func declineMove(_ m: MTBizLocal.Move) {
        send {
            self.local.moves[m.id] = nil
            self.saveLocalQ()
        }
    }
    /// The place is this phone's (the new phone's side): the move ends, the number is bound to the new key (PhoneBind), and
    /// the administrators hear it.
    private func movedQ(_ org: String) {
        local.joining[org] = nil
        saveLocalQ()
        bindPhoneQ(org, nil)
        spreadQ(org)
        MontanaP2PTrace.mark("biz_move", "place here org=\(String(org.prefix(8)))")
    }
    /// The first day of an organisation was shown.
    @MainActor
    func greeted(_ org: String) {
        send {
            self.local.firstDay.removeAll { $0 == org }
            self.saveLocalQ()
        }
    }

    /// A member's road: the pipe a word of theirs came by or their card opened (already the conversation's root); a road just
    /// bound is held -- it has just spoken.
    private func bindQ(_ member: String, _ pipe: String) {
        local.routes[member] = pipe
        held.insert(pipe)
        saveLocalQ()
    }
    /// The pipe this phone last bound to a member, held or not: whom a pipe speaks for, even while it is out of reach.
    func route(of member: String) -> String? { roads[member] }
    /// The pipe this phone reaches a member by, when it holds one (the main thread's question).
    @MainActor
    func pipe(of member: String) -> String? {
        guard let p = roads[member], MontanaConv.holds(p) else { return nil }
        return p
    }
    /// The same question on the queue: the roads it holds, against the pipes the main thread found held.
    private func pipeQ(of member: String) -> String? {
        guard let p = local.routes[member], held.contains(p) else { return nil }
        return p
    }

    /// WHAT EACH MEMBER MAY HOLD, CARRIED ON: the roster to every member this phone reaches; of the closed stream (H, S and C
    /// together), an administrator carries the whole to administrators, and everyone else gets the lanes that are theirs
    /// (mt_biz_slice): their own lane of H from an administrator (an employee carries their own to the administrators), every
    /// order whose path they stand on (orders[].people), every chat they hear (chats[].people). One word (BZ:), one road. A
    /// word the member already got from here is not carried twice.
    private func spreadQ(_ org: String) {
        guard let v = drawn[org], let c = chains[org] else { return }
        let me = v.me.member
        let boss = Self.boss(v.me.role)
        let ownR = Self.headsText(c.r), ownH = Self.headsText(c.h)
        var wrote = false
        for them in v.members where them.member != me && them.status != "removed" {
            guard let pipe = pipeQ(of: them.member) else { continue }
            var files = Data()
            if boss && Self.boss(them.role) {
                files = c.h
            } else {
                for lane in Self.lanes(for: them, in: v, boss: boss) {
                    if let l = Data(montanaHex: lane), let s = MTBizCore.slice(c.h, lane: l) { files.append(s) }
                }
            }
            // BY THEIR HEADS (contract 1.1): the slice first, then only what they lack of it; heads not known -- the whole.
            var roster = c.r
            if let told = local.heads[org]?[them.member] {
                if let lacked = Self.lacked(c.r, by: told.r) { roster = lacked }
                if let lacked = Self.lacked(files, by: told.h) { files = lacked }
                if roster.isEmpty, files.isEmpty { continue }   // they hold all of it
            }
            let r = roster.isEmpty ? nil : roster.base64EncodedString()
            let h = files.isEmpty ? nil : files.base64EncodedString()
            let digest = MTBizPlace.hash(Data(((r ?? "") + ":" + (h ?? "")).utf8))
            if local.sent[org]?[them.member] == digest { continue }
            outbox.append((MTBizWord(t: "s", o: org, m: me, r: r, h: h, rh: ownR, hh: ownH), pipe))
            local.sent[org, default: [:]][them.member] = digest
            wrote = true
        }
        if wrote { saveLocalQ() }
        meetUnreachedQ(org)
        chores.append { [weak self] in self?.tend(org) }   // the chats this phone opened follow the roster
    }
    /// The owner and the administrators: they hold the whole of the files and pay.
    static func boss(_ role: String) -> Bool { role == MTBizRole.owner.word || role == MTBizRole.admin.word }
    /// THE VOICES OF A CHAT (chat.rs): its author and the administrators -- who switch its following and who see it.
    static func speaks(for c: MTBizView.Chat, in v: MTBizView) -> Bool { c.author == v.me.member || boss(v.me.role) }
    /// The organisations' chats that follow this person: followed, heard, and spoken for by others.
    @MainActor
    func followedChats() -> [(org: String, chat: MTBizView.Chat)] {
        var out: [(org: String, chat: MTBizView.Chat)] = []
        for (org, v) in views {
            for c in v.chatList where c.track == true && c.people.contains(v.me.member) && !Self.speaks(for: c, in: v) { out.append((org, c)) }
        }
        return out
    }
    /// The followed chats this phone watches: it speaks for them.
    @MainActor
    func watchedChats() -> Set<String> {
        Set(views.values.flatMap { v in v.chatList.filter { $0.track == true && Self.speaks(for: $0, in: v) }.map(\.id) })
    }
    /// MY PLACE, TOLD (MTBizTrack): one word to each voice of each chat that follows me, by the road this phone holds to them.
    @MainActor
    func tell(la: Double, lo: Double, ac: Double, at: UInt64, auto: Bool = false, only: Set<String>? = nil) {
        for (org, c) in followedChats() where only?.contains(c.id) ?? true {
            guard let v = views[org] else { continue }
            let voices = Set(v.members.filter { Self.boss($0.role) && $0.status != "removed" }.map(\.member) + [c.author])
            for voice in voices where voice != v.me.member {
                guard let p = pipe(of: voice) else { continue }
                carry(MTBizWord(t: "g", o: org, m: v.me.member, c: c.id, la: la, lo: lo, ac: ac, at: at, au: auto ? true : nil), to: p)
            }
        }
    }
    /// The closed lanes one member may hold, of those this phone carries (the contract v1.1: the view's people of each lane).
    static func lanes(for them: MTBizView.Member, in v: MTBizView, boss: Bool) -> [String] {
        var out: [String] = []
        if boss { out.append(them.member) } else if Self.boss(them.role) { out.append(v.me.member) }
        out += v.orderList.filter { $0.people.contains(them.member) }.map(\.id)
        out += v.chatList.filter { $0.people.contains(them.member) }.map(\.id)
        // The lanes of shifts the member holds (9.1): their own, and their department's when they manage it; one lane once.
        for lane in v.shiftList.filter({ $0.people.contains(them.member) }).map(\.lane) where !out.contains(lane) { out.append(lane) }
        return out
    }

    /// THE MEMBERS THIS PHONE HAS NO ROAD TO ARE MET BY THEIR CARDS, the Messenger's own first contact: an administrator meets
    /// every member (the pays and their files go to each), a member meets the owner (the receipts and the shop's coins go
    /// there) and everyone they share a chat with (Sh.2: every phone writes its own letters to every listener). Once an hour at
    /// most for each. The meeting itself is the main thread's; its road comes back to the queue.
    private func meetUnreachedQ(_ org: String) {
        guard let v = drawn[org] else { return }
        let boss = Self.boss(v.me.role)
        let now = Date().timeIntervalSince1970
        var toMeet: [(member: String, card: String)] = []
        for t in v.members where t.active && t.member != v.me.member && !t.card.isEmpty && pipeQ(of: t.member) == nil {
            let shares = v.chatList.contains { $0.people.contains(v.me.member) && $0.people.contains(t.member) }
            guard boss || t.role == MTBizRole.owner.word || shares else { continue }
            guard 3600 < now - (local.met[t.member] ?? 0) else { continue }
            local.met[t.member] = now
            toMeet.append((t.member, t.card))
        }
        guard !toMeet.isEmpty else { return }
        saveLocalQ()
        chores.append { [weak self] in
            for one in toMeet {
                Task { @MainActor in
                    let met = await MontanaMeeting.meet(one.card)
                    guard case .opened(let pipe) = met, let self else { return }
                    let root = MTSamePair.root(pipe)
                    self.send {
                        self.bindQ(one.member, root)
                        self.spreadQ(org)
                    }
                }
            }
        }
    }

    /// AN ORDER (6.3): its key is drawn here (32 fresh bytes, the lane of its TimeChain), the record written, the people of its
    /// path told. Nil -- refused, nothing written.
    @MainActor
    func order(_ org: String, from: String, to: String, lines: [MTBizView.Line], voice: String?, text: String) async -> String? {
        guard let key = MTBizCore.random(32)?.montanaHexString else { return nil }
        return await write(org, MTBizCommand.order(key, from: from, to: to, lines: lines, voice: voice, text: text)) == nil ? nil : key
    }

    // -- measures ------------------------------------------------------------------------------

    static func isHex(_ s: String, _ n: Int) -> Bool { s.count == n && s.allSatisfy { $0.isHexDigit && !$0.isUppercase } }
    /// A department's or an item's new name: sixteen fresh bytes.
    static func freshTag() -> String? { MTBizCore.random(16)?.montanaHexString }

    /// A record written by the queue for one who does not wait for its answer (a letter's link in an organisation's chat).
    @MainActor
    func post(_ org: String, _ command: [String: Any]) {
        send { self.writeQ(org, command) }
    }
}
