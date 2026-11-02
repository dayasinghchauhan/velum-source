# Research & Architecture

Research performed 2026-10-22. Only sources that were actually fetched are cited; two requested sources could not be
read (see §2).

## 1. Verified stack

| Component | Version | Why |
|---|---|---|
| compileSdk / targetSdk | 37 (Android 17) | Device runs API 37; target the real behaviour (edge-to-edge, dynamic-range IDs). |
| minSdk | 31 | Personal device app; keeps API surface modern while leaving room for other phones. |
| AGP | 9.2.1, built-in Kotlin | Highest AGP the installed Android Studio Panda 4 (2025.3.4) can open. |
| Gradle | 9.8.1 | Cached; ≥ AGP 9.2 minimum. |
| Kotlin | 2.3.21 | Reads Compose 1.12 metadata; matches the Compose compiler plugin. |
| CameraX | 1.6.2 stable | 1.5.2/1.6.0+ ignore unknown dynamic-range profile IDs that crash `bindToLifecycle` on Android 17 (release notes). 1.6 moved to CameraPipe and made `SessionConfig`/`HighSpeedVideoSessionConfig` stable; Media3 muxer default improves crash resilience of recordings. |
| Compose BOM | 2026.09.00 | Current stable. |
| Media3 | 1.11.1 | Video playback in the gallery. |
| DataStore | 1.3.0 | Settings persistence. |

No analytics, ads, network or cloud dependency. The app does not declare `INTERNET`.

## 2. Sources

| Source | Read? | Findings used |
|---|---|---|
| CameraX releases — developer.android.com/jetpack/androidx/releases/camera | Yes | 1.6.2 latest stable (26 Aug 2026); Android 17 `STANDARD_SMPTE_2094_50` crash fixed in 1.5.2/1.6.0; RAW/DNG + RAW_JPEG in `ImageCapture` since 1.5; `HighSpeedVideoSessionConfig`; `SessionConfig.setFrameRateRange`; `isSessionConfigSupported`; feature groups; Media3 muxer default in 1.6. |
| AGP release notes / Studio compatibility | Yes | AGP 9.4 latest; Studio Panda 4 max AGP 9.2. |
| Compose BOM mapping | Yes | 2026.09.00 → ui 1.12.1. |
| Camera2 multi-camera guide | Yes | Logical camera owns state; physical streams via `OutputConfiguration.setPhysicalCameraId`; only minimal stream guarantees, per-device testing needed; `LENS_DISTORTION`. |
| Camera extensions guide | Yes | Extensions (Auto/Bokeh/HDR/Night/Face Retouch) are vendor-implemented, **preview + still only, never video**; must query availability. |
| CameraCharacteristics reference | Used through the platform API & the device dump | Key availability verified against the device HAL dump rather than the docs page. |
| android/camera-samples | Yes | 22 samples: Camera2 manual controls, RAW/DNG, slow motion, HDR video, extensions, effects, luminosity (Y-plane metering). Licence not shown on the page; **no code copied**. |
| google/jetpack-camera-app | Yes | Apache-2.0; CameraX + Compose, layered `core/data/feature/ui`; per-lens stabilisation, 10-bit HDR, Ultra HDR, low-light boost. Used as an architectural reference only; no code copied. |
| OpenMotionFX/OpenCamera | Yes | MIT; RAW_SENSOR video via Camera2, GLES3 debayer in C++, "flat-log HEVC", false colour/zebra/peaking/waveform. Shows that a custom RAW video pipeline is possible but heavy (NDK); out of scope for phase 1. No code copied. |
| Open Camera | Yes | GPL-3.0+; peaking, zebra, histogram, RAW, bracketing, HDR with alignment. **Not used as code** (GPL would bind the whole app); only as a feature checklist. |
| Sony Xperia Pro Video guide | Yes | P/S/M exposure modes (S = shutter set, ISO auto), angle buttons + slide zoom, touch tracking options, snapshot during recording, pause/resume. Functional concepts only. |
| Samsung Log Video page | Yes | S24 series supports Samsung Log **in Samsung's Camera app** (Pro Video; Video after enabling). 10-bit HEVC. LUTs (Log→Linear 1D, Log→Rec709 3D, CineLUT pack) downloadable. **No third-party API, SDK, vendor tag or integration instructions are documented.** |
| Xiaomi 17 Ultra specs | **No — HTTP 403** | Nothing claimed from it. Xiaomi-inspired items below come from the brief's own list. |
| XDA S24 Ultra GCam thread | **No — HTTP 403** | Nothing claimed from it. |

## 3. Licence obligations

All runtime dependencies are AndroidX / Kotlin (Apache-2.0). No third-party camera code was copied. Open Camera
(GPL) was used only as a checklist of concepts. Notices: `THIRD_PARTY_NOTICES.md`.

## 4. Feasibility classification (public APIs, this device)

Status is confirmed against the running app's capability report where marked ✔; otherwise it is the plan.

| Feature | Class | Route |
|---|---|---|
| Logical back camera with 0.6–10× zoom ratio | Feasible | CameraX zoom on logical camera; HAL switches lenses. |
| Lock to a specific physical lens | Conditional | `CameraSelector.setPhysicalCameraId` — only for physical ids the app can see; stream combos tested at runtime. |
| Manual ISO / shutter / focus distance | Feasible | MANUAL_SENSOR present; Camera2 interop request options. |
| Shutter priority (S) | Feasible, app-side | No AE priority mode in HAL → closed-loop ISO from measured luma. Labelled as such. |
| Kelvin white balance | Conditional | No CCT mode → RGGB gains + transform, calibrated from a daylight AWB result. Labelled "approx." |
| RAW/DNG, RAW+JPEG | Feasible if CameraX reports RAW formats | `ImageCaptureCapabilities.getSupportedOutputFormats()`. |
| 200 MP capture | **Not supported** (HAL) | `pixelArraySizeMaximumResolution` on every back sensor = binned size; no ULTRA_HIGH_RESOLUTION mode advertised. |
| Ultra HDR JPEG | Conditional | Only if `OUTPUT_FORMAT_JPEG_ULTRA_HDR` is reported. |
| 10-bit HLG video (HEVC) | Feasible if CameraX reports HLG | Profile map contains HLG10. |
| HDR10 / HDR10+ / Dolby Vision recording | **Not supported** | Not in the dynamic-range profile map. |
| Choosing H.264 vs HEVC for SDR | **Not exposed by CameraX** | Recorder has no public codec setter; SDR codec = encoder-profile default; HLG forces HEVC. Reported as measured. |
| 24/25/30/60 fps | Feasible where `getSupportedFrameRateRanges(sessionConfig)` lists them | 25 fps appears only in the 5× tele's AE list. |
| Slow motion 120/240 | Conditional | `HighSpeedVideoSessionConfig` + `Recorder.getHighSpeedVideoCapabilities`. |
| Samsung Log | **Inaccessible** | No documented public route; not attempted through hidden/OEM calls. App offers a clearly-labelled "Flat" creative profile instead. |
| Monitoring LUT (viewfinder only) | Feasible | GPU `CameraEffect` targeting PREVIEW; recorded stream untouched. |
| Baked-in look in video | Feasible, perf-dependent | Same GPU processor targeting VIDEO_CAPTURE; measured per resolution/fps. |
| Photo looks | Feasible | Same 3D LUT applied on CPU to the captured JPEG; original kept optionally. |
| Zebra / false colour / peaking / grey | Feasible | Fragment shader on the preview output only. |
| Histogram | Feasible | `ImageAnalysis` Y-plane, KEEP_ONLY_LATEST, throttled. |
| Night / HDR still extensions | Conditional | `ExtensionsManager.isExtensionAvailable`. Never used for video. |
| Multi-frame long exposure, panorama, focus bracketing, dynamic shot, subject tracking, scene recognition | Phase 2 | Need dedicated pipelines + tests; not shipped as buttons. |

## 5. Architecture

Single `app` module, layered by package (extra Gradle modules would add build time without isolation benefit for a
single-developer app):

```
com.daya.velum
├─ capability/   CameraCapabilityRepository — Camera2 CameraManager + CameraX infos → immutable DeviceCapabilities, JSON report
├─ camera/       CameraEngine — sole owner of ProcessCameraProvider; binds a SessionSpec; manual controls via Camera2 interop;
│                capture-result reader (actual ISO/shutter/focus); lens model
├─ video/        RecordingStateMachine (IDLE→PREPARING→RECORDING⇄PAUSED→FINALIZING→SAVED|ERROR), finalisation check
├─ imaging/      Look model → 3D LUT, .cube parser, GL LookProcessor (CameraEffect), CPU LUT applier for photos
├─ media/        MediaRepository (MediaStore DCIM/Velum), thumbnails, delete, metadata probe
├─ settings/     CameraSettings (single source of truth) + DataStore, capability validation & fallback
├─ presets/      Photo styles, scene recipes, video creator recipes, custom presets (JSON in app storage)
└─ ui/           Compose: camera screen, mode rails, pro panels, film/creator, gallery, settings, diagnostics
```

Key decisions:

1. **CameraX owns the device; Camera2 is used read-only for discovery.** Manual controls go through
   `Camera2CameraControl` request options on the CameraX session, so there is exactly one session owner and no
   API mixing on an open device. Raw Camera2 sessions (e.g. RAW video) would need a separate engine; out of scope.
2. **One immutable `CameraSettings` state** in a `StateFlow`; the engine diff-applies it. Rebinding happens only for
   changes that alter the stream set (mode, lens, resolution, fps, dynamic range, effect target); everything else is a
   request-option update.
3. **Capability-gated UI.** Every control reads `DeviceCapabilities`; unsupported → hidden or disabled with a reason.
4. **Looks are one model, three uses.** A `Look` produces a 33³ LUT used by the GPU preview/video processor and the CPU
   photo path, so preview and output match. Monitoring overlays are drawn only into the preview output surface.
5. **Recording is a state machine** driven by `VideoRecordEvent`s; SAVED only after the finalised file is probed with
   `MediaMetadataRetriever` (duration > 0, video track present).

## Findings added after the first build (23 Oct 2026)

- **4K60**: not available to third-party apps (33.3 ms minimum frame duration for 4K PRIVATE). Samsung Camera uses
  private vendor sessions.
- **Samsung Log / Blackmagic-style Log**: no Log or HDR10/Dolby Vision dynamic-range profile is exposed to other apps;
  Log workflows are out of scope.
- **Telephoto**: physical sensors 6 (3x, 7.9 mm f/2.4) and 7 (5x, 18.6 mm f/3.4) can be pinned per stream through
  Camera2 interop; `CameraSelector.setPhysicalCameraId` alone was ignored by CameraX 1.6 on this phone.
- **Still pipeline**: the ISP ignores EDGE_MODE and SCENE_MODE_HDR for still JPEGs (measured). Real still
  enhancement beyond the ISP comes from the vendor extensions (Night, Bokeh) or app-level multi-frame work.
- **CameraX**: Ultra HDR cannot be combined with a CameraEffect; a baked effect plus an extra JPEG stream exceeds the
  supported stream combinations at 4K; concurrent cameras compose without per-camera rotation.
- **Colour**: the camera's SDR video and the preview are display-referred BT.709, the same domain as the sRGB JPEG,
  so one 33^3 LUT is valid for preview, photos and SDR video; HLG gets no LUT.

Sources: Android Camera2 `CaptureRequest` / `CameraCharacteristics` reference, Camera extensions guide,
android/camera-samples, Leica Looks page, Hasselblad Natural Colour Solution page, Sony Creative Look help guide and
S-Cinetone white paper, DXOMARK on vivo ZEISS modes, Google Motion Photo format 1.0, slaycamera/luts (CC BY 4.0).
