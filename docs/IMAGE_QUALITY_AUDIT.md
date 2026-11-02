# Image quality audit

Scope: the whole photo and video path as built on 23 Oct 2026 (see CAMERA_PIPELINE_ARCHITECTURE.md). Every finding
below was checked against real captures from the S24 Ultra; numbers are in IMAGE_QUALITY_BENCHMARK.md.

## Findings and what was done

| # | Finding | Evidence | Action |
|---|---|---|---|
| 1 | **Double sharpening.** Graded copies re-sharpened the ISP's already sharpened JPEG with a 4-tap cross sampled ~3 px apart at 12 MP: noise +87%, 31% of fabric pixels pushed outside their neighbourhood (halos, crunchy texture). Same kernel in the GPU path for baked video. | SharpnessBench; on-device graded copy noise x2.2 | Replaced with luma USM ([1 4 6 4 1]), 2.2-level noise threshold and 3x3 range clamp, CPU and GPU. Now noise +20%, halos 0%. |
| 2 | **Controls with no measurable effect on stills.** `Detail: Natural` (EDGE_MODE_FAST) and `Auto HDR` (SCENE_MODE_HDR) changed the JPEG no more than shot-to-shot noise. | 4 matched captures, mean |diff| 2.6/255 | Kept (they cost nothing and may help video or other scenes) but the settings text now states the measured result. Auto HDR still needs a high-contrast daylight test. |
| 3 | **A recipe silently left white balance on Daylight**; Photo mode had no WB control, so indoor photos were yellow with no visible cause. | wall R/B 1.42 before, 1.02 after | WB chip now appears in every mode when WB is not Auto; one tap restores Auto. Test script also restores WB after testing a recipe. |
| 4 | **Video 1x looked zoomed.** | FOV match: 1/1.26 (Samsung 1/1.20) | Stabilisation margin; explained in the format sheet with the measured numbers. Off keeps the full width. |
| 5 | **Movie mode blur did not know what to blur, looked blocky.** Fixed focus plane until a tap; single coarse mip level magnified into blocks; foreground and background blurred alike; sharp subject bled into bokeh. | frames before/after | Automatic subject (person, else nearer centre subject), background-weighted CoC with a dead zone, 16-tap disc from a lower mip, per-tap CoC weighting; 30 fps cap. |
| 6 | **Shutter during a rebind lost the photo** ("Camera is closed"). | logcat | takePhoto waits for the new session and retries once. |
| 7 | **Dual video inset upside down when held sideways.** | front reference photo vs inset | CameraX concurrent composition has no per-camera rotation; warning shown, documented. Not fixed. |
| 8 | **Looks that pushed reds magenta** (Leica Bleach, Moody Cinema) | contact sheets | Shadow tint reduced, red/magenta bands nudged toward orange. |
| 9 | **Unnecessary JPEG recompression?** | code | The original is never recompressed; only the graded copy is encoded (once). Motion photo rewrites the file byte-for-byte with the same JPEG data plus XMP and MP4. |
| 10 | **Colour-space handling.** | code + LUT analysis | Photo JPEG and SDR video share the display-referred BT.709/sRGB domain, so one LUT is valid for both; HLG gets no LUT; YUV paths use strides and full-range BT.601 correctly (no green/purple casts seen in any saved stack). |
| 11 | **Preview vs saved photo.** | code | Same LUT; spatial effects use the same formulas scaled to resolution. Sharpening is now resolution-aware on both sides. |

## Not found

- No double white balance: the app sets AWB mode or Kelvin gains in the request and never re-balances the JPEG
  (looks may shift warmth on purpose, and white preservation keeps neutral whites neutral).
- No unintended resizing: saved photos are full sensor output (4080x3060 main, 4000x3000 ultra-wide), XPan crops
  to 4080x1506 by design.

## Still to measure

High-contrast scenes (windows, backlight), daylight, people with a range of skin tones, telephoto lenses, moving
subjects in low light, and a colour target if one becomes available. See PHYSICAL_DEVICE_TEST_RESULTS.md.
