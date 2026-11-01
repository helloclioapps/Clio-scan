package app.cliopdf.scan.pdf

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * "Flatbed" clean-up for photographed pages, done on the phone.
 *
 * 1. Estimate the paper brightness everywhere on the page (block maximum, which ignores the dark text,
 *    then smoothed) — this is the lighting, including table and hand shadows.
 * 2. Divide every pixel by that estimate, so the paper becomes evenly white and shadows disappear.
 * 3. Grayscale: stretch the contrast. Black & white: threshold with Otsu's method, keeping a narrow
 *    soft edge so letters stay smooth.
 */
object Enhance {
    fun apply(src: Bitmap, look: String): Bitmap {
        if (look != "gray" && look != "bw") return src
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val g = IntArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            g[i] = (((c shr 16) and 255) * 77 + ((c shr 8) and 255) * 150 + (c and 255) * 29) shr 8
        }

        // background (paper + lighting) on a coarse grid
        val bs = max(8, max(w, h) / 90)
        val gw = (w + bs - 1) / bs
        val gh = (h + bs - 1) / bs
        var bg = IntArray(gw * gh)
        for (by in 0 until gh) for (bx in 0 until gw) {
            var m = 1
            val y1 = min(h, (by + 1) * bs)
            val x1 = min(w, (bx + 1) * bs)
            var y = by * bs
            while (y < y1) {
                val row = y * w
                var x = bx * bs
                while (x < x1) {
                    val v = g[row + x]
                    if (v > m) m = v
                    x += 2
                }
                y += 2
            }
            bg[by * gw + bx] = m
        }
        repeat(3) { bg = box(bg, gw, gh) }

        // normalise each pixel by the interpolated background
        val norm = IntArray(w * h)
        val hist = IntArray(256)
        for (y in 0 until h) {
            val fy = ((y + 0.5f) / bs - 0.5f).coerceIn(0f, (gh - 1).toFloat())
            val y0 = fy.toInt()
            val y1 = min(y0 + 1, gh - 1)
            val ty = fy - y0
            val row = y * w
            for (x in 0 until w) {
                val fx = ((x + 0.5f) / bs - 0.5f).coerceIn(0f, (gw - 1).toFloat())
                val x0 = fx.toInt()
                val x1 = min(x0 + 1, gw - 1)
                val tx = fx - x0
                val top = bg[y0 * gw + x0] * (1 - tx) + bg[y0 * gw + x1] * tx
                val bot = bg[y1 * gw + x0] * (1 - tx) + bg[y1 * gw + x1] * tx
                val b = max(24f, top * (1 - ty) + bot * ty)
                val v = (g[row + x] * 255f / b).toInt().coerceIn(0, 255)
                norm[row + x] = v
                hist[v]++
            }
        }

        val out = IntArray(w * h)
        if (look == "gray") {
            val lo = 35f
            val hi = 228f
            for (i in norm.indices) {
                val v = ((norm[i] - lo) * 255f / (hi - lo)).toInt().coerceIn(0, 255)
                out[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        } else {
            val t = otsu(hist, w * h).coerceIn(130, 215)
            val soft = 14
            for (i in norm.indices) {
                val v = ((norm[i] - (t - soft)) * 255 / (2 * soft)).coerceIn(0, 255)
                out[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun box(a: IntArray, w: Int, h: Int): IntArray {
        val o = IntArray(a.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0
            var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val yy = y + dy
                val xx = x + dx
                if (yy in 0 until h && xx in 0 until w) { s += a[yy * w + xx]; n++ }
            }
            o[y * w + x] = s / n
        }
        return o
    }

    private fun otsu(hist: IntArray, total: Int): Int {
        var sum = 0.0
        for (i in 0..255) sum += i * hist[i].toDouble()
        var sumB = 0.0
        var wB = 0
        var best = 0.0
        var t = 180
        for (i in 0..255) {
            wB += hist[i]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += i * hist[i].toDouble()
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; t = i }
        }
        return t
    }
}
