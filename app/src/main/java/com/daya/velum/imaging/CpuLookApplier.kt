package com.daya.velum.imaging

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Applies the same LUT + intensity + grain + vignette as the GPU shader to a full-resolution still, in parallel
 * row bands. Mirrors `LookProcessor` fragment shader maths so preview and saved photo match.
 */
object CpuLookApplier {

    suspend fun apply(src: Bitmap, lut: Lut3D, intensity: Float, params: LookParams, seed: Float = 0.37f): Bitmap =
        withContext(Dispatchers.Default) {
            val w = src.width; val h = src.height
            val raw = IntArray(w * h)
            src.getPixels(raw, 0, w, 0, 0, w, h)
            // Spatial sharpening/softening first (same kernel as the shader), scaled by look intensity.
            val px = if (params.sharpness != 0f) Sharpness.apply(raw, w, h, params.sharpness * intensity) else raw
            val bands = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
            val rowsPer = (h + bands - 1) / bands
            val aspect = w.toFloat() / h
            val grainScale = 1080f / minOf(w, h)
            coroutineScope {
                (0 until bands).map { band ->
                    async {
                        val y0 = band * rowsPer; val y1 = minOf(h, y0 + rowsPer)
                        if (y0 >= y1) return@async
                        val o = FloatArray(3)
                        for (y in y0 until y1) {
                            val v = (y + 0.5f) / h
                            val base = y * w
                            for (x in 0 until w) {
                                val c = px[base + x]
                                val r = ((c shr 16) and 0xFF) / 255f
                                val g = ((c shr 8) and 0xFF) / 255f
                                val b = (c and 0xFF) / 255f
                                lut.sample(r, g, b, o)
                                var rr = r + (o[0] - r) * intensity
                                var gg = g + (o[1] - g) * intensity
                                var bb = b + (o[2] - b) * intensity
                                if (params.grain > 0f) {
                                    // Grain cell size is relative to the short side so it matches the 1080p preview.
                                    val n = LookMath.hash(kotlin.math.floor(x * grainScale), kotlin.math.floor(y * grainScale), seed) *
                                        params.grain * 0.16f * intensity
                                    val l = 0.2126f * rr + 0.7152f * gg + 0.0722f * bb
                                    val k = n * (1f - 0.6f * l)
                                    rr += k; gg += k; bb += k
                                }
                                if (params.vignette > 0f) {
                                    val f = 1f - (1f - LookMath.vignetteFactor((x + 0.5f) / w, v, params.vignette, aspect)) * intensity
                                    rr *= f; gg *= f; bb *= f
                                }
                                px[base + x] = (0xFF shl 24) or (to8(rr) shl 16) or (to8(gg) shl 8) or to8(bb)
                            }
                        }
                    }
                }.awaitAll()
            }
            Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        }

    private fun to8(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
}
