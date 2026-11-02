package com.daya.velum.imaging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import androidx.exifinterface.media.ExifInterface
import com.daya.velum.R
import kotlin.math.min
import kotlin.math.roundToInt

enum class PhotoFrame(val label: String) { OFF("Off"), WHITE("White frame"), BLACK("Black frame") }

/**
 * Adds an information bar under a photo with the real capture data from its EXIF: device, 35 mm-equivalent focal
 * length, aperture, shutter and ISO. Nothing is invented — a value missing from EXIF is simply left out.
 */
object InfoFrame {
    /** Retail names for model codes (the device's own name, shown as the camera that took the photo). */
    private val MODEL_NAMES = mapOf("SM-S928" to "Galaxy S24 Ultra", "SM-S926" to "Galaxy S24+", "SM-S921" to "Galaxy S24",
        "SM-S938" to "Galaxy S25 Ultra", "SM-S948" to "Galaxy S26 Ultra")

    fun apply(context: Context, src: Bitmap, exif: ExifInterface, frame: PhotoFrame): Bitmap {
        if (frame == PhotoFrame.OFF) return src
        val short = min(src.width, src.height)
        val bar = (short * 0.095f).roundToInt()
        val pad = (short * 0.03f).roundToInt()
        val out = Bitmap.createBitmap(src.width, src.height + bar, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val bg = if (frame == PhotoFrame.WHITE) Color.WHITE else Color.rgb(12, 12, 12)
        val fg = if (frame == PhotoFrame.WHITE) Color.rgb(24, 24, 24) else Color.rgb(236, 230, 218)
        val sub = if (frame == PhotoFrame.WHITE) Color.rgb(120, 116, 110) else Color.rgb(142, 137, 128)
        c.drawColor(bg)
        c.drawBitmap(src, 0f, 0f, null)
        val semi = runCatching { ResourcesCompat.getFont(context, R.font.plex_cond_semibold) }.getOrNull() ?: Typeface.DEFAULT_BOLD
        val reg = runCatching { ResourcesCompat.getFont(context, R.font.plex_cond_regular) }.getOrNull() ?: Typeface.DEFAULT
        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fg; typeface = semi; textSize = bar * 0.30f }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = sub; typeface = reg; textSize = bar * 0.21f }
        val line1 = src.height + bar * 0.46f
        val line2 = src.height + bar * 0.78f
        // Left: device. Right: exposure triangle and focal length.
        val code = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim().orEmpty().ifEmpty { android.os.Build.MODEL }
        val model = MODEL_NAMES.entries.firstOrNull { code.startsWith(it.key) }?.value ?: code
        c.drawText(model, pad.toFloat(), line1, big)
        c.drawText("Shot on Velum", pad.toFloat(), line2, small)
        val parts = buildList {
            exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM)?.toIntOrNull()?.takeIf { it > 0 }?.let { add("${it}mm") }
            exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0 }?.let { add("f/${"%.1f".format(it)}") }
            exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0 }?.let {
                add(if (it >= 0.3) "${"%.1f".format(it)}s" else "1/${(1.0 / it).roundToInt()}s")
            }
            exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { add("ISO $it") }
        }
        val right = parts.joinToString("   ")
        c.drawText(right, src.width - pad - big.measureText(right), line1, big)
        // EXIF "yyyy:MM:dd HH:mm:ss" → "yyyy.MM.dd HH:mm".
        val date = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.takeIf { it.length >= 16 }?.let { it.substring(0, 10).replace(':', '.') + " " + it.substring(11, 16) }
        if (date != null) c.drawText(date, src.width - pad - small.measureText(date), line2, small)
        // Hairline divider between the two blocks.
        val div = Paint().apply { color = sub; strokeWidth = (bar * 0.012f).coerceAtLeast(1f) }
        val x = src.width - pad - big.measureText(right) - pad * 0.8f
        c.drawLine(x, src.height + bar * 0.24f, x, src.height + bar * 0.82f, div)
        return out
    }
}
