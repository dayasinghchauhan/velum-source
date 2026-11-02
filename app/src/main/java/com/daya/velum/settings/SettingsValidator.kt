package com.daya.velum.settings

import com.daya.velum.capability.DeviceCapabilities
import com.daya.velum.capability.LensOption
import com.daya.velum.capability.ProbeNames
import com.daya.velum.capability.probeOk

/** Result of validating settings against what the hardware reported. [changes] are user-visible explanations. */
data class Validated(val settings: CameraSettings, val changes: List<String>)

/** Feature availability for the active lens, consumed by the UI to enable/disable controls with a reason. */
data class Availability(
    val lens: LensOption?,
    val manualSensor: Boolean,
    val manualWb: Boolean,
    val raw: Boolean,
    val ultraHdr: Boolean,
    val flash: Boolean,
    val isoRange: IntRange,
    val shutterRangeNs: LongRange,
    val evRange: IntRange,
    val evStep: Float,
    val zoomRange: ClosedFloatingPointRange<Float>,
    val minFocusDiopters: Float,
    val videoQualities: List<VideoQuality>,
    val hlgQualities: List<VideoQuality>,
    val fpsFor: (VideoQuality, DynamicRangeMode) -> List<Int>,
    val stabilization: List<Stabilization>,
    val nightExtension: Boolean,
    val bokehExtension: Boolean,
) {
    companion object {
        val NONE = Availability(null, false, false, false, false, false, 100..100, 1_000_000L..1_000_000L, 0..0, 1f,
            1f..1f, 0f, emptyList(), emptyList(), { _, _ -> emptyList() }, listOf(Stabilization.OFF), false, false)
    }
}

object SettingsValidator {

    fun availability(caps: DeviceCapabilities?, s: CameraSettings): Availability {
        if (caps == null) return Availability.NONE
        val lens = caps.lenses.firstOrNull { it.key == s.lensKey } ?: caps.lenses.firstOrNull() ?: return Availability.NONE
        val c2 = caps.c2(lens.logicalId)
        val cx = caps.cx(lens.logicalId)
        val qualities = cx?.qualitiesSdr.orEmpty().mapNotNull { q -> VideoQuality.entries.firstOrNull { it.name == q } }
        val hlgQ = cx?.qualitiesHlg.orEmpty().mapNotNull { q -> VideoQuality.entries.firstOrNull { it.name == q } }
        val stab = buildList {
            add(Stabilization.OFF)
            if (cx?.stabilizationSupported == true) add(Stabilization.ON)
            if (c2?.videoStabModes?.contains("PREVIEW_STABILIZATION") == true) add(Stabilization.PREVIEW)
        }
        val iso = c2?.sensor?.isoRange
        val exp = c2?.sensor?.exposureNsRange
        val ev = c2?.evRange
        val zr = c2?.zoomRatioRange
        return Availability(
            lens = lens,
            manualSensor = c2?.capabilities?.contains("MANUAL_SENSOR") == true && iso != null && exp != null,
            manualWb = c2?.capabilities?.contains("MANUAL_POST_PROCESSING") == true,
            raw = cx?.probeOk(ProbeNames.RAW_JPEG) == true,
            ultraHdr = cx?.probeOk(ProbeNames.ULTRA_HDR) == true,
            flash = cx?.hasFlash == true,
            isoRange = iso?.let { it.lower..it.upper } ?: 100..100,
            shutterRangeNs = exp?.let { it.lower..it.upper } ?: 1_000_000L..1_000_000L,
            evRange = ev?.let { it.lower..it.upper } ?: 0..0,
            evStep = c2?.evStep ?: 1f,
            zoomRange = zr?.let { it.lower..it.upper } ?: 1f..1f,
            minFocusDiopters = c2?.lens?.minFocusDiopters ?: 0f,
            videoQualities = qualities,
            hlgQualities = hlgQ,
            fpsFor = { q, dr ->
                listOf(24, 25, 30, 60).filter { fps -> cx?.probeOk(ProbeNames.video(q.name, fps, dr == DynamicRangeMode.HLG)) == true }
            },
            stabilization = stab,
            nightExtension = cx?.extensions?.contains("NIGHT") == true,
            bokehExtension = cx?.extensions?.contains("BOKEH") == true,
        )
    }

    /** Clamps every setting to what [caps] supports, explaining each change. Never silently drops a user choice. */
    fun validate(caps: DeviceCapabilities?, input: CameraSettings): Validated {
        if (caps == null || caps.lenses.isEmpty()) return Validated(input, emptyList())
        val changes = mutableListOf<String>()
        var s = input
        if (caps.lenses.none { it.key == s.lensKey }) {
            val fallback = caps.lenses.firstOrNull { it.role.name == "MAIN" } ?: caps.lenses.first()
            if (s.lensKey.isNotEmpty()) changes += "Saved lens is unavailable; using ${fallback.label}."
            s = s.copy(lensKey = fallback.key, zoom = fallback.zoomRatio)
        }
        val a = availability(caps, s)
        s = s.copy(zoom = s.zoom.coerceIn(a.zoomRange.start, a.zoomRange.endInclusive))

        if (s.photoFormat == PhotoFormat.RAW_JPEG && !a.raw) { s = s.copy(photoFormat = PhotoFormat.JPEG); changes += "RAW is not available on this lens; JPEG selected." }
        if (s.photoFormat == PhotoFormat.ULTRA_HDR && !a.ultraHdr) { s = s.copy(photoFormat = PhotoFormat.JPEG); changes += "Ultra HDR is not available on this lens; JPEG selected." }
        if (s.flash != FlashMode.OFF && !a.flash) s = s.copy(flash = FlashMode.OFF)
        if (s.mode == CaptureMode.NIGHT && !a.nightExtension) changes += "Night processing isn't offered on ${a.lens?.label}; capturing normally."
        if (s.mode == CaptureMode.PORTRAIT && !a.bokehExtension) changes += "Background blur isn't offered on ${a.lens?.label}; capturing normally."
        if (s.mode.extension != null && s.exposure.program != ExposureProgram.P) s = s.copy(exposure = s.exposure.copy(program = ExposureProgram.P))

        // Video format: quality → dynamic range → fps, falling back one axis at a time and saying so.
        var v = s.video
        if (v.dynamicRange == DynamicRangeMode.HLG && v.quality !in a.hlgQualities) {
            v = v.copy(dynamicRange = DynamicRangeMode.SDR); changes += "HLG is not available at ${v.quality.label}; SDR selected."
        }
        val qList = if (v.dynamicRange == DynamicRangeMode.HLG) a.hlgQualities else a.videoQualities
        if (v.quality !in qList && qList.isNotEmpty()) {
            val q = qList.first(); changes += "${v.quality.label} is not available on this lens; ${q.label} selected."; v = v.copy(quality = q)
        }
        val fpsList = a.fpsFor(v.quality, v.dynamicRange)
        if (v.fps !in fpsList && fpsList.isNotEmpty()) {
            val f = if (30 in fpsList) 30 else fpsList.first()
            changes += "${v.fps} fps is not supported at ${v.quality.label} ${v.dynamicRange.label}; $f fps selected."
            v = v.copy(fps = f)
        }
        if (v.stabilization !in a.stabilization) v = v.copy(stabilization = a.stabilization.last())
        s = s.copy(video = v)

        // Exposure: manual needs MANUAL_SENSOR; S needs the GPU meter, which is off in HLG.
        var e = s.exposure
        if (!a.manualSensor && e.program != ExposureProgram.P) { e = e.copy(program = ExposureProgram.P); changes += "Manual exposure is not available on this lens." }
        if (e.program == ExposureProgram.S && s.mode.isVideo && v.dynamicRange == DynamicRangeMode.HLG) {
            e = e.copy(program = ExposureProgram.P); changes += "Shutter priority needs the SDR metering path; switched to P for HLG."
        }
        e = e.copy(
            iso = e.iso.coerceIn(a.isoRange.first, a.isoRange.last),
            shutterNs = e.shutterNs.coerceIn(a.shutterRangeNs.first, a.shutterRangeNs.last),
            evIndex = e.evIndex.coerceIn(a.evRange.first, a.evRange.last),
        )
        if (s.mode.isVideo) {
            val maxNs = 1_000_000_000L / v.fps
            if (e.shutterNs > maxNs) e = e.copy(shutterNs = maxNs)
        }
        if (e.program != ExposureProgram.P && s.flash != FlashMode.OFF && s.flash != FlashMode.TORCH) s = s.copy(flash = FlashMode.OFF)
        s = s.copy(exposure = e)

        if (s.focus.manual && !a.manualSensor) s = s.copy(focus = s.focus.copy(manual = false))
        s = s.copy(focus = s.focus.copy(diopters = s.focus.diopters.coerceIn(0f, a.minFocusDiopters)))
        if (s.wb.mode == WbMode.KELVIN && !a.manualWb) { s = s.copy(wb = s.wb.copy(mode = WbMode.AUTO)); changes += "Kelvin white balance is not available." }
        s = s.copy(wb = s.wb.copy(kelvin = s.wb.kelvin.coerceIn(2000, 10000)))
        return Validated(s, changes)
    }
}
