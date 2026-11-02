package com.daya.velum.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.json.JSONObject

private val Context.store by preferencesDataStore("velum_settings")
private val KEY = stringPreferencesKey("camera_settings_v1")

interface CameraSettingsRepository {
    suspend fun load(): CameraSettings
    suspend fun save(s: CameraSettings)
}

/**
 * Persists settings as one JSON document. Deliberately NOT persisted (so a new session never starts in a confusing
 * state): zoom, AE/AF/AWB locks, timer, hold-to-compare. Unknown enum values fall back to defaults.
 */
class DataStoreSettingsRepository(private val context: Context) : CameraSettingsRepository {

    override suspend fun load(): CameraSettings =
        runCatching { context.store.data.first()[KEY]?.let { fromJson(JSONObject(it)) } }.getOrNull() ?: CameraSettings()

    override suspend fun save(s: CameraSettings) {
        context.store.edit { it[KEY] = toJson(s).toString() }
    }

    companion object {
        fun toJson(s: CameraSettings): JSONObject = JSONObject().apply {
            put("mode", s.mode.name); put("lens", s.lensKey); put("lockPhys", s.lockPhysicalLens)
            put("flash", s.flash.name); put("photoFormat", s.photoFormat.name); put("aspect", s.photoAspect.name)
            put("jpegQ", s.jpegQuality); put("sound", s.shutterSound); put("haptics", s.haptics); put("pro", s.proControls)
            put("face", s.faceDetect); put("hdr", s.autoHdr); put("detail", s.detail.name)
            put("exp", JSONObject().put("program", s.exposure.program.name).put("ev", s.exposure.evIndex)
                .put("iso", s.exposure.iso).put("shutter", s.exposure.shutterNs))
            put("focus", JSONObject().put("manual", s.focus.manual).put("d", s.focus.diopters.toDouble()))
            put("wb", JSONObject().put("mode", s.wb.mode.name).put("k", s.wb.kelvin))
            put("video", JSONObject().put("q", s.video.quality.name).put("fps", s.video.fps).put("dr", s.video.dynamicRange.name)
                .put("stab", s.video.stabilization.name).put("br", s.video.bitrateMbps).put("audio", s.video.audio))
            put("mon", JSONObject().put("zebra", s.monitor.zebra).put("zp", s.monitor.zebraPercent).put("peak", s.monitor.peaking)
                .put("fc", s.monitor.falseColor).put("grey", s.monitor.grey).put("hist", s.monitor.histogram)
                .put("grid", s.monitor.grid.name).put("level", s.monitor.level).put("center", s.monitor.centerMark)
                .put("safe", s.monitor.safeArea))
            put("pLook", look(s.photoLook)); put("vLook", look(s.videoLook))
            put("cine", JSONObject().put("ratio", s.cine.ratio.name).put("framing", s.cine.framing.name))
            put("movie", JSONObject().put("blur", s.movie.blur.toDouble()).put("smart", s.movie.smartFraming)
                .put("zf", s.movie.zoomFrom.toDouble()).put("zt", s.movie.zoomTo.toDouble()).put("zs", s.movie.zoomSeconds))
            put("le", s.longExposure.name)
            put("frame", s.photoFrame.name)
            put("horizon", s.horizonLock)
            put("motion", s.motionPhoto)
            put("fx", JSONObject().put("e", s.effects.effect.name).put("trail", s.effects.trail.toDouble()).put("speed", s.effects.speed))
        }

        private fun look(l: LookSettings) = JSONObject().put("id", l.lookId).put("i", l.intensity.toDouble())
            .put("t", l.target.name).put("orig", l.keepOriginal)

        private inline fun <reified E : Enum<E>> JSONObject.enum(k: String, d: E): E =
            optString(k).let { v -> enumValues<E>().firstOrNull { it.name == v } } ?: d

        fun fromJson(j: JSONObject): CameraSettings {
            val d = CameraSettings()
            val e = j.optJSONObject("exp") ?: JSONObject()
            val f = j.optJSONObject("focus") ?: JSONObject()
            val w = j.optJSONObject("wb") ?: JSONObject()
            val v = j.optJSONObject("video") ?: JSONObject()
            val m = j.optJSONObject("mon") ?: JSONObject()
            val c = j.optJSONObject("cine") ?: JSONObject()
            fun lookOf(o: JSONObject?, def: LookSettings) = if (o == null) def else LookSettings(
                lookId = o.optString("id", def.lookId), intensity = o.optDouble("i", def.intensity.toDouble()).toFloat().coerceIn(0f, 1f),
                target = o.enum("t", def.target), keepOriginal = o.optBoolean("orig", def.keepOriginal),
            )
            return d.copy(
                mode = j.enum("mode", d.mode), lensKey = j.optString("lens", ""), lockPhysicalLens = j.optBoolean("lockPhys", false),
                flash = j.enum("flash", d.flash).let { if (it == FlashMode.TORCH) FlashMode.OFF else it },
                photoFormat = j.enum("photoFormat", d.photoFormat), photoAspect = j.enum("aspect", d.photoAspect),
                jpegQuality = j.optInt("jpegQ", d.jpegQuality).coerceIn(70, 100),
                shutterSound = j.optBoolean("sound", true), haptics = j.optBoolean("haptics", true), proControls = j.optBoolean("pro", false),
                faceDetect = j.optBoolean("face", true), autoHdr = j.optBoolean("hdr", false), detail = j.enum("detail", Detail.STANDARD),
                exposure = ExposureSettings(program = e.enum("program", ExposureProgram.P), evIndex = e.optInt("ev", 0),
                    iso = e.optInt("iso", 100), shutterNs = e.optLong("shutter", 10_000_000L)),
                focus = FocusSettings(manual = f.optBoolean("manual", false), diopters = f.optDouble("d", 0.0).toFloat()),
                wb = WbSettings(mode = w.enum("mode", WbMode.AUTO), kelvin = w.optInt("k", 5500)),
                video = VideoSettings(quality = v.enum("q", VideoQuality.UHD), fps = v.optInt("fps", 30),
                    dynamicRange = v.enum("dr", DynamicRangeMode.SDR), stabilization = v.enum("stab", Stabilization.ON),
                    bitrateMbps = v.optInt("br", 0), audio = v.optBoolean("audio", true)),
                monitor = MonitorSettings(zebra = m.optBoolean("zebra"), zebraPercent = m.optInt("zp", 95).coerceIn(70, 100),
                    peaking = m.optBoolean("peak"), falseColor = m.optBoolean("fc"), grey = m.optBoolean("grey"),
                    histogram = m.optBoolean("hist"), grid = m.enum("grid", GridType.NONE), level = m.optBoolean("level"),
                    centerMark = m.optBoolean("center"), safeArea = m.optBoolean("safe")),
                photoLook = lookOf(j.optJSONObject("pLook"), d.photoLook),
                videoLook = lookOf(j.optJSONObject("vLook"), d.videoLook),
                cine = CineSettings(ratio = c.enum("ratio", CineRatio.R239), framing = c.enum("framing", CineFraming.GUIDE)),
                longExposure = j.enum("le", com.daya.velum.imaging.LongExposureMode.CROWD),
                photoFrame = j.enum("frame", com.daya.velum.imaging.PhotoFrame.OFF),
                horizonLock = j.optBoolean("horizon", false),
                motionPhoto = j.optBoolean("motion", false),
                movie = (j.optJSONObject("movie") ?: JSONObject()).let { m2 ->
                    MovieSettings(blur = m2.optDouble("blur", 0.6).toFloat().coerceIn(0f, 1f), smartFraming = m2.optBoolean("smart", false),
                        zoomFrom = m2.optDouble("zf", 1.0).toFloat(), zoomTo = m2.optDouble("zt", 2.0).toFloat(), zoomSeconds = m2.optInt("zs", 4).coerceIn(1, 15))
                },
                effects = (j.optJSONObject("fx") ?: JSONObject()).let { f2 ->
                    EffectSettings(effect = f2.enum("e", MovieEffect.SLOW_SHUTTER), trail = f2.optDouble("trail", 0.6).toFloat().coerceIn(0f, 1f),
                        speed = f2.optInt("speed", 10).coerceIn(2, 60))
                },
            )
        }
    }
}
