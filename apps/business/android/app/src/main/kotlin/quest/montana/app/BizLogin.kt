package quest.montana.app

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.text.Editable
import android.util.Log
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar

/** A mark on its own round of glass (iOS MontanaCallMark): the glyph white, its word for TalkBack, the whole 48dp the target. */
private fun roundMark(c: Context, glyph: Int, said: Int, onTap: () -> Unit): View = FrameLayout(c).apply {
    background = c.glassPlate(oval = true)
    contentDescription = c.getString(said)
    addView(c.icon(glyph, Color.WHITE, 20), FrameLayout.LayoutParams(c.dp(20), c.dp(20), Gravity.CENTER))
    pressable(onTap)
}

/** The system's bars and the keyboard as one inset, left, top, right, bottom (WindowInsets.Type is Android 11's). */
@Suppress("DEPRECATION")
private fun barsAndKeys(i: WindowInsets): IntArray =
    if (Build.VERSION.SDK_INT >= 30) i.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime()).let { intArrayOf(it.left, it.top, it.right, it.bottom) }
    else intArrayOf(i.systemWindowInsetLeft, i.systemWindowInsetTop, i.systemWindowInsetRight, i.systemWindowInsetBottom)

/** The system's bar at the foot of the screen (WindowInsets.Type is Android 11's). */
@Suppress("DEPRECATION")
private fun footBar(i: WindowInsets): Int =
    if (Build.VERSION.SDK_INT >= 30) i.getInsets(WindowInsets.Type.navigationBars()).bottom else i.systemWindowInsetBottom

/**
 * The country a number's digits name (iOS MTBizCountries.match): the longest calling code they begin with; where two countries
 * share a code (1, 7) the one the person chose keeps it.
 */
internal fun BizCountries.match(digits: String, prefer: BizCountries.Country): BizCountries.Country? {
    var n = minOf(3, digits.length)
    while (0 < n) {
        val head = digits.substring(0, n)
        if (prefer.code == head) return prefer
        all.firstOrNull { it.code == head }?.let { return it }
        n--
    }
    return null
}

/**
 * The number as the field shows it (iOS MTBizCountries.shown): «+», the calling code, then the national digits -- up to ten of
 * them as 3 3-2-2, more in threes. Digits that name no country stand as typed.
 */
internal fun BizCountries.shown(digits: String, code: String?): String {
    if (digits.isEmpty()) return ""
    if (code == null || !digits.startsWith(code) || digits.length <= code.length) return "+" + digits
    val national = digits.substring(code.length)
    val out = StringBuilder("+").append(code).append(' ')
    national.forEachIndexed { i, ch ->
        if (national.length <= 10) { if (i == 3) out.append(' ') else if (i == 6 || i == 8) out.append('-') }
        else if (0 < i && i % 3 == 0) out.append(' ')
        out.append(ch)
    }
    return out.toString()
}

/**
 * THE NUMBER'S DOOR (iOS MTBizPhoneDoor and MTBizPhoneFlow, 65c08442; the author's word 06.10.2026 10:3x MSK, with his
 * screenshots: «after "continue with phone number" give the field of the phone number as on the screenshots, with the question
 * how you want to get the code»): one field -- the flag of the country the number names (a globe while it names none) with the
 * mark that opens the countries, the number with its calling code in groups, the clear mark -- and «Next» at the foot in the
 * platform's blue, open once the number is a number (6..15 digits past a known code). The field takes the keyboard at once; the
 * flag follows the code the person types. «Next» asks how the code should come, from the foot of the screen: one row for every
 * road the service keeps, its name and glyph the service's own (no outer service is named in the app); while the service is
 * asked, the platform's spinner; once asked and silent, ONE HONEST LINE (the checklist 2.2) and, where the page has a way on,
 * the door to go on without a number. The person is never born here: the login page's door of the number makes them first
 * (BizLogin, the one road of a birth). The confirmation is kept only when the core proves it for this key and this number, and
 * it goes into every organization's files. While it waits, the door says so, with the way back (the cross) and the outer
 * service opened again; a door that leaves leaves its wait.
 */
class BizPhoneDoor(private val act: MainActivity, private val onWithout: (() -> Unit)? = null, private val onConfirmed: () -> Unit) {
    val view: LinearLayout = act.vstack(Gravity.NO_GRAVITY)
    private var roads: List<BizService.Road> = emptyList()
    /** The service was asked at least once: its silence is spoken from here on, never guessed before. */
    private var asked = false
    private var waiting: BizService.Road? = null
    private var started: BizService.Started? = null
    /** The country the person chose: it keeps a calling code two countries share. */
    private var chosen = BizCountries.initial(act)
    /** The number's digits, its calling code first, at most fifteen (E.164); the field shows them in groups. */
    private var digits = chosen.code
    /** The field is being written from the digits: its own watcher stands aside. */
    private var shaping = false
    /** Each start or cancel turns the walk: a wait of an older walk speaks no more. */
    @Volatile private var walk = 0
    /** Each leaving of the door turns the watch: the roads of an older watch are asked no more. */
    @Volatile private var watch = 0
    private val field = EditText(act)
    private val flag = FrameLayout(act)
    private val countryMark = act.hstack()
    private val clear = FrameLayout(act)
    private val rim = GradientDrawable()
    private val next: View = act.loginDoor(null, act.getString(R.string.biz_go_next), MT.blue) { ask() }
    private val form: LinearLayout
    /** The question how the code should come, while it stands, and its rows (drawn again as the service answers). */
    private var roadSheet: Dialog? = null
    private var roadRows: LinearLayout? = null
    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
        override fun afterTextChanged(s: Editable?) {
            if (shaping) return
            digits = (s?.toString() ?: "").filter { it in '0'..'9' }.take(15)
            BizCountries.match(digits, chosen)?.let { chosen = it }
            shape()
        }
    }

    init {
        form = act.vstack(Gravity.NO_GRAVITY) {
            addView(fieldRow(), lp(MATCH, dp(64)))
            gap(16)
            spacer()
            gap(16)
            addView(next, lp())
        }
        shape()
        draw()
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { if (roads.isEmpty()) watchRoads(); type(400) }
            override fun onViewDetachedFromWindow(v: View) { watch++; roadSheet?.dismiss(); if (waiting != null) cancel() }
        })
    }

    /** The roads, asked until the service names them or the door leaves (iOS watch: every five seconds). */
    private fun watchRoads() {
        val w = ++watch
        act.background {
            while (w == watch) {
                val got = BizService.roads()
                act.onMain { if (w == watch) { roads = got; asked = true; fillRoads() } }
                if (got.isNotEmpty()) return@background
                Thread.sleep(5000)
            }
        }
    }

    /** The country the digits name; null while they name none (the field then shows a globe). */
    private fun country(): BizCountries.Country? = BizCountries.match(digits, chosen)

    private fun e164(): String? {
        val k = country() ?: return null
        return if (k.code.length < digits.length && digits.length in 6..15) "+" + digits else null
    }

    private fun draw() {
        view.removeAllViews()
        val road = waiting
        if (road != null) waitingFace(road) else view.addView(form, lp(MATCH, 0, 1f))
    }

    /** The one field (iOS field): the country's mark, the number, the clear mark, on a plate whose rim lights while it types. */
    private fun fieldRow(): View {
        val c = act
        rim.cornerRadius = c.dp(16).toFloat()
        rim.setColor(Color.argb(150, 28, 28, 30))
        lit(false)
        return c.hstack {
            background = rim
            setPadding(0, 0, dp(8), 0)
            addView(countryMark.apply {
                gravity = Gravity.CENTER
                minimumWidth = dp(64)
                addView(flag, lp(WRAP, WRAP))
                addView(c.icon(R.drawable.ic_arrow_drop_down, Color.WHITE, 18).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                    lp(dp(18), dp(18)).apply { marginStart = dp(2) })
                pressable { countries() }
            }, lp(WRAP, MATCH))
            addView(field.apply {
                hint = c.getString(R.string.biz_phone_word)
                setHintTextColor(MT.gray); setTextColor(Color.WHITE); textSize = 20f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                fontFeatureSettings = "tnum"   // the digits of one width (iOS monospacedDigit): the groups stand still as they grow
                inputType = InputType.TYPE_CLASS_PHONE
                setAutofillHints(View.AUTOFILL_HINT_PHONE)
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
                imeOptions = EditorInfo.IME_ACTION_NEXT
                setOnEditorActionListener { _, _, _ -> ask(); true }
                isSingleLine = true
                background = null
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), 0, dp(4), 0)
                setOnFocusChangeListener { _, on -> lit(on) }
                addTextChangedListener(watcher)
            }, lp(0, MATCH, 1f))
            addView(clear.apply {
                contentDescription = c.getString(R.string.biz_clear)
                addView(c.icon(R.drawable.ic_cancel, MT.gray, 22), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
                pressable { digits = country()?.code ?: ""; shape() }
            }, lp(dp(48), dp(48)))
        }
    }

    /** The plate's rim: white and 2dp while the field types, 1dp at 0.35 otherwise (iOS typing). */
    private fun lit(on: Boolean) = rim.setStroke(act.dp(if (on) 2 else 1), MT.withAlpha(Color.WHITE, if (on) 1f else 0.35f))

    /** The field's face from the digits: the number in its groups, the flag it names, the clear mark, «Next» open or closed. */
    private fun shape() {
        val c = act
        val k = country()
        val shown = BizCountries.shown(digits, k?.code)
        if (field.text.toString() != shown) {
            shaping = true
            field.setText(shown)
            field.setSelection(shown.length)
            shaping = false
        }
        flag.removeAllViews()
        // USER-DATA: the flag of the country the number names
        if (k != null) flag.addView(c.text(BizCountries.flag(k), 22f), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        else flag.addView(c.icon(R.drawable.ic_language, Color.WHITE, 22), FrameLayout.LayoutParams(c.dp(22), c.dp(22), Gravity.CENTER))
        flag.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        // the mark says what it is and what it holds (iOS «Country»), never the flag's glyph
        countryMark.contentDescription = c.getString(R.string.biz_country) + (k?.let { ", " + BizCountries.name(c, it) + ", +" + it.code } ?: "")
        clear.visibility = if ((k?.code?.length ?: 0) < digits.length) View.VISIBLE else View.GONE
        val on = e164() != null
        next.isEnabled = on
        next.alpha = if (on) 1f else 0.4f
    }

    private fun keysAway() {
        act.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(field.windowToken, 0)
    }

    /** The keyboard comes to the field (iOS typing = true) once the page has settled: a field focused while it rises takes none. */
    private fun type(ms: Long) = act.onMainAfter(ms) {
        if (field.isAttachedToWindow && waiting == null && roadSheet == null && field.requestFocus())
            act.getSystemService(InputMethodManager::class.java)?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
    }

    /** The countries (iOS MTBizCountrySheet): the keyboard goes; a country chosen sets the digits to its code; back, the keyboard. */
    private fun countries() {
        keysAway()
        act.push { close ->
            bizCountryPage(act, country() ?: chosen, { k -> chosen = k; digits = k.code; shape() }, close).apply {
                addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {}
                    override fun onViewDetachedFromWindow(v: View) { type(300) }
                })
            }
        }
    }

    /** «Next»: the keyboard goes, and the question how the code should come rises from the foot of the screen (iOS MTBizRoadSheet). */
    private fun ask() {
        if (e164() == null || roadSheet != null) return
        keysAway()
        val c = act
        val sheet = Dialog(c)
        sheet.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val rows = c.vstack(Gravity.NO_GRAVITY)
        val face = c.vstack(Gravity.NO_GRAVITY) {
            minimumHeight = dp(340)
            setPadding(dp(24), dp(16), dp(24), dp(24))
            addView(c.hstack {
                spacer()
                addView(roundMark(c, R.drawable.ic_close, R.string.biz_close) { sheet.dismiss() }, lp(dp(48), dp(48)))
            }, lp())
            addView(c.text(c.getString(R.string.biz_code_how), 28f, Color.WHITE, bold = true), lp().apply { bottomMargin = dp(12) })
            addView(rows, lp())
        }
        // the sheet keeps clear of the system's bar at the foot, wherever the system lays its window
        face.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(v.dp(24), v.dp(16), v.dp(24), v.dp(24) + footBar(insets))
            insets
        }
        sheet.setContentView(face)
        sheet.setCanceledOnTouchOutside(true)
        sheet.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply {
                setColor(MT.plate)
                val r = c.dp(20).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            })
            setGravity(Gravity.BOTTOM)
            setWindowAnimations(android.R.style.Animation_InputMethod)
        }
        sheet.setOnDismissListener { if (roadSheet === sheet) { roadSheet = null; roadRows = null } }
        roadSheet = sheet
        roadRows = rows
        fillRoads()
        sheet.show()
        sheet.window?.setLayout(MATCH, WRAP)
    }

    /** The question's rows (iOS MTBizRoadSheet), drawn again whenever the service answers; the whole row is the target. */
    private fun fillRoads() {
        val rows = roadRows ?: return
        val c = act
        rows.removeAllViews()
        when {
            roads.isNotEmpty() -> roads.forEach { r ->
                rows.addView(c.hstack {
                    minimumHeight = dp(56)
                    addView(glyph(r.glyph), lp(dp(36), dp(26)).apply { marginEnd = dp(16) })
                    // USER-DATA: the road's own name, as the Business's service names it in the person's language
                    addView(c.text(r.title, 20f), lp(0, WRAP, 1f))
                    pressable { roadSheet?.dismiss(); start(r) }
                }, lp())
            }
            asked -> {
                rows.addView(c.text(c.getString(R.string.biz_phone_unavailable), 15f, MT.gray), lp())
                onWithout?.let { go ->
                    rows.gap(12)
                    rows.addView(c.loginDoor(null, c.getString(R.string.biz_without_number)) { roadSheet?.dismiss(); go() }, lp())
                }
            }
            // the service is being asked for its roads
            else -> rows.addView(FrameLayout(c).apply {
                addView(ProgressBar(c), FrameLayout.LayoutParams(c.dp(28), c.dp(28), Gravity.CENTER))
            }, lp(MATCH, c.dp(56)))
        }
    }

    /** The service names a glyph of the platform it was asked for; Android wears its own nearest one, white. */
    private fun glyph(name: String): View = act.icon(when {
        name.startsWith("paperplane") -> R.drawable.ic_send
        name.startsWith("phone") -> R.drawable.ic_peer_phone
        else -> R.drawable.ic_peer_message
    }, Color.WHITE).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }   // the door's word speaks (iOS k0d)

    /** The road is taken: the person's key, then the start, the link, the wait. */
    private fun start(road: BizService.Road) {
        if (waiting != null) return
        val n = e164() ?: return
        val w = ++walk
        waiting = road
        started = null
        draw()
        act.background {
            // a person still being born behind the number's page (iOS MontanaSeed.birth): the road waits for that birth
            if (MontanaSeed.birth?.let { runCatching { it.get() }.getOrDefault(false) } == false) {
                refuse(w, R.string.biz_identity_failed); return@background
            }
            val pub = Biz.myKeyNow()   // a background thread waits on the Business's thread; the screen never does
            if (pub == null) { refuse(w, R.string.biz_identity_failed); return@background }
            val s = BizService.start(n, BizPhone.subject(pub), road.channel)
            if (s == null) { refuse(w, R.string.biz_service_silent); return@background }
            act.onMain { if (w == walk) { started = s; visit(s.link) } }
            while (w == walk) {
                if (s.expiresMs < Biz.now()) { refuse(w, R.string.biz_expired); return@background }
                when (val r = BizService.wait(s.nonce, s.claim)) {
                    is BizService.Wait.Confirmed -> {
                        // proven by the core on the Business's thread, kept, and bound into every organization's files there
                        if (!Biz.keepPhone(r.attest, n)) { refuse(w, R.string.biz_not_this_number); return@background }
                        act.onMain { if (w == walk) { waiting = null; started = null; draw(); onConfirmed() } }
                        return@background
                    }
                    BizService.Wait.Again -> {}
                    BizService.Wait.Expired -> { refuse(w, R.string.biz_expired); return@background }
                    BizService.Wait.Failed -> Thread.sleep(2000)
                }
            }
        }
    }

    private fun refuse(w: Int, word: Int) = act.onMain {
        if (w != walk) return@onMain
        waiting = null
        started = null
        draw()
        AlertDialog.Builder(act).setTitle(R.string.biz_phone_word).setMessage(word).setPositiveButton(R.string.ok, null).show()
    }

    private fun cancel() { walk++; waiting = null; started = null; draw() }

    /**
     * The outer service, by the link the Business's service handed -- ONLY ON THE WEB'S SECURE ROAD (point 0, iOS road): an answer
     * that named another scheme (a telephone, a message, another app's door) is not followed; the service's answer is no word
     * of command over the phone.
     */
    private fun visit(link: String) {
        val u = runCatching { Uri.parse(link) }.getOrNull() ?: return
        if (u.scheme?.lowercase() != "https" || u.host.isNullOrEmpty()) { Log.i("Montana", "biz_phone link refused: not https"); return }
        runCatching { act.startActivity(Intent(Intent.ACTION_VIEW, u)) }
    }

    private fun waitingFace(road: BizService.Road) {
        val c = act
        view.addView(c.hstack {
            addView(c.icon(R.drawable.ic_close, Color.WHITE).apply {
                setPadding(c.dp(12), c.dp(12), c.dp(12), c.dp(12))
                contentDescription = c.getString(R.string.cancel)
                pressable { cancel() }
            }, lp(c.dp(48), c.dp(48)))
            spacer()
        }, lp())
        view.addView(ProgressBar(c), lp(MATCH, c.dp(32)))
        view.gap(12)
        view.addView(c.text(c.getString(R.string.biz_wait_title), 17f, Color.WHITE, bold = true, center = true), lp())
        view.gap(12)
        view.addView(c.text(c.getString(R.string.biz_wait_sub), 15f, MT.gray, center = true), lp())
        view.gap(12)
        // USER-DATA: the road's own name, as the Business's service names it
        view.addView(c.loginDoor(glyph(road.glyph), road.title) { started?.let { visit(it.link) } }, lp())
    }
}

/**
 * THE COUNTRIES (iOS MTBizCountrySheet, 65c08442; the author's screenshots 06.10.2026): the page with its search -- by the name
 * in the person's language, the region, the calling code -- the country the number names and the phone's own region first,
 * then every country by its name; the whole row is the target.
 */
internal fun bizCountryPage(act: MainActivity, chosen: BizCountries.Country, onChoose: (BizCountries.Country) -> Unit, onClose: () -> Unit): View {
    val c = act
    val locale = c.resources.configuration.locales[0]
    val home = BizCountries.initial(c)
    val first = if (home === chosen) listOf(chosen) else listOf(chosen, home)
    val order = java.text.Collator.getInstance(locale)
    val rest = BizCountries.all.filter { k -> first.none { it === k } }
        .sortedWith { a, b -> order.compare(BizCountries.name(c, a), BizCountries.name(c, b)) }
    val query = EditText(c)
    val sheet = BizSheet(act, c.getString(R.string.biz_choose_country), onClose, cross = true, head = c.hstack {
        setPadding(dp(16), dp(4), dp(16), dp(10))
        addView(c.hstack {
            background = c.glassPlate()
            setPadding(dp(12), 0, dp(12), 0)
            addView(c.icon(R.drawable.ic_search, MT.gray, 20), lp(dp(20), dp(20)).apply { marginEnd = dp(8) })
            addView(query.apply {
                hint = c.getString(R.string.biz_search_country)
                setHintTextColor(MT.gray); setTextColor(Color.WHITE); textSize = 17f
                background = null
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT
                imeOptions = EditorInfo.IME_ACTION_SEARCH
                setPadding(0, 0, 0, 0)
            }, lp(0, WRAP, 1f))
        }, lp(0, dp(44), 1f))
    })
    fun row(k: BizCountries.Country): View = c.hstack {
        setPadding(dp(16), dp(8), dp(14), dp(8)); minimumHeight = dp(48); bizWhole()
        // USER-DATA: a country's flag, its name in the system's words, its calling code
        addView(c.text(BizCountries.flag(k), 22f), lp(WRAP, WRAP).apply { marginEnd = dp(14) })
        addView(c.text(BizCountries.name(c, k) + " (+" + k.code + ")", 16f), lp(0, WRAP, 1f))
        pressable { onChoose(k); onClose() }
    }
    fun fill(typed: String) {
        val q = typed.trim()
        val body = sheet.body
        body.removeAllViews()
        if (q.isEmpty()) {
            body.section(null, null, *first.map { row(it) }.toTypedArray())
            body.section(null, null, *rest.map { row(it) }.toTypedArray())
            return
        }
        val digits = q.filter { it in '0'..'9' }
        val low = q.lowercase(locale)
        val found = (first + rest).filter { k ->
            BizCountries.name(c, k).lowercase(locale).contains(low) || k.region.lowercase(locale).contains(low) ||
                (digits.isNotEmpty() && k.code.startsWith(digits))
        }
        if (found.isNotEmpty()) body.section(null, null, *found.map { row(it) }.toTypedArray())
    }
    query.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
        override fun afterTextChanged(s: Editable?) = fill(s?.toString() ?: "")
    })
    // the page follows the keyboard of its search: the list ends above it
    sheet.page.setOnApplyWindowInsetsListener { v, insets ->
        val b = barsAndKeys(insets)
        v.setPadding(b[0], b[1], b[2], b[3])
        insets
    }
    sheet.page.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = v.requestApplyInsets()
        override fun onViewDetachedFromWindow(v: View) {}
    })
    fill("")
    return sheet.page
}

/**
 * THE FIRST SCREEN OF MONTANA BUSINESS (iOS MontanaOnboardingView.doors and .phone of the Business): the author's glass icon,
 * the title, and the doors pressed to the foot — the Montana door (the Messenger's own road to a seed: a new one, or the 24
 * words) and under it the number's door in the author's burgundy (the author's word 06.10: «"By phone number" in the same
 * style as our Montana button, only in our Burgundy red»). The number's door makes the person at once by the one road of a
 * birth and opens the number's page; the 24 words are not shown — they wait in Settings (Save 24 words). Confirmed or not,
 * the path goes on to the name, as in the Messenger's first launch, and into the app.
 */
class BizLogin(private val act: MainActivity, private val onDone: () -> Unit) {
    val view = FrameLayout(act)
    private val content = FrameLayout(act)
    /** The person this first screen made at the number's door: through the Montana door they walk on as themselves. */
    private var bornHere: String? = if (MontanaSeed.hasSeed) MontanaSeed.mnemonic else null

    init {
        view.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
        entry()
    }

    private fun show(page: View) {
        content.removeAllViews()
        act.setBackdrop(R.drawable.montana_burgundy)   // the pages' ground by default (ChatWall.BY_DEFAULT)
        content.addView(page, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun page(build: LinearLayout.() -> Unit) = act.vstack {
        setPadding(dp(24), dp(24), dp(24), dp(24))
        build()
    }

    private fun entry() {
        act.back = null
        val c = act
        show(page {
            spacer()
            // the author's crest, its tile as the launcher shows it (CrestView, iOS MTAppCrest)
            addView(CrestView(c), lp(dp(120), dp(120)))
            gap(22)
            addView(c.text(c.getString(R.string.sign_in_title), 28f, Color.WHITE, bold = true, center = true), lp())   // the Messenger's own words (iOS bd6c2415)
            gap(6)
            addView(c.text(c.getString(R.string.biz_login_sub), 17f, MT.gray, center = true), lp())
            spacer()
            addView(c.loginDoor(R.drawable.logo, c.getString(R.string.continue_montana)) { montanaRoad() }, lp())
            gap(10)
            addView(phoneDoor(), lp())
            gap(14)
            addView(c.linkedText(c.getString(R.string.sign_in_footer), 11f, MT.gray, MT.withAlpha(Color.WHITE, 0.8f)) { url ->
                when (url) {
                    "montana://terms" -> act.showTermsSheet()
                    "montana://privacy" -> runCatching { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL))) }
                }
            }.apply { setPadding(dp(12), 0, dp(12), 0) }, lp())
        })
    }

    /**
     * THE NUMBER'S DOOR (iOS 6b5a8dec; the author's word 06.10.2026 11:4x MSK: «make the button of the sign-in by number like the
     * system's iOS one, in our bubbles' colour, and fast»): the platform's prominent button in my bubbles' blue (MT.blue, iOS
     * BT.mF1). The tap opens the number's page at once; the person is born behind it by the core's one door of birth
     * (MontanaSeed.birth), and the number's road waits for that birth. A birth that could not be stored says so and returns.
     */
    private fun phoneDoor(): View = act.prominentDoor(R.drawable.ic_peer_phone, act.getString(R.string.biz_phone_door), MT.blue) {
        if (!MontanaSeed.hasSeed && MontanaSeed.birth == null) {
            MontanaSeed.birth = java.util.concurrent.CompletableFuture.supplyAsync {
                val keys = SeedKeys.generate()
                val stored = keys != null && MontanaSeed.enter(keys)
                act.onMain {
                    if (stored) { bornHere = keys!!.mnemonic; Biz.forgetKey() }
                    else {
                        MontanaSeed.birth = null
                        entry()
                        AlertDialog.Builder(act).setTitle(R.string.store_failed_title).setMessage(R.string.store_failed_msg)
                            .setPositiveButton(R.string.got_it, null).show()
                    }
                }
                stored
            }
        }
        phonePage()
    }

    /**
     * THE NUMBER'S PAGE (iOS MontanaOnboardingView.phone, 65c08442; the author's word 06.10 10:3x: «as on the screenshots»): the
     * person was born at the door, and here stand, under the path's one back mark, the large title, the line under it and the
     * number's door -- its field and «Next», which asks how the code should come. Without a living service one honest line and
     * the way on without a number; the number is confirmed later from the organizations' page.
     */
    private fun phonePage() {
        val c = act
        act.back = { entry() }
        show(FrameLayout(c).apply {
            addView(c.vstack(Gravity.NO_GRAVITY) {
                setPadding(dp(24), dp(56), dp(24), dp(24))   // iOS padding(24), and 28 more under the back mark
                addView(c.text(c.getString(R.string.biz_join_title), 34f, Color.WHITE, bold = true), lp())
                gap(10)
                addView(c.text(c.getString(R.string.biz_join_sub), 20f, Color.WHITE), lp())
                gap(34)
                addView(BizPhoneDoor(act, onWithout = { name() }) { name() }.view, lp(MATCH, 0, 1f))
            }, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(roundMark(c, R.drawable.ic_arrow_back_ios_new, R.string.back) { entry() },
                FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.START).apply { setMargins(dp(12), dp(4), 0, 0) })
        })
    }

    /** The name comes next, as in the Messenger's first launch; then the app. */
    private fun name() {
        act.back = null
        content.removeAllViews()
        act.setBackdrop(null)
        content.addView(Profile(act, onboarding = true) { finish() }.view, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun finish() { Prefs.setBool(ENTERED, true); onDone() }

    /** THE MONTANA DOOR: the Messenger's own road to a seed (a new one, the 24 words), and back here by its back. */
    private fun montanaRoad() {
        val road = Onboarding(act, start = 0, onLeave = { content.removeAllViews(); entry() }, montana = true, born = bornHere) { finish() }
        content.removeAllViews()
        content.addView(road.view, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    companion object {
        /** The person came in on this phone — by the number's door or by the Montana road. */
        const val ENTERED = "biz.entered"
        val entered: Boolean get() = Prefs.bool(ENTERED, false)
    }
}
