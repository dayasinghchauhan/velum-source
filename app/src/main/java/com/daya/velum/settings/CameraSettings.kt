package com.daya.velum.settings

/** [extension] names the vendor still extension the mode runs on (CameraX ExtensionMode), if any. */
enum class CaptureMode(val label: String, val isVideo: Boolean, val isPro: Boolean, val extension: String? = null) {
    NIGHT("Night", false, false, "NIGHT"),
    PORTRAIT("Portrait", false, false, "BOKEH"),
    PHOTO("Photo", false, false),
    /** Multi-frame long exposure (average or lighten blend of full-resolution frames). */
    LONG_EXPOSURE("Long exposure", false, false),
    PRO_PHOTO("Pro", false, true),
    VIDEO("Video", true, false),
    PRO_VIDEO("Pro Video", true, true),
    CINEMA("Film", true, true),
    /** Cinematic video: depth-of-field blur, subject tracking, rack focus, auto zoom, smart framing. */
    MOVIE("Movie", true, false),
    /** Dual video: back and front cameras at once, composited picture-in-picture into one recording. */
    DUAL("Dual video", true, false),
    /** True slow motion via a Camera2 high-speed session (1080p 120/240 fps). */
    SLOW_MO("Slow motion", true, false),
    /** Vlog: directed multi-shot templates stitched into one clip. */
    VLOG("Vlog", true, false),
    /** Movie effects: slow shutter, time freeze, parallel world, time-lapse, night time-lapse. */
    EFFECTS("Effects", true, false),
    /** Close-up photos on the back camera that focuses closest (ultra-wide, 5 cm). */
    MACRO("Macro", false, false),
    /** Night sky: long aligned stack of maximum-length exposures. */
    ASTRO("Astro", false, false),
    /** Burst of full-resolution frames; the sharpest one is saved. */
    BEST_SHOT("Best shot", false, false),
    /** Two shots joined down the middle so the same person appears twice. */
    CLONE("Clone", false, false),
}

enum class MovieEffect(val label: String, val description: String) {
    SLOW_SHUTTER("Slow shutter", "Moving things leave light trails while still things stay sharp. Best on a steady phone."),
    TIME_FREEZE("Time freeze", "Tap Freeze while recording: the background stops, people keep moving."),
    PARALLEL("Parallel world", "Mirrors the frame across its centre for a reflected, symmetric world."),
    TIMELAPSE("Time-lapse", "Speeds time up. Keep the phone still; audio is not recorded."),
    NIGHT_TIMELAPSE("Night time-lapse", "Longer 1/8 s exposures for city lights and traffic trails at night. Use a tripod."),
}

data class MovieSettings(
    /** Depth-of-field strength, 0 = off. */
    val blur: Float = 0.6f,
    val smartFraming: Boolean = false,
    val zoomFrom: Float = 1f,
    val zoomTo: Float = 2f,
    val zoomSeconds: Int = 4,
)

data class EffectSettings(
    val effect: MovieEffect = MovieEffect.SLOW_SHUTTER,
    /** Slow shutter trail length 0..1. */
    val trail: Float = 0.6f,
    /** Time-lapse speed-up factor. */
    val speed: Int = 10,
)

/** Modes shown directly on the rail; the rest live in the More grid (with a one-line description each). */
val RAIL_MODES = listOf(CaptureMode.NIGHT, CaptureMode.PORTRAIT, CaptureMode.PHOTO, CaptureMode.VIDEO, CaptureMode.MOVIE, CaptureMode.PRO_PHOTO)
val MORE_MODES = listOf(
    CaptureMode.PRO_VIDEO to "Manual video: P/S/M exposure, focus, white balance, zebra and peaking.",
    CaptureMode.CINEMA to "Film creator: scene recipes, looks and cinema framing 2.39 / 2.76.",
    CaptureMode.LONG_EXPOSURE to "Crowd removal, light trails, silky water, light painting, star trails.",
    CaptureMode.VLOG to "Directed multi-shot templates joined into one clip, with your music.",
    CaptureMode.DUAL to "Back and front cameras together in one picture-in-picture video.",
    CaptureMode.SLOW_MO to "Real high-speed capture at 1080p 120 or 240 fps, played back slowed down.",
    CaptureMode.EFFECTS to "Slow shutter, time freeze, parallel world, time-lapse, night time-lapse.",
    CaptureMode.MACRO to "Close-ups from 3 cm: flowers, food, textures. Uses the ultra-wide lens.",
    CaptureMode.ASTRO to "Stars and night sky: 30 s to 4 min of aligned frames. Tripod or steady surface.",
    CaptureMode.BEST_SHOT to "Shoots a quick burst and keeps the sharpest frame: kids, pets, moving hands.",
    CaptureMode.CLONE to "Two shots, one picture: the same person on both sides of the frame.",
)

enum class PhotoFormat(val label: String) { JPEG("JPEG"), ULTRA_HDR("Ultra HDR"), RAW_JPEG("RAW+JPEG") }
enum class FlashMode(val label: String) { OFF("Off"), AUTO("Auto"), ON("On"), TORCH("Torch") }
enum class PhotoAspect(val label: String, val w: Int, val h: Int) { R4_3("4:3", 4, 3), R16_9("16:9", 16, 9), R1_1("1:1", 1, 1), XPAN("65:24", 65, 24) }
enum class VideoQuality(val label: String) { UHD("4K"), FHD("1080p"), HD("720p") }
enum class DynamicRangeMode(val label: String) { SDR("SDR"), HLG("HLG 10-bit") }
enum class Stabilization(val label: String) { OFF("Off"), ON("Standard"), PREVIEW("Enhanced") }
enum class ExposureProgram(val label: String) { P("P"), S("S"), M("M") }
enum class WbMode(val label: String, val awb: Int) {
    AUTO("Auto", 1), INCANDESCENT("Tungsten", 2), FLUORESCENT("Fluorescent", 3), DAYLIGHT("Daylight", 5),
    CLOUDY("Cloudy", 6), KELVIN("Kelvin", 0),
}
/** ISP detail rendering. STANDARD = camera default sharpening; NATURAL = minimal edge enhancement, finer texture. */
enum class Detail(val label: String) { STANDARD("Standard"), NATURAL("Natural") }

enum class GridType(val label: String) { NONE("Off"), THIRDS("Thirds"), CENTER("Center"), GOLDEN("Phi") }

/** Cinema framing ratio, rendered as guides and optionally applied as an output crop. */
enum class CineRatio(val label: String, val ratio: Float) {
    R16_9("16:9", 16f / 9f), R2_1("2:1", 2f), R239("2.39:1", 2.39f), R276("2.76:1", 2.76f)
}
enum class CineFraming(val label: String) { GUIDE("Guide only"), CROP("Crop output"), BOTH("Guide + crop") }
enum class LookTarget(val label: String) { MONITOR("Monitor only"), BAKED("Baked into file") }

data class ExposureSettings(
    val program: ExposureProgram = ExposureProgram.P,
    val evIndex: Int = 0,
    val iso: Int = 100,
    val shutterNs: Long = 10_000_000L,
    val aeLock: Boolean = false,
)

data class FocusSettings(val manual: Boolean = false, val diopters: Float = 0f, val afLock: Boolean = false)

data class WbSettings(val mode: WbMode = WbMode.AUTO, val kelvin: Int = 5500, val awbLock: Boolean = false)

data class VideoSettings(
    val quality: VideoQuality = VideoQuality.UHD,
    val fps: Int = 30,
    val dynamicRange: DynamicRangeMode = DynamicRangeMode.SDR,
    val stabilization: Stabilization = Stabilization.ON,
    /** 0 = encoder default. */
    val bitrateMbps: Int = 0,
    val audio: Boolean = true,
)

data class MonitorSettings(
    val zebra: Boolean = false,
    val zebraPercent: Int = 95,
    val peaking: Boolean = false,
    val falseColor: Boolean = false,
    val grey: Boolean = false,
    val histogram: Boolean = false,
    val grid: GridType = GridType.NONE,
    val level: Boolean = false,
    val centerMark: Boolean = false,
    val safeArea: Boolean = false,
)

data class LookSettings(
    val lookId: String = "neutral",
    val intensity: Float = 1f,
    val target: LookTarget = LookTarget.MONITOR,
    /** Photo modes: keep an ungraded copy next to the graded JPEG. */
    val keepOriginal: Boolean = true,
)

data class CineSettings(val ratio: CineRatio = CineRatio.R239, val framing: CineFraming = CineFraming.GUIDE)

/**
 * The single source of truth for what the camera should be doing. Immutable; every change produces a new value that
 * [com.daya.velum.camera.CameraEngine] diff-applies.
 */
data class CameraSettings(
    val mode: CaptureMode = CaptureMode.PHOTO,
    val lensKey: String = "",
    val zoom: Float = 1f,
    val lockPhysicalLens: Boolean = false,
    val flash: FlashMode = FlashMode.OFF,
    val timerSec: Int = 0,
    val photoFormat: PhotoFormat = PhotoFormat.JPEG,
    val photoAspect: PhotoAspect = PhotoAspect.R4_3,
    val jpegQuality: Int = 95,
    val exposure: ExposureSettings = ExposureSettings(),
    val focus: FocusSettings = FocusSettings(),
    val wb: WbSettings = WbSettings(),
    val video: VideoSettings = VideoSettings(),
    val monitor: MonitorSettings = MonitorSettings(),
    val photoLook: LookSettings = LookSettings(target = LookTarget.BAKED),
    val videoLook: LookSettings = LookSettings(),
    val cine: CineSettings = CineSettings(),
    val movie: MovieSettings = MovieSettings(),
    val longExposure: com.daya.velum.imaging.LongExposureMode = com.daya.velum.imaging.LongExposureMode.CROWD,
    /** Long exposure duration in seconds; 0 = the mode's default. */
    val leSeconds: Int = 0,
    val astroSeconds: Int = 60,
    /** Photo: keep a short silent clip around each photo (Google Motion Photo format). */
    val motionPhoto: Boolean = false,
    /** Video: keep the horizon level while the phone rolls (GPU rotation with a small crop). */
    val horizonLock: Boolean = false,
    /** Depth-blur strength for Portrait at 3x/5x (0.1..1). */
    val portraitBlur: Float = 0.6f,
    val bestKeep: Int = 1,
    val cloneFirstLeft: Boolean = true,
    /** Dual video: true = front camera is the main picture. */
    val dualSwap: Boolean = false,
    /** Dual video inset corner: 0 TL, 1 TR, 2 BL, 3 BR. */
    val dualCorner: Int = 1,
    val slowMoFps: Int = 240,
    val photoFrame: com.daya.velum.imaging.PhotoFrame = com.daya.velum.imaging.PhotoFrame.OFF,
    val effects: EffectSettings = EffectSettings(),
    val shutterSound: Boolean = true,
    val haptics: Boolean = true,
    val proControls: Boolean = false,
    /** Face-priority autofocus/exposure/white balance using the camera's face detector. */
    val faceDetect: Boolean = true,
    /** Camera HDR scene mode for stills in auto modes (multi-frame inside the ISP). */
    val autoHdr: Boolean = false,
    val detail: Detail = Detail.STANDARD,
) {
    val look get() = if (mode.isVideo) videoLook else photoLook
}
