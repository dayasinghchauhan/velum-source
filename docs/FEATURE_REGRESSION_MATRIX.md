# Feature regression matrix

Status key: **Verified** = exercised on the S24 Ultra and the saved output inspected; **Verified (no crash)** = run in
the crash sweep but output not judged in detail; **Partial** = works with a stated gap; **No measured effect** = the
control reaches the camera but the camera ignores it for this output; **Unsupported** = not exposed by the device.
"Last check" is the date of the most recent device run.

## Photo modes

| Feature | Status | Notes | Last check |
|---|---|---|---|
| Photo (JPEG 4080x3060) | Verified | EXIF orientation correct; Samsung comparison in benchmark | 23 Oct |
| RAW+JPEG | Verified | DNG decodes (Bayer GBRG16) | 22 Oct |
| Ultra HDR | Verified | MPF + hdrgm gain map present; no GPU look on this format | 22 Oct |
| Aspect 4:3 / 16:9 / 1:1 / XPan 65:24 | Verified | XPan saves 4080x1506 | 23 Oct |
| Night (vendor extension) | Verified | brighter, cleaner in a dark scene (22 Oct); no difference at ISO ~1000 (23 Oct) | 23 Oct |
| Portrait 1x (vendor bokeh) | Verified | background blurred behind a person | 22 Oct |
| Portrait 3x/5x (Velum depth blur) | Verified | object scene; soft hair edges expected | 23 Oct |
| Pro (P/S/M, ISO, shutter, focus, WB, Kelvin) | Verified | M ISO 100 1/100 matches EXIF | 22 Oct |
| Long exposure (crowd, neon, silk, light paint, star trails, starry sky) | Verified | 36 frames in 4 s | 22 Oct |
| Astro (aligned stack) | Partial | indoor: 115 frames, 0 px drift; real sky untested | 23 Oct |
| Macro (ultra-wide, 5 cm) | Verified | binds camera 2, 4000x3000, 2.2 mm f/2.2 | 23 Oct |
| Best shot | Verified | 3 sharpest of 17 frames | 23 Oct |
| Clone | Verified | seamless join, static scene; no person tested | 23 Oct |
| Motion photo | Verified | Google format parses; system picker shows "Motion Photo" | 23 Oct |
| Burst (hold shutter) | Verified (no crash) | | 22 Oct |
| Timer 3/10 s | Verified (no crash) | | 22 Oct |
| Info frame | Verified | real EXIF values | 22 Oct |
| Front camera photo | Verified | | 23 Oct |
| Shutter during mode switch | Verified | retries on the new session | 23 Oct |

## Video modes

| Feature | Status | Notes | Last check |
|---|---|---|---|
| Video 4K/1080p/720p, 24/30/60 | Verified | 4K24 24.0 fps, 1080p60 59.9 fps | 22-23 Oct |
| HLG 10-bit | Verified | HEVC Main10 BT.2020/HLG; looks off | 22 Oct |
| Stabilisation Off/Standard/Enhanced | Verified | Standard crops 1/1.26 at 1x | 23 Oct |
| Pause/resume, background finalise | Verified | | 22 Oct |
| Pro Video | Verified (no crash) | | 23 Oct |
| Film (recipes, cine ratios, crop output) | Verified (no crash) | | 23 Oct |
| Movie (auto subject, tap rack focus, track, smart framing, auto zoom) | Verified | blur quality fixed; 30 fps cap | 23 Oct |
| Effects (slow shutter, freeze, parallel, time-lapse, night time-lapse) | Verified | time-lapse ~10x | 22 Oct |
| Vlog templates + music | Verified | 4 shots -> 10 s file | 22 Oct |
| Dual video | Partial | portrait: needs a hand-held check; sideways: inset upside down (warned) | 23 Oct |
| Slow motion 120/240 | Verified | 1,056 frames retimed | 22 Oct |
| Horizon lock | Verified | direction checked with injected roll | 23 Oct |

## Colour, looks and presets

| Feature | Status | Notes | Last check |
|---|---|---|---|
| Looks on preview | Verified | | 23 Oct |
| Looks baked into photo (graded copy) | Verified | Leica Authentic, ZEISS T* Pop, Hasselblad XPan saved | 23 Oct |
| Looks baked into video | Verified | S-Cinetone recorded | 23 Oct |
| Essentials (15 originals + 5 slay LUTs) | Verified | contact sheets on real photos; device capture not yet per look | 23 Oct |
| Brands (Leica 11, ZEISS 4, Hasselblad 6, Sony 10) | Verified | contact sheets; 3 saved on device | 23 Oct |
| Intensity, default strength, Reset, No look | Verified (no crash) | | 23 Oct |
| Favourites, recents | Verified (no crash) | | 22 Oct |
| Custom look editor (incl. colour mixer, shadows/highlights) | Verified (no crash) | | 23 Oct |
| .cube import | Verified | | 22 Oct |
| Recipes (scene presets) | Verified | | 23 Oct |
| My presets, QR export/import | Verified | round trip | 23 Oct |
| Hold-to-compare | Verified (no crash) | | 22 Oct |

## Image-quality settings

| Feature | Status | Notes |
|---|---|---|
| Face priority | Verified (no crash) | scene mode FACE_PRIORITY + face boxes |
| Auto HDR | No measured effect | indoor scene; high-contrast test pending |
| Detail Standard/Natural | No measured effect (stills) | text updated |
| White balance chip outside Pro | Verified | fixes the hidden-Daylight bug |

## Monitoring and UI

| Feature | Status |
|---|---|
| Zebra, peaking, false colour, greyscale, histogram, level, centre, safe area | Verified (preview only, not recorded) |
| Grid, flash/torch, timer, volume-key shutter | Verified |
| Lens chips 0.6x/1x/3x/5x, pinned physical lenses | Verified |
| Gallery and viewer | Verified (no crash) |
| Settings, camera diagnostics | Verified (no crash) |
| Switch transitions, animated strips | Verified (screen recording) |

## Crash sweep, 23 Oct

24 steps across every mode, sheet and the gallery: 0 app crashes (`sweep.sh`). Two logged "crashes" were the test
tool's own UI dumper colliding with a second sweep, not Velum (same app process id throughout).
