package com.daya.velum

import com.daya.velum.imaging.LookCatalog
import com.daya.velum.imaging.LookMath
import com.daya.velum.imaging.Lut3D
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.floor

/**
 * Design tool, not a correctness test: renders every built-in look onto a real photo with the production LUT maths
 * and writes a labelled contact sheet. Runs only when VELUM_LOOK_SRC points at a JPEG.
 */
class LookContactSheet {
    @Test fun render() {
        val src = System.getenv("VELUM_LOOK_SRC")
        assumeTrue(src != null && File(src).exists())
        val full = ImageIO.read(File(src!!))
        val tw = System.getenv("VELUM_LOOK_TW")?.toIntOrNull() ?: 360; val th = full.height * tw / full.width
        val thumb = BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB)
        thumb.createGraphics().apply { setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR); drawImage(full, 0, 0, tw, th, null); dispose() }
        val only = System.getenv("VELUM_LOOKS")?.split(",")
        val looks = LookCatalog.all.filter { only == null || it.id in only }
        val cols = System.getenv("VELUM_LOOK_COLS")?.toIntOrNull() ?: 5; val rows = (looks.size + cols - 1) / cols
        val sheet = BufferedImage(cols * tw, rows * (th + 26), BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 16)
        val o = FloatArray(3)
        looks.forEachIndexed { i, look ->
            val lut = Lut3D.fromLook(look.params)
            val img = BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB)
            val gs = 1080f / minOf(tw, th) * (minOf(tw, th) / 1080f) // grain at thumbnail scale
            val sharp = Sharpen.apply(thumb, look.params.sharpness)
            for (y in 0 until th) for (x in 0 until tw) {
                val c = sharp.getRGB(x, y)
                var r = ((c shr 16) and 255) / 255f; var gg = ((c shr 8) and 255) / 255f; var b = (c and 255) / 255f
                lut.sample(r, gg, b, o); r = o[0]; gg = o[1]; b = o[2]
                val p = look.params
                if (p.grain > 0f) { val n = LookMath.hash(floor(x * gs), floor(y * gs), 0.37f) * p.grain * 0.16f; val k = n * (1 - 0.6f * (0.2126f * r + 0.7152f * gg + 0.0722f * b)); r += k; gg += k; b += k }
                if (p.vignette > 0f) { val f = LookMath.vignetteFactor((x + .5f) / tw, (y + .5f) / th, p.vignette, tw.toFloat() / th); r *= f; gg *= f; b *= f }
                img.setRGB(x, y, Color(r.coerceIn(0f, 1f), gg.coerceIn(0f, 1f), b.coerceIn(0f, 1f)).rgb)
            }
            val cx = (i % cols) * tw; val cy = (i / cols) * (th + 26)
            g.drawImage(img, cx, cy, null)
            g.color = Color.WHITE; g.drawString(look.name, cx + 6, cy + th + 19)
        }
        g.dispose()
        val out = File(System.getenv("VELUM_LOOK_OUT") ?: "build/look_sheet.png")
        ImageIO.write(sheet, "png", out)
        println("wrote ${out.absolutePath}")
    }
}
