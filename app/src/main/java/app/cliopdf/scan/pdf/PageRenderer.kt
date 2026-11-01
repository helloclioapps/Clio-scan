package app.cliopdf.scan.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File

/** Renders PDF pages with Android's built-in renderer. One page at a time (PdfRenderer is not thread-safe). */
class PageRenderer(file: File) : Closeable {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)

    private var closed = false
    val pageCount: Int get() = renderer.pageCount

    /** Page size in points (as displayed, i.e. after the page's rotation). */
    @Synchronized
    fun pageSize(index: Int): Pair<Int, Int> = renderer.openPage(index).use { it.width to it.height }

    @Synchronized
    fun render(index: Int, targetWidth: Int, maxHeight: Int = targetWidth * 3): Bitmap {
        check(!closed) { "Document closed" }
        renderer.openPage(index).use { page ->
            var scale = targetWidth.toFloat() / page.width
            if (page.height * scale > maxHeight) scale = maxHeight.toFloat() / page.height
            val w = (page.width * scale).toInt().coerceAtLeast(1)
            val h = (page.height * scale).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try { renderer.close() } catch (_: Exception) {}
        try { pfd.close() } catch (_: Exception) {}
    }
}
