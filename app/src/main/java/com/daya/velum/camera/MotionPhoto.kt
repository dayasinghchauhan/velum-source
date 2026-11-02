package com.daya.velum.camera

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

/**
 * Google Motion Photo format 1.0: the still JPEG carries an XMP Container directory (Primary JPEG + MotionPhoto MP4)
 * and the MP4 bytes are appended after the JPEG's end. Viewers that do not understand it simply show the JPEG.
 * https://developer.android.com/media/platform/motion-photo-format
 */
object MotionPhoto {
    fun embed(jpeg: ByteArray, mp4: ByteArray, presentationUs: Long): ByteArray {
        require(jpeg.size > 4 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) { "Not a JPEG" }
        val xmp = xmp(mp4.size, presentationUs).toByteArray(Charsets.UTF_8)
        val ns = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)
        val segLen = 2 + ns.size + xmp.size
        require(segLen <= 0xFFFF) { "XMP too large" }
        // Insert after SOI and any leading APP0/APP1 segments (JFIF, Exif), so Exif stays the first APP1.
        var pos = 2
        while (pos + 4 <= jpeg.size && jpeg[pos] == 0xFF.toByte()) {
            val marker = jpeg[pos + 1].toInt() and 0xFF
            if (marker != 0xE0 && marker != 0xE1) break
            val len = ((jpeg[pos + 2].toInt() and 0xFF) shl 8) or (jpeg[pos + 3].toInt() and 0xFF)
            pos += 2 + len
        }
        val out = ByteArrayOutputStream(jpeg.size + segLen + 2 + mp4.size)
        out.write(jpeg, 0, pos)
        out.write(0xFF); out.write(0xE1); out.write(segLen shr 8); out.write(segLen and 0xFF)
        out.write(ns); out.write(xmp)
        out.write(jpeg, pos, jpeg.size - pos)
        out.write(mp4)
        return out.toByteArray()
    }

    private fun xmp(mp4Length: Int, presentationUs: Long) = """<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Velum">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about=""
    xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
    xmlns:Container="http://ns.google.com/photos/1.0/container/"
    xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
   GCamera:MotionPhoto="1"
   GCamera:MotionPhotoVersion="1"
   GCamera:MotionPhotoPresentationTimestampUs="$presentationUs">
   <Container:Directory>
    <rdf:Seq>
     <rdf:li rdf:parseType="Resource">
      <Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/>
     </rdf:li>
     <rdf:li rdf:parseType="Resource">
      <Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="$mp4Length" Item:Padding="0"/>
     </rdf:li>
    </rdf:Seq>
   </Container:Directory>
  </rdf:Description>
 </rdf:RDF>
</x:xmpmeta>"""
}

object Mp4Trim {
    /**
     * Copies the last [keepUs] of [src]'s video (starting at the preceding key frame, no re-encode) to [dst].
     * Returns the time of [markUs] (a time in [src]) inside the new file, or null if [src] has no video.
     */
    fun lastSeconds(src: File, dst: File, keepUs: Long, markUs: Long): Long? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(src.absolutePath)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true } ?: return null
            val fmt = ex.getTrackFormat(track)
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            ex.selectTrack(track)
            ex.seekTo((durUs - keepUs).coerceAtLeast(0L), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val first = ex.sampleTime.takeIf { it >= 0 } ?: return null
            val rot = MediaMetadataRetriever().use { r -> r.setDataSource(src.absolutePath); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0 }
            val mux = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            mux.setOrientationHint(rot)
            val out = mux.addTrack(fmt)
            mux.start()
            val buf = ByteBuffer.allocate(if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 4 shl 20)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                info.set(0, n, ex.sampleTime - first, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                mux.writeSampleData(out, buf, info)
                ex.advance()
            }
            mux.stop(); mux.release()
            return (markUs - first).coerceAtLeast(0L)
        } finally { ex.release() }
    }
}
