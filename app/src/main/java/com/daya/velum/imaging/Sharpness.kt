package com.daya.velum.imaging

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * Luma detail control for saved stills: amount > 0 sharpens, < 0 softens.
 *
 * The camera's JPEG is already sharpened by the ISP, so sharpening here is deliberately restrained:
 * - unsharp mask on luma only (no colour fringes), using a separable [1 4 6 4 1] blur whose step grows with the
 *   image size (one step ≈ one pixel at 1080p-equivalent, matching the preview shader's scale);
 * - a noise threshold: detail smaller than [NOISE_FLOOR] is left alone, so grain is not amplified;
 * - halo clamp: the result may not leave the min/max of its 3x3 neighbourhood (plus a tiny slack), so edges get
 *   crisper without white or dark outlines and without ringing.
 * Processed in row bands (parallel) so memory stays at a few MB regardless of image size.
 */
object Sharpness {
    private const val NOISE_FLOOR = 2.2f   // in 0..255 luma units
    private const val SLACK = 1.5f
    private const val BAND = 192

    fun apply(px: IntArray, w: Int, h: Int, amount: Float): IntArray {
        if (amount == 0f) return px
        val step = max(1, Math.round(min(w, h) / 1080f))
        val m = 2 * step + 1
        val out = IntArray(px.size)
        val bands = (h + BAND - 1) / BAND
        FramePool.bands(bands) { b0, b1 ->
            for (b in b0 until b1) {
                val y0 = b * BAND; val y1 = min(h, y0 + BAND)
                val s0 = max(0, y0 - m); val s1 = min(h, y1 + m)
                val rows = s1 - s0
                val lum = FloatArray(rows * w)
                for (r in 0 until rows) {
                    val o = (s0 + r) * w
                    for (x in 0 until w) {
                        val c = px[o + x]
                        lum[r * w + x] = 0.2126f * (c shr 16 and 255) + 0.7152f * (c shr 8 and 255) + 0.0722f * (c and 255)
                    }
                }
                // Separable blur: horizontal into tmp, vertical below.
                val tmp = FloatArray(rows * w)
                for (r in 0 until rows) {
                    val o = r * w
                    for (x in 0 until w) {
                        val a = lum[o + max(0, x - 2 * step)]; val bb = lum[o + max(0, x - step)]
                        val c = lum[o + x]
                        val d = lum[o + min(w - 1, x + step)]; val e = lum[o + min(w - 1, x + 2 * step)]
                        tmp[o + x] = (a + 4 * bb + 6 * c + 4 * d + e) / 16f
                    }
                }
                for (y in y0 until y1) {
                    val r = y - s0
                    val ra = max(0, r - 2 * step) * w; val rb = max(0, r - step) * w; val rc = r * w
                    val rd = min(rows - 1, r + step) * w; val re = min(rows - 1, r + 2 * step) * w
                    val ru = max(0, r - 1) * w; val rn = min(rows - 1, r + 1) * w
                    for (x in 0 until w) {
                        val blur = (tmp[ra + x] + 4 * tmp[rb + x] + 6 * tmp[rc + x] + 4 * tmp[rd + x] + tmp[re + x]) / 16f
                        val l = lum[rc + x]
                        val nl = if (amount > 0f) {
                            var d = l - blur
                            d = sign(d) * max(0f, abs(d) - NOISE_FLOOR)
                            val xl = max(0, x - 1); val xr = min(w - 1, x + 1)
                            var lo = l; var hi = l
                            for (q in 0..2) {
                                val row = if (q == 0) ru else if (q == 1) rc else rn
                                for (k in 0..2) {
                                    val n = lum[row + if (k == 0) xl else if (k == 1) x else xr]
                                    if (n < lo) lo = n; if (n > hi) hi = n
                                }
                            }
                            (l + d * amount * 1.4f).coerceIn(lo - SLACK, hi + SLACK)
                        } else l + (blur - l) * (-amount) * 0.8f
                        val dl = nl - l
                        val c = px[y * w + x]
                        val rr = ((c shr 16 and 255) + dl + 0.5f).toInt().coerceIn(0, 255)
                        val gg = ((c shr 8 and 255) + dl + 0.5f).toInt().coerceIn(0, 255)
                        val bl = ((c and 255) + dl + 0.5f).toInt().coerceIn(0, 255)
                        out[y * w + x] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bl
                    }
                }
            }
        }
        return out
    }
}
