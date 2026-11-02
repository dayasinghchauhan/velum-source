package com.daya.velum.presets

import com.daya.velum.capability.DeviceCapabilities
import com.daya.velum.settings.*

enum class RecipeKind { PHOTO, VIDEO }

/**
 * A shooting recipe: a coordinated set of *intents*. Each field is optional; applying a recipe only touches the
 * fields it sets, and every field that the hardware cannot honour is reported back instead of being dropped silently.
 */
data class Recipe(
    val id: String,
    val name: String,
    val kind: RecipeKind,
    val description: String,
    val tip: String,
    val lookId: String? = null,
    val intensity: Float? = null,
    val evStops: Float? = null,
    val wb: WbMode? = null,
    val kelvin: Int? = null,
    val program: ExposureProgram? = null,
    val shutterNs: Long? = null,
    val iso: Int? = null,
    val fps: Int? = null,
    val quality: VideoQuality? = null,
    val stabilization: Stabilization? = null,
    val photoAspect: PhotoAspect? = null,
    val cineRatio: CineRatio? = null,
    val zebra: Boolean? = null,
    val peaking: Boolean? = null,
    val grid: GridType? = null,
    val focusInfinity: Boolean? = null,
    val night: Boolean? = null,
    val photoFormat: PhotoFormat? = null,
    val swatch: Long = 0xFF6B6B6B,
    val builtIn: Boolean = true,
)

data class RecipeResult(val settings: CameraSettings, val applied: List<String>, val skipped: List<String>)

object RecipeEngine {

    fun apply(r: Recipe, base: CameraSettings, caps: DeviceCapabilities?): RecipeResult {
        val applied = mutableListOf<String>(); val skipped = mutableListOf<String>()
        var s = base
        val video = r.kind == RecipeKind.VIDEO
        if (video && !s.mode.isVideo) s = s.copy(mode = CaptureMode.CINEMA)
        if (!video && s.mode.isVideo) s = s.copy(mode = CaptureMode.PHOTO)
        val a = SettingsValidator.availability(caps, s)

        r.lookId?.let { id ->
            val ls = (if (video) s.videoLook else s.photoLook).copy(lookId = id, intensity = r.intensity ?: 1f)
            s = if (video) s.copy(videoLook = ls) else s.copy(photoLook = ls)
            applied += "Look"
        }
        r.evStops?.let { ev ->
            val idx = (ev / a.evStep).toInt().coerceIn(a.evRange.first, a.evRange.last)
            s = s.copy(exposure = s.exposure.copy(evIndex = idx)); applied += "Exposure %+.1f EV".format(idx * a.evStep)
        }
        r.wb?.let { wb ->
            if (wb == WbMode.KELVIN && !a.manualWb) skipped += "Kelvin white balance (not supported, auto kept)"
            else { s = s.copy(wb = s.wb.copy(mode = wb, kelvin = r.kelvin ?: s.wb.kelvin)); applied += "White balance ${wb.label}${r.kelvin?.let { " ${it}K" } ?: ""}" }
        }
        if (r.program != null || r.shutterNs != null || r.iso != null) {
            if (!a.manualSensor) skipped += "Manual shutter/ISO (lens has no manual sensor control; auto exposure kept)"
            else {
                var e = s.exposure
                r.program?.let { e = e.copy(program = it) }
                r.shutterNs?.let { e = e.copy(shutterNs = it) }
                r.iso?.let { e = e.copy(iso = it) }
                s = s.copy(exposure = e); applied += "Exposure ${e.program.label}"
            }
        }
        if (video) {
            var v = s.video
            r.quality?.let { if (it in a.videoQualities) { v = v.copy(quality = it); applied += it.label } else skipped += "${it.label} (not available)" }
            r.fps?.let { f ->
                if (f in a.fpsFor(v.quality, v.dynamicRange)) { v = v.copy(fps = f); applied += "$f fps" }
                else skipped += "$f fps at ${v.quality.label} (not supported here; ${v.fps} fps kept)"
            }
            r.stabilization?.let { st -> if (st in a.stabilization) { v = v.copy(stabilization = st); applied += "Stabilisation ${st.label}" } else skipped += "Stabilisation ${st.label}" }
            s = s.copy(video = v)
            r.cineRatio?.let { s = s.copy(cine = s.cine.copy(ratio = it)); applied += "Frame ${it.label}" }
        } else {
            r.photoAspect?.let { s = s.copy(photoAspect = it); applied += "Aspect ${it.label}" }
            r.photoFormat?.let { f ->
                val ok = when (f) { PhotoFormat.RAW_JPEG -> a.raw; PhotoFormat.ULTRA_HDR -> a.ultraHdr; else -> true }
                if (ok) { s = s.copy(photoFormat = f); applied += f.label } else skipped += "${f.label} (not available)"
            }
            r.night?.let { n -> if (n && !a.nightExtension) skipped += "Night processing (not offered by this camera)" else if (n) { s = s.copy(mode = CaptureMode.NIGHT); applied += "Night mode" } }
        }
        r.zebra?.let { s = s.copy(monitor = s.monitor.copy(zebra = it)) }
        r.peaking?.let { s = s.copy(monitor = s.monitor.copy(peaking = it)) }
        r.grid?.let { s = s.copy(monitor = s.monitor.copy(grid = it)) }
        r.focusInfinity?.let { if (it && a.manualSensor) { s = s.copy(focus = s.focus.copy(manual = true, diopters = 0f)); applied += "Focus ∞" } else if (it) skipped += "Manual focus" }
        val v = SettingsValidator.validate(caps, s)
        return RecipeResult(v.settings, applied, skipped + v.changes)
    }

    private const val MS = 1_000_000L

    val photo: List<Recipe> = listOf(
        Recipe("street", "Street", RecipeKind.PHOTO, "Fast, contrasty documentary look.", "Pre-focus at a distance and wait for the subject.",
            lookId = "documentary", grid = GridType.THIRDS, swatch = 0xFF5C6670),
        Recipe("golden", "Golden Hour", RecipeKind.PHOTO, "Keeps warm light warm instead of neutralising it.", "Shoot toward the side light, not straight into the sun.",
            lookId = "golden_film", intensity = 0.8f, wb = WbMode.DAYLIGHT, evStops = -0.3f, swatch = 0xFFC9893A),
        Recipe("blue_hour", "Blue Hour", RecipeKind.PHOTO, "Cool, deep, protects lit windows.", "Brace the phone; the camera may choose slow shutter speeds.",
            lookId = "cool_film", evStops = -0.3f, swatch = 0xFF3A5A8C),
        Recipe("night_city", "Night City", RecipeKind.PHOTO, "Multi-frame Night extension with highlight protection.", "Hold still until the capture finishes.",
            lookId = "moody_night", intensity = 0.7f, night = true, evStops = -0.3f, swatch = 0xFF27304A),
        Recipe("indoor_warm", "Indoor Warm", RecipeKind.PHOTO, "Tungsten white balance and soft contrast.", "Turn off mixed light sources if you can.",
            lookId = "soft_portrait", intensity = 0.6f, wb = WbMode.INCANDESCENT, swatch = 0xFFA0703E),
        Recipe("portrait", "Portrait", RecipeKind.PHOTO, "Gentle contrast and natural skin.", "Use the 3× lens and tap the eyes to focus.",
            lookId = "soft_portrait", photoAspect = PhotoAspect.R4_3, swatch = 0xFFB98B76),
        Recipe("landscape", "Landscape", RecipeKind.PHOTO, "Rich colour, infinity focus, horizon grid.", "Turn on the level to keep the horizon straight.",
            lookId = "vibrant", focusInfinity = true, grid = GridType.THIRDS, photoFormat = PhotoFormat.RAW_JPEG, swatch = 0xFF4D7A55),
        Recipe("architecture", "Architecture", RecipeKind.PHOTO, "Neutral, crisp, with a thirds grid.", "Keep the phone vertical to avoid converging lines.",
            lookId = "clean_commercial", grid = GridType.THIRDS, swatch = 0xFF8A8F96),
        Recipe("food", "Food & Product", RecipeKind.PHOTO, "Clean, bright, slightly warm.", "Shoot near a window; diffuse direct sun.",
            lookId = "clean_commercial", wb = WbMode.KELVIN, kelvin = 5200, evStops = 0.3f, photoAspect = PhotoAspect.R1_1, swatch = 0xFFD6B48A),
        Recipe("macro", "Macro & Close-up", RecipeKind.PHOTO, "Peaking on to judge thin focus.", "The 3× lens focuses closer than it looks; step back if focus fails.",
            lookId = "natural", peaking = true, swatch = 0xFF6E8B4A),
        Recipe("pets", "Pets & Motion", RecipeKind.PHOTO, "Shutter priority 1/500 to freeze movement.", "Use burst: hold the shutter.",
            lookId = "vibrant", intensity = 0.6f, program = ExposureProgram.S, shutterNs = 2 * MS, swatch = 0xFF9C6B45),
        Recipe("sports", "Sports & Action", RecipeKind.PHOTO, "Shutter priority 1/1000.", "Pan with the subject while holding the shutter.",
            lookId = "rich_contrast", intensity = 0.6f, program = ExposureProgram.S, shutterNs = 1 * MS, swatch = 0xFFB23A3A),
        Recipe("rain", "Rainy Weather", RecipeKind.PHOTO, "Muted cool tones that suit overcast light.", "Look for reflections in puddles.",
            lookId = "muted_cinema", evStops = 0.3f, swatch = 0xFF55697A),
        Recipe("travel_doc", "Travel Documentary", RecipeKind.PHOTO, "Authentic colour, RAW backup.", "Capture context: wide, then details.",
            lookId = "authentic", photoFormat = PhotoFormat.RAW_JPEG, swatch = 0xFF7A6A55),
        Recipe("concert", "Concert & Low Light", RecipeKind.PHOTO, "Underexposed to keep stage lights from clipping.", "Wait for peak lighting; use 3× from the crowd.",
            lookId = "moody_night", evStops = -1f, swatch = 0xFF4A2D5C),
        Recipe("moon", "Moon", RecipeKind.PHOTO, "Manual 1/250 ISO 50, infinity focus, 5× lens.",
            "This is a manual exposure starting point for a bright moon; it does not synthesise detail.",
            program = ExposureProgram.M, shutterNs = 4 * MS, iso = 50, focusInfinity = true, evStops = 0f, swatch = 0xFF9DA3A8),
    )

    val video: List<Recipe> = listOf(
        Recipe("v_travel", "Cinematic Travel", RecipeKind.VIDEO, "Teal-amber, 24p, 2.39 frame, stabilised.", "Move slowly; let scenes play.",
            lookId = "teal_amber", intensity = 0.7f, fps = 24, cineRatio = CineRatio.R239, stabilization = Stabilization.ON, swatch = 0xFF2F6A6E),
        Recipe("v_nostalgia", "Warm Nostalgia", RecipeKind.VIDEO, "Golden, grainy, soft.", "Backlight works beautifully here.",
            lookId = "golden_film", fps = 24, swatch = 0xFFC08A3E),
        Recipe("v_street", "Street Film", RecipeKind.VIDEO, "Documentary colour, 24p, 180° shutter (1/48).", "Keep the camera at chest height.",
            lookId = "documentary", fps = 24, program = ExposureProgram.S, shutterNs = 20_833_333L, swatch = 0xFF505A64),
        Recipe("v_night", "Night Cinema", RecipeKind.VIDEO, "Moody night grade, 24p, zebra at 95%.", "Neon and street lamps are your key lights.",
            lookId = "moody_night", fps = 24, zebra = true, swatch = 0xFF222B44),
        Recipe("v_urban", "Urban Documentary", RecipeKind.VIDEO, "Muted, realistic, 30p.", "Hold shots for at least 5 seconds.",
            lookId = "muted_cinema", fps = 30, swatch = 0xFF5E6466),
        Recipe("v_portrait", "Portrait Film", RecipeKind.VIDEO, "Soft contrast for faces, 24p, 2:1.", "Use the 3× lens for flattering perspective.",
            lookId = "soft_portrait", fps = 24, cineRatio = CineRatio.R2_1, swatch = 0xFFB08470),
        Recipe("v_nature", "Nature Documentary", RecipeKind.VIDEO, "Natural colour, 4K 30p, peaking.", "Lock exposure on bright sky scenes.",
            lookId = "natural", quality = VideoQuality.UHD, fps = 30, peaking = true, swatch = 0xFF4E7A4C),
        Recipe("v_rain", "Moody Rain", RecipeKind.VIDEO, "Cool, muted, slightly faded.", "Shoot through glass for texture.",
            lookId = "cool_film", fps = 24, swatch = 0xFF4A5E70),
        Recipe("v_mono", "Dramatic Monochrome", RecipeKind.VIDEO, "High-contrast B&W, 24p.", "Look for hard light and shadows.",
            lookId = "dramatic_mono", fps = 24, swatch = 0xFF3A3A3A),
        Recipe("v_romantic", "Soft Romantic", RecipeKind.VIDEO, "Pastel highlights, lifted blacks.", "Soft window light suits this.",
            lookId = "soft_romantic", fps = 24, swatch = 0xFFC79AA0),
        Recipe("v_commercial", "Clean Commercial", RecipeKind.VIDEO, "Bright, neutral, 30p, 16:9.", "Even lighting matters more than the grade.",
            lookId = "clean_commercial", fps = 30, cineRatio = CineRatio.R16_9, swatch = 0xFFCFCFCF),
        Recipe("v_product", "Product Detail", RecipeKind.VIDEO, "Neutral, peaking for focus, 30p.", "Use manual focus and slow movements.",
            lookId = "clean_commercial", fps = 30, peaking = true, swatch = 0xFFB0A090),
        Recipe("v_sports", "Sports Motion", RecipeKind.VIDEO, "1080p 60, crisp shutter 1/250.", "60 fps can be slowed 2.5× in a 24p edit.",
            lookId = "rich_contrast", intensity = 0.5f, quality = VideoQuality.FHD, fps = 60, program = ExposureProgram.S, shutterNs = 4 * MS, swatch = 0xFFA83A32),
        Recipe("v_slowmo", "Slow Motion (60p)", RecipeKind.VIDEO, "Highest supported regular frame rate for slowing in edit.",
            "True high-speed (120/240) recording is not available through CameraX on this device yet.",
            quality = VideoQuality.FHD, fps = 60, program = ExposureProgram.S, shutterNs = 8 * MS, swatch = 0xFF6A4FA0),
        Recipe("v_home", "Retro Home Movie", RecipeKind.VIDEO, "Heavy grain, vignette, warm fade.", "Handheld wobble suits this look.",
            lookId = "home_movie", fps = 24, stabilization = Stabilization.OFF, swatch = 0xFF9E7A4A),
        Recipe("v_minimal", "Minimalist Film", RecipeKind.VIDEO, "Quiet, desaturated, 2.76 frame.", "Use negative space.",
            lookId = "minimal_film", fps = 24, cineRatio = CineRatio.R276, swatch = 0xFF8C8C84),
        Recipe("v_handheld", "Handheld Cinematic", RecipeKind.VIDEO, "Enhanced stabilisation, 24p, neutral film.", "Walk heel-to-toe, elbows in.",
            lookId = "neutral_film", fps = 24, stabilization = Stabilization.PREVIEW, swatch = 0xFF5A5048),
        Recipe("v_walk", "Walking Travel", RecipeKind.VIDEO, "Stabilised 30p, natural colour.", "Keep the horizon level with the level indicator.",
            lookId = "natural", fps = 30, stabilization = Stabilization.PREVIEW, swatch = 0xFF5F7A88),
    )

    fun all() = photo + video
}
