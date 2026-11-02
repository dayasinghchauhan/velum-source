# Physical device test results

Device: Galaxy S24 Ultra SM-S928U1, Android 17 (SDK 37), One UI 9, build CP2A.260605.016.S928U1UEU6EZIF, over adb.
Only tests actually run on this phone are listed as run.

## Build and unit tests (23 Oct 2026)

- `assembleDebug`, `assembleRelease` (unminified, 117 MB): pass.
- Unit tests: 24 run, 0 failed; 2 measurement tools skipped unless their input files are given (LookContactSheet,
  SharpnessBench).
- Release APK signed locally with the debug key, installed: starts (non-debuggable), takes a photo, 0 crashes.
  File: `D:\Projects\velum-builds\velum-0.1.0-release-testsigned.apk` (not in git; test signing only).

## Device runs on 23 Oct

| Test | Result |
|---|---|
| Crash sweep, 24 steps (all modes, sheets, gallery, front camera) | 0 app crashes |
| Samsung vs Velum photo, indoor | done; see IMAGE_QUALITY_BENCHMARK.md section 1 |
| Standard / Natural / Auto HDR / Night variants | done; no measurable difference |
| Old vs new sharpening, graded copy on device | noise gain x2.2 -> x1.43 |
| Video FOV at 1x, stabilisation on/off, Samsung video | 1/1.26, 1/1.02, Samsung 1/1.20 |
| Movie clip (auto subject) | sharp subject, smooth background; 30.0 fps |
| Portrait 3x depth blur | blurred copy saved, 4080x3060, ~4 s |
| Macro | camera 2, 4000x3000 |
| Best shot keep 3 | 3 files, best of 17 frames |
| Clone two shots | 4080x3060, no visible seam |
| Astro 30 s indoor | 115 frames, 0 px drift after fix (2,544 px drift before) |
| Motion photo | XMP + 3.1 s MP4, landscape rotation correct after fix, picker shows "Motion Photo" |
| Horizon lock with injected +10 deg roll | correct direction after sign fix |
| QR preset export and import | round trip |
| White balance reset | wall R/B 1.42 -> 1.02 |
| Dual video held sideways | inset upside down (known, warned) |
| Leica Authentic, XPan, S-Cinetone saved outputs | looks present in files |

## Not run (and how to run them)

| Test | Why not | How |
|---|---|---|
| Real night sky with Astro | night-time, needs the phone outdoors, pointed at open sky | More > Astro > 60 s, phone on a steady surface; pull `VLM_ASTRO_*.jpg` |
| Daylight, cloudy, backlit, high-contrast window scenes | captured at night indoors | same scene with Samsung Camera and Velum Photo, phone fixed; run `iq_metrics.py` |
| People, several skin tones; fine hair | no subject in front of the phone | as above, Portrait 1x/3x and Natural Portrait look |
| Telephoto 3x/5x and ultra-wide comparisons with Samsung | time | as above per lens |
| Moving subjects in low light | needs a subject | Photo and Best shot vs Samsung |
| Dual video held upright | phone was lying sideways | hold upright, record 3 s, check the inset |
| Battery and thermals over long Motion photo / Movie sessions | time | 10-minute runs, `dumpsys battery` and thermal status |
| Colour accuracy (Delta E) | no colour target | photograph a ColorChecker under known light |
