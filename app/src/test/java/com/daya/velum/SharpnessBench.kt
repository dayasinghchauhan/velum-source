package com.daya.velum

import com.daya.velum.imaging.Sharpness
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Measurement tool, not a pass/fail test: runs the previous and the current still sharpener on a real photo at the
 * same strength and prints noise (flat region), detail (textured region) and overshoot (pixels pushed outside their
 * 3x3 neighbourhood range, i.e. halos). Runs only when VELUM_SHARP_SRC points at a JPEG. Writes 1:1 crops to
 * VELUM_SHARP_OUT (a directory) when set.
 */
class SharpnessBench {
    @Test fun bench() {
        val src = System.getenv("VELUM_SHARP_SRC")
        assumeTrue(src != null && File(src).exists())
        val img = ImageIO.read(File(src!!))
        val w = img.width; val h = img.height
        val px = IntArray(w * h); img.getRGB(0, 0, w, h, px, 0, w)
        val amount = System.getenv("VELUM_SHARP_AMOUNT")?.toFloatOrNull() ?: 0.5f
        fun box(x0: Double, y0: Double, x1: Double, y1: Double) = intArrayOf((x0 * w).toInt(), (y0 * h).toInt(), (x1 * w).toInt(), (y1 * h).toInt())
        val flat = box(0.40, 0.80, 0.55, 0.95); val tex = box(0.10, 0.54, 0.28, 0.64); val text = box(0.12, 0.41, 0.20, 0.47)
        fun lum(p: IntArray, i: Int) = 0.2126 * (p[i] shr 16 and 255) + 0.7152 * (p[i] shr 8 and 255) + 0.0722 * (p[i] and 255)
        fun hpStd(p: IntArray, b: IntArray): Double {
            var s = 0.0; var s2 = 0.0; var n = 0
            for (y in b[1] + 1 until b[3] - 1) for (x in b[0] + 1 until b[2] - 1) {
                val i = y * w + x
                val v = 4 * lum(p, i) - lum(p, i - 1) - lum(p, i + 1) - lum(p, i - w) - lum(p, i + w)
                s += v; s2 += v * v; n++
            }
            return sqrt(s2 / n - (s / n) * (s / n))
        }
        fun overshoot(orig: IntArray, p: IntArray, b: IntArray): Double {
            var n = 0; var o = 0
            for (y in b[1] + 1 until b[3] - 1) for (x in b[0] + 1 until b[2] - 1) {
                var lo = 1e9; var hi = -1e9
                for (dy in -1..1) for (dx in -1..1) { val v = lum(orig, (y + dy) * w + x + dx); if (v < lo) lo = v; if (v > hi) hi = v }
                val v = lum(p, y * w + x)
                if (v > hi + 4 || v < lo - 4) o++
                n++
            }
            return 100.0 * o / n
        }
        val old = OldSharpness.apply(px, w, h, amount)
        val new = Sharpness.apply(px, w, h, amount)
        for ((name, p) in listOf("original" to px, "old" to old, "new" to new)) {
            println("%-8s floorNoise %.2f  fabricDetail %.2f  textDetail %.2f  halo%%(fabric) %.2f  halo%%(text) %.2f".format(
                name, hpStd(p, flat), hpStd(p, tex), hpStd(p, text), overshoot(px, p, tex), overshoot(px, p, text)))
        }
        System.getenv("VELUM_SHARP_OUT")?.let { dir ->
            for ((name, p) in listOf("original" to px, "old" to old, "new" to new)) {
                val b = text; val cw = b[2] - b[0]; val ch = b[3] - b[1]
                val o = BufferedImage(cw, ch, BufferedImage.TYPE_INT_RGB)
                for (y in 0 until ch) for (x in 0 until cw) o.setRGB(x, y, p[(b[1] + y) * w + b[0] + x])
                ImageIO.write(o, "png", File(dir, "sharp_$name.png"))
            }
        }
    }
}
