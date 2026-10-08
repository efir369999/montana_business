package quest.montana.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File

/**
 * WHAT MONTANA BUSINESS HANDS TO THE SYSTEM'S SHEET (the constitution's point 0: privacy, elegance, safety): the chains of an
 * organization, a place's label, an invitation's poster, a payroll's CSV. They are written into the app's cache -- no backup
 * carries it, the system clears it -- one name per thing, written over each time, and handed out by the export's own door
 * (BizExportProvider), read-only and only with a granted address. Nothing of it lies in the media folder (the one the
 * Messenger's received files live in); what an earlier build left there is swept away.
 */
object BizExport {
    fun dir(c: Context) = File(c.cacheDir, "export").apply { mkdirs() }
    /**
     * A name a person gave, made a file's name (iOS MTBizPlace.fileName): letters, numbers and spaces, sixty-four at most, trimmed;
     * empty -- the fallback.
     */
    fun fileName(s: String, fallback: String): String =
        s.filter { it.isLetter() || it.category.code.startsWith("N") || it == ' ' }.take(64).trim().ifEmpty { fallback }
    /**
     * THE ONE DOOR OUT (iOS MTBizPlace.export, point 0): the export folder is emptied before every export, so no earlier copy of
     * an organization's chains, a payroll, a label or a poster stays behind; then the files of this export are written.
     */
    fun export(c: Context, files: List<Pair<String, ByteArray>>): List<File> {
        val d = dir(c)
        d.listFiles()?.forEach { it.delete() }
        return files.mapNotNull { (name, bytes) -> File(d, name).takeIf { f -> runCatching { f.writeBytes(bytes) }.isSuccess } }
    }
    /** How long a file handed to the system's sheet stays: long enough for the receiving app to read it, no longer. */
    private const val STALE_MS = 3_600_000L
    /**
     * WHAT WAS HANDED OUT LONG AGO GOES (K.0): an export lies in the app's cache in the clear -- the receiving app reads it after
     * the sheet closes -- so the files older than an hour are emptied and taken away whenever the Business page opens.
     */
    fun sweepStale(c: Context) {
        val now = System.currentTimeMillis()
        dir(c).listFiles()?.filter { now - it.lastModified() > STALE_MS }?.forEach { f -> runCatching { f.writeBytes(ByteArray(0)) }; f.delete() }
    }
    fun uri(c: Context, f: File): Uri = Uri.Builder().scheme("content").authority(c.packageName + ".export").appendPath(f.name).build()
    /** The system's sheet, with the files granted to it for reading. */
    fun share(act: MainActivity, files: List<File>, type: String, words: String? = null) {
        if (files.isEmpty()) return
        val uris = ArrayList(files.map { uri(act, it) })
        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
            else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        send.setType(type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (words != null) send.putExtra(Intent.EXTRA_TEXT, words)
        act.startActivity(Intent.createChooser(send, null))
    }
    /**
     * WHAT EARLIER BUILDS LEFT IN THE MEDIA FOLDER (builds 1-7 wrote the chains, the labels and the poster there) goes away, by
     * the files' own names: the chains (.mtbiz), a label (label-TAG.png) and the poster (poster.png).
     */
    fun sweepMedia(c: Context) {
        val label = Regex("label-[0-9A-F]{8}[.]png")
        Media.dir(c).listFiles()?.filter { it.name.endsWith(".mtbiz") || it.name == "poster.png" || label.matches(it.name) }?.forEach { it.delete() }
    }
}

/** THE EXPORT'S DOOR (the platform's ContentProvider, no library): the export folder alone, read-only, a granted address only. */
class BizExportProvider : ContentProvider() {
    override fun onCreate() = true
    private fun fileOf(uri: Uri): File? {
        val c = context ?: return null
        val name = uri.lastPathSegment ?: return null
        if (name.contains('/') || name.startsWith(".")) return null
        return File(BizExport.dir(c), name).takeIf { it.exists() }
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? =
        fileOf(uri)?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }
    override fun getType(uri: Uri): String? =
        uri.lastPathSegment?.substringAfterLast('.', "")?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.lowercase()) } ?: "application/octet-stream"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? {
        val f = fileOf(uri) ?: return null
        return android.database.MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>(f.name, f.length())) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
