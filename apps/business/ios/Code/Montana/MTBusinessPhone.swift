import Foundation
import AuthenticationServices
import Contacts
import CryptoKit
import SwiftUI
import UIKit
import UniformTypeIdentifiers

// ════════════════════════════════════════════════════════════
// MONTANA BUSINESS: SIGN-IN BY THE PHONE NUMBER
// ════════════════════════════════════════════════════════════
// The author's words 06.10.2026: «a native sign-in, the number confirmed through a service or a bot, to enter the account by
// the phone number; the app has its own site and everything of its own». The person's identity is born on this phone at once
// (mt_generate_mnemonic, the one door of a birth); the Business's one service on the app's own site confirms that this number
// belongs to this key and signs the confirmation with its ML-DSA-65 key, which the core holds pinned (mt_biz_attest_open). The
// roads of the confirmation -- how many, their names and their glyphs -- come from the service itself (health): no outer
// service is named anywhere in the app, and without a living service there is no road on the screen, never a dead button.

/// The service of the Business on the app's own site: what roads it keeps, the start of a confirmation, the wait for it.
enum MTBizService {
    /// One road of a confirmation as the service names it: its number (1, 2), its title in the person's language, its glyph.
    struct Road: Decodable, Identifiable, Equatable {
        var channel: Int
        var title: String
        var glyph: String
        var id: Int { channel }
        private enum Keys: String, CodingKey { case channel, title, glyph }
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: Keys.self)
            channel = try c.decode(Int.self, forKey: .channel)
            // The title comes as one word, or as a word for each language the service speaks.
            if let one = try? c.decode(String.self, forKey: .title) {
                title = one
            } else {
                let many = try c.decode([String: String].self, forKey: .title)
                title = many[MTLanguage.code] ?? many["en"] ?? many.values.first ?? ""
            }
            let g = (try? c.decode(String.self, forKey: .glyph)) ?? ""
            glyph = UIImage(systemName: g) == nil ? "phone.fill" : g
        }
    }
    /// The roads, and beside them the account's door (the author's choice 06.10.2026 16:3x MSK: «the login page of the account»):
    /// the bot's own sign-in, named apart so that a build reading only the roads never offers it.
    struct Health: Decodable {
        var ok: Bool; var paths: [Road]; var signIn: Road?; var mail: Road?
        private enum CodingKeys: String, CodingKey { case ok, paths, signIn = "account", mail = "email" }
    }
    static let signInChannel = 3
    static let mailChannel = 4
    /// The start's answer: the nonce the outer link carries (no secret there), the claim this phone alone holds and waits with,
    /// the link, the term.
    struct Started: Decodable { var nonce: String; var claim: String; var link: String; var app_link: String?; var expires_ms: UInt64 }
    /// The person's card as the bot's profile names it (the author's word 06.10.2026 16:0x MSK: «sign-in by the bot with the
    /// number, from where the whole card of the user is drawn»), given with the confirmation to this claim alone.
    struct Card { var name: String; var bio: String; var photo: Data?; var claimedName: String = "" }
    enum Wait { case confirmed(Data, Card?), again, expired, failed, mismatch, unverified }

    /// THE DOORS OF THE SERVICE (the author's word 06.10.2026 19:58 MSK: «why does the sign-in button work unstably»; T1's diary
    /// 19:55-19:57 showed the phone on a failing mobile road, and the one door it asked never answered): the service itself and
    /// the door that carries to it, as the project names them; the door that answered last is asked first, the others after it.
    private static let doorLock = NSLock()
    private static var lastDoor: String?
    static var doors: [String] {
        doorLock.lock()   // LOCK-OK: one word in memory
        let last = lastDoor
        doorLock.unlock()
        let all = MontanaContour.bizServices
        guard let last, all.contains(last) else { return all }
        return [last] + all.filter { $0 != last }
    }
    private static func answered(_ host: String) {
        doorLock.lock()   // LOCK-OK: one word in memory
        lastDoor = host
        doorLock.unlock()
    }
    /// One request through the doors in order: the first door that answers at all (any status) is kept; a silent door gives way
    /// to the next. Every answer and every silence is written to the diary with its time -- a failure is measured, not guessed.
    static func call(_ path: String, method: String = "POST", json: [String: Any]? = nil, query: [URLQueryItem] = [],
                     timeout: TimeInterval = 20) async -> (Data, Int)? {
        let body = json.flatMap { try? JSONSerialization.data(withJSONObject: $0) }
        for host in doors {
            var c = URLComponents(string: "https://" + host + "/b/v1" + path)
            if !query.isEmpty { c?.queryItems = query }
            guard let u = c?.url else { continue }
            var q = URLRequest(url: u, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: timeout)
            q.httpMethod = method
            if let body {
                q.setValue("application/json", forHTTPHeaderField: "Content-Type")
                q.httpBody = body
            }
            let t0 = Date()
            do {
                let (d, r) = try await URLSession.shared.data(for: q)
                let code = (r as? HTTPURLResponse)?.statusCode ?? 0
                answered(host)
                MontanaP2PTrace.mark("biz_svc", "\(path) host=\(host) code=\(code) ms=\(Int(Date().timeIntervalSince(t0) * 1000))")
                return (d, code)
            } catch {
                if Task.isCancelled { return nil }
                MontanaP2PTrace.mark("biz_svc", "\(path) host=\(host) silent=\((error as? URLError)?.code.rawValue ?? -1) ms=\(Int(Date().timeIntervalSince(t0) * 1000))")
            }
        }
        return nil
    }
    /// The service's word on its roads, asked of every door at once: the first door that answers «ok» is the one kept.
    static func health() async -> Health? {
        await withTaskGroup(of: (String, Health?).self) { group in
            for host in MontanaContour.bizServices {
                group.addTask {
                    guard let u = URL(string: "https://" + host + "/b/v1/health") else { return (host, nil) }
                    let q = URLRequest(url: u, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 8)
                    let t0 = Date()
                    let got = try? await URLSession.shared.data(for: q)
                    let code = (got?.1 as? HTTPURLResponse)?.statusCode ?? -1
                    let h = got.flatMap { code == 200 ? try? JSONDecoder().decode(Health.self, from: $0.0) : nil }
                    MontanaP2PTrace.mark("biz_svc", "/health host=\(host) code=\(code) ok=\(h?.ok == true ? 1 : 0) ms=\(Int(Date().timeIntervalSince(t0) * 1000))")
                    return (host, h)
                }
            }
            for await (host, h) in group where h?.ok == true {
                answered(host)
                group.cancelAll()
                return h
            }
            return nil
        }
    }
    /// The roads the service keeps now; none when no door answers.
    static func roads() async -> [Road] {
        guard let h = await health() else { return [] }
        // The account's door first when the service offers it: it is the road the person takes.
        return ((h.signIn.map { [$0] } ?? []) + h.paths).filter { 0 < $0.channel && !$0.title.isEmpty }
    }
    /// The confirmation begins: the number, the key it is for (SHA-256 of the key, hex), the road.
    static func start(e164: String, subject: String, channel: Int) async -> Started? {
        guard let got = await call("/phone/start", json: ["e164": e164, "subject": subject, "channel": channel], timeout: 15),
              got.1 == 200 else { return nil }
        return try? JSONDecoder().decode(Started.self, from: got.0)
    }
    /// One long wait (the service holds it 25 seconds): the confirmation, «not yet», or the term ran out. Only the claim takes
    /// the confirmation away: the nonce alone, as the outer service saw it in the link, is answered «gone».
    static func wait(nonce: String, claim: String) async -> Wait {
        guard let got = await call("/phone/wait", method: "GET", query: [URLQueryItem(name: "nonce", value: nonce), URLQueryItem(name: "claim", value: claim)],
                                   timeout: 35) else { return .failed }
        switch got.1 {
        case 200: return attestation(in: got.0).map { .confirmed($0, card(in: got.0)) } ?? .failed
        case 204: return .again
        case 410: return .expired
        default: return .failed
        }
    }
    /// The account's sign-in came back with its code: the service redeems it with the verifier it alone holds and answers with
    /// the confirmation itself -- the verified number must be the one asked for.
    static func finish(nonce: String, claim: String, code: String) async -> Wait {
        guard let got = await call("/account/finish", json: ["nonce": nonce, "claim": claim, "code": code], timeout: 30) else { return .failed }
        switch got.1 {
        case 200: return attestation(in: got.0).map { .confirmed($0, card(in: got.0)) } ?? .failed
        case 409: return .mismatch
        case 403: return .unverified
        case 410: return .expired
        default: return .failed
        }
    }
    /// The card beside the confirmation: the name (first and last), the bio, the face; a card with nothing in it is none.
    static func card(in d: Data) -> Card? {
        guard let o = try? JSONSerialization.jsonObject(with: d) as? [String: Any], let c = o["card"] as? [String: Any] else { return nil }
        func word(_ k: String) -> String { (c[k] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines) }
        let name = [word("first"), word("last")].filter { !$0.isEmpty }.joined(separator: " ")
        let photo = (c["photo"] as? String).flatMap { Data(base64Encoded: $0) }
        if name.isEmpty && word("bio").isEmpty && photo == nil && word("nick").isEmpty { return nil }
        return Card(name: name, bio: word("bio"), photo: photo, claimedName: word("nick"))
    }
    /// THE ADDRESS'S DOOR (the author's word 06.10.2026 17:5x MSK: «a second sign-in by e-mail ... the third way»): offered
    /// only while the service names it -- no dead door on the login page.
    static func mailDoor() async -> Road? { await health()?.mail }
    struct MailStarted: Decodable { var nonce: String; var claim: String; var expires_ms: UInt64 }
    enum MailStart { case started(MailStarted), refused(String) }
    /// The code is mailed from montana.quest to the address, for the person's own key.
    static func emailStart(_ email: String, subject: String) async -> MailStart {
        let silent = "The confirmation service did not answer. Try again in a minute."
        guard let got = await call("/email/start", json: ["email": email, "subject": subject, "lang": MTLanguage.code]) else { return .refused(silent) }
        switch got.1 {
        case 200: return (try? JSONDecoder().decode(MailStarted.self, from: got.0)).map { .started($0) } ?? .refused(silent)
        case 400: return .refused("This address does not look right.")
        case 429, 503: return .refused("Wait a minute before asking again.")
        case 502: return .refused("The letter could not be sent. Try again in a minute.")
        default: return .refused(silent)
        }
    }
    enum MailFinish { case confirmed(Data), wrong(Int), gone, failed }
    /// The code typed back: the service compares it and answers with the signed confirmation of the address.
    static func emailFinish(nonce: String, claim: String, code: String) async -> MailFinish {
        guard let got = await call("/email/finish", json: ["nonce": nonce, "claim": claim, "code": code]) else { return .failed }
        switch got.1 {
        case 200: return attestation(in: got.0).map { .confirmed($0) } ?? .failed
        case 403:
            let left = (try? JSONSerialization.jsonObject(with: got.0) as? [String: Any])?["left"] as? Int ?? 0
            return .wrong(left)
        case 410: return .gone
        default: return .failed
        }
    }
    /// The person's card into the catalog «number -> person»: only with the confirmation issued last for the number.
    static func dirPut(attest: String, card: [String: String]) async -> Bool {
        await call("/dir/put", json: ["attestation": attest, "card": card])?.1 == 200
    }
    /// THE PERSON LEAVES THE CATALOG (Apple's rule 5.1.1(v): an account made in the app is deleted in the app; the author's word
    /// 06.10.2026 20:4x MSK «finish everything yourself up to Apple's release»): the entry goes with the number's last confirmation.
    /// True when the catalog holds nothing of this confirmation any more (taken now, or already gone).
    static func dirRemove(attest: String) async -> Bool {
        guard let code = await call("/dir/remove", json: ["attestation": attest], timeout: 8)?.1 else { return false }
        return code == 200 || code == 404
    }
    /// The cards of the catalog behind these hashes of numbers (SHA-256 of E.164, at most 500 at once).
    static func dirMatch(_ hashes: [String]) async -> [(String, [String: String])] {
        guard let got = await call("/dir/match", json: ["hashes": hashes]), got.1 == 200,
              let o = try? JSONSerialization.jsonObject(with: got.0) as? [String: Any],
              let found = o["found"] as? [[String: Any]] else { return [] }
        return found.compactMap { row in
            guard let h = row["hash"] as? String, let card = row["card"] as? [String: Any] else { return nil }
            return (h, card.compactMapValues { $0 as? String })
        }
    }
    /// The confirmation's bytes, whichever way the answer carries them: raw, hex, or hex under «attest» in JSON.
    static func attestation(in d: Data) -> Data? {
        if d.prefix(4) == Data("MTBA".utf8) { return d }
        if let o = try? JSONSerialization.jsonObject(with: d) as? [String: Any], let hex = o["attest"] as? String { return Data(montanaHex: hex) }
        return String(data: d, encoding: .utf8).flatMap { Data(montanaHex: $0.trimmingCharacters(in: .whitespacesAndNewlines)) }
    }
}

/// THE NUMBER CONFIRMED ON THIS PHONE: the service's signed confirmation, kept in the person's own folder of the Business; the
/// core reads it (the number, the key, the road, the moment) only once the service's pinned key proves the signature.
enum MTBizPhone {
    static let file = "phone.mtba"
    static func attestation() -> Data? { MTBizPlace.read(file) }
    static func confirmed() -> MTBizCore.Attest? { attestation().flatMap { MTBizCore.attestOpen($0) } }
    /// The key a confirmation is given to: SHA-256 of the person's public key, hex.
    static func subject(_ pub: Data) -> String { MTBizPlace.hash(pub) }
    /// A confirmation is kept only when the core proves it and it names this person's key and the number asked for.
    @discardableResult
    static func keep(_ a: Data, e164: String) -> Bool {
        guard let pub = MontanaSeed.keys()?.pub, let open = MTBizCore.attestOpen(a),
              open.subject == subject(pub), open.e164 == e164 else { return false }
        return MTBizPlace.write(file, a)
    }
}

/// THE ADDRESS CONFIRMED ON THIS PHONE: the service's signed confirmation (MTBE), kept in the person's own folder of the
/// Business beside the number's; the core reads it only once the service's pinned key proves the signature.
enum MTBizEmail {
    static let file = "email.mtbe"
    static func confirmed() -> MTBizCore.EmailAttest? { MTBizPlace.read(file).flatMap { MTBizCore.emailOpen($0) } }
    /// A confirmation is kept only when the core proves it and it names this person's key and the address asked for.
    @discardableResult
    static func keep(_ a: Data, email: String) -> Bool {
        guard let pub = MontanaSeed.keys()?.pub, let open = MTBizCore.emailOpen(a),
              open.subject == MTBizPhone.subject(pub), open.email == email else { return false }
        return MTBizPlace.write(file, a)
    }
    /// The address in the core's one shape (mt-business valid_email): trimmed, lower-cased, printable ASCII, one @, a local
    /// part of 1..64, a dotted domain with no empty label, 254 at most. Nil -- not an address yet.
    static func shaped(_ typed: String) -> String? {
        let s = typed.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let parts = s.split(separator: "@", omittingEmptySubsequences: false)
        guard (3...254).contains(s.count), s.unicodeScalars.allSatisfy({ (33...126).contains($0.value) }), parts.count == 2,
              (1...64).contains(parts[0].count), parts[1].contains("."),
              !parts[1].split(separator: ".", omittingEmptySubsequences: false).contains(where: { $0.isEmpty }) else { return nil }
        return s
    }
}

/// THE CARD COMES INTO THE PROFILE (the author's word 06.10.2026 16:0x MSK): the name, the bio and the face the bot's profile gave
/// fill the person's own profile where it is still empty -- a field the person already filled is theirs and stays. The name and
/// the bio go by the profile's own keys and words to the peers; the face by its one road (MontanaSelfFace).
enum MTBizCard {
    @MainActor static func adopt(_ c: MTBizService.Card?) {
        guard let c else { return }
        let d = UserDefaults.standard
        func blank(_ key: String) -> Bool { (d.string(forKey: key) ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        var took: [String] = []
        let name = String(MTCrown.plain(c.name).prefix(64))   // the profile's own bound and rule: 64 letters, a crown is never taken in
        if !name.isEmpty, blank("userName") { d.set(name, forKey: "userName"); E2E.shared.broadcastName(); took.append("name") }
        if !c.bio.isEmpty, blank("profileBio") { d.set(c.bio, forKey: "profileBio"); E2E.shared.broadcastAbout(); took.append("bio") }
        if let face = c.photo, (d.data(forKey: "avatarData") ?? Data()).isEmpty { MontanaSelfFace.set(face); took.append("face") }
        // THE NICK TOO (the author's word 06.10.2026 18:3x MSK: «the nick too -- assign it from there»): the messenger's username is
        // taken on the plane of names by its one road, when this person holds no name yet.
        if !c.claimedName.isEmpty, MontanaNames.heldName == nil {
            took.append("nick")
            let claimedName = c.claimedName
            Task { let r = await MontanaNamePlane.take(claimedName); MontanaP2PTrace.mark("biz_card", "nick taking=\(r)") }
        }
        MontanaP2PTrace.mark("biz_card", "took=" + (took.isEmpty ? "-" : took.joined(separator: ",")))
    }
}

/// The countries a number is chosen by: the region and its calling code (ITU-T E.164), written once. The name is the system's
/// own, in the person's language; the flag is the region's two letters.
enum MTBizCountries {
    struct Country: Identifiable, Hashable { var region: String; var code: String; var id: String { region } }
    private static let table = "US 1,CA 1,RU 7,KZ 7,EG 20,ZA 27,GR 30,NL 31,BE 32,FR 33,ES 34,HU 36,IT 39,RO 40,CH 41,AT 43,GB 44,DK 45,SE 46,NO 47,PL 48,DE 49,PE 51,MX 52,CU 53,AR 54,BR 55,CL 56,CO 57,VE 58,MY 60,AU 61,ID 62,PH 63,NZ 64,SG 65,TH 66,JP 81,KR 82,VN 84,CN 86,TR 90,IN 91,PK 92,AF 93,LK 94,MM 95,IR 98,MA 212,DZ 213,TN 216,LY 218,SN 221,CI 225,GH 233,NG 234,CM 237,ET 251,KE 254,TZ 255,UG 256,ZW 263,PT 351,LU 352,IE 353,IS 354,AL 355,MT 356,CY 357,FI 358,BG 359,LT 370,LV 371,EE 372,MD 373,AM 374,BY 375,AD 376,MC 377,UA 380,RS 381,ME 382,HR 385,SI 386,BA 387,MK 389,CZ 420,SK 421,LI 423,GT 502,SV 503,HN 504,NI 505,CR 506,PA 507,BO 591,EC 593,PY 595,UY 598,HK 852,MO 853,KH 855,LA 856,BD 880,TW 886,MV 960,LB 961,JO 962,SY 963,IQ 964,KW 965,SA 966,YE 967,OM 968,AE 971,IL 972,BH 973,QA 974,MN 976,NP 977,TJ 992,TM 993,AZ 994,GE 995,KG 996,UZ 998"   // NOT-UI: regions and their calling codes
    static let all: [Country] = table.split(separator: ",").compactMap { row in
        let p = row.split(separator: " ")
        return p.count == 2 ? Country(region: String(p[0]), code: String(p[1])) : nil
    }
    static var initial: Country {
        let r = Locale.current.region?.identifier ?? "US"
        return all.first { $0.region == r } ?? all[0]
    }
    static func name(_ c: Country) -> String { MTLanguage.locale.localizedString(forRegionCode: c.region) ?? c.region }
    static func flag(_ c: Country) -> String {
        String(String.UnicodeScalarView(c.region.unicodeScalars.compactMap { Unicode.Scalar(127_397 + $0.value) }))
    }
    /// The number in the one form the service and the core accept: «+», the country's code and the national digits (a leading
    /// trunk zero dropped, and Russia's and Kazakhstan's trunk 8); a number typed with its own «+» is taken as written.
    static func e164(_ c: Country, _ typed: String) -> String? {
        let digits = typed.filter { $0.isASCII && $0.isNumber }
        var full: String
        if typed.trimmingCharacters(in: .whitespaces).hasPrefix("+") {
            full = digits
        } else {
            var n = digits
            while n.hasPrefix("0") { n.removeFirst() }
            if c.code == "7", n.count == 11, n.hasPrefix("8") { n.removeFirst() }
            full = c.code + n
        }
        guard (6...15).contains(full.count), !full.hasPrefix("0") else { return nil }
        return "+" + full
    }
}

/// THE FLOW OF ONE CONFIRMATION: the roads read from the service, the start for the person's own key, the outer service opened
/// by the link the service hands, and the long wait until the signed confirmation comes or its term ends. The person is never
/// born here: the login page's door of the number makes them first, by the one road of a birth (MontanaOnboardingView.createSeed).
@MainActor
final class MTBizPhoneFlow: ObservableObject {
    @Published var roads: [MTBizService.Road] = []
    @Published var asked = false   // the service was asked at least once: its silence is spoken from here on, never guessed before
    @Published var checking = false   // «Next» is asking the service again before it speaks of silence
    @Published var waiting: MTBizService.Road?
    @Published var refusal: String?
    @Published private(set) var link: URL?      // the bot's link the service handed, opened by the person's own tap
    @Published private(set) var opened = false  // the bot was opened at least once: the page says it waits
    @Published private(set) var signIn = false  // the road taken is the sign-in door: its page is the messenger's own, no link stands
    private var appLink: URL?                     // the door into the messenger's own app, when the service named one this app may open
    private var returnTo: URL?                    // where the sign-in comes back to (its redirect: the app's own scheme)
    private var e164: String?
    private var finished: (() -> Void)?
    private var session: ASWebAuthenticationSession?
    /// The flow whose sign-in stands now: the one entrance of links hands the way back to it (MTBizReturn).
    static weak var current: MTBizPhoneFlow?
    private var started: MTBizService.Started?
    private var task: Task<Void, Never>?
    private var asking: Task<MTBizService.Wait, Never>?

    /// The roads are asked while the door stands: a service silent at the first look (no network yet at the first launch) is
    /// asked again every five seconds, until it names its roads or the door leaves.
    func watch() async {
        while !Task.isCancelled {
            let got = await MTBizService.roads()
            roads = got
            asked = true
            if !got.isEmpty { return }
            try? await Task.sleep(nanoseconds: 5_000_000_000)
        }
    }
    /// THE QUESTION ASKED AGAIN AT THE TAP (T1 19:55 MSK: «Next» on a phone whose one ask had failed showed «unavailable» while
    /// the service stood): three rounds over every door, two seconds apart, before the page speaks of silence.
    func askAgain() async -> [MTBizService.Road] {
        for round in 0..<3 {
            let got = await MTBizService.roads()
            roads = got
            asked = true
            if !got.isEmpty || Task.isCancelled { return got }
            if round < 2 { try? await Task.sleep(nanoseconds: 2_000_000_000) }
        }
        return []
    }
    func cancel() {
        task?.cancel()
        task = nil
        asking?.cancel()
        asking = nil
        waiting = nil
        started = nil
        link = nil
        opened = false
        session?.cancel()
        session = nil
        signIn = false
        appLink = nil
        returnTo = nil
        e164 = nil
        finished = nil
        release()
    }
    /// The person came back from the bot: a wait held across the app's sleep may hang on a connection that died with it, so it
    /// is let go and asked again at once, not after its 35 seconds; the service keeps the answer for the claim until the term.
    func cameBack() {
        asking?.cancel()
    }
    /// THE BOT OPENS BY THE PERSON'S OWN TAP (the author's word 06.10.2026 16:0x MSK: «the page gives the link to our bot with a big
    /// button»), as often as they tap; nothing leaves the app before it.
    func openLink() {
        guard let u = link else { return }
        opened = true
        guard signIn else { UIApplication.shared.open(u); return }
        // THE ACCOUNT'S DOOR OPENS IN THE MESSENGER'S OWN APP, its native confirmation (the author's choice 06.10.2026 16:3x MSK:
        // «the native login page, even with anonymous accounts»); a phone without that app gets the issuer's page in the system's
        // sheet. Either way the way back carries the code to this page.
        if let door = appLink {
            UIApplication.shared.open(door) { ok in Task { @MainActor [weak self] in if !ok { self?.openPage(u) } } }
        } else {
            openPage(u)
        }
    }
    /// THE LINK OPENED IN THE SYSTEM'S BROWSER (the line under the button): the bot's page, or the sign-in's page whose way back is
    /// this app's own scheme -- the one entrance of links hands the code to this flow (MTBizReturn).
    func openInBrowser() {
        guard let u = link else { return }
        opened = true
        UIApplication.shared.open(u)
    }
    /// THE WAY BACK IS THE APP'S OWN SCHEME (06.10.2026 17:2x MSK, measured): the issuer's generated return domain names only the
    /// messenger's own apps in its link file, on the domain and in Apple's CDN alike, so no phone would bring the person back to
    /// this app by it; the redirect registered with the bot is montana-business://tglogin. A code caught by another app on that
    /// scheme is worth nothing: only the service holds the PKCE verifier that redeems it.
    private func openPage(_ u: URL) {
        guard let to = returnTo, let scheme = to.scheme?.lowercased() else { UIApplication.shared.open(u); return }
        let back: (URL?, Error?) -> Void = { url, _ in
            Task { @MainActor [weak self] in
                self?.session = nil
                if let url { self?.cameBackWith(url) }
            }
        }
        let s: ASWebAuthenticationSession
        if scheme == "https" {
            if #available(iOS 17.4, *), let host = to.host {
                s = ASWebAuthenticationSession(url: u, callback: .https(host: host, path: to.path.isEmpty ? "/" : to.path), completionHandler: back)
            } else {
                UIApplication.shared.open(u)   // the universal link brings the way back
                return
            }
        } else {
            s = ASWebAuthenticationSession(url: u, callbackURLScheme: scheme, completionHandler: back)
        }
        s.presentationContextProvider = MTBizSheetAnchor.shared
        s.prefersEphemeralWebBrowserSession = false
        session = s
        s.start()
    }
    /// THE OTHER APP'S DOOR IS FOLLOWED ONLY UNDER THE SCHEME THIS APP NAMES (Info.plist MontanaBizAppDoor): the service's answer
    /// names the door, the app decides whether it is one it may open -- never a telephone, a message or a stranger's app.
    static func appDoor(_ link: String?) -> URL? {
        let allowed = (Bundle.main.object(forInfoDictionaryKey: "MontanaBizAppDoor") as? String ?? "").lowercased()
        guard !allowed.isEmpty, let s = link, let u = URL(string: s), u.scheme?.lowercased() == allowed else { return nil }
        return u
    }
    /// THE LINK THE SERVICE HANDS IS OPENED ONLY ON THE WEB'S SECURE ROAD (point 0 of the constitution): an answer that named another scheme -- a
    /// telephone, a message, another app's door -- is not followed; the service's answer is no word of command over the phone.
    static func road(_ link: String) -> URL? {
        guard let u = URL(string: link), u.scheme?.lowercased() == "https", u.host?.isEmpty == false else { return nil }
        return u
    }
    /// The road is taken: the person's key, then the start, the link, the wait.
    func start(_ road: MTBizService.Road, e164: String, done: @escaping () -> Void) {
        guard waiting == nil else { return }
        waiting = road
        task = Task {
            await MontanaSeed.birth?.value   // a person still being born behind the number's page: the road waits for the birth
            let keys = Task.detached(priority: .userInitiated) { MontanaSeed.keys()?.pub }
            guard let pub = await keys.value else {
                refuse("The identity could not be stored on this device")
                return
            }
            guard let s = await MTBizService.start(e164: e164, subject: MTBizPhone.subject(pub), channel: road.channel),
                  let u = Self.road(s.link) else {
                refuse("The confirmation service did not answer. Try again in a minute.")
                return
            }
            started = s
            link = u
            MontanaP2PTrace.mark("biz_phone", "started channel=\(road.channel)")
            if road.channel == MTBizService.signInChannel {
                // THE ACCOUNT'S ANSWER COMES WITH THE WAY BACK, not by the wait: the code the sign-in returns is redeemed here.
                signIn = true
                self.e164 = e164
                finished = done
                appLink = Self.appDoor(s.app_link)
                let back = URLComponents(url: u, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "redirect_uri" }?.value
                returnTo = back.flatMap { URL(string: $0) }
                Self.current = self
                MTBizReturn.expect(returnTo)
                return
            }
            while !Task.isCancelled {
                if s.expires_ms < MTBusiness.nowMs { refuse("The confirmation took too long. Start again."); return }
                let ask = Task { await MTBizService.wait(nonce: s.nonce, claim: s.claim) }
                asking = ask
                switch await ask.value {
                case .confirmed(let a, let card):
                    confirm(a, card: card, e164: e164, channel: road.channel, done: done)
                    return
                case .mismatch, .unverified: refuse("The confirmation does not match this number."); return
                case .again: continue
                case .expired: refuse("The confirmation took too long. Start again."); return
                case .failed: if !ask.isCancelled { try? await Task.sleep(nanoseconds: 2_000_000_000) }
                }
            }
        }
    }
    /// The sign-in came back -- the universal link the messenger's app opens after its confirmation, or the sheet's own end --
    /// with the code; the service redeems it and answers with the confirmation itself.
    func cameBackWith(_ url: URL) {
        guard signIn, let s = started, let n = e164, let done = finished else { return }
        guard let code = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first(where: { $0.name == "code" })?.value,
              !code.isEmpty else {
            refuse("The sign-in did not come back. Start again.")
            return
        }
        MontanaP2PTrace.mark("biz_phone", "sign-in came back")
        task = Task {
            switch await MTBizService.finish(nonce: s.nonce, claim: s.claim, code: code) {
            case .confirmed(let a, let card): confirm(a, card: card, e164: n, channel: MTBizService.signInChannel, done: done)
            case .mismatch: refuse("The confirmation does not match this number.")
            case .unverified: refuse("This account has no verified number.")
            case .expired: refuse("The confirmation took too long. Start again.")
            case .again, .failed: refuse("The confirmation service did not answer. Try again in a minute.")
            }
        }
    }
    private func confirm(_ a: Data, card: MTBizService.Card?, e164: String, channel: Int, done: () -> Void) {
        guard MTBizPhone.keep(a, e164: e164) else { refuse("The confirmation does not match this number."); return }
        MTBizCard.adopt(card)   // the card fills what the profile still lacks
        let claimedName = card?.claimedName
        Task { await MTBizDirectory.publish(attest: a, claimedName: claimedName) }   // the number is attached to the person at once (18:3x)
        MontanaP2PTrace.mark("biz_phone", "confirmed channel=\(channel)")
        waiting = nil
        release()
        for org in MTBusiness.shared.views.keys { MTBusiness.shared.bindPhone(org) }
        E2E.shared.broadcastAbout()   // the number confirmed goes to every correspondent with my words (stage N)
        done()
    }
    private func release() {
        if Self.current === self { Self.current = nil; MTBizReturn.expect(nil) }
    }
    private func refuse(_ key: String) {
        waiting = nil
        refusal = key
        release()
        MontanaP2PTrace.mark("biz_phone", "refused")
    }
}

/// THE WAY BACK OF THE SIGN-IN: the scheme and host it returns to while a sign-in stands, read by the one entrance of links
/// (MontanaMeeting.handleLink) on whatever thread a link lands.
enum MTBizReturn {
    private static let lock = NSLock()
    private static var key: String?
    private static func form(_ u: URL) -> String { (u.scheme ?? "").lowercased() + "://" + (u.host ?? "").lowercased() }
    static func expect(_ to: URL?) {
        lock.lock()   // LOCK-OK: one word in memory
        key = to.map { form($0) }
        lock.unlock()
    }
    static func matches(_ url: URL) -> Bool {
        lock.lock()   // LOCK-OK: one word in memory
        defer { lock.unlock() }
        return key != nil && form(url) == key
    }
}

/// The window the system's sign-in sheet rises over: the top of the one stack of modals (MTTop).
final class MTBizSheetAnchor: NSObject, ASWebAuthenticationPresentationContextProviding {
    static let shared = MTBizSheetAnchor()
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        MainActor.assumeIsolated { MTTop.controller?.view.window } ?? ASPresentationAnchor()
    }
}

extension MTBizCountries {
    /// The country a number's digits name: the longest calling code they begin with; where two countries share a code (1, 7)
    /// the one the person chose keeps it.
    static func match(_ digits: String, prefer: Country) -> Country? {
        var n = min(3, digits.count)
        while 0 < n {
            let head = String(digits.prefix(n))
            if prefer.code == head { return prefer }
            if let c = all.first(where: { $0.code == head }) { return c }
            n -= 1
        }
        return nil
    }
    /// The number as the field shows it: «+», the calling code, then the national digits -- up to ten of them as 3 3-2-2, more
    /// in threes. Digits that name no country stand as typed.
    static func shown(_ digits: String, code: String?) -> String {
        guard !digits.isEmpty else { return "" }
        guard let code, digits.hasPrefix(code), code.count < digits.count else { return "+" + digits }
        let national = Array(digits.dropFirst(code.count))
        var out = "+" + code + " "
        for (i, ch) in national.enumerated() {
            if national.count <= 10 {
                if i == 3 { out += " " } else if i == 6 || i == 8 { out += "-" }
            } else if 0 < i, i % 3 == 0 {
                out += " "
            }
            out.append(ch)
        }
        return out
    }
    /// A CONFIRMED NUMBER AS A PROFILE SHOWS IT (stage N, the author's word 07.10.2026 19:1x: «shown in the profile as in the
    /// reference»): the field's own grouping, the country found by its code.
    static func pretty(_ e164: String) -> String {
        let digits = e164.filter { $0.isASCII && $0.isNumber }
        guard !digits.isEmpty else { return "" }
        return shown(digits, code: match(digits, prefer: initial)?.code)
    }
    /// A number as the wire may carry it: E.164 -- the plus and 8 to 15 digits.
    static func isE164(_ s: String) -> Bool {
        guard s.hasPrefix("+") else { return false }
        let digits = s.dropFirst()
        return (8...15).contains(digits.count) && digits.allSatisfy { $0.isASCII && $0.isNumber } && digits.first != "0"
    }
}

/// MY NUMBER, AS MY CORRESPONDENTS SEE IT (stage N, the author's word: «it must be in the account, shown in the profile ... sent to
/// the contacts»): only the number the service confirmed for my own key -- never one typed by hand.
extension MTPeerAbout {
    static var myPhone: String { MTBizPhone.confirmed()?.e164 ?? "" }
    /// A CORRESPONDENT'S NUMBER IS KEPT ONLY WITH THE SERVICE'S WORD FOR IT (the critic, stage N: «a measure names what it measures»):
    /// the confirmation beside it must open under the service's pinned key (MTBizCore.attestOpen) and name the very number. A
    /// number said without it -- a build that sends none, or a word that lies -- is not shown as a confirmed number.
    static func confirmedNumber(_ o: [String: Any]) -> String? {
        guard let p = o["p"] as? String, MTBizCountries.isE164(p), let hex = o["pa"] as? String, hex.count <= 4096,
              let a = Data(montanaHex: hex), let open = MTBizCore.attestOpen(a), open.e164 == p else { return nil }
        return p
    }
}

/// THE NUMBER'S DOOR (the author's word 06.10.2026 10:3x MSK, with his screenshots: «after "continue with phone number" give
/// the field of the phone number as on the screenshots, with the question how you want to get the code»): one field -- the
/// flag of the country the number names and its mark, the number with its calling code, the clear mark -- and «Next» at the
/// foot. The flag follows the code the person types; its mark opens the countries with a search. «Next» asks how the code
/// should come: one row for every road the service keeps, its name and glyph the service's own (no outer service is named in
/// the app). While a confirmation waits, the door says so, with the way back (the cross) and the outer service opened again.
/// Without a living service the question's sheet says ONE HONEST LINE (the checklist 2.2) and, where the page has a way on,
/// gives the door to go on without a number.
struct MTBizPhoneDoor: View {
    var onConfirmed: () -> Void
    /// The way on without a number (the login page): the number is confirmed later from the organisations' page.
    var onWithout: (() -> Void)? = nil
    @StateObject private var flow = MTBizPhoneFlow()
    @State private var chosen = MTBizCountries.initial
    @State private var digits = MTBizCountries.initial.code
    @State private var countries = false
    @State private var asking = false
    @State private var copied = false   // the copy mark shows its check for two seconds
    @FocusState private var typing: Bool
    @Environment(\.scenePhase) private var phase
    /// The country the digits name; nil while they name none (the field then shows a globe).
    private var country: MTBizCountries.Country? { MTBizCountries.match(digits, prefer: chosen) }
    /// THE NUMBER AS TYPED (the author's screenshot 06.10.2026 16:30 MSK: an anonymous number, +888, and «Next» grey): a number
    /// no country names is taken as written -- the service and the sign-in judge it, not a table of countries; a number that
    /// names one must be longer than its code.
    private var e164: String? {
        if let c = country, digits.count <= c.code.count { return nil }
        guard (6...15).contains(digits.count), digits.first != "0" else { return nil }
        return "+" + digits
    }
    private var shown: Binding<String> {
        Binding(get: { MTBizCountries.shown(digits, code: country?.code) }, set: { typed in
            digits = String(typed.filter { $0.isASCII && $0.isNumber }.prefix(15))
            if let c = MTBizCountries.match(digits, prefer: chosen) { chosen = c }
        })
    }
    private var without: (() -> Void)? {
        guard let go = onWithout else { return nil }
        return { asking = false; go() }
    }

    var body: some View {
        Group {
            if let road = flow.waiting {
                waitingFace(road)
            } else {
                VStack(spacing: 16) {
                    field
                    Spacer(minLength: 0)
                    foot
                }
            }
        }
        .frame(maxWidth: 420)
        .task { await flow.watch() }
        .task {
            try? await Task.sleep(nanoseconds: 400_000_000)   // a field focused while its page still rises does not take the keyboard
            typing = true
        }
        .onDisappear { flow.cancel() }   // a door that leaves leaves its wait: nothing walks on behind the person
        .onChange(of: phase) { _, now in if now == .active { flow.cameBack() } }
        .onChange(of: countries) { _, open in if !open { typing = true } }
        .sheet(isPresented: $countries) {
            MTBizCountrySheet(chosen: country ?? chosen) { c in
                chosen = c
                digits = c.code
            }
        }
        .sheet(isPresented: $asking) {
            MTBizRoadSheet(flow: flow, onWithout: without) { road in
                asking = false
                guard let n = e164 else { return }
                flow.start(road, e164: n, done: onConfirmed)
            }
        }
        .mtBizWord($flow.refusal, title: "Phone number")
    }

    private var field: some View {
        HStack(spacing: 0) {
            Button { typing = false; countries = true } label: {
                HStack(spacing: 6) {
                    if let c = country {
                        // USER-DATA: the flag of the country the number names
                        Text(verbatim: MTBizCountries.flag(c)).font(.title2)
                    } else {
                        Image(systemName: "globe").font(.title3).foregroundColor(.white)
                    }
                    Image(systemName: "arrowtriangle.down.fill").font(.caption2).foregroundColor(.white)
                }
                .frame(minWidth: 64, minHeight: 64)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text("Country"))
            TextField("Phone number", text: shown)
                .keyboardType(.phonePad)
                .textContentType(.telephoneNumber)
                .font(.title3.weight(.semibold)).foregroundColor(.white)
                .monospacedDigit()
                .focused($typing)
            if (country?.code.count ?? 0) < digits.count {
                Button { digits = country?.code ?? "" } label: {
                    Image(systemName: "xmark.circle.fill").font(.title3).foregroundColor(.gray)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("Clear"))
            }
        }
        .padding(.trailing, 8)
        .frame(height: 64)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(MontanaOctagon.barMaterial))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous)
            .stroke(Color.white.opacity(typing ? 1 : 0.35), lineWidth: typing ? 2 : 1))
    }

    /// «Next» stands whatever the service says: the question comes after the number, as on the author's screenshots, and the
    /// sheet of the question speaks the service's silence honestly, with the way on where the page has one.
    private var foot: some View {
        Button {
            typing = false
            Task {
                // A page that does not know the roads yet asks the service again at the tap, before it speaks of silence.
                if flow.roads.isEmpty {
                    flow.checking = true
                    _ = await flow.askAgain()
                    flow.checking = false
                }
                // ONE ROAD, NO QUESTION (the author's word 06.10.2026 16:0x MSK: «after the tap the page gives the link to our bot»): a
                // service that keeps one road is not asked about -- its page stands at once; several roads are still asked.
                // The account's door, when the service offers it, is the road taken (the author's choice 06.10.2026 16:3x MSK).
                let door = flow.roads.first { $0.channel == MTBizService.signInChannel }
                if let road = door ?? (flow.roads.count == 1 ? flow.roads.first : nil), let n = e164 {
                    flow.start(road, e164: n, done: onConfirmed)
                } else {
                    asking = true
                }
            }
        } label: {
            if flow.checking { ProgressView().tint(.white) } else { Text("Next") }
        }
            .buttonStyle(MTLoginDoorStyle(tint: MontanaOctagon.platformBlue))
            .disabled(e164 == nil || flow.checking)
    }

    /// THE PAGE OF THE BOT (the author's word 06.10.2026 16:0x MSK: «after the tap on the sign-in by the phone number the page gives
    /// the link to our bot, with a big button VERIFY NUMBER»): the platform's prominent capsule at the large size, in the blue of my
    /// bubbles as the number's door wears it, opens the bot; the service's link stands under it as the web writes it; once the bot
    /// was opened the page says it waits. The cross leaves the wait.
    private func waitingFace(_ road: MTBizService.Road) -> some View {
        VStack(spacing: 12) {
            HStack {
                MontanaCallMark(glyph: "xmark", label: "Cancel") { flow.cancel() }
                Spacer()
            }
            Spacer(minLength: 0)
            Image(systemName: road.glyph).font(.system(size: 44, weight: .semibold)).foregroundColor(.white).accessibilityHidden(true)
            Text("Tap the button to confirm your number in our bot, then come back here.")
                .font(.body).foregroundColor(.white).multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            Button { flow.openLink() } label: {
                Text("Verify Number")
                    .textCase(.uppercase)
                    .font(.title3.weight(.bold))
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.borderedProminent)
            .buttonBorderShape(.capsule)
            .controlSize(.large)
            .tint(MontanaOctagon.platformBlue)
            .disabled(flow.link == nil)
            if let link = flow.link {
                // THE LINK STANDS UNDER THE BUTTON, LIVE, AND ONE TAP COPIES IT (the author's word 06.10.2026 22:5x MSK, his screenshot
                // of the Mac: «under the button a shown, clickable link and a copy button in our style»): the link opens in the
                // system's browser -- the sign-in's way back is this app's own scheme, so its code returns here even where the
                // messenger's own door does not open; the round mark beside it copies the link for the confirmation's term.
                HStack(spacing: 8) {
                    Button { flow.openInBrowser() } label: {
                        // USER-DATA: the link as the Business's service hands it
                        Text(verbatim: link.absoluteString)
                            .font(.footnote).underline().foregroundColor(Color.white.opacity(0.8))
                            .lineLimit(1).truncationMode(.middle)
                            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    MontanaCallMark(glyph: copied ? "checkmark" : "doc.on.doc", label: "Copy") {
                        UIPasteboard.general.setItems([[UTType.url.identifier: link]],
                                                      options: [.expirationDate: Date().addingTimeInterval(TimeInterval(13 * 60))])
                        copied = true
                        Task { try? await Task.sleep(nanoseconds: 2_000_000_000); copied = false }
                    }
                }
            } else {
                ProgressView().tint(.white).frame(minHeight: 44)   // the service is handing the link
            }
            if flow.opened {
                HStack(spacing: 8) {
                    ProgressView().tint(.white)
                    Text("Waiting for the confirmation…").font(.subheadline).foregroundColor(.white)
                }
            }
            Spacer(minLength: 0)
        }
    }
}

/// HOW THE CODE COMES (the author's word 06.10.2026 10:3x: «with the question how you want to get the code»): a sheet from the
/// foot of the screen, the question, and one row for every road the service keeps -- its glyph and its name in the person's
/// language, both the service's own; the whole row is the target. While the service is still being asked, the platform's
/// spinner; when it answers with none, the honest line and, where the page has one, the way on without a number.
struct MTBizRoadSheet: View {
    @ObservedObject var flow: MTBizPhoneFlow
    var onWithout: (() -> Void)?
    var onRoad: (MTBizService.Road) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Spacer()
                MontanaCallMark(glyph: "xmark", label: "Close") { dismiss() }
            }
            Text("How would you like to get the code?")
                .font(.title.bold()).foregroundColor(.white)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.bottom, 12)
            if !flow.roads.isEmpty {
                ForEach(flow.roads) { road in
                    Button { onRoad(road) } label: {
                        HStack(spacing: 16) {
                            Image(systemName: road.glyph).font(.title2.weight(.semibold)).foregroundColor(.white)
                                .frame(width: 36).accessibilityHidden(true)
                            // USER-DATA: the road's own name, as the Business's service names it in the person's language
                            Text(verbatim: road.title).font(.title3).foregroundColor(.white)
                            Spacer(minLength: 0)
                        }
                        .frame(minHeight: 56)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            } else if flow.asked {
                // The door's watch keeps asking every five seconds: the rows stand here the moment the service answers.
                HStack(spacing: 8) {
                    ProgressView().tint(.gray)
                    Text("Phone number confirmation is unavailable right now").font(.subheadline).foregroundColor(.gray)
                }
                if let onWithout {
                    Button { onWithout() } label: { Text("Continue without a number") }
                        .buttonStyle(MTLoginDoorStyle())
                        .padding(.top, 12)
                }
            } else {
                ProgressView().tint(.white).frame(maxWidth: .infinity, minHeight: 56)   // the service is being asked for its roads
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 24).padding(.top, 16)
        .presentationDetents([.height(340)])
        .preferredColorScheme(.dark)
    }
}

/// THE COUNTRIES (the author's screenshots 06.10.2026): the platform's sheet with its search; the country the number names and
/// the phone's own region first, then every country by its name in the person's language; the whole row is the target.
struct MTBizCountrySheet: View {
    let chosen: MTBizCountries.Country
    var onChoose: (MTBizCountries.Country) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    private var first: [MTBizCountries.Country] {
        let home = MTBizCountries.initial
        return home == chosen ? [chosen] : [chosen, home]
    }
    private var rest: [MTBizCountries.Country] {
        MTBizCountries.all.filter { !first.contains($0) }
            .sorted { MTBizCountries.name($0).localizedCompare(MTBizCountries.name($1)) == .orderedAscending }
    }
    private var found: [MTBizCountries.Country] {
        let q = query.trimmingCharacters(in: .whitespaces)
        let digits = q.filter { $0.isASCII && $0.isNumber }
        return (first + rest).filter { c in
            MTBizCountries.name(c).localizedCaseInsensitiveContains(q) || c.region.localizedCaseInsensitiveContains(q)
                || (!digits.isEmpty && c.code.hasPrefix(digits))
        }
    }

    var body: some View {
        NavigationStack {
            List {
                if query.trimmingCharacters(in: .whitespaces).isEmpty {
                    Section { ForEach(first) { row($0) } }
                    Section { ForEach(rest) { row($0) } }
                } else {
                    Section { ForEach(found) { row($0) } }
                }
            }
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: Text("Search for a country or region"))
            .scrollContentBackground(.hidden).montanaPageGround()
            .navigationTitle("Choose your country or region").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarLeading) { MontanaCloseMark { dismiss() } } }
        }
        .preferredColorScheme(.dark)
    }

    private func row(_ c: MTBizCountries.Country) -> some View {
        Button { onChoose(c); dismiss() } label: {
            HStack(spacing: 14) {
                // USER-DATA: a country's flag, its name in the system's words, its calling code
                Text(verbatim: MTBizCountries.flag(c)).font(.title2)
                Text(verbatim: MTBizCountries.name(c) + " (+" + c.code + ")").foregroundColor(.white)
                Spacer(minLength: 0)
            }
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .listRowBackground(MTGlassRowPlate())
    }
}

/// THE FLOW OF ONE ADDRESS'S CONFIRMATION: the code asked for the person's own key, the code typed back, the signed answer
/// kept. The person is never born here: the login page's door of the address makes them first (MontanaOnboardingView).
@MainActor
final class MTBizMailFlow: ObservableObject {
    @Published var sentTo: String?
    @Published var busy = false
    @Published var refusal: String?
    private var started: MTBizService.MailStarted?

    func reset() {
        sentTo = nil
        started = nil
        busy = false
    }
    func start(_ email: String) {
        guard !busy else { return }
        busy = true
        Task {
            await MontanaSeed.birth?.value   // a person still being born behind the page: the code waits for the birth
            let keys = Task.detached(priority: .userInitiated) { MontanaSeed.keys()?.pub }
            guard let pub = await keys.value else {
                refuse("The identity could not be stored on this device")
                return
            }
            switch await MTBizService.emailStart(email, subject: MTBizPhone.subject(pub)) {
            case .started(let s):
                started = s
                sentTo = email
                busy = false
                MontanaP2PTrace.mark("biz_mail", "started")
            case .refused(let key):
                refuse(key)
            }
        }
    }
    func finish(_ code: String, done: @escaping () -> Void) {
        guard !busy, let s = started, let email = sentTo else { return }
        busy = true
        Task {
            switch await MTBizService.emailFinish(nonce: s.nonce, claim: s.claim, code: code) {
            case .confirmed(let a):
                guard MTBizEmail.keep(a, email: email) else { refuse("The confirmation does not match this address."); return }
                MontanaP2PTrace.mark("biz_mail", "confirmed")
                busy = false
                done()
            case .wrong(let left):
                busy = false
                refusal = String(format: String(localized: "The code is not right. Tries left: %lld"), left)
            case .gone:
                reset()
                refuse("The code has expired. Ask for a new one.")
            case .failed:
                refuse("The confirmation service did not answer. Try again in a minute.")
            }
        }
    }
    private func refuse(_ key: String) {
        busy = false
        refusal = key
        MontanaP2PTrace.mark("biz_mail", "refused")
    }
}

/// THE ADDRESS'S DOOR (the author's word 06.10.2026 17:5x MSK): the field of the address and «Next»; then the field of the code,
/// which the system fills from the letter (one-time code), and six digits confirm at once -- no second button.
struct MTBizMailDoor: View {
    var onConfirmed: () -> Void
    @StateObject private var flow = MTBizMailFlow()
    @State private var typed = ""
    @State private var code = ""
    @FocusState private var typing: Bool

    var body: some View {
        VStack(spacing: 16) {
            if let sent = flow.sentTo {
                // USER-DATA: the address the person typed
                Text("We sent it to \(sent)").font(.body).foregroundColor(.white)
                    .frame(maxWidth: .infinity, alignment: .leading)
                plate {
                    TextField("Code", text: $code)
                        .keyboardType(.numberPad)
                        .textContentType(.oneTimeCode)
                        .font(.title3.weight(.semibold).monospacedDigit()).foregroundColor(.white)
                        .focused($typing)
                        .onChange(of: code) { _, entered in
                            let digits = String(entered.filter { $0.isASCII && $0.isNumber }.prefix(6))
                            if digits != entered { code = digits }
                            if digits.count == 6 { flow.finish(digits, done: onConfirmed) }
                        }
                }
                Button { flow.reset(); code = ""; typing = true } label: {
                    Text("Change address").font(.body).frame(maxWidth: .infinity, minHeight: 44).contentShape(Rectangle())
                }
                .buttonStyle(.plain).foregroundColor(Color.white.opacity(0.8))
                Spacer(minLength: 0)
            } else {
                plate {
                    TextField("Email", text: $typed)
                        .keyboardType(.emailAddress)
                        .textContentType(.emailAddress)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .font(.title3.weight(.semibold)).foregroundColor(.white)
                        .focused($typing)
                        .submitLabel(.next)
                        .onSubmit { if let a = MTBizEmail.shaped(typed) { flow.start(a) } }
                }
                Spacer(minLength: 0)
                Button { if let a = MTBizEmail.shaped(typed) { typing = false; flow.start(a) } } label: {
                    HStack(spacing: 8) {
                        if flow.busy { ProgressView().tint(.white) }
                        Text("Next")
                    }
                }
                .buttonStyle(MTLoginDoorStyle(tint: MontanaOctagon.platformBlue))
                .disabled(MTBizEmail.shaped(typed) == nil || flow.busy)
            }
        }
        .frame(maxWidth: 420)
        .task {
            try? await Task.sleep(nanoseconds: 400_000_000)   // a field focused while its page still rises does not take the keyboard
            typing = true
        }
        .onChange(of: flow.sentTo) { _, _ in typing = true }
        .mtBizWord($flow.refusal, title: "Email")
    }

    /// The field's plate, as the number's field wears it.
    private func plate<Field: View>(@ViewBuilder _ field: () -> Field) -> some View {
        field()
            .padding(.horizontal, 16)
            .frame(height: 64)
            .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(MontanaOctagon.barMaterial))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(Color.white.opacity(typing ? 1 : 0.35), lineWidth: typing ? 2 : 1))
    }
}

/// THE CATALOG «NUMBER -> PERSON» ON THIS PHONE (the author's words 06.10.2026 18:3x-18:4x MSK: «attach the number to the account
/// at once, so that by it contacts can see you and it shows who of the contacts is in Montana»; his choice: «visible to everyone who
/// has my number» -- a deviation from the Montana set, recorded in the Business checklist). The person's own card goes to the
/// catalog with the confirmation of the number; the phone's book is asked by hashes of its numbers (SHA-256 of E.164) and the
/// people found stand on the Contacts page as rows of it.
@MainActor
final class MTBizDirectory: ObservableObject {
    static let shared = MTBizDirectory()
    static let rowPrefix = "dir:"
    /// e164 -- the number as my own book holds it: the catalog never names a number, it is asked by its hash.
    struct Found: Equatable { let hash: String; let name: String; let claimedName: String; let link: String; var e164: String = "" }
    @Published private(set) var found: [Found] = []
    private var askedAt = Date.distantPast
    /// The hashes of my book already found in Montana and said once («X is in Montana»): the person's own, never in a copy.
    nonisolated static let seenKey = "biz.dirSeen"
    private var watching: NSObjectProtocol?
    private init() {
        // THE BOOK CHANGED (a person added or a number corrected): it is asked again at once, not in ten minutes (stage N)
        watching = NotificationCenter.default.addObserver(forName: .CNContactStoreDidChange, object: nil, queue: .main) { _ in
            Task { @MainActor in
                MTBizDirectory.shared.askedAt = .distantPast
                await MTBizDirectory.shared.refresh()
            }
        }
    }

    func person(_ ref: String) -> Found? {
        guard ref.hasPrefix(Self.rowPrefix) else { return nil }
        return found.first { Self.rowPrefix + $0.hash == ref }
    }

    /// My card, with the confirmation of my number: the name of my profile, my nick, the link of my first contact.
    static func publish(attest: Data, claimedName: String?) async {
        let card = ["name": E2E.myDisplayName(), "nick": claimedName ?? MontanaNames.heldName ?? "", "link": MontanaCard.offerStanding() ?? ""]
        let ok = await MTBizService.dirPut(attest: attest.montanaHexString, card: card)
        MontanaP2PTrace.mark("biz_dir", "published=\(ok ? 1 : 0)")
    }

    /// The person leaves (Forget this device): the catalog lets the number and the card go first, while the confirmation that
    /// proves them still lies on this phone; a door that does not answer leaves the entry, and the support page says how to ask.
    static func withdraw() async {
        guard let a = MTBizPhone.attestation() else { return }
        let gone = await MTBizService.dirRemove(attest: a.montanaHexString)
        MontanaP2PTrace.mark("biz_dir", "withdrawn=\(gone ? 1 : 0)")
    }

    /// The people of my book who are in Montana: asked at most once in ten minutes, only with the book's permission.
    func refresh() async {
        let st = CNContactStore.authorizationStatus(for: .contacts)
        var allowed = st == .authorized
        if #available(iOS 18.0, *) { allowed = allowed || st == .limited }
        guard allowed, 600 < Date().timeIntervalSince(askedAt) else { return }
        askedAt = Date()
        let mine = MTBizPhone.confirmed()?.e164
        let book = await Task.detached(priority: .utility) { Self.numbersInBook() }.value
        var byHash: [String: String] = [:]
        var numberOf: [String: String] = [:]
        for (e164, name) in book where e164 != mine { byHash[Self.hash(e164)] = name; numberOf[Self.hash(e164)] = e164 }
        let hashes = Array(byHash.keys)
        var out: [Found] = []
        var at = 0
        while at < hashes.count {
            let chunk = Array(hashes[at..<min(at + 500, hashes.count)])
            at += 500
            for (h, card) in await MTBizService.dirMatch(chunk) {
                guard let link = card["link"], !link.isEmpty else { continue }
                let named = byHash[h] ?? ""
                out.append(Found(hash: h, name: named.isEmpty ? (card["name"] ?? "") : named, claimedName: card["nick"] ?? "", link: link,
                                 e164: numberOf[h] ?? ""))
            }
        }
        found = out.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        MontanaP2PTrace.mark("biz_dir", "asked=\(hashes.count) found=\(out.count)")
        joined(out)
    }
    /// WHO OF MY BOOK CAME TO MONTANA (stage N, the author's word 07.10.2026 19:1x: «the name of the user and that he is in
    /// Montana»): a person found now and never before is said once, by a banner of Montana's own room. The first asking on a phone
    /// only learns the book -- a hundred banners at the first look would be noise, not news.
    private func joined(_ now: [Found]) {
        let d = UserDefaults.standard
        let first = d.object(forKey: Self.seenKey) == nil
        let before = Set(d.stringArray(forKey: Self.seenKey) ?? [])
        if !first {
            for f in now where !before.contains(f.hash) {
                MontanaNotify.presentJoined(name: f.name.isEmpty ? "@" + f.claimedName : f.name, tag: f.hash)
            }
        }
        d.set(Array(before.union(now.map(\.hash))), forKey: Self.seenKey)
    }
    /// A NUMBER ASKED BY THE PERSON'S OWN TAP (stage N, the author's word: «find by it»): the search never asks while a person
    /// types (ChatsListView.runEmailSearch) -- the row «Find in Montana» does, once, by the number's hash alone. Nil -- nobody of
    /// Montana holds it, or the service is silent.
    func lookUp(_ e164: String) async -> Found? {
        guard MTBizCountries.isE164(e164), e164 != MTBizPhone.confirmed()?.e164 else { return nil }
        let h = Self.hash(e164)
        if let known = found.first(where: { $0.hash == h }) { return known }
        guard let hit = await MTBizService.dirMatch([h]).first(where: { $0.0 == h }), let link = hit.1["link"], !link.isEmpty else {
            MontanaP2PTrace.mark("biz_dir", "looked up -- none")
            return nil
        }
        MontanaP2PTrace.mark("biz_dir", "looked up -- found")
        return Found(hash: h, name: hit.1["name"] ?? "", claimedName: hit.1["nick"] ?? "", link: link, e164: e164)
    }

    /// The numbers of the phone's book in E.164 (the phone's own region for numbers written without a code), with the name the
    /// book gives each.
    nonisolated static func numbersInBook() -> [String: String] {
        let store = CNContactStore()
        let keys = [CNContactGivenNameKey, CNContactFamilyNameKey, CNContactPhoneNumbersKey] as [CNKeyDescriptor]
        let region = MTBizCountries.initial
        var out: [String: String] = [:]
        try? store.enumerateContacts(with: CNContactFetchRequest(keysToFetch: keys)) { c, _ in
            let name = [c.givenName, c.familyName].filter { !$0.isEmpty }.joined(separator: " ")
            for n in c.phoneNumbers {
                if let e = MTBizCountries.e164(region, n.value.stringValue) { out[e] = name }
            }
        }
        return out
    }
    nonisolated static func hash(_ e164: String) -> String {
        SHA256.hash(data: Data(e164.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}

