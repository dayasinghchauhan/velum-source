package com.daya.velum

import com.daya.velum.imaging.Adj
import com.daya.velum.imaging.Band
import com.daya.velum.imaging.CubeParseException
import com.daya.velum.imaging.HueBands
import com.daya.velum.imaging.CubeParser
import com.daya.velum.imaging.FrameMath
import com.daya.velum.imaging.LookCatalog
import com.daya.velum.imaging.LookMath
import com.daya.velum.imaging.LookParams
import com.daya.velum.imaging.Lut3D
import org.junit.Assert.*
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

class ImagingTest {
    private fun cube(s: String) = CubeParser.parse(BufferedReader(StringReader(s)))

    private fun identityCube(n: Int) = buildString {
        appendLine("TITLE \"id\""); appendLine("LUT_3D_SIZE $n")
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) appendLine("${r / (n - 1f)} ${g / (n - 1f)} ${b / (n - 1f)}")
    }

    @Test fun srgbRoundTrip() {
        for (i in 0..100) {
            val v = i / 100f
            assertEquals(v, LookMath.linearToSrgb(LookMath.srgbToLinear(v)), 1e-4f)
        }
    }

    @Test fun neutralParamsAreIdentity() {
        val o = FloatArray(3)
        for (v in listOf(0f, 0.18f, 0.5f, 0.9f, 1f)) {
            LookMath.apply(LookParams(), v, v * 0.5f, 1f - v, o)
            assertEquals(v, o[0], 1e-4f); assertEquals(v * 0.5f, o[1], 1e-4f); assertEquals(1f - v, o[2], 1e-4f)
        }
    }

    @Test fun identityLutSamplesExactly() {
        val l = Lut3D.identity(17)
        val o = FloatArray(3)
        l.sample(0.123f, 0.456f, 0.789f, o)
        assertArrayEquals(floatArrayOf(0.123f, 0.456f, 0.789f), o, 1e-5f)
    }

    @Test fun everyBuiltInLookChangesTheImageAndStaysInGamut() {
        val o = FloatArray(3)
        for (look in LookCatalog.all.filter { !it.isNeutral }) {
            val lut = Lut3D.fromLook(look.params)
            var maxDiff = 0f
            for (i in lut.data.indices) {
                val v = lut.data[i]
                assertTrue("${look.id} out of range", v in 0f..1f)
            }
            for (p in listOf(floatArrayOf(0.2f, 0.4f, 0.6f), floatArrayOf(0.8f, 0.5f, 0.3f), floatArrayOf(0.5f, 0.5f, 0.5f), floatArrayOf(0.05f, 0.05f, 0.08f))) {
                lut.sample(p[0], p[1], p[2], o)
                for (c in 0..2) maxDiff = maxOf(maxDiff, kotlin.math.abs(o[c] - p[c]))
            }
            // Grain/vignette-only looks are positional; every other look must visibly change colour.
            if (look.params.copy(grain = 0f, vignette = 0f) != LookParams())
                assertTrue("${look.id} has no identifiable effect (max Δ $maxDiff)", maxDiff > 0.02f)
        }
    }

    @Test fun monoLooksRemoveChroma() {
        val lut = Lut3D.fromLook(LookCatalog.byId("silver_mono")!!.params)
        val o = FloatArray(3)
        lut.sample(0.9f, 0.2f, 0.1f, o)
        assertEquals(o[0], o[1], 0.01f); assertEquals(o[1], o[2], 0.01f)
    }

    @Test fun cubeIdentityParses() {
        val l = cube(identityCube(5))
        assertEquals(5, l.size); assertEquals("id", l.title)
        val o = FloatArray(3); l.sample(0.3f, 0.6f, 0.9f, o)
        assertArrayEquals(floatArrayOf(0.3f, 0.6f, 0.9f), o, 1e-4f)
    }

    @Test fun cubeDomainIsNormalised() {
        val text = "LUT_3D_SIZE 2\nDOMAIN_MIN 0 0 0\nDOMAIN_MAX 2 2 2\n" + "0 0 0\n2 0 0\n0 2 0\n2 2 0\n0 0 2\n2 0 2\n0 2 2\n2 2 2\n"
        val l = cube(text)
        assertEquals(1f, l.data[3], 1e-6f)
    }

    @Test fun cubeRejectsMalformed() {
        val bad = listOf(
            "" to "No LUT_3D_SIZE",
            "LUT_3D_SIZE 2\n0 0 0\n" to "Expected 8",
            "LUT_1D_SIZE 256\n" to "1D",
            "LUT_3D_SIZE 99\n" to "not in",
            "0 0 0\n" to "before LUT_3D_SIZE",
            "LUT_3D_SIZE 2\n0 0 x\n" to "not a number",
            "LUT_3D_SIZE 2\n" + "0 0 0\n".repeat(9) to "More than",
            "LUT_3D_SIZE 2\n" + "0 0 NaN\n".repeat(8) to "non-finite",
            "LUT_3D_SIZE 2\nDOMAIN_MIN 1 1 1\nDOMAIN_MAX 0 0 0\n" + "0 0 0\n".repeat(8) to "DOMAIN_MAX",
        )
        for ((src, msg) in bad) {
            try { cube(src); fail("accepted: $msg") } catch (e: CubeParseException) {
                assertTrue("'${e.message}' should mention '$msg'", e.message!!.contains(msg))
            }
        }
    }

    @Test fun cubeOnTopOfLookAppliesBoth() {
        val inverted = Lut3D.build(3) { r, g, b, o -> o[0] = 1 - r; o[1] = 1 - g; o[2] = 1 - b }
        val l = Lut3D.fromLook(LookParams(), inverted, 9)
        val o = FloatArray(3); l.sample(0.25f, 0.5f, 0.75f, o)
        assertArrayEquals(floatArrayOf(0.75f, 0.5f, 0.25f), o, 1e-3f)
    }

    @Test fun vignetteIsOneAtCentreAndDarkerAtCorners() {
        assertEquals(1f, LookMath.vignetteFactor(0.5f, 0.5f, 1f, 1.5f), 1e-6f)
        assertTrue(LookMath.vignetteFactor(0f, 0f, 1f, 1.5f) < 0.5f)
    }

    @Test fun grainHashIsBoundedAndDeterministic() {
        for (i in 0 until 1000) {
            val h = LookMath.hash(i.toFloat(), (i * 7 % 13).toFloat(), 0.37f)
            assertTrue(h >= -0.5f && h <= 0.5f)
            assertEquals(h, LookMath.hash(i.toFloat(), (i * 7 % 13).toFloat(), 0.37f), 0f)
        }
    }

    @Test fun colourMixerLeavesGreysAlone() {
        val p = LookParams(bands = HueBands.of(Band.GREEN to Adj(h = 20f, s = 0.8f, l = -0.8f), Band.BLUE to Adj(s = -1f), Band.RED to Adj(h = -20f)))
        val o = FloatArray(3)
        for (v in listOf(0f, 0.18f, 0.5f, 0.9f, 1f)) {
            LookMath.apply(p, v, v, v, o)
            assertEquals(v, o[0], 1e-4f); assertEquals(v, o[1], 1e-4f); assertEquals(v, o[2], 1e-4f)
        }
    }

    @Test fun colourMixerMovesOnlyItsBand() {
        val o = FloatArray(3)
        // Green shifted toward yellow (negative hue): red channel rises, blue untouched.
        LookMath.apply(LookParams(bands = HueBands.of(Band.GREEN to Adj(h = -20f))), 0.2f, 0.6f, 0.2f, o)
        assertTrue(o[0] > 0.25f)
        // A pure blue is not affected by a green adjustment.
        LookMath.apply(LookParams(bands = HueBands.of(Band.GREEN to Adj(h = -20f, s = -1f))), 0.1f, 0.2f, 0.8f, o)
        assertEquals(0.1f, o[0], 1e-3f); assertEquals(0.2f, o[1], 1e-3f); assertEquals(0.8f, o[2], 1e-3f)
        // Desaturating red pulls the channels together.
        LookMath.apply(LookParams(bands = HueBands.of(Band.RED to Adj(s = -0.8f))), 0.8f, 0.2f, 0.2f, o)
        assertTrue(o[0] - o[1] < 0.4f)
    }

    @Test fun shadowsAndHighlightsKeepEndPoints() {
        val o = FloatArray(3)
        val p = LookParams(shadows = 1f, highlights = -1f)
        LookMath.apply(p, 0f, 0f, 0f, o); assertEquals(0f, o[1], 1e-4f)
        LookMath.apply(p, 1f, 1f, 1f, o); assertEquals(1f, o[1], 1e-4f)
        LookMath.apply(p, 0.2f, 0.2f, 0.2f, o); assertTrue(o[1] > 0.22f)
        LookMath.apply(p, 0.8f, 0.8f, 0.8f, o); assertTrue(o[1] < 0.79f)
    }

    @Test fun everyBrandLookIsInTheBrandList() {
        val brands = LookCatalog.all.mapNotNull { it.brand }.toSet()
        assertEquals(LookCatalog.BRANDS.toSet(), brands)
        assertEquals(LookCatalog.all.size, LookCatalog.all.map { it.id }.toSet().size)
    }

    @Test fun alignmentFindsRealShiftAndNeverWandersOnFlatFrames() {
        val sw = 64; val sh = 48
        fun tex(ox: Int, oy: Int) = FloatArray(sw * sh) { i -> val x = i % sw + ox; val y = i / sw + oy
            (128 + 60 * kotlin.math.sin(x * 0.45) * kotlin.math.cos(y * 0.31) + 30 * kotlin.math.sin((x + 2 * y) * 0.17)).toFloat() }
        val ref = tex(0, 0)
        // cur sampled at +3,-2 means ref(x,y) == cur(x-3, y+2)
        val (dx, dy, _) = FrameMath.shift(ref, tex(3, -2), sw, sh, 5)
        assertEquals(-3, dx); assertEquals(2, dy)
        val flat = FloatArray(sw * sh) { 250f }
        assertFalse(FrameMath.alignable(flat))
        var hint = 0 to 0
        repeat(20) { val (x, y, _) = FrameMath.shift(flat, flat, sw, sh, 4, hint); hint = x to y }
        assertEquals(0 to 0, hint)
    }
}
