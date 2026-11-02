package com.daya.velum.camera

import androidx.camera.core.MeteringPointFactory
import com.daya.velum.imaging.Reframe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.PI

/** What the Movie-mode UI shows: where focus is (view px), whether it is tracking, and the focus plane. */
data class MovieUi(
    val focusView: Pair<Float, Float>? = null,
    val tracking: Boolean = false,
    val lost: Boolean = false,
    val focusDepth: Float = 0.6f,
    val zooming: Boolean = false,
    /** True while focus is chosen automatically (person, else the nearer subject in the centre). */
    val autoFocus: Boolean = true,
    /** What automatic focus is on: "person" or "subject"; null before the first depth map. */
    val autoTarget: String? = null,
)

/**
 * Cinematic controller. Tap = rack focus to that subject (the focus plane glides there, like a focus puller);
 * double-tap = lock and track the subject (focus and smart framing follow it). Coordinates: the camera surface's
 * normalised space from [MeteringPointFactory] (x right, y down), converted to the GL buffer space (v up).
 */
class MovieController(private val engine: CameraEngine, private val scope: CoroutineScope) {
    private val _ui = MutableStateFlow(MovieUi())
    val ui: StateFlow<MovieUi> = _ui
    private var loop: Job? = null
    private var zoomJob: Job? = null
    private var targetDepth = 0.6f
    private var depth = 0.6f
    private var frameX = 0.5f; private var frameY = 0.5f
    /** View→surface affine and its inverse, fitted from three probe points of the metering factory. */
    private var fwd: FloatArray? = null
    private var inv: FloatArray? = null

    fun calibrate(factory: MeteringPointFactory, viewW: Float, viewH: Float) {
        if (viewW <= 0f || viewH <= 0f) return
        val p0 = factory.createPoint(0f, 0f); val p1 = factory.createPoint(viewW, 0f); val p2 = factory.createPoint(0f, viewH)
        // surface = A·view + t, solved from three correspondences.
        val a = floatArrayOf((p1.x - p0.x) / viewW, (p2.x - p0.x) / viewH, p0.x, (p1.y - p0.y) / viewW, (p2.y - p0.y) / viewH, p0.y)
        fwd = a
        val det = a[0] * a[4] - a[1] * a[3]
        if (abs(det) < 1e-9f) return
        val i0 = a[4] / det; val i1 = -a[1] / det; val i3 = -a[3] / det; val i4 = a[0] / det
        inv = floatArrayOf(i0, i1, -(i0 * a[2] + i1 * a[5]), i3, i4, -(i3 * a[2] + i4 * a[5]))
    }

    private fun viewToBuffer(x: Float, y: Float): Pair<Float, Float>? {
        val a = fwd ?: return null
        val sx = a[0] * x + a[1] * y + a[2]; val sy = a[3] * x + a[4] * y + a[5]
        return sx to (1f - sy)
    }

    private fun bufferToView(u: Float, v: Float): Pair<Float, Float>? {
        val i = inv ?: return null
        val sx = u; val sy = 1f - v
        return (i[0] * sx + i[1] * sy + i[2]) to (i[3] * sx + i[4] * sy + i[5])
    }

    fun start() {
        if (loop?.isActive == true) return
        if (com.daya.velum.BuildConfig.DEBUG) scope.launch { delay(3000); engine.ml.debugDumpOnce = true }
        loop = scope.launch {
            while (isActive) {
                tick()
                delay(33)
            }
        }
    }

    fun stop() {
        loop?.cancel(); loop = null
        manual = false
        engine.enableTracking(false)
        _ui.value = MovieUi()
    }

    /** Rack focus to the subject under the tap. With [track], keep following it. */
    fun tap(x: Float, y: Float, track: Boolean) {
        val (u, v) = viewToBuffer(x, y) ?: return
        engine.ml.depthAt(u, v)?.let { targetDepth = it }
        manual = true
        if (track) {
            engine.ml.track(u, v)
            engine.enableTracking(true)
        } else {
            engine.enableTracking(false)
        }
        _ui.value = _ui.value.copy(focusView = x to y, tracking = track, lost = false, autoFocus = false)
    }

    /** Back to automatic focus (person first, else the nearer subject in the centre). */
    fun autoFocus() {
        manual = false
        engine.enableTracking(false)
        _ui.value = _ui.value.copy(focusView = null, tracking = false, lost = false, autoFocus = true)
    }

    private var manual = false

    /**
     * Automatic subject: the median depth of the person mask when a person fills at least 1.5 % of the frame,
     * otherwise the 75th-percentile depth (the nearer part) of the central 40 % of the frame.
     */
    private fun autoTarget(): Pair<Float, String>? {
        val d = engine.ml.depthMap ?: return null
        val n = 256
        val p = engine.ml.personMap
        if (p != null) {
            val vals = ArrayList<Float>()
            for (i in d.indices) if (p[i] > 0.5f) vals += d[i]
            if (vals.size > n * n * 0.015) { vals.sort(); return vals[vals.size / 2] to "person" }
        }
        val c = ArrayList<Float>()
        for (y in (n * 0.3).toInt() until (n * 0.7).toInt()) for (x in (n * 0.3).toInt() until (n * 0.7).toInt()) c += d[y * n + x]
        if (c.isEmpty()) return null
        c.sort()
        return c[(c.size * 0.75).toInt()] to "subject"
    }

    fun clearTracking() {
        engine.enableTracking(false)
        _ui.value = _ui.value.copy(tracking = false, lost = false)
    }

    private fun tick() {
        val ui = _ui.value
        if (ui.tracking) {
            val t = engine.ml.tracked
            if (t != null && t.third > 0f) {
                engine.ml.depthAt(t.first, t.second)?.let { targetDepth = it }
                val view = bufferToView(t.first, t.second)
                // Smart framing: the recorded window glides toward the subject (buffer space; the processor maps it).
                frameX += (t.first - frameX) * 0.08f
                frameY += (t.second - frameY) * 0.08f
                _ui.value = ui.copy(focusView = view ?: ui.focusView, lost = false)
                engine.setReframe(Reframe(frameX, frameY, 0.8f))
            } else if (t != null) _ui.value = ui.copy(lost = true)
        }
        if (!manual && !ui.tracking) autoTarget()?.let { (dp, what) ->
            targetDepth = dp
            if (ui.autoTarget != what) _ui.value = _ui.value.copy(autoTarget = what)
        }
        // Critically damped approach: a smooth focus pull, never a jump.
        depth += (targetDepth - depth) * 0.12f
        engine.setFocusDepth(depth)
        if (abs(depth - ui.focusDepth) > 0.002f) _ui.value = _ui.value.copy(focusDepth = depth)
    }

    /** Smooth programmed zoom between two ratios with ease-in-out, independent of the finger. */
    fun autoZoom(from: Float, to: Float, seconds: Int, setZoom: (Float) -> Unit) {
        zoomJob?.cancel()
        zoomJob = scope.launch {
            _ui.value = _ui.value.copy(zooming = true)
            val steps = seconds * 30
            for (i in 0..steps) {
                val t = i / steps.toFloat()
                val e = (0.5 - 0.5 * cos(PI * t)).toFloat()
                setZoom(from + (to - from) * e)
                delay(33)
            }
            _ui.value = _ui.value.copy(zooming = false)
        }
    }

    fun cancelZoom() { zoomJob?.cancel(); _ui.value = _ui.value.copy(zooming = false) }
}
