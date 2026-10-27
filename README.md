# RawCam

A deliberately minimal Android camera for Android 14+ built directly on Camera2. It takes
JPEGs with every optional processing stage switched off, lets you pick the physical lens,
and gives you manual ISO, shutter, focus and white balance when the device supports them.

No CameraX, no Compose, no AppCompat, no AndroidX runtime, no third-party libraries.
The release APK is about **176,097 bytes (~0.18 MB)**.

## Requirements

- Android 14 or newer (`minSdk = 34`, `targetSdk = 34`)
- A camera that exposes JPEG output through Camera2
- Only the `CAMERA` permission is requested; photos are written through MediaStore

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

## Controls

The UI follows the neutral black-and-yellow `camera-ui.html` mockup: a compact icon-and-label
row, a centred rounded preview frame, a glass lens capsule, a mode carousel and a classic
shutter bar. Everything is built from framework views and small custom drawables, so Material
Components and AndroidX are not needed.

- **Top row** — five icon-and-label controls:
  - **Flash** — `Off` / `On`, dimmed when the active lens has no flash.
  - **Timer** — `Off` / `3s` / `10s`. A large countdown is drawn over the preview; the timer is
    cancelled if the activity pauses.
  - **RAW** — fixed `On` indicator for the JPEG pipeline with processing disabled. Tapping it
    shows a short explanation.
  - **Grid** — `Off` / `3×3`. The overlay preference is persisted.
  - **Aspect** — `3:4`, `2:3`, `9:16` or `Full`, limited to ratios the camera can write
    natively. `Full` picks the native ratio closest to the screen.
- **Preview** — a centred, rounded frame at the selected ratio on a black stage. It shows the
  live readout (ISO, shutter, focus and WB), the zoom badge, the glass lens capsule, the focus
  ring, the capture badge, the timer countdown and the shutter flash.
  - **Tap** to focus and meter (only in Auto focus mode; the AE region matters only while
    exposure is Auto).
  - **Pinch** to zoom, relative to the selected lens, up to 5× or the device limit.
  - **Long-press the readout** to open the capability report for the current lens.
- **Mode carousel** — `Manual`, `Photo`, `Info`.
  - **Manual** opens the manual-controls sheet.
  - **Photo** closes the sheet and returns to the plain preview.
  - **Info** opens the capability report.
- **Bottom bar** — gallery thumbnail, shutter and flip.
  - **Shutter** compresses and rebounds while the photo is saved.
  - **Thumbnail** shows the last JPEG saved by RawCam and opens it with `ACTION_VIEW`.
  - **Flip** switches back/front. The front preview is mirrored; the saved JPEG is not.
- **Lens capsule** — verified physical lenses only (`.6`, `1×`, …), overlaid at the bottom of
  the preview. It is hidden when the active camera has a single lens; the selected pill turns
  yellow.
- **Toast** — feedback appears as an in-app capsule above the lens capsule instead of a system
  `Toast`.

The manual sheet is part of the activity, so it stays inside the portrait-locked, edge-to-edge
layout and never requires AndroidX. While it is open the bottom bar slides away, the scrim
dims the preview and the sheet slides up. It only shows the panels the lens supports:

- **EXP** — switch A/M, then use the ISO and shutter sliders. Manual mode sets both together,
  exactly as Camera2 requires. Long exposures leave the preview approximate (and say so),
  while the saved photo uses the exact values.
- **FOCUS** — Auto, continuous, or manual focus distance. Fixed-focus and AF-less lenses show
  `Fixed focus` / `No AF` instead of a misleading distance.
- **WB** — Auto, preset illuminants, or Kelvin. Kelvin mode turns AWB off and applies the
  device's colour transform.
- **Close**, the scrim, **Photo**, or **Back** dismisses the sheet.

## Design notes

- **Palette** — near-black surfaces (`#17181B`, `#1C1C1E`, `#2A2A2D`), yellow accent
  (`#FFD54A`), translucent glass (`#8C1C1C1E`), white and dim-white text. Red (`#FF4A3D`) is
  reserved for future recording state.
- **Shape** — a centred 18dp-rounded preview frame, a 24dp glass lens capsule, 17dp mode pills,
  a 14dp thumbnail, an 18dp chip set and a 78dp shutter with a 4dp white ring.
- **Motion** — the top row slides down, the preview fades and scales in, the bottom bar rises on
  launch; the shutter compresses and rebounds; the countdown pulses; the sheet slides up while
  the bottom bar slides away; the toast fades in and out.
- **Dialogs** — alert dialogs and the aspect popup use the same neutral surfaces, 28dp/20dp
  corners and yellow actions.
- **No images** — all icons are small vector drawables tinted at runtime; no icon fonts or
  bitmap assets are bundled.

## What "no processing" means

Every still request explicitly asks for:

- noise reduction off
- edge enhancement off
- hot-pixel correction off
- chromatic aberration correction off
- distortion correction off
- video stabilisation off
- sRGB tone map (or `FAST` where the device lacks the preset curve)
- linear colour correction / transform matrix as appropriate

The live readout is built from `TotalCaptureResult`, and each capture logs whether the device
honoured the request. Long-press the readout for the same information in a dialog.

## Verify on a device

```powershell
adb logcat -s RawCam/Caps RawCam/Verify RawCam/Controller RawCam/Save
```

- `RawCam/Caps` lists discovered cameras, physical lenses, supported streams, ISO/shutter
  ranges, focus support, AWB presets and every "off" capability.
- `RawCam/Verify` prints the accepted request values from `TotalCaptureResult`
  (`NR`, `EDGE`, `HOT`, `CA`, `DIST`, `STAB`, `TONEMAP`, `CCM`, `AE`, `ISO`, `SHUTTER`,
  `FOCUS`, `AF`, `AWB`, `PHYS`). `PHYS` names the physical lens that actually produced the
  frame.
- `RawCam/Controller` logs the chosen lens strategy, session plan, capture values and JPEG
  arrival.

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
| Google Pixel 5 (serial `09171FDD40050B`) | 14 (API 34) | Passed the checks below |

Pixel 5 results:

- Back: 0.6× ultrawide physical `3`, 1× main physical `2`; both physical streams verified.
- Front: direct camera `1`, no flash and no manual focus; fixed-focus label shown.
- Auto capture: 10.4 MB JPEG with NR/EDGE/HOT/CA/DIST/STAB all off, AE on, AWB auto.
- Manual exposure: `AE=OFF`, ISO 1109 requested / 1108 reported, shutter within device rounding.
- 10.2 s manual exposure completed and verified at ISO 55 / 10.17 s.
- Ultrawide capture verified `PHYS=3`; main verified `PHYS=2`.
- 16:9 produced a `4032×2268` JPEG; Full produced `4000×2000` on the ultrawide.
- WB preset Incandescent verified `AWB=INCANDESCENT`; Kelvin verified `AWB=OFF`, `CCM=TRANSFORM_MATRIX`.
- Manual focus verified `AF=OFF` with the requested dioptre value.
- Flip, pause/resume, lens-chip visibility and front EXIF orientation verified.
- Preview orientation checked against the saved upright JPEG by edge correlation.
- `camera-ui.html` UI verified on-device: five-control top row (`Off`/`3s`/`On`/`3×3`/`3:4`),
  rounded 3:4 frame at 1080×1440, glass lens capsule, `Manual`/`Photo`/`Info` carousel, shutter
  bar, in-app toast above the lens capsule, 3-second countdown, aspect popup (`3:4`, `2:3`,
  `9:16`, `Full`) and slide-up manual sheet with auto/manual chips.
- `Full` aspect selected the native 2:1 stream on the Pixel 5 main lens (shown as a 1:2 upright
  frame); the 3:4 capture was 4032×3024.
- Manual sheet contents follow the active lens: back shows EXP + FOCUS + WB; ultrawide and
  front show EXP + WB (no focus). Back and the scrim both dismiss the sheet.
- Grid preference survived an app restart; the thumbnail updated after capture and opened the
  system viewer chooser; the Info report, aspect popup and permission screen rendered correctly.
- Front preview is mirrored on screen while the saved JPEG stays unmirrored.
- 4:3 capture produced 4032×3024; Full produced 4000×2000.

Devices from other manufacturers are still untested. Capability differences are expected;
the UI should hide anything the device cannot do.

## Known limitations

- No RAW/DNG, no video, no gallery, no HDR, no computational photography.
- No portrait/background blur, no night mode, no panorama.
- Manual controls and extra lenses appear only when the camera advertises them.
- Physical-lens streams may not support zoom on some devices; the app then keeps the lens
  chip for stills and disables or falls back on zoom.
- The preview is a centred frame at the selected ratio rather than a full-bleed crop; `Full`
  chooses the native stream closest to the screen ratio, so a little of the stage stays black
  on most devices. The JPEG always keeps the selected ratio.
- The gallery thumbnail opens the system viewer chooser rather than an in-app gallery;
  RawCam has no gallery of its own.
- Long exposures slow the preview and make it approximate by design.
- The orientation listener is quantised to 0/90/180/270; a device lying flat can report an
  ambiguous rotation, which affects the EXIF orientation of that shot.
- Release builds are unsigned and have no Play Store packaging.

## Project layout

```
app/src/main/java/com/rawcam/
  MainActivity.kt          UI, gestures, permissions, preview layout, manual sheet
  AspectFrameLayout.kt     Centred ratio frame for the rounded preview
  GridOverlay.kt           Rule-of-thirds overlay
  CameraController.kt      Camera2 lifecycle, sessions, capture, lens fallback
  CameraCaps.kt            Camera/lens discovery and profiles
  RequestApplier.kt        Pure request spec -> Camera2 keys
  ProcessingVerifier.kt    Live readout and processing verification
  PhotoSaver.kt            Untouched JPEG bytes -> MediaStore
  OrientationHelper.kt     Device rotation -> EXIF orientation / icon rotation
  core/                    Pure Kotlin: no Android types
    Model.kt               State, lens and aspect models
    AspectMath.kt          Aspect/output/preview sizing and crop
    ExposureMath.kt        ISO/shutter steps, formatting, preview clamping
    ManualFocus.kt         Focus distance/dioptre mapping
    ManualWhiteBalance.kt  Kelvin and gain math
    FocusMath.kt           Tap-to-sensor coordinate mapping
    OrientationMath.kt     EXIF and icon rotation
    RequestBuilder.kt      Pure capture request specification
app/src/test/java/com/rawcam/core/
  102 JVM unit tests covering the pure logic
```

## Tests

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

The suite covers aspect/output sizing, exposure and shutter formatting, focus mapping,
orientation (front and back), Kelvin/gain math, state clamping and request building.
