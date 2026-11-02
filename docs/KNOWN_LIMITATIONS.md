# Known limitations

Facts about this phone and public Android APIs, then open problems in Velum.

## Imposed by the device or public APIs (verified)

- **4K 60 fps** is not offered to third-party apps: 3840x2160 PRIVATE has a 33.3 ms minimum frame duration. Samsung
  Camera's 4K60 uses private vendor sessions.
- **200 MP** is not exposed (no ULTRA_HIGH_RESOLUTION_SENSOR; maximum array is the binned 4080x3060).
- **Samsung Log, HDR10 and Dolby Vision** recording profiles are not exposed; Velum records SDR or HLG 10-bit.
  (Log workflows are out of scope by decision.)
- **Samsung's own still processing** (its multi-frame engine, scene optimiser, AI processing) is not available. Velum
  gets the ISP's standard still JPEG, plus the NIGHT, BOKEH and FACE_RETOUCH extensions. There is no HDR extension.
- **EDGE_MODE and CONTROL_SCENE_MODE_HDR** made no measurable difference to still JPEGs in testing.
- **Auto-exposure priority and CCT white balance** do not exist in the HAL; shutter priority and Kelvin are app-side.
- **Telephoto lenses** can be pinned per stream; on the logical camera the HAL may crop the main sensor at 3x in dim
  or close scenes.
- **High-speed video** (120/240 fps) is 1080p and silent; CameraX has no high-speed profile on this phone.
- **CameraX concurrent camera** (Dual video) composes both cameras without per-camera rotation, so the front inset is
  upside down when the phone is held sideways.

## Open problems in Velum

- **Not yet compared with Samsung Camera** in daylight, backlight, with people, at 3x/5x or for moving subjects;
  one indoor scene has been measured (IMAGE_QUALITY_BENCHMARK.md). No colour target was available, so there are
  no colour-accuracy figures.
- **Auto HDR** has not been tested in a high-contrast scene.
- **Astro** has been tested indoors only (115 frames, 0 px drift). Not yet on a real night sky. Alignment is
  translation only, so star trails appear on the 2-4 minute settings; no dark-frame subtraction.
- **Movie mode** depth comes from a 256x256 monocular depth model: fine hair and thin objects get soft edges, and
  depth can flicker slightly. Capped at 30 fps.
- **Portrait at 3x/5x** blur is computed from a 256x256 depth map and person mask: hair edges are soft.
- **Motion photo** adds a continuously running silent video loop while on, which costs battery. The clip covers about
  1.5 s each side of the shutter.
- **Horizon lock** crops more as the tilt grows (up to x1.55 at 20 deg in 16:9).
- **Stabilised 1x video** shows about 1/1.26 of the photo width (Samsung's own: 1/1.2).
- **Best shot** ranks by sharpness only (shake, focus); it does not detect closed eyes or expressions.
- **Brand looks** reproduce how each maker describes its colour; they are not the makers' in-camera processing.
