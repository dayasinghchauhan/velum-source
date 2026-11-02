# Image processing changelog

Newest first. Each entry names the commit and the evidence it rests on.

| Commit | Change | Evidence |
|---|---|---|
| a2e1153 | Dual video warns when held sideways (inset recorded upside down). | front reference vs inset frame |
| a2d0e99 | Settings text states measured results for Stabilisation crop, Auto HDR and Detail. | benchmark sections 2 and 4 |
| 72643c0 | Halo-free, noise-aware sharpening (CPU stills and GPU video). | SharpnessBench; on-device noise gain x2.2 -> x1.43 |
| 1a6ebc5 | Essentials looks; slay LUTs bundled with credit; default strength + Reset per look; shutter retry after rebind. | contact sheets on real photos; LUT analysis |
| 95370e2 | Movie: automatic subject, disc blur, halo guard, 30 fps cap. | 1080p clip frames; 38 -> 30.0 fps steady |
| 128e4b7 | Smooth switch transitions (held, blurred last frame), animated strips, gradient video controls. | screen recording |
| 0384533 | Preset QR export/import. | on-device round trip |
| 86ad640 | WB chip visible outside Pro when not Auto. | wall R/B 1.42 -> 1.02 |
| f48bb1b | Motion photo (Google format). | file parses; picker labels it "Motion Photo" |
| 1ac7e57 | Horizon lock for video. | injected-roll recordings |
| 8f76327 | Portrait at 3x/5x: depth blur on the still. | 3x capture before/after |
| c3190fc | Macro, Astro (aligned stack), Best shot, Clone. | captures; alignment drift bug found and fixed |
| 69cc9e0 | Brand looks (Leica, ZEISS, Hasselblad, Sony); 8-band hue mixer; shadows/highlights; XPan frame. | contact sheets; saved photo with Leica Authentic |
| 4ef42b1 and earlier | Camera engine, looks, modes, slow motion, long exposure, vlog, dual video. | see PHYSICAL_DEVICE_TEST_RESULTS.md |
