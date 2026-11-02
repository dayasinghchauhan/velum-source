# Camera pipeline architecture

Device: Galaxy S24 Ultra, SM-S928U1, Snapdragon 8 Gen 3 (SM8650), Android 17 (SDK 37), One UI 9,
build `CP2A.260605.016.S928U1UEU6EZIF`. Code: `app/src/main/java/com/daya/velum/`.

## 1. A photo, from shutter to gallery

```
Shutter (button or volume key)
  -> CameraViewModel.shutter()                      mode dispatch (Photo, Long exposure, Astro, Best shot, Clone, ...)
  -> CameraEngine.takePhoto(rotation)               ImageCapture, CAPTURE_MODE_MAXIMIZE_QUALITY, JPEG / Ultra HDR / RAW+JPEG
       Camera2 request carries the app's 3A choices (applyControls): AE/AF/AWB modes, P/S/M exposure, Kelvin gains,
       face-priority scene mode, optional HDR scene mode, EDGE/NR modes (see "what the HAL honours" below)
  -> Samsung ISP still pipeline (demosaic, WB, NR, sharpening, tone map, JPEG encode)        == path A
  -> MediaStore  DCIM/Velum/VLM_<time>.jpg  (the original; never modified, except Motion photo, see below)
  -> EngineEvent.PhotoSaved -> CameraViewModel.onPhotoSaved()
       only if a look is chosen and set to "Save graded copy", or an info frame is on, or Portrait >= 2x:
       PhotoGrader.grade():
         decode JPEG -> rotate upright from EXIF
         -> [Portrait >= 2x] PortraitRenderer: MiDaS depth + person mask -> depth blur          (CPU, ~4 s at 12 MP)
         -> CpuLookApplier: Sharpness (luma USM, noise threshold, halo clamp) -> 33^3 LUT -> grain -> vignette
         -> InfoFrame (optional)
         -> JPEG re-encode at the chosen quality, EXIF copied, orientation normalised
       -> DCIM/Velum/VLM_<time>_<look>.jpg   (the original is deleted only if "Keep ungraded original" is off)
  -> [Motion photo on] the JPEG is rewritten with an XMP container and a 3 s MP4 appended (Google Motion Photo 1.0)
```

So the saved image is **A** (an ISP JPEG) by default. It becomes **E** (multi-stage) only when the user asks for a
look, portrait depth blur or a frame, and then the graded copy sits next to the untouched original.

Other still paths:

| Mode | Path | Source |
|---|---|---|
| Night | CameraX Extensions `NIGHT` (vendor multi-frame) -> JPEG | C (vendor) |
| Portrait 1x | CameraX Extensions `BOKEH` -> JPEG | C (vendor) |
| Portrait >= 2x | ISP JPEG + Velum depth blur copy | A + E |
| RAW+JPEG | `RAW_SENSOR` DNG + ISP JPEG | D + A |
| Ultra HDR | ISP JPEG_R (gain map); no GPU look path | A |
| Long exposure, Astro | 4080x3060 YUV_420_888 frames, app accumulation (average / lighten), Astro aligned | B + C (app) |
| Best shot | 4080x3060 YUV frames, sharpest of a ~1.6 s burst | B |
| Clone | two 4080x3060 YUV frames, aligned, feathered join | B |
| Macro | ISP JPEG from camera id 2 (ultra-wide, 5 cm focus) | A |

YUV conversion (`FrameOps.kt`, `LongExposure.kt`): per-plane row and pixel strides honoured, chroma kept at native
1/2 x 1/2, BT.601 full-range (JFIF) matrix, which is what Android's YUV_420_888 camera output uses. Output is sRGB JPEG.

## 2. Video

```
CameraX Recorder (H.264/HEVC by profile)  <-  SessionConfig { Preview, VideoCapture, [CameraEffect] , ViewPort 16:9 }
CameraEffect = LookProcessor (GLES 3 SurfaceProcessor), targets:
   PREVIEW only                       when nothing is baked (look shown in viewfinder only)
   PREVIEW | VIDEO_CAPTURE            when a look is baked, or in Movie / Effects / Vlog, or Horizon lock is on
LookProcessor per frame:
   OES camera texture -> mipmapped 2D frame (buffer space)
   -> temporal effects (slow shutter, time freeze) -> depth of field (Movie) -> reframe / horizon rotation
   -> sharpen (same rules as stills) -> 33^3 LUT -> grain/vignette -> [preview only] zebra, peaking, false colour
HLG 10-bit: no CameraEffect at all (looks and monitors are off), the HAL's HLG/BT.2020 stream is recorded as is.
```

The preview texture and SDR video are display-referred BT.709-encoded RGB after the OES sampler's YUV conversion,
which is the same domain as the photo JPEG (sRGB/BT.709 primaries, similar transfer), so one LUT serves both. No LUT is
applied to HLG.

Slow motion uses its own Camera2 constrained high-speed session (`SlowMotionEngine`); Dual video uses CameraX
concurrent cameras with CameraX's own composition (no app effect).

## 3. Looks (presets)

Every look is a `LookParams` record (`Look.kt`) baked into a 33^3 LUT by `LookMath.apply`, optionally on top of a
`.cube` file (imported, or bundled in `assets/luts/`). The same LUT drives the GPU (preview, baked video) and the CPU
(saved photos), so preview and saved output match except for resolution-dependent sharpening. Spatial parts (grain,
vignette, sharpening) are implemented twice with the same formulas.

LookMath order: exposure (white-preserving curve) and white balance in linear light -> highlight shoulder -> sRGB ->
contrast -> shadows/highlights -> saturation/vibrance -> 8-band hue mixer (fades out on neutrals) -> monochrome mix ->
split toning (bell-weighted, neutral white and black) -> fade -> white preservation -> skin protection -> skin undertone.

## 4. Where real enhancements happen

| Enhancement | Where | Always on? |
|---|---|---|
| Demosaic, NR, sharpening, tone map, WB | Samsung ISP | yes (every capture) |
| Multi-frame night / bokeh | Samsung vendor extensions | Night, Portrait 1x |
| Exposure, WB, focus choices | app -> Camera2 request | yes |
| Colour grade, sharpening, grain, vignette | app (LUT + spatial) | only with a look |
| Depth blur | app (MiDaS + segmenter) | Portrait >= 2x, Movie |
| Stacking / alignment | app | Long exposure, Astro |

## 5. What the HAL honours (measured, see IMAGE_QUALITY_BENCHMARK.md)

For still JPEGs in this test scene, `EDGE_MODE_FAST` vs default, `CONTROL_SCENE_MODE_HDR` and the NIGHT extension at
ISO ~1000 changed the image no more than shot-to-shot noise (mean |difference| 2.6/255). The ISP applies its own
still processing regardless of those requests.
