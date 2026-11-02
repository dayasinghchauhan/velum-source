package com.daya.velum.imaging

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

private const val TAG = "Velum/ML"

/**
 * On-device depth (MiDaS v2.1 small, MIT) and person segmentation (MediaPipe selfie segmenter, Apache-2.0) on a
 * dedicated thread. Frames arriving while a run is in progress are dropped, so latency never accumulates.
 * Everything stays on the device; no model ever touches the network.
 *
 * Coordinates: input and outputs are 256×256 in the processor's buffer space and GL row order. [rotation] (0/90/180/270)
 * rotates the model's view so people appear upright to the networks; results are rotated back before use.
 */
class MlRunner(private val context: Context) {
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "VelumML").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val busy = AtomicBoolean(false)
    private var depth: Model? = null
    private var seg: Model? = null

    /** One LiteRT CompiledModel with reusable I/O buffers; GPU first, CPU if the GPU accelerator is unavailable. */
    private inner class Model(path: String) : AutoCloseable {
        private val model: CompiledModel = runCatching {
            CompiledModel.create(context.assets, path, CompiledModel.Options(Accelerator.GPU))
        }.getOrElse {
            Log.w(TAG, "GPU unavailable for $path (${it.message}); using CPU")
            CompiledModel.create(context.assets, path, CompiledModel.Options(Accelerator.CPU))
        }
        private val inputs: List<TensorBuffer> = model.createInputBuffers()
        private val outputs: List<TensorBuffer> = model.createOutputBuffers()
        init { Log.i(TAG, "loaded $path") }
        fun run(input: FloatArray): FloatArray {
            inputs[0].writeFloat(input)
            model.run(inputs, outputs)
            return outputs[0].readFloat()
        }
        override fun close() { inputs.forEach { it.close() }; outputs.forEach { it.close() }; model.close() }
    }
    @Volatile var wantDepth = false
    @Volatile var wantMask = false
    @Volatile var rotation = 0
    @Volatile var onDepth: ((ByteBuffer) -> Unit)? = null
    @Volatile var onMask: ((ByteBuffer) -> Unit)? = null
    @Volatile var debugDumpOnce = false
    @Volatile var lastDepthMs = 0L
    @Volatile var lastSegMs = 0L

    /** Latest normalised depth (0..1, 1 = nearest) in buffer space, for focus picking. Row 0 = GL bottom row. */
    @Volatile var depthMap: FloatArray? = null
        private set
    /** Latest person probability in buffer space. */
    @Volatile var personMap: FloatArray? = null
        private set

    private val n = 256
    private var runs = 0
    private var smoothLo = Float.NaN
    private var smoothHi = Float.NaN
    private var prevDepth: FloatArray? = null

    // ---- subject tracker (normalised cross-correlation on 256² luma, buffer space) ----
    @Volatile var wantTrack = false
    @Volatile private var trackRequest: Pair<Float, Float>? = null
    /** Tracked point in buffer space (u, v from GL bottom) and match confidence 0..1; null when lost. */
    @Volatile var tracked: Triple<Float, Float, Float>? = null
        private set
    private var template: FloatArray? = null
    private var tx = 0; private var ty = 0
    private val tr = 12 // template half-size
    private val sr = 22 // search radius

    fun track(u: Float, v: Float) { trackRequest = u to v; wantTrack = true }
    fun stopTracking() { wantTrack = false; template = null; tracked = null }

    private fun runTracker(src: ByteBuffer) {
        val gray = FloatArray(n * n)
        for (i in 0 until n * n) {
            val b = i * 4
            gray[i] = 0.299f * (src.get(b).toInt() and 255) + 0.587f * (src.get(b + 1).toInt() and 255) + 0.114f * (src.get(b + 2).toInt() and 255)
        }
        trackRequest?.let { (u, v) ->
            tx = (u * (n - 1)).toInt().coerceIn(tr, n - 1 - tr); ty = (v * (n - 1)).toInt().coerceIn(tr, n - 1 - tr)
            template = patch(gray, tx, ty); trackRequest = null
            tracked = Triple(tx / (n - 1f), ty / (n - 1f), 1f)
            return
        }
        val t = template ?: return
        var best = -2f; var bx = tx; var by = ty
        for (y in max(tr, ty - sr)..min(n - 1 - tr, ty + sr) step 2) for (x in max(tr, tx - sr)..min(n - 1 - tr, tx + sr) step 2) {
            val c = ncc(gray, x, y, t)
            if (c > best) { best = c; bx = x; by = y }
        }
        // Refine at full step around the coarse best.
        for (y in max(tr, by - 1)..min(n - 1 - tr, by + 1)) for (x in max(tr, bx - 1)..min(n - 1 - tr, bx + 1)) {
            val c = ncc(gray, x, y, t)
            if (c > best) { best = c; bx = x; by = y }
        }
        if (best < 0.45f) { tracked = tracked?.let { Triple(it.first, it.second, 0f) }; return }
        tx = bx; ty = by
        // Slowly adapt the template so the subject can turn and change shape.
        val fresh = patch(gray, tx, ty)
        for (i in t.indices) t[i] = t[i] * 0.85f + fresh[i] * 0.15f
        tracked = Triple(tx / (n - 1f), ty / (n - 1f), best)
    }

    private fun patch(g: FloatArray, cx: Int, cy: Int): FloatArray {
        val w = 2 * tr + 1
        return FloatArray(w * w) { i -> g[(cy - tr + i / w) * n + (cx - tr + i % w)] }
    }

    private fun ncc(g: FloatArray, cx: Int, cy: Int, t: FloatArray): Float {
        val w = 2 * tr + 1
        var sa = 0f; var sb = 0f
        for (i in t.indices) { sa += g[(cy - tr + i / w) * n + (cx - tr + i % w)]; sb += t[i] }
        val ma = sa / t.size; val mb = sb / t.size
        var num = 0f; var da = 0f; var db = 0f
        for (i in t.indices) {
            val a = g[(cy - tr + i / w) * n + (cx - tr + i % w)] - ma; val b = t[i] - mb
            num += a * b; da += a * a; db += b * b
        }
        return if (da <= 1e-3f || db <= 1e-3f) 0f else num / kotlin.math.sqrt(da * db)
    }

    fun submit(f: MlFrame) {
        if (!wantDepth && !wantMask && !wantTrack) return
        if (!busy.compareAndSet(false, true)) return
        exec.execute {
            try { run(f) } catch (t: Throwable) { Log.e(TAG, "inference failed", t) } finally { busy.set(false) }
        }
    }

    fun release() = exec.execute {
        depth?.close(); seg?.close()
        depth = null; seg = null
    }

    /** Maps a model-space pixel (x,y) back to buffer space for the configured rotation. */
    private fun toBuffer(x: Int, y: Int): Int {
        val (bx, by) = when (rotation) {
            90 -> Pair(y, n - 1 - x)
            180 -> Pair(n - 1 - x, n - 1 - y)
            270 -> Pair(n - 1 - y, x)
            else -> Pair(x, y)
        }
        return by * n + bx
    }

    private fun run(f: MlFrame) {
        val src = f.rgba
        if (wantTrack) runTracker(src)
        if (!wantDepth && !wantMask) return
        // Build the model-space RGB image (rotated upright). GL row 0 is the bottom row, so flip vertically too.
        val rgb = FloatArray(n * n * 3)
        for (y in 0 until n) for (x in 0 until n) {
            val bi = toBuffer(x, n - 1 - y) * 4
            val o = (y * n + x) * 3
            rgb[o] = (src.get(bi).toInt() and 255) / 255f
            rgb[o + 1] = (src.get(bi + 1).toInt() and 255) / 255f
            rgb[o + 2] = (src.get(bi + 2).toInt() and 255) / 255f
        }
        val dumping = debugDumpOnce
        if (dumping) { debugDumpOnce = false; dump(rgb, "ml_input.png") }
        runs++
        if (runs % 60 == 1) Log.i(TAG, "run #$runs rot=$rotation meanIn=${"%.3f".format(rgb.average())} depth=${lastDepthMs}ms seg=${lastSegMs}ms")

        if (wantMask) {
            val t0 = SystemClock.elapsedRealtime()
            val m = seg ?: Model("models/selfie_segmenter.tflite").also { seg = it }
            val out = m.run(rgb)
            val prob = FloatArray(n * n)
            val bytes = ByteBuffer.allocateDirect(n * n)
            for (y in 0 until n) for (x in 0 until n) {
                val p = out[y * n + x].coerceIn(0f, 1f)
                val bi = toBuffer(x, n - 1 - y)
                prob[bi] = p
            }
            for (i in 0 until n * n) bytes.put(i, (prob[i] * 255f).toInt().toByte())
            personMap = prob
            if (dumping) dumpGray(prob, "ml_mask.png")
            onMask?.invoke(bytes)
            lastSegMs = SystemClock.elapsedRealtime() - t0
        }
        if (wantDepth) {
            val t0 = SystemClock.elapsedRealtime()
            val d = depth ?: Model("models/midas_v21_small_256.tflite").also { depth = it }
            val mean = floatArrayOf(0.485f, 0.456f, 0.406f); val std = floatArrayOf(0.229f, 0.224f, 0.225f)
            val norm = FloatArray(n * n * 3) { i -> (rgb[i] - mean[i % 3]) / std[i % 3] }
            val raw = d.run(norm)
            // Robust range (2nd–98th percentile), smoothed over time so the map doesn't pump.
            val sorted = raw.copyOf().also { it.sort() }
            val lo = sorted[(n * n * 0.02).toInt()]; val hi = sorted[(n * n * 0.98).toInt()]
            smoothLo = if (smoothLo.isNaN()) lo else smoothLo * 0.8f + lo * 0.2f
            smoothHi = if (smoothHi.isNaN()) hi else smoothHi * 0.8f + hi * 0.2f
            val span = max(1e-3f, smoothHi - smoothLo)
            val map = FloatArray(n * n)
            for (y in 0 until n) for (x in 0 until n) {
                map[toBuffer(x, n - 1 - y)] = ((raw[y * n + x] - smoothLo) / span).coerceIn(0f, 1f)
            }
            val prev = prevDepth
            if (prev != null) for (i in map.indices) map[i] = prev[i] * 0.45f + map[i] * 0.55f
            prevDepth = map
            depthMap = map
            if (dumping) dumpGray(map, "ml_depth.png")
            val bytes = ByteBuffer.allocateDirect(n * n)
            for (i in 0 until n * n) bytes.put(i, (map[i] * 255f).toInt().toByte())
            onDepth?.invoke(bytes)
            lastDepthMs = SystemClock.elapsedRealtime() - t0
        }
    }

    /** Median depth in a small window around buffer-space (u, v) 0..1 (v measured from the GL bottom row). */
    fun depthAt(u: Float, v: Float): Float? {
        val m = depthMap ?: return null
        val cx = (u * (n - 1)).toInt().coerceIn(0, n - 1); val cy = (v * (n - 1)).toInt().coerceIn(0, n - 1)
        val vals = ArrayList<Float>()
        for (dy in -4..4) for (dx in -4..4) {
            val x = cx + dx; val y = cy + dy
            if (x in 0 until n && y in 0 until n) vals += m[y * n + x]
        }
        vals.sort()
        return vals[vals.size / 2]
    }

    fun reset() { prevDepth = null; smoothLo = Float.NaN; smoothHi = Float.NaN; depthMap = null; personMap = null }

    /**
     * Depth (0..1, 1 = nearest) and person probability for a finished still, as [n]×[n] maps in the image's own
     * (upright) orientation. Runs on the ML thread and blocks the caller; call from a background dispatcher.
     */
    fun stillMaps(bmp: android.graphics.Bitmap): Triple<FloatArray, FloatArray, Int> = exec.submit(java.util.concurrent.Callable {
        val s = android.graphics.Bitmap.createScaledBitmap(bmp, n, n, true)
        val px = IntArray(n * n); s.getPixels(px, 0, n, 0, 0, n, n); s.recycle()
        val rgb = FloatArray(n * n * 3)
        for (i in 0 until n * n) {
            val c = px[i]
            rgb[i * 3] = (c shr 16 and 255) / 255f; rgb[i * 3 + 1] = (c shr 8 and 255) / 255f; rgb[i * 3 + 2] = (c and 255) / 255f
        }
        val sm = seg ?: Model("models/selfie_segmenter.tflite").also { seg = it }
        val pOut = sm.run(rgb)
        val person = FloatArray(n * n) { pOut[it].coerceIn(0f, 1f) }
        val d = depth ?: Model("models/midas_v21_small_256.tflite").also { depth = it }
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f); val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        val raw = d.run(FloatArray(n * n * 3) { i -> (rgb[i] - mean[i % 3]) / std[i % 3] })
        val sorted = raw.copyOf().also { it.sort() }
        val lo = sorted[(n * n * 0.02).toInt()]; val hi = sorted[(n * n * 0.98).toInt()]
        val span = max(1e-3f, hi - lo)
        val dm = FloatArray(n * n) { ((raw[it] - lo) / span).coerceIn(0f, 1f) }
        Triple(dm, person, n)
    }).get()

    /** Debug: writes a buffer-space map as an upright-for-the-model grey image (same orientation as ml_input). */
    private fun dumpGray(m: FloatArray, name: String) = runCatching {
        val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        for (y in 0 until n) for (x in 0 until n) {
            val v = (m[toBuffer(x, n - 1 - y)] * 255).toInt().coerceIn(0, 255)
            b.setPixel(x, y, (0xFF shl 24) or (v shl 16) or (v shl 8) or v)
        }
        java.io.File(context.cacheDir, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun dump(rgb: FloatArray, name: String) = runCatching {
        val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        for (y in 0 until n) for (x in 0 until n) {
            val o = (y * n + x) * 3
            b.setPixel(x, y, (0xFF shl 24) or ((rgb[o] * 255).toInt() shl 16) or ((rgb[o + 1] * 255).toInt() shl 8) or (rgb[o + 2] * 255).toInt())
        }
        java.io.File(context.cacheDir, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(TAG, "dumped $name rotation=$rotation")
    }

}
