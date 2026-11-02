package com.daya.velum.media

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.util.Size
import androidx.camera.core.ImageCapture
import androidx.camera.video.MediaStoreOutputOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val ALBUM = "Velum"
private val RELATIVE = "${Environment.DIRECTORY_DCIM}/$ALBUM"

enum class MediaKind { PHOTO, RAW, VIDEO }

data class MediaItem(
    val uri: Uri,
    val kind: MediaKind,
    val name: String,
    val dateMs: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val sizeBytes: Long,
    val mime: String,
)

data class VideoProbe(val ok: Boolean, val durationMs: Long, val width: Int, val height: Int, val fps: Float?, val mime: String?, val hasAudio: Boolean, val bitrate: Int?, val error: String?)

class MediaRepository(private val context: Context) {
    private val resolver get() = context.contentResolver

    fun newBaseName(prefix: String): String =
        "${prefix}_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    /**
     * Deletes this app's own unfinished (IS_PENDING) entries in the album that are over 10 minutes old, i.e. saves
     * cut short when the app was closed. Newer ones may be a recording or retime still in progress.
     */
    suspend fun deletePendingLeftovers(): Int = withContext(Dispatchers.IO) {
        var n = 0
        val cutoffSec = System.currentTimeMillis() / 1000 - 600
        val args = android.os.Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.IS_PENDING}=1 AND " +
                    "${MediaStore.MediaColumns.DATE_ADDED} < ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?")
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf("$RELATIVE%", cutoffSec.toString(), context.packageName))
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY)
        }
        for (base in listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)) {
            runCatching {
                resolver.query(base, arrayOf(MediaStore.MediaColumns._ID), args, null)?.use { c ->
                    while (c.moveToNext()) if (resolver.delete(ContentUris.withAppendedId(base, c.getLong(0)), null, null) > 0) n++
                }
            }
        }
        n
    }

    private fun pendingValues(name: String, mime: String) = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, mime)
        put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }

    /** ImageCapture writes JPEG/DNG straight into MediaStore; we publish (IS_PENDING=0) only after validation. */
    fun photoOutputOptions(baseName: String, mime: String): ImageCapture.OutputFileOptions {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, baseName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE)
        }
        return ImageCapture.OutputFileOptions.Builder(resolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).build()
    }

    fun videoOutputOptions(baseName: String): MediaStoreOutputOptions {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$baseName.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE)
        }
        return MediaStoreOutputOptions.Builder(resolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values).build()
    }

    /** Writes an app-encoded JPEG (graded photo). Published only if the bytes fully land. */
    suspend fun saveJpeg(name: String, writer: (java.io.OutputStream) -> Unit): Uri = withContext(Dispatchers.IO) {
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, pendingValues("$name.jpg", "image/jpeg"))
            ?: error("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri)?.use(writer) ?: error("Cannot open output")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri
        } catch (t: Throwable) {
            resolver.delete(uri, null, null); throw t
        }
    }

    /** Checks an image decodes to the expected bounds. */
    suspend fun probeImage(uri: Uri): Size? = withContext(Dispatchers.IO) {
        runCatching {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
            if (o.outWidth > 0 && o.outHeight > 0) Size(o.outWidth, o.outHeight) else null
        }.getOrNull()
    }

    suspend fun probeVideo(uri: Uri): VideoProbe = withContext(Dispatchers.IO) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val fps = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
                ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toFloatOrNull()?.let { if (dur > 0) it * 1000f / dur else null }
            val hasVideo = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            VideoProbe(hasVideo && dur > 0 && w > 0, dur, w, h, fps, r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
                hasAudio, r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull(), null)
        } catch (t: Throwable) {
            VideoProbe(false, 0, 0, 0, null, null, false, null, t.message)
        } finally { runCatching { r.release() } }
    }

    suspend fun list(): List<MediaItem> = withContext(Dispatchers.IO) {
        val out = mutableListOf<MediaItem>()
        val proj = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DURATION,
        )
        val sel = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.IS_PENDING}=0"
        val args = arrayOf("$RELATIVE%")
        for ((base, isVideo) in listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI to false, MediaStore.Video.Media.EXTERNAL_CONTENT_URI to true)) {
            resolver.query(base, proj, sel, args, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0); val mime = c.getString(6) ?: ""
                    out += MediaItem(
                        uri = ContentUris.withAppendedId(base, id),
                        kind = when { isVideo -> MediaKind.VIDEO; mime.contains("dng") -> MediaKind.RAW; else -> MediaKind.PHOTO },
                        name = c.getString(1) ?: "", dateMs = c.getLong(2) * 1000, width = c.getInt(3), height = c.getInt(4),
                        durationMs = if (isVideo) c.getLong(7) else 0, sizeBytes = c.getLong(5), mime = mime,
                    )
                }
            }
        }
        out.sortedWith(compareByDescending<MediaItem> { it.dateMs }.thenBy { it.kind != MediaKind.PHOTO })
    }

    suspend fun latest(): MediaItem? = list().firstOrNull { it.kind != MediaKind.RAW }

    suspend fun thumbnail(uri: Uri, px: Int): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { resolver.loadThumbnail(uri, Size(px, px), null) }.getOrNull()
    }

    /** Deletes an item this app created. Returns false if the system requires user consent instead. */
    suspend fun delete(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching { resolver.delete(uri, null, null) > 0 }.getOrDefault(false)
    }

    fun freeBytes(): Long = runCatching { StatFs(Environment.getExternalStorageDirectory().path).availableBytes }.getOrDefault(-1L)
}
