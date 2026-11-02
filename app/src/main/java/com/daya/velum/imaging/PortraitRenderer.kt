package com.daya.velum.imaging

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Depth-of-field for a finished still (used for Portrait at 3x and 5x, where the camera's own bokeh processing is not
 * offered). Inputs are an upright photo plus a relative depth map and a person mask from [MlRunner.stillMaps].
 *
 * - Focus is the median depth of the person, or of the frame centre when no person is found.
 * - Blur grows with distance from that depth (background more than foreground) and is zero on the person.
 * - Blurring happens at 1/4 resolution in linear light (so lights bloom into bokeh discs) with normalised convolution
 *   weighted toward out-of-focus pixels, so the sharp subject does not smear a halo into the background.
 * - The result is blended into the full-resolution photo per pixel.
 */
object PortraitRenderer {
    private val toLin = FloatArray(256) { i -> LookMath.srgbToLinear(i / 255f) }
    private val toSrgb = IntArray(4096) { i -> (LookMath.linearToSrgb(i / 4095f) * 255f + 0.5f).toInt().coerceIn(0, 255) }

    data class Result(val bitmap: Bitmap, val personFound: Boolean, val focusDepth: Float)

    fun render(src: Bitmap, depth: FloatArray, person: FloatArray, n: Int, strength: Float): Result {
        val w = src.width; val h = src.height
        // ---- focus depth
        val personVals = depth.indices.filter { person[it] > 0.5f }
        val found = personVals.size > n * n * 0.02
        val focus = if (found) median(personVals.map { depth[it] })
        else median((n * 35 / 100 until n * 65 / 100).flatMap { y -> (n * 35 / 100 until n * 65 / 100).map { x -> depth[y * n + x] } })
        // ---- circle of confusion per map cell, 0..1
        val coc = FloatArray(n * n) { i ->
            val behind = LookMath.smooth(0.04f, 0.4f, focus - depth[i])
            val front = LookMath.smooth(0.1f, 0.45f, depth[i] - focus) * 0.6f
            max(behind, front) * (1f - LookMath.smooth(0.25f, 0.7f, person[i]))
        }
        // ---- quarter-resolution linear image and weights
        val q = 4; val sw = w / q; val sh = h / q
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        val px = IntArray(sw * sh); small.getPixels(px, 0, sw, 0, 0, sw, sh); small.recycle()
        val r = FloatArray(sw * sh); val g = FloatArray(sw * sh); val b = FloatArray(sw * sh); val wt = FloatArray(sw * sh)
        for (i in px.indices) {
            val c = px[i]
            var lr = toLin[c shr 16 and 255]; var lg = toLin[c shr 8 and 255]; var lb = toLin[c and 255]
            // Clipped lights carry more energy than 1.0; a modest boost lets them bloom into discs like real bokeh.
            if (max(lr, max(lg, lb)) > 0.92f) { lr *= 2.2f; lg *= 2.2f; lb *= 2.2f }
            val cc = sample(coc, n, (i % sw + 0.5f) / sw, (i / sw + 0.5f) / sh)
            val wv = 0.04f + 0.96f * cc
            r[i] = lr * wv; g[i] = lg * wv; b[i] = lb * wv; wt[i] = wv
        }
        fun blurred(radius: Int): Array<FloatArray> {
            val o = arrayOf(r.copyOf(), g.copyOf(), b.copyOf(), wt.copyOf())
            for (ch in o) repeat(3) { boxH(ch, sw, sh, radius); boxV(ch, sw, sh, radius) }
            for (i in 0 until sw * sh) { val k = 1f / max(1e-4f, o[3][i]); o[0][i] *= k; o[1][i] *= k; o[2][i] *= k }
            return o
        }
        val s = strength.coerceIn(0.1f, 1f)
        val b1 = blurred(max(1, (sw * 0.005f * s).roundToInt()))
        val b2 = blurred(max(2, (sw * 0.018f * s).roundToInt()))
        // ---- full-resolution composite, in parallel row bands
        val out = IntArray(w * h)
        src.getPixels(out, 0, w, 0, 0, w, h)
        FramePool.bands(h) { y0, y1 ->
            for (y in y0 until y1) {
                val v = (y + 0.5f) / h
                val sy = (v * sh - 0.5f).coerceIn(0f, sh - 1f)
                for (x in 0 until w) {
                    val u = (x + 0.5f) / w
                    val c = sample(coc, n, u, v)
                    if (c <= 0.01f) continue
                    val i = y * w + x
                    val p = out[i]
                    val sx = (u * sw - 0.5f).coerceIn(0f, sw - 1f)
                    var cr = toLin[p shr 16 and 255]; var cg = toLin[p shr 8 and 255]; var cb = toLin[p and 255]
                    val r1 = bil(b1[0], sw, sh, sx, sy); val g1 = bil(b1[1], sw, sh, sx, sy); val bb1 = bil(b1[2], sw, sh, sx, sy)
                    if (c < 0.5f) {
                        val t = c * 2f
                        cr += (r1 - cr) * t; cg += (g1 - cg) * t; cb += (bb1 - cb) * t
                    } else {
                        val t = (c - 0.5f) * 2f
                        cr = r1 + (bil(b2[0], sw, sh, sx, sy) - r1) * t
                        cg = g1 + (bil(b2[1], sw, sh, sx, sy) - g1) * t
                        cb = bb1 + (bil(b2[2], sw, sh, sx, sy) - bb1) * t
                    }
                    out[i] = (0xFF shl 24) or (enc(cr) shl 16) or (enc(cg) shl 8) or enc(cb)
                }
            }
        }
        return Result(Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888), found, focus)
    }

    // Soft clip above 1.0 so boosted lights roll off instead of hard-clipping.
    private fun enc(l: Float): Int {
        val v = if (l <= 0.8f) l else 0.8f + 0.2f * (1f - 1f / (1f + (l - 0.8f) * 5f))
        return toSrgb[(v.coerceIn(0f, 1f) * 4095f).toInt()]
    }

    private fun sample(m: FloatArray, n: Int, u: Float, v: Float): Float =
        bil(m, n, n, (u * n - 0.5f).coerceIn(0f, n - 1f), (v * n - 0.5f).coerceIn(0f, n - 1f))

    private fun bil(m: FloatArray, w: Int, h: Int, x: Float, y: Float): Float {
        val x0 = x.toInt(); val y0 = y.toInt(); val x1 = min(w - 1, x0 + 1); val y1 = min(h - 1, y0 + 1)
        val fx = x - x0; val fy = y - y0
        val a = m[y0 * w + x0] + (m[y0 * w + x1] - m[y0 * w + x0]) * fx
        val c = m[y1 * w + x0] + (m[y1 * w + x1] - m[y1 * w + x0]) * fx
        return a + (c - a) * fy
    }

    private fun boxH(a: FloatArray, w: Int, h: Int, r: Int) {
        val tmp = FloatArray(w); val k = 1f / (2 * r + 1)
        for (y in 0 until h) {
            val o = y * w
            var acc = 0f
            for (i in -r..r) acc += a[o + i.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[x] = acc * k
                acc += a[o + min(w - 1, x + r + 1)] - a[o + max(0, x - r)]
            }
            System.arraycopy(tmp, 0, a, o, w)
        }
    }

    private fun boxV(a: FloatArray, w: Int, h: Int, r: Int) {
        val tmp = FloatArray(h); val k = 1f / (2 * r + 1)
        for (x in 0 until w) {
            var acc = 0f
            for (i in -r..r) acc += a[i.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                tmp[y] = acc * k
                acc += a[min(h - 1, y + r + 1) * w + x] - a[max(0, y - r) * w + x]
            }
            for (y in 0 until h) a[y * w + x] = tmp[y]
        }
    }

    private fun median(v: List<Float>): Float = if (v.isEmpty()) 0.5f else v.sorted()[v.size / 2]

}
