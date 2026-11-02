package com.daya.velum.imaging

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Thread pool shared by the CPU frame operations. */
internal object FramePool {
    val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceIn(2, 8))
    inline fun bands(n: Int, crossinline f: (Int, Int) -> Unit) {
        val k = 8
        val tasks = ArrayList<Future<*>>(k)
        for (b in 0 until k) { val s = b * n / k; val e = (b + 1) * n / k; tasks += pool.submit { f(s, e) } }
        tasks.forEach { it.get() }
    }
}

/**
 * A detached copy of one YUV_420_888 camera frame (the ImageProxy must be closed quickly), with its rotation so it can
 * be rendered upright. Chroma is stored at native ½×½ resolution.
 */
class YuvFrame(val w: Int, val h: Int, val y: ByteArray, val u: ByteArray, val v: ByteArray, val rotation: Int, val tsNs: Long) {
    val uprightW get() = if (rotation % 180 == 0) w else h
    val uprightH get() = if (rotation % 180 == 0) h else w

    /** Maps an upright pixel to buffer coordinates. */
    fun toBuffer(ux: Int, uy: Int): Long {
        val (bx, by) = when (rotation) {
            90 -> uy to (h - 1 - ux)
            180 -> (w - 1 - ux) to (h - 1 - uy)
            270 -> (w - 1 - uy) to ux
            else -> ux to uy
        }
        return (bx.toLong() shl 32) or (by.toLong() and 0xffffffffL)
    }

    fun argbAt(bx: Int, by: Int): Int {
        val yy = (y[by * w + bx].toInt() and 255).toFloat()
        val ci = (by / 2) * (w / 2) + (bx / 2)
        val uu = (u[ci].toInt() and 255) - 128f
        val vv = (v[ci].toInt() and 255) - 128f
        // BT.601 full-range (JFIF) YUV → RGB, as produced by the camera's YUV_420_888 stream.
        val r = yy + 1.402f * vv
        val g = yy - 0.344136f * uu - 0.714136f * vv
        val b = yy + 1.772f * uu
        return (0xFF shl 24) or (c8(r) shl 16) or (c8(g) shl 8) or c8(b)
    }

    /** Upright RGB bitmap, optionally downscaled by an integer factor. */
    fun toBitmap(scale: Int = 1): Bitmap {
        val ow = uprightW / scale; val oh = uprightH / scale
        val px = IntArray(ow * oh)
        FramePool.bands(oh) { s, e ->
            for (oy in s until e) for (ox in 0 until ow) {
                val p = toBuffer(ox * scale, oy * scale)
                px[oy * ow + ox] = argbAt((p shr 32).toInt(), p.toInt())
            }
        }
        return Bitmap.createBitmap(px, ow, oh, Bitmap.Config.ARGB_8888)
    }

    /** Downscaled luma in BUFFER orientation (cheap; used for alignment and sharpness). */
    fun lumaSmall(f: Int): FloatArray {
        val sw = w / f; val sh = h / f
        return FloatArray(sw * sh) { i -> (y[(i / sw) * f * w + (i % sw) * f].toInt() and 255).toFloat() }
    }

    companion object {
        fun copy(img: ImageProxy): YuvFrame {
            val w = img.width; val h = img.height
            val py = img.planes[0]; val pu = img.planes[1]; val pv = img.planes[2]
            val yb = ByteArray(w * h); val ub = ByteArray((w / 2) * (h / 2)); val vb = ByteArray((w / 2) * (h / 2))
            val ybuf = py.buffer; val ubuf = pu.buffer; val vbuf = pv.buffer
            for (r in 0 until h) {
                val base = r * py.rowStride
                if (py.pixelStride == 1) { ybuf.position(base); ybuf.get(yb, r * w, w) }
                else for (c in 0 until w) yb[r * w + c] = ybuf.get(base + c * py.pixelStride)
            }
            val cw = w / 2
            for (r in 0 until h / 2) for (c in 0 until cw) {
                ub[r * cw + c] = ubuf.get(r * pu.rowStride + c * pu.pixelStride)
                vb[r * cw + c] = vbuf.get(r * pv.rowStride + c * pv.pixelStride)
            }
            return YuvFrame(w, h, yb, ub, vb, img.imageInfo.rotationDegrees, img.imageInfo.timestamp)
        }

        fun c8(f: Float) = max(0, min(255, (f + 0.5f).toInt()))
    }
}

object FrameMath {
    /**
     * Global translation (dx, dy) that best maps [cur] onto [ref] (both sw×sh small luma), by mean absolute
     * difference over a ±[r] search window, sampling every other pixel. Returns (dx, dy, score).
     */
    fun shift(ref: FloatArray, cur: FloatArray, sw: Int, sh: Int, r: Int, hint: Pair<Int, Int> = 0 to 0): Triple<Int, Int, Float> {
        val m = r + 2
        fun mad(dx: Int, dy: Int): Float {
            var sum = 0f; var n = 0
            var yy = m
            while (yy < sh - m) {
                val cy = yy + dy
                if (cy in 0 until sh) {
                    var xx = m
                    while (xx < sw - m) {
                        val cx = xx + dx
                        if (cx in 0 until sw) { sum += abs(ref[yy * sw + xx] - cur[cy * sw + cx]); n++ }
                        xx += 2
                    }
                }
                yy += 2
            }
            return if (n > 0) sum / n else Float.MAX_VALUE
        }
        // The hint (previous shift) wins unless another offset is clearly better, so flat or noisy frames, where every
        // offset scores alike, cannot make the estimate wander.
        val atHint = mad(hint.first, hint.second)
        var best = atHint; var bx = hint.first; var by = hint.second
        for (dy in hint.second - r..hint.second + r) for (dx in hint.first - r..hint.first + r) {
            if (dx == hint.first && dy == hint.second) continue
            val v = mad(dx, dy)
            if (v < best) { best = v; bx = dx; by = dy }
        }
        if (best > atHint * 0.97f - 0.3f) return Triple(hint.first, hint.second, atHint)
        return Triple(bx, by, best)
    }

    /** True when small luma has enough texture, and is not blown out, to estimate a shift from. */
    fun alignable(l: FloatArray): Boolean {
        var s = 0.0; var s2 = 0.0
        for (v in l) { s += v; s2 += v * v }
        val mean = s / l.size; val variance = s2 / l.size - mean * mean
        return variance > 25.0 && mean < 235.0
    }

    /** Variance of the Laplacian: higher = sharper (focus and motion blur both lower it). */
    fun sharpness(l: FloatArray, sw: Int, sh: Int): Float {
        var s = 0.0; var s2 = 0.0; var n = 0
        for (yy in 1 until sh - 1) for (xx in 1 until sw - 1) {
            val i = yy * sw + xx
            val lap = 4 * l[i] - l[i - 1] - l[i + 1] - l[i - sw] - l[i + sw]
            s += lap; s2 += lap * lap; n++
        }
        val mean = s / n
        return (s2 / n - mean * mean).toFloat()
    }
}
