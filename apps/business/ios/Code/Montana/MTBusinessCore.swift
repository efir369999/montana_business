import Foundation
import MontanaBindings

// ════════════════════════════════════════════════════════════
// MONTANA BUSINESS: THE DOORS OF THE CORE (the crate mt-business, the checklist's contract v1)
// ════════════════════════════════════════════════════════════
// An organisation is a TimeChain of its own: signed records linked by hashes along lanes -- R, the roster every member
// holds, and H, the people's files, whose lane of one employee reaches only that employee and the administrators. Every
// rule of it (who may write what, the fold, the salary due, the order of the records) is written once, in Rust; this file
// carries bytes to the doors and reads their answer. Android calls the same crate through JNI (MtBusiness): the two faces
// of the app cannot disagree about an organisation, because neither of them decides anything about it.

/// The roles of the contract, by the number a record carries.
enum MTBizRole: Int, CaseIterable, Identifiable {
    case owner = 0, admin = 1, manager = 2, employee = 3
    var id: Int { rawValue }
    /// The word the view names a role by (mt_biz_view).
    var word: String {
        switch self {
        case .owner: return "owner"        // NOT-UI: the view's token
        case .admin: return "admin"        // NOT-UI: the view's token
        case .manager: return "manager"    // NOT-UI: the view's token
        case .employee: return "employee"  // NOT-UI: the view's token
        }
    }
    init?(word: String?) {
        guard let w = word, let r = MTBizRole.allCases.first(where: { $0.word == w }) else { return nil }
        self = r
    }
    var title: String {
        switch self {
        case .owner: return String(localized: "Owner", bundle: MTLanguage.bundle)
        case .admin: return String(localized: "Administrator", bundle: MTLanguage.bundle)
        case .manager: return String(localized: "Manager", bundle: MTLanguage.bundle)
        case .employee: return String(localized: "Employee", bundle: MTLanguage.bundle)
        }
    }
}

/// A node's kind, by the number a Node record carries (the core's NODE_*), and the word the view names it by.
enum MTBizNodeKind: Int, CaseIterable, Identifiable {
    case supplier = 1, warehouse = 2, hub = 3, carrier = 4, shop = 5
    var id: Int { rawValue }
    var word: String {
        switch self {
        case .supplier: return "supplier"    // NOT-UI: the view's token
        case .warehouse: return "warehouse"  // NOT-UI: the view's token
        case .hub: return "hub"              // NOT-UI: the view's token
        case .carrier: return "carrier"      // NOT-UI: the view's token
        case .shop: return "shop"            // NOT-UI: the view's token
        }
    }
    init?(word: String) {
        guard let k = MTBizNodeKind.allCases.first(where: { $0.word == word }) else { return nil }
        self = k
    }
    var title: String {
        switch self {
        case .supplier: return String(localized: "Supplier", bundle: MTLanguage.bundle)
        case .warehouse: return String(localized: "Warehouse", bundle: MTLanguage.bundle)
        case .hub: return String(localized: "Hub", bundle: MTLanguage.bundle)
        case .carrier: return String(localized: "Carrier", bundle: MTLanguage.bundle)
        case .shop: return String(localized: "Store", bundle: MTLanguage.bundle)
        }
    }
    var glyph: String {
        switch self {
        case .supplier: return "shippingbox.fill"
        case .warehouse: return "archivebox.fill"
        case .hub: return "arrow.triangle.branch"
        case .carrier: return "truck.box.fill"
        case .shop: return "storefront.fill"
        }
    }
}

/// What the core shows of an organisation to one viewer (mt_biz_view): every field as the core writes it.
struct MTBizView: Decodable, Identifiable {
    struct Org: Decodable { var id: String; var name: String; var created_ms: UInt64 }
    struct Me: Decodable { var member: String; var role: String; var can: [String] }
    struct Member: Decodable, Identifiable {
        var member: String
        var name: String
        var role: String
        var dept: String?
        var title: String
        var card: String
        var status: String
        var joined_ms: UInt64
        var phone_confirmed: Bool
        var phone_mismatch: Bool?
        /// The number of the member's confirmed PhoneBind, where the viewer may see it (the member and the administrators).
        var phone: String?
        var id: String { member }
        var active: Bool { status == "active" }   // NOT-UI: the view's token
    }
    struct Dept: Decodable, Identifiable { var id: String; var name: String }
    struct Invite: Decodable, Identifiable { var id: String; var role: String; var dept: String?; var expires_ms: UInt64; var state: String }
    struct Rejected: Decodable { var record: String; var reason: String }
    // THE SUPPLY CHAIN AND THE CHATS (contract v1.1): chain S, an order's lane; chain C, a chat's lane. A core without them
    // writes none of these fields, and the view still opens: each is optional, read through its list below.
    struct Line: Decodable, Hashable { var item: String; var size: String; var qty: UInt64; var coins: UInt64 }
    struct Item: Decodable, Identifiable { var id: String; var title: String; var sizes: String; var unit: String; var active: Bool }
    struct Node: Decodable, Identifiable {
        var id: String; var kind: String; var name: String; var place: String; var active: Bool; var mine: Bool; var staff: [String]
    }
    struct Place: Decodable, Identifiable {
        var id: String; var lines: [Line]; var packed_at: String; var holder: String?; var transit_from: String?; var transit_to: String?
        var condition: UInt64; var note: String; var photo: String?
    }
    struct Step: Decodable, Identifiable {
        var record: String; var kind: String; var at_ms: UInt64; var author: String; var node: String?; var place: String?
        var detail: UInt64; var lines: [Line]; var note: String
        var id: String { record }
    }
    struct Order: Decodable, Identifiable {
        var id: String; var author: String; var at_ms: UInt64; var from: String; var to: String; var source: String; var voice: String?
        var text: String; var state: String; var lines: [Line]; var places: [Place]; var steps: [Step]; var people: [String]
        // THE TIME ECONOMY (K.5), the core's own measure from the order's steps: its time on the way, its longest stand at one
        // node, the stand going on now. A core without them leaves them nil.
        var way_ms: UInt64?; var longest_stand: Stand?; var standing: Standing?
        /// What of the order no place holds yet (contract 1.2), the core's own count: its lines, in their order, less what its
        /// places were packed with. A core before 1.2 leaves it nil.
        var unpacked: [Remaining]?
    }
    /// One line still to pack: the item, its size, how many.
    struct Remaining: Decodable, Hashable { var item: String; var size: String; var qty: UInt64 }
    /// A stand of an order's goods at one node: where, and how long (at the supplier from the order to its first packing; a
    /// place at a node from its packing or acceptance there to its handing over). The road is not a stand.
    struct Stand: Decodable { var node: String; var ms: UInt64 }
    /// The stand going on now: where, since when, and how long when the core drew the view.
    struct Standing: Decodable { var node: String; var since_ms: UInt64; var ms: UInt64 }
    /// One person's time on closed shifts begun in the current week and month (period.rs, the salary's UTC boundaries), and
    /// of it the confirmed -- for the people whose shifts the viewer sees. Seconds, never coins.
    struct Worked: Decodable {
        var member: String; var week_s: UInt64; var week_confirmed_s: UInt64; var month_s: UInt64; var month_confirmed_s: UInt64
    }
    struct Stock: Decodable, Identifiable {
        var node: String; var item: String; var size: String; var on_hand: UInt64; var counter: UInt64; var sold: UInt64
        var id: String { node + ":" + item + ":" + size }
    }
    /// One record of the journal (7.4): what the core shows of the last records the viewer may see.
    struct Entry: Decodable, Identifiable {
        var record: String; var chain: String; var kind: String; var at_ms: UInt64; var author: String; var lane: String; var outcome: String
        var id: String { record }
    }
    /// A chat of chain C (contract 1.2): who opened it and who hears it. Its letters are drawn by the group's own feed; the chain
    /// keeps each letter's link, and the view does not carry them.
    struct Chat: Decodable, Identifiable {
        var id: String; var kind: String; var name: String; var dept: String?; var group: String?; var author: String
        var opened_ms: UInt64; var people: [String]
        /// Its people are followed on the map (Track, chat.rs): absent from a core older than the switch.
        var track: Bool?
        /// The window its followed people send their place in, seconds; 0 -- none (TrackWindow, chat.rs); absent from an older core.
        var track_window_s: UInt64?
    }
    /// A shift (9.1): opened and closed by its member, confirmed by somebody else; its lane is the member's own lane of shifts.
    struct Shift: Decodable, Identifiable {
        var member: String; var lane: String; var node: String?; var open_record: String; var open_ms: UInt64
        var close_record: String?; var close_ms: UInt64?; var seconds: UInt64?; var note: String; var confirmed_by: String?
        var people: [String]
        var id: String { open_record }
        var open: Bool { close_record == nil }
    }

    var org: Org?
    var me: Me
    var members: [Member]
    var depts: [Dept]
    var invites: [Invite]
    var rejected: [Rejected]
    var waiting: Int
    var head: String
    var items: [Item]?
    var nodes: [Node]?
    var orders: [Order]?
    var stock: [Stock]?
    var chats: [Chat]?
    var journal: [Entry]?
    var shifts: [Shift]?
    var time: [Worked]?
    var shiftList: [Shift] { shifts ?? [] }
    var timeList: [Worked] { time ?? [] }
    var itemList: [Item] { items ?? [] }
    var nodeList: [Node] { nodes ?? [] }
    var orderList: [Order] { orders ?? [] }
    var stockList: [Stock] { stock ?? [] }
    var chatList: [Chat] { chats ?? [] }

    var id: String { org?.id ?? head }
    var role: MTBizRole? { MTBizRole(word: me.role) }
    func may(_ kind: String) -> Bool { me.can.contains(kind) }
    func member(_ id: String) -> Member? { members.first { $0.member == id } }
    func worked(_ member: String) -> Worked? { timeList.first { $0.member == member } }
    func dept(_ id: String?) -> Dept? { id.flatMap { d in depts.first { $0.id == d } } }
    var owner: Member? { members.first { $0.role == MTBizRole.owner.word && $0.active } }
}

/// WHOM THE VIEWER MAY TOUCH (point 0 of the constitution, «no dead buttons»): the core's list (me.can) says what this role
/// may write at all, and the fold's rule (fold.rs, chat.rs) asks about the target as well -- an administrator does not place
/// another administrator, a manager acts for their own department alone. A screen draws an act only where both agree; where
/// they do not and the reason can be said, the reason stands in the button's place.
extension MTBizView {
    /// The viewer's own department while it stands open: an archived one takes nothing (the core's unknown_dept), and the
    /// view names only the open ones.
    var myDept: String? { member(me.member)?.dept.flatMap { dept($0)?.id } }
    /// A manager invites employees, into their own department alone (fold.rs Invite); an invitation's number is the
    /// administrators' (invite_phone).
    var invitesIntoOwnDept: Bool { role == .manager }
    var mayInvite: Bool { may("invite") && (role != .manager || myDept != nil) }

    /// Assign (fold.rs): the owner places anyone; an administrator themselves and those below; a manager the employees of
    /// their own department, within it.
    func mayAssign(_ m: Member) -> Bool {
        guard may("assign"), m.status != "removed", let target = MTBizRole(word: m.role) else { return false }
        switch role {
        case .owner?: return true
        case .admin?: return m.member == me.member || target.rawValue >= MTBizRole.manager.rawValue
        case .manager?: return myDept != nil && target == .employee && m.dept == myDept
        default: return false
        }
    }

    /// Rekey (fold.rs): the owner moves any place to a new key; an administrator their own and those below.
    func mayRekey(_ member: String) -> Bool {
        guard may("rekey"), let m = self.member(member), m.status != "removed", let target = MTBizRole(word: m.role) else { return false }
        switch role {
        case .owner?: return true
        case .admin?: return member == me.member || target.rawValue >= MTBizRole.manager.rawValue
        default: return false
        }
    }

}

enum MTBizCore {
    /// A door's refusal: its code as the core spoke it (MT_ERR_*).
    struct Refusal: Error { let code: Int32 }
    static let keySize = 1952
    static let idSize = 32

    /// Bytes handed to a door: never a null pointer, even for an empty chain -- its length says it is empty.
    private static func held(_ d: Data) -> [UInt8] { d.isEmpty ? [0] : [UInt8](d) }

    /// A door that writes into the caller's buffer: asked with a first guess, and again with the length it named.
    private static func answer(first: Int, _ door: (UnsafeMutablePointer<UInt8>, Int, UnsafeMutablePointer<Int>) -> Int32) -> Result<Data, Refusal> {
        var cap = max(first, 256)
        for _ in 0..<3 {
            var out = [UInt8](repeating: 0, count: cap)
            var len = 0
            let rc = out.withUnsafeMutableBufferPointer { b -> Int32 in
                guard let base = b.baseAddress else { return MT_ERR_NULL_PTR }
                return door(base, b.count, &len)
            }
            if rc == MT_OK, len <= out.count { return .success(Data(out.prefix(len))) }
            if rc == MT_ERR_BUFFER_TOO_SMALL, cap < len { cap = len; continue }
            return .failure(Refusal(code: rc == MT_OK ? MT_ERR_BUFFER_TOO_SMALL : rc))
        }
        return .failure(Refusal(code: MT_ERR_BUFFER_TOO_SMALL))
    }

    /// The next record of this author: its lane, place and previous record come from the chains handed in, its right from
    /// the fold (MT_ERR_BIZ_DENIED when the author may not write it). The command is the contract's JSON (MTBizCommand).
    static func author(seckey: Data, pubkey: Data, roster: Data, hr: Data, command: [String: Any], at ms: UInt64) -> Result<Data, Refusal> {
        guard let js = try? JSONSerialization.data(withJSONObject: command, options: [.sortedKeys]),
              let text = String(data: js, encoding: .utf8) else { return .failure(Refusal(code: MT_ERR_DECODE)) }
        let sk = held(seckey), pk = held(pubkey), r = held(roster), h = held(hr)
        return answer(first: 8192) { out, cap, len in
            text.withCString { cmd in
                sk.withUnsafeBufferPointer { skp in
                    pk.withUnsafeBufferPointer { pkp in
                        r.withUnsafeBufferPointer { rp in
                            h.withUnsafeBufferPointer { hp in
                                mt_biz_author(skp.baseAddress, pkp.baseAddress, rp.baseAddress, roster.count, hp.baseAddress, hr.count,
                                              cmd, ms, out, cap, len)
                            }
                        }
                    }
                }
            }
        }
    }

    /// One chain from two: the union by record id, in the order (at_ms, id). added -- how many of the incoming were new.
    static func merge(_ have: Data, _ incoming: Data) -> Result<(chain: Data, added: Int), Refusal> {
        var added = 0
        let a = held(have), b = held(incoming)
        let r = answer(first: have.count + incoming.count + 64) { out, cap, len in
            a.withUnsafeBufferPointer { ap in
                b.withUnsafeBufferPointer { bp in
                    mt_biz_merge(ap.baseAddress, have.count, bp.baseAddress, incoming.count, out, cap, len, &added)
                }
            }
        }
        return r.map { ($0, added) }
    }

    /// The organisation as one viewer sees it at one moment.
    static func view(roster: Data, hr: Data, viewer: Data, now ms: UInt64) -> MTBizView? {
        let r = held(roster), h = held(hr), v = held(viewer)
        let got = answer(first: 16_384) { out, cap, len in
            r.withUnsafeBufferPointer { rp in
                h.withUnsafeBufferPointer { hp in
                    v.withUnsafeBufferPointer { vp in
                        mt_biz_view(rp.baseAddress, roster.count, hp.baseAddress, hr.count, vp.baseAddress, ms, out, cap, len)
                    }
                }
            }
        }
        guard case .success(let js) = got else { return nil }
        do { return try JSONDecoder().decode(MTBizView.self, from: js) } catch {
            // The diary leaves the phone: it names where the view broke (its keys), never what the view held.
            MontanaP2PTrace.mark("biz_view", "unreadable at=" + Self.readPath(error))
            return nil
        }
    }

    /// Where a view failed to read, by its keys and indices alone: a decoding error's own words may quote what it read.
    static func readPath(_ error: Error) -> String {
        let path: [CodingKey]
        switch error as? DecodingError {
        case .typeMismatch(_, let c)?, .valueNotFound(_, let c)?, .dataCorrupted(let c)?: path = c.codingPath
        case .keyNotFound(let k, let c)?: path = c.codingPath + [k]
        default: return "-"
        }
        return String(path.map { $0.intValue.map(String.init) ?? $0.stringValue }.joined(separator: ".").prefix(120))
    }

    /// The records of one lane of H: what is handed to that employee.
    static func slice(_ hr: Data, lane: Data) -> Data? {
        let h = held(hr), l = held(lane)
        let got = answer(first: hr.count + 64) { out, cap, len in
            h.withUnsafeBufferPointer { hp in
                l.withUnsafeBufferPointer { lp in mt_biz_slice(hp.baseAddress, hr.count, lp.baseAddress, out, cap, len) }
            }
        }
        if case .success(let d) = got { return d }
        return nil
    }

    /// THE HEADS OF A STREAM (contract 1.1, core 2f6e012): per lane its highest place and the digest of what is held up to it --
    /// what a phone tells the one it speaks to, so that only what it lacks comes back.
    static func heads(_ stream: Data) -> Data? {
        let s = held(stream)
        let got = answer(first: 4 + 73 * 64) { out, cap, len in
            s.withUnsafeBufferPointer { sp in mt_biz_heads(sp.baseAddress, stream.count, out, cap, len) }
        }
        if case .success(let d) = got { return d }
        return nil
    }
    /// What the holder of `heads` lacks of `stream`: a lane it does not name, whole; of a named lane, what is past its place,
    /// and what is below it too when the digests differ (a sibling of a fork).
    static func after(_ stream: Data, heads: Data) -> Data? {
        let s = held(stream), h = held(heads)
        let got = answer(first: stream.count + 64) { out, cap, len in
            s.withUnsafeBufferPointer { sp in
                h.withUnsafeBufferPointer { hp in mt_biz_after(sp.baseAddress, stream.count, hp.baseAddress, heads.count, out, cap, len) }
            }
        }
        if case .success(let d) = got { return d }
        return nil
    }
    /// A stream kept to one organisation (its genesis id, 32 bytes): nothing of another organisation is merged.
    static func keep(_ stream: Data, org: Data) -> Data? {
        guard org.count == idSize else { return nil }
        let s = held(stream), o = [UInt8](org)
        let got = answer(first: stream.count + 64) { out, cap, len in
            o.withUnsafeBufferPointer { op in
                s.withUnsafeBufferPointer { sp in mt_biz_keep(op.baseAddress, sp.baseAddress, stream.count, out, cap, len) }
            }
        }
        if case .success(let d) = got { return d }
        return nil
    }

    /// What a number's confirmation says, once its signature is proven by the service's key: the number, the key it was
    /// given to, the road and the moment. Nil -- the confirmation is not the service's.
    struct Attest: Decodable { var e164: String; var subject: String; var channel: Int; var at_ms: UInt64 }
    static func attestOpen(_ attest: Data) -> Attest? {
        let a = held(attest)
        let got = answer(first: 512) { out, cap, len in
            a.withUnsafeBufferPointer { ap in mt_biz_attest_open(ap.baseAddress, attest.count, out, cap, len) }
        }
        guard case .success(let js) = got else { return nil }
        return try? JSONDecoder().decode(Attest.self, from: js)
    }

    /// What an address's confirmation says, once the same service key proves it: the address, the key it was given to, the
    /// moment (MTBE, mt-business email.rs). Nil -- the confirmation is not the service's.
    struct EmailAttest: Decodable { var email: String; var subject: String; var at_ms: UInt64 }
    static func emailOpen(_ attest: Data) -> EmailAttest? {
        let a = held(attest)
        let got = answer(first: 512) { out, cap, len in
            a.withUnsafeBufferPointer { ap in mt_biz_email_open(ap.baseAddress, attest.count, out, cap, len) }
        }
        guard case .success(let js) = got else { return nil }
        return try? JSONDecoder().decode(EmailAttest.self, from: js)
    }

    /// The member's lasting name: derived from the key they joined with, kept through a new key.
    static func memberId(_ pubkey: Data) -> String? {
        guard pubkey.count == keySize else { return nil }
        let k = [UInt8](pubkey)
        var out = [UInt8](repeating: 0, count: idSize)
        let rc = k.withUnsafeBufferPointer { p in mt_biz_member_id(p.baseAddress, &out) }
        return rc == MT_OK ? Data(out).montanaHexString : nil
    }

    /// An invitation's id from its secret, as the view names it (mt_biz_invite_id): the core's label is never repeated here.
    static func inviteId(_ secret: Data) -> String? {
        guard secret.count == idSize else { return nil }
        let k = [UInt8](secret)
        var out = [UInt8](repeating: 0, count: idSize)
        let rc = k.withUnsafeBufferPointer { p in mt_biz_invite_id(p.baseAddress, &out) }
        return rc == MT_OK ? Data(out).montanaHexString : nil
    }

    /// Fresh bytes from the core's own source (an invitation's secret, an item's or a department's name).
    static func random(_ n: Int) -> Data? {
        var b = [UInt8](repeating: 0, count: n)
        return mt_random_fast(&b, n) == 0 ? Data(b) : nil
    }
}

/// THE COMMANDS OF THE CONTRACT, each written once: the kind and the fields as the core's records name them, bytes in hex.
enum MTBizCommand {
    static func genesis(name: String, ownerName: String, card: String) -> [String: Any] {
        ["kind": "genesis", "name": name, "owner_name": ownerName, "owner_card": card]
    }
    static func invite(secret: String, role: MTBizRole, dept: String?, expires ms: UInt64, phoneBound: Bool) -> [String: Any] {
        ["kind": "invite", "secret": secret, "role": role.rawValue, "dept": dept ?? noDept, "expires_ms": ms, "phone_bound": phoneBound]
    }
    static func join(secret: String, name: String, card: String) -> [String: Any] {
        ["kind": "join", "secret": secret, "name": name, "card": card]
    }
    static func role(member: String, role: MTBizRole) -> [String: Any] { ["kind": "role", "member": member, "role": role.rawValue] }
    static func dept(_ id: String, name: String, archived: Bool) -> [String: Any] {
        ["kind": "dept", "dept": id, "name": name, "archived": archived]
    }
    static func assign(member: String, dept: String?, title: String) -> [String: Any] {
        ["kind": "assign", "member": member, "dept": dept ?? noDept, "title": title]
    }
    static func remove(member: String) -> [String: Any] { ["kind": "remove", "member": member] }
    /// A member's place moved to a new key (a new phone): the key in hex, 1952 bytes.
    static func rekey(member: String, key: String) -> [String: Any] { ["kind": "rekey", "member": member, "key": key] }
    static func shiftOpen() -> [String: Any] { ["kind": "shift_open"] }
    static func shiftClose(note: String) -> [String: Any] { ["kind": "shift_close", "note": note] }
    static func shiftConfirm(_ close: String) -> [String: Any] { ["kind": "shift_confirm", "record": close] }
    static func revoke(invite: String) -> [String: Any] { ["kind": "revoke", "invite_id": invite] }
    static func profile(name: String, card: String) -> [String: Any] { ["kind": "profile", "name": name, "card": card] }
    static func phoneBind(attest: Data) -> [String: Any] { ["kind": "phone_bind", "attest": attest.montanaHexString] }
    static func invitePhone(invite: String, e164: String) -> [String: Any] { ["kind": "invite_phone", "invite_id": invite, "e164": e164] }
    /// A chat follows its people on the map, or stops (its author or an administrator, chat.rs Track).
    static func track(chat: String, on: Bool) -> [String: Any] { ["kind": "track", "chat": chat, "on": on] }
    /// The window a followed chat's people send their place in, or none (0): the same voices (chat.rs TrackWindow).
    static func trackWindow(chat: String, seconds: UInt64) -> [String: Any] { ["kind": "track_window", "chat": chat, "window_s": seconds] }
    // THE SUPPLY CHAIN (contract v1.1): the catalogue is the roster's (R), an order and its steps its own lane of S, named by
    // the order's 32-byte key; places, nodes and products are 16-byte tags.
    static func item(_ id: String, title: String, sizes: String, unit: String, active: Bool) -> [String: Any] {
        ["kind": "item", "item": id, "title": title, "sizes": sizes, "unit": unit, "active": active]
    }
    static func node(_ id: String, kind: MTBizNodeKind, name: String, place: String, active: Bool) -> [String: Any] {
        ["kind": "node", "node": id, "node_kind": kind.rawValue, "name": name, "place": place, "active": active]
    }
    static func nodeStaff(node: String, member: String, on: Bool) -> [String: Any] {
        ["kind": "node_staff", "node": node, "member": member, "on": on]
    }
    static func lines(_ ls: [MTBizView.Line]) -> [[String: Any]] {
        ls.map { ["item": $0.item, "size": $0.size, "qty": $0.qty, "coins": $0.coins] }
    }
    /// An order: by hand (source 1, the words as written) or by voice (source 2, the voice note's hash and its transcription).
    static func order(_ key: String, from: String, to: String, lines ls: [MTBizView.Line], voice: String?, text: String) -> [String: Any] {
        var c: [String: Any] = ["kind": "order", "order": key, "from": from, "to": to, "lines": lines(ls), "text": text,
                                "source": voice == nil ? textSource : voiceSource]
        if let voice { c["voice"] = voice }
        return c
    }
    static func confirm(order: String, node: String) -> [String: Any] { ["kind": "confirm", "order": order, "node": node] }
    static func pack(order: String, place: String, node: String, lines ls: [MTBizView.Line]) -> [String: Any] {
        ["kind": "pack", "order": order, "place": place, "node": node, "lines": lines(ls)]
    }
    static func handoff(order: String, place: String, from: String, to: String) -> [String: Any] {
        ["kind": "handoff", "order": order, "place": place, "from": from, "to": to]
    }
    static func accept(order: String, place: String, node: String, condition: Int, note: String, photo: String?) -> [String: Any] {
        var c: [String: Any] = ["kind": "accept", "order": order, "place": place, "node": node, "condition": condition, "note": note]
        if let photo { c["photo"] = photo }
        return c
    }
    static func scan(order: String, place: String, node: String) -> [String: Any] {
        ["kind": "scan", "order": order, "place": place, "node": node]
    }
    static func shelf(order: String, node: String, lines ls: [MTBizView.Line]) -> [String: Any] {
        ["kind": "shelf", "order": order, "node": node, "lines": lines(ls)]
    }
    static func sale(order: String, node: String, lines ls: [MTBizView.Line]) -> [String: Any] {
        ["kind": "sale", "order": order, "node": node, "lines": lines(ls)]
    }
    static func issue(order: String, place: String?, kind: Int, note: String) -> [String: Any] {
        var c: [String: Any] = ["kind": "issue", "order": order, "issue_kind": kind, "note": note]
        if let place { c["place"] = place }
        return c
    }
    static func cancel(order: String, note: String) -> [String: Any] { ["kind": "cancel", "order": order, "note": note] }
    /// An order's sources (the core's SOURCE_TEXT, SOURCE_VOICE).
    static let textSource = 1
    static let voiceSource = 2
    /// A department of none: sixteen zero bytes.
    static let noDept = String(repeating: "0", count: 32)
}
