package com.daya.velum.video

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

private const val TAG = "Velum/Vlog"

/** A programmed camera move performed by the app during a shot (smooth zoom ramp). */
enum class ShotMove(val label: String, val from: Float, val to: Float) {
    HOLD("Hold", 1f, 1f), PUSH_IN("Push in", 1f, 1.6f), PULL_OUT("Pull out", 1.6f, 1f), DETAIL("Detail", 2.5f, 3f), WIDE("Wide", 0.6f, 0.6f),
}

data class Shot(val seconds: Float, val prompt: String, val move: ShotMove)

/** A vlog template: a sequence of short directed shots and the look the whole clip uses. */
data class VlogTemplate(val id: String, val name: String, val description: String, val lookId: String, val shots: List<Shot>) {
    val totalSeconds get() = shots.sumOf { it.seconds.toDouble() }.toFloat()
}

object VlogTemplates {
    val all = listOf(
        VlogTemplate("day_out", "Day Out", "Five quick shots that tell a small story of a place.", "vibrant", listOf(
            Shot(3f, "Establish: the whole place, wide", ShotMove.WIDE),
            Shot(2.5f, "Walk in: point at where you're going", ShotMove.PUSH_IN),
            Shot(2f, "A detail: food, sign or texture", ShotMove.DETAIL),
            Shot(2.5f, "People: a friend or the crowd", ShotMove.HOLD),
            Shot(3f, "Goodbye: pull back from the scene", ShotMove.PULL_OUT),
        )),
        VlogTemplate("food", "Food Diary", "Arrival, the dish, the first bite.", "golden_glow", listOf(
            Shot(2.5f, "The place or the table", ShotMove.HOLD),
            Shot(2f, "Close on the dish", ShotMove.DETAIL),
            Shot(2.5f, "Slow push toward the plate", ShotMove.PUSH_IN),
            Shot(2.5f, "First bite or cheers", ShotMove.HOLD),
        )),
        VlogTemplate("city", "City Night", "Lights, streets and motion after dark.", "moody_night", listOf(
            Shot(3f, "Skyline or street, wide", ShotMove.WIDE),
            Shot(2.5f, "Traffic or crowd moving", ShotMove.HOLD),
            Shot(2f, "A neon sign up close", ShotMove.DETAIL),
            Shot(3f, "Walk forward down the street", ShotMove.PUSH_IN),
            Shot(3f, "Look up, then pull back", ShotMove.PULL_OUT),
        )),
        VlogTemplate("travel", "Travel Film", "Cinematic teal-amber trip recap.", "teal_amber", listOf(
            Shot(3f, "Landscape, wide and still", ShotMove.WIDE),
            Shot(2.5f, "Moving: from a car, train or walking", ShotMove.HOLD),
            Shot(2f, "Hands, ticket or map detail", ShotMove.DETAIL),
            Shot(2.5f, "Your face reacting", ShotMove.HOLD),
            Shot(3f, "Push in on the destination", ShotMove.PUSH_IN),
            Shot(3f, "Final wide: pull out", ShotMove.PULL_OUT),
        )),
        VlogTemplate("portrait", "Portrait Reel", "Four flattering shots of one person.", "soft_portrait", listOf(
            Shot(2.5f, "Full figure, walking toward you", ShotMove.HOLD),
            Shot(2.5f, "Waist up, slow push", ShotMove.PUSH_IN),
            Shot(2f, "Eyes and smile, close", ShotMove.DETAIL),
            Shot(3f, "Turn away, pull out", ShotMove.PULL_OUT),
        )),
    )
    fun byId(id: String) = all.firstOrNull { it.id == id }
}

/**
 * Joins clips recorded in one session (same codec and size) into one MP4 without re-encoding. Optionally replaces
 * the audio with an AAC track from [musicUri], trimmed to the video length.
 */
object VlogStitcher {
    fun stitch(context: Context, parts: List<File>, out: java.io.FileDescriptor, musicUri: Uri?): Long {
        require(parts.isNotEmpty()) { "No shots to join" }
        val rotation = MediaMetadataRetriever().run {
            try { setDataSource(parts[0].absolutePath); extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0 }
            finally { release() }
        }
        val muxer = MediaMuxer(out, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer.setOrientationHint(rotation)
        val first = MediaExtractor().apply { setDataSource(parts[0].absolutePath) }
        var vFmt: MediaFormat? = null; var aFmt: MediaFormat? = null
        for (i in 0 until first.trackCount) {
            val f = first.getTrackFormat(i); val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/") && vFmt == null) vFmt = f
            if (mime.startsWith("audio/") && aFmt == null) aFmt = f
        }
        first.release()
        val music = musicUri?.let { uri ->
            MediaExtractor().apply { setDataSource(context, uri, null) }.takeIf { ex ->
                (0 until ex.trackCount).any { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC }
            }
        }
        if (musicUri != null && music == null) Log.w(TAG, "music is not AAC; keeping recorded audio")
        val musicTrack = music?.let { ex -> (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC } }
        val vOut = muxer.addTrack(vFmt ?: error("First shot has no video"))
        val aOut = when {
            music != null -> muxer.addTrack(music.getTrackFormat(musicTrack!!))
            aFmt != null -> muxer.addTrack(aFmt)
            else -> -1
        }
        muxer.start()
        val buf = ByteBuffer.allocate(8 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        var offsetUs = 0L
        for (part in parts) {
            val ex = MediaExtractor().apply { setDataSource(part.absolutePath) }
            var partEnd = 0L
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                val target = when {
                    mime.startsWith("video/") -> vOut
                    mime.startsWith("audio/") && music == null && aOut >= 0 -> aOut
                    else -> -1
                }
                if (target < 0) continue
                ex.selectTrack(i)
                ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                var firstTs = -1L
                while (true) {
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) break
                    val ts = ex.sampleTime
                    if (firstTs < 0) firstTs = ts
                    info.set(0, n, offsetUs + (ts - firstTs), if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(target, buf, info)
                    partEnd = maxOf(partEnd, ts - firstTs)
                    ex.advance()
                }
                ex.unselectTrack(i)
            }
            ex.release()
            offsetUs += partEnd + 33_333
        }
        if (music != null) {
            music.selectTrack(musicTrack!!)
            var firstTs = -1L
            while (true) {
                val n = music.readSampleData(buf, 0)
                if (n < 0) break
                val ts = music.sampleTime
                if (firstTs < 0) firstTs = ts
                val t = ts - firstTs
                if (t > offsetUs) break
                info.set(0, n, t, 0)
                muxer.writeSampleData(aOut, buf, info)
                music.advance()
            }
            music.release()
        }
        muxer.stop(); muxer.release()
        return offsetUs / 1000
    }
}
