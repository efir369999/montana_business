package quest.montana.app

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast

/**
 * THE COMPOSE BAR (iOS ChatInputBar): one bar for every chat, in two shapes the person chooses with its own arrow — and the
 * choice outlives the chat and the launch (iOS: «the row is folded or open by the person's own choice»).
 *
 *   folded:  [⌄] [ the words ………… ☺ ] [🎙 / ➤]
 *   open:    [ the words across the whole width ]
 *            [⌃]  ☺  🖼  🪪  📄  ➤  👤  [🎙 / ➤]
 *
 * The send artwork stands at the end while there are words; without them, the recording button — a tap switches the voice and
 * the video message (iOS composeMediaMode). The field itself never leaves its place: only what stands around it changes.
 */
class ChatInputBar(private val act: MainActivity, val field: EditText, private val onSend: () -> Unit) : LinearLayout(act) {
    private val c: Context = act
    private var open = Prefs.bool(OPEN_KEY, false)

    private val fieldRow = LinearLayout(c).apply { orientation = HORIZONTAL; gravity = Gravity.BOTTOM }
    private val actionRow = LinearLayout(c).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val plate = FrameLayout(c)                 // the field's glass, with the emoji key inside while folded
    private val unfoldKey = glassKey(R.drawable.ic_chevron_down, R.string.compose_open) { setOpen(true) }
    private val emojiInside = ImageView(c).apply {
        setImageResource(R.drawable.ic_face_smiling); imageTintList = android.content.res.ColorStateList.valueOf(GLYPH)
        contentDescription = c.getString(R.string.emoji)
        setPadding(dp(9), dp(9), dp(9), dp(9))
        pressable { showKeys() }
        setOnLongClickListener { onStickers?.invoke(); onStickers != null }   // a hold on the emoji key: the stickers
    }
    private val sendColumn = FrameLayout(c)
    private val send = ImageView(c).apply {
        setImageResource(R.drawable.send_button); scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = c.getString(R.string.send)
        pressable { if (field.text.isNotBlank()) onSend() }
        setOnLongClickListener { if (field.text.isNotBlank()) onSendLater?.invoke(it); onSendLater != null }   // a hold: «Send later» (iOS)
    }
    private val record = RecordKey(c)

    init {
        orientation = VERTICAL
        setPadding(dp(16), dp(6), dp(16), dp(8))
        field.apply {
            hint = c.getString(R.string.message_hint)
            setHintTextColor(MT.gray); setTextColor(Color.WHITE)
            textSize = 17f
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 6
            minHeight = dp(TIER)
        }
        plate.background = c.glassPlate().apply { cornerRadius = dp(18).toFloat() }
        plate.addView(field, FrameLayout.LayoutParams(MATCH, WRAP))
        plate.addView(emojiInside, FrameLayout.LayoutParams(dp(TIER), dp(TIER), Gravity.BOTTOM or Gravity.END))

        sendColumn.addView(send, FrameLayout.LayoutParams(dp(TIER), dp(TIER), Gravity.CENTER))
        sendColumn.addView(record, FrameLayout.LayoutParams(dp(TIER), dp(TIER), Gravity.CENTER))

        addView(fieldRow, lp())
        addView(actionRow, lp().apply { topMargin = dp(10) })

        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun afterTextChanged(s: Editable?) { syncSend() }
        })
        shape()
    }

    /** The one writer of the shape is the finger (iOS composeOpen); it is kept for the next chat and the next launch. */
    private fun setOpen(v: Boolean) {
        open = v
        Prefs.setBool(OPEN_KEY, v)
        shape()
    }

    private fun shape() {
        (sendColumn.parent as? LinearLayout)?.removeView(sendColumn)
        (unfoldKey.parent as? LinearLayout)?.removeView(unfoldKey)
        (plate.parent as? LinearLayout)?.removeView(plate)
        fieldRow.removeAllViews(); actionRow.removeAllViews()
        if (open) {
            // the words take the whole upper tier; the room of the emoji key is given back to them
            fieldRow.addView(plate, lp(0, WRAP, 1f))
            emojiInside.visibility = View.GONE
            field.setPadding(dp(14), dp(8), dp(14), dp(8))
            val keys = listOf(
                glassKey(R.drawable.ic_chevron_up, R.string.compose_fold) { setOpen(false) },
                glassKey(R.drawable.ic_face_smiling, R.string.emoji) { showKeys() }.apply { setOnLongClickListener { onStickers?.invoke(); onStickers != null } },
                glassKey(R.drawable.ic_compose_photo, R.string.gallery) { onGallery?.invoke() ?: notYet() },
                glassKey(R.drawable.ic_compose_card, R.string.business_card) { notYet() },
                glassKey(R.drawable.ic_compose_doc, R.string.file) { onFile?.invoke() ?: notYet() },
                glassKey(R.drawable.ic_compose_location, R.string.location) { notYet() },
                glassKey(R.drawable.ic_compose_contact, R.string.contact) { onContact?.invoke() ?: notYet() },
                sendColumn)
            // the first and the last flush with the row's edges, the ones between shared evenly
            keys.forEachIndexed { i, k ->
                if (i > 0) actionRow.addView(View(c), lp(0, 1, 1f))
                actionRow.addView(k, lp(dp(TIER), dp(TIER)))
            }
            actionRow.visibility = View.VISIBLE
        } else {
            fieldRow.addView(unfoldKey, lp(dp(TIER), dp(TIER)).apply { marginEnd = dp(10) })
            fieldRow.addView(plate, lp(0, WRAP, 1f))
            fieldRow.addView(sendColumn, lp(dp(TIER), dp(TIER)).apply { marginStart = dp(10) })
            emojiInside.visibility = View.VISIBLE
            field.setPadding(dp(14), dp(8), dp(TIER + 4), dp(8))
            actionRow.visibility = View.GONE
        }
        syncSend()
    }

    /** Words → the send artwork; no words → the recording button (iOS sendColumn). */
    fun syncSend() {
        val words = field.text.isNotBlank()
        send.visibility = if (words) View.VISIBLE else View.GONE
        record.visibility = if (words) View.GONE else View.VISIBLE
    }

    private fun showKeys() {
        field.requestFocus()
        c.getSystemService(InputMethodManager::class.java).showSoftInput(field, 0)
    }

    /** What iOS does here and Android does not do yet says so in words instead of doing nothing. */
    /** The gallery and file keys' deeds, given by the page that owns the letters (the conversation sends media). */
    var onGallery: (() -> Unit)? = null
    var onSendLater: ((View) -> Unit)? = null   // the send key's hold (Schedule.kt)
    var onContact: (() -> Unit)? = null   // the phone's contact as a letter (iOS ContactPicker)
    var onFile: (() -> Unit)? = null
    var onStickers: (() -> Unit)? = null   // the sticker panel, opened by a hold on the emoji key (Stickers.kt)
    /** The voice's deeds: start the tape (false — it could not start), and end it (true — dropped, not sent). */
    var onVoiceStart: (() -> Boolean)? = null
    var onVoiceEnd: ((Boolean) -> Unit)? = null
    /** The tape's time is up (the note's 6:39): the hold ends as if the finger let go. */
    fun stopRecording() = record.endHold()
    /** The bin of a locked tape (iOS «Cancel» on the bar's line): the tape is dropped. */
    fun cancelRecording() = record.cancelHold()
    /** THE LOCK (iOS MTHoldOverlayView lockPlate): the finger's way up, 0…1, shown over the key; 1 — the tape rolls hands-free. */
    var onRecLift: ((Float) -> Unit)? = null
    var onRecLocked: (() -> Unit)? = null
    /** The tape ended (sent, dropped, or cut by its minute): the page takes its lock and its controls away. */
    var onRecIdle: (() -> Unit)? = null

    /** The round note's deeds, the same shape as the voice's (iOS MontanaVideoNoteHold under the held camera mark). */
    var onNoteStart: (() -> Boolean)? = null
    var onNoteEnd: ((Boolean) -> Unit)? = null

    private fun notYet() = Toast.makeText(c, R.string.compose_not_yet, Toast.LENGTH_SHORT).show()

    /** One round glass key at the tier's height with the grey glyph (iOS .montanaOctagon(square: true, bar: true)). */
    private fun glassKey(res: Int, label: Int, onTap: () -> Unit): View = FrameLayout(c).apply {
        background = c.glassPlate(oval = true)
        contentDescription = c.getString(label)
        addView(c.icon(res, GLYPH), FrameLayout.LayoutParams(dp(21), dp(21), Gravity.CENTER))
        pressable(onTap)
    }

    /**
     * THE RECORDING BUTTON (iOS MTComposeMark .voice / .video): the artwork, grown by a third and cut round. A tap switches
     * the microphone and the camera, and the choice is remembered; a hold would record — Android records nothing yet.
     */
    private inner class RecordKey(c: Context) : FrameLayout(c) {
        private val face = ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER; scaleX = 1.33f; scaleY = 1.33f }
        init {
            addView(face, LayoutParams(MATCH, MATCH))
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setOval(0, 0, v.width, v.height) }
            }
            clipToOutline = true
            draw()
            setOnClickListener {
                // a locked tape's key is its send arrow (iOS: the crown's arrow sends); otherwise a tap switches the mark
                if (locked) { finish(cancel = false); return@setOnClickListener }
                Prefs.setStr(MODE_KEY, if (video) "mic" else "video")
                draw()
            }
            // THE HOLD RECORDS (iOS: hold the mark, speak, let go to send, slide left to drop) — when the page gives the voice's deeds.
            setOnLongClickListener {
                // The mark's mode decides the tape: the microphone's voice or the camera's round note (iOS MTComposeMark).
                val start = if (video) onNoteStart else onVoiceStart
                if (start == null) { notYet(); return@setOnLongClickListener true }
                recordingNote = video
                recording = start.invoke()
                if (recording) { startX = lastX; startY = lastY; field.isEnabled = false; field.hint = c.getString(R.string.voice_hint); onRecLift?.invoke(0f) }
                true
            }
            setOnTouchListener { v, e ->
                lastX = e.rawX; lastY = e.rawY
                if (recording && !locked) when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_MOVE -> {
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        // THE WAY UP TO THE LOCK (iOS lockWay 72): the plate's padlock closes as the finger rises
                        val lift = ((startY - e.rawY) / dp(LOCK_WAY)).coerceIn(0f, 1f)
                        onRecLift?.invoke(lift)
                        if (lift >= 1f) {
                            locked = true; draw(); field.hint = ""; onRecLocked?.invoke()   // no «release to send» over a locked tape (iOS)
                            v.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                        } else if (startX - e.rawX > dp(120)) finish(cancel = true)
                    }
                    android.view.MotionEvent.ACTION_UP -> { finish(cancel = false); return@setOnTouchListener true }
                    android.view.MotionEvent.ACTION_CANCEL -> finish(cancel = true)
                }
                else if (locked && e.actionMasked == android.view.MotionEvent.ACTION_UP && e.eventTime - e.downTime > 400) return@setOnTouchListener true   // the finger that locked it lets go: the tape rolls on
                false
            }
        }
        private var recording = false
        private var locked = false
        private var startX = 0f
        private var startY = 0f
        private var lastX = 0f
        private var lastY = 0f
        private fun finish(cancel: Boolean) {
            if (!recording) return
            recording = false
            if (locked) { locked = false; draw() }
            onRecIdle?.invoke()
            field.isEnabled = true; field.hint = c.getString(R.string.message_hint)
            if (recordingNote) onNoteEnd?.invoke(cancel) else onVoiceEnd?.invoke(cancel)
        }
        private var recordingNote = false
        fun endHold() = finish(cancel = false)
        fun cancelHold() = finish(cancel = true)
        private val video get() = Prefs.str(MODE_KEY, "mic") == "video"
        private fun draw() {
            if (locked) { face.setImageResource(R.drawable.send_button); contentDescription = c.getString(R.string.send); return }
            face.setImageResource(if (video) R.drawable.video_record_button else R.drawable.voice_record_button)
            contentDescription = c.getString(if (video) R.string.record_video else R.string.record_voice)
        }
    }

    private companion object {
        const val LOCK_WAY = 72                            // iOS lockWay: the finger's way up to the lock, in points
        const val TIER = 36                                // iOS MontanaOctagon.composeHeight
        val GLYPH = Color.rgb(204, 204, 204)               // iOS MontanaOctagon.barGlyph
        const val OPEN_KEY = "composeOpen"
        const val MODE_KEY = "composeMediaMode"            // iOS @AppStorage("composeMediaMode")
    }
}
