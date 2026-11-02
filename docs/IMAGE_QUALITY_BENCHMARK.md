# Image quality benchmark

All numbers below come from real captures on the S24 Ultra (SM-S928U1) on 23 Oct 2026, ~03:40-04:00, indoors under
warm LED room light at night. The phone lay still on a table (a fixed tripod substitute), so framing is matched across
captures. Full-size files: `D:\Projects\velum-evidence\2026-10-23\` (not in git). Crops: `docs/evidence/`.

There is no colour target (ColorChecker) available, so **no colour-accuracy (Delta E) numbers are reported**. Neutral
balance is measured on a white painted wall only.

Tools: `iq_metrics.py` (regions as fractions of the upright frame) and `SharpnessBench.kt` (JVM, runs the old and new
sharpeners on the same file). Metrics:
- *lumaNoise*: standard deviation of luma after removing a 5x5 box blur, in a flat floor area (0-255 units).
- *detail*: standard deviation of the luma Laplacian in a textured area. **It also rises with noise**, so it is only
  read together with noise and with the 1:1 crops.
- *halo%*: share of pixels pushed more than 4-6 levels outside the min/max of their 3x3 neighbourhood in the original.

## 1. Velum default photo vs Samsung Camera (same scene, same lens 1x, 12 MP)

| | Samsung Camera | Velum (Photo, no look) |
|---|---|---|
| File | `20261023_034356.jpg` 4000x3000 | `VLM_20261023_034221_412.jpg` 4080x3060 |
| Exposure chosen | ISO 1600, 1/50 s | ISO 1132, 1/33 s |
| Mean luma | 0.395 | 0.390 |
| Clipped / crushed | 0.23% / 0.05% | 0.23% / 0.00% |
| White wall R/G, B/G | 0.990, 1.021 | 0.981, 1.003 |
| Floor luma noise | 8.72 | 2.49 |
| Red seat hue / saturation | 353.6 / 0.539 | 354.5 / 0.604 |
| Fabric "detail" | 34.9 | 25.7 |

Reading: same brightness and clipping; both balance the wall close to neutral (within ~2%). Velum's file has about
3.5x less luma noise; Samsung keeps visible grain and its fabric looks blotchy at 1:1, while Velum's shows finer
threads (`docs/evidence/crop_sv.jpg`). Samsung's higher "detail" figure is mostly noise. Velum renders the red seat
more saturated; without a colour reference it cannot be said which is closer to the real fabric.

Caveats: one scene, one lighting condition. Samsung chose a faster shutter (1/50 vs 1/33 s), which matters for
hand-held shots and moving subjects; this static test favours the longer exposure. Daylight, backlit, portrait-with-
people and telephoto comparisons have **not** been run yet (see PHYSICAL_DEVICE_TEST_RESULTS.md).

## 2. Velum capture options against each other

| Variant | Mean |diff| vs Standard (0-255) | Floor noise | Fabric detail |
|---|---|---|---|
| Standard (default) | - | 2.21 | 24.3 |
| Detail = Natural (EDGE_MODE_FAST, NR HQ) | 2.61 | 2.21 | 24.2 |
| Auto HDR (SCENE_MODE_HDR) | 2.64 | 2.21 | 24.1 |
| Night extension | 2.66 | 2.29 | 23.9 |

All differences are at shot-to-shot noise level. The HAL ignores the EDGE and HDR scene requests for stills, and the
Night extension does not engage multi-frame processing at this light level. Settings text was changed to say so.

## 3. Sharpening (graded copies and baked video)

`SharpnessBench` on `v_std.jpg`, strength 0.5:

| | Floor noise | Fabric detail | Text detail | Halo% fabric | Halo% text |
|---|---|---|---|---|---|
| Original ISP JPEG | 6.12 | 24.3 | 10.6 | 0 | 0 |
| Old sharpener (4-tap cross, ~3 px apart) | 11.42 | 45.9 | 18.7 | 31.0 | 8.3 |
| New sharpener (USM + threshold + clamp) | 7.35 | 32.8 | 14.0 | 0 | 0 |

On device, ZEISS T* Pop graded copy (sharpness 0.5 plus contrast): floor-noise gain over the original fell from
x2.2 (old build, `pop_graded_OLD.jpg`) to x1.43 (new build). Crop: `docs/evidence/sharp_cmp.jpg`.

## 4. Video field of view at 1x

Matched against a photo from the same position (`fov_match.py`, correlation >= 0.88):

| Recording | Share of photo width shown |
|---|---|
| Velum, stabilisation Standard | 1/1.26 |
| Velum, stabilisation Off | 1/1.02 |
| Samsung Camera video (4K HDR10+, its default stabilisation) | 1/1.20 |

The "zoomed 1x video" is the stabilisation margin. Velum crops slightly more than Samsung's own pipeline.

## 5. Movie mode

1080p clip, chair scene, automatic subject: subject sharp, background blurred smoothly without blocks or halos
(`docs/evidence/mv_frame_s.jpg`). Frame rate: 1080p60 setting gave 116 frames in 3.03 s (~38 fps) with per-frame depth
of field, so Movie is now capped at 30 fps; re-measured 175 frames in 5.83 s = 30.0 fps.

## 6. Slay LUTs on real photos

Neutral ramp, white/black points, skin and sky shifts measured from the .cube files and rendered on two real photos
(`docs/evidence/slay_sheet.jpg`):

| LUT | Black -> | White -> | Skin hue / sat change | Note |
|---|---|---|---|---|
| Amber | 0 | 1.00,1.00,0.95 | +3 deg / +37% | warm; default strength 70% |
| Fade | 0.10 | 0.93 | -5 deg / -29% | genuine matte |
| Neon | 0,0.01,0.05 | 1.00,0.98,1.00 | -7 deg / +18% | magenta highlights; night only, default 60% |
| Film | 0,0.01,0.05 | 1.00,1.00,0.97 | +1 deg / +11% | subtle |
| Portrait | 0.04 | 1.00,0.97,0.92 | 0 / +13% | warm, soft |

They are display-referred (domain 0-1, identity-like grey axis), i.e. built for ordinary sRGB/BT.709 images, which
is what Velum's photo and SDR video paths carry. No conversion is needed; they are not used with HLG.
