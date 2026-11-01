package app.cliopdf.scan

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.cliopdf.scan.data.Doc
import app.cliopdf.scan.data.DocStore
import app.cliopdf.scan.data.Prefs
import app.cliopdf.scan.data.jpegQuality
import app.cliopdf.scan.data.maxImageSide
import app.cliopdf.scan.net.MacLink
import app.cliopdf.scan.net.PairedMac
import app.cliopdf.scan.pdf.PageRenderer
import app.cliopdf.scan.pdf.PdfOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Screen {
    data object Home : Screen
    data class Document(val id: String) : Screen
    data class Organize(val id: String) : Screen
    data object Merge : Screen
    data object Settings : Screen
    data object Pair : Screen
}

data class Busy(val title: String, val detail: String = "", val progress: Float? = null)
data class Note(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null)
data class TextResult(val title: String, val text: String)
data class Alert(val title: String, val text: String)

sealed interface PairState {
    data object Idle : PairState
    data class Working(val mac: String, val step: String) : PairState
    data class Done(val mac: String) : PairState
    data class Failed(val mac: String?, val message: String) : PairState
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val store = DocStore(app)
    val prefs = Prefs(app)
    private val link = MacLink(app)

    val docs = MutableStateFlow<List<Doc>>(emptyList())
    val busy = MutableStateFlow<Busy?>(null)
    val mac = MutableStateFlow<PairedMac?>(link.mac)
    val textResult = MutableStateFlow<TextResult?>(null)
    val alert = MutableStateFlow<Alert?>(null)
    val pairState = MutableStateFlow<PairState>(PairState.Idle)
    val macConfirmed = MutableStateFlow(link.confirmed)
    val notes = MutableSharedFlow<Note>(extraBufferCapacity = 8)
    val intents = MutableSharedFlow<Intent>(extraBufferCapacity = 4)
    val stack = mutableStateListOf<Screen>(Screen.Home)

    init {
        refresh()
        // nothing temporary survives a restart: shared copies, outgoing files, scanner pages
        viewModelScope.launch(Dispatchers.IO) { wipeTemp() }
    }

    private fun wipeTemp() {
        val cache = getApplication<Application>().cacheDir
        File(cache, "shared").deleteRecursively()
        File(cache, "outgoing").deleteRecursively()
        cache.listFiles()?.filter { it.name.contains("docscan", true) || it.name.contains("mlkit", true) }?.forEach { it.deleteRecursively() }
    }

    /** Scanner pages are written to this app's cache; remove them as soon as the PDF exists. */
    private fun deleteScannerPages(uris: List<Uri>) {
        val cache = getApplication<Application>().cacheDir.canonicalPath
        uris.forEach { u ->
            if (u.scheme == "file") u.path?.let { p -> File(p).takeIf { it.canonicalPath.startsWith(cache) }?.delete() }
        }
    }

    // ------------------------------------------------------------------ navigation

    val screen: Screen get() = stack.last()
    fun go(s: Screen) { stack.add(s) }
    fun back(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false
    private fun home() { while (stack.size > 1) stack.removeAt(stack.lastIndex) }

    fun refresh() {
        viewModelScope.launch { docs.value = withContext(Dispatchers.IO) { store.list() } }
    }

    fun note(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
        notes.tryEmit(Note(text, action, onAction))
    }

    private fun job(title: String, block: suspend (progress: (Int, Int, String) -> Unit) -> Unit) {
        if (busy.value != null) return
        busy.value = Busy(title)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    block { done, total, label ->
                        busy.value = Busy(title, label, if (total > 0) done.toFloat() / total else null)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                val msg = friendly(e)
                if (msg.length > 110 || msg.contains('\n')) alert.value = Alert(title, msg) else note(msg)
            } finally {
                busy.value = null
                refresh()
            }
        }
    }

    private fun friendly(e: Throwable): String {
        val m = e.message ?: ""
        return when {
            e is app.cliopdf.scan.net.SendError -> m
            m.contains("password", true) || e.javaClass.simpleName.contains("InvalidPassword") ->
                "This PDF is password-protected. Remove the password first."
            e is OutOfMemoryError -> "The document is too large for this phone."
            m.isNotBlank() -> m
            else -> "Something went wrong."
        }
    }

    // ------------------------------------------------------------------ creating documents

    fun onScanned(pages: List<Uri>) {
        if (pages.isEmpty()) return
        val s = prefs.state.value
        val title = DocStore.scanTitle(s.titlePattern)
        build(pages, title, passthrough = s.quality != "small", label = "Saving scan", scanner = true)
    }

    fun importImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val title = DocStore.scanTitle(prefs.state.value.titlePattern.replace("Scan", "Photos"))
        build(uris, title, passthrough = false, label = "Creating PDF")
    }

    private fun build(uris: List<Uri>, title: String, passthrough: Boolean, label: String, scanner: Boolean = false) {
        val s = prefs.state.value
        job(label) { progress ->
            val (id, file) = store.newFile()
            try {
                val pages = PdfOps.buildFromImages(
                    getApplication<Application>(), uris, file, ocr = s.autoOcr, quality = jpegQuality(s.quality),
                    maxSide = maxImageSide(s.quality), passthroughJpeg = passthrough && s.pageLook == "original",
                    look = s.pageLook, progress = progress,
                )
                store.commit(id, title, pages, searchable = s.autoOcr)
            } catch (e: Exception) {
                file.delete(); throw e
            } finally {
                if (scanner) deleteScannerPages(uris)
            }
            val doc = store.get(id)
            withContext(Dispatchers.Main) {
                note("Saved “${doc?.title ?: title}”", "Open") { go(Screen.Document(id)) }
            }
            if (s.autoSend && link.mac != null) sendNow(listOf(id), progress)
        }
    }

    fun importPdfs(uris: List<Uri>) {
        if (uris.isEmpty()) return
        job("Importing") { progress ->
            var ok = 0
            val failed = mutableListOf<String>()
            uris.forEachIndexed { i, uri ->
                val name = (store.displayName(uri) ?: "Document").removeSuffix(".pdf").removeSuffix(".PDF")
                progress(i, uris.size, name)
                val (id, file) = store.newFile()
                try {
                    getApplication<Application>().contentResolver.openInputStream(uri)!!.use { input ->
                        file.outputStream().use { input.copyTo(it) }
                    }
                    val pages = PdfOps.pageCount(file)
                    val hasText = try { PdfOps.extractText(file).length > 20 } catch (_: Exception) { false }
                    store.commit(id, name, pages, hasText)
                    ok++
                } catch (e: Exception) {
                    file.delete(); failed += name
                }
            }
            when {
                failed.isNotEmpty() -> note("Couldn't open ${failed.joinToString()} — damaged or password-protected.")
                ok == 1 -> note("PDF imported.")
                else -> note("$ok PDFs imported.")
            }
        }
    }

    // ------------------------------------------------------------------ tools

    fun makeSearchable(id: String) {
        val doc = store.get(id) ?: return
        job("Making searchable") { progress ->
            val tmp = File(store.dir, "$id.tmp")
            val read = PdfOps.makeSearchable(doc.file, tmp, progress)
            if (read > 0) {
                tmp.renameTo(doc.file)
                note("Text recognised on $read page${if (read == 1) "" else "s"}. You can now search and copy it.")
            } else {
                tmp.delete()
                note("This PDF already contains text.")
            }
            store.update(id, searchable = true)
        }
    }

    fun compress(id: String, level: String) {
        val doc = store.get(id) ?: return
        job("Compressing") { progress ->
            val tmp = File(store.dir, "$id.tmp")
            val before = doc.file.length()
            if (PdfOps.compress(doc.file, tmp, level, progress)) {
                val after = tmp.length()
                tmp.renameTo(doc.file)
                val pct = ((1 - after.toDouble() / before) * 100).toInt()
                note("Compressed: ${size(before)} → ${size(after)} ($pct% smaller)")
            } else {
                tmp.delete()
                note("This PDF is already as small as it can get at this level.")
            }
        }
    }

    fun merge(ids: List<String>, title: String) {
        val files = ids.mapNotNull { store.get(it)?.file }
        if (files.size < 2) return
        job("Merging") { progress ->
            progress(0, 1, "Combining ${files.size} documents")
            val (id, out) = store.newFile()
            PdfOps.merge(files, out)
            val searchable = ids.all { store.get(it)?.searchable == true }
            store.commit(id, title.ifBlank { "Merged" }, PdfOps.pageCount(out), searchable)
            withContext(Dispatchers.Main) {
                home(); go(Screen.Document(id))
                note("Merged into one PDF.")
            }
        }
    }

    fun saveOrganized(id: String, order: List<Pair<Int, Int>>, asCopy: Boolean) {
        val doc = store.get(id) ?: return
        if (order.isEmpty()) { note("A document needs at least one page."); return }
        job("Saving pages") {
            if (asCopy) {
                val (nid, out) = store.newFile()
                PdfOps.rebuild(doc.file, order, out)
                store.commit(nid, "${doc.title} (edited)", order.size, doc.searchable)
                withContext(Dispatchers.Main) { back(); back(); go(Screen.Document(nid)) }
            } else {
                val tmp = File(store.dir, "$id.tmp")
                PdfOps.rebuild(doc.file, order, tmp)
                tmp.renameTo(doc.file)
                store.update(id, pages = order.size)
                withContext(Dispatchers.Main) { back() }
            }
            note("Pages saved.")
        }
    }

    fun extractText(id: String) {
        val doc = store.get(id) ?: return
        job("Getting text") { progress ->
            var text = try { PdfOps.extractText(doc.file) } catch (_: Exception) { "" }
            if (text.length < 10) text = PdfOps.ocrText(doc.file, progress)
            if (text.isBlank()) note("No text found in this document.")
            else textResult.value = TextResult(doc.title, text)
        }
    }

    fun rename(id: String, title: String) { store.rename(id, title); refresh() }

    fun delete(id: String) {
        store.delete(id)
        if (stack.any { it is Screen.Document && it.id == id }) home()
        refresh()
        note("Document deleted.")
    }

    fun deleteAll() {
        docs.value.forEach { store.delete(it.id) }
        File(getApplication<Application>().cacheDir, "shared").deleteRecursively()
        refresh()
        note("All documents deleted.")
    }

    // ------------------------------------------------------------------ sharing

    private fun sharedCopy(doc: Doc): Uri {
        val dir = File(getApplication<Application>().cacheDir, "shared").apply { deleteRecursively(); mkdirs() }
        val f = File(dir, "${doc.title}.pdf")
        doc.file.copyTo(f, overwrite = true)
        return FileProvider.getUriForFile(getApplication<Application>(), "${getApplication<Application>().packageName}.files", f)
    }

    fun share(id: String) {
        val doc = store.get(id) ?: return
        viewModelScope.launch {
            val uri = withContext(Dispatchers.IO) { sharedCopy(doc) }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, doc.title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            intents.emit(Intent.createChooser(send, "Share “${doc.title}”"))
        }
    }

    fun shareText(title: String, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, title)
        }
        intents.tryEmit(Intent.createChooser(send, "Share text"))
    }

    fun exportTo(id: String, target: Uri) {
        val doc = store.get(id) ?: return
        job("Saving to device") {
            getApplication<Application>().contentResolver.openOutputStream(target)!!.use { out ->
                doc.file.inputStream().use { it.copyTo(out) }
            }
            note("Saved to your device.")
        }
    }

    // ------------------------------------------------------------------ Mac

    fun sendToMac(id: String) = sendToMac(listOf(id))

    fun sendToMac(ids: List<String>) {
        if (link.mac == null) { go(Screen.Pair); return }
        if (ids.isEmpty()) return
        job(if (ids.size == 1) "Sending to Mac" else "Sending ${ids.size} documents") { progress -> sendNow(ids, progress) }
    }

    fun sendAllNew() = sendToMac(docs.value.filter { it.sentTo == null }.map { it.id })

    /** Sends the documents in one encrypted payload, as PDFs or as page images, then applies the after-send rule. */
    private suspend fun sendNow(ids: List<String>, progress: (Int, Int, String) -> Unit) {
        val docsToSend = ids.mapNotNull { store.get(it) }
        if (docsToSend.isEmpty()) return
        val m = link.mac ?: return
        val s = prefs.state.value
        val outgoing = File(getApplication<Application>().cacheDir, "outgoing").apply { deleteRecursively(); mkdirs() }
        try {
            val files = mutableListOf<Pair<File, String>>()
            if (s.sendAs == "images") {
                docsToSend.forEachIndexed { di, d ->
                    PageRenderer(d.file).use { r ->
                        for (i in 0 until r.pageCount) {
                            progress(i, r.pageCount, "Preparing ${d.title} — page ${i + 1}")
                            val (w, h) = r.pageSize(i)
                            val px = (w * 200f / 72f).toInt().coerceIn(600, 2400)
                            val bmp = r.render(i, px, (px * h / w.coerceAtLeast(1)).coerceAtMost(3600))
                            val name = if (r.pageCount == 1) "${d.title}.jpg" else "${d.title} - p${i + 1}.jpg"
                            val f = File(outgoing, "$di-$i.jpg")
                            f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it) }
                            bmp.recycle()
                            files += f to name
                        }
                    }
                }
            } else {
                docsToSend.forEach { files += it.file to "${it.title}.pdf" }
            }
            val folder = if (docsToSend.size == 1) docsToSend[0].title else DocStore.scanTitle("From phone {date}")
            val name = link.send(files, folder, onStep = { progress(0, 0, it) }) { f ->
                progress((f * 100).toInt(), 100, "Sending to ${m.name} (encrypted)")
            }
            mac.value = link.mac; macConfirmed.value = true
            if (s.afterSend == "delete") {
                docsToSend.forEach { store.delete(it.id) }
                withContext(Dispatchers.Main) { if (screen is Screen.Document || screen is Screen.Organize) home() }
                note(if (docsToSend.size == 1) "Sent to $name and removed from this phone." else "${docsToSend.size} documents sent to $name and removed from this phone.")
            } else {
                docsToSend.forEach { store.update(it.id, sentTo = name) }
                note(if (docsToSend.size == 1) "Sent to $name." else "${docsToSend.size} documents sent to $name.")
            }
        } finally {
            outgoing.deleteRecursively()
        }
    }

    /** Called with the text of the scanned QR code. */
    fun pair(qr: String) {
        val m = try { link.accept(qr) } catch (e: Exception) {
            pairState.value = PairState.Failed(null, e.message ?: "This QR code can't be used."); return
        }
        mac.value = m; macConfirmed.value = false
        confirmPairing()
    }

    fun confirmPairing() {
        val m = link.mac ?: return
        if (pairState.value is PairState.Working) return
        pairState.value = PairState.Working(m.name, "QR code read. Contacting ${m.name}…")
        viewModelScope.launch {
            try {
                link.hello { step -> pairState.value = PairState.Working(m.name, step) }
                mac.value = link.mac; macConfirmed.value = true
                pairState.value = PairState.Done(m.name)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                pairState.value = PairState.Failed(m.name, e.message ?: "The Mac couldn't be reached.")
            }
        }
    }

    fun phoneIp(): String? = link.phoneIp()

    fun resetPairState() { if (pairState.value !is PairState.Working) pairState.value = PairState.Idle }

    fun testMac() {
        job("Checking connection") { progress ->
            link.hello { progress(0, 0, it) }
            mac.value = link.mac; macConfirmed.value = true
            note("${link.mac?.name ?: "Your Mac"} is reachable.")
        }
    }

    fun unpair() {
        link.unpair(); mac.value = null; macConfirmed.value = false
        pairState.value = PairState.Idle
        note("This phone is no longer paired with a Mac.")
    }

    companion object {
        fun size(b: Long): String = when {
            b >= 1_000_000 -> String.format("%.1f MB", b / 1_000_000.0)
            b >= 1_000 -> "${b / 1_000} KB"
            else -> "$b B"
        }
    }
}
