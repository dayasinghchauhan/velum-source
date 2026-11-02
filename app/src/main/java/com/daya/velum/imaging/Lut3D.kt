package com.daya.velum.imaging

import java.io.BufferedReader
import kotlin.math.floor

/** RGB 3D LUT, red index fastest (the .cube convention). Values are display-domain 0..1. */
class Lut3D(val size: Int, val data: FloatArray, val title: String? = null) {
    init {
        require(size in 2..MAX_SIZE) { "LUT size $size outside 2..$MAX_SIZE" }
        require(data.size == size * size * size * 3) { "LUT data has ${data.size / 3} entries, expected ${size * size * size}" }
    }

    /** Trilinear lookup; [out] receives RGB. */
    fun sample(r: Float, g: Float, b: Float, out: FloatArray) {
        val n1 = size - 1
        val fr = r.coerceIn(0f, 1f) * n1; val fg = g.coerceIn(0f, 1f) * n1; val fb = b.coerceIn(0f, 1f) * n1
        val r0 = floor(fr).toInt().coerceAtMost(n1 - 1); val g0 = floor(fg).toInt().coerceAtMost(n1 - 1); val b0 = floor(fb).toInt().coerceAtMost(n1 - 1)
        val dr = fr - r0; val dg = fg - g0; val db = fb - b0
        for (c in 0..2) {
            fun v(ri: Int, gi: Int, bi: Int) = data[((bi * size + gi) * size + ri) * 3 + c]
            val c00 = v(r0, g0, b0) * (1 - dr) + v(r0 + 1, g0, b0) * dr
            val c10 = v(r0, g0 + 1, b0) * (1 - dr) + v(r0 + 1, g0 + 1, b0) * dr
            val c01 = v(r0, g0, b0 + 1) * (1 - dr) + v(r0 + 1, g0, b0 + 1) * dr
            val c11 = v(r0, g0 + 1, b0 + 1) * (1 - dr) + v(r0 + 1, g0 + 1, b0 + 1) * dr
            val c0 = c00 * (1 - dg) + c10 * dg
            val c1 = c01 * (1 - dg) + c11 * dg
            out[c] = c0 * (1 - db) + c1 * db
        }
    }

    /** RGBA8 bytes for a GL_TEXTURE_3D upload (width = red axis). */
    fun toRgba8(): ByteArray {
        val n = size * size * size
        val out = ByteArray(n * 4)
        for (i in 0 until n) {
            out[i * 4] = (data[i * 3].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
            out[i * 4 + 1] = (data[i * 3 + 1].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
            out[i * 4 + 2] = (data[i * 3 + 2].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
            out[i * 4 + 3] = -1
        }
        return out
    }

    companion object {
        const val MAX_SIZE = 65
        const val DEFAULT_SIZE = 33

        fun identity(size: Int = DEFAULT_SIZE): Lut3D = build(size) { r, g, b, o -> o[0] = r; o[1] = g; o[2] = b }

        inline fun build(size: Int, f: (Float, Float, Float, FloatArray) -> Unit): Lut3D {
            val d = FloatArray(size * size * size * 3)
            val tmp = FloatArray(3)
            val n1 = (size - 1).toFloat()
            var i = 0
            for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
                f(r / n1, g / n1, b / n1, tmp)
                d[i++] = tmp[0]; d[i++] = tmp[1]; d[i++] = tmp[2]
            }
            return Lut3D(size, d)
        }

        /** Look → LUT. An imported cube (if any) is applied first, then the parametric adjustments. */
        fun fromLook(params: LookParams, base: Lut3D? = null, size: Int = DEFAULT_SIZE): Lut3D {
            val pre = FloatArray(3)
            return build(base?.size?.coerceAtLeast(size) ?: size) { r, g, b, o ->
                if (base != null) {
                    base.sample(r, g, b, pre)
                    LookMath.apply(params, pre[0], pre[1], pre[2], o)
                } else LookMath.apply(params, r, g, b, o)
            }
        }
    }
}

class CubeParseException(message: String) : Exception(message)

/**
 * Parser for Adobe/Resolve `.cube` 3D LUTs. Rejects 1D LUTs, sizes over 65, wrong entry counts, non-finite values
 * and unknown keywords that would change interpretation. DOMAIN_MIN/MAX are honoured by normalising the output.
 */
object CubeParser {
    private const val MAX_BYTES = 12L * 1024 * 1024

    fun parse(reader: BufferedReader, byteLength: Long = -1): Lut3D {
        if (byteLength > MAX_BYTES) throw CubeParseException("File too large (${byteLength / 1024} KB)")
        var size = -1
        var title: String? = null
        var domMin = floatArrayOf(0f, 0f, 0f)
        var domMax = floatArrayOf(1f, 1f, 1f)
        var data: FloatArray? = null
        var count = 0
        var lineNo = 0
        reader.lineSequence().forEach { raw ->
            lineNo++
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            val first = line[0]
            if (first.isLetter()) {
                val parts = line.split(Regex("\\s+"))
                when (parts[0].uppercase()) {
                    "TITLE" -> title = line.substringAfter(' ').trim().trim('"')
                    "LUT_3D_SIZE" -> {
                        if (data != null) throw CubeParseException("Line $lineNo: LUT_3D_SIZE after data")
                        size = parts.getOrNull(1)?.toIntOrNull() ?: throw CubeParseException("Line $lineNo: bad LUT_3D_SIZE")
                        if (size !in 2..Lut3D.MAX_SIZE) throw CubeParseException("LUT_3D_SIZE $size not in 2..${Lut3D.MAX_SIZE}")
                        data = FloatArray(size * size * size * 3)
                    }
                    "LUT_1D_SIZE" -> throw CubeParseException("1D LUTs are not supported; export a 3D LUT")
                    "DOMAIN_MIN" -> domMin = triple(parts, lineNo)
                    "DOMAIN_MAX" -> domMax = triple(parts, lineNo)
                    "LUT_IN_VIDEO_RANGE", "LUT_OUT_VIDEO_RANGE" -> throw CubeParseException("Video-range LUTs are not supported")
                    "LUT_3D_INPUT_RANGE" -> {
                        val a = parts.getOrNull(1)?.toFloatOrNull(); val b = parts.getOrNull(2)?.toFloatOrNull()
                        if (a == null || b == null) throw CubeParseException("Line $lineNo: bad LUT_3D_INPUT_RANGE")
                        domMin = floatArrayOf(a, a, a); domMax = floatArrayOf(b, b, b)
                    }
                    else -> { /* tolerated metadata such as LUT_IN_COLOR_SPACE */ }
                }
            } else {
                val d = data ?: throw CubeParseException("Line $lineNo: data before LUT_3D_SIZE")
                val v = triple(line.split(Regex("\\s+")), lineNo, offset = 0)
                if (count >= size * size * size) throw CubeParseException("More than ${size * size * size} entries")
                d[count * 3] = v[0]; d[count * 3 + 1] = v[1]; d[count * 3 + 2] = v[2]
                count++
            }
        }
        val d = data ?: throw CubeParseException("No LUT_3D_SIZE found")
        if (count != size * size * size) throw CubeParseException("Expected ${size * size * size} entries, found $count")
        for (c in 0..2) if (domMax[c] <= domMin[c]) throw CubeParseException("DOMAIN_MAX must exceed DOMAIN_MIN")
        // Normalise outputs into 0..1 relative to the declared domain.
        for (i in 0 until count) for (c in 0..2) {
            d[i * 3 + c] = ((d[i * 3 + c] - domMin[c]) / (domMax[c] - domMin[c])).coerceIn(0f, 1f)
        }
        return Lut3D(size, d, title)
    }

    private fun triple(parts: List<String>, lineNo: Int, offset: Int = 1): FloatArray {
        if (parts.size < offset + 3) throw CubeParseException("Line $lineNo: expected 3 numbers")
        return FloatArray(3) { i ->
            val f = parts[offset + i].toFloatOrNull() ?: throw CubeParseException("Line $lineNo: '${parts[offset + i]}' is not a number")
            if (!f.isFinite()) throw CubeParseException("Line $lineNo: non-finite value")
            f
        }
    }
}
