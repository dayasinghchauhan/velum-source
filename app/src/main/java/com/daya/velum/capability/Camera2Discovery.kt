package com.daya.velum.capability

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraCharacteristics.*
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.util.Log
import android.util.Range
import android.util.Size
import kotlin.math.hypot

private const val TAG = "Velum/Discovery"

/** Pure Camera2 read-only discovery. Never opens a camera device. */
class Camera2Discovery(private val manager: CameraManager) {

    fun discover(): List<Camera2Info> {
        val out = mutableListOf<Camera2Info>()
        val ids = runCatching { manager.cameraIdList.toList() }.getOrElse { emptyList() }
        for (id in ids) {
            val ch = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            out += describe(id, ch, physicalOnly = false, parent = null)
            for (pid in ch.physicalCameraIds) {
                if (ids.contains(pid) || out.any { it.id == pid }) continue
                val pch = runCatching { manager.getCameraCharacteristics(pid) }
                    .onFailure { Log.w(TAG, "physical $pid of $id not readable: ${it.message}") }
                    .getOrNull() ?: continue
                out += describe(pid, pch, physicalOnly = true, parent = id)
            }
        }
        return out
    }

    private fun describe(id: String, ch: CameraCharacteristics, physicalOnly: Boolean, parent: String?): Camera2Info {
        val caps = ch.get(REQUEST_AVAILABLE_CAPABILITIES)?.toList().orEmpty()
        val map = ch.get(SCALER_STREAM_CONFIGURATION_MAP)
        val maxMap = if (Build.VERSION.SDK_INT >= 31) ch.get(SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION) else null
        val physSize = ch.get(SENSOR_INFO_PHYSICAL_SIZE)
        val focals = ch.get(LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList().orEmpty()
        val equiv = if (physSize != null && focals.isNotEmpty()) {
            val diag = hypot(physSize.width.toDouble(), physSize.height.toDouble())
            (focals.first() * 43.2666 / diag).toFloat()
        } else null

        val streams = StreamCaps(
            jpeg = sizes(map, ImageFormat.JPEG),
            yuv = sizes(map, ImageFormat.YUV_420_888),
            priv = map?.getOutputSizes(SurfaceTexture::class.java)?.sortedByArea().orEmpty(),
            raw = sizes(map, ImageFormat.RAW_SENSOR),
            heic = sizes(map, ImageFormat.HEIC),
            jpegR = if (Build.VERSION.SDK_INT >= 34) sizes(map, ImageFormat.JPEG_R) else emptyList(),
            maxResJpeg = sizes(maxMap, ImageFormat.JPEG),
            maxResRaw = sizes(maxMap, ImageFormat.RAW_SENSOR),
            highSpeed = map?.let { m ->
                runCatching {
                    m.highSpeedVideoSizes.map { s -> HighSpeedConfig(s, m.getHighSpeedVideoFpsRangesFor(s).toList()) }
                }.getOrNull()
            }.orEmpty(),
        )

        val drp = ch.get(REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)?.supportedProfiles?.map(::profileName).orEmpty()
        val cs = ch.get(REQUEST_AVAILABLE_COLOR_SPACE_PROFILES)
            ?.getSupportedColorSpaces(ImageFormat.UNKNOWN)?.map { it.name }.orEmpty()
        val ext = runCatching {
            manager.getCameraExtensionCharacteristics(id).supportedExtensions.map(::extName)
        }.getOrElse { emptyList() }

        val evStep = ch.get(CONTROL_AE_COMPENSATION_STEP)?.toFloat()
        val info = Camera2Info(
            id = id,
            isPhysicalOnly = physicalOnly,
            parentLogicalId = parent,
            facing = when (ch.get(LENS_FACING)) {
                LENS_FACING_BACK -> "BACK"; LENS_FACING_FRONT -> "FRONT"; LENS_FACING_EXTERNAL -> "EXTERNAL"; else -> "?"
            },
            hardwareLevel = when (ch.get(INFO_SUPPORTED_HARDWARE_LEVEL)) {
                INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
                else -> "?"
            },
            capabilities = caps.map(::capName),
            physicalIds = ch.physicalCameraIds.toList(),
            sensor = SensorCaps(
                isoRange = ch.get(SENSOR_INFO_SENSITIVITY_RANGE),
                exposureNsRange = ch.get(SENSOR_INFO_EXPOSURE_TIME_RANGE),
                maxFrameDurationNs = ch.get(SENSOR_INFO_MAX_FRAME_DURATION),
                physicalSizeMm = physSize?.let { it.width to it.height },
                pixelArray = ch.get(SENSOR_INFO_PIXEL_ARRAY_SIZE),
                pixelArrayMaxRes = if (Build.VERSION.SDK_INT >= 31) ch.get(SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION) else null,
                colorFilter = ch.get(SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)?.let {
                    listOf("RGGB", "GRBG", "GBRG", "BGGR", "RGB", "MONO", "NIR").getOrElse(it) { "$it" }
                },
            ),
            lens = LensCaps(
                focalLengthsMm = focals,
                apertures = ch.get(LENS_INFO_AVAILABLE_APERTURES)?.toList().orEmpty(),
                equivFocalMm = equiv,
                minFocusDiopters = ch.get(LENS_INFO_MINIMUM_FOCUS_DISTANCE),
                focusCalibration = ch.get(LENS_INFO_FOCUS_DISTANCE_CALIBRATION)?.let {
                    listOf("UNCALIBRATED", "APPROXIMATE", "CALIBRATED").getOrElse(it) { "$it" }
                },
                ois = ch.get(LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.contains(LENS_OPTICAL_STABILIZATION_MODE_ON) == true,
            ),
            streams = streams,
            zoomRatioRange = ch.get(CONTROL_ZOOM_RATIO_RANGE),
            maxDigitalZoom = ch.get(SCALER_AVAILABLE_MAX_DIGITAL_ZOOM),
            evRange = ch.get(CONTROL_AE_COMPENSATION_RANGE),
            evStep = evStep,
            aeFpsRanges = ch.get(CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty(),
            afModes = ch.get(CONTROL_AF_AVAILABLE_MODES)?.map {
                listOf("OFF", "AUTO", "MACRO", "CONTINUOUS_VIDEO", "CONTINUOUS_PICTURE", "EDOF").getOrElse(it) { "$it" }
            }.orEmpty(),
            awbModes = ch.get(CONTROL_AWB_AVAILABLE_MODES)?.map {
                listOf("OFF", "AUTO", "INCANDESCENT", "FLUORESCENT", "WARM_FLUORESCENT", "DAYLIGHT", "CLOUDY_DAYLIGHT", "TWILIGHT", "SHADE").getOrElse(it) { "$it" }
            }.orEmpty(),
            aeModes = ch.get(CONTROL_AE_AVAILABLE_MODES)?.map {
                listOf("OFF", "ON", "ON_AUTO_FLASH", "ON_ALWAYS_FLASH", "ON_AUTO_FLASH_REDEYE", "ON_EXTERNAL_FLASH", "ON_LOW_LIGHT_BOOST").getOrElse(it) { "$it" }
            }.orEmpty(),
            colorCorrectionModes = if (Build.VERSION.SDK_INT >= 36) ch.get(COLOR_CORRECTION_AVAILABLE_MODES)?.toList().orEmpty() else emptyList(),
            aePriorityModes = if (Build.VERSION.SDK_INT >= 36) ch.get(CONTROL_AE_AVAILABLE_PRIORITY_MODES)?.toList().orEmpty() else emptyList(),
            videoStabModes = ch.get(CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.map {
                listOf("OFF", "ON", "PREVIEW_STABILIZATION").getOrElse(it) { "$it" }
            }.orEmpty(),
            flash = ch.get(FLASH_INFO_AVAILABLE) == true,
            dynamicRangeProfiles = drp,
            colorSpaces = cs,
            extensions = ext,
            noiseReductionModes = ch.get(NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.toList().orEmpty(),
            edgeModes = ch.get(EDGE_AVAILABLE_EDGE_MODES)?.toList().orEmpty(),
            tonemapModes = ch.get(TONEMAP_AVAILABLE_TONE_MAP_MODES)?.toList().orEmpty(),
            sessionProbes = emptyList(),
        )
        return info.copy(sessionProbes = probeSessions(info, parent))
    }

    /**
     * Validates representative stream combinations through [CameraManager.getCameraDeviceSetup] (API 35+),
     * which answers without opening the device. Physical ids are probed through their logical parent.
     */
    private fun probeSessions(info: Camera2Info, parent: String?): List<SessionProbe> {
        if (Build.VERSION.SDK_INT < 35) return listOf(SessionProbe("all", ProbeResult.NOT_TESTED, "needs API 35"))
        val deviceId = parent ?: info.id
        val supported = runCatching { manager.isCameraDeviceSetupSupported(deviceId) }.getOrDefault(false)
        if (!supported) return listOf(SessionProbe("all", ProbeResult.NOT_TESTED, "CameraDeviceSetup unsupported for $deviceId"))
        val setup = runCatching { manager.getCameraDeviceSetup(deviceId) }.getOrNull()
            ?: return listOf(SessionProbe("all", ProbeResult.ERROR, "getCameraDeviceSetup failed"))
        val phys = if (parent != null) info.id else null
        val s = info.streams
        val p1080 = Size(1920, 1080)
        val p4k = Size(3840, 2160)
        val jpegMax = s.jpeg.firstOrNull()
        val rawMax = s.raw.firstOrNull()

        fun priv(size: Size, profile: Long? = null) = OutputConfiguration(size, SurfaceTexture::class.java).apply {
            phys?.let { setPhysicalCameraId(it) }
            if (profile != null) dynamicRangeProfile = profile
        }
        fun img(format: Int, size: Size) = OutputConfiguration(format, size).apply { phys?.let { setPhysicalCameraId(it) } }

        val probes = mutableListOf<Pair<String, () -> SessionConfiguration>>()
        if (jpegMax != null) probes += "PRIV 1080p + JPEG ${jpegMax.str()}" to {
            SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(priv(p1080), img(ImageFormat.JPEG, jpegMax)))
        }
        if (jpegMax != null && rawMax != null) probes += "PRIV 1080p + RAW ${rawMax.str()} + JPEG ${jpegMax.str()}" to {
            SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                listOf(priv(p1080), img(ImageFormat.RAW_SENSOR, rawMax), img(ImageFormat.JPEG, jpegMax)))
        }
        if (s.priv.contains(p4k)) {
            probes += "PRIV 1080p + PRIV 4K (video)" to {
                SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(priv(p1080), priv(p4k)))
            }
            if (jpegMax != null) probes += "PRIV 1080p + PRIV 4K + JPEG ${jpegMax.str()}" to {
                SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(priv(p1080), priv(p4k), img(ImageFormat.JPEG, jpegMax)))
            }
            probes += "PRIV 1080p + PRIV 4K + YUV 640x480" to {
                SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                    listOf(priv(p1080), priv(p4k), img(ImageFormat.YUV_420_888, Size(640, 480))))
            }
            if (info.dynamicRangeProfiles.contains("HLG10")) probes += "HLG10 PRIV 1080p + PRIV 4K" to {
                SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                    listOf(priv(p1080, DynamicRangeProfiles.HLG10), priv(p4k, DynamicRangeProfiles.HLG10)))
            }
            for (fps in listOf(24, 25, 30, 60)) {
                if (info.aeFpsRanges.none { it.lower == fps && it.upper == fps }) continue
                probes += "PRIV 1080p + PRIV 4K @ $fps fixed" to {
                    SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(priv(p1080), priv(p4k))).apply {
                        val req = setup.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        req.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
                        sessionParameters = req.build()
                    }
                }
            }
        }
        return probes.map { (name, build) ->
            runCatching {
                val ok = setup.isSessionConfigurationSupported(build())
                SessionProbe(name, if (ok) ProbeResult.SUPPORTED else ProbeResult.UNSUPPORTED)
            }.getOrElse { SessionProbe(name, ProbeResult.ERROR, it.javaClass.simpleName + ": " + it.message) }
        }
    }

    private fun sizes(map: StreamConfigurationMap?, format: Int): List<Size> =
        runCatching { map?.getOutputSizes(format)?.sortedByArea().orEmpty() }.getOrElse { emptyList() }

    companion object {
        fun capName(c: Int) = when (c) {
            REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> "BACKWARD_COMPATIBLE"
            REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "MANUAL_SENSOR"
            REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> "MANUAL_POST_PROCESSING"
            REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
            REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING -> "PRIVATE_REPROCESSING"
            REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS -> "READ_SENSOR_SETTINGS"
            REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "BURST_CAPTURE"
            REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING -> "YUV_REPROCESSING"
            REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> "DEPTH_OUTPUT"
            REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO -> "CONSTRAINED_HIGH_SPEED_VIDEO"
            REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING -> "MOTION_TRACKING"
            REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "LOGICAL_MULTI_CAMERA"
            REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME -> "MONOCHROME"
            REQUEST_AVAILABLE_CAPABILITIES_SECURE_IMAGE_DATA -> "SECURE_IMAGE_DATA"
            REQUEST_AVAILABLE_CAPABILITIES_SYSTEM_CAMERA -> "SYSTEM_CAMERA"
            REQUEST_AVAILABLE_CAPABILITIES_OFFLINE_PROCESSING -> "OFFLINE_PROCESSING"
            REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR -> "ULTRA_HIGH_RESOLUTION_SENSOR"
            REQUEST_AVAILABLE_CAPABILITIES_REMOSAIC_REPROCESSING -> "REMOSAIC_REPROCESSING"
            REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT -> "DYNAMIC_RANGE_TEN_BIT"
            REQUEST_AVAILABLE_CAPABILITIES_STREAM_USE_CASE -> "STREAM_USE_CASE"
            REQUEST_AVAILABLE_CAPABILITIES_COLOR_SPACE_PROFILES -> "COLOR_SPACE_PROFILES"
            else -> "CAP_$c"
        }

        fun profileName(p: Long) = when (p) {
            DynamicRangeProfiles.STANDARD -> "STANDARD"
            DynamicRangeProfiles.HLG10 -> "HLG10"
            DynamicRangeProfiles.HDR10 -> "HDR10"
            DynamicRangeProfiles.HDR10_PLUS -> "HDR10_PLUS"
            DynamicRangeProfiles.DOLBY_VISION_10B_HDR_REF -> "DOLBY_VISION_10B_HDR_REF"
            DynamicRangeProfiles.DOLBY_VISION_10B_HDR_OEM -> "DOLBY_VISION_10B_HDR_OEM"
            DynamicRangeProfiles.DOLBY_VISION_8B_HDR_REF -> "DOLBY_VISION_8B_HDR_REF"
            DynamicRangeProfiles.DOLBY_VISION_8B_HDR_OEM -> "DOLBY_VISION_8B_HDR_OEM"
            else -> "PROFILE_$p"
        }

        fun extName(e: Int) = when (e) {
            android.hardware.camera2.CameraExtensionCharacteristics.EXTENSION_AUTOMATIC -> "AUTO"
            android.hardware.camera2.CameraExtensionCharacteristics.EXTENSION_BOKEH -> "BOKEH"
            android.hardware.camera2.CameraExtensionCharacteristics.EXTENSION_HDR -> "HDR"
            android.hardware.camera2.CameraExtensionCharacteristics.EXTENSION_NIGHT -> "NIGHT"
            android.hardware.camera2.CameraExtensionCharacteristics.EXTENSION_FACE_RETOUCH -> "FACE_RETOUCH"
            else -> "EXT_$e"
        }
    }
}

internal fun Array<Size>.sortedByArea() = sortedByDescending { it.width.toLong() * it.height }
internal fun Size.str() = "${width}x$height"
