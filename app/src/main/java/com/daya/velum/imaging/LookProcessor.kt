package com.daya.velum.imaging

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor
import kotlin.math.max
import kotlin.math.min

private const val TAG = "Velum/GL"

/** Monitoring aids drawn into the preview output only. */
data class MonitorFlags(
    val zebra: Boolean = false,
    val zebraThreshold: Float = 0.95f,
    val peaking: Boolean = false,
    val peakingThreshold: Float = 0.18f,
    val falseColor: Boolean = false,
    val grey: Boolean = false,
) {
    val any get() = zebra || peaking || falseColor || grey
}

/** Real-time creative effects rendered on the GPU into preview and (when baked) video. */
enum class LiveEffect { NONE, SLOW_SHUTTER, TIME_FREEZE, MIRROR_LR, MIRROR_TB }

/** Depth-of-field state for Movie mode. [focusDepth] is in model depth units 0..1 (1 = nearest). */
data class DofState(val enabled: Boolean = false, val focusDepth: Float = 0.6f, val strength: Float = 0.7f, val protectPeople: Boolean = true)

/** Reframing (smart composition / digital pan): centre in BUFFER space 0..1 and scale ≤ 1. Video output only. */
data class Reframe(val cx: Float = 0.5f, val cy: Float = 0.5f, val scale: Float = 1f) {
    val isIdentity get() = scale >= 0.999f
}

/** Everything the shader needs for one frame; replaced atomically from any thread. */
data class RenderState(
    val lookOnPreview: Boolean = false,
    val lookOnVideo: Boolean = false,
    val intensity: Float = 1f,
    val grain: Float = 0f,
    val vignette: Float = 0f,
    val sharpness: Float = 0f,
    val monitor: MonitorFlags = MonitorFlags(),
    /** Hold-to-compare: preview shows the ungraded image while true. */
    val compareOriginal: Boolean = false,
    /** True when the saved output (photo or video) carries the look, so overlays/meters judge the graded signal. */
    val recordedGraded: Boolean = false,
    val effect: LiveEffect = LiveEffect.NONE,
    /** Slow shutter: fraction of each new frame mixed into the trail (smaller = longer trails). */
    val trail: Float = 0.12f,
    val dof: DofState = DofState(),
    val reframe: Reframe = Reframe(),
    /** Video time-lapse: keep 1 of every [timelapseEvery] frames (1 = normal video). */
    val timelapseEvery: Int = 1,
)

/** Luma statistics of the recorded signal, ~8 Hz. [histogram] has 64 bins of display-encoded luma. */
class FrameStats(val histogram: IntArray, val meanLinear: Float, val clippedFraction: Float, val blackFraction: Float, val timestampMs: Long)

/** A 256×256 RGBA snapshot of the current frame (buffer orientation, GL row order) handed to ML models. */
class MlFrame(val rgba: ByteBuffer, val size: Int, val timestampNs: Long)

/**
 * GLES 3 [SurfaceProcessor] used for every SDR session. The camera image is first resolved into a mipmapped 2D
 * texture; temporal effects (slow shutter, time freeze) and depth of field operate on that, then each CameraX output
 * is drawn with its own transform. Preview outputs additionally get monitoring overlays and hold-to-compare; video
 * outputs never do, so overlays can never be burned into a recording. All GL runs on one dedicated thread.
 */
class LookProcessor : SurfaceProcessor {
    private val thread = HandlerThread("VelumGL").apply { start() }
    private val handler = Handler(thread.looper)
    val executor = Executor { r -> if (!handler.post(r)) Log.w(TAG, "GL thread gone; dropped task") }

    @Volatile var state = RenderState()
    @Volatile private var pendingLut: Lut3D? = Lut3D.identity()
    @Volatile var statsListener: ((FrameStats) -> Unit)? = null
    @Volatile var errorListener: ((Throwable) -> Unit)? = null
    /** Receives ML frames at most every [mlIntervalMs]; null disables the readback entirely. */
    @Volatile var mlListener: ((MlFrame) -> Unit)? = null
    @Volatile var mlIntervalMs = 66L
    @Volatile private var pendingDepth: ByteBuffer? = null
    @Volatile private var pendingMask: ByteBuffer? = null
    @Volatile private var freezeRequested = false

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var progResolve = 0
    private var progMain = 0
    private var progTrail = 0
    private var oesTex = 0
    private var lutTex = 0
    private var frameTex = 0; private var frameFbo = 0
    private val trailTex = IntArray(2); private val trailFbo = IntArray(2); private var trailIdx = 0; private var trailValid = false
    private var freezeTex = 0; private var freezeFbo = 0; private var freezeValid = false
    private var depthTex = 0; private var maskTex = 0; private var depthValid = false; private var maskValid = false
    private var meterFbo = 0; private var meterTex = 0
    private var mlFbo = 0; private var mlTex = 0
    private var allocated = Size(0, 0)
    private var input: SurfaceTexture? = null
    private var inputSize = Size(1, 1)
    private val outputs = LinkedHashMap<SurfaceOutput, Out>()
    private val stMatrix = FloatArray(16)
    private val stInverse = FloatArray(16)
    private val outMatrix = FloatArray(16)
    private val sampleMatrix = FloatArray(16)
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private var frameCount = 0L
    private var lastStatsMs = 0L
    private var lastMlMs = 0L
    private var released = false
    private val meterW = 64
    private val meterH = 36
    private val mlSize = 256
    private val meterBuf = ByteBuffer.allocateDirect(meterW * meterH * 4).order(ByteOrder.nativeOrder())
    private var lutSize = Lut3D.DEFAULT_SIZE
    private var tlSource = 0L
    private var tlWritten = 0L
    private var tlBaseNs = -1L

    private class Out(val surface: Surface, val egl: EGLSurface, val size: Size, val isPreview: Boolean)

    private val quad: FloatBuffer = floatBuf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val quadTex: FloatBuffer = floatBuf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

    init { handler.post { runCatching { initGl() }.onFailure { fail(it) } } }

    fun setLut(lut: Lut3D) { pendingLut = lut }

    /** Depth map from the ML thread: 256² bytes, 255 = nearest, GL row order. */
    fun submitDepth(bytes: ByteBuffer) { pendingDepth = bytes }
    fun submitMask(bytes: ByteBuffer) { pendingMask = bytes }
    fun clearMl() { handler.post { depthValid = false; maskValid = false } }
    /** Captures the current frame as the frozen plate for [LiveEffect.TIME_FREEZE]. */
    fun freezeNow() { freezeRequested = true }
    fun releaseFreeze() { handler.post { freezeValid = false } }
    /** Restarts time-lapse frame numbering; call when a recording starts. */
    fun resetTimelapseClock() { handler.post { tlSource = 0; tlWritten = 0; tlBaseNs = -1 } }

    /** Creates the CameraX effect. [bakeIntoVideo] adds the VIDEO_CAPTURE target; otherwise video is untouched. */
    fun createEffect(bakeIntoVideo: Boolean): CameraEffect {
        val targets = if (bakeIntoVideo) CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE else CameraEffect.PREVIEW
        return object : CameraEffect(
            targets, OUTPUT_OPTION_ONE_FOR_EACH_TARGET, TRANSFORMATION_ARBITRARY, executor, this,
            { t -> fail(t) },
        ) {}
    }

    fun release() {
        handler.post {
            released = true
            outputs.forEach { (o, out) -> destroyOut(out); runCatching { o.close() } }
            outputs.clear()
            input?.release(); input = null
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
                EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
            display = EGL14.EGL_NO_DISPLAY
            thread.quitSafely()
        }
    }

    // ---- SurfaceProcessor (called on [executor]) ----

    override fun onInputSurface(request: SurfaceRequest) {
        if (released || progMain == 0) { request.willNotProvideSurface(); return }
        makeCurrent(pbuffer)
        val st = SurfaceTexture(oesTex)
        st.setDefaultBufferSize(request.resolution.width, request.resolution.height)
        val surface = Surface(st)
        input?.setOnFrameAvailableListener(null)
        input = st
        inputSize = request.resolution
        trailValid = false; freezeValid = false
        st.setOnFrameAvailableListener({ onFrame(it) }, handler)
        request.provideSurface(surface, executor) {
            st.setOnFrameAvailableListener(null)
            if (input === st) input = null
            st.release(); surface.release()
        }
    }

    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        if (released) { surfaceOutput.close(); return }
        val surface = surfaceOutput.getSurface(executor) { ev ->
            if (ev.eventCode == SurfaceOutput.Event.EVENT_REQUEST_CLOSE) {
                outputs.remove(surfaceOutput)?.let(::destroyOut)
                surfaceOutput.close()
            }
        }
        val egl = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (egl == null || egl == EGL14.EGL_NO_SURFACE) {
            fail(IllegalStateException("eglCreateWindowSurface failed: 0x${Integer.toHexString(EGL14.eglGetError())}"))
            surfaceOutput.close(); return
        }
        val isPreview = surfaceOutput.targets and CameraEffect.PREVIEW != 0
        outputs[surfaceOutput] = Out(surface, egl, surfaceOutput.size, isPreview)
        Log.i(TAG, "output ${surfaceOutput.size} targets=${surfaceOutput.targets} preview=$isPreview")
    }

    // ---- rendering ----

    private fun onFrame(st: SurfaceTexture) {
        if (released || st !== input) return
        try {
            makeCurrent(pbuffer)
            st.updateTexImage()
            st.getTransformMatrix(stMatrix)
            Matrix.invertM(stInverse, 0, stMatrix, 0)
            ensureTargets(inputSize)
            pendingLut?.let { uploadLut(it); pendingLut = null }
            pendingDepth?.let { uploadR8(depthTex, it); depthValid = true; pendingDepth = null }
            pendingMask?.let { uploadR8(maskTex, it); maskValid = true; pendingMask = null }
            val s = state
            val tSec = (SystemClock.elapsedRealtime() % 100_000L) / 1000f

            // 1. Resolve the external camera image into a mipmapped 2D texture in buffer space.
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, frameFbo)
            GLES30.glViewport(0, 0, inputSize.width, inputSize.height)
            GLES30.glUseProgram(progResolve)
            bindTex(0, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex); u1i(progResolve, "sTex", 0)
            GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(progResolve, "uTexMatrix"), 1, false, stMatrix, 0)
            drawQuad(progResolve, identity)
            // Mip levels exist only when depth of field needs them; otherwise sample the base level (a mipmap filter
            // over ungenerated levels would read black whenever the image is minified).
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frameTex)
            if (s.dof.enabled) {
                GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
            } else {
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            }

            // 2. Temporal effects.
            var source = frameTex
            if (s.effect == LiveEffect.SLOW_SHUTTER) {
                val dst = 1 - trailIdx
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, trailFbo[dst])
                GLES30.glViewport(0, 0, inputSize.width, inputSize.height)
                GLES30.glUseProgram(progTrail)
                bindTex(0, GLES30.GL_TEXTURE_2D, frameTex); u1i(progTrail, "uCur", 0)
                bindTex(1, GLES30.GL_TEXTURE_2D, trailTex[trailIdx]); u1i(progTrail, "uPrev", 1)
                u1f(progTrail, "uMix", if (trailValid) s.trail else 1f)
                drawQuad(progTrail, identity)
                trailIdx = dst; trailValid = true
                source = trailTex[trailIdx]
            } else trailValid = false
            if (freezeRequested) { copyTex(frameTex, freezeFbo); freezeValid = true; freezeRequested = false }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

            // 3. Outputs.
            stepHorizon()
            val dead = mutableListOf<SurfaceOutput>()
            for ((so, out) in outputs) {
                if (!out.isPreview && s.timelapseEvery > 1) {
                    val keep = tlSource % s.timelapseEvery == 0L
                    tlSource++
                    if (!keep) continue
                }
                if (!EGL14.eglMakeCurrent(display, out.egl, out.egl, context)) { dead += so; continue }
                so.updateTransformMatrix(outMatrix, stMatrix)
                // The intermediate already has the SurfaceTexture transform applied: sample with stᐨ¹ · output.
                Matrix.multiplyMM(sampleMatrix, 0, stInverse, 0, outMatrix, 0)
                val lookOn = if (out.isPreview) s.lookOnPreview && !s.compareOriginal else s.lookOnVideo
                val reframe = if (out.isPreview || s.reframe.isIdentity) Reframe() else outputReframe(s.reframe)
                drawMain(out.size, sampleMatrix, source, lookOn, s, if (out.isPreview) s.monitor else null, s.recordedGraded, tSec,
                    reframe, out.isPreview && s.compareOriginal)
                val pts = if (!out.isPreview && s.timelapseEvery > 1) {
                    // Kept frames are spaced at 30 fps so playback runs N× faster than real time.
                    if (tlBaseNs < 0) tlBaseNs = st.timestamp
                    tlBaseNs + (tlWritten++) * 33_333_333L
                } else st.timestamp
                EGLExt.eglPresentationTimeANDROID(display, out.egl, pts)
                if (!EGL14.eglSwapBuffers(display, out.egl)) dead += so
            }
            dead.forEach { so -> outputs.remove(so)?.let(::destroyOut) }
            frameCount++
            if (frameCount % 300L == 1L) Log.i(TAG, "state in=$inputSize outs=${outputs.size} dof=${s.dof} depthValid=$depthValid maskValid=$maskValid fx=${s.effect} lapse=${s.timelapseEvery}")
            if (frameCount % 3L == 0L) meter(s)
            ml(st.timestamp)
        } catch (t: Throwable) {
            Log.e(TAG, "frame failed", t)
        }
    }

    /** Horizon lock: camera roll away from level, degrees (sign: positive = rotate the picture counter-clockwise). */
    @Volatile var horizonDeg = 0f
    @Volatile var horizonOn = false
    private var horizonSmooth = 0f
    private var horizonPeak = 0f

    private fun stepHorizon() {
        if (!horizonOn) { horizonSmooth = 0f; horizonPeak = 0f; return }
        horizonSmooth += (horizonDeg - horizonSmooth) * 0.2f
        horizonPeak = max(kotlin.math.abs(horizonSmooth), horizonPeak * 0.995f)
    }

    private val invSample = FloatArray(16)
    private val vecIn = FloatArray(4)
    private val vecOut = FloatArray(4)

    /** Converts a buffer-space reframe centre into this output's texture space, clamped so the window stays inside. */
    private fun outputReframe(r: Reframe): Reframe {
        if (!Matrix.invertM(invSample, 0, sampleMatrix, 0)) return Reframe()
        vecIn[0] = r.cx; vecIn[1] = r.cy; vecIn[2] = 0f; vecIn[3] = 1f
        Matrix.multiplyMV(vecOut, 0, invSample, 0, vecIn, 0)
        val h = r.scale / 2f
        return Reframe(vecOut[0].coerceIn(h, 1f - h), vecOut[1].coerceIn(h, 1f - h), r.scale)
    }

    private fun drawMain(size: Size, matrix: FloatArray, source: Int, lookOn: Boolean, s: RenderState, mon: MonitorFlags?,
                         signalGraded: Boolean, t: Float, reframe: Reframe, bypassEffects: Boolean) {
        val p = progMain
        GLES30.glViewport(0, 0, size.width, size.height)
        GLES30.glUseProgram(p)
        bindTex(0, GLES30.GL_TEXTURE_2D, source); u1i(p, "uSrc", 0)
        bindTex(1, GLES30.GL_TEXTURE_3D, lutTex); u1i(p, "uLut", 1)
        bindTex(2, GLES30.GL_TEXTURE_2D, depthTex); u1i(p, "uDepth", 2)
        bindTex(3, GLES30.GL_TEXTURE_2D, maskTex); u1i(p, "uMask", 3)
        bindTex(4, GLES30.GL_TEXTURE_2D, freezeTex); u1i(p, "uFreeze", 4)
        bindTex(5, GLES30.GL_TEXTURE_2D, frameTex); u1i(p, "uLive", 5)
        u1i(p, "uLookOn", if (lookOn) 1 else 0)
        u1f(p, "uLutSize", lutSize.toFloat())
        u1f(p, "uIntensity", s.intensity)
        u1f(p, "uGrain", if (lookOn) s.grain else 0f)
        u1f(p, "uVignette", if (lookOn) s.vignette else 0f)
        u1f(p, "uSharp", if (lookOn) s.sharpness * s.intensity else 0f)
        u1f(p, "uGrainScale", 1080f / max(1, min(size.width, size.height)))
        u1f(p, "uAspect", size.width.toFloat() / max(1, size.height))
        u1f(p, "uTime", t)
        var mask = 0
        if (mon != null) {
            if (mon.zebra) mask = mask or 1
            if (mon.peaking) mask = mask or 2
            if (mon.falseColor) mask = mask or 4
            if (mon.grey) mask = mask or 8
            u1f(p, "uZebra", mon.zebraThreshold); u1f(p, "uPeak", mon.peakingThreshold)
        }
        u1i(p, "uMonitor", mask)
        u1i(p, "uSignalGraded", if (signalGraded) 1 else 0)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(p, "uTexel"), 1f / inputSize.width, 1f / inputSize.height)
        val fx = if (bypassEffects) LiveEffect.NONE else s.effect
        u1i(p, "uMirror", when (fx) { LiveEffect.MIRROR_LR -> 1; LiveEffect.MIRROR_TB -> 2; else -> 0 })
        u1i(p, "uFreezeOn", if (fx == LiveEffect.TIME_FREEZE && freezeValid && maskValid) 1 else 0)
        val dofOn = !bypassEffects && s.dof.enabled && depthValid
        u1i(p, "uDofOn", if (dofOn) 1 else 0)
        u1f(p, "uFocus", s.dof.focusDepth)
        u1f(p, "uDofStrength", s.dof.strength)
        u1i(p, "uProtectPeople", if (s.dof.protectPeople && maskValid) 1 else 0)
        // Largest blur ≈ 2.5 % of the frame's long side, independent of the stream resolution.
        val maxLod = Math.log(max(inputSize.width, inputSize.height) * 0.025) / Math.log(2.0)
        u1f(p, "uMaxLod", max(2f, maxLod.toFloat()))
        // Largest blur radius in source texels: 2.2 % of the long side (about 85 px at 4K).
        u1f(p, "uMaxRadius", max(inputSize.width, inputSize.height) * 0.022f)
        GLES30.glUniform4f(GLES30.glGetUniformLocation(p, "uCrop"), reframe.cx - reframe.scale / 2f, reframe.cy - reframe.scale / 2f, reframe.scale, reframe.scale)
        val hd = if (horizonOn) horizonSmooth else 0f
        if (hd != 0f) {
            val th = Math.toRadians(hd.toDouble())
            val aspect = size.width.toFloat() / size.height
            // Zoom just enough that the rotated frame has no empty corners, sized for the recent peak angle so the
            // framing does not pulse as the angle changes.
            val pk = Math.toRadians(horizonPeak.toDouble())
            val fill = (kotlin.math.cos(pk) + kotlin.math.sin(pk) * max(aspect, 1f / aspect)).toFloat()
            GLES30.glUniform3f(GLES30.glGetUniformLocation(p, "uRot"), (kotlin.math.cos(th) / fill - 1.0).toFloat(), (kotlin.math.sin(th) / fill).toFloat(), aspect)
        } else GLES30.glUniform3f(GLES30.glGetUniformLocation(p, "uRot"), 0f, 0f, 0f)
        drawQuad(p, matrix)
    }

    private fun drawQuad(p: Int, matrix: FloatArray) {
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(p, "uMatrix"), 1, false, matrix, 0)
        val aPos = GLES30.glGetAttribLocation(p, "aPos")
        val aTex = GLES30.glGetAttribLocation(p, "aTex")
        GLES30.glEnableVertexAttribArray(aPos); GLES30.glVertexAttribPointer(aPos, 2, GLES30.GL_FLOAT, false, 0, quad)
        GLES30.glEnableVertexAttribArray(aTex); GLES30.glVertexAttribPointer(aTex, 2, GLES30.GL_FLOAT, false, 0, quadTex)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun blit(src: Int, dstFbo: Int, w: Int, h: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, dstFbo)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glUseProgram(progTrail)
        bindTex(0, GLES30.GL_TEXTURE_2D, src); u1i(progTrail, "uCur", 0)
        bindTex(1, GLES30.GL_TEXTURE_2D, src); u1i(progTrail, "uPrev", 1)
        u1f(progTrail, "uMix", 1f)
        drawQuad(progTrail, identity)
    }

    private fun copyTex(src: Int, dstFbo: Int) = blit(src, dstFbo, inputSize.width, inputSize.height)

    private fun meter(s: RenderState) {
        val l = statsListener ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastStatsMs < 110) return
        lastStatsMs = now
        makeCurrent(pbuffer)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, meterFbo)
        // Meter the recorded signal: graded only when the look is baked. Full frame, no effects.
        drawMain(Size(meterW, meterH), identity, frameTex, s.recordedGraded, s.copy(grain = 0f, vignette = 0f, sharpness = 0f),
            null, false, 0f, Reframe(), true)
        meterBuf.clear()
        GLES30.glReadPixels(0, 0, meterW, meterH, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, meterBuf)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        val hist = IntArray(64)
        var linSum = 0f; var clipped = 0; var black = 0
        val n = meterW * meterH
        for (i in 0 until n) {
            val r = (meterBuf.get(i * 4).toInt() and 0xFF) / 255f
            val g = (meterBuf.get(i * 4 + 1).toInt() and 0xFF) / 255f
            val b = (meterBuf.get(i * 4 + 2).toInt() and 0xFF) / 255f
            val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
            hist[(y * 63.999f).toInt().coerceIn(0, 63)]++
            linSum += LookMath.srgbToLinear(y)
            if (max(r, max(g, b)) >= 0.985f) clipped++
            if (y <= 0.02f) black++
        }
        l(FrameStats(hist, linSum / n, clipped.toFloat() / n, black.toFloat() / n, now))
    }

    /** Downsamples the live frame to 256² for the ML thread. Bounded: at most one frame per interval. */
    private fun ml(tsNs: Long) {
        val l = mlListener ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastMlMs < mlIntervalMs) return
        lastMlMs = now
        makeCurrent(pbuffer)
        blit(frameTex, mlFbo, mlSize, mlSize)
        val buf = ByteBuffer.allocateDirect(mlSize * mlSize * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, mlSize, mlSize, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        buf.rewind()
        l(MlFrame(buf, mlSize, tsNs))
    }

    // ---- GL setup ----

    private fun initGl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        check(EGL14.eglInitialize(display, ver, 0, ver, 1)) { "eglInitialize" }
        val cfgAttrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            0x3142 /* EGL_RECORDABLE_ANDROID */, 1,
            EGL14.EGL_NONE,
        )
        val cfgs = arrayOfNulls<EGLConfig>(1); val num = IntArray(1)
        check(EGL14.eglChooseConfig(display, cfgAttrs, 0, cfgs, 0, 1, num, 0) && num[0] > 0) { "eglChooseConfig" }
        config = cfgs[0]
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
        pbuffer = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        makeCurrent(pbuffer)
        progResolve = buildProgram(VERTEX_OES, FRAG_RESOLVE)
        progMain = buildProgram(VERTEX, FRAG_MAIN)
        progTrail = buildProgram(VERTEX, FRAG_TRAIL)

        val t = IntArray(6); GLES30.glGenTextures(6, t, 0)
        oesTex = t[0]; lutTex = t[1]; meterTex = t[2]; depthTex = t[3]; maskTex = t[4]; mlTex = t[5]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        for ((k, v) in listOf(GLES30.GL_TEXTURE_MIN_FILTER to GLES30.GL_LINEAR, GLES30.GL_TEXTURE_MAG_FILTER to GLES30.GL_LINEAR,
                GLES30.GL_TEXTURE_WRAP_S to GLES30.GL_CLAMP_TO_EDGE, GLES30.GL_TEXTURE_WRAP_T to GLES30.GL_CLAMP_TO_EDGE))
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, k, v)
        meterFbo = fboFor(meterTex, meterW, meterH)
        mlFbo = fboFor(mlTex, mlSize, mlSize)
        for (tex in listOf(depthTex, maskTex)) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, mlSize, mlSize, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, null)
            texParams(GLES30.GL_LINEAR)
        }
        pendingLut?.let { uploadLut(it); pendingLut = null }
        Log.i(TAG, "GL ready: ${GLES30.glGetString(GLES30.GL_RENDERER)}")
    }

    /** (Re)allocates the input-sized render targets when the camera resolution changes. */
    private fun ensureTargets(size: Size) {
        if (size == allocated) return
        val del = intArrayOf(frameTex, trailTex[0], trailTex[1], freezeTex).filter { it != 0 }.toIntArray()
        if (del.isNotEmpty()) GLES30.glDeleteTextures(del.size, del, 0)
        val delF = intArrayOf(frameFbo, trailFbo[0], trailFbo[1], freezeFbo).filter { it != 0 }.toIntArray()
        if (delF.isNotEmpty()) GLES30.glDeleteFramebuffers(delF.size, delF, 0)
        val t = IntArray(4); GLES30.glGenTextures(4, t, 0)
        frameTex = t[0]; trailTex[0] = t[1]; trailTex[1] = t[2]; freezeTex = t[3]
        // Mipmapped (immutable storage) so depth of field can blur cheaply with textureLod.
        val levels = (Math.log(max(size.width, size.height).toDouble()) / Math.log(2.0)).toInt() + 1
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frameTex)
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, levels, GLES30.GL_RGBA8, size.width, size.height)
        texParams(GLES30.GL_LINEAR)
        frameFbo = attach(frameTex)
        for (i in 0..1) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, trailTex[i])
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, size.width, size.height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            texParams(GLES30.GL_LINEAR)
            trailFbo[i] = attach(trailTex[i])
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, freezeTex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, size.width, size.height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        texParams(GLES30.GL_LINEAR)
        freezeFbo = attach(freezeTex)
        allocated = size
        trailValid = false; freezeValid = false
    }

    private fun texParams(minFilter: Int) {
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, minFilter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    private fun attach(tex: Int): Int {
        val f = IntArray(1); GLES30.glGenFramebuffers(1, f, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return f[0]
    }

    private fun fboFor(tex: Int, w: Int, h: Int): Int {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        texParams(GLES30.GL_LINEAR)
        return attach(tex)
    }

    private fun uploadR8(tex: Int, bytes: ByteBuffer) {
        bytes.rewind()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, mlSize, mlSize, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, bytes)
    }

    private fun uploadLut(lut: Lut3D) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGBA8, lut.size, lut.size, lut.size, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, ByteBuffer.wrap(lut.toRgba8()))
        for (p in intArrayOf(GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_TEXTURE_WRAP_R))
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, p, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        lutSize = lut.size
    }

    private fun destroyOut(o: Out) {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
            EGL14.eglDestroySurface(display, o.egl)
        }
    }

    private fun makeCurrent(s: EGLSurface) {
        check(EGL14.eglMakeCurrent(display, s, s, context)) { "eglMakeCurrent 0x${Integer.toHexString(EGL14.eglGetError())}" }
    }

    private fun fail(t: Throwable) {
        Log.e(TAG, "effect error", t)
        errorListener?.invoke(t)
    }

    private fun bindTex(unit: Int, target: Int, tex: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(target, tex)
    }
    private fun u1i(p: Int, n: String, v: Int) = GLES30.glUniform1i(GLES30.glGetUniformLocation(p, n), v)
    private fun u1f(p: Int, n: String, v: Float) = GLES30.glUniform1f(GLES30.glGetUniformLocation(p, n), v)

    private fun buildProgram(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
            val ok = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader compile: " + GLES30.glGetShaderInfoLog(s) }
            return s
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        val ok = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "link: " + GLES30.glGetProgramInfoLog(p) }
        return p
    }

    companion object {
        private fun floatBuf(vararg v: Float): FloatBuffer =
            ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(v); position(0) }

        private const val VERTEX_OES = """#version 300 es
in vec4 aPos;
in vec4 aTex;
uniform mat4 uTexMatrix;
uniform mat4 uMatrix;
out vec2 vTex;
void main() { gl_Position = aPos; vTex = (uTexMatrix * aTex).xy; }
"""
        private const val FRAG_RESOLVE = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform samplerExternalOES sTex;
in vec2 vTex;
out vec4 frag;
void main() { frag = vec4(texture(sTex, vTex).rgb, 1.0); }
"""
        private const val VERTEX = """#version 300 es
in vec4 aPos;
in vec4 aTex;
uniform mat4 uMatrix;
uniform vec4 uCrop;
// Horizon lock: (cos/zoom - 1, sin/zoom, output aspect). All zero = no rotation, so programs that never set it are safe.
uniform vec3 uRot;
out vec2 vTex;
out vec2 vUv;
void main() {
    gl_Position = aPos;
    vec2 uv = aTex.xy;
    vec2 ruv = uv;
    if (uRot.x != 0.0 || uRot.y != 0.0) {
        vec2 a = vec2(max(uRot.z, 1e-3), 1.0);
        vec2 q = (uv - 0.5) * a;
        float c = 1.0 + uRot.x;
        q = vec2(c * q.x - uRot.y * q.y, uRot.y * q.x + c * q.y);
        ruv = q / a + 0.5;
    }
    vec2 cropped = (uCrop.z > 0.0) ? uCrop.xy + ruv * uCrop.zw : ruv;
    vTex = (uMatrix * vec4(cropped, 0.0, 1.0)).xy;
    vUv = uv;
}
"""
        private const val FRAG_TRAIL = """#version 300 es
precision mediump float;
uniform sampler2D uCur;
uniform sampler2D uPrev;
uniform float uMix;
in vec2 vTex;
out vec4 frag;
void main() { frag = vec4(mix(texture(uPrev, vTex).rgb, texture(uCur, vTex).rgb, uMix), 1.0); }
"""
        // Grade maths mirrors CpuLookApplier: sharpen → LUT (half-texel remap) → intensity → grain → vignette.
        private const val FRAG_MAIN = """#version 300 es
precision highp float;
uniform sampler2D uSrc;
uniform sampler2D uLive;
uniform highp sampler3D uLut;
uniform sampler2D uDepth;
uniform sampler2D uMask;
uniform sampler2D uFreeze;
uniform float uLutSize;
uniform int uLookOn;
uniform float uIntensity;
uniform float uGrain;
uniform float uGrainScale;
uniform float uVignette;
uniform float uSharp;
uniform float uAspect;
uniform float uTime;
uniform int uMonitor;
uniform int uSignalGraded;
uniform float uZebra;
uniform float uPeak;
uniform vec2 uTexel;
uniform int uMirror;
uniform int uFreezeOn;
uniform int uDofOn;
uniform float uFocus;
uniform float uDofStrength;
uniform int uProtectPeople;
uniform float uMaxLod;
uniform float uMaxRadius;
in vec2 vTex;
in vec2 vUv;
out vec4 frag;

float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
float hash(vec2 p, float seed) { return fract(sin((p.x + seed) * 12.9898 + (p.y + seed) * 78.233) * 43758.5453) - 0.5; }

vec2 mirrored(vec2 t) {
    if (uMirror == 1) return vec2(t.x > 0.5 ? 1.0 - t.x : t.x, t.y);
    if (uMirror == 2) return vec2(t.x, t.y > 0.5 ? 1.0 - t.y : t.y);
    return t;
}

// Depth of field. Blur (circle of confusion, 0..1) grows with distance from the focus plane: fully behind the
// subject, more gently in front, with a dead zone so depth-model noise near the subject never shows as patchy blur.
// People at the focus distance are protected by the segmentation mask.
float cocAt(vec2 t) {
    float d = texture(uDepth, t).r;
    float behind = smoothstep(0.05, 0.38, uFocus - d);
    float front = smoothstep(0.10, 0.45, d - uFocus) * 0.6;
    float c = max(behind, front) * uDofStrength;
    if (uProtectPeople == 1) {
        float m = texture(uMask, t).r;
        float personAtFocus = 1.0 - clamp(abs(d - uFocus) * 3.0, 0.0, 1.0);
        c *= 1.0 - smoothstep(0.3, 0.7, m) * personAtFocus;
    }
    return c;
}

// Disc blur: 16 taps on a golden-angle spiral, sampled from a mip level about two steps below the radius so the
// taps overlap (smooth, no blocks). Each tap is weighted by its own blur amount, so a sharp subject cannot bleed
// into the background bokeh; bright taps get more weight so lights bloom into discs.
vec3 dof(vec2 t, vec3 sharp) {
    float coc = cocAt(t);
    if (coc < 0.02) return sharp;
    float radius = coc * uMaxRadius;
    float lod = max(0.0, log2(max(radius, 1.0)) - 2.0);
    vec3 acc = vec3(0.0);
    float w = 0.0;
    for (int i = 0; i < 16; i++) {
        float fi = float(i) + 0.5;
        float a = fi * 2.39996;
        vec2 st = t + vec2(cos(a), sin(a)) * sqrt(fi / 16.0) * radius * uTexel;
        vec3 s = textureLod(uSrc, st, lod).rgb;
        float l = luma(s);
        float wt = (0.12 + smoothstep(0.0, 0.5, cocAt(st))) * (1.0 + l * l * 2.0);
        acc += s * wt; w += wt;
    }
    return mix(sharp, acc / w, smoothstep(0.02, 0.12, coc));
}

vec3 sharpen(vec3 c, vec2 t) {
    vec3 a = texture(uSrc, t + vec2(uTexel.x, 0.0)).rgb; vec3 b = texture(uSrc, t - vec2(uTexel.x, 0.0)).rgb;
    vec3 u = texture(uSrc, t + vec2(0.0, uTexel.y)).rgb; vec3 d = texture(uSrc, t - vec2(0.0, uTexel.y)).rgb;
    vec3 avg = (a + b + u + d) * 0.25;
    if (uSharp > 0.0) {
        // Same rules as the still sharpener: ignore detail below the noise floor, and never push luma outside the
        // neighbourhood's range (no halos or ringing).
        float l = luma(c);
        float dl = l - luma(avg);
        dl = sign(dl) * max(0.0, abs(dl) - 0.0086);
        float lo = min(l, min(min(luma(a), luma(b)), min(luma(u), luma(d))));
        float hi = max(l, max(max(luma(a), luma(b)), max(luma(u), luma(d))));
        float nl = clamp(l + dl * uSharp * 1.4, lo - 0.006, hi + 0.006);
        return clamp(c + (nl - l), 0.0, 1.0);
    }
    return mix(c, avg, -uSharp * 0.7);
}

vec3 grade(vec3 c) {
    float s = (uLutSize - 1.0) / uLutSize;
    float o = 0.5 / uLutSize;
    vec3 g = texture(uLut, clamp(c, 0.0, 1.0) * s + o).rgb;
    vec3 r = mix(c, g, uIntensity);
    if (uGrain > 0.0) {
        float n = hash(floor(gl_FragCoord.xy * uGrainScale), fract(uTime) * 7.0 + 0.37) * uGrain * 0.16 * uIntensity;
        r += n * (1.0 - 0.6 * luma(r));
    }
    if (uVignette > 0.0) {
        vec2 d = (vUv - 0.5) * vec2(uAspect, 1.0);
        float dist = length(d) / length(vec2(0.5 * uAspect, 0.5));
        float tt = clamp((dist - 0.45) / 0.55, 0.0, 1.0);
        r *= 1.0 - uVignette * 0.65 * tt * tt * (3.0 - 2.0 * tt) * uIntensity;
    }
    return clamp(r, 0.0, 1.0);
}

vec3 falseColor(float y) {
    if (y < 0.025) return vec3(0.45, 0.0, 0.6);
    if (y < 0.10) return vec3(0.1, 0.25, 0.85);
    if (y < 0.38) return vec3(y * 0.8);
    if (y < 0.48) return vec3(0.25, 0.75, 0.3);
    if (y < 0.58) return vec3(y * 0.85);
    if (y < 0.72) return vec3(0.95, 0.6, 0.65);
    if (y < 0.93) return vec3(y * 0.9);
    if (y < 0.98) return vec3(0.95, 0.85, 0.15);
    return vec3(0.95, 0.12, 0.1);
}

void main() {
    vec2 t = mirrored(vTex);
    vec3 src = texture(uSrc, t).rgb;
    if (uFreezeOn == 1) {
        // Time freeze: the person (segmentation mask) stays live; everything else holds the frozen plate.
        float m = smoothstep(0.35, 0.65, texture(uMask, t).r);
        src = mix(texture(uFreeze, t).rgb, texture(uLive, t).rgb, m);
    }
    if (uDofOn == 1) src = dof(t, src);
    vec3 base = (uLookOn == 1 && uSharp != 0.0) ? sharpen(src, t) : src;
    vec3 c = (uLookOn == 1) ? grade(base) : src;
    if (uMonitor != 0) {
        vec3 sig = (uSignalGraded == 1) ? c : src;
        float y = luma(sig);
        if ((uMonitor & 8) != 0) c = vec3(luma(c));
        if ((uMonitor & 4) != 0) c = falseColor(y);
        if ((uMonitor & 2) != 0) {
            float tl = luma(texture(uSrc, t + uTexel * vec2(-1.0, -1.0)).rgb);
            float tp = luma(texture(uSrc, t + uTexel * vec2( 0.0, -1.0)).rgb);
            float tr = luma(texture(uSrc, t + uTexel * vec2( 1.0, -1.0)).rgb);
            float l  = luma(texture(uSrc, t + uTexel * vec2(-1.0,  0.0)).rgb);
            float r  = luma(texture(uSrc, t + uTexel * vec2( 1.0,  0.0)).rgb);
            float bl = luma(texture(uSrc, t + uTexel * vec2(-1.0,  1.0)).rgb);
            float b  = luma(texture(uSrc, t + uTexel * vec2( 0.0,  1.0)).rgb);
            float br = luma(texture(uSrc, t + uTexel * vec2( 1.0,  1.0)).rgb);
            float gx = -tl - 2.0 * l - bl + tr + 2.0 * r + br;
            float gy = -tl - 2.0 * tp - tr + bl + 2.0 * b + br;
            if (length(vec2(gx, gy)) > uPeak) c = mix(c, vec3(0.91, 0.64, 0.24), 0.9);
        }
        if ((uMonitor & 1) != 0 && y >= uZebra) {
            float s = mod(floor((gl_FragCoord.x + gl_FragCoord.y + uTime * 40.0) / 7.0), 2.0);
            c = mix(c, vec3(s), 0.75);
        }
    }
    frag = vec4(c, 1.0);
}
"""
    }
}
