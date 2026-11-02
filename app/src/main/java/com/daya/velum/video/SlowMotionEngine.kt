package com.daya.velum.video

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaRecorder
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import com.daya.velum.media.ALBUM
import com.daya.velum.media.MediaRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val TAG = "Velum/SlowMo"

sealed interface SlowMoState {
    data object Closed : SlowMoState
    data object Opening : SlowMoState
    data class Ready(val fps: Int, val size: Size) : SlowMoState
    data class Recording(val fps: Int, val startedMs: Long) : SlowMoState
    data class Saved(val uri: Uri) : SlowMoState
    data class Error(val message: String) : SlowMoState
}

/**
 * True slow motion with a Camera2 constrained high-speed session (CameraX exposes no high-speed profile on this
 * device). Frames are captured at [fps] and written by MediaRecorder with captureRate = fps and frameRate = 30, so the
 * file plays back slowed down (8× at 240 fps) in any player. A persistent encoder surface keeps one session alive
 * across recordings. CameraX must be unbound while this engine owns the camera.
 */
class SlowMotionEngine(private val context: Context, private val media: MediaRepository) {
    private val thread = HandlerThread("VelumSlowMo").apply { start() }
    private val handler = Handler(thread.looper)
    private val manager = context.getSystemService(CameraManager::class.java)
    private val _state = object {
        private val f = MutableStateFlow<SlowMoState>(SlowMoState.Closed)
        var value: SlowMoState
            get() = f.value
            set(v) { if (v != f.value) Log.i(TAG, "state ${f.value::class.simpleName} -> $v"); f.value = v }
        fun flow(): StateFlow<SlowMoState> = f
    }
    val state: StateFlow<SlowMoState> = _state.flow()
    /** One event per finished take (StateFlow would conflate Saved→Ready). */
    val saved = kotlinx.coroutines.flow.MutableSharedFlow<Uri>(extraBufferCapacity = 4)

    private var device: CameraDevice? = null
    private var session: CameraConstrainedHighSpeedCaptureSession? = null
    private var previewSurface: Surface? = null
    private var encoderSurface: Surface? = null
    private var recorder: MediaRecorder? = null
    private var fps = 240
    private var size = Size(1920, 1080)
    private var cameraId = "0"
    private var orientationHint = 90

    /** Highest supported high-speed config for [preferFps] on [id], or null. */
    fun supported(id: String, preferFps: Int): Pair<Size, Int>? = runCatching {
        val map = manager.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val sizes = map.highSpeedVideoSizes.sortedByDescending { it.width * it.height }
        for (s in sizes) {
            val fpsList = map.getHighSpeedVideoFpsRangesFor(s).filter { it.lower == it.upper }.map { it.upper }
            val pick = fpsList.filter { it <= preferFps }.maxOrNull() ?: continue
            if (s.width >= 1280) return@runCatching s to pick
        }
        null
    }.getOrNull()

    @SuppressLint("MissingPermission")
    fun open(id: String, wantFps: Int, preview: SurfaceTexture, deviceRotationDeg: Int) {
        if (_state.value !is SlowMoState.Closed && _state.value !is SlowMoState.Error) return
        val cfg = supported(id, wantFps) ?: run { _state.value = SlowMoState.Error("This lens has no high-speed mode."); return }
        cameraId = id; size = cfg.first; fps = cfg.second
        val sensor = manager.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        orientationHint = (sensor - deviceRotationDeg + 360) % 360
        _state.value = SlowMoState.Opening
        preview.setDefaultBufferSize(size.width, size.height)
        previewSurface = Surface(preview)
        encoderSurface = MediaCodec.createPersistentInputSurface()
        // CameraX releases the device asynchronously; open only once the framework reports it free.
        val cb = object : CameraManager.AvailabilityCallback() {
            override fun onCameraAvailable(cid: String) {
                if (cid != cameraId) return
                manager.unregisterAvailabilityCallback(this)
                openWithRetry(12)
            }
        }
        availability = cb
        manager.registerAvailabilityCallback(cb, handler)
        handler.postDelayed({ if (availability === cb) { manager.unregisterAvailabilityCallback(cb); availability = null; if (device == null) openWithRetry(12) } }, 2500)
    }
    private var availability: CameraManager.AvailabilityCallback? = null

    @SuppressLint("MissingPermission")
    private fun openWithRetry(left: Int) {
        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { availability = null; device = d; prepareRecorder(); createSession() }
                override fun onDisconnected(d: CameraDevice) { Log.w(TAG, "disconnected"); d.close(); device = null; _state.value = SlowMoState.Closed }
                override fun onError(d: CameraDevice, error: Int) {
                    Log.w(TAG, "device error $error (retries left $left)")
                    d.close(); device = null
                    if (left > 0) handler.postDelayed({ openWithRetry(left - 1) }, 600) else _state.value = SlowMoState.Error("Camera error $error")
                }
            }, handler)
        } catch (t: Throwable) {
            if (left > 0) handler.postDelayed({ openWithRetry(left - 1) }, 600) else _state.value = SlowMoState.Error(t.message ?: "open failed")
        }
    }

    private var rawFile: java.io.File? = null

    /**
     * A fresh MediaRecorder bound to the persistent surface, writing the native high-frame-rate stream to a private
     * temp file. (MediaRecorder's capture-rate mode drops frames on this codec path, so slowing is done afterwards.)
     */
    private fun prepareRecorder() {
        rawFile?.delete()
        val f = java.io.File(context.cacheDir, "slomo_raw_${System.currentTimeMillis()}.mp4")
        rawFile = f
        recorder = MediaRecorder(context).apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(f.absolutePath)
            setVideoEncoder(MediaRecorder.VideoEncoder.HEVC)
            setVideoSize(size.width, size.height)
            setVideoEncodingBitRate(if (fps >= 240) 90_000_000 else 60_000_000)
            setVideoFrameRate(fps)
            setOrientationHint(orientationHint)
            setInputSurface(encoderSurface!!)
            prepare()
        }
    }

    /** Lossless retime: every captured frame is kept and spaced at 30 fps, so playback is fps/30 times slower. */
    private fun retimeToMediaStore(raw: java.io.File): Uri {
        val name = media.newBaseName("VLM_SLOMO")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/" + ALBUM)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val cr = context.contentResolver
        val uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore insert failed")
        try {
            cr.openFileDescriptor(uri, "rw")!!.use { pfd ->
                val ex = android.media.MediaExtractor().apply { setDataSource(raw.absolutePath) }
                val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)!!.startsWith("video/") }
                val fmt = ex.getTrackFormat(track)
                fmt.setInteger(android.media.MediaFormat.KEY_FRAME_RATE, 30)
                val mux = android.media.MediaMuxer(pfd.fileDescriptor, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                mux.setOrientationHint(orientationHint)
                val out = mux.addTrack(fmt)
                mux.start()
                ex.selectTrack(track)
                val buf = java.nio.ByteBuffer.allocate(4 * 1024 * 1024)
                val info = MediaCodec.BufferInfo()
                val k = fps / 30.0
                var first = -1L
                while (true) {
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) break
                    if (first < 0) first = ex.sampleTime
                    info.set(0, n, ((ex.sampleTime - first) * k).toLong(),
                        if (ex.sampleFlags and android.media.MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    mux.writeSampleData(out, buf, info)
                    ex.advance()
                }
                mux.stop(); mux.release(); ex.release()
            }
            cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } catch (t: Throwable) { cr.delete(uri, null, null); throw t }
    }

    private fun createSession() {
        val d = device ?: return
        val outs = listOf(previewSurface!!, encoderSurface!!)
        @Suppress("DEPRECATION")
        d.createConstrainedHighSpeedCaptureSession(outs, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s as CameraConstrainedHighSpeedCaptureSession
                repeat(recording = false)
                _state.value = SlowMoState.Ready(fps, size)
            }
            override fun onConfigureFailed(s: CameraCaptureSession) { _state.value = SlowMoState.Error("High-speed session rejected") }
        }, handler)
    }

    private fun repeat(recording: Boolean) {
        val s = session ?: return
        val d = device ?: return
        val b = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(previewSurface!!)
            if (recording) addTarget(encoderSurface!!)
            // Preview-only bursts use a variable (30, N) range; fixed (N, N) only while the encoder is a target.
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, if (recording) Range(fps, fps) else Range(30, fps))
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }
        s.setRepeatingBurst(s.createHighSpeedRequestList(b.build()), null, handler)
    }

    fun start() = handler.post {
        if (_state.value !is SlowMoState.Ready) return@post
        try {
            if (recorder == null) prepareRecorder()
            recorder!!.start()
            repeat(recording = true)
            _state.value = SlowMoState.Recording(fps, System.currentTimeMillis())
        } catch (t: Throwable) { Log.e(TAG, "start failed", t); _state.value = SlowMoState.Error("Could not start: ${t.message}") }
    }

    fun stop() = handler.post {
        if (_state.value !is SlowMoState.Recording) return@post
        repeat(recording = false)
        val raw = rawFile
        try {
            recorder?.stop()
        } catch (t: Throwable) {
            Log.e(TAG, "stop failed", t)
            _state.value = SlowMoState.Error("Recording too short or failed")
            recorder?.release(); recorder = null; raw?.delete(); rawFile = null
            runCatching { prepareRecorder() }
            _state.value = SlowMoState.Ready(fps, size)
            return@post
        }
        recorder?.release(); recorder = null
        rawFile = null
        if (raw != null) {
            try { saved.tryEmit(retimeToMediaStore(raw)) }
            catch (t: Throwable) { Log.e(TAG, "retime failed", t); _state.value = SlowMoState.Error("Could not save slow motion: ${t.message}") }
            finally { raw.delete() }
        }
        // Ready for the next take on the same session.
        runCatching { prepareRecorder() }.onFailure { Log.e(TAG, "re-prepare failed", it) }
        _state.value = SlowMoState.Ready(fps, size)
    }

    fun close() = handler.post {
        Log.i(TAG, "close requested in ${_state.value}")
        runCatching { if (_state.value is SlowMoState.Recording) recorder?.stop() }
        runCatching { session?.close() }; session = null
        runCatching { device?.close() }; device = null
        recorder?.release(); recorder = null
        rawFile?.delete(); rawFile = null
        encoderSurface?.release(); encoderSurface = null
        previewSurface?.release(); previewSurface = null
        _state.value = SlowMoState.Closed
    }

    fun previewSize() = size
}
