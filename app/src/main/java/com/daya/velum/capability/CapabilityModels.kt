package com.daya.velum.capability

import android.util.Range
import android.util.Size

/** Lens role derived from 35 mm-equivalent focal length relative to the main camera, never from a camera id. */
enum class LensRole(val label: String) { ULTRA_WIDE("Ultra-wide"), MAIN("Main"), TELE("Tele"), FRONT("Front"), UNKNOWN("Camera") }

data class SensorCaps(
    val isoRange: Range<Int>?,
    val exposureNsRange: Range<Long>?,
    val maxFrameDurationNs: Long?,
    val physicalSizeMm: Pair<Float, Float>?,
    val pixelArray: Size?,
    val pixelArrayMaxRes: Size?,
    val colorFilter: String?,
)

data class LensCaps(
    val focalLengthsMm: List<Float>,
    val apertures: List<Float>,
    val equivFocalMm: Float?,
    val minFocusDiopters: Float?,
    val focusCalibration: String?,
    val ois: Boolean,
)

data class StreamCaps(
    val jpeg: List<Size>,
    val yuv: List<Size>,
    val priv: List<Size>,
    val raw: List<Size>,
    val heic: List<Size>,
    val jpegR: List<Size>,
    val maxResJpeg: List<Size>,
    val maxResRaw: List<Size>,
    val highSpeed: List<HighSpeedConfig>,
)

data class HighSpeedConfig(val size: Size, val fps: List<Range<Int>>)

data class SessionProbe(val name: String, val result: ProbeResult, val detail: String = "")

enum class ProbeResult { SUPPORTED, UNSUPPORTED, NOT_TESTED, ERROR }

/** Raw Camera2 view of one camera id (logical or physical). */
data class Camera2Info(
    val id: String,
    val isPhysicalOnly: Boolean,
    val parentLogicalId: String?,
    val facing: String,
    val hardwareLevel: String,
    val capabilities: List<String>,
    val physicalIds: List<String>,
    val sensor: SensorCaps,
    val lens: LensCaps,
    val streams: StreamCaps,
    val zoomRatioRange: Range<Float>?,
    val maxDigitalZoom: Float?,
    val evRange: Range<Int>?,
    val evStep: Float?,
    val aeFpsRanges: List<Range<Int>>,
    val afModes: List<String>,
    val awbModes: List<String>,
    val aeModes: List<String>,
    val colorCorrectionModes: List<Int>,
    val aePriorityModes: List<Int>,
    val videoStabModes: List<String>,
    val flash: Boolean,
    val dynamicRangeProfiles: List<String>,
    val colorSpaces: List<String>,
    val extensions: List<String>,
    val noiseReductionModes: List<Int>,
    val edgeModes: List<Int>,
    val tonemapModes: List<Int>,
    val sessionProbes: List<SessionProbe>,
)

data class EncoderInfo(
    val name: String,
    val mime: String,
    val hardware: Boolean,
    val maxSize: Size?,
    val profiles: List<String>,
    val bitrateRange: Range<Int>?,
    val fps4k: Range<Double>?,
    val fps1080: Range<Double>?,
)

/** What CameraX itself reports for a camera it can open. */
data class CameraXInfo(
    val selectorKey: String,
    val cameraId: String,
    val facing: Int,
    val intrinsicZoom: Float,
    val physicalIds: List<String>,
    val dynamicRanges: List<String>,
    val qualitiesSdr: List<String>,
    val qualitiesHlg: List<String>,
    val qualityResolutions: Map<String, Size>,
    val highSpeedQualities: List<String>,
    val frameRateRanges: List<Range<Int>>,
    val stillFormats: List<String>,
    val zslSupported: Boolean,
    val stabilizationSupported: Boolean,
    val torchStrength: Boolean,
    val lowLightBoost: Boolean,
    val extensions: List<String>,
    val hasFlash: Boolean,
    val sessionProbes: List<SessionProbe> = emptyList(),
)

data class DeviceCapabilities(
    val generatedAtMs: Long,
    val model: String,
    val build: String,
    val sdk: Int,
    val camera2: List<Camera2Info>,
    val cameraX: List<CameraXInfo>,
    val encoders: List<EncoderInfo>,
    val lenses: List<LensOption>,
    val notes: List<String>,
) {
    fun c2(id: String) = camera2.firstOrNull { it.id == id }
    fun cx(id: String) = cameraX.firstOrNull { it.cameraId == id }
}

/**
 * A user-facing lens choice. [zoomRatio] is the ratio on [logicalId] that frames like this lens; when
 * [physicalId] is set the app may also pin the stream to that physical sensor.
 */
data class LensOption(
    val key: String,
    val label: String,
    val role: LensRole,
    val facing: Int,
    val logicalId: String,
    val physicalId: String?,
    val zoomRatio: Float,
    val equivFocalMm: Float?,
    val evidence: String,
)
