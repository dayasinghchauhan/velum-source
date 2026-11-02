package com.daya.velum.imaging

import android.graphics.Bitmap
import kotlin.math.roundToInt

/**
 * Best shot: scores every full-resolution frame of a short burst for sharpness (variance of the Laplacian on 1/8-scale
 * luma, which drops with both hand shake and missed focus) and keeps a copy of only the best [keep] frames.
 */
class BestShotCollector(private val keep: Int = 3) {
    private val best = ArrayList<Pair<Float, YuvFrame>>()
    var frames = 0; private set

    @Synchronized fun offer(img: androidx.camera.core.ImageProxy) {
        frames++
        val f = 8; val sw = img.width / f; val sh = img.height / f
        val p = img.planes[0]; val b = p.buffer
        val small = FloatArray(sw * sh) { i -> (b.get((i / sw) * f * p.rowStride + (i % sw) * f * p.pixelStride).toInt() and 255).toFloat() }
        val score = FrameMath.sharpness(small, sw, sh)
        if (best.size >= keep && score <= best.last().first) return
        best += score to YuvFrame.copy(img)
        best.sortByDescending { it.first }
        while (best.size > keep) best.removeAt(best.size - 1)
    }

    /** Frames ranked sharpest first. */
    @Synchronized fun ranked(): List<Pair<Float, YuvFrame>> = best.toList()
}

/**
 * Clone: two shots from a fixed position are joined along a vertical seam (upright coordinates) with a soft
 * feather, so the same person can appear twice. Shot B is aligned to A first to absorb small hand movement.
 */
object CloneComposer {
    fun compose(a: YuvFrame, b: YuvFrame, seam: Float): Bitmap {
        val ba = a.toBitmap(1); val bb = b.toBitmap(1)
        val (dx, dy) = alignUpright(a, b)
        val w = ba.width; val h = ba.height
        val pa = IntArray(w * h); ba.getPixels(pa, 0, w, 0, 0, w, h)
        val pb = IntArray(w * h); bb.getPixels(pb, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        val sx = seam * w; val feather = w * 0.05f
        FramePool.bands(h) { s, e ->
            for (yy in s until e) for (xx in 0 until w) {
                val t = ((xx - (sx - feather / 2)) / feather).coerceIn(0f, 1f)
                val bxp = (xx + dx).coerceIn(0, w - 1); val byp = (yy + dy).coerceIn(0, h - 1)
                out[yy * w + xx] = mix(pa[yy * w + xx], pb[byp * w + bxp], t)
            }
        }
        ba.recycle(); bb.recycle()
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun alignUpright(a: YuvFrame, b: YuvFrame): Pair<Int, Int> {
        val f = 8
        val (dxB, dyB, _) = FrameMath.shift(a.lumaSmall(f), b.lumaSmall(f), a.w / f, a.h / f, 6)
        // Buffer-space shift → upright-space shift.
        val (ux, uy) = when (a.rotation) { 90 -> -dyB to dxB; 180 -> -dxB to -dyB; 270 -> dyB to -dxB; else -> dxB to dyB }
        return ux * f to uy * f
    }

    private fun mix(p: Int, q: Int, t: Float): Int {
        if (t <= 0f) return p; if (t >= 1f) return q
        val r = ((p shr 16 and 255) * (1 - t) + (q shr 16 and 255) * t).roundToInt()
        val g = ((p shr 8 and 255) * (1 - t) + (q shr 8 and 255) * t).roundToInt()
        val bl = ((p and 255) * (1 - t) + (q and 255) * t).roundToInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }
}
