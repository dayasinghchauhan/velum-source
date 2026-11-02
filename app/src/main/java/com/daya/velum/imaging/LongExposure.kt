package com.daya.velum.imaging

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.max
import kotlin.math.min

/** How frames combine. AVERAGE smooths motion and noise; LIGHTEN keeps the brightest value each pixel ever saw. */
enum class Blend { AVERAGE, LIGHTEN }

/**
 * Real multi-frame long exposure from full-resolution YUV_420_888 frames. Accumulation runs in parallel row bands;
 * frames that arrive while a frame is still being added are dropped by the caller (KEEP_ONLY_LATEST), so the work
 * never queues up. Chroma is accumulated at its native ½×½ resolution.
 */
class LongExposure(private val blend: Blend, private val align: Boolean = false) {
    private val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceIn(2, 8))
    var width = 0; private set
    var height = 0; private set
    var frames = 0; private set
    private var y = IntArray(0)          // sum (AVERAGE) or max (LIGHTEN) of luma
    private var u = IntArray(0)
    private var v = IntArray(0)
    private var blockY = IntArray(0)     // LIGHTEN: brightest 2×2 luma seen per chroma sample
    private var rotation = 0
    // Alignment (astro): every frame is shifted onto the first one. Shift is searched on 1/16-scale luma.
    private var ref: FloatArray? = null
    private var lastShift = 0 to 0
    var maxShiftPx = 0; private set

    /** Adds one frame. Must not be called concurrently. */
    fun add(img: ImageProxy) {
        val w = img.width; val h = img.height
        if (frames == 0) {
            width = w; height = h; rotation = img.imageInfo.rotationDegrees
            y = IntArray(w * h); u = IntArray((w / 2) * (h / 2)); v = IntArray((w / 2) * (h / 2))
            if (blend == Blend.LIGHTEN) blockY = IntArray((w / 2) * (h / 2))
        } else if (w != width || h != height) return
        val py = img.planes[0]; val pu = img.planes[1]; val pv = img.planes[2]
        val yb = py.buffer; val ub = pu.buffer; val vb = pv.buffer
        val yRow = py.rowStride; val yPix = py.pixelStride
        val uRow = pu.rowStride; val uPix = pu.pixelStride
        val vRow = pv.rowStride; val vPix = pv.pixelStride
        val cw = w / 2; val ch = h / 2
        // Source offset in chroma samples (luma offset is twice this), so luma and chroma stay registered.
        var ocx = 0; var ocy = 0
        if (align) {
            val f = ALIGN_SCALE; val sw = w / f; val sh = h / f
            val small = FloatArray(sw * sh) { i -> (yb.get((i / sw) * f * yRow + (i % sw) * f * yPix).toInt() and 255).toFloat() }
            val r0 = ref
            if (r0 == null) { if (FrameMath.alignable(small)) ref = small }
            else if (FrameMath.alignable(small)) {
                val (dx0, dy0, _) = FrameMath.shift(r0, small, sw, sh, 4, lastShift)
                // A real hand-held or tripod drift is a few percent of the frame at most.
                val lim = sw / 12
                val dx = dx0.coerceIn(-lim, lim); val dy = dy0.coerceIn(-lim, lim)
                lastShift = dx to dy
                maxShiftPx = max(maxShiftPx, max(kotlin.math.abs(dx), kotlin.math.abs(dy)) * f)
            }
            ocx = lastShift.first * f / 2; ocy = lastShift.second * f / 2
        }
        val bands = 8
        val tasks = ArrayList<Future<*>>(bands)
        for (band in 0 until bands) {
            val r0 = band * ch / bands; val r1 = (band + 1) * ch / bands
            tasks += pool.submit {
                for (cy in r0 until r1) {
                    val scy = (cy + ocy).coerceIn(0, ch - 1)
                    for (cx in 0 until cw) {
                        val ci = cy * cw + cx
                        val scx = (cx + ocx).coerceIn(0, cw - 1)
                        val uu = ub.get(scy * uRow + scx * uPix).toInt() and 255
                        val vv = vb.get(scy * vRow + scx * vPix).toInt() and 255
                        var bsum = 0
                        for (dy in 0..1) {
                            val row = (cy * 2 + dy)
                            val base = (scy * 2 + dy) * yRow
                            for (dx in 0..1) {
                                val col = cx * 2 + dx
                                val lum = yb.get(base + (scx * 2 + dx) * yPix).toInt() and 255
                                val yi = row * w + col
                                if (blend == Blend.AVERAGE) y[yi] += lum else if (lum > y[yi]) y[yi] = lum
                                bsum += lum
                            }
                        }
                        if (blend == Blend.AVERAGE) { u[ci] += uu; v[ci] += vv }
                        else if (bsum > blockY[ci] || frames == 0) { blockY[ci] = bsum; u[ci] = uu; v[ci] = vv }
                    }
                }
            }
        }
        tasks.forEach { it.get() }
        frames++
    }

    /** Renders the current accumulation. [scale] = 1 for the final image, larger for a quick preview. */
    fun render(scale: Int = 1): Bitmap? {
        if (frames == 0) return null
        val w = width / scale; val h = height / scale
        val px = IntArray(w * h)
        val n = if (blend == Blend.AVERAGE) frames else 1
        val cw = width / 2
        val bands = 8
        val tasks = (0 until bands).map { band ->
            pool.submit {
                for (oy in band * h / bands until (band + 1) * h / bands) {
                    val sy = oy * scale
                    for (ox in 0 until w) {
                        val sx = ox * scale
                        val ci = min(height / 2 - 1, sy / 2) * cw + min(cw - 1, sx / 2)
                        val yy = y[sy * width + sx].toFloat() / n
                        val uu = u[ci].toFloat() / n - 128f
                        val vv = v[ci].toFloat() / n - 128f
                        // BT.601 full-range YUV → RGB (camera YUV_420_888 is full-range JFIF).
                        val r = yy + 1.402f * vv
                        val g = yy - 0.344136f * uu - 0.714136f * vv
                        val b = yy + 1.772f * uu
                        px[oy * w + ox] = (0xFF shl 24) or (c8(r) shl 16) or (c8(g) shl 8) or c8(b)
                    }
                }
            }
        }
        tasks.forEach { it.get() }
        val bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        if (rotation == 0) return bmp
        return Bitmap.createBitmap(bmp, 0, 0, w, h, Matrix().apply { postRotate(rotation.toFloat()) }, true).also { bmp.recycle() }
    }

    fun release() { pool.shutdown() }

    private fun c8(f: Float) = max(0, min(255, (f + 0.5f).toInt()))

    companion object { const val ALIGN_SCALE = 16 }
}

/** A named long-exposure recipe: blend, duration and the exposure bias that makes the effect read well. */
enum class LongExposureMode(val label: String, val blend: Blend, val seconds: Int, val evBias: Float, val manualMaxShutter: Boolean, val tip: String) {
    CROWD("Moving crowd", Blend.AVERAGE, 4, 0f, false, "Moving people dissolve; static buildings stay sharp. Hold very still or use a tripod."),
    NEON("Neon trails", Blend.LIGHTEN, 6, -1f, false, "Car lights and neon become streaks. Tripod recommended."),
    SILK("Silky water & clouds", Blend.AVERAGE, 8, -0.3f, false, "Water and moving clouds turn smooth, like a painting."),
    LIGHT_PAINT("Light painting", Blend.LIGHTEN, 15, -2f, false, "In a dark place, draw with a torch or phone light while the capture runs."),
    STAR_TRAILS("Star trails", Blend.LIGHTEN, 60, 0f, true, "Clear dark sky, tripod. Stars draw arcs as the earth turns."),
    STARRY_SKY("Starry sky", Blend.AVERAGE, 15, 0f, true, "Stacks many frames to lift faint stars out of the noise. Tripod required."),
}
