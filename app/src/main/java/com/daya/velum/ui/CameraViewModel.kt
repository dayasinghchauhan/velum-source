package com.daya.velum.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.daya.velum.VelumApp
import com.daya.velum.camera.EngineEvent
import com.daya.velum.capability.DeviceCapabilities
import com.daya.velum.imaging.Look
import com.daya.velum.imaging.LookParams
import com.daya.velum.media.MediaKind
import com.daya.velum.presets.PresetStore
import com.daya.velum.presets.Recipe
import com.daya.velum.presets.RecipeEngine
import com.daya.velum.presets.UserPreset
import com.daya.velum.settings.*
import com.daya.velum.video.RecState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withLock

private const val TAG = "Velum/VM"

/** UI-facing notices: short, factual, auto-dismissed. */
data class Notice(val text: String, val id: Long = System.nanoTime())

class CameraViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app as VelumApp
    val engine = c.engine
    val looks = c.looks
    val presets = c.presets
    val media = c.media

    private val _settings = MutableStateFlow(CameraSettings())
    val settings: StateFlow<CameraSettings> = _settings.asStateFlow()
    val caps: StateFlow<DeviceCapabilities?> = c.caps.capabilities
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready
    val notices = MutableSharedFlow<Notice>(extraBufferCapacity = 8)
    private val _thumb = MutableStateFlow<Pair<Uri, Bitmap?>?>(null)
    val thumb: StateFlow<Pair<Uri, Bitmap?>?> = _thumb
    private val _countdown = MutableStateFlow(0)
    val countdown: StateFlow<Int> = _countdown
    private val _grading = MutableStateFlow(0)
    val grading: StateFlow<Int> = _grading
    private val _flash = MutableStateFlow(0L)
    /** Bumps on each shutter release for the capture animation. */
    val shutterFlash: StateFlow<Long> = _flash

    private var saveJob: Job? = null
    private var timerJob: Job? = null
    private var burstJob: Job? = null
    private val gradeLock = Mutex()
    var deviceRotation = android.view.Surface.ROTATION_0
        set(v) {
            field = v
            engine.motionRotation = v
            engine.setDeviceRotationDegrees(when (v) { android.view.Surface.ROTATION_90 -> 90; android.view.Surface.ROTATION_180 -> 180; android.view.Surface.ROTATION_270 -> 270; else -> 0 })
        }
    val movie = com.daya.velum.camera.MovieController(engine, viewModelScope)
    val slowMo = c.slowMo
    val deviceRotationDegrees get() = when (deviceRotation) { android.view.Surface.ROTATION_90 -> 90; android.view.Surface.ROTATION_180 -> 180; android.view.Surface.ROTATION_270 -> 270; else -> 0 }
    private var frozen = false
    private val sound = android.media.MediaActionSound().apply {
        load(android.media.MediaActionSound.SHUTTER_CLICK)
        load(android.media.MediaActionSound.START_VIDEO_RECORDING)
        load(android.media.MediaActionSound.STOP_VIDEO_RECORDING)
    }

    private fun play(kind: Int) {
        // Some regions require the sound regardless; CameraInfo.mustPlayShutterSound() reports that.
        if (_settings.value.shutterSound || androidx.camera.core.CameraInfo.mustPlayShutterSound()) sound.play(kind)
    }

    val availability get() = SettingsValidator.availability(caps.value, _settings.value)

    init {
        viewModelScope.launch {
            looks.load(); presets.load()
            _settings.value = c.settingsRepo.load()
        }
        viewModelScope.launch {
            engine.events.collect { ev ->
                when (ev) {
                    is EngineEvent.PhotoSaved -> onPhotoSaved(ev.uri)
                    is EngineEvent.PhotoFailed -> notify(ev.message)
                    is EngineEvent.Notice -> notify(ev.message)
                    is EngineEvent.Focused -> {}
                    is EngineEvent.Downgraded -> {
                        update { s -> s.copy(video = s.video.copy(
                            stabilization = ev.stabilization ?: s.video.stabilization,
                            dynamicRange = if (ev.sdr) DynamicRangeMode.SDR else s.video.dynamicRange)) }
                        notify(ev.message)
                    }
                }
            }
        }
        // Files left unfinished when the app was closed mid-save (slow motion retime, recordings) show as broken
        // entries in other gallery apps.
        viewModelScope.launch { media.deletePendingLeftovers() }
        watchHeat()
        viewModelScope.launch {
            slowMo.saved.collect { uri ->
                val p = media.probeVideo(uri)
                if (p.ok) { refreshThumb(uri); notify("Slow motion saved (%.1f s playback)".format(p.durationMs / 1000f)) }
                else { media.delete(uri); notify("Slow motion file did not finalise; removed.") }
            }
        }
        viewModelScope.launch {
            engine.rec.collect { r ->
                if (r is RecState.Saved) {
                    if (pendingVlogFile != null) onVlogSaved() else { refreshThumb(r.uri); r.note?.let { notify("Saved. $it") } }
                }
                if (r is RecState.Error && pendingVlogFile != null) { pendingVlogFile = null; _vlog.value = _vlog.value.copy(shooting = false) }
                if (r is RecState.Error) notify(r.message)
            }
        }
        refreshThumbLatest()
    }

    /** Called once the camera provider exists: discovers capabilities, then validates the restored settings. */
    fun onProviderReady() = viewModelScope.launch {
        val p = engine.init()
        val caps = c.caps.refresh(p)
        val v = SettingsValidator.validate(caps, _settings.value)
        v.changes.forEach(::notify)
        _settings.value = v.settings
        applyLookToEngine(v.settings)
        _ready.value = true
        engine.apply(v.settings)
        if (v.settings.mode == CaptureMode.MOVIE) movie.start()
    }

    fun notify(t: String) { notices.tryEmit(Notice(t)) }

    // ---------------------------------------------------------------- settings mutation

    fun update(f: (CameraSettings) -> CameraSettings) {
        val before = _settings.value
        val v = SettingsValidator.validate(caps.value, f(before))
        v.changes.forEach(::notify)
        val s = v.settings
        _settings.value = s
        if (s.look != before.look || s.mode.isVideo != before.mode.isVideo) applyLookToEngine(s)
        if (_ready.value) engine.apply(s)
        if (s.mode == CaptureMode.MOVIE) movie.start() else if (before.mode == CaptureMode.MOVIE) movie.stop()
        saveJob?.cancel()
        saveJob = viewModelScope.launch { delay(600); c.settingsRepo.save(_settings.value) }
    }

    private fun applyLookToEngine(s: CameraSettings) = viewModelScope.launch {
        val look = looks.byId(s.look.lookId) ?: looks.byId("neutral")!!
        engine.processor.setLut(looks.lutFor(look))
        engine.setLookExtras(look.params.grain, look.params.vignette, look.params.sharpness)
    }

    fun setMode(m: CaptureMode) {
        if (engine.rec.value.isActive) { notify("Stop recording to change mode."); return }
        if (_settings.value.mode == CaptureMode.CLONE && m != CaptureMode.CLONE) cloneReset()
        update {
            var s = it.copy(mode = m)
            if (!m.isPro) s = s.copy(exposure = s.exposure.copy(program = ExposureProgram.P), focus = s.focus.copy(manual = false))
            // Macro frames the ultra-wide at about 1.5x, a working distance of a few centimetres; leaving restores the lens zoom.
            if (m == CaptureMode.MACRO) s = s.copy(zoom = 1.5f)
            else if (it.mode == CaptureMode.MACRO) s = s.copy(zoom = caps.value?.lenses?.firstOrNull { l -> l.key == s.lensKey }?.zoomRatio ?: 1f)
            s
        }
    }

    fun selectLens(key: String) {
        val lens = caps.value?.lenses?.firstOrNull { it.key == key } ?: return
        val cur = caps.value?.lenses?.firstOrNull { it.key == _settings.value.lensKey }
        if (engine.rec.value.isActive && cur?.logicalId != lens.logicalId) { notify("Switching camera would end the recording."); return }
        // A pinned physical sensor already frames at its own focal length, so zoom restarts at 1.0 on it.
        update { it.copy(lensKey = key, zoom = if (it.lockPhysicalLens && lens.physicalId != null) 1f else lens.zoomRatio) }
    }

    fun setZoom(z: Float) = update { it.copy(zoom = z) }

    fun flipCamera() {
        val c = caps.value ?: return
        val cur = c.lenses.firstOrNull { it.key == _settings.value.lensKey }
        val target = c.lenses.firstOrNull { it.facing != cur?.facing && (it.role.name == "MAIN" || it.role.name == "FRONT") } ?: return
        if (engine.rec.value.isActive) { notify("Stop recording to switch cameras."); return }
        update { it.copy(lensKey = target.key, zoom = target.zoomRatio) }
    }

    fun cycleFlash() = update {
        val opts = if (it.mode.isVideo) listOf(FlashMode.OFF, FlashMode.TORCH) else FlashMode.entries
        it.copy(flash = opts[(opts.indexOf(it.flash).coerceAtLeast(0) + 1) % opts.size])
    }

    fun cycleTimer() = update { it.copy(timerSec = when (it.timerSec) { 0 -> 3; 3 -> 10; else -> 0 }) }

    // ---------------------------------------------------------------- capture

    /** Long exposure progress for the UI: elapsed ms, frames, live preview. Null when idle. */
    data class LeProgress(val elapsedMs: Long, val totalMs: Long, val frames: Int, val preview: Bitmap?)
    private val _le = MutableStateFlow<LeProgress?>(null)
    val longExposureProgress: StateFlow<LeProgress?> = _le

    private fun runLongExposure(astro: Boolean = false) {
        if (_le.value != null) return
        val s = _settings.value
        val mode = if (astro) com.daya.velum.imaging.LongExposureMode.STARRY_SKY else s.longExposure
        val secs = when { astro -> s.astroSeconds; s.leSeconds > 0 -> s.leSeconds; else -> mode.seconds }
        val label = if (astro) "Astro" else mode.label
        engine.setAnalysisRotation(deviceRotation)
        viewModelScope.launch {
            _le.value = LeProgress(0, secs * 1000L, 0, null)
            play(android.media.MediaActionSound.START_VIDEO_RECORDING)
            val raw = engine.longExposure(mode, secs, align = astro) { ms, n, prev -> _le.value = LeProgress(ms, secs * 1000L, n, prev ?: _le.value?.preview) }
            val frames = _le.value?.frames ?: 0
            play(android.media.MediaActionSound.STOP_VIDEO_RECORDING)
            if (raw == null) { _le.value = null; notify("$label could not start on this lens."); return@launch }
            try {
                // The stack has the noise of one frame divided by sqrt(frames), so it can be brightened cleanly. The
                // curve is white-preserving: faint stars and sky lift, bright stars do not clip.
                val bmp = if (!astro) raw else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    // Lift by how dark the stack really is (target mean luma ~0.22), never more than 1.4 stops.
                    val stops = (kotlin.math.ln(0.22 / meanLuma(raw).coerceAtLeast(0.01)) / kotlin.math.ln(2.0)).toFloat().coerceIn(0f, 1.4f)
                    if (stops < 0.1f) raw else {
                        val p = com.daya.velum.imaging.LookParams(exposure = stops, shadows = 0.15f * stops, highlightRoll = 0.4f, contrast = 0.08f)
                        com.daya.velum.imaging.CpuLookApplier.apply(raw, com.daya.velum.imaging.Lut3D.fromLook(p), 1f, p).also { raw.recycle() }
                    }
                }
                val note = "Velum ${label.lowercase()}: ${secs}s, $frames frames" + if (astro) ", aligned" else ""
                val uri = saveStill(if (astro) "VLM_ASTRO" else "VLM_LE", bmp, note, s)
                refreshThumb(uri)
                notify("Saved ${label.lowercase()} ($frames frames over ${secs}s)")
            } catch (t: Throwable) {
                Log.e(TAG, "long exposure save failed", t); notify("$label could not be saved: ${t.message}")
            } finally { _le.value = null }
        }
    }

    private fun meanLuma(b: Bitmap): Double {
        var s = 0.0; var n = 0
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) {
                val c = b.getPixel(x, y)
                s += (0.2126 * (c shr 16 and 255) + 0.7152 * (c shr 8 and 255) + 0.0722 * (c and 255)) / 255.0; n++
                x += 32
            }
            y += 32
        }
        return if (n > 0) s / n else 0.0
    }

    /** Saves a computed still, plus a graded copy when a photo look is on (or only the graded one, per settings). */
    private suspend fun saveStill(prefix: String, bmp: Bitmap, note: String, s: CameraSettings): Uri {
        val look = looks.byId(s.photoLook.lookId)
        val graded = if (look != null && !look.isNeutral && s.photoLook.target == LookTarget.BAKED)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                com.daya.velum.imaging.CpuLookApplier.apply(bmp, looks.lutFor(look), s.photoLook.intensity, look.params)
            } else null
        val name = media.newBaseName(prefix)
        val uri = saveBitmapJpeg(name, if (graded != null && !s.photoLook.keepOriginal) graded else bmp, note, s.jpegQuality)
        if (graded != null && s.photoLook.keepOriginal) saveBitmapJpeg(name + "_" + look!!.id.take(12), graded, note + ", look ${look.name}", s.jpegQuality)
        return uri
    }

    // ------------------------------------------------------------------ preset QR codes

    /** Saves a QR card for [p] to the gallery. A custom look's parameters travel with it; an imported .cube does not. */
    fun exportPresetQr(p: com.daya.velum.presets.UserPreset) = viewModelScope.launch {
        runCatching {
            val look = looks.byId(p.settings.look.lookId)
            val custom = look?.takeIf { it.category == com.daya.velum.imaging.LookCategory.CUSTOM }
            val payload = com.daya.velum.presets.PresetQr.encode(p.name, p.settings, custom?.name, custom?.id, custom?.params)
            val summary = "${p.settings.mode.label}, ${look?.name ?: "Neutral"}" +
                if (p.settings.mode.isVideo) ", ${p.settings.video.quality.label} ${p.settings.video.fps} fps" else ""
            val card = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { com.daya.velum.presets.PresetQr.card(p.name, summary, payload) }
            media.saveJpeg(media.newBaseName("VLM_PRESET")) { out -> card.compress(Bitmap.CompressFormat.JPEG, 95, out) }
            card.recycle()
            Log.i(TAG, "preset QR: ${payload.length} chars")
            notify("Saved a QR card for ${p.name} to the gallery" + if (custom?.cubeFile != null) " (its .cube file is not included)" else "")
        }.onFailure { Log.e(TAG, "preset QR export failed", it); notify("QR code could not be made: ${it.message}") }
    }

    /** Reads a Velum preset QR code from an image and adds it to My presets. */
    fun importPresetQr(uri: Uri) = viewModelScope.launch {
        runCatching {
            val bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val cr = getApplication<Application>().contentResolver
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                cr.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
                var sample = 1
                while (maxOf(o.outWidth, o.outHeight) / sample > 3000) sample *= 2
                cr.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) }
                    ?: error("The image could not be opened.")
            }
            val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { com.daya.velum.presets.PresetQr.read(bmp) }
                ?: error("No QR code found in that image.")
            val d = com.daya.velum.presets.PresetQr.decode(text)
            var st = d.settings
            if (d.lookParams != null && d.lookId != null) {
                val l = looks.saveCustom(d.lookName ?: "Imported look", d.lookParams, null)
                fun remap(ls: LookSettings) = if (ls.lookId == d.lookId) ls.copy(lookId = l.id) else ls
                st = st.copy(photoLook = remap(st.photoLook), videoLook = remap(st.videoLook))
            }
            presets.create(d.name, st)
            notify("Imported preset ${d.name}")
        }.onFailure { Log.e(TAG, "preset QR import failed", it); notify(it.message ?: "Import failed") }
    }

    // ------------------------------------------------------------------ best shot / clone

    private val _busy = MutableStateFlow<String?>(null)
    /** A short status line while a computed capture (best shot, clone) is working. */
    val busy: StateFlow<String?> = _busy

    private fun runBestShot() {
        if (_busy.value != null) return
        val s = _settings.value
        engine.setAnalysisRotation(deviceRotation)
        viewModelScope.launch {
            _busy.value = "Hold on: picking the sharpest frame"
            play(android.media.MediaActionSound.SHUTTER_CLICK)
            _flash.value = System.nanoTime()
            try {
                val (seen, ranked) = engine.bestShot(1600, s.bestKeep.coerceIn(1, 3))
                if (ranked.isEmpty()) { notify("Best shot needs the frame stream; try again."); return@launch }
                var last: Uri? = null
                for ((i, pair) in ranked.withIndex()) {
                    val bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { pair.second.toBitmap() }
                    last = saveStill(if (i == 0) "VLM_BEST" else "VLM_BEST${i + 1}", bmp, "Velum best shot: rank ${i + 1} of $seen frames", s)
                    bmp.recycle()
                }
                last?.let { refreshThumb(it) }
                notify(if (ranked.size == 1) "Saved the sharpest of $seen frames" else "Saved the ${ranked.size} sharpest of $seen frames")
            } catch (t: Throwable) {
                Log.e(TAG, "best shot failed", t); notify("Best shot failed: ${t.message}")
            } finally { _busy.value = null }
        }
    }

    data class CloneUi(val first: com.daya.velum.imaging.YuvFrame? = null, val ghost: Bitmap? = null)
    private val _clone = MutableStateFlow(CloneUi())
    val clone: StateFlow<CloneUi> = _clone

    fun cloneReset() { _clone.value.ghost?.recycle(); _clone.value = CloneUi() }

    private fun cloneShot() {
        if (_busy.value != null) return
        val s = _settings.value
        engine.setAnalysisRotation(deviceRotation)
        viewModelScope.launch {
            play(android.media.MediaActionSound.SHUTTER_CLICK)
            _flash.value = System.nanoTime()
            val f = engine.grabFrame()
            if (f == null) { notify("Clone needs the frame stream; try again."); return@launch }
            val first = _clone.value.first
            if (first == null) {
                // Ghost for the viewfinder: the first shot drawn the way the (portrait) viewfinder shows it.
                val portrait = com.daya.velum.imaging.YuvFrame(f.w, f.h, f.y, f.u, f.v, 90, f.tsNs)
                val ghost = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { portrait.toBitmap(8) }
                _clone.value = CloneUi(f, ghost)
                notify("First shot kept. Move to the other side and shoot again.")
                return@launch
            }
            _busy.value = "Joining the two shots"
            try {
                val out = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    if (s.cloneFirstLeft) com.daya.velum.imaging.CloneComposer.compose(first, f, 0.5f)
                    else com.daya.velum.imaging.CloneComposer.compose(f, first, 0.5f)
                }
                val uri = saveStill("VLM_CLONE", out, "Velum clone: two shots joined", s)
                out.recycle()
                refreshThumb(uri)
                notify("Saved clone photo")
            } catch (t: Throwable) {
                Log.e(TAG, "clone failed", t); notify("Clone failed: ${t.message}")
            } finally { cloneReset(); _busy.value = null }
        }
    }

    private suspend fun saveBitmapJpeg(name: String, bmp: Bitmap, software: String, quality: Int): Uri =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val tmp = java.io.File.createTempFile("velum_le", ".jpg", ctx.cacheDir)
            try {
                tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
                val ex = androidx.exifinterface.media.ExifInterface(tmp)
                ex.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_MAKE, android.os.Build.MANUFACTURER)
                ex.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_MODEL, android.os.Build.MODEL)
                val now = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                ex.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME, now)
                ex.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL, now)
                ex.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_SOFTWARE, software)
                ex.saveAttributes()
                media.saveJpeg(name) { out -> tmp.inputStream().use { it.copyTo(out) } }
            } finally { tmp.delete() }
        }

    // ------------------------------------------------------------------ vlog

    data class VlogUi(val templateId: String = "day_out", val shot: Int = 0, val parts: List<java.io.File> = emptyList(),
                      val shooting: Boolean = false, val stitching: Boolean = false)
    private val _vlog = MutableStateFlow(VlogUi())
    val vlog: StateFlow<VlogUi> = _vlog
    private val vlogDir get() = java.io.File(getApplication<Application>().cacheDir, "vlog").apply { mkdirs() }

    fun vlogTemplate(id: String) {
        if (_vlog.value.shooting) return
        _vlog.value.parts.forEach { it.delete() }
        _vlog.value = VlogUi(templateId = id)
        com.daya.velum.video.VlogTemplates.byId(id)?.let { t -> update { it.copy(videoLook = it.videoLook.copy(lookId = t.lookId)) } }
    }

    private fun vlogShoot() {
        val v = _vlog.value
        val t = com.daya.velum.video.VlogTemplates.byId(v.templateId) ?: return
        if (v.shooting || v.stitching || v.shot >= t.shots.size) return
        val shot = t.shots[v.shot]
        val a = availability
        val from = shot.move.from.coerceIn(a.zoomRange.start, a.zoomRange.endInclusive)
        val to = shot.move.to.coerceIn(a.zoomRange.start, a.zoomRange.endInclusive)
        setZoom(from)
        _vlog.value = v.copy(shooting = true)
        val file = java.io.File(vlogDir, "shot_${v.shot}_${System.currentTimeMillis()}.mp4")
        engine.acknowledgeRecording()
        val mic = _settings.value.video.audio && androidx.core.content.ContextCompat.checkSelfPermission(
            getApplication(), android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        play(android.media.MediaActionSound.START_VIDEO_RECORDING)
        engine.startRecordingToFile(deviceRotation, mic, file, (shot.seconds * 1000).toLong())
        if (from != to) movie.autoZoom(from, to, maxOf(1, shot.seconds.toInt())) { z -> setZoom(z) }
        pendingVlogFile = file
    }
    private var pendingVlogFile: java.io.File? = null

    private fun onVlogSaved() {
        val f = pendingVlogFile ?: return
        pendingVlogFile = null
        val v = _vlog.value
        _vlog.value = v.copy(shooting = false, shot = v.shot + 1, parts = v.parts + f)
        val t = com.daya.velum.video.VlogTemplates.byId(v.templateId) ?: return
        notify(if (v.shot + 1 < t.shots.size) "Shot ${v.shot + 1} of ${t.shots.size} done" else "All shots done. Tap Finish to make your vlog.")
    }

    fun vlogRedo() {
        val v = _vlog.value
        if (v.shooting || v.parts.isEmpty()) return
        v.parts.last().delete()
        _vlog.value = v.copy(shot = v.shot - 1, parts = v.parts.dropLast(1))
    }

    /** Joins the shots (no re-encode) into DCIM/Velum, optionally with music from the user's AAC/M4A file. */
    fun vlogFinish(music: Uri?) {
        val v = _vlog.value
        if (v.parts.isEmpty() || v.stitching) return
        _vlog.value = v.copy(stitching = true)
        viewModelScope.launch {
            try {
                val cr = getApplication<Application>().contentResolver
                val name = media.newBaseName("VLM_VLOG")
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "$name.mp4")
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DCIM + "/" + com.daya.velum.media.ALBUM)
                    put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = cr.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore insert failed")
                val ms = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        cr.openFileDescriptor(uri, "rw")!!.use { pfd -> com.daya.velum.video.VlogStitcher.stitch(getApplication(), v.parts, pfd.fileDescriptor, music) }
                    } catch (t: Throwable) { cr.delete(uri, null, null); throw t }
                }
                cr.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                val probe = media.probeVideo(uri)
                if (!probe.ok) { media.delete(uri); error("joined file did not verify") }
                v.parts.forEach { it.delete() }
                _vlog.value = VlogUi(templateId = v.templateId)
                refreshThumb(uri)
                notify("Vlog saved (%.1f s)".format(ms / 1000f))
            } catch (t: Throwable) {
                Log.e(TAG, "vlog stitch failed", t)
                _vlog.value = _vlog.value.copy(stitching = false)
                notify("Vlog could not be made: ${t.message}")
            }
        }
    }

    fun shutter() {
        val s = _settings.value
        if (s.mode == CaptureMode.VLOG) { vlogShoot(); return }
        if (s.mode == CaptureMode.SLOW_MO) {
            when (slowMo.state.value) {
                is com.daya.velum.video.SlowMoState.Recording -> { slowMo.stop(); play(android.media.MediaActionSound.STOP_VIDEO_RECORDING) }
                is com.daya.velum.video.SlowMoState.Ready -> { slowMo.start(); play(android.media.MediaActionSound.START_VIDEO_RECORDING) }
                else -> notify("Slow motion is starting")
            }
            return
        }
        if (s.mode.isVideo) { toggleRecord(); return }
        if (s.mode == CaptureMode.LONG_EXPOSURE) { runLongExposure(); return }
        if (s.mode == CaptureMode.ASTRO) { runLongExposure(astro = true); return }
        if (s.mode == CaptureMode.BEST_SHOT) { runBestShot(); return }
        if (s.mode == CaptureMode.CLONE) { cloneShot(); return }
        if (_countdown.value > 0) { timerJob?.cancel(); _countdown.value = 0; notify("Timer cancelled"); return }
        timerJob = viewModelScope.launch {
            for (i in s.timerSec downTo 1) { _countdown.value = i; delay(1000) }
            _countdown.value = 0
            _flash.value = System.nanoTime()
            play(android.media.MediaActionSound.SHUTTER_CLICK)
            val motion = s.mode == CaptureMode.PHOTO && s.motionPhoto
            val clip = if (motion) async { engine.motionClip() } else null
            val uris = engine.takePhoto(deviceRotation)
            clip?.await()?.let { (file, us) -> uris.firstOrNull()?.let { embedMotion(it, file, us) } }
                ?: run { if (motion && uris.isNotEmpty()) notify("Photo saved without motion (the video loop was not ready).") }
        }
    }

    private suspend fun embedMotion(uri: Uri, clip: java.io.File, presentationUs: Long) = gradeLock.withLock {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val cr = getApplication<Application>().contentResolver
                val jpeg = cr.openInputStream(uri)?.use { it.readBytes() } ?: error("cannot read photo")
                val out = com.daya.velum.camera.MotionPhoto.embed(jpeg, clip.readBytes(), presentationUs)
                cr.openOutputStream(uri, "wt")?.use { it.write(out) } ?: error("cannot write photo")
                Log.i(TAG, "motion photo: ${jpeg.size} + ${clip.length()} bytes, photo at ${presentationUs / 1000} ms")
            }.onFailure { Log.e(TAG, "motion embed failed", it); notify("Motion could not be added; the photo is saved.") }
            clip.delete()
        }
    }

    fun burstStart() {
        if (_settings.value.mode.isVideo || burstJob?.isActive == true) return
        burstJob = viewModelScope.launch {
            var n = 0
            while (n < 30) {
                _flash.value = System.nanoTime()
                launch { engine.takePhoto(deviceRotation) }
                n++
                delay(140)
                while (engine.capturesInFlight.value > 3) delay(30) // bounded queue: never pile up requests
            }
        }
    }

    fun burstStop() { burstJob?.cancel(); burstJob = null }

    fun bracket() = viewModelScope.launch {
        notify("Bracketing −2, 0, +2 EV")
        _flash.value = System.nanoTime()
        engine.bracket(deviceRotation, listOf(-2f, 0f, 2f))
    }

    /** Time freeze: toggles the frozen background plate. */
    fun toggleFreeze() { frozen = !frozen; engine.freeze(frozen) }
    val isFrozen get() = frozen

    fun autoZoom() {
        val m = _settings.value.movie
        movie.autoZoom(m.zoomFrom, m.zoomTo, m.zoomSeconds) { z -> setZoom(z) }
    }

    fun snapshot() = viewModelScope.launch { _flash.value = System.nanoTime(); play(android.media.MediaActionSound.SHUTTER_CLICK); engine.takePhoto(deviceRotation) }

    override fun onCleared() {
        thermalListener?.let { getApplication<Application>().getSystemService(android.os.PowerManager::class.java)?.removeThermalStatusListener(it) }
        sound.release(); super.onCleared()
    }

    private var thermalListener: android.os.PowerManager.OnThermalStatusChangedListener? = null
    private var heatWarned = false

    /**
     * 4K, HLG and high bitrates heat the phone quickly; past CRITICAL the system closes the camera on its own and an
     * unfinished file is lost. Warn at SEVERE, and stop (saving the file) at CRITICAL.
     */
    private fun watchHeat() {
        val pm = getApplication<Application>().getSystemService(android.os.PowerManager::class.java) ?: return
        val l = android.os.PowerManager.OnThermalStatusChangedListener { st ->
            val recording = engine.rec.value.let { it is RecState.Recording || it is RecState.Paused }
            when {
                st >= android.os.PowerManager.THERMAL_STATUS_CRITICAL && recording -> {
                    engine.stopRecording(); play(android.media.MediaActionSound.STOP_VIDEO_RECORDING)
                    notify("Recording stopped and saved: the phone is too hot. Let it cool down.")
                }
                st >= android.os.PowerManager.THERMAL_STATUS_SEVERE && !heatWarned -> {
                    heatWarned = true
                    notify("Phone is getting hot. Long 4K or HLG recording may stop; 1080p or a lower bitrate runs cooler.")
                }
                st <= android.os.PowerManager.THERMAL_STATUS_MODERATE -> heatWarned = false
            }
        }
        pm.addThermalStatusListener(getApplication<Application>().mainExecutor, l)
        thermalListener = l
    }

    fun toggleRecord() {
        when (engine.rec.value) {
            is RecState.Recording, is RecState.Paused -> { engine.stopRecording(); play(android.media.MediaActionSound.STOP_VIDEO_RECORDING) }
            RecState.Idle, is RecState.Saved, is RecState.Error -> {
                engine.acknowledgeRecording()
                val s = _settings.value
                val lapse = s.mode == CaptureMode.EFFECTS && (s.effects.effect == MovieEffect.TIMELAPSE || s.effects.effect == MovieEffect.NIGHT_TIMELAPSE)
                frozen = false
                val mic = !lapse && s.video.audio && androidx.core.content.ContextCompat.checkSelfPermission(
                    getApplication(), android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                play(android.media.MediaActionSound.START_VIDEO_RECORDING)
                engine.startRecording(deviceRotation, mic)
            }
            else -> {}
        }
    }

    private fun onPhotoSaved(uri: Uri) {
        refreshThumb(uri)
        val s = _settings.value
        val chosen = looks.byId(s.photoLook.lookId)
        val lookOn = chosen != null && !chosen.isNeutral && s.photoLook.target == LookTarget.BAKED
        val portraitOn = s.mode == CaptureMode.PORTRAIT && s.zoom >= com.daya.velum.camera.PORTRAIT_TELE_ZOOM
        if (s.mode.isVideo || (!lookOn && !portraitOn && s.photoFrame == com.daya.velum.imaging.PhotoFrame.OFF)) return
        val look = if (lookOn) chosen!! else com.daya.velum.imaging.LookCatalog.NEUTRAL
        val blur: ((Bitmap) -> Bitmap)? = if (!portraitOn) null else { b ->
            val (d, p, n) = engine.ml.stillMaps(b)
            val r = com.daya.velum.imaging.PortraitRenderer.render(b, d, p, n, s.portraitBlur)
            Log.i(TAG, "portrait blur: person=${r.personFound} focus=${"%.2f".format(r.focusDepth)}")
            r.bitmap
        }
        viewModelScope.launch {
            _grading.value++
            try {
                gradeLock.withLock {
                    val lut = looks.lutFor(look)
                    val graded = c.grader.grade(uri, look, lut, s.photoLook.intensity, s.jpegQuality, s.photoFrame, blur)
                    if (!s.photoLook.keepOriginal) media.delete(uri)
                    refreshThumb(graded)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "grading failed", t)
                notify("Look could not be applied; the original photo is saved.")
            } finally { _grading.value-- }
        }
    }

    private fun refreshThumb(uri: Uri) = viewModelScope.launch { _thumb.value = uri to media.thumbnail(uri, 192) }

    fun refreshThumbLatest() = viewModelScope.launch {
        val item = media.latest()
        _thumb.value = item?.let { it.uri to media.thumbnail(it.uri, 192) }
    }

    // ---------------------------------------------------------------- looks, recipes, presets

    fun selectLook(look: Look) {
        val xpan = look.id.startsWith("hb_xpan")
        update {
            val ls = it.look.copy(lookId = look.id, intensity = look.defaultIntensity)
            if (it.mode.isVideo) it.copy(videoLook = ls)
            else {
                // The XPan looks belong to the 65:24 frame, as in Hasselblad's XPan mode; leaving them restores 4:3.
                val wasXpan = it.photoLook.lookId.startsWith("hb_xpan")
                val aspect = when {
                    xpan -> PhotoAspect.XPAN
                    wasXpan && it.photoAspect == PhotoAspect.XPAN -> PhotoAspect.R4_3
                    else -> it.photoAspect
                }
                it.copy(photoLook = ls, photoAspect = aspect)
            }
        }
        if (xpan && !settings.value.mode.isVideo) notify("Panoramic 65:24 frame on. Hold the phone sideways.")
        viewModelScope.launch { looks.markUsed(look.id) }
    }

    fun setLookIntensity(v: Float) = update {
        val ls = it.look.copy(intensity = v.coerceIn(0f, 1f))
        if (it.mode.isVideo) it.copy(videoLook = ls) else it.copy(photoLook = ls)
    }

    fun setLookTarget(t: LookTarget) {
        if (engine.rec.value.isActive) { notify("Stop recording to change where the look is applied."); return }
        update { if (it.mode.isVideo) it.copy(videoLook = it.videoLook.copy(target = t)) else it.copy(photoLook = it.photoLook.copy(target = t)) }
    }

    fun applyRecipe(r: Recipe) {
        if (engine.rec.value.isActive) { notify("Stop recording to apply a recipe."); return }
        val res = RecipeEngine.apply(r, _settings.value, caps.value)
        update { res.settings }
        val msg = buildString {
            append(r.name)
            if (res.skipped.isNotEmpty()) append(". Kept automatic: ").append(res.skipped.joinToString("; "))
        }
        notify(msg)
    }

    fun applyPreset(p: UserPreset) {
        if (engine.rec.value.isActive) { notify("Stop recording to apply a preset."); return }
        update { PresetStore.applyPreset(p, it) }
        notify("Preset ${p.name}")
    }

    fun savePreset(name: String) = viewModelScope.launch { presets.create(name, _settings.value); notify("Saved preset $name") }

    suspend fun saveCustomLook(name: String, params: LookParams, basedOn: Look?): Look = looks.saveCustom(name, params, basedOn)

    fun refreshLookLut() = applyLookToEngine(_settings.value)

}
