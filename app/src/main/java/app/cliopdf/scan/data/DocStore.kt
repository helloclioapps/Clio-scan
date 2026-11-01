package app.cliopdf.scan.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** A PDF kept privately inside the app (no other app can read it unless the user shares it). */
data class Doc(
    val id: String,
    val title: String,
    val file: File,
    val pages: Int,
    val size: Long,
    val created: Long,
    val modified: Long,
    val searchable: Boolean,
    val sentTo: String? = null,
)

/**
 * Documents live in filesDir/documents as <id>.pdf, with a small JSON index for titles and flags.
 */
class DocStore(private val context: Context) {
    val dir: File = File(context.filesDir, "documents").apply { mkdirs() }
    private val index = File(dir, "index.json")

    private fun readIndex(): JSONObject = try {
        if (index.exists()) JSONObject(index.readText()) else JSONObject()
    } catch (_: Exception) {
        JSONObject()
    }

    private fun writeIndex(o: JSONObject) {
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(index)
    }

    @Synchronized
    fun list(): List<Doc> {
        val idx = readIndex()
        val docs = mutableListOf<Doc>()
        dir.listFiles { f -> f.extension.equals("pdf", true) }?.forEach { f ->
            val id = f.nameWithoutExtension
            val m = idx.optJSONObject(id) ?: JSONObject()
            docs += Doc(
                id = id,
                title = m.optString("title", "Document"),
                file = f,
                pages = m.optInt("pages", 0),
                size = f.length(),
                created = m.optLong("created", f.lastModified()),
                modified = f.lastModified(),
                searchable = m.optBoolean("searchable", false),
                sentTo = m.optString("sentTo", "").ifEmpty { null },
            )
        }
        return docs.sortedByDescending { it.modified }
    }

    fun get(id: String): Doc? = list().firstOrNull { it.id == id }

    /** Reserve a new file; call [commit] once the PDF has been written into it. */
    fun newFile(): Pair<String, File> {
        val id = UUID.randomUUID().toString().take(13).replace("-", "")
        return id to File(dir, "$id.pdf")
    }

    @Synchronized
    fun commit(id: String, title: String, pages: Int, searchable: Boolean) {
        val idx = readIndex()
        val m = idx.optJSONObject(id) ?: JSONObject().put("created", System.currentTimeMillis())
        m.put("title", uniqueTitle(title, id)).put("pages", pages).put("searchable", searchable)
        idx.put(id, m)
        writeIndex(idx)
    }

    @Synchronized
    fun update(id: String, pages: Int? = null, searchable: Boolean? = null, sentTo: String? = null) {
        val idx = readIndex()
        val m = idx.optJSONObject(id) ?: return
        pages?.let { m.put("pages", it) }
        searchable?.let { m.put("searchable", it) }
        sentTo?.let { m.put("sentTo", it) }
        idx.put(id, m)
        writeIndex(idx)
    }

    @Synchronized
    fun rename(id: String, title: String) {
        val idx = readIndex()
        val m = idx.optJSONObject(id) ?: return
        m.put("title", uniqueTitle(title.trim().ifEmpty { "Document" }, id))
        writeIndex(idx)
    }

    @Synchronized
    fun delete(id: String) {
        File(dir, "$id.pdf").delete()
        val idx = readIndex()
        idx.remove(id)
        writeIndex(idx)
    }

    private fun uniqueTitle(want: String, selfId: String): String {
        val clean = want.replace(Regex("[\\\\/:*?\"<>|]"), "-").trim().take(120).ifEmpty { "Document" }
        val idx = readIndex()
        val taken = idx.keys().asSequence().filter { it != selfId }.map { idx.optJSONObject(it)?.optString("title") }.toSet()
        if (clean !in taken) return clean
        var n = 2
        while ("$clean ($n)" in taken) n++
        return "$clean ($n)"
    }

    fun displayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    companion object {
        fun scanTitle(pattern: String, now: Date = Date()): String {
            val stamp = SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US).format(now)
            return pattern.replace("{date}", stamp).ifBlank { "Scan $stamp" }
        }
    }
}
