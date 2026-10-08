package quest.montana.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Switch
import java.io.ByteArrayOutputStream
import java.io.File

// ════════════════════════════════════════════════════════════
// MONTANA BUSINESS: THE SUPPLY CHAIN, FROM THE ORDER TO THE COUNTER (stages 6.3-6.6, iOS MTBusinessSupply.swift page for page)
// ════════════════════════════════════════════════════════════
// The author's word 06.10.2026: «a special focus on supply -- from the moment a pair of shoes is ordered, where the managers
// write the order or record it by voice with its transcription, the whole chain of supply to every logistics node and to the
// shop's counter». Every order is a TimeChain of its own (chain S, its lane the order's key): each step is signed by the one
// who took it, and where a place stands comes only from its keeper's own Accept. The rules live in the core; these pages draw
// the core's view and hand it the contract's commands (BizCommand). The voice is heard on this phone alone (the platform's
// on-device recognizer): the sound never leaves it, and the order carries only its hash.

/** The words of the supply pages, each named once (iOS MTBizSupplyText). */
object BizSupplyText {
    fun state(s: String) = when (s) {
        "confirmed" -> R.string.biz_st_confirmed
        "packed" -> R.string.biz_st_packed
        "in_transit" -> R.string.biz_st_in_transit
        "delivered" -> R.string.biz_st_delivered
        "cancelled" -> R.string.biz_st_cancelled
        else -> R.string.biz_st_new
    }
    /** A step's glyph and its words. */
    fun step(kind: String): Pair<Int, Int> = when (kind) {
        "order" -> R.drawable.ic_set_doc to R.string.biz_sp_order
        "confirm" -> R.drawable.ic_set_check_circle to R.string.biz_sp_confirm
        "pack" -> R.drawable.ic_archive to R.string.biz_sp_pack
        "handoff" -> R.drawable.ic_send to R.string.biz_sp_handoff
        "accept" -> R.drawable.ic_arrow_circle_down to R.string.biz_sp_accept
        "scan" -> R.drawable.ic_qr_scan to R.string.biz_sp_scan
        "shelf" -> R.drawable.ic_unarchive to R.string.biz_sp_shelf
        "sale" -> R.drawable.ic_cart to R.string.biz_sp_sale
        "issue" -> R.drawable.ic_gpp_maybe to R.string.biz_sp_issue
        "cancel" -> R.drawable.ic_close to R.string.biz_sp_cancel
        else -> R.drawable.ic_dot to R.string.biz_sp_step
    }
    fun condition(c: Long): Int? = when (c) { 1L -> R.string.biz_cond_whole; 2L -> R.string.biz_cond_damaged; 3L -> R.string.biz_cond_short; else -> null }
    fun issue(k: Long): Int? = when (k) { 1L -> R.string.biz_is_return; 2L -> R.string.biz_is_defect; 3L -> R.string.biz_is_loss; else -> null }
    fun moment(ms: Long): String = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(ms))
    /** A span in the system's own words, counted as iOS MTBizText.span counts it (BizDuration.span): two units at most. */
    fun span(c: Context, ms: Long): String = told(c, BizDuration.span(ms))
    /** Time on shifts in hours and minutes, the minute cut down, as iOS MTBizText.hours (BizDuration.hours). */
    fun hours(c: Context, seconds: Long): String = told(c, BizDuration.hours(seconds))
    /** The units in the platform's abbreviated words (ICU short, iOS «abbreviated»). */
    private fun told(c: Context, units: List<Pair<Long, Long>>): String {
        fun unit(len: Long) = when (len) {
            BizDuration.DAY -> android.icu.util.MeasureUnit.DAY
            BizDuration.HOUR -> android.icu.util.MeasureUnit.HOUR
            BizDuration.MINUTE -> android.icu.util.MeasureUnit.MINUTE
            else -> android.icu.util.MeasureUnit.SECOND
        }
        return android.icu.text.MeasureFormat.getInstance(c.resources.configuration.locales[0], android.icu.text.MeasureFormat.FormatWidth.SHORT)
            .formatMeasures(*units.map { android.icu.util.Measure(it.first, unit(it.second)) }.toTypedArray())
    }
    /** A place's short name on the screen and on its label: the first eight letters of its tag. */
    fun tag(hex: String) = hex.take(8).uppercase()
}

// ─────────────────────────── the economy of time (K.5, core 3da0ada) ───────────────────────────

/** A day: an order standing at one node longer than this is named first, wherever orders are listed. */
internal const val LONG_STAND_MS = 86_400_000L
/** The orders standing now at one node longer than a day (standing.ms), the longest first. */
internal fun longStanding(v: BizView): List<BizOrder> = v.orders.filter { LONG_STAND_MS < (it.standing?.ms ?: 0L) }.sortedByDescending { it.standing?.ms ?: 0L }
/** Where an order stands and how long (K.5): «Stands at North: 3 d 2 h». */
private fun standText(c: Context, v: BizView, s: BizStand) = c.getString(R.string.biz_stands_at, v.nodeName(s.node), BizSupplyText.span(c, s.ms))   // USER-DATA: the node's name
/** The order's time in one line under its TimeChain (K.5, iOS's own phrases): its way in all, and the longest it stood at one node. */
private fun orderTime(c: Context, v: BizView, o: BizOrder): String {
    val l = o.longestStand
    return if (l.ms <= 0L) c.getString(R.string.biz_way_all, BizSupplyText.span(c, o.wayMs))
    // USER-DATA: the node's name
    else c.getString(R.string.biz_way_longest, BizSupplyText.span(c, o.wayMs), v.nodeName(l.node), BizSupplyText.span(c, l.ms))
}

/** A node's kind, by the number a Node record carries (the core's NODE_*), and the word the view names it by. */
enum class BizNodeKind(val number: Int, val word: String, val title: Int, val glyph: Int) {
    SUPPLIER(1, "supplier", R.string.biz_nk_supplier, R.drawable.ic_briefcase),
    WAREHOUSE(2, "warehouse", R.string.biz_nk_warehouse, R.drawable.ic_archive),
    HUB(3, "hub", R.string.biz_nk_hub, R.drawable.ic_hub),
    CARRIER(4, "carrier", R.string.biz_nk_carrier, R.drawable.ic_send),
    SHOP(5, "shop", R.string.biz_nk_store, R.drawable.ic_cart);
    companion object { fun of(word: String) = values().firstOrNull { it.word == word } }
}

/** One line of goods being written: the product, its size, how many (iOS MTBizDraftLine). */
class DraftLine(var item: String, var size: String, var qty: Int) {
    constructor(l: BizLine) : this(l.item, l.size, l.qty.coerceIn(1L, 9999L).toInt())
    companion object {
        fun lines(ds: List<DraftLine>) = ds.filter { it.item.isNotEmpty() }.map { BizLine(it.item, it.size, maxOf(1, it.qty).toLong(), 0) }
    }
}

/**
 * THE DRAFT FROM THE WORDS (iOS MTBizDraft, 6.3): what a dictated order names, matched against the organization's own catalogue
 * -- the product, its size, the count, the node it goes to. A guess for the manager to correct, never a record by itself.
 */
object BizDraft {
    class Read(val line: DraftLine?, val to: String?)
    fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
    /** Two words agree when they are equal, or both four letters or longer with the same first four. */
    fun agree(a: String, b: String) = a == b || (4 <= a.length && 4 <= b.length && a.take(4) == b.take(4))
    fun score(name: String, heard: List<String>) = words(name).count { w -> 3 <= w.length && heard.any { agree(w, it) } }
    fun read(text: String, v: BizView): Read {
        val heard = words(text)
        val item = v.items.filter { it.active }.map { it to score(it.title, heard) }.filter { 0 < it.second }.maxByOrNull { it.second }?.first
        val size = item?.sizeList?.firstOrNull { s -> heard.contains(s.lowercase()) } ?: ""
        val qty = heard.mapNotNull { it.toIntOrNull() }.firstOrNull { it.toString() != size && it in 1..9999 } ?: 1
        val to = v.nodes.filter { it.active }.map { it to score(it.name + " " + it.place, heard) }.filter { 0 < it.second }.maxByOrNull { it.second }?.first?.id
        return Read(item?.let { DraftLine(it.id, size, qty) }, to)
    }
}

/**
 * THE VOICE OF AN ORDER (iOS MTBizVoice, 6.3): the platform's own microphone records it (AudioRecord, 16 kHz, 16-bit, one
 * channel), the note is kept sealed in the person's Business folder under its own hash, and the platform's ON-DEVICE recognizer
 * hears that very recording (createOnDeviceSpeechRecognizer, the recording handed in as its audio source, the language's
 * on-device model asked for first). A phone without it says so in one honest line, and the order is written by hand; nothing
 * of the sound goes anywhere.
 */
class BizVoice(private val act: MainActivity, private val org: String, private val changed: () -> Unit) {
    var recording = false; private set
    var hearing = false; private set
    var heard = ""; private set
    var refusal: Int? = null
    var hash: String? = null; private set
    @Volatile private var rec: AudioRecord? = null
    private var tape: Thread? = null
    private val pcm = ByteArrayOutputStream()
    private var recognizer: SpeechRecognizer? = null

    fun toggle() { if (recording) finish() else begin() }

    private fun begin() {
        refusal = null
        if (act.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            act.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 7)
            refusal = R.string.biz_voice_mic_off
            changed()
            return
        }
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = runCatching { AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192)) }.getOrNull()
        if (r == null || r.state != AudioRecord.STATE_INITIALIZED) { r?.release(); refusal = R.string.biz_voice_mic_off; changed(); return }
        synchronized(pcm) { pcm.reset() }
        rec = r
        recording = true
        r.startRecording()
        tape = Thread {
            val buf = ByteArray(4096)
            while (rec === r) {
                val n = r.read(buf, 0, buf.size)
                if (0 < n) synchronized(pcm) { if (pcm.size() < LIMIT) pcm.write(buf, 0, n) }
            }
        }.apply { start() }
        changed()
    }

    private fun finish() {
        recording = false
        val r = rec
        rec = null
        runCatching { r?.stop() }
        tape?.join(500)
        r?.release()
        val raw = synchronized(pcm) { pcm.toByteArray() }
        if (raw.isEmpty()) { changed(); return }
        act.background {
            val wav = wav(raw)
            val h = Biz.hex(Wire.sha(wav))
            Biz.keepMedia(org, "voice-" + h + ".wav", wav) { kept ->   // NOT-UI: a file name
                if (!kept) changed() else { hash = h; hear(raw) }
            }
        }
    }

    private fun hear(raw: ByteArray) {
        if (Build.VERSION.SDK_INT < 33 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(act)) { refuse(R.string.biz_voice_no_model); return }
        val sr = SpeechRecognizer.createOnDeviceSpeechRecognizer(act)
        recognizer = sr
        hearing = true
        changed()
        val lang = act.resources.configuration.locales[0]
        val ask = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang.toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        sr.checkRecognitionSupport(ask, act.mainExecutor, object : RecognitionSupportCallback {
            override fun onSupportResult(s: RecognitionSupport) {
                // the language's model must lie on this phone already: a model only online, or only on its way, is not hearing here
                val tag = s.installedOnDeviceLanguages.firstOrNull { it.equals(lang.toLanguageTag(), true) }
                    ?: s.installedOnDeviceLanguages.firstOrNull { java.util.Locale.forLanguageTag(it).language == lang.language }
                if (tag == null) refuse(R.string.biz_voice_no_model) else listen(sr, ask.putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag), raw)
            }
            override fun onError(error: Int) { refuse(R.string.biz_voice_no_model) }
        })
    }

    private fun listen(sr: SpeechRecognizer, ask: Intent, raw: ByteArray) {
        val pipe = ParcelFileDescriptor.createPipe()
        ask.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE)
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onResults(b: Bundle?) {
                val words = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                if (words.isEmpty()) refuse(R.string.biz_voice_unheard) else { hearing = false; heard = words; done(); changed() }
            }
            override fun onError(error: Int) { refuse(R.string.biz_voice_unheard) }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(b: Bundle?) {}
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        sr.startListening(ask)
        pipe[0].close()
        Thread { runCatching { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(raw) } } }.start()
    }

    private fun refuse(word: Int) { hearing = false; refusal = word; done(); changed() }
    private fun done() { recognizer?.destroy(); recognizer = null }

    /** The page leaves: the microphone and the recognizer with it. */
    fun stop() { rec?.let { r -> rec = null; runCatching { r.stop() }; r.release() }; recording = false; done() }

    private companion object {
        const val RATE = 16_000
        /** [I-14] three minutes of an order's voice at most. */
        const val LIMIT = RATE * 2 * 180
        /** The recording as a file of its own (RIFF WAVE, PCM 16-bit, one channel): what is kept and hashed. */
        fun wav(pcm: ByteArray): ByteArray {
            val b = java.nio.ByteBuffer.allocate(44 + pcm.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16)
                .putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size).put(pcm)
            return b.array()
        }
    }
}

// ─────────────────────────── the organization's page: the supply section ───────────────────────────

/** SUPPLY on the organization's page (iOS supply): the orders, a new one, the stock, the catalogue for the administrators. */
internal fun LinearLayout.supplySection(act: MainActivity, org: String, v: BizView) {
    val c = act
    val chief = Biz.boss(v.role)
    if (!chief && v.orders.isEmpty() && v.keptNodes.isEmpty()) return
    val rows = mutableListOf<View>()
    // K.5: the orders standing longer than a day at one node, the longest first, with the node and the time
    longStanding(v).forEach { o -> rows += orderRow(c, o, v) { act.push { orderPage(act, org, o.id, it) } } }
    rows += c.bizRow(R.drawable.ic_archive, c.getString(R.string.biz_orders), chevron = true,
        detail = v.orders.count { it.open && it.state != "delivered" }.toString()) { act.push { ordersPage(act, org, it) } }
    if (v.may("order")) rows += orderBlocked(v)?.let { c.bizWhyNot(it) }
        ?: c.bizRow(R.drawable.ic_compose, c.getString(R.string.biz_new_order), chevron = true) { act.push { draftPage(act, org, it) } }
    if (chief || v.stock.isNotEmpty()) rows += c.bizRow(R.drawable.ic_hub, c.getString(R.string.biz_stock), chevron = true) { act.push { stockPage(act, org, it) } }
    if (v.may("item")) rows += c.bizRow(R.drawable.ic_badge, c.getString(R.string.biz_products), chevron = true) { act.push { productsPage(act, org, it) } }
    if (v.may("node")) rows += c.bizRow(R.drawable.ic_set_server, c.getString(R.string.biz_nodes), chevron = true) { act.push { nodesPage(act, org, it) } }
    section(c.getString(R.string.biz_supply), null, *rows.toTypedArray())
}

// ─────────────────────────── the orders ───────────────────────────

/** THE ORDERS THE PERSON STANDS ON (iOS MTBizOrdersPage): the open ones first, the newest first. */
internal fun ordersPage(act: MainActivity, org: String, onBack: () -> Unit): View {
    val sheet = BizSheet(act, act.getString(R.string.biz_orders), onBack)
    Live(act, sheet, { Biz.view(org) }) { v ->
        v ?: return@Live
        val c = act
        val all = v.orders.sortedByDescending { it.atMs }
        // K.5: the orders standing longer than a day first, the longest first; then those waiting for this person's step (a
        // storekeeper finds their own work first); then the rest, the newest first
        val long = longStanding(v)
        val rest = all.filter { it.open && it.state != "delivered" && it !in long }
        val open = long + rest.filter { OrderRights(v, it).yours } + rest.filter { !OrderRights(v, it).yours }
        val closed = all.filter { !(it.open && it.state != "delivered") }
        val rows = mutableListOf<View>()
        if (open.isEmpty()) rows += c.bizLine(null, 0, c.getString(R.string.biz_no_open_orders), titleColor = MT.gray)
        open.forEach { o -> rows += orderRow(c, o, v) { act.push { orderPage(act, org, o.id, it) } } }
        if (v.may("order")) rows += orderBlocked(v)?.let { c.bizWhyNot(it) }
            ?: c.bizRow(R.drawable.ic_compose, c.getString(R.string.biz_new_order), chevron = true) { act.push { draftPage(act, org, it) } }
        section(c.getString(R.string.biz_in_progress), null, *rows.toTypedArray())
        if (closed.isNotEmpty()) section(c.getString(R.string.biz_finished), null, *closed.map { o -> orderRow(c, o, v) { act.push { orderPage(act, org, o.id, it) } } }.toTypedArray())
    }
    return sheet.page
}

/** An order in the list (iOS MTBizOrderRow): its route, its state and goods, the moment it was written. */
private fun orderRow(c: Context, o: BizOrder, v: BizView, onTap: () -> Unit): View = c.hstack {
    setPadding(dp(16), dp(10), dp(14), dp(10)); minimumHeight = dp(48); bizWhole()
    // the glyph says what the words do not (iOS k0d): how the order was written
    addView(c.bizGlyph(if (o.source == "voice") R.drawable.ic_mic else R.drawable.ic_set_doc, Color.WHITE, 20,
        said = if (o.source == "voice") R.string.biz_by_voice else R.string.biz_in_writing), lp(sp(20), sp(20)).apply { marginEnd = dp(16) })
    addView(c.vstack(Gravity.NO_GRAVITY) {
        // USER-DATA: the order's two nodes, as the administrators named them
        addView(c.text(v.nodeName(o.from) + " → " + v.nodeName(o.to), 16f), lp())
        // USER-DATA: the order's goods, as the catalogue names them
        addView(c.text(c.getString(BizSupplyText.state(o.state)) + " · " + v.linesText(o.lines), 12f, MT.gray), lp())
        // iOS MTBizNote(glyph: "hourglass"): the glyph hidden, the words speak
        o.standing?.takeIf { LONG_STAND_MS < it.ms }?.let { s -> addView(c.hstack {
            addView(c.bizGlyph(R.drawable.ic_restore, Color.WHITE, 12), lp(sp(12), sp(12)).apply { marginEnd = dp(4) })
            addView(c.text(standText(c, v, s), 12f, MT.gray), lp(0, WRAP, 1f))
        }, lp()) }
        // «Waits for your step» (iOS MTBizNote, 1fec0318): the glyph hidden, the words speak
        if (OrderRights(v, o).yours) addView(c.hstack {
            addView(c.bizGlyph(R.drawable.ic_send, Color.WHITE, 12), lp(sp(12), sp(12)).apply { marginEnd = dp(4) })
            addView(c.text(c.getString(R.string.biz_waits_your_step), 12f, MT.gray), lp(0, WRAP, 1f))
        }, lp())
    }, lp(0, WRAP, 1f))
    // USER-DATA: the moment the order was written, in the system's words
    addView(c.text(BizSupplyText.moment(o.atMs), 11f, MT.gray), lp(WRAP, WRAP).apply { marginStart = dp(8) })
    addView(c.bizGlyph(R.drawable.ic_chevron_right, Color.rgb(89, 89, 89), 18), lp(sp(18), sp(18)).apply { marginStart = dp(6) })
    pressable(onTap)
}

/**
 * THE GOODS of an order, a place or a counter (iOS MTBizLinesSection): one block per line -- the product, its size, how many;
 * a line is added by the row under them and taken away by a long press on its own block (iOS: the row's own swipe).
 */
private fun LinearLayout.linesSection(act: MainActivity, lines: MutableList<DraftLine>, items: List<BizItem>, redraw: () -> Unit) {
    val c = act
    val rows = mutableListOf<View>()
    lines.forEach { line ->
        rows += c.vstack(Gravity.NO_GRAVITY) {
            val item = items.firstOrNull { it.id == line.item }
            // USER-DATA: a product's name, as the administrator wrote it
            addView(c.pickerRow(c.getString(R.string.biz_product), item?.title ?: c.getString(R.string.biz_choose)) {
                val ids = listOf("") + items.map { it.id }
                choose(act, c.getString(R.string.biz_product), listOf(c.getString(R.string.biz_choose)) + items.map { it.title }, ids.indexOf(line.item)) { i ->
                    line.item = ids[i]
                    if (line.size !in (items.firstOrNull { it.id == line.item }?.sizeList ?: emptyList())) line.size = ""
                    redraw()
                }
            }, lp())
            val sizes = item?.sizeList ?: emptyList()
            if (sizes.isNotEmpty()) addView(c.pickerRow(c.getString(R.string.biz_size), line.size.ifEmpty { c.getString(R.string.biz_no_size) }) {
                val all = listOf("") + sizes
                // USER-DATA: the sizes, as the administrator wrote them
                choose(act, c.getString(R.string.biz_size), listOf(c.getString(R.string.biz_no_size)) + sizes, all.indexOf(line.size)) { i -> line.size = all[i]; redraw() }
            }, lp())
            // how many: the platform's own number picker (iOS Stepper), the whole row its target
            addView(c.pickerRow(c.getString(R.string.biz_quantity), line.qty.toString()) {   // USER-DATA: how many
                val picker = android.widget.NumberPicker(c).apply { minValue = 1; maxValue = 9999; value = line.qty; wrapSelectorWheel = false }
                android.app.AlertDialog.Builder(act).setTitle(R.string.biz_quantity).setView(picker)
                    .setPositiveButton(R.string.ok) { _, _ -> line.qty = picker.value; redraw() }
                    .setNegativeButton(R.string.cancel, null).show()
            }, lp())
            setOnLongClickListener { lines.remove(line); redraw(); true }
        }
    }
    rows += c.bizRow(R.drawable.ic_plus, c.getString(R.string.biz_add_line)) { lines.add(DraftLine("", "", 1)); redraw() }
    section(c.getString(R.string.biz_goods), null, *rows.toTypedArray())
}

/** A choice of a node (iOS Picker of nodes): «Choose» and the nodes by their names. */
private fun nodePicker(act: MainActivity, title: String, nodes: List<BizNode>, chosen: String, done: (String) -> Unit): View {
    val c = act
    val ids = listOf("") + nodes.map { it.id }
    // USER-DATA: a node's name, as the administrators wrote it
    val names = listOf(c.getString(R.string.biz_choose)) + nodes.map { it.name }
    return c.pickerRow(title, names[maxOf(0, ids.indexOf(chosen))]) { choose(act, title, names, ids.indexOf(chosen)) { i -> done(ids[i]) } }
}

/**
 * A NEW ORDER (iOS MTBizOrderDraftPage, 6.3): by voice or by hand. The voice is recorded and heard on this phone; what it
 * names fills the draft -- the product, its size, how many, where it goes -- and the manager corrects it and confirms with the
 * check. The order carries source 2, the hash of the voice note and its transcription; by hand, source 1 and the words.
 */
private fun draftPage(act: MainActivity, org: String, onBack: () -> Unit): View {
    var from = ""
    var to = ""
    var started = false
    var lastHeard = ""
    val lines = mutableListOf<DraftLine>()
    val words = act.bizField(R.string.biz_order_words).apply {
        isSingleLine = false; minLines = 2; maxLines = 6
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    }
    lateinit var live: Live<BizView?>
    lateinit var voice: BizVoice
    val sheet = BizSheet(act, act.getString(R.string.biz_new_order), { voice.stop(); onBack() }) {
        val ls = DraftLine.lines(lines)
        if (from.isEmpty() || to.isEmpty() || from == to || ls.isEmpty()) { say(act, R.string.biz_order_incomplete); return@BizSheet }
        val t = words.text.toString().trim().take(4096)
        val h = voice.hash
        Biz.order(org, from, to, ls, h, t) { key -> if (key != null) { voice.stop(); onBack() } else say(act, R.string.biz_refused) }
    }
    voice = BizVoice(act, org) { act.onMain { live.redraw() } }
    // where an order may go: anywhere for a manager or an administrator; for anyone else, the nodes they stand on
    val destinations = { v: BizView -> v.nodes.filter { it.active }.let { all -> if (v.role == "employee") all.filter { it.mine } else all } }
    live = Live(act, sheet, { Biz.view(org) }) { v ->
        v ?: return@Live
        val c = act
        if (!started) {
            started = true
            val shop = BizNodeKind.SHOP.word
            val dest = destinations(v)
            to = dest.firstOrNull { it.kind == shop && it.mine }?.id ?: dest.firstOrNull { it.kind == shop }?.id ?: ""
            from = v.nodes.filter { it.active }.firstOrNull { it.kind != shop && it.id != to }?.id ?: ""
            if (lines.isEmpty()) lines += DraftLine("", "", 1)
        }
        if (voice.heard.isNotEmpty() && voice.heard != lastHeard) {
            lastHeard = voice.heard
            words.setText(voice.heard)
            val d = BizDraft.read(voice.heard, v)
            d.line?.let { lines.clear(); lines += it }
            d.to?.let { n -> if (n != from) to = n }
        }
        // BY VOICE
        val voiceRows = mutableListOf<View>(c.hstack {
            setPadding(dp(16), dp(10), dp(14), dp(10)); minimumHeight = dp(48); bizWhole()
            addView(c.bizGlyph(if (voice.recording) R.drawable.ic_record_circle else R.drawable.ic_mic, Color.WHITE, 20),
                lp(sp(20), sp(20)).apply { marginEnd = dp(16) })
            addView(c.text(c.getString(if (voice.recording) R.string.biz_voice_stop else R.string.biz_voice_dictate), 16f), lp(0, WRAP, 1f))
            if (voice.hearing) addView(ProgressBar(c), lp(dp(20), dp(20)))
            if (!voice.hearing) pressable { voice.toggle() }
        })
        voice.refusal?.let { voiceRows += c.text(c.getString(it), 13f, MT.gray).apply { setPadding(dp(16), dp(8), dp(16), dp(10)) } }
        section(c.getString(R.string.biz_by_voice), c.getString(R.string.biz_voice_footer), *voiceRows.toTypedArray())
        // THE ROUTE
        val active = v.nodes.filter { it.active }
        val route = mutableListOf<View>()
        if (active.size < 2) route += c.bizLine(null, 0, c.getString(R.string.biz_two_nodes), titleColor = MT.gray)
        route += nodePicker(act, c.getString(R.string.biz_where_from), active, from) { from = it; live.redraw() }
        route += nodePicker(act, c.getString(R.string.biz_where_to), destinations(v), to) { to = it; live.redraw() }
        section(c.getString(R.string.biz_route), null, *route.toTypedArray())
        linesSection(act, lines, v.items.filter { it.active }) { live.redraw() }
        (words.parent as? LinearLayout)?.removeView(words)
        section(null, null, words)
    }
    return sheet.page
}

/**
 * What one may do on an order's page (iOS MTBizOrderRights), read once from the view: every act only where the core takes it --
 * mt-business supply.rs step, rule for rule. A cancelled order refuses only a confirmation, a packing and a second cancel; its
 * places still travel, are accepted, scanned, shelved and sold, and its problems are still told.
 */
private class OrderRights(v: BizView, o: BizOrder) {
    var confirm = false; var pack = false; var accept = false; var scan = false; var shelf = false; var sale = false
    var issue = false; var cancel = false
    /** Packing waits for a product still offered (check_lines of a new place): said in one line instead of the act. */
    var packNeedsProducts = false
    /** Places this viewer's node holds: their labels; of them, the ones that have a live node to go to are handed over. */
    var held: List<BizPlace> = emptyList()
    var handOver: List<BizPlace> = emptyList()
    init {
        val chief = Biz.boss(v.role)
        val live = { n: String? -> v.node(n)?.active == true }
        // Confirm: an open order not confirmed yet ("new"), at its source, which this viewer keeps
        confirm = o.state == "new" && v.may("confirm") && v.keeps(o.from)
        // Pack: an open, confirmed order; only at its source (not_supplier), live (unknown_node) and kept (denied); and only while
        // something of it lies in no box yet (unpacked, contract 1.2) of goods still offered (check_lines of a new place)
        val packing = o.open && o.state != "new" && v.may("pack") && v.keeps(o.from) && live(o.from) && o.unpacked.isNotEmpty()
        pack = packing && o.unpacked.any { l -> v.item(l.item)?.active == true }
        packNeedsProducts = packing && !pack
        // Handoff: the place's holder is this viewer's node (not_holder, denied), to another live node (unknown_node, same_node)
        held = o.places.filter { v.keeps(it.holder) }
        handOver = if (v.may("handoff")) held.filter { p -> v.nodes.any { it.active && it.id != p.holder } } else emptyList()
        // Accept: a place on its way to a node this viewer keeps (not_addressed, denied)
        accept = v.may("accept") && o.places.any { v.keeps(it.transitTo) }
        // Scan: a place of the order, at a live node this viewer keeps
        scan = v.may("scan") && v.keptNodes.isNotEmpty() && o.places.isNotEmpty()
        // Shelf, Sale: at a node of the order's path this viewer keeps (foreign_node, denied), never more than lies there on hand
        // (short_stock) or on the counter (short_counter)
        shelf = v.may("shelf") && o.places.isNotEmpty() && shelfNodes(v, o).isNotEmpty()
        sale = v.may("sale") && o.places.isNotEmpty() && saleNodes(v, o).isNotEmpty()
        // Issue: the administrators, the order's author, the people of its path
        issue = v.may("issue") && (chief || o.author == v.me || o.path.any { v.keeps(it) })
        // Cancel: the administrators and the author, once (already_cancelled)
        cancel = o.open && v.may("cancel") && (chief || o.author == v.me)
    }
    /**
     * The order's way waits for this person (iOS 1fec0318, f4a88f18): to confirm it, to pack what is left, to hand a place on, to
     * accept one coming to their node -- at the nodes they staff (the view's mine), never every order for an administrator. The
     * steps one may always take (scan, the counter, a problem, a cancel) wait for nobody.
     */
    var yours = false
    init {
        val staffs = { n: String? -> v.node(n)?.mine == true }
        yours = (confirm && staffs(o.from)) || (pack && staffs(o.from)) || (accept && o.places.any { staffs(it.transitTo) }) ||
            handOver.any { it.holder != o.to && staffs(it.holder) }
    }
    val any: Boolean get() = confirm || pack || packNeedsProducts || accept || scan || shelf || sale || issue || cancel || held.isNotEmpty()
}

/**
 * The open nodes this viewer keeps on the order's way where something lies on hand (supply.rs Shelf: shelved never past held),
 * in the nodes' own order (iOS counterNodes).
 */
private fun shelfNodes(v: BizView, o: BizOrder): List<String> = v.keptNodes.filter { n -> n.id in o.path && v.stock.any { it.node == n.id && 0 < it.onHand } }.map { it.id }
/** The open nodes this viewer keeps on the order's way where something lies on the counter (supply.rs Sale: sold never past shelved). */
private fun saleNodes(v: BizView, o: BizOrder): List<String> = v.keptNodes.filter { n -> n.id in o.path && v.stock.any { it.node == n.id && 0 < it.counter } }.map { it.id }

/**
 * supply.rs open: who may write an order -- a manager or an administrator, or the people of the node it goes to -- between two
 * live nodes, of goods still offered. Null when this viewer may write one; else the honest line instead of the button, the
 * reasons asked in iOS's own order (MTBizView.orderRefusal): the employee's node first, then the two nodes, then a product.
 */
internal fun orderBlocked(v: BizView): Int? = when {
    v.role == "employee" && v.keptNodes.isEmpty() -> R.string.biz_order_own_node
    v.nodes.count { it.active } < 2 -> R.string.biz_two_nodes
    v.items.none { it.active } -> R.string.biz_need_products
    else -> null
}

/**
 * AN ORDER'S PAGE (iOS MTBizOrderPage, 6.5): its route and goods, every place with where it stands (its keeper's own word) or
 * its way, the steps the person may take on it, and the order's TimeChain -- each step with its moment, its signer and the time
 * it stood after the step before.
 */
private fun orderPage(act: MainActivity, org: String, order: String, onBack: () -> Unit): View {
    val sheet = BizSheet(act, act.getString(R.string.biz_order), onBack)
    Live(act, sheet, { Biz.view(org) }) { v ->
        val o = v?.order(order) ?: return@Live
        val c = act
        // THE HEAD
        val head = mutableListOf<View>(c.hstack {
            setPadding(dp(16), dp(10), dp(14), dp(10)); minimumHeight = dp(48); bizWhole()
            addView(c.bizGlyph(R.drawable.ic_send, Color.WHITE, 20), lp(sp(20), sp(20)).apply { marginEnd = dp(16) })
            addView(c.vstack(Gravity.NO_GRAVITY) {
                addView(c.text(v.nodeName(o.from) + " → " + v.nodeName(o.to), 17f, Color.WHITE, bold = true), lp())   // USER-DATA: the route
                addView(c.text(c.getString(BizSupplyText.state(o.state)), 12f, MT.gray), lp())
            }, lp(0, WRAP, 1f))
        })
        o.lines.forEach { head += c.bizLine(null, 0, v.lineText(it)) }   // USER-DATA: one line of the order's goods
        if (o.text.isNotEmpty()) head += c.hstack {
            setPadding(dp(16), dp(10), dp(14), dp(10)); minimumHeight = dp(48); bizWhole()
            addView(c.bizGlyph(if (o.source == "voice") R.drawable.ic_mic else R.drawable.ic_set_doc, Color.WHITE, 20,
                said = if (o.source == "voice") R.string.biz_by_voice else R.string.biz_in_writing), lp(sp(20), sp(20)).apply { marginEnd = dp(16) })
            addView(c.text(o.text, 15f), lp(0, WRAP, 1f))   // USER-DATA: the order's words, as the manager wrote or said them
        }
        section(null, null, *head.toTypedArray())
        // THE PLACES
        if (o.places.isNotEmpty()) section(c.getString(R.string.biz_places), null, *o.places.map { p ->
            c.vstack(Gravity.NO_GRAVITY) {
                setPadding(dp(16), dp(10), dp(14), dp(10)); minimumHeight = dp(48); bizWhole()
                addView(c.hstack {
                    addView(c.bizGlyph(R.drawable.ic_archive, Color.WHITE, 18), lp(sp(18), sp(18)).apply { marginEnd = dp(8) })
                    // USER-DATA: the place's tag, the first letters of its code
                    addView(c.text(BizSupplyText.tag(p.id), 16f).apply { typeface = Typeface.MONOSPACE }, lp(0, WRAP, 1f))
                    BizSupplyText.condition(p.condition)?.let { addView(c.text(c.getString(it), 12f, MT.gray)) }
                }, lp())
                val holder = p.holder
                val a = p.transitFrom
                val b = p.transitTo
                val where = when {
                    holder != null -> c.getString(R.string.biz_where_now, v.nodeName(holder))
                    a != null && b != null -> c.getString(R.string.biz_on_the_way, v.nodeName(a), v.nodeName(b))
                    else -> null
                }
                where?.let { addView(c.text(it, 14f), lp()) }   // USER-DATA: the nodes' names
                addView(c.text(v.linesText(p.lines), 12f, MT.gray), lp())   // USER-DATA: the place's goods
                if (p.note.isNotEmpty()) addView(c.text(p.note, 12f, MT.gray), lp())   // USER-DATA: the keeper's own note at the acceptance
            }
        }.toTypedArray())
        // THE STEPS ONE MAY TAKE
        val r = OrderRights(v, o)
        if (r.any) {
            val rows = mutableListOf<View>()
            if (r.confirm) rows += c.bizRow(R.drawable.ic_set_check_circle, c.getString(R.string.biz_confirm_order)) { bizAct(act, org, BizCommand.confirm(order, o.from)) }
            if (r.pack) rows += c.bizRow(R.drawable.ic_archive, c.getString(R.string.biz_pack_place)) { step(act, org, order, Step.PACK) }
            else if (r.packNeedsProducts) rows += c.bizWhyNot(R.string.biz_need_products)
            r.held.forEach { p ->
                if (p in r.handOver) rows += c.bizRow(R.drawable.ic_send, c.getString(R.string.biz_hand_over_tag, BizSupplyText.tag(p.id))) { step(act, org, order, Step.HANDOFF, p.id) }
                rows += c.bizRow(R.drawable.ic_qr_scan, c.getString(R.string.biz_label_of, BizSupplyText.tag(p.id))) { label(act, p.id, v, o) }
            }
            if (r.accept) rows += c.bizRow(R.drawable.ic_arrow_circle_down, c.getString(R.string.biz_accept_place)) { step(act, org, order, Step.ACCEPT) }
            if (r.scan) rows += c.bizRow(R.drawable.ic_qr_scan, c.getString(R.string.biz_scan_place)) { step(act, org, order, Step.SCAN) }
            if (r.shelf) rows += c.bizRow(R.drawable.ic_unarchive, c.getString(R.string.biz_put_counter)) { step(act, org, order, Step.SHELF) }
            if (r.sale) rows += c.bizRow(R.drawable.ic_cart, c.getString(R.string.biz_record_sale)) { step(act, org, order, Step.SALE) }
            if (r.issue) rows += c.bizRow(R.drawable.ic_gpp_maybe, c.getString(R.string.biz_report_problem)) { step(act, org, order, Step.ISSUE) }
            if (r.cancel) rows += c.bizRow(R.drawable.ic_close, c.getString(R.string.biz_cancel_order)) { step(act, org, order, Step.CANCEL) }
            section(c.getString(R.string.biz_steps), null, *rows.toTypedArray())
        }
        // THE ORDER'S TIMECHAIN
        section(c.getString(R.string.biz_timechain), orderTime(c, v, o), *o.steps.mapIndexed { i, s -> stepRow(c, s, if (i == 0) null else o.steps[i - 1].atMs, v) }.toTypedArray())
    }
    return sheet.page
}

/** One step of an order's TimeChain (iOS MTBizStepRow): what was done, by whom, where, when -- and how long the order stood before it. */
private fun stepRow(c: Context, s: BizStep, before: Long?, v: BizView): View = c.hstack {
    gravity = Gravity.TOP
    setPadding(dp(16), dp(10), dp(14), dp(10)); minimumHeight = dp(48); bizWhole()
    val k = BizSupplyText.step(s.kind)
    addView(c.bizGlyph(k.first, Color.WHITE, 20), lp(sp(20), sp(20)).apply { marginEnd = dp(16); topMargin = dp(2) })
    addView(c.vstack(Gravity.NO_GRAVITY) {
        addView(c.text(c.getString(k.second), 16f), lp())
        // USER-DATA: who signed the step, the node, the place, the moment
        addView(c.text(listOf(v.member(s.author)?.name ?: "", v.nodeName(s.node), s.place?.let { BizSupplyText.tag(it) } ?: "", BizSupplyText.moment(s.atMs))
            .filter { it.isNotEmpty() }.joinToString(" · "), 12f, MT.gray), lp())
        if (s.kind == "accept") BizSupplyText.condition(s.detail)?.let { addView(c.text(c.getString(it), 12f, MT.gray), lp()) }
        if (s.kind == "issue") BizSupplyText.issue(s.detail)?.let { addView(c.text(c.getString(it), 12f, MT.gray), lp()) }
        if (s.lines.isNotEmpty()) addView(c.text(v.linesText(s.lines), 12f, MT.gray), lp())   // USER-DATA: the step's goods
        if (s.note.isNotEmpty()) addView(c.text(s.note, 12f, MT.gray), lp())   // USER-DATA: the signer's own note
        if (before != null && before < s.atMs) addView(c.hstack {
            addView(c.bizGlyph(R.drawable.ic_restore, MT.gray, 12), lp(sp(12), sp(12)).apply { marginEnd = dp(4) })
            addView(c.text(BizSupplyText.span(c, s.atMs - before), 11f, MT.gray))   // USER-DATA: the time the order stood before this step
        }, lp())
    }, lp(0, WRAP, 1f))
}

/**
 * THE PLACE'S LABEL (iOS printLabel): its code as a QR (the place's whole tag), its short name and its route, through the
 * platform's own share sheet -- print, save, send.
 */
private fun label(act: MainActivity, id: String, v: BizView, o: BizOrder) {
    val q = QrCode.encode(id, QrCode.Ecc.M) ?: return
    val module = 600 / (q.size + 8)
    val side = module * (q.size + 8)
    val bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.drawColor(Color.WHITE)
    val ink = Paint().apply { color = Color.BLACK; isAntiAlias = false }
    for (y in 0 until q.size) for (x in 0 until q.size) if (q.dark(x, y))
        canvas.drawRect(((x + 4) * module).toFloat(), ((y + 4) * module).toFloat(), ((x + 5) * module).toFloat(), ((y + 5) * module).toFloat(), ink)
    val png = java.io.ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    val f = BizExport.export(act, listOf(("label-" + BizSupplyText.tag(id) + ".png") to png)).firstOrNull() ?: return   // NOT-UI: a file name
    val words = BizSupplyText.tag(id) + "  " + v.nodeName(o.from) + " → " + v.nodeName(o.to)   // USER-DATA: the place and its route
    BizExport.share(act, listOf(f), "image/png", words)
}

/** The steps a person takes on an order, each on its own page (iOS MTBizStepAct). */
private enum class Step(val title: Int) {
    PACK(R.string.biz_pack_place), HANDOFF(R.string.biz_hand_over), ACCEPT(R.string.biz_accept_place), SCAN(R.string.biz_scan_place),
    SHELF(R.string.biz_put_counter), SALE(R.string.biz_record_sale), ISSUE(R.string.biz_report_problem), CANCEL(R.string.biz_cancel_order);
    /** Accepting and scanning begin at the place's own label: the platform's scanner reads its code first. */
    val scansFirst: Boolean get() = this == ACCEPT || this == SCAN
}

/**
 * A step begins: accepting and scanning at the place's label (the app's own scanner; a code that is not a place of this order
 * -- for an acceptance, one on its way to a node this person keeps -- is passed over, the scanner keeps looking); the rest on
 * their page at once.
 */
private fun step(act: MainActivity, org: String, order: String, kind: Step, handed: String = "") {
    if (!kind.scansFirst) { act.push { stepPage(act, org, order, kind, handed, "", it) }; return }
    act.push { close ->
        lateinit var page: View
        // a code passed over is said in the scanner's own line (iOS 1fec0318): the storekeeper holding the wrong box is never left
        // before a camera that does nothing; the scanner keeps looking
        fun misread(word: Int) = act.onMain { page.findViewWithTag<android.widget.TextView>(SCAN_HINT_TAG)?.text = act.getString(word) }
        page = scannerPage(act, close, act.getString(R.string.biz_point_label)) { code ->
            val v = Biz.view(org)
            val o = v?.order(order)
            val t = code.trim().lowercase()
            val p = o?.places?.firstOrNull { it.id == t }
            val to = p?.transitTo
            val node = when {
                v == null -> null
                p == null -> { misread(R.string.biz_scan_not_this_order); null }
                kind == Step.ACCEPT -> to?.takeIf { v.keeps(it) } ?: run { misread(R.string.biz_scan_not_your_node); null }
                else -> p.holder?.takeIf { h -> v.keptNodes.any { it.id == h } } ?: (v.keptNodes.firstOrNull()?.id ?: "")   // Scan: a live node one keeps
            }
            if (node != null) act.onMain { act.push { stepPage(act, org, order, kind, t, node, it) } }
            node != null
        }
        page
    }
}

/**
 * ONE STEP OF AN ORDER (iOS MTBizStepSheet): the node it stands on, the goods it names, the place it is about, the condition,
 * the note and the photo of an acceptance (in the chain only the photo's hash; the photo stays on this phone).
 */
private fun stepPage(act: MainActivity, org: String, order: String, kind: Step, place0: String, node0: String, onClose: () -> Unit): View {
    var node = node0
    var place = place0
    var condition = 1
    var issueKind = 1
    var photoHash: String? = null
    var prepared = false
    val lines = mutableListOf<DraftLine>()
    val note = act.bizField(R.string.biz_note).apply {
        isSingleLine = false; maxLines = 5
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    }
    lateinit var live: Live<BizView?>
    val sheet = BizSheet(act, act.getString(kind.title), onClose, cross = true) {
        val words = note.text.toString().trim().take(1024)
        val ls = DraftLine.lines(lines)
        run {
            val o = Biz.view(org)?.order(order)
            var packed: String? = null   // the new place's tag: its label comes up once the sheet has gone
            val cmd = if (o == null) null else when (kind) {
                Step.PACK -> if (node.isNotEmpty() && ls.isNotEmpty()) Biz.freshTag()?.let { packed = it; BizCommand.pack(order, it, node, ls) } else null
                Step.HANDOFF -> o.places.firstOrNull { it.id == place }?.holder?.takeIf { node.isNotEmpty() }?.let { BizCommand.handoff(order, place, it, node) }
                Step.ACCEPT -> if (place.isNotEmpty() && node.isNotEmpty()) BizCommand.accept(order, place, node, condition, words, photoHash) else null
                Step.SCAN -> if (place.isNotEmpty() && node.isNotEmpty()) BizCommand.scan(order, place, node) else null
                Step.SHELF -> if (node.isNotEmpty() && ls.isNotEmpty()) BizCommand.shelf(order, node, ls) else null
                Step.SALE -> if (node.isNotEmpty() && ls.isNotEmpty()) BizCommand.sale(order, node, ls) else null
                Step.ISSUE -> BizCommand.issue(order, place.ifEmpty { null }, issueKind, words)
                Step.CANCEL -> BizCommand.cancel(order, words)
            }
            if (cmd == null) say(act, R.string.biz_refused)
            else Biz.write(org, cmd) { id ->
                if (id == null) say(act, R.string.biz_refused)
                else {
                    onClose()
                    // the next thing a storekeeper does (iOS 1fec0318): the new place's label, in the platform's own sheet --
                    // print, save, send; the next node accepts the place by it
                    val tag = packed
                    val v = Biz.view(org)
                    val o2 = v?.order(order)
                    if (tag != null && v != null && o2 != null) label(act, tag, v, o2)
                }
            }
        }
    }
    fun placeRow(p: String): View = act.hstack {
        setPadding(dp(16), dp(10), dp(14), dp(10)); minimumHeight = dp(48); bizWhole()
        addView(act.bizGlyph(R.drawable.ic_archive, Color.WHITE, 20), lp(sp(20), sp(20)).apply { marginEnd = dp(16) })
        addView(act.text(BizSupplyText.tag(p), 16f).apply { typeface = Typeface.MONOSPACE }, lp(0, WRAP, 1f))   // USER-DATA: the place's tag
    }
    live = Live(act, sheet, { Biz.view(org) }) { v ->
        val o = v?.order(order) ?: return@Live
        val c = act
        if (!prepared) {
            prepared = true
            // the defaults a step starts from: the node the person keeps on the order's way, the goods the order names
            when (kind) {
                // Pack: only at the order's source (not_supplier), of goods still offered (check_lines of a new place)
                // the lines no box holds yet (unpacked): with several places, the storekeeper sees what is left to pack
                Step.PACK -> { node = o.from; lines += o.unpacked.filter { l -> v.item(l.item)?.active == true }.map { DraftLine(it) } }
                // Handoff: to another live node; the order's destination when it is one
                Step.HANDOFF -> { val holder = o.places.firstOrNull { it.id == place }?.holder; node = if (holder != o.to && v.node(o.to)?.active == true) o.to else "" }
                // Shelf, Sale: a node of the path one keeps where the goods lie on hand / on the counter
                Step.SHELF, Step.SALE -> {
                    val at = if (kind == Step.SHELF) shelfNodes(v, o) else saleNodes(v, o)
                    node = if (o.to in at) o.to else at.firstOrNull() ?: ""
                    lines += o.lines.map { DraftLine(it) }
                }
                else -> {}
            }
        }
        (note.parent as? LinearLayout)?.removeView(note)
        val pick = { title: Int, nodes: List<BizNode> -> nodePicker(act, c.getString(title), nodes, node) { node = it; live.redraw() } }
        when (kind) {
            // USER-DATA: the order's source, the one node a place is packed at
            Step.PACK -> { section(null, null, c.bizLine(null, 0, c.getString(R.string.biz_packed_at), v.nodeName(o.from))); linesSection(act, lines, v.items.filter { it.active }) { live.redraw() } }
            Step.HANDOFF -> {
                val holder = o.places.firstOrNull { it.id == place }?.holder
                section(null, c.getString(R.string.biz_on_its_way_footer), placeRow(place), pick(R.string.biz_where_to, v.nodes.filter { it.active && it.id != holder }))
            }
            Step.ACCEPT -> {
                val conds = listOf(R.string.biz_cond_whole, R.string.biz_cond_damaged, R.string.biz_cond_short)
                section(null, c.getString(R.string.biz_photo_footer), placeRow(place),
                    c.pickerRow(c.getString(R.string.biz_condition), c.getString(conds[condition - 1])) {
                        choose(act, c.getString(R.string.biz_condition), conds.map { c.getString(it) }, condition - 1) { i -> condition = i + 1; live.redraw() }
                    },
                    note,
                    c.bizRow(if (photoHash == null) R.drawable.ic_photo else R.drawable.ic_set_check_circle,
                        c.getString(if (photoHash == null) R.string.biz_add_photo else R.string.biz_photo_added)) {
                        act.pickPhoto { uri ->
                            if (uri != null) act.background {
                                val data = runCatching { act.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                                val h = data?.let { Biz.hex(Wire.sha(it)) }
                                // the acceptance's photo: kept in the person's Business folder under its own hash; the chain gets the hash alone
                                if (data != null && h != null) Biz.keepMedia(org, "photo-" + h, data) { kept -> if (kept) { photoHash = h; live.redraw() } }   // NOT-UI: a file name
                            }
                        }
                    })
            }
            Step.SCAN -> section(null, null, placeRow(place), pick(R.string.biz_at, v.keptNodes))
            Step.SHELF, Step.SALE -> {
                val at = if (kind == Step.SHELF) shelfNodes(v, o) else saleNodes(v, o)
                section(null, null, pick(R.string.biz_at, at.mapNotNull { v.node(it) }))
                linesSection(act, lines, v.items) { live.redraw() }
            }
            Step.ISSUE -> {
                val kinds = listOf(R.string.biz_is_return, R.string.biz_is_defect, R.string.biz_is_loss)
                val ids = listOf("") + o.places.map { it.id }
                val names = listOf(c.getString(R.string.biz_whole_order)) + o.places.map { BizSupplyText.tag(it.id) }   // USER-DATA: the places' tags
                section(null, null,
                    c.pickerRow(c.getString(R.string.biz_sp_issue), c.getString(kinds[issueKind - 1])) {
                        choose(act, c.getString(R.string.biz_sp_issue), kinds.map { c.getString(it) }, issueKind - 1) { i -> issueKind = i + 1; live.redraw() }
                    },
                    c.pickerRow(c.getString(R.string.biz_place), names[maxOf(0, ids.indexOf(place))]) {
                        choose(act, c.getString(R.string.biz_place), names, ids.indexOf(place)) { i -> place = ids[i]; live.redraw() }
                    },
                    note)
            }
            Step.CANCEL -> section(null, c.getString(R.string.biz_cancel_footer), note)
        }
    }
    return sheet.page
}

// ─────────────────────────── the stock ───────────────────────────

/** THE STOCK BY NODE (iOS MTBizStockPage, 6.6): for every node the person keeps, each product and size -- on hand, on the counter, sold. */
private fun stockPage(act: MainActivity, org: String, onBack: () -> Unit): View {
    val sheet = BizSheet(act, act.getString(R.string.biz_stock), onBack)
    Live(act, sheet, { Biz.view(org) }) { v ->
        v ?: return@Live
        val c = act
        if (v.stock.isEmpty()) section(null, null, c.bizLine(null, 0, c.getString(R.string.biz_nothing_counted), titleColor = MT.gray))
        v.nodes.filter { n -> v.stock.any { it.node == n.id } }.forEach { n ->
            // USER-DATA: the node's name
            section(n.name, c.getString(R.string.biz_stock_footer), *v.stock.filter { it.node == n.id }.map { s ->
                c.hstack {
                    setPadding(dp(16), dp(10), dp(14), dp(10)); minimumHeight = dp(48); bizWhole()
                    // USER-DATA: the product and its size
                    addView(c.text(listOf(v.item(s.item)?.title ?: "", s.size).filter { it.isNotEmpty() }.joinToString(" · "), 16f), lp(0, WRAP, 1f))
                    for ((glyph, count, name) in listOf(Triple(R.drawable.ic_archive, s.onHand, R.string.biz_on_hand),
                            Triple(R.drawable.ic_unarchive, s.counter, R.string.biz_sp_shelf), Triple(R.drawable.ic_cart, s.sold, R.string.biz_sp_sale))) {
                        addView(c.hstack {
                            addView(c.bizGlyph(glyph, MT.gray, 12), lp(sp(12), sp(12)).apply { marginEnd = dp(3) })
                            addView(c.text(count.toString(), 12f, MT.gray))   // USER-DATA: a count of units
                            contentDescription = c.getString(name) + " " + count
                        }, lp(WRAP, WRAP).apply { marginStart = dp(10) })
                    }
                }
            }.toTypedArray())
        }
    }
    return sheet.page
}

// ─────────────────────────── the catalogue (administrators) ───────────────────────────

/** THE PRODUCTS (iOS MTBizProductsPage): every product with its sizes and its unit; a product withdrawn stays in the old orders. */
private fun productsPage(act: MainActivity, org: String, onBack: () -> Unit): View {
    val sheet = BizSheet(act, act.getString(R.string.biz_products), onBack)
    Live(act, sheet, { Biz.view(org) }) { v ->
        v ?: return@Live
        val c = act
        val rows = v.items.map { i ->
            c.hstack {
                setPadding(dp(16), dp(10), dp(14), dp(10)); minimumHeight = dp(48); bizWhole()
                addView(c.bizGlyph(R.drawable.ic_badge, Color.WHITE, 20), lp(sp(20), sp(20)).apply { marginEnd = dp(16) })
                addView(c.vstack(Gravity.NO_GRAVITY) {
                    addView(c.text(i.title, 16f, if (i.active) Color.WHITE else MT.gray), lp())   // USER-DATA: the product's name
                    addView(c.text(listOf(i.sizes, i.unit).filter { it.isNotEmpty() }.joinToString(" · "), 12f, MT.gray), lp())   // USER-DATA: its sizes and unit
                }, lp(0, WRAP, 1f))
                if (!i.active) addView(c.text(c.getString(R.string.biz_withdrawn), 12f, MT.gray))
                pressable {
                    confirmAct(act, i.title, if (i.active) R.string.biz_withdraw else R.string.biz_offer_again) {
                        bizAct(act, org, BizCommand.item(i.id, i.title, i.sizes, i.unit, !i.active))
                    }
                }
            }
        }.toMutableList<View>()
        rows += c.bizRow(R.drawable.ic_plus, c.getString(R.string.biz_new_product)) { act.push { productPage(act, org, it) } }
        section(null, c.getString(R.string.biz_sizes_footer), *rows.toTypedArray())
    }
    return sheet.page
}

/** A new product (iOS MTBizProductSheet): its name, its sizes, its unit. */
private fun productPage(act: MainActivity, org: String, onClose: () -> Unit): View {
    val title = act.bizField(R.string.biz_product_name)
    val sizes = act.bizField(R.string.biz_sizes_opt)
    val unit = act.bizField(R.string.biz_unit_opt)
    val sheet = BizSheet(act, act.getString(R.string.biz_new_product), onClose, cross = true) {
        val t = title.text.toString().trim().take(256)
        val tag = Biz.freshTag()
        if (t.isEmpty() || tag == null) { say(act, R.string.biz_refused); return@BizSheet }
        bizWrite(act, org, BizCommand.item(tag, t, sizes.text.toString().trim().take(256), unit.text.toString().trim().take(64), true)) {
            if (it != null) onClose() else say(act, R.string.biz_refused)
        }
    }
    title.bizFirstField()
    sheet.readyWhen(title) { title.text.isNotBlank() }
    sheet.body.section(null, null, title, sizes, unit)
    return sheet.page
}

/** THE NODES (iOS MTBizNodesPage): suppliers, warehouses, hubs, carriers, shops -- each opens to its people. */
private fun nodesPage(act: MainActivity, org: String, onBack: () -> Unit): View {
    val sheet = BizSheet(act, act.getString(R.string.biz_nodes), onBack)
    Live(act, sheet, { Biz.view(org) }) { v ->
        v ?: return@Live
        val c = act
        val rows = v.nodes.map { n -> nodeRow(c, n, chevron = true).apply { pressable { act.push { nodePage(act, org, n.id, it) } } } }.toMutableList<View>()
        rows += c.bizRow(R.drawable.ic_plus, c.getString(R.string.biz_new_node)) { act.push { nodeSheet(act, org, it) } }
        section(null, c.getString(R.string.biz_nodes_footer), *rows.toTypedArray())
    }
    return sheet.page
}

/** A node in a list (iOS MTBizNodeRow): its kind's glyph, its name, its kind and place, how many people it has. */
private fun nodeRow(c: Context, n: BizNode, chevron: Boolean): View = c.hstack {
    setPadding(dp(16), dp(10), dp(14), dp(10)); minimumHeight = dp(48); bizWhole()
    val kind = BizNodeKind.of(n.kind)
    addView(c.bizGlyph(kind?.glyph ?: R.drawable.ic_pin, Color.WHITE, 20), lp(sp(20), sp(20)).apply { marginEnd = dp(16) })
    addView(c.vstack(Gravity.NO_GRAVITY) {
        addView(c.text(n.name, 16f, if (n.active) Color.WHITE else MT.gray), lp())   // USER-DATA: the node's name
        // USER-DATA: the node's kind in the person's language and its place, as written
        addView(c.text(listOf(kind?.let { c.getString(it.title) } ?: "", n.place).filter { it.isNotEmpty() }.joinToString(" · "), 12f, MT.gray), lp())
    }, lp(0, WRAP, 1f))
    addView(c.bizGlyph(R.drawable.ic_person, MT.gray, 12), lp(sp(12), sp(12)).apply { marginEnd = dp(3) })
    addView(c.text(n.staff.size.toString(), 12f, MT.gray))   // USER-DATA: how many people the node has
    if (chevron) addView(c.bizGlyph(R.drawable.ic_chevron_right, Color.rgb(89, 89, 89), 18), lp(sp(18), sp(18)).apply { marginStart = dp(6) })
}

/** A NODE'S PAGE (iOS MTBizNodePage): who takes its steps (node_staff) -- one switch per member -- and its closing. */
private fun nodePage(act: MainActivity, org: String, node: String, onBack: () -> Unit): View {
    val sheet = BizSheet(act, "", onBack)
    Live(act, sheet, { Biz.view(org) }) { v ->
        val n = v?.node(node) ?: return@Live
        val c = act
        sheet.title.text = n.name   // USER-DATA: the node's name
        section(null, null, nodeRow(c, n, chevron = false))
        section(c.getString(R.string.biz_node_people), c.getString(R.string.biz_node_people_footer), *v.members.filter { it.status == "active" }.map { m ->
            c.hstack {
                // ONE STOP FOR TALKBACK: the switch speaks the name and its state; the row and its words stand aside
                setPadding(dp(16), dp(6), dp(14), dp(6)); minimumHeight = dp(48)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                addView(c.text(m.name, 16f).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, lp(0, WRAP, 1f))   // USER-DATA: a member's name
                val sw = Switch(c).apply {
                    isChecked = m.id in n.staff
                    thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                    trackTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(MT.green, Color.rgb(57, 57, 61)))
                    contentDescription = m.name
                    setOnCheckedChangeListener { _, on -> bizAct(act, org, BizCommand.nodeStaff(node, m.id, on)) }
                }
                addView(sw)
                setOnClickListener { sw.toggle() }
            }
        }.toTypedArray())
        BizNodeKind.of(n.kind)?.let { kind ->
            section(null, null, c.bizRow(if (n.active) R.drawable.ic_close else R.drawable.ic_restore,
                c.getString(if (n.active) R.string.biz_close_node else R.string.biz_open_node)) {
                bizAct(act, org, BizCommand.node(node, kind.number, n.name, n.place, !n.active))
            })
        }
    }
    return sheet.page
}

/** A new node (iOS MTBizNodeSheet): its kind, its name, its place. */
private fun nodeSheet(act: MainActivity, org: String, onClose: () -> Unit): View {
    var kind = BizNodeKind.WAREHOUSE
    val name = act.bizField(R.string.biz_node_name)
    val spot = act.bizField(R.string.biz_where_it_is)
    lateinit var kindRow: View
    val sheet = BizSheet(act, act.getString(R.string.biz_new_node), onClose, cross = true) {
        val n = name.text.toString().trim().take(256)
        val tag = Biz.freshTag()
        if (n.isEmpty() || tag == null) { say(act, R.string.biz_refused); return@BizSheet }
        bizWrite(act, org, BizCommand.node(tag, kind.number, n, spot.text.toString().trim().take(256), true)) { if (it != null) onClose() else say(act, R.string.biz_refused) }
    }
    fun makeKindRow(): View = act.pickerRow(act.getString(R.string.biz_kind), act.getString(kind.title)) {
        val all = BizNodeKind.values().toList()
        choose(act, act.getString(R.string.biz_kind), all.map { act.getString(it.title) }, all.indexOf(kind)) { i ->
            kind = all[i]
            val parent = kindRow.parent as LinearLayout
            val at = parent.indexOfChild(kindRow)
            parent.removeView(kindRow); kindRow = makeKindRow(); parent.addView(kindRow, at, lp())
        }
    }
    kindRow = makeKindRow()
    name.bizFirstField()
    sheet.readyWhen(name) { name.text.isNotBlank() }
    sheet.body.section(null, null, kindRow, name, spot)
    return sheet.page
}
