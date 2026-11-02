# Device & Environment Report

Recorded 2026-10-22. Every value below was read from a command that was actually run; nothing is assumed from the brief.

## 1. Workspace

| Item | Finding |
|---|---|
| Working directory given | `C:\Users\Daya` (user home, not a repository) |
| Existing camera project | None. No Android camera project existed in the home dir, `D:\Projects`, or `Documents\GitHub`. |
| Decision | New project created at `D:\Projects\velum` (git initialised there). No existing files were touched. |

## 2. Connected phone

Commands run: `adb devices -l`, `adb shell getprop <key>`, `adb shell dumpsys media.camera` (output saved, 1.04 MB, parsed with a script).

| Property | Value |
|---|---|
| ADB serial | (redacted) (USB, `device` state) |
| `ro.product.model` | **SM-S928U1** (US unlocked variant, *not* SM-S928B as the brief expected) |
| `ro.product.device` / name | `e3q` / `e3quew` |
| `ro.build.version.release` | **17** |
| `ro.build.version.sdk` / `sdk_full` | **37** / 37.0 |
| `ro.build.version.oneui` | **90000** → One UI 9.0 |
| `ro.build.display.id` | `CP2A.260605.016.S928U1UEU6EZIF` |
| `ro.build.fingerprint` | `samsung/e3quew/e3q:17/CP2A.260605.016/S928U1UEU6EZIF:user/release-keys` |
| Security patch | 2026-09-05 |
| SoC | `ro.soc.model=SM8650`, `ro.board.platform=pineapple` (Snapdragon 8 Gen 3 for Galaxy) |
| First API level | 34 |

Note: the first `adb devices` call (before the phone was plugged in) returned no devices; the user then connected it and every later command ran against the device above.

## 3. Camera service, HAL view (not the app view)

`dumpsys media.camera` reports **4 camera devices to the API** (3 "normal"), while the HAL provider lists 14 devices
(`0 1 2 3 4 20 21 23 24 52 54 56 58 90`). IDs 20+ are Samsung-internal and are normally hidden from third-party
apps. What a normal app can actually open is established by the app's own capability discovery
(see `DEVICE_CAPABILITY_REPORT.md`), not by this table.

| HAL id | Facing | Focal | f/ | Sensor (mm) | Array | Notes |
|---|---|---|---|---|---|---|
| 0 | Back | 6.3 | 1.7 | 9.79×7.34 | 4080×3060 | LOGICAL_MULTI_CAMERA, physical ids `2,5,6,7`; zoomRatio 0.6–10; ISO 12–3200; exposure 82.9 µs–117.4 ms; AE fps ranges 15–24/24/15–30/30/15–60/60 |
| 0/phys 5 | Back | 6.3 | 1.7 | 9.79×7.34 | 4080×3060 | main, `pixelArraySizeMaximumResolution` = 4080×3060 (no 200 MP mode advertised) |
| 0/phys 6 | Back | 7.9 | 2.4 | 4.48×3.36 | 4000×3000 | ≈3× tele |
| 0/phys 7 | Back | 18.6 | 3.4 | 5.71×4.28 | 4080×3060 | ≈5× tele |
| 2 | Back | 2.2 | 2.2 | 5.60×4.20 | 4000×3000 | ultra-wide |
| 1 | Front | 3.3 | 2.2 | 4.48×3.36 | 4000×3000 | front |
| 3 | Front | 3.3 | 2.2 | 3.80×2.85 | 3392×2544 | front (second mode) |

Capabilities common to the public back cameras: `BACKWARD_COMPATIBLE, RAW, MANUAL_SENSOR, MANUAL_POST_PROCESSING,
READ_SENSOR_SETTINGS, BURST_CAPTURE, CONSTRAINED_HIGH_SPEED_VIDEO, DYNAMIC_RANGE_TEN_BIT, STREAM_USE_CASE,
COLOR_SPACE_PROFILES`.

Other HAL facts that shape the design:

- Dynamic-range profiles map: `[2 0] [0]` → STANDARD + **HLG10** only. No HDR10, HDR10+ or Dolby Vision profile.
- `colorCorrection.availableModes = [0 1 2]` → no `COLOR_CORRECTION_MODE_CCT` (Android 16 Kelvin key). Kelvin must be approximated with RGGB gains.
- `control.aeAvailablePriorityModes = [0]` → no hardware shutter- or ISO-priority AE. Shutter priority must be done by the app.
- Optical stabilisation `[0 1]` on main/tele, video stabilisation modes `[0 1 2]` (OFF, ON, PREVIEW_STABILIZATION).
- No vendor tag or documented key for Samsung Log appears in the public key lists.

## 4. Development environment

| Tool | Version / location | How checked |
|---|---|---|
| Android Studio | Panda 4, **2025.3.4** (`AI-253.32098.37.2534`) | `product-info.json` |
| Max AGP this Studio supports | 9.2 | AGP/Studio compatibility table (developer.android.com) |
| JDK used for builds | JetBrains Runtime **21.0.10** (bundled with Studio) | `java -version` |
| System default JDK | 26.0.1 (not used; too new for Gradle tooling) | `C:\Program Files\Java` |
| Android SDK (existing) | `C:\Program Files (x86)\Android\android-sdk`, platforms 35/36 only, **read-only** for this user, no sdkmanager | `ls`, `touch` |
| Android SDK (new, used) | `D:\Projects\android-sdk`: `platforms;android-37.0`, `build-tools;36.1.0` + `36.0.0`, `platform-tools 37.0.1` | installed with the new `android` CLI (`--no-metrics`) from cmdline-tools 16111833 |
| Gradle | **9.8.1** (wrapper; already cached) | wrapper dists |
| AGP | **9.2.1** (built-in Kotlin) | Google Maven metadata |
| Kotlin Gradle Plugin | **2.3.21** | Maven Central metadata |
| CameraX | **1.6.2** (latest stable, 26 Aug 2026; contains the Android 17 dynamic-range crash fix from 1.5.2/1.6.0) | CameraX release notes |
| Compose BOM | **2026.09.00** (ui 1.12.1, material3 1.4.0) | BOM mapping page |
| Media3 | 1.11.1 | Google Maven |
| ffmpeg/ffprobe (host) | present (winget) | PATH |

## 5. Blockers found

| Blocker | Resolution |
|---|---|
| Phone not attached at first probe | User connected it; all device work since then is on (redacted). |
| System SDK not writable, no API 37 platform | Created writable SDK at `D:\Projects\android-sdk` (`local.properties`, not committed). |
| Xiaomi 17 Ultra spec page and XDA thread return HTTP 403 to the fetch tool | Not inspected; no findings are claimed from them. |

## 6. Commands run (Phase A)

```
adb version; adb devices -l; adb mdns services
adb shell getprop ro.product.model|device|name, ro.build.version.release|sdk|sdk_full,
  ro.build.display.id, ro.build.fingerprint, ro.board.platform, ro.soc.model,
  ro.soc.manufacturer, ro.build.version.oneui, ro.build.PDA, ro.build.version.security_patch,
  ro.product.first_api_level
adb shell dumpsys media.camera > dumpsys_camera.txt   (parsed by parse_dumpsys.py)
android --no-metrics --sdk=D:/Projects/android-sdk sdk install "platforms;android-37.0" "build-tools;36.1.0" platform-tools
gradlew assembleDebug   (toolchain smoke build: PASS)
javap on camerax 1.6.2 classes.jar (API surface check)
```
