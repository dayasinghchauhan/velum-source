package com.daya.velum.imaging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.daya.velum.media.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Produces a graded copy of a saved JPEG. The original file is never modified. EXIF is copied from the original,
 * orientation is normalised (pixels are rotated) and the Software tag records the look.
 */
class PhotoGrader(private val context: Context, private val media: MediaRepository) {

    suspend fun grade(src: Uri, look: Look, lut: Lut3D, intensity: Float, quality: Int, frame: PhotoFrame = PhotoFrame.OFF,
                      portrait: ((Bitmap) -> Bitmap)? = null): Uri = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        val exifIn = cr.openInputStream(src)?.use { ExifInterface(it) } ?: error("Cannot read source EXIF")
        val decoded = cr.openInputStream(src)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inMutable = false }) }
            ?: error("Cannot decode source")
        val rot = exifIn.rotationDegrees
        val upright = if (rot != 0) Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
            .also { decoded.recycle() } else decoded
        // Depth blur works on the camera's colours, before any look.
        val blurred = portrait?.let { f -> f(upright).also { if (it !== upright) upright.recycle() } } ?: upright
        val toned = if (look.isNeutral) blurred else CpuLookApplier.apply(blurred, lut, intensity, look.params).also { blurred.recycle() }
        val graded = InfoFrame.apply(context, toned, exifIn, frame).also { if (it !== toned) toned.recycle() }
        val tmp = File.createTempFile("grade", ".jpg", context.cacheDir)
        try {
            tmp.outputStream().use { graded.compress(Bitmap.CompressFormat.JPEG, quality, it) }
            val w = graded.width; val h = graded.height
            graded.recycle()
            val exifOut = ExifInterface(tmp)
            for (tag in COPY_TAGS) exifIn.getAttribute(tag)?.let { exifOut.setAttribute(tag, it) }
            exifOut.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            exifOut.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, w.toString())
            exifOut.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, h.toString())
            exifOut.setAttribute(ExifInterface.TAG_SOFTWARE, "Velum" + (if (portrait != null) " · depth blur" else "") +
                (if (look.isNeutral) "" else " · look: ${look.name} ${(intensity * 100).toInt()}%") +
                (if (frame != PhotoFrame.OFF) " · ${frame.label.lowercase()}" else ""))
            exifOut.saveAttributes()
            val name = (cr.query(src, arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: media.newBaseName("VLM")).substringBeforeLast('.') + "_" + when {
                portrait != null && look.isNeutral -> "portrait"
                portrait != null -> "portrait_" + look.id.take(12)
                look.isNeutral -> "frame"
                else -> look.id.take(12)
            }
            media.saveJpeg(name) { out -> tmp.inputStream().use { it.copyTo(out) } }
        } finally { tmp.delete() }
    }

    companion object {
        private val COPY_TAGS = listOf(
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_ISO_SPEED_RATINGS, ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_EXPOSURE_PROGRAM,
            ExifInterface.TAG_METERING_MODE, ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_LENS_MAKE,
            ExifInterface.TAG_LENS_MODEL, ExifInterface.TAG_APERTURE_VALUE, ExifInterface.TAG_SHUTTER_SPEED_VALUE,
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
        )
    }
}
