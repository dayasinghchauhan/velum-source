package com.daya.velum

import com.daya.velum.imaging.Sharpness
import java.awt.image.BufferedImage

/** JVM adapter so the contact sheet uses the production [Sharpness] kernel. */
object Sharpen {
    fun apply(src: BufferedImage, amount: Float): BufferedImage {
        if (amount == 0f) return src
        val w = src.width; val h = src.height
        val px = IntArray(w * h); src.getRGB(0, 0, w, h, px, 0, w)
        val out = Sharpness.apply(px, w, h, amount)
        return BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, w, h, out, 0, w) }
    }
}
