package com.daya.velum.capability

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.VideoCapture
import androidx.camera.video.Recorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

private const val TAG = "Velum/Caps"

interface CameraCapabilityRepository {
    val capabilities: StateFlow<DeviceCapabilities?>
    suspend fun refresh(provider: ProcessCameraProvider): DeviceCapabilities
    fun reportFile(): File
}

class DefaultCameraCapabilityRepository(private val context: Context) : CameraCapabilityRepository {
    private val _caps = MutableStateFlow<DeviceCapabilities?>(null)
    override val capabilities: StateFlow<DeviceCapabilities?> = _caps
    private val manager = context.getSystemService(CameraManager::class.java)

    override fun reportFile() = File(context.filesDir, "capabilities.json")

    override suspend fun refresh(provider: ProcessCameraProvider): DeviceCapabilities {
        val c2 = withContext(Dispatchers.Default) { Camera2Discovery(manager).discover() }
        val extensions = runCatching { ExtensionsManager.getInstanceAsync(context, provider).await() }.getOrNull()
        val cx = withContext(Dispatchers.Main) { provider.availableCameraInfos.mapNotNull { describeCameraX(it, extensions) } }
        val encoders = withContext(Dispatchers.Default) { encoders() }
        val lenses = buildLenses(c2, cx)
        val notes = buildNotes(c2, cx)
        val caps = DeviceCapabilities(
            generatedAtMs = System.currentTimeMillis(),
            model = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})",
            build = Build.DISPLAY,
            sdk = Build.VERSION.SDK_INT,
            camera2 = c2, cameraX = cx, encoders = encoders, lenses = lenses, notes = notes,
        )
        withContext(Dispatchers.IO) {
            runCatching { reportFile().writeText(CapabilityReport.toJson(caps).toString(1)) }
                .onFailure { Log.w(TAG, "report write failed", it) }
        }
        _caps.value = caps
        return caps
    }

    @OptIn(ExperimentalCamera2Interop::class)
    @SuppressLint("RestrictedApi")
    private fun describeCameraX(info: CameraInfo, ext: ExtensionsManager?): CameraXInfo? = runCatching {
        val id = Camera2CameraInfo.from(info).cameraId
        val vc = Recorder.getVideoCapabilities(info)
        val drs = vc.supportedDynamicRanges
        val sdrQ = vc.getSupportedQualities(DynamicRange.SDR)
        val hlgQ = if (drs.contains(DynamicRange.HLG_10_BIT)) vc.getSupportedQualities(DynamicRange.HLG_10_BIT) else emptyList()
        val qRes = buildMap {
            for (q in sdrQ) vc.getResolution(q, DynamicRange.SDR)?.let { put(qualityName(q), it) }
        }
        val hs = runCatching {
            val h = Recorder.getHighSpeedVideoCapabilities(info) ?: return@runCatching emptyList<String>()
            h.getSupportedQualities(DynamicRange.SDR).map(::qualityName)
        }.getOrElse { emptyList() }
        val selector = selectorFor(id)
        val extModes = buildList {
            if (ext != null) {
                for ((mode, name) in listOf(
                    ExtensionMode.NIGHT to "NIGHT", ExtensionMode.HDR to "HDR", ExtensionMode.BOKEH to "BOKEH",
                    ExtensionMode.AUTO to "AUTO", ExtensionMode.FACE_RETOUCH to "FACE_RETOUCH",
                )) if (runCatching { ext.isExtensionAvailable(selector, mode) }.getOrDefault(false)) add(name)
            }
        }
        CameraXInfo(
            selectorKey = id,
            cameraId = id,
            facing = info.lensFacing,
            intrinsicZoom = info.intrinsicZoomRatio,
            physicalIds = info.physicalCameraInfos.mapNotNull { runCatching { Camera2CameraInfo.from(it).cameraId }.getOrNull() },
            dynamicRanges = drs.map(::dynamicRangeName),
            qualitiesSdr = sdrQ.map(::qualityName),
            qualitiesHlg = hlgQ.map(::qualityName),
            qualityResolutions = qRes,
            highSpeedQualities = hs,
            frameRateRanges = info.supportedFrameRateRanges.sortedWith(compareBy({ it.upper }, { it.lower })),
            stillFormats = ImageCapture.getImageCaptureCapabilities(info).supportedOutputFormats.map(::stillFormatName),
            zslSupported = info.isZslSupported,
            stabilizationSupported = vc.isStabilizationSupported,
            torchStrength = info.isTorchStrengthSupported,
            lowLightBoost = info.isLowLightBoostSupported,
            extensions = extModes,
            hasFlash = info.hasFlashUnit(),
            sessionProbes = probeCameraX(info, sdrQ, hlgQ),
        )
    }.onFailure { Log.w(TAG, "CameraX describe failed", it) }.getOrNull()

    /**
     * Asks CameraX whether representative use-case combinations can be configured, without binding. These are the
     * combinations the app's modes actually request, so a mode is only offered when its probe passes.
     */
    private fun probeCameraX(info: CameraInfo, sdr: List<Quality>, hlg: List<Quality>): List<SessionProbe> {
        val probes = mutableListOf<SessionProbe>()
        fun probe(name: String, build: () -> SessionConfig) {
            probes += runCatching {
                SessionProbe(name, if (info.isSessionConfigSupported(build())) ProbeResult.SUPPORTED else ProbeResult.UNSUPPORTED)
            }.getOrElse { SessionProbe(name, ProbeResult.ERROR, "${it.javaClass.simpleName}: ${it.message}") }
        }
        fun video(q: Quality, dr: DynamicRange = DynamicRange.SDR, stab: Boolean = false) =
            VideoCapture.Builder(Recorder.Builder().setQualitySelector(QualitySelector.from(q)).build())
                .setDynamicRange(dr).apply { if (stab) setVideoStabilizationEnabled(true) }.build()
        val formats = ImageCapture.getImageCaptureCapabilities(info).supportedOutputFormats
        probe("Preview + JPEG") { SessionConfig(listOf(Preview.Builder().build(), ImageCapture.Builder().build())) }
        if (ImageCapture.OUTPUT_FORMAT_RAW_JPEG in formats) probe(ProbeNames.RAW_JPEG) {
            SessionConfig(listOf(Preview.Builder().build(),
                ImageCapture.Builder().setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW_JPEG).build()))
        }
        if (ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR in formats) probe(ProbeNames.ULTRA_HDR) {
            SessionConfig(listOf(Preview.Builder().build(),
                ImageCapture.Builder().setOutputFormat(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR).build()))
        }
        for (q in sdr.filter { it == Quality.UHD || it == Quality.FHD || it == Quality.HD }) {
            val qn = qualityName(q)
            probe("Preview + Video $qn + JPEG") {
                SessionConfig(listOf(Preview.Builder().build(), video(q), ImageCapture.Builder().build()))
            }
            probe("Preview + Video $qn + Analysis") {
                SessionConfig(listOf(Preview.Builder().build(), video(q), ImageAnalysis.Builder().build()))
            }
            probe(ProbeNames.stabilised(qn)) { SessionConfig(listOf(Preview.Builder().build(), video(q, stab = true))) }
            for (fps in listOf(24, 25, 30, 60)) probe(ProbeNames.video(qn, fps, hlg = false)) {
                SessionConfig.Builder(Preview.Builder().build(), video(q)).setFrameRateRange(Range(fps, fps)).build()
            }
            if (q in hlg) for (fps in listOf(24, 30, 60)) probe(ProbeNames.video(qn, fps, hlg = true)) {
                SessionConfig.Builder(Preview.Builder().setDynamicRange(DynamicRange.HLG_10_BIT).build(), video(q, DynamicRange.HLG_10_BIT))
                    .setFrameRateRange(Range(fps, fps)).build()
            }
        }
        return probes
    }

    private fun encoders(): List<EncoderInfo> {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        val mimes = listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC, "video/apv", MediaFormat.MIMETYPE_VIDEO_AV1)
        return list.filter { it.isEncoder }.flatMap { ci ->
            ci.supportedTypes.filter { it in mimes }.mapNotNull { mime ->
                runCatching {
                    val caps = ci.getCapabilitiesForType(mime)
                    val v = caps.videoCapabilities
                    EncoderInfo(
                        name = ci.name,
                        mime = mime,
                        hardware = ci.isHardwareAccelerated,
                        maxSize = v?.let { Size(it.supportedWidths.upper, it.supportedHeights.upper) },
                        profiles = caps.profileLevels.map { profileLabel(mime, it.profile) }.distinct(),
                        bitrateRange = v?.bitrateRange,
                        fps4k = v?.takeIf { it.isSizeSupported(3840, 2160) }?.getSupportedFrameRatesFor(3840, 2160),
                        fps1080 = v?.takeIf { it.isSizeSupported(1920, 1080) }?.getSupportedFrameRatesFor(1920, 1080),
                    )
                }.getOrNull()
            }
        }
    }

    /**
     * Lens choices come from focal-length evidence: 35 mm-equivalent focal of each physical sensor divided by that of
     * the default back camera. A physical lens is only offered if it belongs to a logical camera the app can open and
     * its ratio lies inside that camera's zoom-ratio range; otherwise standalone camera ids are offered as-is.
     */
    private fun buildLenses(c2: List<Camera2Info>, cx: List<CameraXInfo>): List<LensOption> {
        val out = mutableListOf<LensOption>()
        val openable = cx.map { it.cameraId }.toSet()
        for (facing in listOf(CameraSelector.LENS_FACING_BACK, CameraSelector.LENS_FACING_FRONT)) {
            val fName = if (facing == CameraSelector.LENS_FACING_BACK) "BACK" else "FRONT"
            val logicals = c2.filter { !it.isPhysicalOnly && it.facing == fName && it.id in openable }
            val primary = logicals.firstOrNull() ?: continue
            val baseEquiv = primary.lens.equivFocalMm
            if (facing == CameraSelector.LENS_FACING_FRONT) {
                out += LensOption("front:${primary.id}", "Front", LensRole.FRONT, facing, primary.id, null, 1f,
                    primary.lens.equivFocalMm, "camera ${primary.id}")
                continue
            }
            val zr = primary.zoomRatioRange
            out += LensOption("back:${primary.id}:1", "1×", LensRole.MAIN, facing, primary.id, null, 1f, baseEquiv,
                "camera ${primary.id} default (${primary.lens.focalLengthsMm.firstOrNull()} mm)")
            val candidates = primary.physicalIds.mapNotNull { pid -> c2.firstOrNull { it.id == pid } } +
                logicals.drop(1)
            for (cam in candidates) {
                val eq = cam.lens.equivFocalMm ?: continue
                if (baseEquiv == null) continue
                val ratio = eq / baseEquiv
                if (abs(ratio - 1f) < 0.12f) continue
                val inLogical = cam.id in primary.physicalIds && zr != null && ratio >= zr.lower - 0.02f && ratio <= zr.upper
                val role = if (ratio < 1f) LensRole.ULTRA_WIDE else LensRole.TELE
                val label = ratioLabel(ratio)
                if (out.any { it.label == label }) continue
                if (inLogical) {
                    out += LensOption("back:${primary.id}:$label", label, role, facing, primary.id, cam.id,
                        ratio.coerceAtLeast(zr!!.lower), eq,
                        "physical ${cam.id} of ${primary.id}: ${cam.lens.focalLengthsMm.firstOrNull()} mm, ${"%.0f".format(eq)} mm-eq")
                } else if (cam.id in openable) {
                    out += LensOption("back:${cam.id}", label, role, facing, cam.id, null, 1f, eq,
                        "standalone camera ${cam.id}: ${"%.0f".format(eq)} mm-eq")
                }
            }
        }
        return out.sortedWith(compareBy({ it.facing != CameraSelector.LENS_FACING_BACK }, { it.equivFocalMm ?: 0f }))
    }

    private fun buildNotes(c2: List<Camera2Info>, cx: List<CameraXInfo>): List<String> = buildList {
        val back = c2.filter { it.facing == "BACK" }
        if (back.none { it.capabilities.contains("ULTRA_HIGH_RESOLUTION_SENSOR") })
            add("No camera advertises ULTRA_HIGH_RESOLUTION_SENSOR: full 200 MP readout is not available to this app.")
        if (c2.none { it.colorCorrectionModes.contains(3) })
            add("COLOR_CORRECTION_MODE_CCT not offered: Kelvin white balance is an approximation via RGGB gains.")
        if (c2.all { it.aePriorityModes.size <= 1 })
            add("No AE priority modes: shutter priority is implemented app-side (closed-loop ISO).")
        val hidden = c2.filter { it.isPhysicalOnly }.map { it.id }
        if (hidden.isNotEmpty()) add("Physical sub-cameras readable by this app: ${hidden.joinToString()}.")
        val drs = c2.flatMap { it.dynamicRangeProfiles }.toSet()
        if ("HDR10" !in drs && "HDR10_PLUS" !in drs && drs.none { it.startsWith("DOLBY") })
            add("Dynamic range profiles: ${drs.joinToString()} — no HDR10/HDR10+/Dolby Vision capture path.")
        add("Samsung Log: no public API is documented; native Log capture is not offered.")
        if (cx.isEmpty()) add("CameraX reported no cameras.")
    }

    companion object {
        @OptIn(ExperimentalCamera2Interop::class)
        fun selectorFor(cameraId: String): CameraSelector = CameraSelector.Builder()
            .addCameraFilter { infos ->
                infos.filter { Camera2CameraInfo.from(it).cameraId == cameraId }
            }.build()

        fun ratioLabel(r: Float): String = when {
            r < 1f -> "%.1f×".format(r).replace(".0×", "×")
            abs(r - r.roundToInt()) < 0.35f -> "${r.roundToInt()}×"
            else -> "%.1f×".format(r)
        }

        fun qualityName(q: Quality): String = when (q) {
            Quality.UHD -> "UHD"; Quality.FHD -> "FHD"; Quality.HD -> "HD"; Quality.SD -> "SD"
            else -> q.toString()
        }

        fun dynamicRangeName(d: DynamicRange): String = when (d) {
            DynamicRange.SDR -> "SDR"
            DynamicRange.HLG_10_BIT -> "HLG_10_BIT"
            DynamicRange.HDR10_10_BIT -> "HDR10"
            DynamicRange.HDR10_PLUS_10_BIT -> "HDR10_PLUS"
            DynamicRange.DOLBY_VISION_10_BIT -> "DOLBY_VISION_10"
            DynamicRange.DOLBY_VISION_8_BIT -> "DOLBY_VISION_8"
            else -> d.toString()
        }

        fun stillFormatName(f: Int): String = when (f) {
            ImageCapture.OUTPUT_FORMAT_JPEG -> "JPEG"
            ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR -> "JPEG_ULTRA_HDR"
            ImageCapture.OUTPUT_FORMAT_RAW -> "RAW"
            ImageCapture.OUTPUT_FORMAT_RAW_JPEG -> "RAW_JPEG"
            else -> "FORMAT_$f"
        }

        fun profileLabel(mime: String, p: Int): String = when (mime) {
            MediaFormat.MIMETYPE_VIDEO_HEVC -> when (p) {
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain -> "Main"
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 -> "Main10"
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 -> "Main10HDR10"
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus -> "Main10HDR10Plus"
                else -> "p$p"
            }
            MediaFormat.MIMETYPE_VIDEO_AVC -> when (p) {
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline -> "Baseline"
                MediaCodecInfo.CodecProfileLevel.AVCProfileMain -> "Main"
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh -> "High"
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10 -> "High10"
                else -> "p$p"
            }
            else -> "p$p"
        }

        @Suppress("unused")
        fun rangeStr(r: Range<*>?) = r?.let { "${it.lower}-${it.upper}" } ?: "-"
    }
}

/** Probe names shared by the repository (writer) and the settings validator (reader). */
object ProbeNames {
    const val RAW_JPEG = "Preview + RAW+JPEG"
    const val ULTRA_HDR = "Preview + Ultra HDR"
    fun video(quality: String, fps: Int, hlg: Boolean) = "Preview + Video $quality${if (hlg) " HLG10" else ""} @$fps"
    fun stabilised(quality: String) = "Preview + Video $quality stabilised"
}

fun CameraXInfo.probeOk(name: String) = sessionProbes.any { it.name == name && it.result == ProbeResult.SUPPORTED }
