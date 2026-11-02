package com.daya.velum.camera

import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector

/** Values the camera actually used, read from capture results (not what we asked for). */
data class LiveMeta(
    val iso: Int? = null,
    val exposureNs: Long? = null,
    val frameDurationNs: Long? = null,
    val focusDiopters: Float? = null,
    val aperture: Float? = null,
    val aeState: Int? = null,
    val afState: Int? = null,
    val awbState: Int? = null,
    val activePhysicalId: String? = null,
    /** Physical sensors that produced per-frame results (stream sources), when the HAL reports them. */
    val physicalSources: List<String> = emptyList(),
    val zoomRatio: Float? = null,
    val ois: Boolean? = null,
    val videoStab: Int? = null,
    val gains: RggbChannelVector? = null,
    val transform: ColorSpaceTransform? = null,
    val timestampMs: Long = 0,
    /** Detected faces in sensor active-array pixels. */
    val faces: List<android.graphics.Rect> = emptyList(),
) {
    val fps get() = frameDurationNs?.takeIf { it > 0 }?.let { 1e9 / it }
}

object ExposureFormat {
    fun shutter(ns: Long): String {
        if (ns <= 0) return "—"
        val s = ns / 1e9
        return if (s >= 0.3) "%.1fs".format(s) else "1/${(1.0 / s).let { if (it >= 100) Math.round(it / 5) * 5 else Math.round(it) }}"
    }

    /** Photographic shutter stops (1/3 EV) inside [range]. */
    fun shutterStops(range: LongRange): List<Long> {
        val base = listOf(1.0 / 8000, 1.0 / 6400, 1.0 / 5000, 1.0 / 4000, 1.0 / 3200, 1.0 / 2500, 1.0 / 2000, 1.0 / 1600,
            1.0 / 1250, 1.0 / 1000, 1.0 / 800, 1.0 / 640, 1.0 / 500, 1.0 / 400, 1.0 / 320, 1.0 / 250, 1.0 / 200, 1.0 / 160,
            1.0 / 125, 1.0 / 100, 1.0 / 96, 1.0 / 80, 1.0 / 60, 1.0 / 50, 1.0 / 48, 1.0 / 40, 1.0 / 30, 1.0 / 25, 1.0 / 24,
            1.0 / 20, 1.0 / 15, 1.0 / 13, 1.0 / 10, 1.0 / 8, 1.0 / 6, 1.0 / 5, 1.0 / 4, 0.3, 0.4, 0.5)
        return base.map { (it * 1e9).toLong() }.filter { it in range }.distinct().sorted()
    }

    fun isoStops(range: IntRange): List<Int> =
        listOf(12, 16, 20, 25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400)
            .filter { it in range }

    fun diopterToDistance(d: Float): String = if (d <= 0.01f) "∞" else {
        val m = 1f / d
        if (m >= 1f) "%.1fm".format(m) else "${(m * 100).toInt()}cm"
    }
}
