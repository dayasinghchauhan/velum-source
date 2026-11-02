# Test plan

Results live in PHYSICAL_DEVICE_TEST_RESULTS.md (device) and FEATURE_REGRESSION_MATRIX.md (per feature).

## Every change

1. `./gradlew assembleDebug testDebugUnitTest` (JAVA_HOME = Android Studio JBR 21).
2. Install on the S24 Ultra, open the affected mode, capture, **pull the saved file and look at it**.
3. Crash sweep (`scratchpad/sweep.sh`): every mode, sheets, gallery, front camera; 0 app crashes required.

## Image-quality changes

1. Phone fixed on a surface, same scene and light, capture a baseline before the change.
2. Capture after the change; for colour or tone changes also capture Samsung Camera at 1x, 12 MP.
3. `iq_metrics.py regions.json label=file ...` for brightness, clipping, wall neutrality, noise, detail;
   `SharpnessBench` for any sharpening change; `lut_eval.py` for a new LUT.
4. Compare 1:1 crops by eye. A higher "detail" number that comes with higher noise is not an improvement.
5. Keep the files in `D:\Projects\velum-evidence\<date>\` and small crops in `docs/evidence/`.

## Repeatable capture hooks (debug builds only)

`am start -f 0x20000000 -n com.daya.velum/.MainActivity` with:
- `--es debugMode VIDEO` (any `CaptureMode` name)
- `--es debugSet stab=OFF` (also `fps=30`, `look=<id>`, `hdr=1`, `detail=NATURAL`)
- `--ef debugRoll 10` / `--ez debugRollOff true` (horizon-lock direction)

The volume keys are the shutter, so captures need no screen taps (`scratchpad/cap.sh`: vmode, vset, vphoto, vvideo,
sam_photo).

## Looks

For a new or changed look: render it on real photos with the contact-sheet tool (`VELUM_LOOK_SRC`,
`VELUM_LOOKS`, `VELUM_LOOK_TW`, `VELUM_LOOK_COLS`), check skin, whites, reds and greens, then save one real photo and
one video with it on the phone.
