package com.daya.velum.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Rational
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.DynamicRange
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.MeteringPoint
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.core.UseCase
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.daya.velum.capability.CameraCapabilityRepository
import com.daya.velum.capability.DeviceCapabilities
import com.daya.velum.capability.ProbeNames
import com.daya.velum.capability.probeOk
import com.daya.velum.imaging.FrameStats
import com.daya.velum.settings.MovieEffect
import com.daya.velum.settings.CaptureMode
import com.daya.velum.imaging.Reframe
import com.daya.velum.imaging.MlRunner
import com.daya.velum.imaging.LiveEffect
import com.daya.velum.imaging.DofState
import com.daya.velum.imaging.LookProcessor
import com.daya.velum.imaging.MonitorFlags
import com.daya.velum.imaging.RenderState
import com.daya.velum.media.MediaRepository
import com.daya.velum.settings.CameraSettings
import com.daya.velum.settings.CineFraming
import com.daya.velum.settings.DynamicRangeMode
import com.daya.velum.settings.ExposureProgram
import com.daya.velum.settings.FlashMode
import com.daya.velum.settings.LookTarget
import com.daya.velum.settings.PhotoAspect
import com.daya.velum.settings.PhotoFormat
import com.daya.velum.settings.Stabilization
import com.daya.velum.settings.VideoQuality
import com.daya.velum.settings.WbMode
import com.daya.velum.video.RecEvent
import com.daya.velum.video.RecState
import com.daya.velum.video.RecordingStateMachine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

private const val TAG = "Velum/Engine"
/** From this zoom ratio Portrait uses Velum's depth blur instead of the camera's bokeh extension. */
const val PORTRAIT_TELE_ZOOM = 2f
/** Horizon lock range, degrees. Past this the crop would be too tight to be useful. */
const val HORIZON_MAX = 20f
/** Direction of the correction relative to gravity roll; confirmed on device with an injected roll. */
const val HORIZON_SIGN = -1f
val HORIZON_MODES = setOf(CaptureMode.VIDEO, CaptureMode.PRO_VIDEO, CaptureMode.CINEMA, CaptureMode.VLOG)

sealed interface EngineStatus {
    data object Idle : EngineStatus
    data object Binding : EngineStatus
    data class Ready(val description: String) : EngineStatus
    data class Error(val message: String, val recoverable: Boolean = true) : EngineStatus
}

sealed interface EngineEvent {
    data class PhotoSaved(val uri: Uri, val width: Int, val height: Int, val label: String) : EngineEvent
    data class PhotoFailed(val message: String) : EngineEvent
    data class Notice(val message: String) : EngineEvent
    data class Focused(val success: Boolean) : EngineEvent
    /** The camera refused a video combination; the engine asks for a lighter one instead of stopping. */
    data class Downgraded(val stabilization: Stabilization?, val sdr: Boolean, val message: String) : EngineEvent
}

/** The parts of [CameraSettings] that require a new CameraX session when they change. */
private data class BindKey(
    val cameraId: String,
    val physicalId: String?,
    /** Photo mode keeps a rolling silent video loop for Motion photos. */
    val motion: Boolean = false,
    val video: Boolean,
    val photoFormat: PhotoFormat?,
    val photoAspect: PhotoAspect?,
    val quality: VideoQuality?,
    val fps: Int?,
    val hdr: Boolean,
    val stab: Stabilization?,
    val effect: Boolean,
    val bake: Boolean,
    val cineCrop: Float?,
    /** Vendor still extension ("NIGHT", "BOKEH") or null. */
    val extension: String?,
    /** Dual video composition (swap, corner) or null. */
    val dual: Pair<Boolean, Int>?,
    /** Long exposure: full-resolution YUV analysis stream instead of still capture. */
    val analysis: Boolean,
    val pro: Boolean,
)

/**
 * Sole owner of the CameraX session. UI hands it immutable [CameraSettings]; the engine rebinds only when the stream
 * set changes and otherwise updates request options in place. Manual 3A goes through Camera2 interop on the same
 * session, so no second Camera2 client ever touches the device.
 */
@OptIn(ExperimentalCamera2Interop::class)
class CameraEngine(
    private val context: Context,
    private val capsRepo: CameraCapabilityRepository,
    private val media: MediaRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainExec = ContextCompat.getMainExecutor(context)
    private val ioExec = Executors.newSingleThreadExecutor { r -> Thread(r, "VelumCapture") }

    private val _meta = MutableStateFlow(LiveMeta())
    val meta: StateFlow<LiveMeta> = _meta
    private val _stats = MutableStateFlow<FrameStats?>(null)
    val stats: StateFlow<FrameStats?> = _stats
    private val _rec = MutableStateFlow<RecState>(RecState.Idle)
    val rec: StateFlow<RecState> = _rec
    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.Idle)
    val status: StateFlow<EngineStatus> = _status
    val events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 16)
    private val _capturing = MutableStateFlow(0)
    val capturesInFlight: StateFlow<Int> = _capturing

    private var provider: ProcessCameraProvider? = null
    private var extensions: ExtensionsManager? = null
    private var owner: LifecycleOwner? = null
    private var surfaceProvider: Preview.SurfaceProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var boundKey: BindKey? = null
    private var appliedOptions: Map<CaptureRequest.Key<*>, Any?> = emptyMap()
    private var current = CameraSettings()
    private var lastZoom = -1f
    private var lastTorch: Boolean? = null
    private var lastEv: Int? = null
    private var sIso: Int? = null
    private var kelvinBase: Pair<RggbChannelVector, ColorSpaceTransform>? = null
    private var kelvinCalibrating = false
    private var lastSAdjustMs = 0L
    private var bindRetries = 0
    /** Times of automatic rebinds after camera errors; capped so a broken setup ends in an error, not a loop. */
    private val recoveries = ArrayDeque<Long>()
    private var analysis: ImageAnalysis? = null
    private val leExec = Executors.newSingleThreadExecutor { r -> Thread(r, "VelumLongExp") }
    @Volatile private var accumulator: com.daya.velum.imaging.LongExposure? = null
    /** Transient 3A override used while a long exposure runs (null = normal settings). */
    private var leOverride: Map<CaptureRequest.Key<*>, Any?>? = null

    /** Receives full-resolution frames when no long exposure is running (best shot, clone). Runs on [leExec]. */
    @Volatile private var frameSink: ((androidx.camera.core.ImageProxy) -> Unit)? = null

    private fun onAnalysisFrame(img: androidx.camera.core.ImageProxy) {
        try {
            val acc = accumulator
            if (acc != null) acc.add(img) else frameSink?.invoke(img)
        } catch (t: Throwable) { Log.e(TAG, "frame handling failed", t) } finally { img.close() }
    }

    /** Full-resolution frames are delivered upright for this device rotation (a Surface.ROTATION_* value). */
    fun setAnalysisRotation(rotation: Int) { analysis?.targetRotation = rotation }

    /** One detached full-resolution frame, or null if the frame stream is not bound. */
    suspend fun grabFrame(timeoutMs: Long = 3000): com.daya.velum.imaging.YuvFrame? {
        if (analysis == null) return null
        val d = kotlinx.coroutines.CompletableDeferred<com.daya.velum.imaging.YuvFrame>()
        frameSink = { img -> if (!d.isCompleted) d.complete(com.daya.velum.imaging.YuvFrame.copy(img)) }
        return try { kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { d.await() } } finally { frameSink = null }
    }

    /** Scores every frame for [ms] and returns (frames seen, sharpest [keep] best first). */
    suspend fun bestShot(ms: Long, keep: Int): Pair<Int, List<Pair<Float, com.daya.velum.imaging.YuvFrame>>> {
        if (analysis == null) return 0 to emptyList()
        val col = com.daya.velum.imaging.BestShotCollector(keep)
        frameSink = { img -> col.offer(img) }
        try { delay(ms) } finally { frameSink = null }
        kotlinx.coroutines.withContext(Dispatchers.IO) { leExec.submit { }.get() }
        return col.frames to col.ranked()
    }

    // ---- Motion photo: a rolling silent video loop in Photo mode; the shutter keeps ~1.5 s either side of the photo.
    private var motionCapture: VideoCapture<Recorder>? = null
    private var motionRec: Recording? = null
    private var motionFinal: CompletableDeferred<Boolean>? = null
    private var motionFile: java.io.File? = null
    private var motionStartMs = 0L
    private var motionIdx = 0
    private var motionJob: kotlinx.coroutines.Job? = null
    @Volatile private var motionHolding = false
    /** Rotation for the motion loop; a change restarts the current segment so the clip matches the photo. */
    @Volatile var motionRotation = Surface.ROTATION_0
        set(v) { if (v != field) { field = v; motionStale = true } }
    @Volatile private var motionStale = false
    private val motionDir get() = java.io.File(context.cacheDir, "motion").apply { mkdirs() }

    private fun startMotionSegment() {
        val vc = motionCapture ?: return
        val f = java.io.File(motionDir, "seg${motionIdx++ % 2}.mp4")
        val done = CompletableDeferred<Boolean>()
        motionFinal = done; motionFile = f; motionStartMs = SystemClock.elapsedRealtime(); motionStale = false
        vc.targetRotation = motionRotation
        motionRec = runCatching {
            vc.output.prepareRecording(context, androidx.camera.video.FileOutputOptions.Builder(f).build())
                .start(ContextCompat.getMainExecutor(context)) { e ->
                    if (e is VideoRecordEvent.Finalize) done.complete(e.error == VideoRecordEvent.Finalize.ERROR_NONE)
                }
        }.onFailure { Log.e(TAG, "motion segment failed", it); done.complete(false) }.getOrNull()
    }

    private fun startMotionLoop() {
        motionJob?.cancel()
        motionJob = scope.launch {
            startMotionSegment()
            while (true) {
                delay(500)
                if (!motionHolding && (motionStale || SystemClock.elapsedRealtime() - motionStartMs > 6000)) {
                    motionRec?.stop(); motionRec = null
                    withTimeoutOrNull(3000) { motionFinal?.await() }
                    if (!motionHolding) startMotionSegment()
                }
            }
        }
    }

    private fun stopMotion() {
        motionJob?.cancel(); motionJob = null
        motionRec?.stop(); motionRec = null
    }

    /** Keeps recording 1.5 s past the shutter, then returns a ≤3 s clip and the photo's time inside it. */
    suspend fun motionClip(): Pair<java.io.File, Long>? {
        if (motionCapture == null || motionRec == null) return null
        motionHolding = true
        try {
            val shutterMs = SystemClock.elapsedRealtime()
            val segStart = motionStartMs
            delay(1500)
            val f = motionFile ?: return null
            val done = motionFinal ?: return null
            motionRec?.stop(); motionRec = null
            if (withTimeoutOrNull(4000) { done.await() } != true) return null
            val out = java.io.File(motionDir, "clip.mp4")
            val pres = kotlinx.coroutines.withContext(Dispatchers.IO) {
                runCatching { Mp4Trim.lastSeconds(f, out, 3_000_000L, (shutterMs - segStart) * 1000L) }.onFailure { Log.e(TAG, "motion trim failed", it) }.getOrNull()
            } ?: return null
            return out to pres
        } finally {
            motionHolding = false
            if (motionJob?.isActive == true && motionRec == null) startMotionSegment()
        }
    }

    /** Debug builds only: a fixed roll deviation injected for testing the horizon-lock direction. */
    @Volatile var debugRoll: Float? = null

    /**
     * Gravity roll of the phone in degrees (0 = upright portrait, ±90 = landscape). Horizon lock levels the picture
     * against the nearest of those four orientations, up to ±[HORIZON_MAX] degrees.
     */
    fun setRoll(deg: Float) {
        val dev = debugRoll ?: (deg - Math.round(deg / 90f) * 90f)
        // Near 45° the holding orientation is ambiguous: keep the last angle rather than flip.
        if (kotlin.math.abs(dev) > HORIZON_MAX + 8f) return
        processor.horizonDeg = HORIZON_SIGN * dev.coerceIn(-HORIZON_MAX, HORIZON_MAX)
    }

    /** The back camera that focuses closest (the ultra-wide on this phone: 5 cm), used for Macro. */
    fun macroCameraId(): String? = caps?.camera2
        ?.filter { !it.isPhysicalOnly && it.facing == "BACK" && caps?.cx(it.id) != null }
        ?.maxByOrNull { it.lens.minFocusDiopters ?: 0f }?.id

    /**
     * Runs a multi-frame long exposure for [seconds]: applies the EV bias, lets AE settle, locks AE/AWB (or sets a
     * fixed max-length exposure for star modes), accumulates every frame it can, then returns the rendered image.
     * [onProgress] receives (elapsedMs, frames, preview) about three times a second.
     */
    suspend fun longExposure(mode: com.daya.velum.imaging.LongExposureMode, seconds: Int, align: Boolean = false,
                             onProgress: (Long, Int, android.graphics.Bitmap?) -> Unit): android.graphics.Bitmap? {
        val cam = camera ?: return null
        if (analysis == null) return null
        val c2 = caps?.c2(boundKey?.cameraId ?: "0")
        val step = c2?.evStep ?: 0.1f
        // Fixed maximum exposure only makes sense in the dark. If auto exposure is already short (a lit room, dusk),
        // lock the auto exposure instead so the stack is not blown out.
        val m = _meta.value
        val dark = (m.exposureNs ?: 0L).toDouble() * (m.iso ?: 0) >= 60_000_000.0 * 1600
        if (mode.manualMaxShutter && c2 != null && dark) {
            val maxExp = c2.sensor.exposureNsRange?.upper ?: 100_000_000L
            val iso = minOf(3200, c2.sensor.isoRange?.upper ?: 3200)
            leOverride = mapOf(CaptureRequest.CONTROL_AE_MODE to CameraMetadata.CONTROL_AE_MODE_OFF,
                CaptureRequest.SENSOR_EXPOSURE_TIME to maxExp, CaptureRequest.SENSOR_FRAME_DURATION to maxExp,
                CaptureRequest.SENSOR_SENSITIVITY to iso, CaptureRequest.CONTROL_AWB_LOCK to true)
        } else {
            cam.cameraControl.setExposureCompensationIndex(((current.exposure.evIndex * step + mode.evBias) / step).roundToInt())
            delay(700)
            leOverride = mapOf(CaptureRequest.CONTROL_AE_LOCK to true, CaptureRequest.CONTROL_AWB_LOCK to true)
        }
        appliedOptions = emptyMap(); applyControls(current)
        delay(250)
        val acc = com.daya.velum.imaging.LongExposure(mode.blend, align)
        accumulator = acc
        val start = SystemClock.elapsedRealtime()
        try {
            while (SystemClock.elapsedRealtime() - start < seconds * 1000L) {
                delay(330)
                val prev = kotlinx.coroutines.withContext(Dispatchers.Default) { runCatching { acc.render(8) }.getOrNull() }
                onProgress(SystemClock.elapsedRealtime() - start, acc.frames, prev)
            }
        } finally {
            accumulator = null
            leOverride = null
            lastEv = null
            appliedOptions = emptyMap(); applyControls(current)
        }
        // Wait for an in-flight add() on the analysis thread to finish before rendering.
        kotlinx.coroutines.withContext(Dispatchers.IO) { leExec.submit { }.get() }
        val out = kotlinx.coroutines.withContext(Dispatchers.Default) { acc.render(1) }
        if (align) Log.i(TAG, "aligned stack: ${acc.frames} frames, max shift ${acc.maxShiftPx}px")
        acc.release()
        return out
    }

    val ml = MlRunner(context).also { m ->
        m.onDepth = { b -> processor.submitDepth(b) }
        m.onMask = { b -> processor.submitMask(b) }
    }

    val processor = LookProcessor().also { p ->
        p.statsListener = { st -> _stats.value = st; scope.launch { onStats(st) } }
        p.errorListener = { t -> _status.value = EngineStatus.Error("Image pipeline error: ${t.message}") }
    }

    private val caps: DeviceCapabilities? get() = capsRepo.capabilities.value

    suspend fun init(): ProcessCameraProvider {
        provider?.let { return it }
        val p = ProcessCameraProvider.getInstance(context).await()
        provider = p
        extensions = runCatching { ExtensionsManager.getInstanceAsync(context, p).await() }.getOrNull()
        return p
    }

    fun attach(owner: LifecycleOwner, surfaceProvider: Preview.SurfaceProvider) {
        this.owner = owner
        this.surfaceProvider = surfaceProvider
        boundKey = null
    }

    fun detach() {
        runCatching { provider?.unbindAll() }
        camera = null; imageCapture = null; videoCapture = null; boundKey = null
        appliedOptions = emptyMap(); lastZoom = -1f; lastTorch = null; lastEv = null
        owner = null; surfaceProvider = null
    }

    /** True when the bound video session includes a validated still stream. */
    val canSnapshot: Boolean get() = boundKey?.video == true && imageCapture != null

    /** Releases the camera for another owner (the Camera2 slow-motion engine). */
    fun park() {
        if (boundKey == null && camera == null) return
        runCatching { provider?.unbindAll() }
        camera = null; imageCapture = null; videoCapture = null; analysis = null; boundKey = null
        appliedOptions = emptyMap(); lastZoom = -1f; lastTorch = null; lastEv = null
        _status.value = EngineStatus.Idle
    }

    /** Forces a fresh bind of [s], e.g. after a camera error. */
    fun retry(s: CameraSettings) {
        boundKey = null
        apply(s)
    }

    fun release() {
        detach()
        processor.release()
        ml.release()
        ioExec.shutdown()
    }

    // ------------------------------------------------------------------ binding

    private fun keyFor(s: CameraSettings): BindKey? {
        val c = caps ?: return null
        val lens = c.lenses.firstOrNull { it.key == s.lensKey } ?: return null
        val hdr = s.mode.isVideo && s.video.dynamicRange == DynamicRangeMode.HLG
        // The camera's own bokeh processing is a 1x feature; at 3x/5x Portrait takes a normal photo and Velum adds depth blur.
        val ext = s.mode.extension?.takeIf { c.cx(lens.logicalId)?.extensions?.contains(it) == true }
            ?.takeIf { !(s.mode == CaptureMode.PORTRAIT && s.zoom >= PORTRAIT_TELE_ZOOM) }
        if (s.mode == CaptureMode.DUAL) return dualKey(s)
        val crop = if (s.mode.isVideo && s.mode.name == "CINEMA" && s.cine.framing != CineFraming.GUIDE) s.cine.ratio.ratio else null
        val macro = s.mode == CaptureMode.MACRO
        return BindKey(
            cameraId = if (macro) macroCameraId() ?: lens.logicalId else lens.logicalId,
            physicalId = if (s.lockPhysicalLens && !macro) lens.physicalId else null,
            motion = s.mode == CaptureMode.PHOTO && s.motionPhoto && ext == null && s.photoFormat == PhotoFormat.JPEG,
            video = s.mode.isVideo,
            photoFormat = if (s.mode.isVideo) null else s.photoFormat,
            photoAspect = if (s.mode.isVideo) null else s.photoAspect,
            quality = if (s.mode.isVideo) s.video.quality else null,
            // Movie renders depth of field on every frame; like other cinematic modes it is capped at 30 fps.
            fps = if (!s.mode.isVideo) null else if (s.mode == CaptureMode.MOVIE) minOf(s.video.fps, 30) else s.video.fps,
            hdr = hdr,
            stab = if (s.mode.isVideo) s.video.stabilization else null,
            // CameraX rejects Ultra HDR stills combined with a CameraEffect (verified on device), so that format has no GPU path.
            effect = ext == null && !hdr && !(!s.mode.isVideo && s.photoFormat == PhotoFormat.ULTRA_HDR),
            // Movie and Effects modes render their effect into the recording, so the video always goes through the GPU.
            bake = s.mode.isVideo && !hdr && (s.videoLook.target == LookTarget.BAKED || s.mode == CaptureMode.MOVIE || s.mode == CaptureMode.EFFECTS ||
                s.mode == CaptureMode.VLOG || (s.horizonLock && s.mode in HORIZON_MODES)),
            cineCrop = crop,
            extension = ext,
            analysis = s.mode == CaptureMode.LONG_EXPOSURE || s.mode == CaptureMode.ASTRO || s.mode == CaptureMode.BEST_SHOT || s.mode == CaptureMode.CLONE,
            dual = if (s.mode == CaptureMode.DUAL) s.dualSwap to s.dualCorner else null,
            pro = s.mode.isPro,
        )
    }

    /** Applies [s]. Rebinds if needed (never during a recording), then updates request options. */
    fun apply(s: CameraSettings) {
        val prev = current
        current = s
        if (s.mode == CaptureMode.SLOW_MO) { park(); return }
        val key = keyFor(s) ?: return
        if (key != boundKey) {
            if (_rec.value.isActive) {
                events.tryEmit(EngineEvent.Notice("Stop recording to change that setting."))
                current = prev.copy(zoom = s.zoom, exposure = s.exposure, focus = s.focus, wb = s.wb, videoLook = s.videoLook.copy(target = prev.videoLook.target), monitor = s.monitor)
                applyControls(current)
                return
            }
            bind(key, s)
        }
        applyControls(s)
    }

    private fun dualKey(s: CameraSettings) = BindKey(
        cameraId = "dual", physicalId = null, video = true, photoFormat = null, photoAspect = null, quality = VideoQuality.FHD,
        fps = 30, hdr = false, stab = null, effect = false, bake = false, cineCrop = null, extension = null, pro = false,
        dual = s.dualSwap to s.dualCorner, analysis = false,
    )

    /** True when the device reports a back+front concurrent pair (Android concurrent camera API). */
    fun dualSupported(): Boolean = runCatching {
        val pairs = provider?.availableConcurrentCameraInfos.orEmpty()
        pairs.any { set -> set.any { it.lensFacing == CameraSelector.LENS_FACING_BACK } && set.any { it.lensFacing == CameraSelector.LENS_FACING_FRONT } }
    }.getOrDefault(false)

    /**
     * Binds back and front together. Both configs share one Preview and one VideoCapture so CameraX composites them
     * into a single picture-in-picture stream; the inset is scaled to 32 % and placed in the chosen corner.
     */
    private fun bindDual(key: BindKey) {
        val p = provider ?: return
        val o = owner ?: return
        val sp = surfaceProvider ?: return
        val pair = p.availableConcurrentCameraInfos.firstOrNull { set ->
            set.any { it.lensFacing == CameraSelector.LENS_FACING_BACK } && set.any { it.lensFacing == CameraSelector.LENS_FACING_FRONT }
        } ?: error("This phone reports no back+front concurrent camera pair")
        val back = pair.first { it.lensFacing == CameraSelector.LENS_FACING_BACK }.cameraSelector
        val front = pair.first { it.lensFacing == CameraSelector.LENS_FACING_FRONT }.cameraSelector
        val preview = Preview.Builder().build().also { it.surfaceProvider = sp }
        val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.FHD,
            androidx.camera.video.FallbackStrategy.lowerQualityOrHigherThan(Quality.HD))).build()
        val vc = VideoCapture.Builder(recorder).build()
        videoCapture = vc
        val group = androidx.camera.core.UseCaseGroup.Builder().addUseCase(preview).addUseCase(vc).build()
        val (swap, corner) = key.dual ?: (false to 1)
        val sx = if (corner % 2 == 1) 0.62f else -0.62f
        val sy = if (corner < 2) 0.62f else -0.62f
        val full = androidx.camera.core.CompositionSettings.Builder().setAlpha(1f).setOffset(0f, 0f).setScale(1f, 1f).build()
        val inset = androidx.camera.core.CompositionSettings.Builder().setAlpha(1f).setOffset(sx, sy).setScale(0.32f, 0.32f).build()
        val (mainSel, insetSel) = if (swap) front to back else back to front
        val cc = p.bindToLifecycle(listOf(
            androidx.camera.core.ConcurrentCamera.SingleCameraConfig(mainSel, group, full, o),
            androidx.camera.core.ConcurrentCamera.SingleCameraConfig(insetSel, group, inset, o),
        ))
        camera = cc.cameras.firstOrNull()
        boundKey = key
        bindRetries = 0
        _status.value = EngineStatus.Ready("dual: ${if (swap) "front" else "back"} main, inset corner $corner")
        Log.i(TAG, "bound dual $key")
    }

    @SuppressLint("RestrictedApi")
    private fun bind(key: BindKey, s: CameraSettings) {
        val p = provider ?: return
        if (key.dual != null) {
            _status.value = EngineStatus.Binding
            try { p.unbindAll(); camera = null; imageCapture = null; videoCapture = null; analysis = null; bindDual(key) }
            catch (t: Throwable) {
                Log.e(TAG, "dual bind failed", t); boundKey = null
                _status.value = EngineStatus.Error("Dual video could not start: ${t.message}")
            }
            return
        }
        val o = owner ?: return
        val sp = surfaceProvider ?: return
        val c = caps ?: return
        _status.value = EngineStatus.Binding
        try {
            p.unbindAll()
            camera = null; imageCapture = null; videoCapture = null; analysis = null
            stopMotion(); motionCapture = null
            appliedOptions = emptyMap(); lastZoom = -1f; lastTorch = null; lastEv = null; sIso = null
            kelvinBase = null

            // Physical pinning is applied per stream (OutputConfiguration.setPhysicalCameraId via interop):
            // CameraX 1.6 does not forward CameraSelector.setPhysicalCameraId to the outputs on this device.
            var selector = CameraSelector.Builder()
                .addCameraFilter { infos -> infos.filter { androidx.camera.camera2.interop.Camera2CameraInfo.from(it).cameraId == key.cameraId } }
                .build()

            // Preview must share the recorded/captured aspect, or the viewfinder shows a different field of view.
            val previewBuilder = Preview.Builder().setResolutionSelector(
                ResolutionSelector.Builder().setAspectRatioStrategy(
                    if (key.video) AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY,
                ).build(),
            )
            Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(resultCallback)
                .apply { key.physicalId?.let { setPhysicalCameraId(it) } }
            if (key.stab == Stabilization.PREVIEW) previewBuilder.setPreviewStabilizationEnabled(true)
            if (key.hdr) previewBuilder.setDynamicRange(DynamicRange.HLG_10_BIT)
            val preview = previewBuilder.build().also { it.surfaceProvider = sp }

            val useCases = mutableListOf<UseCase>(preview)
            val cx = c.cx(key.cameraId)
            if (key.video) {
                val q = when (key.quality) { VideoQuality.UHD -> Quality.UHD; VideoQuality.HD -> Quality.HD; else -> Quality.FHD }
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(q))
                    .apply { if (s.video.bitrateMbps > 0) setTargetVideoEncodingBitRate(s.video.bitrateMbps * 1_000_000) }
                    .build()
                val vcb = VideoCapture.Builder(recorder)
                key.physicalId?.let { Camera2Interop.Extender(vcb).setPhysicalCameraId(it) }
                val vc = vcb
                    .setDynamicRange(if (key.hdr) DynamicRange.HLG_10_BIT else DynamicRange.SDR)
                    .setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)
                    .apply { if (key.stab == Stabilization.ON) setVideoStabilizationEnabled(true) }
                    .build()
                videoCapture = vc
                useCases += vc
                // Snapshot during recording only where the extra JPEG stream was validated.
                // Baked effects make CameraX share one stream between preview and video; an extra JPEG stream on top
                // exceeds the supported combinations at 4K, so stills-while-recording are offered only without baking.
                if (cx?.probeOk("Preview + Video ${key.quality?.name} + JPEG") == true && !key.hdr && !key.bake) {
                    val icb = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    key.physicalId?.let { Camera2Interop.Extender(icb).setPhysicalCameraId(it) }
                    imageCapture = icb.build().also { useCases += it }
                }
            } else if (key.analysis) {
                val ia = ImageAnalysis.Builder()
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(ResolutionStrategy(android.util.Size(4080, 3060), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                        .build())
                    .build()
                ia.setAnalyzer(leExec) { img -> onAnalysisFrame(img) }
                analysis = ia
                useCases += ia
            } else {
                val fmt = when (key.photoFormat) {
                    PhotoFormat.RAW_JPEG -> ImageCapture.OUTPUT_FORMAT_RAW_JPEG
                    PhotoFormat.ULTRA_HDR -> ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR
                    else -> ImageCapture.OUTPUT_FORMAT_JPEG
                }
                val icb = ImageCapture.Builder()
                    // Always the ISP's best still path: multi-frame noise reduction and full-quality processing.
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .apply { if (key.extension == null) setOutputFormat(fmt) }
                    .setJpegQuality(s.jpegQuality)
                key.physicalId?.let { pid ->
                    Camera2Interop.Extender(icb).setPhysicalCameraId(pid)
                    // CameraX sizes against the logical map; ask for the pinned sensor's own largest JPEG.
                    c.c2(pid)?.streams?.jpeg?.firstOrNull()?.let { size ->
                        icb.setResolutionSelector(ResolutionSelector.Builder()
                            .setResolutionStrategy(ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                            .build())
                    }
                }
                imageCapture = icb.build().also { useCases += it }
                if (key.motion) {
                    val rec = Recorder.Builder().setTargetVideoEncodingBitRate(6_000_000).setQualitySelector(
                        QualitySelector.fromOrderedList(listOf(Quality.FHD, Quality.HD), androidx.camera.video.FallbackStrategy.lowerQualityThan(Quality.HD))).build()
                    val mvc = VideoCapture.Builder(rec).build()
                    val supported = runCatching { p.getCameraInfo(selector).isSessionConfigSupported(SessionConfig(useCases + mvc)) }.getOrDefault(false)
                    if (supported) { motionCapture = mvc; useCases += mvc }
                    else { Log.w(TAG, "motion photo stream not supported with this photo setup"); events.tryEmit(EngineEvent.PhotoFailed("Motion photo is not available with this lens and format.")) }
                }
            }

            val cam = if (key.extension != null) {
                val ext = extensions ?: error("Extensions unavailable")
                val mode = when (key.extension) { "NIGHT" -> ExtensionMode.NIGHT; "BOKEH" -> ExtensionMode.BOKEH; "FACE_RETOUCH" -> ExtensionMode.FACE_RETOUCH; else -> ExtensionMode.NONE }
                selector = ext.getExtensionEnabledCameraSelector(selector, mode)
                p.bindToLifecycle(o, selector, *useCases.toTypedArray())
            } else {
                val cfg = SessionConfig.Builder(useCases).apply {
                    key.fps?.let { setFrameRateRange(Range(it, it)) }
                    if (key.effect) addEffect(processor.createEffect(key.bake))
                    val ratio = key.cineCrop?.let { Rational((it * 1000).roundToInt(), 1000) }
                        ?: key.photoAspect?.takeIf { it != PhotoAspect.R4_3 }?.let { Rational(it.w, it.h) }
                        ?: if (key.video) Rational(16, 9) else null
                    // ROTATION_90 expresses the crop in the sensor's landscape frame, independent of how the phone is held.
                    if (ratio != null) setViewPort(ViewPort.Builder(ratio, Surface.ROTATION_90).build())
                }.build()
                p.bindToLifecycle(o, selector, cfg)
            }
            camera = cam
            if (motionCapture != null) startMotionLoop()
            boundKey = key
            bindRetries = 0
            cam.cameraInfo.cameraState.observe(o) { st -> onCameraState(st) }
            setDeviceRotationDegrees(deviceDeg)
            _status.value = EngineStatus.Ready(describe(key))
            Log.i(TAG, "bound $key")
        } catch (t: Throwable) {
            Log.e(TAG, "bind failed for $key", t)
            boundKey = null
            // A camera still being released by another client (or our previous process) is briefly absent from
            // CameraX's available list. Retry a few times before reporting an error.
            // After two failed retries of the same video setup, step down to a lighter one rather than stop.
            if (bindRetries >= 2 && requestDowngrade(key)) { bindRetries = 0; return }
            if (bindRetries < 4) {
                bindRetries++
                _status.value = EngineStatus.Binding
                scope.launch { delay(400L * bindRetries); if (boundKey == null) apply(current) }
                return
            }
            _status.value = EngineStatus.Error("Camera could not start this configuration: ${t.message}")
        }
    }

    private fun describe(k: BindKey) = buildString {
        append("cam ${k.cameraId}")
        k.physicalId?.let { append(" phys $it") }
        if (k.video) append(" · ${k.quality?.label} ${k.fps}p${if (k.hdr) " HLG" else ""}") else append(" · ${k.photoFormat?.label}")
        if (k.effect) append(if (k.bake) " · look baked" else " · look monitor") else append(" · no GPU path")
        k.extension?.let { append(" · $it extension") }
    }

    private fun onCameraState(st: CameraState) {
        val err = st.error ?: run {
            if (st.type == CameraState.Type.OPEN && _status.value is EngineStatus.Error) _status.value = EngineStatus.Ready(boundKey?.let(::describe) ?: "")
            return
        }
        val msg = when (err.code) {
            CameraState.ERROR_CAMERA_IN_USE -> "Camera is in use by another app."
            CameraState.ERROR_MAX_CAMERAS_IN_USE -> "Too many cameras open."
            CameraState.ERROR_OTHER_RECOVERABLE_ERROR -> "Camera error; retrying."
            CameraState.ERROR_STREAM_CONFIG -> "This stream configuration was rejected by the camera."
            CameraState.ERROR_CAMERA_DISABLED -> "Camera is disabled by policy."
            CameraState.ERROR_CAMERA_FATAL_ERROR -> "Camera stopped with a fatal error. Reopen the app."
            CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> "Camera blocked by Do Not Disturb."
            else -> "Camera error ${err.code}."
        }
        _status.value = EngineStatus.Error(msg, err.type == CameraState.ErrorType.RECOVERABLE)
        // Errors the camera service reports after a session started (a rejected stream setup, a HAL fault under heat
        // or load) leave a dead viewfinder. Reopen the session, stepping video down when the setup was rejected.
        val restartable = err.code == CameraState.ERROR_STREAM_CONFIG || err.code == CameraState.ERROR_CAMERA_FATAL_ERROR ||
            err.code == CameraState.ERROR_OTHER_RECOVERABLE_ERROR
        if (!restartable || _rec.value.isActive) return
        val now = System.currentTimeMillis()
        while (recoveries.isNotEmpty() && now - recoveries.first() > 30_000) recoveries.removeFirst()
        if (recoveries.size >= 3) return
        recoveries.addLast(now)
        val key = boundKey
        if (err.code == CameraState.ERROR_STREAM_CONFIG && key != null && requestDowngrade(key)) return
        _status.value = EngineStatus.Binding
        scope.launch {
            delay(600)
            runCatching { provider?.unbindAll() }
            boundKey = null
            apply(current)
        }
    }

    /**
     * Asks for the next lighter video setup: Enhanced → Standard → Off stabilisation, then HLG → SDR. Returns false
     * when nothing is left to drop (photo modes, or already plain SDR without stabilisation).
     */
    private fun requestDowngrade(key: BindKey): Boolean {
        if (!key.video || key.dual != null) return false
        val ev = when {
            key.stab == Stabilization.PREVIEW -> EngineEvent.Downgraded(Stabilization.ON, false,
                "Enhanced stabilisation isn't available with these settings right now. Switched to Standard.")
            key.stab == Stabilization.ON -> EngineEvent.Downgraded(Stabilization.OFF, false,
                "Stabilisation isn't available with these settings right now. Switched it off.")
            key.hdr -> EngineEvent.Downgraded(null, true, "HLG isn't available with these settings right now. Switched to SDR.")
            else -> return false
        }
        Log.w(TAG, "downgrading $key: ${ev.message}")
        _status.value = EngineStatus.Binding
        events.tryEmit(ev)
        return true
    }

    // ------------------------------------------------------------------ controls

    private fun applyControls(s: CameraSettings) {
        val cam = camera ?: return
        val key = boundKey ?: return
        if (key.dual != null) return
        val cc = cam.cameraControl

        if (abs(s.zoom - lastZoom) > 0.001f) { cc.setZoomRatio(s.zoom); lastZoom = s.zoom }
        val torch = s.flash == FlashMode.TORCH
        if (torch != lastTorch && cam.cameraInfo.hasFlashUnit()) { cc.enableTorch(torch); lastTorch = torch }
        imageCapture?.flashMode = when (s.flash) {
            FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO; FlashMode.ON -> ImageCapture.FLASH_MODE_ON; else -> ImageCapture.FLASH_MODE_OFF
        }
        if (key.extension != null) { pushRender(s, key); return }

        val e = s.exposure
        if (e.program == ExposureProgram.P && e.evIndex != lastEv) { cc.setExposureCompensationIndex(e.evIndex); lastEv = e.evIndex }

        val opts = linkedMapOf<CaptureRequest.Key<*>, Any?>()
        val frameNs = key.fps?.let { 1_000_000_000L / it }
        val nightLapse = s.mode == CaptureMode.EFFECTS && s.effects.effect == MovieEffect.NIGHT_TIMELAPSE
        if (nightLapse) {
            // Night time-lapse: 1/8 s exposures at 8 fps; ISO follows the app's meter (shutter priority).
            val shutter = 125_000_000L
            opts[CaptureRequest.CONTROL_AE_MODE] = CameraMetadata.CONTROL_AE_MODE_OFF
            opts[CaptureRequest.SENSOR_EXPOSURE_TIME] = shutter
            opts[CaptureRequest.SENSOR_SENSITIVITY] = sIso ?: seedShutterPriorityIso(shutter)
            opts[CaptureRequest.SENSOR_FRAME_DURATION] = shutter
        } else when (e.program) {
            ExposureProgram.P -> if (e.aeLock) opts[CaptureRequest.CONTROL_AE_LOCK] = true
            ExposureProgram.S, ExposureProgram.M -> {
                val shutter = frameNs?.let { minOf(e.shutterNs, it) } ?: e.shutterNs
                val iso = if (e.program == ExposureProgram.M) e.iso else (sIso ?: seedShutterPriorityIso(shutter))
                opts[CaptureRequest.CONTROL_AE_MODE] = CameraMetadata.CONTROL_AE_MODE_OFF
                opts[CaptureRequest.SENSOR_EXPOSURE_TIME] = shutter
                opts[CaptureRequest.SENSOR_SENSITIVITY] = iso
                opts[CaptureRequest.SENSOR_FRAME_DURATION] = frameNs ?: maxOf(shutter, 33_333_333L)
            }
        }
        if (s.focus.manual) {
            opts[CaptureRequest.CONTROL_AF_MODE] = CameraMetadata.CONTROL_AF_MODE_OFF
            opts[CaptureRequest.LENS_FOCUS_DISTANCE] = s.focus.diopters
        } else if (s.focus.afLock) {
            opts[CaptureRequest.CONTROL_AF_MODE] = CameraMetadata.CONTROL_AF_MODE_OFF
            opts[CaptureRequest.LENS_FOCUS_DISTANCE] = _meta.value.focusDiopters ?: 0f
        }
        when (s.wb.mode) {
            WbMode.AUTO -> if (s.wb.awbLock) opts[CaptureRequest.CONTROL_AWB_LOCK] = true
            WbMode.KELVIN -> {
                val base = kelvinBase
                if (base == null) {
                    opts[CaptureRequest.CONTROL_AWB_MODE] = CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
                    calibrateKelvin()
                } else {
                    opts[CaptureRequest.CONTROL_AWB_MODE] = CameraMetadata.CONTROL_AWB_MODE_OFF
                    opts[CaptureRequest.COLOR_CORRECTION_MODE] = CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
                    opts[CaptureRequest.COLOR_CORRECTION_TRANSFORM] = base.second
                    opts[CaptureRequest.COLOR_CORRECTION_GAINS] = Kelvin.gainsFor(s.wb.kelvin, base.first)
                }
            }
            else -> opts[CaptureRequest.CONTROL_AWB_MODE] = s.wb.mode.awb
        }
        // Auto-quality layer (auto exposure only): face-priority 3A, HDR scene for stills, ISP detail rendering.
        if (e.program == ExposureProgram.P && !nightLapse) {
            val faceOk = s.faceDetect && caps?.c2(key.cameraId)?.let { true } == true
            when {
                !key.video && s.autoHdr -> {
                    opts[CaptureRequest.CONTROL_MODE] = CameraMetadata.CONTROL_MODE_USE_SCENE_MODE
                    opts[CaptureRequest.CONTROL_SCENE_MODE] = CameraMetadata.CONTROL_SCENE_MODE_HDR
                }
                faceOk -> {
                    opts[CaptureRequest.CONTROL_MODE] = CameraMetadata.CONTROL_MODE_USE_SCENE_MODE
                    opts[CaptureRequest.CONTROL_SCENE_MODE] = CameraMetadata.CONTROL_SCENE_MODE_FACE_PRIORITY
                }
            }
        }
        if (s.faceDetect) opts[CaptureRequest.STATISTICS_FACE_DETECT_MODE] = CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE
        if (s.detail == com.daya.velum.settings.Detail.NATURAL) {
            // FAST keeps a light edge enhancement; OFF measured visibly soft on this ISP.
            opts[CaptureRequest.EDGE_MODE] = CameraMetadata.EDGE_MODE_FAST
            opts[CaptureRequest.NOISE_REDUCTION_MODE] = CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
        }
        leOverride?.let { opts.putAll(it) }
        if (opts != appliedOptions) {
            val b = CaptureRequestOptions.Builder()
            @Suppress("UNCHECKED_CAST")
            for ((k, v) in opts) b.setCaptureRequestOption(k as CaptureRequest.Key<Any>, v as Any)
            Camera2CameraControl.from(cc).setCaptureRequestOptions(b.build())
            appliedOptions = opts
        }
        pushRender(s, key)
    }

    private fun pushRender(s: CameraSettings, key: BindKey) {
        processor.horizonOn = s.horizonLock && s.mode in HORIZON_MODES && key.bake
        val look = s.look
        val lookOn = look.lookId != "neutral"
        val m = s.monitor
        val lookBaked = key.video && (look.target == LookTarget.BAKED || s.mode == CaptureMode.VLOG)
        val fx = s.effects
        val effect = if (s.mode != CaptureMode.EFFECTS) LiveEffect.NONE else when (fx.effect) {
            MovieEffect.SLOW_SHUTTER -> LiveEffect.SLOW_SHUTTER
            MovieEffect.TIME_FREEZE -> LiveEffect.TIME_FREEZE
            MovieEffect.PARALLEL -> LiveEffect.MIRROR_LR
            else -> LiveEffect.NONE
        }
        val every = if (s.mode != CaptureMode.EFFECTS) 1 else when (fx.effect) {
            MovieEffect.TIMELAPSE -> fx.speed
            MovieEffect.NIGHT_TIMELAPSE -> maxOf(1, Math.round(fx.speed * 8f / 30f))
            else -> 1
        }
        processor.state = processor.state.copy(
            lookOnPreview = lookOn,
            lookOnVideo = key.bake && lookOn && lookBaked,
            intensity = look.intensity,
            monitor = MonitorFlags(zebra = m.zebra, zebraThreshold = m.zebraPercent / 100f, peaking = m.peaking, falseColor = m.falseColor, grey = m.grey),
            recordedGraded = lookOn && ((key.video && lookBaked) || (!key.video && look.target == LookTarget.BAKED)),
            effect = effect,
            trail = 0.03f + (1f - fx.trail) * 0.27f,
            timelapseEvery = every,
            dof = if (s.mode == CaptureMode.MOVIE) processor.state.dof.copy(enabled = s.movie.blur > 0.01f, strength = s.movie.blur) else DofState(),
            reframe = if (s.mode == CaptureMode.MOVIE && s.movie.smartFraming) processor.state.reframe else Reframe(),
        )
        ml.wantDepth = s.mode == CaptureMode.MOVIE && s.movie.blur > 0.01f
        ml.wantMask = ml.wantDepth || (s.mode == CaptureMode.EFFECTS && fx.effect == MovieEffect.TIME_FREEZE)
        if (!ml.wantDepth && !ml.wantMask && !ml.wantTrack) { processor.mlListener = null } else processor.mlListener = { ml.submit(it) }
    }

    /** Movie-mode controller hooks: focus plane, framing and tracking (transient, not settings). */
    fun setFocusDepth(d: Float) { processor.state = processor.state.copy(dof = processor.state.dof.copy(focusDepth = d)) }
    fun setReframe(r: Reframe) { if (current.mode == CaptureMode.MOVIE && current.movie.smartFraming) processor.state = processor.state.copy(reframe = r) }
    fun enableTracking(on: Boolean) {
        if (!on) ml.stopTracking()
        processor.mlListener = if (on || ml.wantDepth || ml.wantMask) ({ ml.submit(it) }) else null
    }
    fun freeze(on: Boolean) { if (on) processor.freezeNow() else processor.releaseFreeze() }

    /** Sensor orientation relative to the held device; rotates ML input so people appear upright to the models. */
    private var deviceDeg = 0
    fun setDeviceRotationDegrees(deg: Int) {
        deviceDeg = deg
        // The processor's buffer space already matches the natural (portrait) display orientation, so the model only
        // needs the device rotation (verified on device by dumping the model input). The front camera is mirrored.
        val front = camera?.cameraInfo?.lensFacing == CameraSelector.LENS_FACING_FRONT
        ml.rotation = if (front) (360 - deg) % 360 else deg
    }

    fun setLookExtras(grain: Float, vignette: Float, sharpness: Float = 0f) {
        processor.state = processor.state.copy(grain = grain, vignette = vignette, sharpness = sharpness)
    }

    fun setCompare(on: Boolean) { processor.state = processor.state.copy(compareOriginal = on) }

    /** Shutter priority starting point: keep the exposure product the auto-exposure last chose. */
    private fun seedShutterPriorityIso(shutterNs: Long): Int {
        val m = _meta.value
        val a = caps?.c2(boundKey?.cameraId ?: "0")?.sensor?.isoRange
        val iso = if (m.iso != null && m.exposureNs != null && m.exposureNs > 0) (m.iso * m.exposureNs.toDouble() / shutterNs).roundToInt() else 400
        return (a?.let { iso.coerceIn(it.lower, it.upper) } ?: iso).also { sIso = it }
    }

    /**
     * Closed-loop ISO for shutter priority: the GPU meter reports mean linear luma of the recorded signal; ISO moves
     * toward 18% grey (+EV offset) with damping and a dead band so the image doesn't pump.
     */
    private fun onStats(st: FrameStats) {
        val s = current
        val nightLapse = s.mode == CaptureMode.EFFECTS && s.effects.effect == MovieEffect.NIGHT_TIMELAPSE
        if ((s.exposure.program != ExposureProgram.S && !nightLapse) || boundKey?.effect != true) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSAdjustMs < 250) return
        val actual = _meta.value.iso ?: return
        val evStep = caps?.c2(boundKey?.cameraId ?: "0")?.evStep ?: 0.1f
        val target = 0.18f * 2f.pow(s.exposure.evIndex * evStep)
        val mean = st.meanLinear.coerceAtLeast(0.002f)
        val err = ln(target / mean) / ln(2f)
        if (abs(err) < 0.12f) return
        val range = caps?.c2(boundKey?.cameraId ?: "0")?.sensor?.isoRange ?: return
        val next = (actual * 2f.pow(err.coerceIn(-1.5f, 1.5f) * 0.6f)).roundToInt().coerceIn(range.lower, range.upper)
        if (next == sIso) return
        sIso = next
        lastSAdjustMs = now
        applyControls(s)
    }

    private fun calibrateKelvin() {
        if (kelvinCalibrating) return
        kelvinCalibrating = true
        scope.launch {
            delay(700) // let daylight AWB settle
            val m = _meta.value
            if (m.gains != null && m.transform != null) kelvinBase = m.gains to m.transform
            else events.tryEmit(EngineEvent.Notice("Kelvin unavailable: camera did not report colour gains."))
            kelvinCalibrating = false
            appliedOptions = emptyMap()
            applyControls(current)
        }
    }

    fun focusAt(point: MeteringPoint) {
        val cam = camera ?: return
        val s = current
        var flags = 0
        if (!s.focus.manual && !s.focus.afLock) flags = flags or FocusMeteringAction.FLAG_AF
        if (s.exposure.program == ExposureProgram.P && !s.exposure.aeLock) flags = flags or FocusMeteringAction.FLAG_AE
        if (s.wb.mode == WbMode.AUTO && !s.wb.awbLock) flags = flags or FocusMeteringAction.FLAG_AWB
        if (flags == 0) return
        val action = FocusMeteringAction.Builder(point, flags).setAutoCancelDuration(4, java.util.concurrent.TimeUnit.SECONDS).build()
        if (!cam.cameraInfo.isFocusMeteringSupported(action)) return
        val f = cam.cameraControl.startFocusAndMetering(action)
        f.addListener({ events.tryEmit(EngineEvent.Focused(runCatching { f.get().isFocusSuccessful }.getOrDefault(false))) }, mainExec)
    }

    private val resultCallback = object : CameraCaptureSession.CaptureCallback() {
        private var last = 0L
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val now = SystemClock.elapsedRealtime()
            if (now - last < 90) return
            last = now
            _meta.value = LiveMeta(
                iso = result.get(CaptureResult.SENSOR_SENSITIVITY),
                exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                frameDurationNs = result.get(CaptureResult.SENSOR_FRAME_DURATION),
                focusDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE),
                aperture = result.get(CaptureResult.LENS_APERTURE),
                aeState = result.get(CaptureResult.CONTROL_AE_STATE),
                afState = result.get(CaptureResult.CONTROL_AF_STATE),
                awbState = result.get(CaptureResult.CONTROL_AWB_STATE),
                activePhysicalId = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID),
                physicalSources = runCatching { result.physicalCameraTotalResults.keys.sorted() }.getOrDefault(emptyList()),
                zoomRatio = result.get(CaptureResult.CONTROL_ZOOM_RATIO),
                ois = result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)?.let { it == CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON },
                videoStab = result.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE),
                gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS),
                transform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM),
                faces = result.get(CaptureResult.STATISTICS_FACES)?.filter { it.score >= 30 }?.map { it.bounds }.orEmpty(),
                timestampMs = now,
            )
        }
    }

    // ------------------------------------------------------------------ photo

    /**
     * Captures with CameraX writing straight to MediaStore (EXIF by CameraX). Returns the saved URIs; a JPEG is only
     * reported once it decodes. RAW+JPEG yields two URIs.
     */
    suspend fun takePhoto(rotation: Int, retry: Boolean = true): List<Uri> {
        // A shutter press that lands while the camera is switching (mode, lens) waits for the new session.
        if (imageCapture == null && retry) withTimeoutOrNull(3000) { while (imageCapture == null) delay(50) }
        val ic = imageCapture ?: run { events.tryEmit(EngineEvent.PhotoFailed("Camera not ready")); return emptyList() }
        ic.targetRotation = rotation
        val base = media.newBaseName("VLM")
        val raw = boundKey?.photoFormat == PhotoFormat.RAW_JPEG && boundKey?.video == false
        val done = CompletableDeferred<List<Uri>>()
        val uris = mutableListOf<Uri>()
        val expected = if (raw) 2 else 1
        _capturing.value++
        val cb = object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                output.savedUri?.let { synchronized(uris) { uris += it } }
                if (synchronized(uris) { uris.size } >= expected) done.complete(uris.toList())
            }
            override fun onError(exc: ImageCaptureException) {
                Log.e(TAG, "capture failed", exc)
                done.completeExceptionally(exc)
            }
        }
        try {
            if (raw) ic.takePicture(media.photoOutputOptions("$base", "image/x-adobe-dng"), media.photoOutputOptions(base, "image/jpeg"), ioExec, cb)
            else ic.takePicture(media.photoOutputOptions(base, "image/jpeg"), ioExec, cb)
            val result = withTimeoutOrNull(20_000) { done.await() } ?: run {
                events.tryEmit(EngineEvent.PhotoFailed("Capture timed out")); return emptyList()
            }
            val jpeg = result.firstOrNull { context.contentResolver.getType(it)?.contains("jpeg") == true } ?: result.first()
            val size = media.probeImage(jpeg)
            if (size == null) {
                events.tryEmit(EngineEvent.PhotoFailed("Saved photo could not be decoded; it was removed."))
                result.forEach { media.delete(it) }
                return emptyList()
            }
            events.tryEmit(EngineEvent.PhotoSaved(jpeg, size.width, size.height, if (raw) "RAW+JPEG" else (boundKey?.photoFormat?.label ?: "JPEG")))
            return result
        } catch (t: ImageCaptureException) {
            // The session closed under the capture (a rebind started): retry once on the new session.
            if (retry && t.imageCaptureError == ImageCapture.ERROR_CAMERA_CLOSED) {
                withTimeoutOrNull(3000) { while (imageCapture == null || imageCapture === ic) delay(50) }
                if (imageCapture != null && imageCapture !== ic) { _capturing.value--; return takePhoto(rotation, retry = false).also { _capturing.value++ } }
            }
            events.tryEmit(EngineEvent.PhotoFailed(t.message ?: "Capture failed (${t.imageCaptureError})"))
            return emptyList()
        } finally {
            _capturing.value--
        }
    }

    /** Applies EV offsets one by one (P) or scales shutter (M/S) and captures each frame. */
    suspend fun bracket(rotation: Int, stops: List<Float>): List<Uri> {
        val s = current
        val out = mutableListOf<Uri>()
        val step = caps?.c2(boundKey?.cameraId ?: "0")?.evStep ?: 0.1f
        for (stop in stops) {
            val adj = if (s.exposure.program == ExposureProgram.P) {
                s.copy(exposure = s.exposure.copy(evIndex = (s.exposure.evIndex + stop / step).roundToInt()))
            } else s.copy(exposure = s.exposure.copy(shutterNs = (s.exposure.shutterNs * 2.0.pow(stop.toDouble())).toLong()))
            apply(com.daya.velum.settings.SettingsValidator.validate(caps, adj).settings)
            delay(450)
            out += takePhoto(rotation)
        }
        apply(s)
        return out
    }

    // ------------------------------------------------------------------ video

    /** Records to a private [file] that stops by itself after [limitMs] (Vlog shots). */
    @SuppressLint("MissingPermission")
    fun startRecordingToFile(rotation: Int, withAudio: Boolean, file: java.io.File, limitMs: Long) = startRecording(rotation, withAudio, file to limitMs)

    fun startRecording(rotation: Int, withAudio: Boolean) = startRecording(rotation, withAudio, null)

    @SuppressLint("MissingPermission")
    private fun startRecording(rotation: Int, withAudio: Boolean, target: Pair<java.io.File, Long>?) {
        val vc = videoCapture ?: run { events.tryEmit(EngineEvent.Notice("Video is not ready")); return }
        val next = RecordingStateMachine.reduce(_rec.value, RecEvent.StartRequested) ?: return
        val free = media.freeBytes()
        if (free in 0..400_000_000L) {
            _rec.value = RecState.Error("Not enough free storage (${free / 1_000_000} MB).")
            return
        }
        _rec.value = next
        vc.targetRotation = rotation
        processor.resetTimelapseClock()
        processor.releaseFreeze()
        val pending = if (target != null) {
            val fo = androidx.camera.video.FileOutputOptions.Builder(target.first).setDurationLimitMillis(target.second).build()
            vc.output.prepareRecording(context, fo)
        } else vc.output.prepareRecording(context, media.videoOutputOptions(media.newBaseName("VLM")))
        pending.apply { if (withAudio) withAudioEnabled() }
        recording = pending.start(mainExec) { ev -> onRecordEvent(ev) }
    }

    fun pauseRecording() { if (_rec.value is RecState.Recording) recording?.pause() }
    fun resumeRecording() { if (_rec.value is RecState.Paused) recording?.resume() }
    fun stopRecording() {
        val next = RecordingStateMachine.reduce(_rec.value, RecEvent.StopRequested) ?: return
        _rec.value = next
        recording?.stop()
    }
    fun acknowledgeRecording() { RecordingStateMachine.reduce(_rec.value, RecEvent.Reset)?.let { _rec.value = it } }

    private fun onRecordEvent(ev: VideoRecordEvent) {
        fun step(e: RecEvent) { RecordingStateMachine.reduce(_rec.value, e)?.let { _rec.value = it } }
        when (ev) {
            is VideoRecordEvent.Start -> step(RecEvent.Started(System.currentTimeMillis()))
            is VideoRecordEvent.Status -> step(RecEvent.Status(ev.recordingStats.recordedDurationNanos, ev.recordingStats.numBytesRecorded, ev.recordingStats.audioStats.audioAmplitude))
            is VideoRecordEvent.Pause -> step(RecEvent.Paused)
            is VideoRecordEvent.Resume -> step(RecEvent.Resumed)
            is VideoRecordEvent.Finalize -> {
                recording = null
                val uri = ev.outputResults.outputUri.takeIf { it != Uri.EMPTY }
                val note = if (ev.hasError() && ev.error != VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED) recordErrorText(ev.error, ev.cause) else null
                if (uri == null) { step(RecEvent.Finalized(null, ev.error, note)); return }
                _rec.value = RecState.Finalizing
                scope.launch {
                    val probe = media.probeVideo(uri)
                    if (probe.ok) step(RecEvent.Verified(uri, probe.durationMs, probe.width, probe.height, note))
                    else {
                        media.delete(uri)
                        step(RecEvent.VerifyFailed(null, "Recording did not finalise into a playable file${note?.let { ": $it" } ?: ""}."))
                    }
                }
            }
        }
    }

    private fun recordErrorText(code: Int, cause: Throwable?): String = when (code) {
        VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE -> "Stopped: storage full"
        VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE -> "Stopped: camera closed (app left foreground)"
        VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED -> "Stopped: duration limit"
        VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED -> "Stopped: file size limit"
        VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED -> "Encoder failed"
        VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA -> "No valid data recorded"
        VideoRecordEvent.Finalize.ERROR_RECORDER_ERROR -> "Recorder error"
        VideoRecordEvent.Finalize.ERROR_INVALID_OUTPUT_OPTIONS -> "Invalid output"
        else -> "Error $code${cause?.message?.let { ": $it" } ?: ""}"
    }
}

/** Approximate Kelvin → RGGB gains relative to a daylight (5500 K) AWB calibration. */
object Kelvin {
    /** Linear sRGB colour of a blackbody at [k] kelvin (Tanner Helland fit), normalised to green = 1. */
    fun white(k: Int): FloatArray {
        val t = k / 100.0
        val r = if (t <= 66) 255.0 else 329.698727446 * (t - 60).pow(-0.1332047592)
        val g = if (t <= 66) 99.4708025861 * ln(t) - 161.1195681661 else 288.1221695283 * (t - 60).pow(-0.0755148492)
        val b = when { t >= 66 -> 255.0; t <= 19 -> 0.0; else -> 138.5177312231 * ln(t - 10) - 305.0447927307 }
        fun lin(v: Double) = com.daya.velum.imaging.LookMath.srgbToLinear((v.coerceIn(1.0, 255.0) / 255.0).toFloat())
        val lr = lin(r); val lg = lin(g); val lb = lin(b)
        return floatArrayOf(lr / lg, 1f, lb / lg)
    }

    /** Gains that neutralise light of temperature [k], given the gains the camera chose for daylight. */
    fun gainsFor(k: Int, daylight: RggbChannelVector): RggbChannelVector {
        val d = white(5500); val w = white(k)
        val rs = d[0] / w[0]; val bs = d[2] / w[2]
        return RggbChannelVector(daylight.red * rs, daylight.greenEven, daylight.greenOdd, daylight.blue * bs)
    }
}
