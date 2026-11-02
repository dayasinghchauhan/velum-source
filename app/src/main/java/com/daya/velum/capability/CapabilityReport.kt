package com.daya.velum.capability

import android.util.Range
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject

/** Machine-readable capability report (written to filesDir/capabilities.json) and a human summary. */
object CapabilityReport {

    fun toJson(c: DeviceCapabilities): JSONObject = JSONObject().apply {
        put("generatedAtMs", c.generatedAtMs)
        put("model", c.model); put("build", c.build); put("sdk", c.sdk)
        put("notes", JSONArray(c.notes))
        put("lenses", JSONArray(c.lenses.map { l ->
            JSONObject().put("key", l.key).put("label", l.label).put("role", l.role.name).put("facing", l.facing)
                .put("logicalId", l.logicalId).put("physicalId", l.physicalId ?: JSONObject.NULL)
                .put("zoomRatio", l.zoomRatio.toDouble()).put("equivFocalMm", l.equivFocalMm?.toDouble() ?: JSONObject.NULL)
                .put("evidence", l.evidence)
        }))
        put("camera2", JSONArray(c.camera2.map(::cam2)))
        put("cameraX", JSONArray(c.cameraX.map(::camX)))
        put("encoders", JSONArray(c.encoders.map { e ->
            JSONObject().put("name", e.name).put("mime", e.mime).put("hardware", e.hardware)
                .put("maxSize", sz(e.maxSize)).put("profiles", JSONArray(e.profiles))
                .put("bitrate", rng(e.bitrateRange)).put("fps4k", rng(e.fps4k)).put("fps1080", rng(e.fps1080))
        }))
    }

    private fun cam2(i: Camera2Info) = JSONObject().apply {
        put("id", i.id); put("physicalOnly", i.isPhysicalOnly); put("parent", i.parentLogicalId ?: JSONObject.NULL)
        put("facing", i.facing); put("hardwareLevel", i.hardwareLevel)
        put("capabilities", JSONArray(i.capabilities)); put("physicalIds", JSONArray(i.physicalIds))
        put("sensor", JSONObject().put("iso", rng(i.sensor.isoRange)).put("exposureNs", rng(i.sensor.exposureNsRange))
            .put("maxFrameDurationNs", i.sensor.maxFrameDurationNs ?: JSONObject.NULL)
            .put("physicalSizeMm", i.sensor.physicalSizeMm?.let { "${it.first}x${it.second}" } ?: JSONObject.NULL)
            .put("pixelArray", sz(i.sensor.pixelArray)).put("pixelArrayMaxRes", sz(i.sensor.pixelArrayMaxRes))
            .put("cfa", i.sensor.colorFilter ?: JSONObject.NULL))
        put("lens", JSONObject().put("focalMm", JSONArray(i.lens.focalLengthsMm.map { it.toDouble() }))
            .put("apertures", JSONArray(i.lens.apertures.map { it.toDouble() }))
            .put("equivMm", i.lens.equivFocalMm?.toDouble() ?: JSONObject.NULL)
            .put("minFocusDiopters", i.lens.minFocusDiopters?.toDouble() ?: JSONObject.NULL)
            .put("focusCalibration", i.lens.focusCalibration ?: JSONObject.NULL).put("ois", i.lens.ois))
        put("streams", JSONObject().put("jpeg", sizes(i.streams.jpeg)).put("yuv", sizes(i.streams.yuv))
            .put("priv", sizes(i.streams.priv)).put("raw", sizes(i.streams.raw)).put("heic", sizes(i.streams.heic))
            .put("jpegR", sizes(i.streams.jpegR)).put("maxResJpeg", sizes(i.streams.maxResJpeg))
            .put("maxResRaw", sizes(i.streams.maxResRaw))
            .put("highSpeed", JSONArray(i.streams.highSpeed.map { h ->
                JSONObject().put("size", sz(h.size)).put("fps", JSONArray(h.fps.map(::rng)))
            })))
        put("zoomRatioRange", rng(i.zoomRatioRange)); put("maxDigitalZoom", i.maxDigitalZoom?.toDouble() ?: JSONObject.NULL)
        put("evRange", rng(i.evRange)); put("evStep", i.evStep?.toDouble() ?: JSONObject.NULL)
        put("aeFpsRanges", JSONArray(i.aeFpsRanges.map(::rng)))
        put("afModes", JSONArray(i.afModes)); put("awbModes", JSONArray(i.awbModes)); put("aeModes", JSONArray(i.aeModes))
        put("colorCorrectionModes", JSONArray(i.colorCorrectionModes)); put("aePriorityModes", JSONArray(i.aePriorityModes))
        put("videoStabModes", JSONArray(i.videoStabModes)); put("flash", i.flash)
        put("dynamicRangeProfiles", JSONArray(i.dynamicRangeProfiles)); put("colorSpaces", JSONArray(i.colorSpaces))
        put("extensions", JSONArray(i.extensions))
        put("sessionProbes", JSONArray(i.sessionProbes.map {
            JSONObject().put("name", it.name).put("result", it.result.name).put("detail", it.detail)
        }))
    }

    private fun camX(i: CameraXInfo) = JSONObject().apply {
        put("cameraId", i.cameraId); put("facing", i.facing); put("intrinsicZoom", i.intrinsicZoom.toDouble())
        put("physicalIds", JSONArray(i.physicalIds)); put("dynamicRanges", JSONArray(i.dynamicRanges))
        put("qualitiesSdr", JSONArray(i.qualitiesSdr)); put("qualitiesHlg", JSONArray(i.qualitiesHlg))
        put("qualityResolutions", JSONObject(i.qualityResolutions.mapValues { sz(it.value) }))
        put("highSpeedQualities", JSONArray(i.highSpeedQualities))
        put("frameRateRanges", JSONArray(i.frameRateRanges.map(::rng)))
        put("stillFormats", JSONArray(i.stillFormats)); put("zsl", i.zslSupported)
        put("stabilization", i.stabilizationSupported); put("torchStrength", i.torchStrength)
        put("lowLightBoost", i.lowLightBoost); put("extensions", JSONArray(i.extensions)); put("flash", i.hasFlash)
        put("sessionProbes", JSONArray(i.sessionProbes.map {
            JSONObject().put("name", it.name).put("result", it.result.name).put("detail", it.detail)
        }))
    }

    private fun sz(s: Size?): Any = s?.let { "${it.width}x${it.height}" } ?: JSONObject.NULL
    private fun sizes(l: List<Size>) = JSONArray(l.map { "${it.width}x${it.height}" })
    private fun rng(r: Range<*>?): Any = r?.let { "${it.lower}-${it.upper}" } ?: JSONObject.NULL

    /** Short human-readable digest used by the diagnostics screen header and the markdown report. */
    fun summary(c: DeviceCapabilities): String = buildString {
        appendLine("${c.model} · SDK ${c.sdk} · ${c.build}")
        appendLine("Lenses: " + c.lenses.joinToString { "${it.label} (${it.evidence})" })
        for (x in c.cameraX) {
            appendLine("Camera ${x.cameraId}: SDR ${x.qualitiesSdr}, HLG ${x.qualitiesHlg}, still ${x.stillFormats}, " +
                "fps ${x.frameRateRanges.joinToString { "${it.lower}-${it.upper}" }}, slow-mo ${x.highSpeedQualities}, ext ${x.extensions}")
        }
        c.notes.forEach { appendLine("• $it") }
    }
}
