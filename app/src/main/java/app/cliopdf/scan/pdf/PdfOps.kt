package app.cliopdf.scan.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix as AMatrix
import android.media.ExifInterface
import android.net.Uri
import com.google.mlkit.vision.text.Text
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.util.Matrix
import java.io.ByteArrayInputStream
import java.io.File
import java.util.IdentityHashMap
import kotlin.math.max
import kotlin.math.roundToInt

typealias Progress = (done: Int, total: Int, label: String) -> Unit

object PdfOps {
    private const val A4_W = 595.28f
    private const val A4_LONG = 841.89f

    // ------------------------------------------------------------------ images -> PDF

    /**
     * Build a PDF from photos or scanner pages. JPEGs that need no rotation are embedded untouched
     * (no quality loss, small files); everything else is re-encoded once.
     */
    suspend fun buildFromImages(
        ctx: Context, uris: List<Uri>, out: File, ocr: Boolean, quality: Int, maxSide: Int,
        passthroughJpeg: Boolean, progress: Progress,
    ): Int {
        PDDocument().use { doc ->
            uris.forEachIndexed { i, uri ->
                progress(i, uris.size, "Adding page ${i + 1} of ${uris.size}")
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("A page could not be read.")
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val orientation = exifOrientation(bytes)
                val isJpeg = bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
                val canPass = passthroughJpeg && isJpeg && orientation == ExifInterface.ORIENTATION_NORMAL &&
                    max(bounds.outWidth, bounds.outHeight) <= maxSide * 1.15

                var ocrBitmap: Bitmap? = null
                val image: PDImageXObject
                val iw: Int
                val ih: Int
                if (canPass) {
                    image = JPEGFactory.createFromStream(doc, ByteArrayInputStream(bytes))
                    iw = bounds.outWidth; ih = bounds.outHeight
                    if (ocr) ocrBitmap = decodeBytes(bytes, 2600)
                } else {
                    val bmp = decodeBytes(bytes, maxSide) ?: throw IllegalStateException("A page could not be decoded.")
                    image = JPEGFactory.createFromImage(doc, bmp, quality / 100f)
                    iw = bmp.width; ih = bmp.height
                    ocrBitmap = if (ocr) bmp else { bmp.recycle(); null }
                }
                val pw = if (iw > ih) A4_LONG else A4_W
                val ph = pw * ih / iw
                val page = PDPage(PDRectangle(pw, ph))
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.drawImage(image, 0f, 0f, pw, ph)
                }
                if (ocrBitmap != null) {
                    progress(i, uris.size, "Reading text on page ${i + 1}")
                    val text = Ocr.read(ocrBitmap)
                    val s = pw / ocrBitmap.width
                    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                        addTextLayer(cs, text) { px, py -> floatArrayOf(px * s, ph - py * s) }
                    }
                    ocrBitmap.recycle()
                }
            }
            progress(uris.size, uris.size, "Saving")
            clean(doc)
            doc.save(out)
            return doc.numberOfPages
        }
    }

    private fun exifOrientation(bytes: ByteArray): Int = try {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    /** Decode with EXIF rotation applied, scaled so the long side is at most [maxSide]. */
    fun decodeBytes(bytes: ByteArray, maxSide: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0) return null
        var sample = 1
        while (max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val m = AMatrix()
        when (exifOrientation(bytes)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        }
        val long = max(bmp.width, bmp.height)
        if (long > maxSide) {
            val f = maxSide.toFloat() / long
            m.postScale(f, f)
        }
        if (!m.isIdentity) {
            val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (r !== bmp) bmp.recycle()
            bmp = r
        }
        return bmp
    }

    // ------------------------------------------------------------------ invisible OCR text layer

    private val ocrFont: PDFont get() = PDType1Font.HELVETICA

    /**
     * Writes every recognised word as invisible text exactly over the word in the image, so the PDF can be
     * searched, selected and copied. [toUser] maps image pixels (origin top-left) to PDF user space.
     */
    fun addTextLayer(cs: PDPageContentStream, text: Text, toUser: (Float, Float) -> FloatArray) {
        val font = ocrFont
        cs.beginText()
        cs.setFont(font, 1f)
        cs.setRenderingMode(RenderingMode.NEITHER)
        for (block in text.textBlocks) for (line in block.lines) for (el in line.elements) {
            val box = el.boundingBox ?: continue
            val word = sanitize(font, el.text)
            if (word.isBlank() || box.width() <= 0 || box.height() <= 0) continue
            val sw = try { font.getStringWidth(word) / 1000f } catch (_: Exception) { 0f }
            if (sw <= 0f) continue
            val h = box.height().toFloat()
            val baseY = box.bottom - h * 0.2f
            val o = toUser(box.left.toFloat(), baseY)
            val r = toUser(box.left + 1f, baseY)
            val u = toUser(box.left.toFloat(), baseY - 1f)
            val ux = r[0] - o[0]; val uy = r[1] - o[1]
            val vx = u[0] - o[0]; val vy = u[1] - o[1]
            val xs = box.width() / sw
            val fs = h * 0.9f
            cs.setTextMatrix(Matrix(ux * xs, uy * xs, vx * fs, vy * fs, o[0], o[1]))
            cs.showText(word)
        }
        cs.endText()
    }

    private fun sanitize(font: PDFont, s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            val c = ch.toString()
            sb.append(try { font.encode(c); c } catch (_: Exception) { "?" })
        }
        return sb.toString()
    }

    /** Adds a text layer to pages that have no text yet. Returns how many pages were read. */
    suspend fun makeSearchable(src: File, out: File, progress: Progress): Int {
        var done = 0
        PageRenderer(src).use { renderer ->
            PDDocument.load(src).use { doc ->
                val total = doc.numberOfPages
                for (i in 0 until total) {
                    progress(i, total, "Reading text on page ${i + 1} of $total")
                    if (pageHasText(doc, i)) continue
                    val page = doc.getPage(i)
                    val (dw, dh) = renderer.pageSize(i)
                    val target = (2400f * dw / max(dw, dh)).roundToInt().coerceAtLeast(600)
                    val bmp = renderer.render(i, target, 2600)
                    val text = Ocr.read(bmp)
                    val scale = bmp.width.toFloat() / dw
                    val map = displayToUser(page, scale)
                    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                        addTextLayer(cs, text, map)
                    }
                    bmp.recycle()
                    done++
                }
                progress(total, total, "Saving")
                doc.save(out)
            }
        }
        return done
    }

    private fun pageHasText(doc: PDDocument, i: Int): Boolean = try {
        val s = PDFTextStripper().apply { startPage = i + 1; endPage = i + 1 }
        s.getText(doc).count { !it.isWhitespace() } > 15
    } catch (_: Exception) {
        false
    }

    /** Maps rendered-bitmap pixels (top-left origin, page as displayed) to the page's user space. */
    private fun displayToUser(page: PDPage, scale: Float): (Float, Float) -> FloatArray {
        val box = page.cropBox
        val x0 = box.lowerLeftX; val y0 = box.lowerLeftY
        val w = box.width; val h = box.height
        val rot = ((page.rotation % 360) + 360) % 360
        return { px, py ->
            val dx = px / scale; val dy = py / scale
            when (rot) {
                90 -> floatArrayOf(x0 + dy, y0 + dx)
                180 -> floatArrayOf(x0 + w - dx, y0 + dy)
                270 -> floatArrayOf(x0 + w - dy, y0 + h - dx)
                else -> floatArrayOf(x0 + dx, y0 + h - dy)
            }
        }
    }

    // ------------------------------------------------------------------ text

    /** Text already in the PDF; empty for scans that were never made searchable. */
    fun extractText(file: File): String = PDDocument.load(file).use { PDFTextStripper().getText(it) }.trim()

    suspend fun ocrText(file: File, progress: Progress): String {
        val sb = StringBuilder()
        PageRenderer(file).use { r ->
            for (i in 0 until r.pageCount) {
                progress(i, r.pageCount, "Reading page ${i + 1} of ${r.pageCount}")
                val (dw, dh) = r.pageSize(i)
                val bmp = r.render(i, (2200f * dw / max(dw, dh)).roundToInt().coerceAtLeast(600), 2400)
                val t = Ocr.plain(Ocr.read(bmp))
                bmp.recycle()
                if (sb.isNotEmpty()) sb.append("\n\n")
                sb.append(t)
            }
        }
        return sb.toString().trim()
    }

    // ------------------------------------------------------------------ organise

    fun pageCount(file: File): Int = PDDocument.load(file).use { it.numberOfPages }

    /** Writes the pages listed in [order] (source index, extra rotation in degrees) to [out]. */
    fun rebuild(src: File, order: List<Pair<Int, Int>>, out: File) {
        PDDocument.load(src).use { s ->
            PDDocument().use { d ->
                for ((index, rot) in order) {
                    val p = d.importPage(s.getPage(index))
                    p.rotation = (((p.rotation + rot) % 360) + 360) % 360
                }
                clean(d)
                d.save(out)
            }
        }
    }

    fun merge(files: List<File>, out: File) {
        val m = PDFMergerUtility()
        files.forEach { m.addSource(it) }
        m.destinationFileName = out.absolutePath
        m.mergeDocuments(MemoryUsageSetting.setupTempFileOnly())
    }

    // ------------------------------------------------------------------ compress

    /** Re-encodes photos and scans inside the PDF. Returns false when nothing could be made smaller. */
    fun compress(src: File, out: File, level: String, progress: Progress): Boolean {
        val (maxSide, q) = when (level) {
            "low" -> 2400 to 0.85f
            "high" -> 1250 to 0.5f
            else -> 1700 to 0.68f
        }
        PDDocument.load(src).use { doc ->
            val done = IdentityHashMap<COSStream, PDImageXObject>()
            val total = doc.numberOfPages
            doc.pages.forEachIndexed { i, page ->
                progress(i, total, "Compressing page ${i + 1} of $total")
                page.resources?.let { recompress(doc, it, maxSide, q, done, 0) }
            }
            clean(doc)
            doc.save(out)
        }
        return out.length() in 1 until src.length()
    }

    private fun recompress(
        doc: PDDocument, res: PDResources, maxSide: Int, q: Float,
        done: IdentityHashMap<COSStream, PDImageXObject>, depth: Int,
    ) {
        for (name in res.xObjectNames.toList()) {
            val xo = try { res.getXObject(name) } catch (_: Exception) { null } ?: continue
            if (xo is PDFormXObject) {
                if (depth < 3) xo.resources?.let { recompress(doc, it, maxSide, q, done, depth + 1) }
                continue
            }
            if (xo !is PDImageXObject) continue
            val cos = xo.cosObject
            val already = done[cos]
            if (already != null) { res.put(name, already); continue }
            if (xo.isStencil || xo.bitsPerComponent == 1) continue
            if (cos.containsKey(COSName.SMASK) || cos.containsKey(COSName.MASK)) continue
            val before = cos.length
            val bmp = try { xo.image } catch (_: Exception) { null } ?: continue
            val long = max(bmp.width, bmp.height)
            val scaled = if (long > maxSide) {
                val f = maxSide.toFloat() / long
                Bitmap.createScaledBitmap(bmp, (bmp.width * f).roundToInt().coerceAtLeast(1), (bmp.height * f).roundToInt().coerceAtLeast(1), true)
            } else bmp
            val img = JPEGFactory.createFromImage(doc, scaled, q)
            if (img.cosObject.length < before * 0.9) {
                res.put(name, img)
                done[cos] = img
            }
            if (scaled !== bmp) scaled.recycle()
            bmp.recycle()
        }
    }

    // ------------------------------------------------------------------ privacy

    /** Removes author, software and date fields so the PDF does not reveal where it came from. */
    fun clean(doc: PDDocument) {
        doc.documentInformation = PDDocumentInformation()
        doc.documentCatalog.metadata = null
    }
}
