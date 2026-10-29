# RawCam: Development Plan (v3)

> **Status update:** the UI was rewritten on Jetpack Compose with Material You (dynamic colour) and a leaner feature set. This document remains the reference for the Camera2 pipeline and the "no processing" philosophy; see `README.md` for the current architecture and UI.
>
> **Extension:** video recording, plus resolution and frame-rate settings, was added on top of
> the still pipeline. See §12 for the design and verification results. Video was a non-goal in
> the original plan; it deliberately reuses the same "no processing" request builder.

A minimal Android camera app that takes normal JPEG photos with **every optional processing step turned off**, now with **ultrawide / telephoto lens selection** and **manual ISO, shutter speed, focus and white balance**. Photos are saved to `/DCIM` like a normal camera.

> **Changes from v2:** lens selection (ultrawide / main / telephoto) and manual controls (ISO, shutter, focus, white balance) move from "future ideas" into the core scope. This adds a per-lens capability model, a single pure function that turns app state into camera requests, and a bigger device-testing effort. Timeline grows from ~6-8 days to ~15-20 days.

---

## 1. Goals and Non-Goals

### Goals

| # | Requirement | How it is met |
|---|-------------|---------------|
| 1 | Lightweight | No CameraX, Compose, AppCompat or third-party libs. Framework `Activity` + Camera2 + framework widgets. Target APK < 1.5 MB. |
| 2 | Full-screen viewfinder (or per aspect ratio) | `TextureView` preview, sized/transformed per selected aspect ratio. |
| 3 | Shutter, flash, crop/aspect ratio | Simple buttons plus an aspect selector. |
| 4 | Flip camera | Switch between back and front cameras. |
| 5 | Tap to focus, pinch to zoom | AF/AE regions + zoom (digital, relative to the selected lens). |
| 6 | Flash on/off | Auto-exposure: `AE_MODE_ON_ALWAYS_FLASH`; manual exposure: `FLASH_MODE_SINGLE`. |
| 7 | Save to `/DCIM` | `MediaStore`, `RELATIVE_PATH = DCIM/Camera`. No storage permission. |
| 8 | No post-processing at all | All optional ISP stages disabled; JPEG bytes written to disk untouched by the app. |
| 9 | Android 14+ | `minSdk = 34`. |
| 10 | **Ultrawide / telephoto** | Select a specific physical lens (hardware lens, not a digital crop) wherever the device allows it. |
| 11 | **Manual ISO + shutter speed** | `AE_MODE_OFF` + `SENSOR_SENSITIVITY` + `SENSOR_EXPOSURE_TIME`. |
| 12 | **Manual focus** | `AF_MODE_OFF` + `LENS_FOCUS_DISTANCE`. |
| 13 | **Manual white balance** | Presets (`AWB_MODE_*`) plus a Kelvin slider via `COLOR_CORRECTION_GAINS`. |

### Non-goals
- RAW/DNG output, HEIC, Ultra HDR, video, filters, editing, gallery.
- Shutter-priority / ISO-priority auto modes (would need our own exposure loop).
- Tint (green/magenta) control, histogram, focus peaking (possible later).
- Geotagging (would need location permission).

---

## 2. What "No Post-Processing" Means (Be Precise)

Every digital camera must do *some* processing to turn sensor data into a viewable photo. The plan splits this into three groups:

### 2.1 Stages we turn OFF (optional, vendor "look" and enhancement)
Noise reduction, sharpening, hot-pixel correction, chromatic aberration correction, lens distortion correction, electronic stabilization, scene modes, color effects, HDR / night / portrait / beauty modes, vendor camera extensions, and hidden digital gain boosts (`POST_RAW_SENSITIVITY_BOOST` pinned to 100 in manual exposure).

### 2.2 Stages that CANNOT be turned off (inherent to producing a JPEG)
Demosaicing, white balance, color matrix, a tone curve, and JPEG compression. We choose the most neutral option for each:
- Tone curve: standard **sRGB preset** if the device offers it, otherwise `FAST`.
- JPEG quality: **100**.

### 2.3 What the app itself does: nothing
- The JPEG `byte[]` from the camera is written **directly** to `MediaStore`. The app never decodes, rotates, crops, scales, filters, or re-encodes it.
- Orientation goes in the EXIF tag (`JPEG_ORIENTATION`), not by rotating pixels.
- Cropping/zoom is done by the camera pipeline, not the app.

### 2.4 Manual controls are not post-processing
ISO, shutter speed, focus distance and white balance are **capture-time settings** (how the sensor and lens are configured when the photo is taken). They are exactly what a manual camera app is for. The one subtlety is white balance, which the ISP applies as per-channel gains on the sensor data; the app chooses the gains but does not alter the finished image.

### 2.5 Honest limits
- Some OEM pipelines still apply light processing the Camera2 API cannot disable. The app requests "off" and **verifies what the device reported** (§7).
- Digital zoom beyond a lens's native view upscales pixels. This is unavoidable for pinch-to-zoom; using the real telephoto lens avoids it.
- Which lenses third-party apps may use, and which manual controls work, is **decided by each phone maker**. The app shows only what the device actually supports.

---

## 3. Key Technical Decisions

### 3.1 Camera2, not CameraX
CameraX's `ImageCapture` can decode and re-encode the JPEG and exposes limited control over processing and manual settings. Camera2 gives full control and hands us untouched JPEG bytes. It is also much smaller.

### 3.2 Plain `ImageFormat.JPEG` only
No `JPEG_R` (Ultra HDR), no 10-bit/HDR output profiles, no Camera Extensions.

### 3.3 Lens selection strategy (ladder, verified per device)
Modern phones usually expose one **logical** back camera that fronts several **physical** lenses, and the phone maker decides how much of that is reachable. The app tries three strategies in order and offers a lens button only if it was **verified to work** on that device:

| Strategy | How | Pros / Cons |
|----------|-----|-------------|
| **A. Direct ID** | The lens appears as its own entry in `cameraIdList`; open it directly. | Cleanest (real single lens, no fusion). Only some devices expose it. |
| **B. Physical stream** | Open the logical camera and set `OutputConfiguration.setPhysicalCameraId(id)` on **both** the preview and JPEG streams. | Real single lens, standard API (Android 9+). Stream combos with physical IDs are not guaranteed everywhere. |
| **C. Logical zoom snap** | Stay on the logical camera and snap `CONTROL_ZOOM_RATIO` to each lens's native ratio (e.g. 0.5×, 1×, 3×). Read `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` to see which lens the camera chose. | Works on most devices. Camera decides the lens and may fuse frames, so it is the least "pure". |

**Probing:** at first launch (cached afterwards), for each candidate lens build a `SessionConfiguration` (preview + JPEG, using throwaway surfaces) and call `CameraDevice.isSessionConfigurationSupported()`. The result decides which strategy and which buttons appear.

```kotlin
// Strategy B: both streams pinned to one physical lens
val previewCfg = OutputConfiguration(previewSurface).also { it.setPhysicalCameraId(lens.physicalId) }
val jpegCfg    = OutputConfiguration(jpegReader.surface).also { it.setPhysicalCameraId(lens.physicalId) }
val sessionCfg = SessionConfiguration(
    SessionConfiguration.SESSION_REGULAR, listOf(previewCfg, jpegCfg), executor, stateCallback)
val ok = cameraDevice.isSessionConfigurationSupported(sessionCfg)   // hide lens if false
```

### 3.4 One immutable state, one pure request builder
All user choices live in one `CameraState`. A single pure function `RequestBuilder.build(state, profile)` turns it into Camera2 settings. This keeps the many interacting controls (flash vs manual exposure, manual focus vs tap-to-focus, per-lens ranges) in one testable place.

```kotlin
data class CameraState(
    val cameraId: String, val lens: LensProfile, val aspect: Aspect,
    val flash: Boolean, val zoom: Float,
    val exposure: Exposure,      // Auto | Manual(isoValue, shutterNs)
    val focus: Focus,            // Auto | Manual(diopters)
    val wb: WhiteBalance         // Auto | Preset(awbMode) | Kelvin(k)
)
```

### 3.5 Capability gating, not assumptions
Each manual control is shown only when the device reports support:

| Control | Requires |
|---------|----------|
| ISO + shutter | `REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR`, `SENSOR_INFO_SENSITIVITY_RANGE`, `SENSOR_INFO_EXPOSURE_TIME_RANGE` |
| Manual focus | `MANUAL_SENSOR` and `LENS_INFO_MINIMUM_FOCUS_DISTANCE > 0` (0 means fixed focus: hide) |
| WB presets | the mode listed in `CONTROL_AWB_AVAILABLE_MODES` |
| WB Kelvin slider | `REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING` (needed for `COLOR_CORRECTION_GAINS`) |

If Kelvin is unavailable, the WB control degrades to presets only.

### 3.6 Per-lens profiles
Every lens has its own ranges (ISO, shutter, minimum focus distance, JPEG sizes, zoom, active array). `CameraCaps` builds a `LensProfile` per lens from that lens's own `CameraCharacteristics` (physical ID characteristics where available). Switching lens re-clamps all sliders; values carry over when still in range, focus resets to infinity.

### 3.7 No mirroring, no RAW requirement
Front-camera photos are saved as captured (unmirrored). Any camera that outputs JPEG works.

---

## 4. Architecture

Single module, single Activity, no DI, no coroutines (a `HandlerThread` is enough).

```
app/
└── src/main/
    ├── AndroidManifest.xml
    ├── java/.../rawcam/
    │   ├── MainActivity.kt        UI, permission, lifecycle, gestures
    │   ├── CameraController.kt    Camera2 open/close, session, repeating + still requests
    │   ├── CameraCaps.kt          Enumerate cameras, build LensProfiles, probe lens strategies
    │   ├── CameraState.kt         Immutable app state (lens, aspect, flash, zoom, exposure, focus, wb)
    │   ├── RequestBuilder.kt      Pure function: state + profile -> capture request settings
    │   ├── NoProcessingFlags.kt   The "everything off" flag set (single source of truth)
    │   ├── ManualExposure.kt      ISO/shutter ranges, stop steps, preview clamping
    │   ├── ManualFocus.kt         Diopter mapping, lens-settled wait, focus assist
    │   ├── ManualWhiteBalance.kt  Presets + Kelvin -> RGGB gains / color transform
    │   ├── LensSelector.kt        Strategy A/B/C session setup and lens labels
    │   ├── PhotoSaver.kt          Write JPEG bytes to MediaStore (no decode)
    │   ├── FocusMapper.kt         View coords -> sensor coords (tap to focus)
    │   └── OrientationHelper.kt   Device rotation -> JPEG_ORIENTATION, icon rotation
    └── res/
        ├── layout/activity_main.xml
        └── drawable/              Vector icons only
```

### Data flow

```
 Gestures / buttons ─► CameraState ─► RequestBuilder ─► CaptureRequest
                                                            │
 TextureView ◄── preview stream ◄── CameraCaptureSession ◄──┘
                                          │
 Shutter ─► still request ─► JPEG ImageReader ─► byte[] ─► MediaStore (unchanged bytes)
                                          │
                              TotalCaptureResult ─► verify flags + show live ISO/shutter/focus/WB values
```

---

## 5. Project Setup

- **Language**: Kotlin · **minSdk**: 34 · **compileSdk / targetSdk**: latest stable
- **Dependencies**: none
- **Theme**: framework `android:Theme.Material.NoActionBar`; framework widgets only (`SeekBar`, `Button`, `TextView`)
- **Build config**: R8 minify + shrink resources, vector drawables only, no native code
- **Manifest**:
  ```xml
  <uses-permission android:name="android.permission.CAMERA" />
  <uses-feature android:name="android.hardware.camera.any" android:required="true" />
  <uses-feature android:name="android.hardware.camera.flash" android:required="false" />
  ```
- **Permissions**: only `CAMERA`
- **Orientation**: UI locked to portrait; icons and `JPEG_ORIENTATION` follow device rotation
- **Edge-to-edge**: handle `WindowInsets` for the control panel and cutouts

---

## 6. Implementation Details

### 6.1 Camera discovery (`CameraCaps`)
For each `cameraId` in `CameraManager.cameraIdList`:
1. Read `LENS_FACING` (keep back and front, ignore external).
2. If the camera has `REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA`, read `getPhysicalCameraIds()` and load each physical camera's own `CameraCharacteristics`.
3. For each lens build a `LensProfile`:
   - JPEG sizes per aspect (4:3, 3:2, 16:9, 1:1): largest size matching each ratio, else largest 4:3 plus crop region
   - `SENSOR_INFO_SENSITIVITY_RANGE`, `SENSOR_MAX_ANALOG_SENSITIVITY`, `SENSOR_INFO_EXPOSURE_TIME_RANGE`, `SENSOR_INFO_MAX_FRAME_DURATION`
   - `LENS_INFO_MINIMUM_FOCUS_DISTANCE`, `LENS_INFO_FOCUS_DISTANCE_CALIBRATION`
   - `CONTROL_AWB_AVAILABLE_MODES`, `SENSOR_COLOR_TRANSFORM1/2`, `SENSOR_REFERENCE_ILLUMINANT1/2`
   - `FLASH_INFO_AVAILABLE`, `SENSOR_ORIENTATION`, `SENSOR_INFO_ACTIVE_ARRAY_SIZE`
   - Capabilities (`MANUAL_SENSOR`, `MANUAL_POST_PROCESSING`) and all `*_AVAILABLE_MODES` arrays used in §6.3
4. Compute the lens label: 35 mm-equivalent focal length = `focalLength × 43.27 / sensorDiagonal` (from `LENS_INFO_AVAILABLE_FOCAL_LENGTHS` and `SENSOR_INFO_PHYSICAL_SIZE`). The "main" lens is the one closest to ~26 mm equivalent; others are labeled relative to it ("0.5×", "1×", "3×", "5×").
5. Probe which strategy (A/B/C) works per lens (§3.3) and cache the result.

### 6.2 Session setup (`CameraController`, `LensSelector`)
- Open on `onResume`, close on `onPause` (release session and `ImageReader` too).
- Surfaces: preview (`TextureView`) + `ImageReader(jpegW, jpegH, ImageFormat.JPEG, maxImages = 2)`.
- Repeating request: preview target only; settings come from `RequestBuilder`.
- Changing lens, aspect ratio or camera recreates the session on the camera thread.
- Handle `onDisconnected` / `onError` by closing cleanly and offering a retry.

### 6.3 The "no processing" flags (`NoProcessingFlags`)
Applied to every still-capture request (and the preview where harmless). **Check each against the device's `*_AVAILABLE_MODES` before setting; skip silently if unsupported.**

| Key | Value | Note |
|-----|-------|------|
| `CONTROL_MODE` | `AUTO` | Not `USE_SCENE_MODE` |
| `CONTROL_SCENE_MODE` | not set | |
| `CONTROL_EFFECT_MODE` | `OFF` | |
| `NOISE_REDUCTION_MODE` | `OFF` | |
| `EDGE_MODE` | `OFF` | No sharpening |
| `HOT_PIXEL_MODE` | `OFF` | |
| `COLOR_CORRECTION_ABERRATION_MODE` | `OFF` | |
| `DISTORTION_CORRECTION_MODE` | `OFF` | Mostly affects ultrawide lenses |
| `CONTROL_VIDEO_STABILIZATION_MODE` | `OFF` | Electronic stabilization warps/crops pixels |
| `TONEMAP_MODE` | `PRESET_CURVE` + sRGB, else `FAST` | Neutral standard curve |
| `CONTROL_POST_RAW_SENSITIVITY_BOOST` | `100` | Only when AE is off; no hidden digital gain |
| `JPEG_QUALITY` | `100` | |
| `JPEG_THUMBNAIL_SIZE` | `(0, 0)` | |
| `CONTROL_CAPTURE_INTENT` | `STILL_CAPTURE` (still) / `PREVIEW` (preview) | |

Left at defaults on purpose: optical image stabilization (a lens mechanism, not pixel processing).

### 6.4 Capture flow
1. Build a `TEMPLATE_STILL_CAPTURE` request from `RequestBuilder.build(state, profile)`.
2. **Auto exposure + flash on:** run AE precapture (`CONTROL_AE_PRECAPTURE_TRIGGER_START`), wait for AE to settle, then capture with `AE_MODE_ON_ALWAYS_FLASH`.
3. **Manual exposure + flash on:** capture with `FLASH_MODE_SINGLE` (no precapture, since AE is off). Exposure is whatever the user set, so the photo may be over- or underexposed; this is documented in the UI.
4. **Auto focus:** if AF is mid-scan, wait up to ~1 s then capture anyway.
5. **Manual focus:** wait for `LENS_STATE == STATIONARY` in the preview results (timeout ~1 s) so the lens has finished moving.
6. Set the capture timeout to `exposureTime + 3 s` (long manual exposures take real time).
7. On the `ImageReader` callback, copy the JPEG bytes into a `byte[]`, close the `Image` immediately, and save on a background thread.
8. Keep the `TotalCaptureResult` for verification (§7).
9. Debounce the shutter while a capture is in flight; show brief feedback and, for long exposures, a "capturing..." indicator.

### 6.5 Saving (`PhotoSaver`)
```kotlin
val values = ContentValues().apply {
    put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_${timestamp}.jpg")
    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
    put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
    put(MediaStore.Images.Media.IS_PENDING, 1)
}
val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
resolver.openOutputStream(uri)!!.use { it.write(jpegBytes) }   // bytes untouched
values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
resolver.update(uri, values, null, null)
```
On failure, delete the pending row. Filenames follow `IMG_YYYYMMDD_HHMMSS.jpg`.

### 6.6 Tap to focus (`FocusMapper`)
1. Take the tap `(x, y)` in view coordinates.
2. Invert the preview transform (aspect/crop), then account for `SENSOR_ORIENTATION`, device rotation and front-camera mirroring.
3. Map into `SENSOR_INFO_ACTIVE_ARRAY_SIZE`, taking the current zoom crop into account.
4. Build a `MeteringRectangle` (~10% of the frame, weight 1000); set `CONTROL_AF_REGIONS` + `CONTROL_AE_REGIONS`, send `AF_TRIGGER_START`, then return to continuous AF.
5. Show a focus ring that fades out.

**Interaction with manual modes:** tap-to-focus applies only when focus is Auto; the AE region only matters when exposure is Auto. In manual focus mode a tap does nothing except dismiss open sliders (an "AF" toggle returns to auto).

### 6.7 Pinch to zoom
- `ScaleGestureDetector` multiplies the current zoom, clamped to the lens's usable range.
- Zoom is **digital and relative to the selected lens** (e.g. 1× on the telephoto lens = its native view). Cap defaults to 5× relative to the lens, or the device maximum if lower.
- Applied to both preview and still requests so the saved photo matches the viewfinder. Show a "2.0×" indicator while pinching.
- **To verify in the M0 spike:** whether `CONTROL_ZOOM_RATIO` / `SCALER_CROP_REGION` is honored for physical-lens streams on each device. If not, zoom is disabled in lens-locked mode on that device (lens chips still work).
- In Strategy C (logical zoom snap), pinch zoom is continuous across lenses because the camera itself switches lenses.

### 6.8 Flash
- Shown only if `FLASH_INFO_AVAILABLE` is true for the active camera (typically hidden on the front camera and sometimes on ultrawide).
- Behavior per exposure mode in §6.4.

### 6.9 Aspect ratio
Options: **4:3** (native, default), **3:2**, **16:9**, **1:1**, **Full** (matches screen).
- The preview container is resized and centered per ratio; "Full" fills the screen with a center-crop transform.
- The JPEG output size for that ratio plus `SCALER_CROP_REGION` make the saved framing match the viewfinder.

### 6.10 Flip camera
- Cycle back/front: close the device, reopen, rebuild the session, reset zoom to 1.0×, and reset lens to the main lens.
- Lens chips are shown for the back camera only (and only for the front if the device exposes more than one front lens).
- Manual values carry over where still in range; focus resets.

### 6.11 Orientation
`OrientationEventListener` gives `deviceOrientation` (0/90/180/270):
- Back: `JPEG_ORIENTATION = (sensorOrientation - deviceOrientation + 360) % 360`
- Front: `JPEG_ORIENTATION = (sensorOrientation + deviceOrientation) % 360`

Icons rotate to stay upright while the layout stays portrait.

### 6.12 Lens selection UI
- A row of chips above the shutter: e.g. **0.5× · 1× · 3×**, built from the verified `LensProfile`s (§3.3, §6.1).
- Tapping a chip: save current state, rebuild the session on the chosen lens (strategy A/B/C), re-clamp manual sliders, reset zoom to 1.0×.
- The currently active physical lens is confirmed from `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` in the capture result; a mismatch is logged.
- If only one lens is verified, hide the row.

### 6.13 Manual exposure: ISO + shutter (`ManualExposure`)
**Mode:** an "A / M" toggle on the exposure control. Camera2 turns auto-exposure off for *both* ISO and shutter together (priority modes are out of scope, see non-goals).

```kotlin
set(CONTROL_AE_MODE, CONTROL_AE_MODE_OFF)
set(SENSOR_SENSITIVITY, iso)                       // within SENSOR_INFO_SENSITIVITY_RANGE
set(SENSOR_EXPOSURE_TIME, shutterNs)               // within SENSOR_INFO_EXPOSURE_TIME_RANGE
set(SENSOR_FRAME_DURATION, max(shutterNs, minFrameNs))   // must be >= exposure time
set(CONTROL_POST_RAW_SENSITIVITY_BOOST, 100)       // if supported: no hidden digital gain
```

- **Entering manual:** initialize both sliders from the latest auto `TotalCaptureResult` (`SENSOR_SENSITIVITY`, `SENSOR_EXPOSURE_TIME`) so the image does not suddenly change brightness.
- **ISO slider:** stepped in 1/3 stops across the device range. Values above `SENSOR_MAX_ANALOG_SENSITIVITY` are tinted/marked as digital gain.
- **Shutter slider:** 1/3-stop steps within the device range, displayed as `1/8000` ... `1/2` ... `1"` ... `30"` (whatever the device supports).
- **Long exposures and the preview:** frame duration must be at least the exposure time, so a 1 s exposure makes the preview run at ~1 fps. Preview uses a clamped exposure (about 1/10 s max) with ISO scaled up to approximate brightness (clamped to the ISO range); the **still capture always uses the exact values chosen**. A small note shows "preview brightness approximate" when this is active.
- **Live readout:** show current ISO and shutter on screen from the capture results (also in auto mode).

### 6.14 Manual focus (`ManualFocus`)
```kotlin
set(CONTROL_AF_MODE, CONTROL_AF_MODE_OFF)
set(LENS_FOCUS_DISTANCE, diopters)   // 0 = infinity, LENS_INFO_MINIMUM_FOCUS_DISTANCE = closest
```
- Slider range: infinity (0 diopters) to the lens's minimum focus distance. Diopters are already reciprocal distance, so a linear slider feels natural (fine control near close range). Label with an approximate distance (1/diopters m) and "∞".
- **Calibration:** if `LENS_INFO_FOCUS_DISTANCE_CALIBRATION` is `UNCALIBRATED`, the slider is relative only; do not show metric distances for that lens.
- **Entering manual:** initialize from the latest `LENS_FOCUS_DISTANCE` result.
- **Focus assist (no extra image processing):** while the user drags the slider, the *preview-only* request temporarily zooms 3× on the center (via `SCALER_CROP_REGION` / zoom ratio) so focus is easy to judge; it reverts on release and never touches the still request.
- **Capture:** wait for `LENS_STATE_STATIONARY` before firing (§6.4).
- Hidden for fixed-focus lenses (`LENS_INFO_MINIMUM_FOCUS_DISTANCE == 0`). Ultrawide lenses often have different (sometimes closer) focus ranges, which is why ranges come from the per-lens profile.

### 6.15 Manual white balance (`ManualWhiteBalance`)
Two tiers:

**Tier 1: presets (works wherever `CONTROL_AWB_AVAILABLE_MODES` lists them)**
`AUTO`, `INCANDESCENT`, `FLUORESCENT`, `WARM_FLUORESCENT`, `DAYLIGHT`, `CLOUDY_DAYLIGHT`, `TWILIGHT`, `SHADE`. Set with `CONTROL_AWB_MODE`.

**Tier 2: Kelvin slider (needs `MANUAL_POST_PROCESSING`)**, ~2000 K to ~10000 K:
```kotlin
set(CONTROL_AWB_MODE, CONTROL_AWB_MODE_OFF)
set(COLOR_CORRECTION_MODE, COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
set(COLOR_CORRECTION_GAINS, RggbChannelVector(r, gEven, gOdd, b))
set(COLOR_CORRECTION_TRANSFORM, colorSpaceTransform)
```
How the gains are computed for a chosen Kelvin value:
1. Interpolate the sensor's `COLOR_TRANSFORM1/2` (XYZ to camera RGB) by mired between the two reference illuminants (`SENSOR_REFERENCE_ILLUMINANT1/2`, typically a warm and a daylight source).
2. Camera "neutral" for that temperature = interpolated matrix × XYZ of a white at that temperature.
3. Gains = 1 / neutral, normalized so green = 1.0 (`gEven = gOdd = 1.0`).
4. Color transform: derive from the same calibration (XYZ to sRGB, adapted to D65, composed with the inverse camera matrix). **Fallback:** reuse the transform from the latest auto-AWB result and change only the gains.

This is the riskiest manual feature: calibration data quality varies by device. Mitigations: validate that the "Daylight" preset lands near ~5500 K on the slider, spot-check with a gray card at several temperatures, and if Tier 2 looks wrong on a device, fall back to presets only for that device.

- **Entering manual:** initialize from the latest `COLOR_CORRECTION_GAINS` result so there is no color jump.
- **Display:** show the Kelvin value (or the preset name).

### 6.16 UI layout (portrait, framework widgets only)
```
┌──────────────────────────────┐
│  [flash]      [aspect]  [flip]│   top bar
│                              │
│        viewfinder            │   tap = focus (auto AF), pinch = zoom
│                              │
│  ISO 200  1/125  F∞  5200K   │   live readout (from capture results)
│  [ 0.5× ][ 1× ][ 3× ]        │   lens chips (verified lenses only)
│  [ EXP ] [ FOCUS ] [ WB ]    │   mode chips; tapping reveals one slider
│  ─────────●────────  [AUTO]  │   single slider + Auto/Manual toggle
│            (  ◯  )           │   shutter
└──────────────────────────────┘
```
- Only **one** slider is visible at a time to keep the screen clean. EXP shows two sliders stacked (ISO, then shutter) only while selected.
- Controls the device does not support are hidden, not greyed out.
- Everything uses framework `SeekBar`, `Button`, `TextView` and vector icons to stay small.

---

## 7. Testing Plan

### Device matrix
Minimum: one Pixel, one Samsung, one Xiaomi/Oppo/Vivo-class device, **each with ultrawide and/or telephoto lenses**. Lens exposure and manual-control support differ the most between makers, so single-device testing is not enough.

### Functional tests
- Capture at each aspect ratio, various zoom levels, flash on/off, front and back cameras.
- Lens chips: switch between every verified lens repeatedly; no crash, no leaked sessions, correct preview each time.
- Manual exposure: extreme ISO and shutter values, long exposures (several seconds) with timeout handling, flash with manual exposure.
- Manual focus: sweep infinity to closest on each lens; capture while the lens is moving (must wait for stationary).
- Manual WB: every preset, Kelvin sweep, switching Auto ↔ Manual with no visible jump.
- Rapid shutter presses, background/foreground, screen lock, incoming call, permission revoked mid-session.
- All four device orientations: verify EXIF orientation.

### "Is processing really off?" verification
- **Result echo:** log noise reduction, edge, hot pixel, aberration, tonemap, scene and effect modes from the `TotalCaptureResult` and confirm the device honored each.
- **Side-by-side:** same scene with the stock camera app vs RawCam in low light (noise/smoothing) and on a sharp-edged chart (sharpening halos). RawCam shots should look noisier and more neutral.
- **Byte integrity:** the saved file equals the bytes received from the camera (hash both in a debug build).
- **EXIF check:** `exiftool image.jpg`, confirming no HDR/gain-map segments.
- **Framing check:** crop and zoom in the saved photo match the viewfinder for every ratio and every lens.

### Manual-control verification
- **Lens:** `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` matches the selected lens; field of view visibly differs as expected (compare with a fixed scene).
- **ISO / shutter:** result `SENSOR_SENSITIVITY` / `SENSOR_EXPOSURE_TIME` equal the requested values; JPEG EXIF ISO and exposure time match.
- **Focus:** result `LENS_FOCUS_DISTANCE` matches the request; shoot a ruler or chart at known distances and check sharpness peaks at the expected slider positions (calibrated lenses only).
- **White balance:** shoot a gray card under daylight, warm bulb and LED at several Kelvin settings; verify neutral output near the matching temperature and compare with presets.

### Unit tests (JVM)
- `RequestBuilder` for every combination: Auto/Manual exposure × Auto/Manual focus × WB modes × flash, per lens profile (ranges clamped, incompatible keys never set together).
- `FocusMapper` coordinate math across rotations, mirroring and zoom.
- Aspect to output size / crop rect; orientation mapping (front vs back).
- Kelvin to gains math against known calibration matrices.
- Shutter/ISO stop-step generation and formatting (`1/125`, `2"`).

### Performance and size
APK size, cold start, shutter-to-saved latency, memory during rapid capture, and session-switch time when changing lens.

---

## 8. Milestones

| M | Scope | Exit criteria | Est. |
|---|-------|---------------|------|
| M0 | **Expanded spike:** log capabilities and `*_AVAILABLE_MODES` on test devices; test physical-lens streams (strategy A/B/C), zoom/crop on physical streams, `isSessionConfigurationSupported`; prototype Kelvin to gains. | A per-device table of what works; decision on lens/zoom/WB fallbacks. | 1.5 days |
| M1 | Project skeleton, manifest, R8, permission flow. | App launches and requests permission. | 0.5 day |
| M2 | Viewfinder: discovery, lifecycle, preview, orientation. | Stable preview; no leaks after 20 pause/resume cycles. | 1 day |
| M3 | JPEG capture + `NoProcessingFlags` + `PhotoSaver` + orientation tag + `CameraState`/`RequestBuilder` skeleton. | Shutter saves a valid JPEG to `DCIM/Camera`. | 1-2 days |
| M4 | Flash, flip camera, tap-to-focus, pinch-to-zoom. | All four work on at least two devices. | 2 days |
| M5 | Aspect ratio selector, per-ratio output size + crop region. | Saved framing matches the viewfinder for every ratio. | 1 day |
| M6 | **Lens selection:** per-lens profiles, probing, strategies A/B/C, chips UI, per-lens re-clamping. | Verified lenses switch reliably on at least three devices; unsupported lenses hidden. | 2-3 days |
| M7 | **Manual exposure:** ISO + shutter sliders, A/M toggle, init from auto, preview clamping, long-exposure timeouts, manual flash. | Requested values echoed in results and EXIF; long exposures save correctly. | 1.5 days |
| M8 | **Manual focus:** diopter slider, calibration handling, focus assist, lens-settled wait. | Sweep works on each lens; sharp at expected positions on calibrated devices. | 1.5 days |
| M9 | **Manual white balance:** presets, Kelvin slider, calibration-based gains and transform, fallbacks. | Neutral gray card at several temperatures on at least two devices. | 2-3 days |
| M10 | Verification pass (§7), polish, size optimization. | Processing flags and manual values confirmed per device; APK < 1.5 MB; cold start to preview < 1 s. | 2 days |

**Estimated total: ~15-20 working days.** The three largest risks (lens exposure, physical-stream zoom, Kelvin white balance) are all front-loaded into M0 so surprises appear early.

---

## 9. Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|-----------|
| Phone maker hides physical lenses from third-party apps | No ultrawide/tele on that device | Strategy ladder A/B/C; show only verified lenses; document per-device support. Cannot be forced from app code. |
| Physical-lens stream combos unsupported (preview + JPEG) | Strategy B fails | Probe with `isSessionConfigurationSupported`; fall back to strategy C or hide the lens. |
| Logical camera fuses frames or switches lens on its own (strategy C) | Less "pure" output | Prefer A/B; label strategy C results honestly; log the active physical ID. |
| Zoom ignored on physical streams | Pinch zoom unavailable in lens-locked mode | Detect in M0; disable zoom for that mode on that device; chips still work. |
| Manual controls unsupported (`MANUAL_SENSOR` / `MANUAL_POST_PROCESSING` missing) | Missing sliders | Capability gating (§3.5); hide the controls; WB degrades to presets. |
| Kelvin to gains inaccurate on some devices | Wrong color casts | Calibration-based math + gray card validation; fallback to presets for that device. |
| Long exposures make the preview laggy | Poor UX | Clamp preview exposure and scale ISO; long capture timeouts; clear "capturing" indicator. |
| Flash with manual exposure over/underexposes | Bad photos | Documented in the UI; flash uses a single fixed pulse. |
| Focus distance uncalibrated | Slider has no metric meaning | Show relative slider only; no distance labels. |
| OEM ignores or partially ignores "off" flags | Photo has light processing | Check available modes, verify via results, document per-device behavior. |
| Digital zoom upscales pixels | Softer images | Prefer lens chips for zoom; cap pinch range; document. |
| State complexity (many interacting controls) | Bugs | Single immutable `CameraState` + pure `RequestBuilder` + heavy unit tests. |
| Large JPEGs on high-MP sensors | Memory pressure | `maxImages = 2`, copy and close immediately, save on a background thread. |
| Tap-to-focus math wrong in some orientations | Focus off-target | Unit tests + on-device checks in all rotations. |
| Vendor extensions/HDR sneaking in | Unwanted processing | Never use the Extensions API; plain Camera2 session only. |

---

## 10. Definition of Done

- [ ] Installs and runs on Android 14+ (`minSdk = 34`).
- [ ] Release APK < 1.5 MB, zero third-party dependencies.
- [ ] Full-screen / aspect-ratio-aware viewfinder with tap-to-focus and pinch-to-zoom.
- [ ] Shutter, flash on/off, aspect ratio, and flip camera all work.
- [ ] Lens chips appear only for lenses verified to work on the device, and switching is reliable.
- [ ] Manual ISO, shutter speed, focus and white balance each work where the device supports them, and are hidden where it does not.
- [ ] Requested manual values are confirmed in capture results and EXIF on test devices.
- [ ] Every photo is saved as JPEG in `DCIM/Camera` and appears in the system gallery.
- [ ] All supported processing stages are requested OFF and confirmed through capture results; the app never decodes, crops, rotates, or re-encodes the image.
- [ ] Video records through the same request builder and saves a playable MP4 in `DCIM/Camera`; resolution and frame rate come only from the lens's advertised streams/ranges.
- [ ] Only `CAMERA` is requested at launch (`RECORD_AUDIO` is optional and requested lazily for video).
- [ ] Verified on at least three devices from different manufacturers, each with multiple back lenses.

---

## 11. Possible Future Ideas (not in scope now)

- Optional RAW/DNG mode (the only way to avoid demosaicing, white balance and tone mapping entirely).
- Shutter-priority and ISO-priority modes via our own exposure loop.
- Tint (green/magenta) control and custom white balance from a gray card tap.
- Focus peaking and histogram overlays.
- Exposure bracketing and self-timer.
- Optional geotag.

---

## 12. Video Recording (implemented extension)

### 12.1 Goal

Record through the *same* pipeline as stills: the optional processing stages stay off, and the
app never post-processes the file. The clip must land in `DCIM/Camera` like a stock camera and
must be simple enough to stay inside the lean feature set: one mode switch, one record button,
one settings sheet.

### 12.2 Design

| Topic | Decision |
|-------|----------|
| Encoder | Framework `MediaRecorder`, H.264 in MPEG-4. No CameraX, no MediaCodec pipeline of our own. |
| Camera output | A second `Surface` from `MediaRecorder` added to a `TEMPLATE_RECORD` session (preview + recorder only). |
| Saving | Pending MediaStore row (`IS_PENDING=1`) opened as a `ParcelFileDescriptor`; published on stop, deleted on cancel/failure. |
| Orientation | `setOrientationHint()` from `SENSOR_ORIENTATION` + device rotation, same math as still EXIF. |
| Processing | `RequestBuilder` with `CaptureIntent.RECORD`: NR/EDGE/HOT/CA/DIST/EIS off, neutral tone map, linear CCM. Flash becomes a steady torch. |
| Frame rate | Pinned with `CONTROL_AE_TARGET_FPS_RANGE`, but only using a range the device advertises (`VideoMath.aeRangeFor`); never invented. |
| Manual exposure | Clamped to one frame duration (ISO raised to preserve brightness); the video-mode preview uses the same clamp so it is WYSIWYG. |
| Framing | Video forces a 16:9 plan (`AspectMath.planForOutput`) and restores the saved photo aspect on exit. |
| Resolution | `VideoQuality` candidates (4K/1080p/720p) matched against the lens's own encoder stream map; the canonical buffer (e.g. 3840×2160) is preferred over a 16:9 sensor crop. |
| Audio | `RECORD_AUDIO` requested lazily on the first record. Declining records silently; if `prepare()` fails with audio, the recorder retries the same settings without sound. |
| Short taps | `VideoMath.MIN_RECORDING_MS = 500`: a quicker stop is deferred so the MP4 header is valid. |
| Lifecycle | Pause/teardown finalizes and publishes the clip. A record tap that arrives while the session is reopening is queued and started from `onConfigured` (needed for the audio-permission dialog, which pauses the activity). |
| Events/diagnostics | `RawCam/Video` logs prepare/start/save; the first record frame is verified and logged like a still (`Video recording:`). |

### 12.3 Files added

```
core/VideoMath.kt              Qualities, size/fps matching, AE range, bitrate, duration, clamp
VideoRecorder.kt               MediaRecorder -> pending MediaStore MP4
ui/VideoSettingsSheet.kt       Resolution / frame-rate sheet
ui/Controls.kt                 Mode switch, record shutter, quality pill, recording pill
presentation/CameraUiState.kt  CaptureMode, videoConfig, available qualities/fps
data/UiPreferences.kt          Mode, quality, fps, last-media persistence
test/.../VideoMathTest.kt      20+ pure tests
```

### 12.4 Verification

Emulator (API 36, virtual scene `camera:10`, 1280×720 only):

- 720p30 record → 22.8 s MP4, 2.25 MB, `is_pending=0`, all processing flags verified off.
- Granting the lazy audio prompt starts the queued recording without an error; denying it
  records silently and reports "Recording without sound".
- Quick record→stop still saves a valid 769 ms clip; Home mid-recording publishes the clip.
- Switching back to Photo restores the 4:3 plan (`1280×960`).

Pixel 5 (Android 14):

- Settings sheet offers 4K / 1080p / 720p and 24 / 30 / 60 fps (all advertised by the lens).
- 1080p60 and 4K60 (`3840×2160`) with audio, `PHYS=2`, all processing flags off; MediaStore
  rows report the expected dimensions, duration and size.
- The first attempt at 4K used the sensor's 4032×2268 crop and the HAL rejected the stream
  combination; preferring the canonical 3840×2160 buffer fixed 4K60.
- Still capture at 4032×3024 remains unchanged (12 MB JPEG, bytes untouched).

### 12.5 Limitations

- One clip per tap: no pause/resume, no audio toggle, no trimming, H.264 only.
- Fixed 16:9 in video mode; photo aspect is restored on exit.
- Quality/fps choices are strictly what the device advertises; there is no cross-check that a
  particular size supports a particular frame rate, so an exotic combination can still be
  rejected by the HAL (the clip is then discarded with a "Recording failed" message).
- Some devices expose no wide encoder stream on a lens; that lens hides the mode switch.
