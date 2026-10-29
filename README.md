# RawCam

A deliberately minimal Android camera for Android 14+ built directly on Camera2. It takes
JPEGs with every optional processing stage switched off, records video through the same
unprocessed pipeline, lets you pick the physical lens, and gives you manual ISO, shutter,
focus and white balance when the device supports them.

The app is written in Kotlin with **Jetpack Compose** and **Material 3 / Material You**:
the whole UI follows the wallpaper-derived dynamic colour of Android 14, the viewfinder is
full-bleed, and every transition is declarative and interruptible. No CameraX, no images,
no icon fonts, no third-party libraries.

The release APK is about **1.8 MB** (unsigned).

## Requirements

- Android 14 or newer (`minSdk = 34`, `targetSdk = 34`)
- A camera that exposes JPEG output through Camera2
- `CAMERA` is requested at launch; `RECORD_AUDIO` is requested the first time you record
  video and may be declined (clips are then silent). Photos and videos are written through
  MediaStore.

## Build

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleRelease
```

Outputs:

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release-unsigned.apk`

The release APK is unsigned, so it cannot be installed as-is until you add a signing
configuration (or sign it with `apksigner`).

## Install and run

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.rawcam/.MainActivity
```

Photos are saved as `DCIM/Camera/IMG_yyyyMMdd_HHmmss.jpg`. The JPEG bytes are written
unchanged; orientation is carried in the EXIF tag rather than by rotating pixels.

Videos are saved as `DCIM/Camera/VID_yyyyMMdd_HHmmss.mp4`. MediaRecorder writes the H.264
stream straight from the camera's recorder surface; the app never re-muxes or re-encodes it.

## Controls

The camera screen is a full-bleed viewfinder with a floating Material You control layer:

- **Gestures**
  - **Tap** to focus and meter the tap point (auto focus/auto exposure only). A focus ring
    pulses where you tapped.
  - **Pinch** to zoom, relative to the selected lens, up to 5× or the device limit. A zoom
    badge appears while zoomed.
  - **Double-tap** to toggle between 1× and a quick 2× crop.
- **Top controls** — four round glass buttons:
  - **Flash** — on/off, dimmed when the active lens has no flash.
  - **Timer** — off / 3 s / 10 s. The countdown is drawn over the viewfinder and animates
    between numbers; pressing the shutter again cancels it.
  - **Grid** — rule-of-thirds overlay; the preference is persisted.
  - **Manual controls** — opens the manual sheet. The button is accented while any
    control is out of Auto.
- **Readout** — a small glass pill under the top row shows the live ISO and shutter.
- **Lens pills** — verified physical lenses only (`.6×`, `1×`, …) above the shutter bar,
  hidden when the active camera has a single lens. Tap to switch lens.
- **Mode switch** — **Photo / Video** pill above the shutter (hidden on lenses with no
  recordable stream). Video mode fixes a 16:9 framing so the preview, the sensor crop and
  the encoder agree; the photo aspect is restored when you switch back.
- **Video quality pill** — `720p · 30 fps`, `1080p · 60 fps`, … opens the video settings
  sheet. Only resolutions and frame rates the active lens advertises are offered.
- **Bottom bar**
  - **Thumbnail** — last photo or clip saved by RawCam (clips carry a small play badge);
    opens it with the system viewer.
  - **Shutter** — Material You accent circle, haptic feedback, compresses on press, shows a
    progress ring while the photo is being captured and saved. In video mode it turns red
    and becomes a record button; while recording the centre becomes a white stop square.
  - **Flip** — switches back/front; the icon rotates with the device.
- **Recording pill** — red dot plus elapsed time under the readout while a clip is rolling.
- **Hardware shutter** — the volume keys take a photo.
- **Snackbars** — feedback (flash, timer, saved, lens removed, silent recording) appears as
  a Material snackbar above the shutter bar.

### Video settings sheet

A second modal bottom sheet, opened from the quality pill:

- **Resolution** — only qualities the lens can stream to an encoder, mapped to the camera's
  own buffers (the canonical size such as 3840×2160 is preferred over a sensor crop).
- **Frame rate** — only rates present in `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES`; the exact
  range is pinned on the record request.
- **Summary** — buffer size, frame rate and the computed H.264 bitrate, plus a note that NR,
  sharpening, distortion and stabilisation stay off.
- **Sound** — requested lazily. Declining records silent clips and says so; if the encoder
  cannot prepare with audio, the app retries the same settings without sound.
- Preview in video mode clamps manual exposure to one frame so the viewfinder shows exactly
  what the encoder receives (long shutters are shortened and ISO raised to keep brightness).

### Manual sheet

A Material 3 modal bottom sheet, limited to what the active lens advertises:

- **Exposure** — Auto or Manual, with ISO and shutter sliders in third-stop steps. Manual
  mode sets both together, exactly as Camera2 requires. Long exposures note that the live
  preview is approximate while the saved photo uses the exact values.
- **Focus** — Auto or Manual distance, with metric labels on calibrated lenses.
- **White balance** — Auto, a preset illuminant chip, or a Kelvin slider. Kelvin turns AWB
  off and applies the device's colour transform.

## Design notes

- **Material You** — `dynamicDarkColorScheme` supplies the entire palette (accent, surfaces,
  containers) from the device wallpaper. The camera UI stays dark by design.
- **Shape** — circular glass buttons, 16 dp rounded thumbnail, circular shutter with a 4 dp
  white ring, pill-shaped lens chips and readout.
- **Motion** — spring-driven shutter press, animated countdown, fading focus ring, animated
  flip icon, Material sheet and snackbar transitions. All animations are declarative and
  can be interrupted mid-flight.
- **Legibility** — soft top and bottom gradient scrims keep controls readable on bright
  scenes, and snackbars use a dark tonal (rather than the default light inverse) surface.
- **No images** — icons are small vector drawables tinted at runtime.

## Architecture

The project follows the recommended Android layering behind a single activity:

```
app/src/main/java/com/rawcam/
  MainActivity.kt                 ComponentActivity: edge-to-edge, permission, OrientationHelper
  presentation/
    CameraUiState.kt              Immutable UI snapshot, timer mode, one-shot signals
    CameraViewModel.kt            Owns the camera session, exposes StateFlow + intents
  ui/
    RawCamApp.kt                  Permission gate, snackbar routing, error dialog, audio prompt
    CameraScreen.kt               Full-bleed preview, gestures, grid, countdown, focus ring
    Controls.kt                   Mode switch, top controls, shutter bar, pills, thumbnail
    ManualControlsSheet.kt        Material 3 manual exposure/focus/WB sheet
    VideoSettingsSheet.kt         Material 3 resolution / frame-rate sheet
    ThumbnailLoader.kt            Small upright EXIF-aware thumbnail decode (+ video thumbs)
    ContextExt.kt                 Activity lookup, settings and media-view intents
    theme/Theme.kt                Material You dynamic colour
  data/
    UiPreferences.kt              Grid, timer, mode, video quality and last-media persistence
  camera (data layer, framework):
    CameraController.kt           Camera2 lifecycle, sessions, still + record, lens fallback
    CameraCaps.kt                 Camera/lens discovery and profiles (incl. video streams)
    RequestApplier.kt             Pure request spec -> Camera2 keys
    ProcessingVerifier.kt         Live readout and processing verification
    PhotoSaver.kt                 Untouched JPEG bytes -> MediaStore
    VideoRecorder.kt              MediaRecorder -> pending MediaStore MP4
    OrientationHelper.kt          Device rotation -> EXIF orientation / icon rotation
  core/                           Pure Kotlin: no Android types
    Model.kt, AspectMath.kt, ExposureMath.kt, FocusMath.kt,
    ManualFocus.kt, ManualWhiteBalance.kt, OrientationMath.kt, RequestBuilder.kt,
    VideoMath.kt                  Qualities, fps ranges, bitrate, duration, exposure clamp
app/src/test/java/com/rawcam/core/
  120 JVM unit tests covering the pure logic
```

The flow is strictly one-directional: UI events call `CameraViewModel` intents, the
view-model calls the `CameraController`, controller callbacks become `CameraUiState`, and
Compose renders that state. The camera machinery and the pure math stay untouched by the UI
rewrite, so the existing test suite still covers the pipeline.

## What "no processing" means

Every still and record request explicitly asks for:

- noise reduction off
- edge enhancement off
- hot-pixel correction off
- chromatic aberration correction off
- distortion correction off
- video stabilisation (EIS) off
- sRGB tone map (or `FAST` where the device lacks the preset curve)
- linear colour correction / transform matrix as appropriate

The live readout is built from `TotalCaptureResult`, and each capture logs whether the device
honoured the request. Video uses the same request builder with `TEMPLATE_RECORD`: the first
frame of a recording is verified exactly like a still, and the log names the mode
(`Video recording:`).

Video is written by the platform encoder from the raw camera surface, so the app itself adds
no filters, scaling, frame interpolation or stabilisation. The unavoidable encode step
(demosaic → tone map → H.264) is the same one every camera app performs; the optional ISP
stages above are what RawCam disables.

## Verify on a device

```powershell
adb logcat -s RawCam/Caps RawCam/Verify RawCam/Controller RawCam/Video RawCam/Save
```

- `RawCam/Caps` lists discovered cameras, physical lenses, stream sizes (photo and video),
  AE frame-rate ranges, ISO/shutter ranges, focus support, AWB presets and every "off"
  capability.
- `RawCam/Verify` prints the accepted request values from `TotalCaptureResult`
  (`NR`, `EDGE`, `HOT`, `CA`, `DIST`, `STAB`, `TONEMAP`, `CCM`, `AE`, `ISO`, `SHUTTER`,
  `FOCUS`, `AF`, `AWB`, `PHYS`). `PHYS` names the physical lens that actually produced the
  frame.
- `RawCam/Controller` logs the chosen lens strategy, session plan, capture values and JPEG
  arrival.
- `RawCam/Video` logs the prepared file, buffer, frame rate, bitrate, audio state,
  orientation hint, recording start and the published MediaStore URI.

## Lens selection

The app tries three strategies and only shows a lens chip after it has been verified:

1. **A — physical streams**: open the logical camera and attach preview/JPEG outputs to the
   physical camera ID.
2. **B — logical camera with zoom**: use the logical camera and set the zoom ratio to the
   lens's base zoom.
3. **C — direct camera ID**: open the physical camera directly, if it is separately
   available.

Probe results are cached in `SharedPreferences`. If a physical stream cannot be configured
at runtime, the chip is removed and the app falls back to logical zoom or the main lens.

## Tested devices

| Device | Android | Result |
| --- | --- | --- |
| Google Pixel 5 (serial `09171FDD40050B`) | 14 (API 34) | Photo + video pipeline passed (Compose build) |
| Android 15 emulator (API 36, virtual scene camera) | 15 | Photo + video UI and pipeline passed |

Pixel 5 results with the Compose build:

- Back: 0.6× ultrawide physical `3`, 1× main physical `2`; both physical streams verified.
- Front: direct camera `1`, no flash and no manual focus; fixed-focus label shown.
- Still: 4032×3024 JPEG, 12.0 MB, written unchanged with NR/EDGE/HOT/CA/DIST/STAB off.
- Video: 1080p60 and 4K60 (`3840×2160`) recorded with audio through the physical lens
  (`PHYS=2`), first-frame verification `NR=OFF EDGE=OFF HOT=OFF CA=OFF DIST=OFF STAB=OFF
  TONEMAP=FAST CCM=FAST`; MediaStore entries published with the expected dimensions,
  duration and size.
- Video settings sheet offers 4K / 1080p / 720p and 24 / 30 / 60 fps, each one advertised by
  the lens; the encoder buffer and AE range are pinned to the selection.
- Manual exposure in video is clamped to one frame (e.g. 1/60 s at 60 fps) with ISO raised
  to preserve brightness.
- Photo mode restores the 4:3 framing after leaving video mode; the mode is persisted.

Emulator results (API 36, virtual scene, `camera:10` DIRECT stream map offers only 1280×720):

- Photo/Video switch, 16:9 video framing, `720p · 30 fps` pill and quality sheet listing only
  supported options.
- Record → 22.8 s clip at 1280×720, 2.25 MB in `DCIM/Camera`, `is_pending=0`, verified
  `NR/EDGE/HOT/CA/DIST/STAB=OFF`.
- Lazy audio prompt: granting it starts the queued recording immediately (no "camera not
  ready" error); denying it records silently and shows "Recording without sound".
- A record tap followed by a quick stop tap still produces a valid short clip (≥ 500 ms).
- Home/pause mid-recording publishes the clip; resuming reopens the video session.

Still good checks on other hardware: EXIF orientation against the saved JPEG, lens-switch
continuity, long exposures, and 24 fps/720p combinations on devices that advertise them.

Suggested device checklist for the Compose build:

1. Permission first-run, deny, grant and "open settings" paths.
2. Full-bleed preview orientation, front mirror, pinch zoom and double-tap zoom.
3. Tap-to-focus ring lands under the finger, including near the edges.
4. Flash/timer/grid toggles, countdown cancel, volume-key shutter.
5. Manual sheet: exposure stops, focus label, WB preset/Kelvin; long-exposure note.
6. Lens pills appear only when physical lenses verify; switching keeps the preview upright.
7. Saved JPEG opens from the thumbnail; EXIF orientation upright.
8. Video: mode switch fixes 16:9, quality sheet lists only advertised sizes/rates, record and
   stop produce a playable MP4, audio grant/deny paths, pause mid-clip saves.
9. Snackbars and the camera error dialog (simulate by opening the camera in another app).

## Known limitations

- No RAW/DNG, no gallery, no HDR, no computational photography.
- No portrait/background blur, no night mode, no panorama.
- Video is simple by design: one clip per tap, no pause/resume, no audio toggle, fixed 16:9,
  H.264/MPEG-4 only, and no in-app trimming. Audio is mic-only.
- Video quality options are limited to the device's advertised encoder buffers and AE frame
  rates; a lens with no wide encoder stream shows no Photo/Video switch.
- A capture failure during recording discards that clip rather than publishing a broken file.
- The feature set is intentionally lean: no aspect-ratio selector (photos are 4:3), no
  in-app capability report. Verification stays in logcat.
- The viewfinder is a full-bleed centre crop, so the saved 4:3 photo contains slightly more
  at the left and right edges than the preview shows. This matches how mainstream camera
  apps frame 4:3 stills on tall screens.
- Manual controls and extra lenses appear only when the camera advertises them.
- Physical-lens streams may not support zoom on some devices; the app then keeps the lens
  chip for stills and disables or falls back on zoom.
- The gallery thumbnail opens the system viewer rather than an in-app gallery.
- Long exposures slow the preview and make it approximate by design.
- The activity is portrait-locked; the orientation listener is quantised to 0/90/180/270, so
  a device lying flat can report an ambiguous rotation for that shot.
- Release builds are unsigned and have no Play Store packaging.

## Tests

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

The suite covers aspect/output sizing, exposure and shutter formatting, focus mapping,
orientation (front and back), Kelvin/gain math, state clamping, request building, and video
helpers (quality/size matching, advertised frame rates, AE range selection, bitrate,
duration and exposure clamping).
