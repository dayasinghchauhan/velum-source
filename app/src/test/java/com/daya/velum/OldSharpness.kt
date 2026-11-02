package com.daya.velum

/**
 * Luma unsharp mask (amount > 0) or soften (amount < 0) on ARGB pixels, using the same 4-neighbour kernel as the
 * shader. The kernel radius is one pixel at 1080p-equivalent scale, so it is scaled up for larger images.
 */
object OldSharpness {
    fun apply(px: IntArray, w: Int, h: Int, amount: Float): IntArray {
        if (amount == 0f) return px
        val r = maxOf(1, Math.round(minOf(w, h) / 1080f))
        val out = IntArray(px.size)
        for (y in 0 until h) {
            val yu = maxOf(0, y - r) * w; val yd = minOf(h - 1, y + r) * w; val yc = y * w
            for (x in 0 until w) {
                val c = px[yc + x]
                val n1 = px[yu + x]; val n2 = px[yd + x]; val n3 = px[yc + maxOf(0, x - r)]; val n4 = px[yc + minOf(w - 1, x + r)]
                var rr = (c shr 16) and 255; var gg = (c shr 8) and 255; var bb = c and 255
                val ar = (((n1 shr 16) and 255) + ((n2 shr 16) and 255) + ((n3 shr 16) and 255) + ((n4 shr 16) and 255)) / 4f
                val ag = (((n1 shr 8) and 255) + ((n2 shr 8) and 255) + ((n3 shr 8) and 255) + ((n4 shr 8) and 255)) / 4f
                val ab = ((n1 and 255) + (n2 and 255) + (n3 and 255) + (n4 and 255)) / 4f
                if (amount > 0f) {
                    // Sharpen luma only, so edges don't gain colour fringes.
                    val l = 0.2126f * rr + 0.7152f * gg + 0.0722f * bb
                    val la = 0.2126f * ar + 0.7152f * ag + 0.0722f * ab
                    val d = (l - la) * amount * 1.6f
                    rr = (rr + d).toInt(); gg = (gg + d).toInt(); bb = (bb + d).toInt()
                } else {
                    val k = -amount * 0.7f
                    rr = (rr + (ar - rr) * k).toInt(); gg = (gg + (ag - gg) * k).toInt(); bb = (bb + (ab - bb) * k).toInt()
                }
                out[yc + x] = (0xFF shl 24) or (rr.coerceIn(0, 255) shl 16) or (gg.coerceIn(0, 255) shl 8) or bb.coerceIn(0, 255)
            }
        }
        return out
    }
}
