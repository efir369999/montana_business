package quest.montana.app

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast

/**
 * THE SCANNER (iOS ScanMeetingView): the back camera over the whole page, a square where the code goes, the words under it,
 * «Paste a link» for a link that came by another road. Every frame's brightness goes to our own reader (QrReader) off the
 * main thread — one at a time, the frames between are let go; the first Montana link read closes the page and opens the
 * meeting (the one resolver). The platform's own Camera2, no library.
 */
/**
 * With [onCode] the scanner reads for someone else (iOS SafetyNumberView's scan): every code read is handed to it off the main
 * thread; «true» ends the scan and closes the page, «false» keeps the camera looking. No invitation is opened, no link pasted.
 */
/** The scanner's line under the square, found by this tag: a reader for someone else may say there why a code was passed over. */
const val SCAN_HINT_TAG = "scan.hint"

fun scannerPage(act: MainActivity, onClose: () -> Unit, hint: String? = null, onCode: ((String) -> Boolean)? = null): View {
    val c: Context = act
    val texture = TextureView(c)
    var cam: CameraDevice? = null
    var session: CameraCaptureSession? = null
    var reader: ImageReader? = null
    val thread = HandlerThread("montana-scan").apply { start() }
    val bg = Handler(thread.looper)
    val busy = java.util.concurrent.atomic.AtomicBoolean(false)
    val done = java.util.concurrent.atomic.AtomicBoolean(false)
    var toldForeign = false

    fun finish(link: String) {
        if (!done.compareAndSet(false, true)) return
        act.onMain { onClose(); openInvitation(act, link) }
    }

    @SuppressLint("MissingPermission")
    fun open(st: SurfaceTexture, vw: Int, vh: Int) {
        val cm = c.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK } ?: return
        val map = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        // A 16:9 picture near 1280×720: enough modules for any card code, light enough for every frame.
        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888)
        val size = sizes.filter { it.width * 9 == it.height * 16 && it.width <= 1920 }.minByOrNull { kotlin.math.abs(it.width - 1280) } ?: sizes.first()
        st.setDefaultBufferSize(size.width, size.height)
        fitCrop(texture, size, vw, vh)
        val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        reader = r
        r.setOnImageAvailableListener({ ir ->
            val img = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
            if (done.get() || !busy.compareAndSet(false, true)) { img.close(); return@setOnImageAvailableListener }
            val p = img.planes[0]
            val w = img.width; val h = img.height; val stride = p.rowStride
            val buf = p.buffer
            val lum = ByteArray(buf.remaining()); buf.get(lum)
            img.close()
            val text = runCatching { QrReader.read(lum, w, h, stride) }.getOrNull()
            busy.set(false)
            if (text != null && onCode != null) {
                if (onCode(text) && done.compareAndSet(false, true)) act.onMain { onClose() }
            } else if (text != null) {
                if (Meeting.looksLikeInvitation(text)) finish(text)
                else if (!toldForeign) { toldForeign = true; act.onMain { Toast.makeText(c, R.string.invite_refused, Toast.LENGTH_SHORT).show() } }
            }
        }, bg)
        cm.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) {
                cam = d
                val preview = Surface(st)
                @Suppress("DEPRECATION")
                d.createCaptureSession(listOf(preview, r.surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(preview); addTarget(r.surface)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        }.build()
                        runCatching { s.setRepeatingRequest(req, null, bg) }
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {}
                }, bg)
            }
            override fun onDisconnected(d: CameraDevice) { d.close() }
            override fun onError(d: CameraDevice, e: Int) { d.close() }
        }, bg)
    }
    fun release() {
        runCatching { session?.close() }; runCatching { cam?.close() }; runCatching { reader?.close() }
        session = null; cam = null; reader = null
        thread.quitSafely()
    }

    texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
            if (c.checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) open(st, w, h)
        }
        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { release(); return true }
        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
    }

    val page = FrameLayout(c).apply {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        addView(texture, FrameLayout.LayoutParams(MATCH, MATCH))
        // The square where the code goes.
        addView(View(c).apply {
            background = GradientDrawable().apply { cornerRadius = c.dp(24).toFloat(); setStroke(c.dp(3), Color.WHITE) }
        }, FrameLayout.LayoutParams(c.dp(260), c.dp(260), Gravity.CENTER))
        addView(c.text(hint ?: c.getString(R.string.scan_hint), 16f, Color.WHITE, center = true).apply {
            tag = SCAN_HINT_TAG
            setShadowLayer(8f, 0f, 0f, Color.BLACK)
        }, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER).apply { topMargin = c.dp(180); setMargins(c.dp(24), c.dp(180), c.dp(24), 0) })
        addView(c.icon(R.drawable.ic_close, Color.WHITE).apply {
            setPadding(c.dp(12), c.dp(12), c.dp(12), c.dp(12))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(120, 0, 0, 0)) }
            pressable { release(); onClose() }
        }, FrameLayout.LayoutParams(c.dp(48), c.dp(48), Gravity.TOP or Gravity.START).apply { setMargins(c.dp(16), c.dp(16), 0, 0) })
        // «Paste a link» (iOS: the scanner's own paste): a link that came by mail or a messenger opens the same meeting.
        if (onCode == null) addView(c.text(c.getString(R.string.paste_link), 16f, Color.BLACK, bold = true, center = true).apply {
            background = c.rounded(Color.WHITE, 22)
            setPadding(c.dp(22), c.dp(12), c.dp(22), c.dp(12))
            pressable {
                val clip = c.getSystemService(ClipboardManager::class.java).primaryClip
                val t = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(c)?.toString()?.trim().orEmpty()
                val link = Regex("(https://\\S+|montana://\\S+)").find(t)?.value ?: t
                if (Meeting.looksLikeInvitation(link)) { release(); finish(link) }
                else Toast.makeText(c, R.string.no_invite_in_clipboard, Toast.LENGTH_SHORT).show()
            }
        }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = c.dp(48) })
    }
    if (c.checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
        act.askCamera { granted ->
            if (granted && texture.isAvailable) texture.surfaceTexture?.let { open(it, texture.width, texture.height) }
            else if (!granted) Toast.makeText(c, R.string.camera_needed, Toast.LENGTH_LONG).show()
        }
    }
    return page
}

/** The camera's picture fills the page without stretching (a centre crop of the portrait-turned 16:9 frame). */
private fun fitCrop(tv: TextureView, size: Size, vw: Int, vh: Int) {
    val contentAspect = size.height.toFloat() / size.width   // the frame turned upright
    val viewAspect = vw.toFloat() / vh
    val m = Matrix()
    if (viewAspect < contentAspect) m.setScale(contentAspect / viewAspect, 1f, vw / 2f, vh / 2f)
    else m.setScale(1f, viewAspect / contentAspect, vw / 2f, vh / 2f)
    tv.post { tv.setTransform(m) }
}
