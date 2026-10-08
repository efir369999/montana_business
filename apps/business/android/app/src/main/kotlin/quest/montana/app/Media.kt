package quest.montana.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import android.util.Size
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * PICTURES, FILMS AND FILES (iOS MontanaMedia / MontanaMediaKit), byte for byte:
 *   the file is cut into 512 KiB pieces; each piece is padded (mt_e2e_pad_len), sealed under the letter's blob key with a
 *   nonce DERIVED from the key, its number and its bytes, and laid on the node under SHA-256 of the seal;
 *   the manifest {k, e, bk, sz, chunks:[{bid, cs}], n?, th?, cap?} rides in the letter as MD:<json> — or, when it outgrows
 *   the letter, sealed under a key of its own as a blob and the letter carries MD:{mref, mk, sz}.
 * The receiver fetches every piece, checks its name against its bytes, opens it, cuts the padding, appends it in order —
 * and only an assembled file is answered «delivered»; the pieces are then dropped from the node.
 */
object Media {
    const val CHUNK = 512 * 1024
    private const val LIMIT = 200L * 1024 * 1024   // what this phone carries in one letter for now
    private val inFlight = HashSet<String>()
    private val lastTry = HashMap<String, Long>()   // a piece still missing is asked again a minute later, not every round

    fun dir(c: Context) = File(c.filesDir, "media").apply { mkdirs() }
    fun file(c: Context, mid: String, ext: String) = File(dir(c), "$mid.${ext.ifEmpty { "bin" }.take(8)}")

    /** The core's padding (mt_messenger_e2e::media::pad_len): 256 floor, then 1/16 of the bit length's step. */
    fun padLen(n: Int): Int {
        if (n < 256) return 256
        val bl = 32 - Integer.numberOfLeadingZeros(n)
        val step = 1 shl (bl - 5)
        return ((n + step - 1) / step) * step
    }

    /** What a media letter says of itself, whole, when its manifest rides inside it; null for the sealed shape. */
    fun inline(text: String): JSONObject? =
        runCatching { JSONObject(text.removePrefix(Marks.MEDIA)) }.getOrNull()?.takeIf { !it.has("mref") }

    /** The manifest behind a media letter: inside it, or sealed as a blob of its own (iOS mref/mk). */
    fun manifest(text: String): JSONObject? {
        val o = runCatching { JSONObject(text.removePrefix(Marks.MEDIA)) }.getOrNull() ?: return null
        if (!o.has("mref")) return o
        val mk = runCatching { Base64.decode(o.getString("mk"), Base64.DEFAULT) }.getOrNull() ?: return null
        val sealed = Wire.getBlob(o.getString("mref")) ?: return null
        val plain = Wire.open(mk, sealed) ?: return null
        return runCatching { JSONObject(String(plain, Charsets.UTF_8)) }.getOrNull()
    }

    // ── sending ──

    class Picked(val bytes: ByteArray, val kind: String, val ext: String, val name: String?, val du: Double? = null, val wave: FloatArray? = null, val round: Boolean = false)

    /** What was picked, read whole, with its kind as iOS names kinds (img · vid · doc). */
    fun read(c: Context, uri: Uri, asDoc: Boolean): Picked? {
        val cr = c.contentResolver
        val mime = cr.getType(uri) ?: ""
        var name: String? = null
        var size = -1L
        runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) { name = cur.getString(0); size = cur.getLong(1) }
            }
        }
        if (size > LIMIT) return null
        val bytes = runCatching { cr.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() ?: return null
        val ext = (name?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() && it.length <= 8 }
            ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin").lowercase()
        val kind = when {
            asDoc -> "doc"
            mime.startsWith("image/") -> "img"
            mime.startsWith("video/") -> "vid"
            else -> "doc"
        }
        return Picked(bytes, kind, if (kind == "img" && ext == "jpeg") "jpg" else ext, name)
    }

    /** The picture's small face that rides the manifest (iOS thumbBase64): 120 points, a JPEG of at most 16 KB. */
    private fun thumb(b: Bitmap): String? {
        val scale = minOf(1f, 120f / maxOf(b.width, b.height))
        val small = Bitmap.createScaledBitmap(b, maxOf(1, (b.width * scale).toInt()), maxOf(1, (b.height * scale).toInt()), true)
        var q = 70
        while (q >= 20) {
            val out = ByteArrayOutputStream(); small.compress(Bitmap.CompressFormat.JPEG, q, out)
            if (out.size() <= 16000) return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            q -= 15
        }
        return null
    }

    /**
     * THE ONE ROAD OF A SENT FILE: the row stands at once with the local copy, the pieces are sealed and laid on the node,
     * then the letter with the manifest leaves by the ordinary post (the pieces first — the receiver must find them).
     */
    fun send(c: Context, ref: String, p: Picked, caption: String, qt: String? = null) {
        val mid = Marks.mintMid()
        val local = file(c, mid, p.ext)
        local.writeBytes(p.bytes)
        val provisional = Marks.MEDIA + JSONObject().put("k", p.kind).put("e", p.ext).put("sz", p.bytes.size).apply {
            p.name?.let { put("n", it) }; if (caption.isNotEmpty()) put("cap", caption); p.du?.let { put("du", it) }
            if (p.round) put("r", true)
            p.wave?.takeIf { it.isNotEmpty() }?.let { w -> put("wv", Base64.encodeToString(ByteArray(w.size) { (w[it] * 255).toInt().coerceIn(0, 255).toByte() }, Base64.NO_WRAP)) }
        }
        Book.edit(ref) { it.msgs.add(Msg(mid, provisional, true, Marks.birthMs(mid) ?: System.currentTimeMillis(), qt = qt, file = local.path)) }
        Thread {
            val (letter, man) = seal(c, p, caption, local) ?: run {
                Log.w("Montana", "media: the pieces did not reach the node"); Book.edit(ref) { ch -> ch.msgs.find { it.mid == mid }?.state = -1 }; return@Thread
            }
            Book.edit(ref) { ch -> ch.msgs.find { it.mid == mid }?.let { it.text = letter; it.meta = man } }
            Post.send(ref, mid, letter, qt)   // a forwarded file carries the forwarded quote (iOS forwardedQuote)
        }.start()
    }

    /** The letter and the manifest it stands for (the sender keeps its own manifest: its row draws from it). */
    private fun seal(c: Context, p: Picked, caption: String, local: File): Pair<String, String>? {
        val bk = MtBindings.nativeRandom(32) ?: return null
        val total = p.bytes.size
        val count = maxOf(1, (total + CHUNK - 1) / CHUNK)
        val sealedPieces = ArrayList<Pair<String, ByteArray>>(count)
        val chunks = JSONArray()
        for (i in 0 until count) {
            val off = i * CHUNK
            val len = minOf(CHUNK, total - off)
            val piece = p.bytes.copyOfRange(off, off + len)
            val padded = piece.copyOf(padLen(len))
            val nonce = Wire.sha(bk, Wire.le8(i.toLong()), padded).copyOf(12)
            val sealed = MtBindings.nativeSealBlob(bk, nonce, padded) ?: return null
            val bid = Wire.hex(Wire.sha(sealed))
            sealedPieces.add(bid to sealed)
            chunks.put(JSONObject().put("bid", bid).put("cs", len))
        }
        // THE CARGO MARK (iOS uploadChunks): a function of the pieces' names, and «last» on the final piece.
        val cargo = Wire.hex(Wire.sha(sealedPieces.joinToString("") { it.first }.toByteArray())).take(32)
        sealedPieces.forEachIndexed { i, (bid, sealed) ->
            var ok = false
            for (attempt in 0 until 3) { if (Wire.putBlob(bid, sealed, cargo, i == sealedPieces.size - 1)) { ok = true; break }; Thread.sleep(1500L shl attempt) }
            if (!ok) return null
        }
        val m = JSONObject().put("k", p.kind).put("e", p.ext).put("bk", Base64.encodeToString(bk, Base64.NO_WRAP)).put("sz", total).put("chunks", chunks)
        p.name?.let { if (p.kind == "doc" || it == Stickers.CARD) m.put("n", it) }   // a sticker's picture names itself (iOS docName)
        if (caption.isNotEmpty()) m.put("cap", caption)
        p.du?.let { m.put("du", Math.round(it * 10) / 10.0) }   // the tape's length is the sender's word (iOS «du»)
        if (p.round) m.put("r", true)   // a round video note (iOS «r»)
        // THE WAVE RIDES WITH THE LETTER (iOS «wv», MTWaveform.pack): one byte per line, 0…255.
        p.wave?.takeIf { it.isNotEmpty() }?.let { w -> m.put("wv", Base64.encodeToString(ByteArray(w.size) { (w[it] * 255).toInt().coerceIn(0, 255).toByte() }, Base64.NO_WRAP)) }
        preview(c, local, p.kind)?.let { b -> thumb(b)?.let { m.put("th", it) } }
        val json = m.toString()
        if ((Marks.MEDIA + json).toByteArray().size <= 1900) return (Marks.MEDIA + json) to json
        val mk = MtBindings.nativeRandom(32) ?: return null
        val sealed = Wire.seal(mk, json.toByteArray()) ?: return null
        val mbid = Wire.hex(Wire.sha(sealed))
        if (!Wire.putBlob(mbid, sealed)) return null
        return (Marks.MEDIA + JSONObject().put("mref", mbid).put("mk", Base64.encodeToString(mk, Base64.NO_WRAP)).put("sz", total)) to json
    }

    // ── receiving ──

    /** Every media letter of theirs still without its file is asked for (once at a time), and «delivered» follows the file. */
    fun fetchPending(c: Context) {
        for (ch in Book.all()) for (m in ch.msgs) if (!m.mine && m.text.startsWith(Marks.MEDIA) && m.file == null) fetch(c, ch.ref, m.mid, m.text)
    }

    fun fetch(c: Context, ref: String, mid: String, text: String) {
        synchronized(inFlight) {
            val now = System.currentTimeMillis()
            if ((lastTry[mid] ?: 0) > now - 60_000 || !inFlight.add(mid)) return
            lastTry[mid] = now
        }
        Thread {
            try {
                val man = manifest(text) ?: return@Thread
                val dest = file(c, mid, man.optString("e"))
                if (!download(man, dest)) return@Thread
                Book.edit(ref) { ch -> ch.msgs.find { it.mid == mid }?.let { it.file = dest.path; it.meta = man.toString() } }
                Post.receiptFor(ref, mid)
                drop(man)
            } catch (e: Exception) { Log.w("Montana", "media fetch: ${e.javaClass.simpleName}") }
            finally { synchronized(inFlight) { inFlight.remove(mid) } }
        }.start()
    }

    private fun download(man: JSONObject, dest: File): Boolean {
        val bk = runCatching { Base64.decode(man.getString("bk"), Base64.DEFAULT) }.getOrNull() ?: return false
        val chunks = man.optJSONArray("chunks") ?: return false
        val tmp = File(dest.path + ".part")
        tmp.outputStream().use { out ->
            for (i in 0 until chunks.length()) {
                val ch = chunks.getJSONObject(i)
                val bid = ch.getString("bid"); val cs = ch.getInt("cs")
                var piece: ByteArray? = null
                for (attempt in 0 until 3) {
                    val sealed = Wire.getBlob(bid)
                    if (sealed != null && Wire.hex(Wire.sha(sealed)) == bid) {   // the name is the bytes: integrity
                        piece = Wire.open(bk, sealed)?.let { if (cs <= it.size) it.copyOf(cs) else it }
                        if (piece != null) break
                    }
                    Thread.sleep(500L shl attempt)
                }
                out.write(piece ?: return false)
            }
        }
        return tmp.renameTo(dest)
    }

    /** The file is assembled — the node's pieces serve nobody (iOS dropChunks). */
    private fun drop(man: JSONObject) {
        val a = man.optJSONArray("chunks") ?: return
        val bids = JSONArray(); for (i in 0 until a.length()) bids.put(a.getJSONObject(i).optString("bid"))
        for (door in MontanaCard.doors) Wire.post(door, "/blob-drop", JSONObject().put("bids", bids), 8000)
    }

    // ── showing ──

    /** A picture fit for a bubble: the file sampled down, the first frame of a film, or null. */
    fun preview(c: Context, f: File, kind: String, maxPx: Int = 900): Bitmap? = runCatching {
        when (kind) {
            "img" -> {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, o)
                var s = 1; while (o.outWidth / (s * 2) >= maxPx || o.outHeight / (s * 2) >= maxPx) s *= 2
                // THE CAMERA'S TURN (EXIF orientation): a phone photo lies on its side in its bytes and says how to stand it up.
                BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })?.let { upright(f, it) }
            }
            "vid" -> ThumbnailUtils.createVideoThumbnail(f, Size(maxPx, maxPx), null)
            else -> null
        }
    }.getOrNull()

    /**
     * THE PICTURE STANDS AS IT WAS TAKEN: a camera writes the pixels as the sensor lies and the turn in the EXIF tag beside
     * them (iOS UIImage honours it by itself); the decoder here does not, so the turn — and a mirror — is applied here.
     */
    private fun upright(f: File, b: Bitmap): Bitmap {
        val o = runCatching { ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val m = Matrix()
        when (o) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return b
        }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    fun thumbOf(man: JSONObject?): Bitmap? = man?.optString("th")?.takeIf { it.isNotEmpty() }?.let {
        runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull()?.let { b -> BitmapFactory.decodeByteArray(b, 0, b.size) }
    }

    fun uri(c: Context, f: File): Uri = Uri.parse("content://${c.packageName}.media/${f.name}")
}

/**
 * THE APP'S OWN DOOR TO ITS FILES (the platform's ContentProvider, no library): a received file is handed to the system's
 * viewer or share sheet by a content address; only the media folder answers, read-only, and only with a granted address.
 */
class MediaProvider : ContentProvider() {
    override fun onCreate() = true
    private fun fileOf(uri: Uri): File? {
        val c = context ?: return null
        val name = uri.lastPathSegment ?: return null
        if (name.contains('/') || name.startsWith(".")) return null
        return File(Media.dir(c), name).takeIf { it.exists() }
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? =
        fileOf(uri)?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }
    override fun getType(uri: Uri): String? =
        uri.lastPathSegment?.substringAfterLast('.', "")?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.lowercase()) }
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? {
        val f = fileOf(uri) ?: return null
        return android.database.MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>(f.name, f.length())) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
