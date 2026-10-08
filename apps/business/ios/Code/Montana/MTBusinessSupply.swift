import CryptoKit
import PhotosUI
import Speech
import SwiftUI
import UIKit

// ════════════════════════════════════════════════════════════
// MONTANA BUSINESS: THE SUPPLY CHAIN, FROM THE ORDER TO THE COUNTER (stages 6.3-6.6)
// ════════════════════════════════════════════════════════════
// The author's word 06.10.2026: «a special focus on supply -- from the moment a pair of shoes is ordered, where the managers
// write the order or record it by voice with its transcription, the whole chain of supply to every logistics node and to the
// shop's counter». Every order is a TimeChain of its own (chain S, its lane the order's key): each step is signed by the one
// who took it, and where a place stands comes only from its keeper's own Accept. The rules live in the core; these pages draw
// the core's view and hand it the contract's commands (MTBizCommand). The voice is heard on this phone alone
// (SFSpeechRecognizer, on-device recognition only): the sound never leaves it, and the order carries only its hash.

/// The words of the supply pages, each named once: an order's state, a step's kind, an accepted place's condition, a
/// problem's kind (a moment and a span are MTBizText's).
enum MTBizSupplyText {
    static func state(_ s: String) -> LocalizedStringKey {
        switch s {
        case "confirmed": return "Confirmed"
        case "packed": return "Packed"
        case "in_transit": return "In transit"
        case "delivered": return "Delivered"
        case "cancelled": return "Cancelled"
        default: return "New"
        }
    }
    static func step(_ kind: String) -> (glyph: String, title: LocalizedStringKey) {
        switch kind {
        case "order": return ("doc.text.fill", "Order placed")
        case "confirm": return ("checkmark.circle.fill", "Confirmed by the supplier")
        case "pack": return ("shippingbox.fill", "Place packed")
        case "handoff": return ("arrow.right.circle.fill", "Handed on")
        case "accept": return ("tray.and.arrow.down.fill", "Accepted")
        case "scan": return ("qrcode.viewfinder", "Scanned")
        case "shelf": return ("square.stack.3d.up.fill", "On the counter")
        case "sale": return ("cart.fill", "Sold")
        case "issue": return ("exclamationmark.triangle.fill", "Problem")
        case "cancel": return ("xmark.circle.fill", "Order cancelled")
        default: return ("circle", "Step")
        }
    }
    static func condition(_ c: UInt64) -> LocalizedStringKey? {
        switch c {
        case 1: return "Whole"
        case 2: return "Damaged"
        case 3: return "Short"
        default: return nil
        }
    }
    static func issue(_ k: UInt64) -> LocalizedStringKey? {
        switch k {
        case 1: return "Return"
        case 2: return "Defect"
        case 3: return "Loss"
        default: return nil
        }
    }
    /// A place's short name on the screen and on its label: the first eight letters of its tag.
    static func tag(_ hex: String) -> String { String(hex.prefix(8)).uppercased() }
}

extension MTBizView {
    func item(_ id: String) -> Item? { itemList.first { $0.id == id } }
    func node(_ id: String?) -> Node? { id.flatMap { n in nodeList.first { $0.id == n } } }
    func order(_ id: String) -> Order? { orderList.first { $0.id == id } }
    func nodeName(_ id: String?) -> String { node(id)?.name ?? "" }
    func lineText(_ l: Line) -> String {
        [item(l.item)?.title ?? "", l.size, "×" + String(l.qty)].filter { !$0.isEmpty }.joined(separator: " · ")
    }
    func linesText(_ ls: [Line]) -> String { ls.map(lineText).joined(separator: ", ") }
    /// A node this viewer acts on: every node for an administrator, the nodes they staff for anyone else (the core's keeps).
    func keeps(_ node: String?) -> Bool {
        guard let node else { return false }
        return MTBusiness.boss(me.role) || self.node(node)?.mine == true
    }
    var keptNodes: [Node] { nodeList.filter { $0.active && keeps($0.id) } }
    /// Whether a node has goods to put on its counter (on hand) or to sell (on the counter): the core refuses a step past
    /// them (short_stock, short_counter).
    func hasGoods(at node: String, onCounter: Bool) -> Bool {
        stockList.contains { $0.node == node && 0 < (onCounter ? $0.counter : $0.on_hand) }
    }
    /// The nodes of an order's way where this person may put goods on the counter, or sell them.
    func counterNodes(_ o: Order, sale: Bool) -> [Node] {
        keptNodes.filter { o.path.contains($0.id) && hasGoods(at: $0.id, onCounter: sale) }
    }
    /// Why this person may not write an order now, where the reason can be said (the core's open: a manager or an
    /// administrator, or the people of the node it goes to; two open nodes; a product still offered); nil -- they may.
    var orderRefusal: LocalizedStringKey? {
        if role == .employee, keptNodes.isEmpty { return "You write orders for the node you work at, once an administrator adds you to one." }
        if nodeList.filter(\.active).count < 2 { return "An order needs two nodes. An administrator adds them under Nodes." }
        if !itemList.contains(where: \.active) { return "An order needs a product. An administrator adds them under Products." }
        return nil
    }
}

extension MTBizView.Item {
    /// The sizes as the administrator wrote them, one by one: «40, 41, 42».
    var sizeList: [String] {
        sizes.split(whereSeparator: { $0 == "," || $0 == ";" || $0 == " " }).map(String.init)
    }
}

extension MTBizView.Order {
    /// Every node the order has named or passed, as its route, its places and its steps say.
    var path: [String] {
        var named: [String] = [from, to]
        for p in places {
            let seen: [String?] = [p.packed_at, p.holder, p.transit_from, p.transit_to]
            named += seen.compactMap { $0 }
        }
        named += steps.compactMap(\.node)
        var out: [String] = []
        for n in named where !out.contains(n) { out.append(n) }
        return out
    }
    var open: Bool { state != "cancelled" }
    /// Something of the order is still to pack: the core's unpacked holds a line; a core before contract 1.2 -- not known, so yes.
    var leftToPack: Bool { unpacked.map { !$0.isEmpty } ?? true }
    /// The order stands longer than a day at one node now, by the core's own measure (standing, K.5).
    var stuck: Bool { (standing?.ms ?? 0) > MTBizView.dayMs }
}

extension MTBizView {
    static let dayMs: UInt64 = 86_400_000
    /// The orders standing longer than a day at one node, the longest first: the supply's summary shows them before all else.
    var stuckOrders: [Order] { orderList.filter(\.stuck).sorted { ($0.standing?.ms ?? 0) > ($1.standing?.ms ?? 0) } }
}

/// One line of goods being written: the product, its size, how many.
struct MTBizDraftLine: Identifiable, Equatable {
    var id = UUID()
    var item: String
    var size: String
    var qty: Int
    init(item: String, size: String, qty: Int) { self.item = item; self.size = size; self.qty = qty }
    init(_ l: MTBizView.Line) { item = l.item; size = l.size; qty = Int(clamping: l.qty) }
    static func lines(_ drafts: [MTBizDraftLine]) -> [MTBizView.Line] {
        drafts.compactMap { d in d.item.isEmpty ? nil : MTBizView.Line(item: d.item, size: d.size, qty: UInt64(max(1, d.qty)), coins: 0) }
    }
}

/// THE DRAFT FROM THE WORDS (6.3): what a dictated order names, matched against the organisation's own catalogue -- the
/// product, its size, the count, the node it goes to. A guess for the manager to correct, never a record by itself.
enum MTBizDraft {
    struct Read { var line: MTBizDraftLine?; var to: String? }
    static func words(_ s: String) -> [String] {
        s.lowercased().split(whereSeparator: { !($0.isLetter || $0.isNumber) }).map(String.init)
    }
    /// Two words agree when they are equal, or both four letters or longer with the same first four: a place's name
    /// survives its endings («Tverskaya» heard as «Tverskoy»).
    static func agree(_ a: String, _ b: String) -> Bool {
        if a == b { return true }
        guard 4 <= a.count, 4 <= b.count else { return false }
        return a.prefix(4) == b.prefix(4)
    }
    static func score(_ name: String, _ heard: [String]) -> Int {
        words(name).filter { w in 3 <= w.count && heard.contains { agree(w, $0) } }.count
    }
    static func read(_ text: String, view v: MTBizView) -> Read {
        let heard = words(text)
        let item = v.itemList.filter(\.active).map { ($0, score($0.title, heard)) }.filter { 0 < $0.1 }.max { $0.1 < $1.1 }?.0
        let size = item?.sizeList.first { s in heard.contains(s.lowercased()) } ?? ""
        let qty = heard.compactMap { Int($0) }.first { String($0) != size && (1...9999).contains($0) } ?? 1
        let to = v.nodeList.filter(\.active).map { ($0, score($0.name + " " + $0.place, heard)) }.filter { 0 < $0.1 }.max { $0.1 < $1.1 }?.0.id
        return Read(line: item.map { MTBizDraftLine(item: $0.id, size: size, qty: qty) }, to: to)
    }
}

/// THE VOICE OF AN ORDER (6.3): the Messenger's own tape (VoiceRecorder) records it, the note is kept in the person's
/// Business folder under its own hash, and the platform's recognizer hears it on this phone only
/// (requiresOnDeviceRecognition). A phone that cannot hear this language on the device says so in one honest line, and the
/// order is written by hand; nothing of the sound goes anywhere.
@MainActor
final class MTBizVoice: ObservableObject {
    @Published private(set) var recording = false
    @Published private(set) var hearing = false
    @Published private(set) var heard = ""
    @Published var refusal: String?
    private(set) var hash: String?
    var org = ""   // the organisation the note is kept under (its own media folder)
    private let tape = VoiceRecorder()
    private var recognizer: SFSpeechRecognizer?
    private var task: SFSpeechRecognitionTask?

    func toggle() {
        if recording { finish() } else { begin() }
    }
    private func begin() {
        refusal = nil
        tape.onFailure = { [weak self] in
            Task { @MainActor in
                self?.recording = false
                self?.refusal = "The microphone is off for this app. Enter the order by hand."
            }
        }
        recording = true
        tape.start()
    }
    private func finish() {
        recording = false
        guard let got = tape.stop() else { return }
        let rec = voiceRecordURL(got.0)
        let src = FileManager.default.fileExists(atPath: rec.path) ? rec : voiceFileURL(got.0)
        guard let data = try? Data(contentsOf: src) else { return }
        try? FileManager.default.removeItem(at: src)
        let h = MTBizPlace.hash(data)
        let name = "voice-" + h + ".m4a"   // NOT-UI: a file name
        guard let kept = MTBizPlace.keep(org, name, data) else { return }
        hash = h
        MontanaP2PTrace.mark("biz_voice", "kept bytes=\(data.count)")
        hear(kept)
    }
    private func hear(_ url: URL) {
        guard let r = SFSpeechRecognizer(locale: MTLanguage.locale), r.supportsOnDeviceRecognition else {
            refusal = "This phone cannot hear this language on the device. Enter the order by hand."
            MontanaP2PTrace.mark("biz_voice", "no on-device recognition")
            return
        }
        recognizer = r
        hearing = true
        SFSpeechRecognizer.requestAuthorization { status in
            Task { @MainActor in self.listen(url, allowed: status == .authorized) }
        }
    }
    private func listen(_ url: URL, allowed: Bool) {
        guard allowed, let r = recognizer else {
            hearing = false
            refusal = "Speech recognition is off for this app. Enter the order by hand."
            return
        }
        let q = SFSpeechURLRecognitionRequest(url: url)
        q.requiresOnDeviceRecognition = true   // the sound is heard on this phone, never sent (the checklist 6.3)
        q.shouldReportPartialResults = false
        task = r.recognitionTask(with: q) { result, error in
            let words = result.flatMap { $0.isFinal ? $0.bestTranscription.formattedString : nil }
            let failed = error != nil
            Task { @MainActor in
                if let words {
                    self.hearing = false
                    self.heard = words
                    MontanaP2PTrace.mark("biz_voice", "heard chars=\(words.count)")
                } else if failed, self.hearing {
                    self.hearing = false
                    self.refusal = "The order could not be heard. Enter it by hand."
                }
            }
        }
    }
}

/// The goods of an order, a place or a counter: one row per line, the product, its size and how many; a line is added by
/// the row under them and taken away by the row's own swipe.
struct MTBizLinesSection: View {
    @Binding var lines: [MTBizDraftLine]
    let items: [MTBizView.Item]
    var body: some View {
        Section {
            ForEach($lines) { $line in
                VStack(alignment: .leading, spacing: 6) {
                    Picker("Product", selection: $line.item) {
                        Text("Choose").tag("")
                        ForEach(items) { i in
                            // USER-DATA: a product's name, as the administrator wrote it
                            Text(verbatim: i.title).tag(i.id)
                        }
                    }
                    let sizes = items.first { $0.id == line.item }?.sizeList ?? []
                    if !sizes.isEmpty {
                        Picker("Size", selection: $line.size) {
                            Text("No size").tag("")
                            ForEach(sizes, id: \.self) { s in
                                // USER-DATA: a size, as the administrator wrote it
                                Text(verbatim: s).tag(s)
                            }
                        }
                    }
                    Stepper(value: $line.qty, in: 1...9999) {
                        HStack {
                            Text("Quantity")
                            Spacer()
                            // USER-DATA: how many
                            Text(verbatim: String(line.qty)).monospacedDigit().foregroundColor(.gray)
                        }
                    }
                }
                .padding(.vertical, 4)
            }
            .onDelete { lines.remove(atOffsets: $0) }
            Button { lines.append(MTBizDraftLine(item: "", size: "", qty: 1)) } label: {
                MTBizRowLabel(glyph: "plus.circle.fill", title: "Add a line")
            }
        } header: { Text("Goods") }
        .listRowBackground(MTGlassRowPlate())
    }
}

// -- the organisation's page: the supply section ------------------------------------------------------------------------

extension MTBizOrgPage {
    /// SUPPLY on the organisation's page: the orders the person stands on, a new one, the stock of their nodes, and for the
    /// administrators the catalogue -- the products and the nodes with their people.
    @ViewBuilder func supply(_ v: MTBizView) -> some View {
        let boss = MTBusiness.boss(v.me.role)
        if boss || !v.orderList.isEmpty || !v.keptNodes.isEmpty {
            Section {
                ForEach(v.stuckOrders) { o in
                    NavigationLink { MTBizOrderPage(org: org, order: o.id) } label: { MTBizOrderRow(order: o, view: v) }
                }
                NavigationLink { MTBizOrdersPage(org: org) } label: {
                    MTBizRowLabel(glyph: "shippingbox.fill", title: "Orders",
                                  detail: String(v.orderList.filter { $0.open && $0.state != "delivered" }.count))
                }
                if v.may("order") {
                    if let why = v.orderRefusal {
                        MTBizWhyNot(why)
                    } else {
                        NavigationLink { MTBizOrderDraftPage(org: org) } label: { MTBizRowLabel(glyph: "square.and.pencil", title: "New order") }
                    }
                }
                if boss || !v.stockList.isEmpty {
                    NavigationLink { MTBizStockPage(org: org) } label: { MTBizRowLabel(glyph: "chart.bar.fill", title: "Stock by node") }
                }
                if v.may("item") {
                    NavigationLink { MTBizProductsPage(org: org) } label: { MTBizRowLabel(glyph: "tag.fill", title: "Products") }
                }
                if v.may("node") {
                    NavigationLink { MTBizNodesPage(org: org) } label: {
                        MTBizRowLabel(glyph: "point.3.connected.trianglepath.dotted", title: "Nodes")
                    }
                }
            } header: { Text("Supply") }
            .listRowBackground(MTGlassRowPlate())
        }
    }
}

// -- the orders ---------------------------------------------------------------------------------------------------------

/// THE ORDERS THE PERSON STANDS ON (the core shows each to the people of its path and to the administrators), the open ones
/// first, the newest first.
struct MTBizOrdersPage: View {
    let org: String
    @ObservedObject private var biz = MTBusiness.shared
    private var v: MTBizView? { biz.views[org] }

    var body: some View {
        List {
            if let v {
                let all = v.orderList.sorted { $0.at_ms > $1.at_ms }
                // The orders standing longer than a day at one node come first, the longest first; then the rest, newest first.
                let rest = all.filter { $0.open && $0.state != "delivered" && !$0.stuck }
                // Then those waiting for this person's step, then the rest -- a storekeeper finds their own work first.
                let open = v.stuckOrders + rest.filter { MTBizOrderRights(v, $0).yours } + rest.filter { !MTBizOrderRights(v, $0).yours }
                let closed = all.filter { !($0.open && $0.state != "delivered") }
                Section {
                    if open.isEmpty {
                        Text("No open orders").foregroundColor(.gray).frame(minHeight: 44)
                    }
                    ForEach(open) { o in
                        NavigationLink { MTBizOrderPage(org: org, order: o.id) } label: { MTBizOrderRow(order: o, view: v) }
                    }
                    if v.may("order") {
                        if let why = v.orderRefusal {
                            MTBizWhyNot(why)
                        } else {
                            NavigationLink { MTBizOrderDraftPage(org: org) } label: { MTBizRowLabel(glyph: "square.and.pencil", title: "New order") }
                        }
                    }
                } header: { Text("In progress") }
                .listRowBackground(MTGlassRowPlate())
                if !closed.isEmpty {
                    Section {
                        ForEach(closed) { o in
                            NavigationLink { MTBizOrderPage(org: org, order: o.id) } label: { MTBizOrderRow(order: o, view: v) }
                        }
                    } header: { Text("Finished") }
                    .listRowBackground(MTGlassRowPlate())
                }
            }
        }
        .mtBizPage("Orders")
        .onAppear { biz.redraw() }
    }
}

/// An order in the list: its route, its state and goods, the moment it was written.
struct MTBizOrderRow: View {
    let order: MTBizView.Order
    let view: MTBizView
    var body: some View {
        HStack(spacing: 12) {
            MTBizGlyph(order.source == "voice" ? "waveform" : "doc.text.fill", said: order.source == "voice" ? "By voice" : "In writing")
            VStack(alignment: .leading, spacing: 2) {
                // USER-DATA: the order's two nodes, as the administrators named them
                Text(verbatim: view.nodeName(order.from) + " → " + view.nodeName(order.to)).foregroundColor(.white)
                HStack(spacing: 4) {
                    Text(MTBizSupplyText.state(order.state))
                    // USER-DATA: the order's goods, as the catalogue names them
                    Text(verbatim: "· " + view.linesText(order.lines))
                }
                .font(.caption).foregroundColor(.gray)
                if order.stuck, let s = order.standing {
                    MTBizNote(glyph: "hourglass", text: Text("Stands at \(view.nodeName(s.node)) for \(MTBizText.span(s.ms))"))
                }
                if MTBizOrderRights(view, order).yours {
                    MTBizNote(glyph: "hand.point.right.fill", text: Text("Waits for your step"))
                }
            }
            Spacer()
            // USER-DATA: the moment the order was written, in the system's words
            Text(verbatim: MTBizText.when(order.at_ms)).font(.caption2).foregroundColor(.gray)
        }
        .frame(minHeight: 44)
        .accessibilityElement(children: .combine)
        .contentShape(Rectangle())
    }
}

/// A NEW ORDER (6.3): by voice or by hand. The voice is recorded and heard on this phone; what it names fills the draft --
/// the product, its size, how many, where it goes -- and the manager corrects it and confirms with the checkmark. The order
/// carries source 2, the hash of the voice note and its transcription; by hand, source 1 and the words as written.
struct MTBizOrderDraftPage: View {
    let org: String
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var biz = MTBusiness.shared
    @StateObject private var voice = MTBizVoice()
    @State private var from = ""
    @State private var to = ""
    @State private var lines: [MTBizDraftLine] = []
    @State private var text = ""
    @State private var word: String?
    private var v: MTBizView? { biz.views[org] }

    var body: some View {
        List {
            if let v {
                Section {
                    Button { voice.toggle() } label: {
                        HStack(spacing: 12) {
                            MTBizGlyph(voice.recording ? "stop.circle.fill" : "mic.fill")
                            Text(voice.recording ? "Stop and hear the order" : "Dictate the order").foregroundColor(.white)
                            Spacer()
                            if voice.hearing { ProgressView().tint(.white) }
                        }
                        .frame(minHeight: 44)
                        .accessibilityElement(children: .combine)
                        .contentShape(Rectangle())
                    }
                    .disabled(voice.hearing)
                    if let r = voice.refusal {
                        Text(LocalizedStringKey(r)).font(.footnote).foregroundColor(.gray)
                    }
                } header: { Text("By voice") } footer: {
                    Text("The order is heard on this phone only. The sound stays here; the order keeps its fingerprint.")
                }
                .listRowBackground(MTGlassRowPlate())
                Section {
                    if v.nodeList.filter(\.active).count < 2 {
                        Text("An order needs two nodes. An administrator adds them under Nodes.").foregroundColor(.gray).frame(minHeight: 44)
                    }
                    Picker("Where from", selection: $from) { nodeChoices(v.nodeList.filter(\.active)) }
                    Picker("Where to", selection: $to) { nodeChoices(destinations(v)) }
                } header: { Text("Route") }
                .listRowBackground(MTGlassRowPlate())
                MTBizLinesSection(lines: $lines, items: v.itemList.filter(\.active))
                Section {
                    TextField("Words of the order", text: $text, axis: .vertical).lineLimit(2...6)
                }
                .listRowBackground(MTGlassRowPlate())
            }
        }
        .mtBizPage("New order")
        .toolbar { ToolbarItem(placement: .topBarTrailing) { MontanaDoneMark { place() } } }
        .mtBizWord($word)
        .onAppear { start() }
        .onChange(of: voice.heard) { _, t in fill(t) }
    }

    @ViewBuilder private func nodeChoices(_ nodes: [MTBizView.Node]) -> some View {
        Text("Choose").tag("")
        ForEach(nodes) { n in
            // USER-DATA: a node's name, as the administrators wrote it
            Text(verbatim: n.name).tag(n.id)
        }
    }
    /// Where an order may go: anywhere for a manager or an administrator; for anyone else, the nodes they stand on.
    private func destinations(_ v: MTBizView) -> [MTBizView.Node] {
        let all = v.nodeList.filter(\.active)
        let role = MTBizRole(word: v.me.role)
        return role == .employee ? all.filter(\.mine) : all
    }
    private func start() {
        voice.org = org
        guard let v, from.isEmpty, to.isEmpty else { return }
        let active = v.nodeList.filter(\.active)
        let shop = MTBizNodeKind.shop.word
        to = destinations(v).first { $0.kind == shop && $0.mine }?.id ?? destinations(v).first { $0.kind == shop }?.id ?? ""
        from = active.first { $0.kind != shop && $0.id != to }?.id ?? ""
        if lines.isEmpty { lines = [MTBizDraftLine(item: "", size: "", qty: 1)] }
    }
    private func fill(_ t: String) {
        guard !t.isEmpty, let v else { return }
        text = t
        let d = MTBizDraft.read(t, view: v)
        if let line = d.line { lines = [line] }
        if let n = d.to, n != from { to = n }
    }
    private func place() {
        let ls = MTBizDraftLine.lines(lines)
        guard !from.isEmpty, !to.isEmpty, from != to, !ls.isEmpty else {
            word = "Choose two different nodes and at least one product."
            return
        }
        let t = String(text.trimmingCharacters(in: .whitespacesAndNewlines).prefix(4096))
        Task {
            if await MTBusiness.shared.order(org, from: from, to: to, lines: ls, voice: voice.hash, text: t) != nil {
                dismiss()
            } else {
                word = "The organization did not take this. Check your rights in it."
            }
        }
    }
}

/// What one may do on an order's page, read once from the view: every act stands only where the core would take it.
struct MTBizOrderRights {
    var confirm = false, pack = false, accept = false, scan = false, shelf = false, sale = false, issue = false, cancel = false
    var handOver: [MTBizView.Place] = []
    init(_ v: MTBizView, _ o: MTBizView.Order) {
        // A cancelled order refuses only its confirmation, its packing and a second cancel (supply.rs: open_order); a place
        // already on its way still arrives, is scanned, shelved, sold and argued about -- else it stays «on the way» forever.
        let boss = MTBusiness.boss(v.me.role)
        let kept = !v.keptNodes.isEmpty
        let onPath = o.path.contains { v.keeps($0) }
        confirm = o.open && o.state == "new" && v.may("confirm") && v.keeps(o.from)
        // Goods enter the road where the order takes them from (supply.rs Pack: not_supplier), at an open node.
        // Nothing left to pack (the core's unpacked, empty) -- no packing is offered; a core before 1.2 says nothing of it.
        pack = o.open && o.state != "new" && v.may("pack") && v.keptNodes.contains { $0.id == o.from } && o.leftToPack
        handOver = v.may("handoff") ? o.places.filter { v.keeps($0.holder) } : []
        accept = v.may("accept") && o.places.contains { v.keeps($0.transit_to) }
        scan = v.may("scan") && kept && !o.places.isEmpty
        shelf = v.may("shelf") && !o.places.isEmpty && !v.counterNodes(o, sale: false).isEmpty
        sale = v.may("sale") && !o.places.isEmpty && !v.counterNodes(o, sale: true).isEmpty
        issue = v.may("issue") && (boss || o.author == v.me.member || onPath)
        cancel = o.open && v.may("cancel") && (boss || o.author == v.me.member)
        // Waiting for the people of a node, not for every administrator who may act anywhere: «yours» reads the nodes one
        // staffs (the view's mine).
        let staffs = { (n: String?) -> Bool in n.flatMap { v.node($0)?.mine } == true }
        yours = (confirm && staffs(o.from)) || (pack && (o.unpacked == nil ? o.places.isEmpty : true) && staffs(o.from))
            || (accept && o.places.contains { staffs($0.transit_to) })
            || handOver.contains { $0.holder != o.to && staffs($0.holder) }
    }
    var any: Bool { confirm || pack || accept || scan || shelf || sale || issue || cancel || !handOver.isEmpty }
    /// The order's way waits for this person: to confirm it, to pack its first place, to hand a place on, to accept one coming
    /// to their node. The steps one may always take (scan, the counter, a problem, a cancel) wait for nobody.
    var yours = false
}

/// AN ORDER'S PAGE (6.5): its route and goods, every place with where it stands (its keeper's own word) or its way, the
/// steps the person may take on it, and the order's TimeChain -- each step with its moment, its signer and the time it stood
/// after the step before.
struct MTBizOrderPage: View {
    let org: String
    let order: String
    @ObservedObject private var biz = MTBusiness.shared
    @State private var acting: MTBizStepAct?
    @State private var labelFor: String?   // a place just packed, whose label comes up once its sheet has gone
    @State private var refused = false
    private var v: MTBizView? { biz.views[org] }

    var body: some View {
        List {
            if let v, let o = v.order(order) {
                head(v, o)
                if !o.places.isEmpty { places(v, o) }
                let rights = MTBizOrderRights(v, o)
                if rights.any { acts(v, o, rights) }
                timeline(v, o)
            }
        }
        .mtBizPage("Order")
        .sheet(item: $acting, onDismiss: labelPacked) { a in MTBizStepSheet(org: org, order: order, act: a, packed: { labelFor = $0 }) }
        .mtBizRefused($refused)
        .onAppear { biz.redraw() }
    }

    @ViewBuilder private func head(_ v: MTBizView, _ o: MTBizView.Order) -> some View {
        Section {
            HStack(spacing: 12) {
                MTBizGlyph("point.topleft.down.to.point.bottomright.curvepath.fill")
                VStack(alignment: .leading, spacing: 2) {
                    // USER-DATA: the order's two nodes, as the administrators named them
                    Text(verbatim: v.nodeName(o.from) + " → " + v.nodeName(o.to)).font(.headline).foregroundColor(.white)
                    Text(MTBizSupplyText.state(o.state)).font(.caption).foregroundColor(.gray)
                }
                Spacer()
            }
            .frame(minHeight: 44)
            .accessibilityElement(children: .combine)
            ForEach(Array(o.lines.enumerated()), id: \.offset) { _, l in
                // USER-DATA: one line of the order's goods
                Text(verbatim: v.lineText(l)).foregroundColor(.white).frame(minHeight: 44, alignment: .leading)
            }
            if !o.text.isEmpty {
                Label {
                    // USER-DATA: the order's words, as the manager wrote or said them
                    Text(verbatim: o.text).foregroundColor(.white)
                } icon: { Image(systemName: o.source == "voice" ? "waveform" : "text.quote").foregroundColor(.white) }
            }
        }
        .listRowBackground(MTGlassRowPlate())
    }

    @ViewBuilder private func places(_ v: MTBizView, _ o: MTBizView.Order) -> some View {
        Section {
            ForEach(o.places) { p in
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 8) {
                        Image(systemName: "shippingbox.fill").foregroundColor(.white)
                        // USER-DATA: the place's tag, the first letters of its code
                        Text(verbatim: MTBizSupplyText.tag(p.id)).font(.body.monospaced()).foregroundColor(.white)
                        Spacer()
                        if let c = MTBizSupplyText.condition(p.condition) { Text(c).font(.caption).foregroundColor(.gray) }
                    }
                    if let h = p.holder {
                        Label { Text("Where now: \(v.nodeName(h))") } icon: { Image(systemName: "mappin.circle.fill") }
                            .font(.subheadline).foregroundColor(.white)
                    } else if let a = p.transit_from, let b = p.transit_to {
                        Label { Text("On the way: \(v.nodeName(a)) → \(v.nodeName(b))") } icon: { Image(systemName: "arrow.right.circle") }
                            .font(.subheadline).foregroundColor(.white)
                    }
                    // USER-DATA: the place's goods
                    Text(verbatim: v.linesText(p.lines)).font(.caption).foregroundColor(.gray)
                    if !p.note.isEmpty {
                        // USER-DATA: the keeper's own note at the acceptance
                        Text(verbatim: p.note).font(.caption).foregroundColor(.gray)
                    }
                }
                .padding(.vertical, 4)
                .frame(minHeight: 44)
                .accessibilityElement(children: .combine)
            }
        } header: { Text("Places") }
        .listRowBackground(MTGlassRowPlate())
    }

    @ViewBuilder private func acts(_ v: MTBizView, _ o: MTBizView.Order, _ r: MTBizOrderRights) -> some View {
        Section {
            if r.confirm {
                Button { confirm(o) } label: { MTBizRowLabel(glyph: "checkmark.circle.fill", title: "Confirm the order") }
            }
            if r.pack {
                Button { acting = .pack } label: { MTBizRowLabel(glyph: "shippingbox.fill", title: "Pack a place") }
            }
            ForEach(r.handOver) { p in
                Button { acting = .handoff(p.id) } label: {
                    MTBizRowLabel(glyph: "arrow.right.circle.fill", title: "Hand over \(MTBizSupplyText.tag(p.id))")
                }
                Button { printLabel(p.id, v, o) } label: {
                    MTBizRowLabel(glyph: "qrcode", title: "Label of \(MTBizSupplyText.tag(p.id))")
                }
            }
            if r.accept {
                Button { acting = .accept } label: { MTBizRowLabel(glyph: "tray.and.arrow.down.fill", title: "Accept a place") }
            }
            if r.scan {
                Button { acting = .scan } label: { MTBizRowLabel(glyph: "qrcode.viewfinder", title: "Scan a place") }
            }
            if r.shelf {
                Button { acting = .shelf } label: { MTBizRowLabel(glyph: "square.stack.3d.up.fill", title: "Put on the counter") }
            }
            if r.sale {
                Button { acting = .sale } label: { MTBizRowLabel(glyph: "cart.fill", title: "Record a sale") }
            }
            if r.issue {
                Button { acting = .issue } label: { MTBizRowLabel(glyph: "exclamationmark.triangle.fill", title: "Report a problem") }
            }
            if r.cancel {
                Button { acting = .cancel } label: { MTBizRowLabel(glyph: "xmark.circle.fill", title: "Cancel the order") }
            }
        } header: { Text("Steps") }
        .listRowBackground(MTGlassRowPlate())
    }

    @ViewBuilder private func timeline(_ v: MTBizView, _ o: MTBizView.Order) -> some View {
        Section {
            ForEach(Array(o.steps.enumerated()), id: \.element.id) { i, s in
                MTBizStepRow(step: s, before: i == 0 ? nil : o.steps[i - 1].at_ms, view: v)
            }
        } header: { Text("The order's TimeChain") } footer: {
            // The core's measure (K.5): the time on the way, and the longest stand at one node.
            if let way = o.way_ms {
                if let s = o.longest_stand, 0 < s.ms {
                    Text("On the way in all: \(MTBizText.span(way)) · stood longest at \(v.nodeName(s.node)): \(MTBizText.span(s.ms))")
                } else {
                    Text("On the way in all: \(MTBizText.span(way))")
                }
            }
        }
        .listRowBackground(MTGlassRowPlate())
    }

    private func confirm(_ o: MTBizView.Order) {
        Task { if await MTBusiness.shared.write(org, MTBizCommand.confirm(order: order, node: o.from)) == nil { refused = true } }
    }
    /// THE PLACE'S LABEL: its code as a QR (the place's whole tag), its short name and its route, through the platform's own
    /// share sheet -- print, save, send.
    private func printLabel(_ id: String, _ v: MTBizView, _ o: MTBizView.Order) {
        guard let img = MontanaQR.image(Data(id.utf8), side: 600, scale: 1, correction: "M") else { return }
        MTShare.present([img, MTBizSupplyText.tag(id) + "  " + v.nodeName(o.from) + " → " + v.nodeName(o.to)])
    }
    /// Once the packing sheet has gone, the new place's label comes up in the platform's own sheet: print it, save it, send it.
    private func labelPacked() {
        guard let id = labelFor, let v, let o = v.order(order) else { return }
        labelFor = nil
        printLabel(id, v, o)
    }
}

/// One step of an order's TimeChain: what was done, by whom, where, when -- and how long the order stood before it.
struct MTBizStepRow: View {
    let step: MTBizView.Step
    let before: UInt64?
    let view: MTBizView
    var body: some View {
        let k = MTBizSupplyText.step(step.kind)
        HStack(alignment: .top, spacing: 12) {
            MTBizGlyph(k.glyph).padding(.top, 2)
            VStack(alignment: .leading, spacing: 3) {
                Text(k.title).foregroundColor(.white)
                // USER-DATA: who signed the step, the node, the place, the moment
                Text(verbatim: [view.member(step.author)?.name ?? "", view.nodeName(step.node), step.place.map(MTBizSupplyText.tag) ?? "",
                                MTBizText.when(step.at_ms)].filter { !$0.isEmpty }.joined(separator: " · "))
                    .font(.caption).foregroundColor(.gray)
                if step.kind == "accept", let c = MTBizSupplyText.condition(step.detail) {
                    Text(c).font(.caption).foregroundColor(.gray)
                }
                if step.kind == "issue", let c = MTBizSupplyText.issue(step.detail) {
                    Text(c).font(.caption).foregroundColor(.gray)
                }
                if !step.lines.isEmpty {
                    // USER-DATA: the step's goods
                    Text(verbatim: view.linesText(step.lines)).font(.caption).foregroundColor(.gray)
                }
                if !step.note.isEmpty {
                    // USER-DATA: the signer's own note
                    Text(verbatim: step.note).font(.caption).foregroundColor(.gray)
                }
                if let before, before <= step.at_ms, 0 < step.at_ms - before {
                    // USER-DATA: the time the order stood before this step, in the system's words for a span
                    MTBizNote(glyph: "hourglass", text: Text(verbatim: MTBizText.span(step.at_ms - before)))
                }
            }
            Spacer()
        }
        .padding(.vertical, 4)
        .frame(minHeight: 44)
        .accessibilityElement(children: .combine)
    }
}

/// The steps a person takes on an order, each in its own sheet.
enum MTBizStepAct: Identifiable, Equatable {
    case pack, handoff(String), accept, scan, shelf, sale, issue, cancel
    var id: String {
        switch self {
        case .pack: return "pack"
        case .handoff(let p): return "handoff:" + p
        case .accept: return "accept"
        case .scan: return "scan"
        case .shelf: return "shelf"
        case .sale: return "sale"
        case .issue: return "issue"
        case .cancel: return "cancel"
        }
    }
    var title: LocalizedStringKey {
        switch self {
        case .pack: return "Pack a place"
        case .handoff: return "Hand over"
        case .accept: return "Accept a place"
        case .scan: return "Scan a place"
        case .shelf: return "Put on the counter"
        case .sale: return "Record a sale"
        case .issue: return "Report a problem"
        case .cancel: return "Cancel the order"
        }
    }
    /// Accepting and scanning begin at the place's own label: the platform's scanner reads its code first.
    var scansFirst: Bool { self == .accept || self == .scan }
}

/// ONE STEP OF AN ORDER: the node it stands on, the goods it names, the place it is about -- read by the scanner from the
/// place's label where the step begins there -- the condition, the note and the photo of an acceptance (in the chain only
/// the photo's hash; the photo stays on this phone).
struct MTBizStepSheet: View {
    let org: String
    let order: String
    let act: MTBizStepAct
    /// A place just packed: its tag goes back to the order's page, which hands its label at once (the next thing a storekeeper
    /// does -- the next node accepts it by that label).
    var packed: ((String) -> Void)? = nil
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var biz = MTBusiness.shared
    @State private var misread: LocalizedStringKey?
    @State private var node = ""
    @State private var lines: [MTBizDraftLine] = []
    @State private var place = ""
    @State private var condition = 1
    @State private var kind = 1
    @State private var note = ""
    @State private var photo: PhotosPickerItem?
    @State private var photoHash: String?
    @State private var refused = false
    private var v: MTBizView? { biz.views[org] }
    private var o: MTBizView.Order? { v?.order(order) }
    private var scanning: Bool { act.scansFirst && place.isEmpty }

    var body: some View {
        NavigationStack {
            Group {
                if scanning {
                    MTBizScanner(prompt: misread ?? "Point at the place's label") { code in found(code) }
                } else {
                    form
                }
            }
            .navigationTitle(act.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { MontanaCloseMark { dismiss() } }
                if !scanning {
                    ToolbarItem(placement: .topBarTrailing) { MontanaDoneMark { done() } }
                }
            }
            .mtBizRefused($refused)
            .onAppear { prepare() }
            .onChange(of: photo) { _, item in keep(item) }
        }
        .preferredColorScheme(.dark)
    }

    @ViewBuilder private var form: some View {
        List {
            if let v, let o {
                switch act {
                case .pack:
                    Section {
                        // USER-DATA: the node the order takes its goods from, the only one a place is packed at
                        LabeledContent { Text(verbatim: v.nodeName(o.from)) } label: { Text("Packed at") }
                    }
                    .listRowBackground(MTGlassRowPlate())
                    MTBizLinesSection(lines: $lines, items: v.itemList.filter(\.active))
                case .handoff(let p):
                    let holder = o.places.first { $0.id == p }?.holder
                    Section {
                        placeRow(p)
                        nodePicker("Where to", v.nodeList.filter { $0.active && $0.id != holder })
                    } footer: { Text("The place is on its way until the next node accepts it.") }
                    .listRowBackground(MTGlassRowPlate())
                case .accept:
                    Section {
                        placeRow(place)
                        Picker("Condition", selection: $condition) {
                            Text("Whole").tag(1)
                            Text("Damaged").tag(2)
                            Text("Short").tag(3)
                        }
                        .pickerStyle(.segmented)
                        TextField("Note", text: $note, axis: .vertical)
                        PhotosPicker(selection: $photo, matching: .images) {
                            MTBizRowLabel(glyph: photoHash == nil ? "camera.fill" : "checkmark.circle.fill",
                                          title: photoHash == nil ? "Add a photo" : "Photo added")
                        }
                    } footer: { Text("Only the photo's fingerprint goes into the order's TimeChain; the photo stays on this phone.") }
                    .listRowBackground(MTGlassRowPlate())
                case .scan:
                    Section {
                        placeRow(place)
                        nodePicker("At", v.keptNodes)
                    }
                    .listRowBackground(MTGlassRowPlate())
                case .shelf, .sale:
                    Section { nodePicker("At", v.counterNodes(o, sale: act == .sale)) }.listRowBackground(MTGlassRowPlate())
                    MTBizLinesSection(lines: $lines, items: v.itemList)
                case .issue:
                    Section {
                        Picker("Problem", selection: $kind) {
                            Text("Return").tag(1)
                            Text("Defect").tag(2)
                            Text("Loss").tag(3)
                        }
                        Picker("Place", selection: $place) {
                            Text("The whole order").tag("")
                            ForEach(o.places) { p in
                                // USER-DATA: a place's tag
                                Text(verbatim: MTBizSupplyText.tag(p.id)).tag(p.id)
                            }
                        }
                        TextField("Note", text: $note, axis: .vertical)
                    }
                    .listRowBackground(MTGlassRowPlate())
                case .cancel:
                    Section {
                        TextField("Note", text: $note, axis: .vertical)
                    } footer: { Text("A cancelled order is not packed any more; a place already on its way still arrives.") }
                    .listRowBackground(MTGlassRowPlate())
                }
            }
        }
        .scrollContentBackground(.hidden)
        .montanaPageGround()
    }

    @ViewBuilder private func nodePicker(_ title: LocalizedStringKey, _ nodes: [MTBizView.Node]) -> some View {
        Picker(title, selection: $node) {
            Text("Choose").tag("")
            ForEach(nodes) { n in
                // USER-DATA: a node's name, as the administrators wrote it
                Text(verbatim: n.name).tag(n.id)
            }
        }
    }
    private func placeRow(_ p: String) -> some View {
        HStack(spacing: 12) {
            MTBizGlyph("shippingbox.fill")
            // USER-DATA: the place's tag
            Text(verbatim: MTBizSupplyText.tag(p)).font(.body.monospaced()).foregroundColor(.white)
            Spacer()
        }
        .frame(minHeight: 44)
        .accessibilityElement(children: .combine)
    }

    /// The defaults a step starts from: the node the person keeps on the order's way, the goods the order names.
    private func prepare() {
        guard let v, let o, node.isEmpty else { return }
        switch act {
        case .pack:
            node = o.from
            // What no place holds yet, as the core counts it (contract 1.2); a core before it -- every line of the order.
            if let left = o.unpacked {
                lines = left.map { MTBizDraftLine(item: $0.item, size: $0.size, qty: Int(clamping: $0.qty)) }
            } else {
                lines = o.lines.map { MTBizDraftLine($0) }
            }
        case .handoff(let p):
            let holder = o.places.first { $0.id == p }?.holder
            node = holder == o.to ? "" : o.to
        case .shelf, .sale:
            let at = v.counterNodes(o, sale: act == .sale)
            node = at.contains { $0.id == o.to } ? o.to : (at.first?.id ?? "")
            lines = o.lines.map { MTBizDraftLine($0) }
        default: break
        }
    }
    /// A code the scanner read: a place of this order -- for an acceptance, one on its way to a node this person keeps. Any
    /// other code is said in the scanner's own line, and the scanner keeps looking: a storekeeper holding the wrong box is
    /// never left before a camera that does nothing.
    private func found(_ code: String) {
        guard let v, let o else { return }
        let c = code.lowercased()
        guard let p = o.places.first(where: { $0.id == c }) else { misread = "This code is not a place of this order."; return }
        if act == .accept {
            guard let to = p.transit_to, v.keeps(to) else { misread = "This place is not on its way to your node."; return }
            node = to
        } else {
            node = p.holder.flatMap { v.keeps($0) ? $0 : nil } ?? (v.keptNodes.first?.id ?? "")
        }
        place = c
    }
    /// The acceptance's photo: kept in the person's Business folder under its own hash; the chain gets the hash alone.
    private func keep(_ item: PhotosPickerItem?) {
        guard let item else { return }
        Task {
            guard let data = try? await item.loadTransferable(type: Data.self) else { return }
            let h = MTBizPlace.hash(data)
            await MainActor.run {
                if MTBizPlace.keep(org, "photo-" + h, data) != nil { photoHash = h }   // NOT-UI: a file name
            }
        }
    }
    private func done() {
        guard let o else { return }
        let ls = MTBizDraftLine.lines(lines)
        let words = String(note.trimmingCharacters(in: .whitespacesAndNewlines).prefix(1024))
        var cmd: [String: Any]? = nil
        var newPlace: String?
        switch act {
        case .pack:
            if !node.isEmpty, !ls.isEmpty, let tag = MTBusiness.freshTag() {
                cmd = MTBizCommand.pack(order: order, place: tag, node: node, lines: ls)
                newPlace = tag
            }
        case .handoff(let p):
            if !node.isEmpty, let holder = o.places.first(where: { $0.id == p })?.holder {
                cmd = MTBizCommand.handoff(order: order, place: p, from: holder, to: node)
            }
        case .accept:
            if !place.isEmpty, !node.isEmpty {
                cmd = MTBizCommand.accept(order: order, place: place, node: node, condition: condition, note: words, photo: photoHash)
            }
        case .scan:
            if !place.isEmpty, !node.isEmpty { cmd = MTBizCommand.scan(order: order, place: place, node: node) }
        case .shelf:
            if !node.isEmpty, !ls.isEmpty { cmd = MTBizCommand.shelf(order: order, node: node, lines: ls) }
        case .sale:
            if !node.isEmpty, !ls.isEmpty { cmd = MTBizCommand.sale(order: order, node: node, lines: ls) }
        case .issue:
            cmd = MTBizCommand.issue(order: order, place: place.isEmpty ? nil : place, kind: kind, note: words)
        case .cancel:
            cmd = MTBizCommand.cancel(order: order, note: words)
        }
        guard let cmd else { refused = true; return }
        Task {
            if await MTBusiness.shared.write(org, cmd) != nil {
                if let newPlace { packed?(newPlace) }
                dismiss()
            } else {
                refused = true
            }
        }
    }
}

// -- the stock ----------------------------------------------------------------------------------------------------------

/// THE STOCK BY NODE (6.6): for every node the person keeps (every node for an administrator), each product and size --
/// on hand in places, on the counter, sold.
struct MTBizStockPage: View {
    let org: String
    @ObservedObject private var biz = MTBusiness.shared
    private var v: MTBizView? { biz.views[org] }

    var body: some View {
        List {
            if let v {
                if v.stockList.isEmpty {
                    Section { Text("Nothing is counted yet").foregroundColor(.gray).frame(minHeight: 44) }
                        .listRowBackground(MTGlassRowPlate())
                }
                ForEach(v.nodeList.filter { n in v.stockList.contains { $0.node == n.id } }) { n in
                    Section {
                        ForEach(v.stockList.filter { $0.node == n.id }) { s in
                            HStack(spacing: 10) {
                                // USER-DATA: the product and its size
                                Text(verbatim: [v.item(s.item)?.title ?? "", s.size].filter { !$0.isEmpty }.joined(separator: " · "))
                                    .foregroundColor(.white)
                                Spacer()
                                count("shippingbox", s.on_hand, "On hand")
                                count("square.stack.3d.up", s.counter, "On the counter")
                                count("cart", s.sold, "Sold")
                            }
                            .frame(minHeight: 44)
                            .accessibilityElement(children: .combine)
                        }
                    } header: {
                        // USER-DATA: the node's name
                        Text(verbatim: n.name)
                    } footer: { Text("In places on hand, on the counter, sold.") }
                    .listRowBackground(MTGlassRowPlate())
                }
            }
        }
        .mtBizPage("Stock by node")
        .onAppear { biz.redraw() }
    }
    private func count(_ glyph: String, _ n: UInt64, _ name: LocalizedStringKey) -> some View {
        // USER-DATA: a count of units
        MTBizNote(glyph: glyph, text: Text(verbatim: String(n)).monospacedDigit())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(name))
        .accessibilityValue(Text(verbatim: String(n)))   // USER-DATA: a count of units
    }
}

// -- the catalogue (administrators) -------------------------------------------------------------------------------------

/// THE PRODUCTS (administrators): every product with its sizes and its unit; a product withdrawn stays in the old orders and
/// takes no new one.
struct MTBizProductsPage: View {
    let org: String
    @ObservedObject private var biz = MTBusiness.shared
    @State private var adding = false
    private var v: MTBizView? { biz.views[org] }

    var body: some View {
        List {
            if let v {
                Section {
                    ForEach(v.itemList) { i in
                        HStack(spacing: 12) {
                            MTBizGlyph("tag.fill")
                            VStack(alignment: .leading, spacing: 2) {
                                // USER-DATA: the product's name, as the administrator wrote it
                                Text(verbatim: i.title).foregroundColor(i.active ? .white : .gray)
                                // USER-DATA: its sizes and its unit
                                Text(verbatim: [i.sizes, i.unit].filter { !$0.isEmpty }.joined(separator: " · ")).font(.caption).foregroundColor(.gray)
                            }
                            Spacer()
                            if !i.active { Text("Withdrawn").font(.caption).foregroundColor(.gray) }
                        }
                        .frame(minHeight: 44)
                        .accessibilityElement(children: .combine)
                        .swipeActions {
                            Button(role: i.active ? .destructive : nil) {
                                Task { await MTBusiness.shared.write(org, MTBizCommand.item(i.id, title: i.title, sizes: i.sizes, unit: i.unit, active: !i.active)) }
                            } label: {
                                Label(i.active ? "Withdraw" : "Offer again", systemImage: i.active ? "xmark" : "arrow.uturn.backward")
                            }
                        }
                    }
                    Button { adding = true } label: { MTBizRowLabel(glyph: "plus.circle.fill", title: "New product") }
                } footer: { Text("Sizes are written with commas: 40, 41, 42.") }
                .listRowBackground(MTGlassRowPlate())
            }
        }
        .mtBizPage("Products")
        .sheet(isPresented: $adding) { MTBizProductSheet(org: org) }
    }
}

/// A new product: its name, its sizes, its unit.
struct MTBizProductSheet: View {
    let org: String
    @Environment(\.dismiss) private var dismiss
    @State private var title = ""
    @State private var sizes = ""
    @State private var unit = ""
    @State private var refused = false
    @FocusState private var typing: Bool
    var body: some View {
        MTBizSheet(title: "New product", done: { set() }, ready: !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty) {
            Section {
                TextField("Product name", text: $title).mtBizFirstField($typing)
                TextField("Sizes (optional)", text: $sizes)
                TextField("Unit (optional)", text: $unit)
            }
            .listRowBackground(MTGlassRowPlate())
        }
        .mtBizRefused($refused)
    }
    private func set() {
        let t = String(title.trimmingCharacters(in: .whitespacesAndNewlines).prefix(256))
        let s = String(sizes.trimmingCharacters(in: .whitespacesAndNewlines).prefix(256))
        let u = String(unit.trimmingCharacters(in: .whitespacesAndNewlines).prefix(64))
        guard !t.isEmpty, let tag = MTBusiness.freshTag() else { refused = true; return }
        Task {
            if await MTBusiness.shared.write(org, MTBizCommand.item(tag, title: t, sizes: s, unit: u, active: true)) != nil { dismiss() } else { refused = true }
        }
    }
}

/// THE NODES (administrators): suppliers, warehouses, hubs, carriers, shops -- each opens to its people.
struct MTBizNodesPage: View {
    let org: String
    @ObservedObject private var biz = MTBusiness.shared
    @State private var adding = false
    private var v: MTBizView? { biz.views[org] }

    var body: some View {
        List {
            if let v {
                Section {
                    ForEach(v.nodeList) { n in
                        NavigationLink { MTBizNodePage(org: org, node: n.id) } label: { MTBizNodeRow(node: n) }
                    }
                    Button { adding = true } label: { MTBizRowLabel(glyph: "plus.circle.fill", title: "New node") }
                } footer: { Text("A node is a place an order passes: a supplier, a warehouse, a hub, a carrier, a shop.") }
                .listRowBackground(MTGlassRowPlate())
            }
        }
        .mtBizPage("Nodes")
        .sheet(isPresented: $adding) { MTBizNodeSheet(org: org) }
    }
}

/// A node in a list: its kind's glyph, its name, its kind and place, how many people it has.
struct MTBizNodeRow: View {
    let node: MTBizView.Node
    var body: some View {
        let kind = MTBizNodeKind(word: node.kind)
        HStack(spacing: 12) {
            MTBizGlyph(kind?.glyph ?? "mappin")
            VStack(alignment: .leading, spacing: 2) {
                // USER-DATA: the node's name, as the administrators wrote it
                Text(verbatim: node.name).foregroundColor(node.active ? .white : .gray)
                // USER-DATA: the node's kind in the person's language and its place, as written
                Text(verbatim: [kind?.title ?? "", node.place].filter { !$0.isEmpty }.joined(separator: " · ")).font(.caption).foregroundColor(.gray)
            }
            Spacer()
            // USER-DATA: how many people the node has
            MTBizNote(glyph: "person.2.fill", text: Text(verbatim: String(node.staff.count)))
        }
        .frame(minHeight: 44)
        .accessibilityElement(children: .combine)
        .contentShape(Rectangle())
    }
}

/// A NODE'S PAGE: who takes its steps (node_staff) -- one switch per member of the organisation -- and its closing.
struct MTBizNodePage: View {
    let org: String
    let node: String
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var biz = MTBusiness.shared
    @State private var refused = false
    private var v: MTBizView? { biz.views[org] }

    var body: some View {
        List {
            if let v, let n = v.node(node) {
                Section { MTBizNodeRow(node: n) }.listRowBackground(MTGlassRowPlate())
                Section {
                    ForEach(v.members.filter(\.active)) { m in
                        Toggle(isOn: Binding(get: { n.staff.contains(m.member) }, set: { on in
                            Task { if await MTBusiness.shared.write(org, MTBizCommand.nodeStaff(node: node, member: m.member, on: on)) == nil { refused = true } }
                        })) {
                            // USER-DATA: a member's name
                            Text(verbatim: m.name).foregroundColor(.white)
                        }
                        .frame(minHeight: 44)
                        .accessibilityElement(children: .combine)
                    }
                } header: { Text("People of the node") } footer: {
                    Text("They take the node's steps: confirm, pack, hand over, accept, put on the counter, sell.")
                }
                .listRowBackground(MTGlassRowPlate())
                if let kind = MTBizNodeKind(word: n.kind) {
                    Section {
                        Button(role: n.active ? .destructive : nil) {
                            Task { if await MTBusiness.shared.write(org, MTBizCommand.node(node, kind: kind, name: n.name, place: n.place, active: !n.active)) == nil { refused = true } }
                        } label: {
                            MTBizRowLabel(glyph: n.active ? "xmark.circle.fill" : "arrow.uturn.backward.circle.fill",
                                          title: n.active ? "Close the node" : "Open the node again")
                        }
                    }
                    .listRowBackground(MTGlassRowPlate())
                }
            }
        }
        .mtBizPage(named: v?.node(node)?.name ?? "")
        .mtBizRefused($refused)
    }
}

/// A new node: its kind, its name, its place.
struct MTBizNodeSheet: View {
    let org: String
    @Environment(\.dismiss) private var dismiss
    @State private var kind = MTBizNodeKind.warehouse
    @State private var name = ""
    @State private var spot = ""
    @State private var refused = false
    @FocusState private var typing: Bool
    var body: some View {
        MTBizSheet(title: "New node", done: { set() }, ready: !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty) {
            Section {
                Picker("Kind", selection: $kind) {
                    ForEach(MTBizNodeKind.allCases) { k in Label(k.title, systemImage: k.glyph).tag(k) }
                }
                TextField("Node name", text: $name).mtBizFirstField($typing)
                TextField("Where it is (optional)", text: $spot)
            }
            .listRowBackground(MTGlassRowPlate())
        }
        .mtBizRefused($refused)
    }
    private func set() {
        let n = String(name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(256))
        let w = String(spot.trimmingCharacters(in: .whitespacesAndNewlines).prefix(256))
        guard !n.isEmpty, let tag = MTBusiness.freshTag() else { refused = true; return }
        Task {
            if await MTBusiness.shared.write(org, MTBizCommand.node(tag, kind: kind, name: n, place: w, active: true)) != nil { dismiss() } else { refused = true }
        }
    }
}
