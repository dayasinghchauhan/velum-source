package com.daya.velum.imaging

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * A parametric colour look. Everything except [grain] and [vignette] is baked into a 3D LUT; those two are
 * position/time dependent and are applied by the same formula in the GPU shader and the CPU photo path.
 * All values are in the display (sRGB-encoded) domain; this is a creative grade, not a scene-referred transform.
 */
data class LookParams(
    val exposure: Float = 0f,      // stops, -2..2
    val contrast: Float = 0f,      // -1..1 (negative flattens)
    val saturation: Float = 0f,    // -1..1
    val vibrance: Float = 0f,      // -1..1
    val warmth: Float = 0f,        // -1..1 (blue↔amber)
    val tint: Float = 0f,          // -1..1 (green↔magenta)
    val fade: Float = 0f,          // 0..1 lifts blacks
    val highlightRoll: Float = 0f, // 0..1 soft shoulder
    val shadowHue: Float = 0f,     // degrees
    val shadowAmt: Float = 0f,     // 0..1
    val highlightHue: Float = 0f,
    val highlightAmt: Float = 0f,
    val mono: Float = 0f,          // 0..1
    val monoRed: Float = 0.30f,    // channel mix for mono
    val monoGreen: Float = 0.59f,
    val monoBlue: Float = 0.11f,
    val grain: Float = 0f,         // 0..1 (shader + CPU)
    val vignette: Float = 0f,      // 0..1 (shader + CPU)
    val sharpness: Float = 0f,     // -1..1, negative softens (shader + CPU, spatial)
    val skinProtect: Float = 0f,   // 0..1 pulls skin hues back toward the ungraded colour
    val skinHue: Float = 0f,       // degrees, -20..20: + toward gold/yellow, − toward rose/red
    val skinSat: Float = 0f,       // -1..1 saturation change on skin only
    val skinBright: Float = 0f,    // -1..1 brightness change on skin only
    val shadows: Float = 0f,       // -1..1 lifts (+) or deepens (−) the lower tones, black point fixed
    val highlights: Float = 0f,    // -1..1 brightens (+) or holds back (−) the upper tones, white point fixed
    val bands: HueBands = HueBands(),
) {
    val isIdentity get() = this == LookParams()
}

/** The eight colour bands of a per-hue (HSL) mixer, centred like a photo editor's. */
enum class Band(val label: String, val centre: Float) {
    RED("Red", 0f), ORANGE("Orange", 30f), YELLOW("Yellow", 60f), GREEN("Green", 120f),
    AQUA("Aqua", 180f), BLUE("Blue", 225f), PURPLE("Purple", 270f), MAGENTA("Magenta", 315f)
}

/** Per-band hue shift (degrees, ±30), saturation (−1..1) and luminance (−1..1). Lists are indexed by [Band.ordinal]. */
data class HueBands(val hue: List<Float> = ZERO, val sat: List<Float> = ZERO, val lum: List<Float> = ZERO) {
    val isIdentity get() = hue.all { it == 0f } && sat.all { it == 0f } && lum.all { it == 0f }

    fun with(band: Band, h: Float = hue[band.ordinal], s: Float = sat[band.ordinal], l: Float = lum[band.ordinal]) = HueBands(
        hue.toMutableList().also { it[band.ordinal] = h }, sat.toMutableList().also { it[band.ordinal] = s },
        lum.toMutableList().also { it[band.ordinal] = l })

    companion object {
        val ZERO = List(8) { 0f }
        /** Builder: `HueBands.of(Band.GREEN to Adj(h = -8f, s = -0.2f))`. */
        fun of(vararg e: Pair<Band, Adj>): HueBands = e.fold(HueBands()) { acc, (b, a) -> acc.with(b, a.h, a.s, a.l) }
    }
}

data class Adj(val h: Float = 0f, val s: Float = 0f, val l: Float = 0f)

enum class LookCategory { STYLE, BRAND, NATURAL, MOOD, FILM, MONO, CINEMA, CUSTOM }

data class Look(
    val id: String,
    val name: String,
    val category: LookCategory,
    val description: String,
    val params: LookParams = LookParams(),
    /** Imported .cube applied before [params]; null for parametric looks. */
    val cubeFile: String? = null,
    /** For [LookCategory.BRAND]: the camera maker whose rendering this look is modelled on. */
    val brand: String? = null,
    /** Strength a look starts at when chosen (and returns to on Reset). */
    val defaultIntensity: Float = 1f,
    /** Attribution shown with the look, for licensed third-party LUTs. */
    val credit: String? = null,
) {
    val isNeutral get() = params.isIdentity && cubeFile == null
}

/** Pure colour maths shared by LUT generation and unit tests. */
object LookMath {
    fun srgbToLinear(c: Float): Float = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    fun linearToSrgb(c: Float): Float {
        val x = max(0f, c)
        return if (x <= 0.0031308f) x * 12.92f else 1.055f * x.pow(1f / 2.4f) - 0.055f
    }

    private fun hueRgb(h: Float): FloatArray {
        val hh = ((h % 360f) + 360f) % 360f / 60f
        val x = 1f - abs(hh % 2f - 1f)
        return when (hh.toInt()) {
            0 -> floatArrayOf(1f, x, 0f); 1 -> floatArrayOf(x, 1f, 0f); 2 -> floatArrayOf(0f, 1f, x)
            3 -> floatArrayOf(0f, x, 1f); 4 -> floatArrayOf(x, 0f, 1f); else -> floatArrayOf(1f, 0f, x)
        }
    }

    /** Weight 0..1 for how "skin-like" a colour is: orange hues 5°–50° with moderate saturation. */
    fun skinWeight(r: Float, g: Float, b: Float): Float {
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        if (mx <= 0.05f || mx - mn < 0.04f || r < g || g < b) return 0f
        val h = 60f * ((g - b) / (mx - mn))          // red is max here: hue in 0..60
        val sat = (mx - mn) / mx
        val hueW = when { h < 5f -> h / 5f; h <= 40f -> 1f; h < 55f -> (55f - h) / 15f; else -> 0f }
        val satW = when { sat < 0.12f -> sat / 0.12f; sat <= 0.6f -> 1f; sat < 0.8f -> (0.8f - sat) / 0.2f; else -> 0f }
        return hueW * satW
    }

    /** Maps one display-encoded RGB triple (0..1) through the look. Writes into [out]. */
    fun apply(p: LookParams, r0: Float, g0: Float, b0: Float, out: FloatArray) {
        // 1. linear-light operations: exposure and white balance.
        // Exposure is a white-preserving mid-tone curve f(x) = a·x / (1 − x + a·x): it moves mid-tones by `a` but
        // keeps 0 and 1 fixed, so a look can never clip highlights nor grey out the white point.
        val a = 2f.pow(p.exposure)
        fun ex(x: Float) = if (a == 1f) x else a * x / (1f - x + a * x)
        var r = ex(srgbToLinear(r0)) * (1f + 0.18f * p.warmth) * (1f + 0.06f * p.tint)
        var g = ex(srgbToLinear(g0)) * (1f - 0.10f * p.tint)
        var b = ex(srgbToLinear(b0)) * (1f - 0.18f * p.warmth) * (1f + 0.06f * p.tint)
        // 2. highlight shoulder (Reinhard-style, normalised so 1.0 stays 1.0)
        if (p.highlightRoll > 0f) {
            val k = p.highlightRoll * 1.5f
            fun sh(v: Float) = v * (1f + k) / (1f + k * v)
            r = sh(r); g = sh(g); b = sh(b)
        }
        r = linearToSrgb(r); g = linearToSrgb(g); b = linearToSrgb(b)
        // 3. contrast around mid-grey in display space
        if (p.contrast != 0f) {
            fun con(v: Float): Float {
                val x = v.coerceIn(0f, 1f)
                return if (p.contrast > 0f) {
                    val s = x * x * (3f - 2f * x) // smoothstep S-curve
                    x + (s - x) * p.contrast * 1.2f
                } else {
                    x + (0.5f + (x - 0.5f) * 0.55f - x) * (-p.contrast)
                }
            }
            r = con(r); g = con(g); b = con(b)
        }
        // 3b. shadows / highlights: luminance-ratio scaling so hue is kept; 0 and 1 stay fixed.
        if (p.shadows != 0f || p.highlights != 0f) {
            val l0 = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
            if (l0 > 0.0005f) {
                val l1 = (l0 + p.shadows * 0.6f * l0 * (1f - l0) * (1f - l0) + p.highlights * 0.6f * l0 * l0 * (1f - l0)).coerceIn(0f, 1f)
                val k = l1 / l0
                r *= k; g *= k; b *= k
            }
        }
        // 4. saturation + vibrance
        var l = 0.2126f * r + 0.7152f * g + 0.0722f * b
        if (p.saturation != 0f || p.vibrance != 0f) {
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
            val curSat = if (mx <= 0f) 0f else (mx - mn) / mx
            val s = 1f + p.saturation + p.vibrance * (1f - curSat)
            r = l + (r - l) * s; g = l + (g - l) * s; b = l + (b - l) * s
        }
        // 4b. per-hue mixer. Values are interpolated between neighbouring band centres; strength fades out
        // towards neutral greys so whites, blacks and greys are never tinted by a band.
        if (!p.bands.isIdentity) {
            val hsv = rgbToHsv(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
            val w = smooth(0.03f, 0.18f, hsv[1])
            if (w > 0f) {
                val (dh, ds, dl) = bandValues(p.bands, hsv[0])
                val lBefore = 0.2126f * r + 0.7152f * g + 0.0722f * b
                hsv[0] = (hsv[0] + dh * w + 360f) % 360f
                hsv[1] = (hsv[1] * (1f + ds * w)).coerceIn(0f, 1f)
                val c = hsvToRgb(hsv[0], hsv[1], hsv[2])
                // Hold luminance through the hue/saturation move, then apply the band's own luminance change.
                val lAfter = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]
                val k = (if (lAfter > 0.0005f) lBefore / lAfter else 1f) * (1f + dl * 0.45f * w * hsv[1].coerceAtLeast(0.35f))
                r = c[0] * k; g = c[1] * k; b = c[2] * k
            }
            l = 0.2126f * r + 0.7152f * g + 0.0722f * b
        }
        // 4c. monochrome channel mix (before toning, so toned black-and-white looks keep their tint)
        if (p.mono > 0f) {
            val sum = (p.monoRed + p.monoGreen + p.monoBlue).takeIf { it > 0f } ?: 1f
            val m = (r * p.monoRed + g * p.monoGreen + b * p.monoBlue) / sum
            r += (m - r) * p.mono; g += (m - g) * p.mono; b += (m - b) * p.mono
            l = 0.2126f * r + 0.7152f * g + 0.0722f * b
        }
        // 5. split toning
        if (p.shadowAmt > 0f || p.highlightAmt > 0f) {
            // Bell-shaped weights: tones are tinted, but pure black and paper white stay neutral, as in a
            // careful colourist's grade.
            val lc = l.coerceIn(0f, 1f)
            val ws = smooth(0f, 0.06f, lc) * (1f - smooth(0.05f, 0.55f, lc)) * p.shadowAmt * 0.3f
            val wh = smooth(0.3f, 0.75f, lc) * (1f - smooth(0.86f, 1f, lc) * 0.85f) * p.highlightAmt * 0.3f
            val sc = hueRgb(p.shadowHue); val hc = hueRgb(p.highlightHue)
            r += (sc[0] - 0.5f) * ws + (hc[0] - 0.5f) * wh
            g += (sc[1] - 0.5f) * ws + (hc[1] - 0.5f) * wh
            b += (sc[2] - 0.5f) * ws + (hc[2] - 0.5f) * wh
        }
        // 7. fade: lift blacks, pull whites slightly
        if (p.fade > 0f) {
            val lift = 0.14f * p.fade; val top = 1f - 0.05f * p.fade
            r = lift + r * (top - lift); g = lift + g * (top - lift); b = lift + b * (top - lift)
        }
        // White preservation: near-neutral bright input (walls, paper, clouds) keeps neutral output chroma.
        run {
            val mx0 = max(r0, max(g0, b0)); val mn0 = min(r0, min(g0, b0))
            val sat0 = if (mx0 > 0f) (mx0 - mn0) / mx0 else 0f
            val w = smooth(0.6f, 0.9f, 0.2126f * r0 + 0.7152f * g0 + 0.0722f * b0) * (1f - smooth(0.05f, 0.22f, sat0)) * 0.8f
            if (w > 0f && p.mono < 1f) {
                val lo = 0.2126f * r + 0.7152f * g + 0.0722f * b
                r += (lo - r) * w; g += (lo - g) * w; b += (lo - b) * w
            }
        }
        if (p.skinProtect > 0f) {
            // Skin keeps its natural hue and saturation; only the tonal part of the grade (brightness) applies.
            val w = skinWeight(r0, g0, b0) * p.skinProtect
            if (w > 0f) {
                val lg = 0.2126f * r + 0.7152f * g + 0.0722f * b
                val l0 = 0.2126f * r0 + 0.7152f * g0 + 0.0722f * b0
                val k = if (l0 > 0.001f) lg / l0 else 1f
                r += (r0 * k - r) * w; g += (g0 * k - g) * w; b += (b0 * k - b) * w
            }
        }
        // Skin undertone: rotate hue / adjust saturation and brightness only where the input colour is skin-like.
        if (p.skinHue != 0f || p.skinSat != 0f || p.skinBright != 0f) {
            val w = skinWeight(r0, g0, b0)
            if (w > 0f) {
                val hsv = rgbToHsv(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
                hsv[0] = (hsv[0] + p.skinHue * w + 360f) % 360f
                hsv[1] = (hsv[1] * (1f + p.skinSat * 0.6f * w)).coerceIn(0f, 1f)
                hsv[2] = (hsv[2] * (1f + p.skinBright * 0.25f * w)).coerceIn(0f, 1f)
                val c = hsvToRgb(hsv[0], hsv[1], hsv[2]); r = c[0]; g = c[1]; b = c[2]
            }
        }
        out[0] = r.coerceIn(0f, 1f); out[1] = g.coerceIn(0f, 1f); out[2] = b.coerceIn(0f, 1f)
        l = 0f
    }

    /** Hue-interpolated (hue shift, saturation, luminance) of the mixer at hue [h] degrees. */
    fun bandValues(bands: HueBands, h: Float): Triple<Float, Float, Float> {
        val all = Band.entries
        var i = all.size - 1
        for (k in all.indices) if (h >= all[k].centre) i = k
        val j = (i + 1) % all.size
        val c0 = all[i].centre; val c1 = if (j == 0) 360f else all[j].centre
        val t = ((h - c0) / (c1 - c0)).coerceIn(0f, 1f)
        fun lerp(v: List<Float>) = v[i] + (v[j] - v[i]) * t
        return Triple(lerp(bands.hue), lerp(bands.sat), lerp(bands.lum))
    }

    fun rgbToHsv(r: Float, g: Float, b: Float): FloatArray {
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
        val h = when {
            d <= 1e-6f -> 0f
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        return floatArrayOf((h + 360f) % 360f, if (mx <= 0f) 0f else d / mx, mx)
    }

    fun hsvToRgb(h: Float, s: Float, v: Float): FloatArray {
        val c = v * s; val hh = h / 60f; val x = c * (1f - abs(hh % 2f - 1f)); val m = v - c
        val (r, g, b) = when (hh.toInt()) {
            0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        return floatArrayOf(r + m, g + m, b + m)
    }

    fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Deterministic per-pixel noise in [-0.5, 0.5], identical to the GLSL hash in the shader. */
    fun hash(x: Float, y: Float, seed: Float): Float {
        val v = kotlin.math.sin((x + seed) * 12.9898f + (y + seed) * 78.233f) * 43758.5453f
        return (v - kotlin.math.floor(v)) - 0.5f
    }

    fun vignetteFactor(u: Float, v: Float, amount: Float, aspect: Float): Float {
        val dx = (u - 0.5f) * aspect; val dy = v - 0.5f
        val d = kotlin.math.sqrt(dx * dx + dy * dy) / kotlin.math.sqrt(0.25f * aspect * aspect + 0.25f)
        val t = ((d - 0.45f) / 0.55f).coerceIn(0f, 1f)
        return 1f - amount * 0.65f * t * t * (3f - 2f * t)
    }
}
