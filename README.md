# RawCam

RawCam is a deliberately minimal Android 14+ camera built directly on Camera2. It requests
the camera's optional image-processing stages to be disabled, saves JPEG output through
MediaStore, and records video through the camera's recorder surface. Camera2 remains the
camera stack because the app exposes manual controls and physical lens selection.

The UI uses Kotlin and the Android View system, with Google Material Components 1.14 and
Material 3 Expressive styling. The preview is a platform `TextureView`, overlaid by Material
buttons, cards, sliders, and dialogs. Dynamic colors follow the device theme. There is no
Jetpack Compose or cross-platform UI runtime.

## Requirements and build

- Android 14 or newer (`minSdk = 34`, `compileSdk = 37`, `targetSdk = 37`)
- JDK 17 or newer
- Gradle 9.8.2, AGP 9.4.1, Kotlin 2.4.21, AndroidX Activity 1.13.0, Lifecycle 2.11.0
- Material Components for Android 1.14.0

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleRelease
```

The unsigned release APK is at `app/build/outputs/apk/release/app-release-unsigned.apk`.
Photos and videos are saved under `DCIM/Camera` through MediaStore. Stills are always JPEG:
with the default `Original` save crop the camera's bytes are written untouched, EXIF
orientation included. `16:9` and `Fullscreen` apply a centre crop while saving, and front-camera
photos are mirrored to match the preview. Video is encoded by MediaRecorder and is not re-encoded
by the app.

## Camera UI

- Tap the preview to focus and meter. Pinch to zoom; double-tap to toggle 1x/2x.
- The top bar holds flash, timer (off/3 s/10 s), a centered capture summary that opens settings,
  and the thirds grid.
- The aspect-fit preview card sits below the top bar and always shows the full sensor framing.
  The floating lens row sits directly above the shutter, followed by the last-media thumbnail,
  shutter and flip controls. The bottom bar holds settings, an icon-only Photo/Video toggle, and
  white-balance cycling.
- Settings always list manual controls, save crop (Original, 16:9, Fullscreen) and the
  advertised video quality/frame rate, regardless of the current mode. Manual exposure, focus,
  and white balance are limited by lens support. The save crop only affects the written file,
  so the preview never jumps.
- Volume keys trigger the shutter. Audio permission is requested only when recording.

The layout keeps the centered, rounded viewfinder but moves every control out of the framed
image: the screen is a single vertical column on a 4 dp grid, with a 56 dp top bar, an
aspect-fit card, and 48 dp minimum touch targets. Spacing, sizes, and colours come from the
tokens in `CameraTheme.kt`, so dynamic colour changes stay coherent. Camera errors replace the
current dialog instead of stacking, and a missing permission shows an inline full-screen panel
rather than repeated system dialogs. The View implementation uses Material state feedback, a
short shutter scale response, and Material sliders while leaving the camera frame on its
platform surface.

## Architecture

```text
app/src/main/java/com/necra/purecam/
  MainActivity.kt                 Activity, permission results, orientation and lifecycle
  CameraController.kt             Camera2 lifecycle, sessions, capture and lens fallback
  CameraCaps.kt                   Camera/lens discovery and capability profiles
  RequestApplier.kt               Camera2 request configuration
  ProcessingVerifier.kt           Capture-result readout and processing verification
  PhotoSaver.kt                   JPEG output (crop, rotate, mirror) and MediaStore writes
  VideoRecorder.kt                MediaRecorder output
  OrientationHelper.kt            Device rotation tracking
  presentation/
    CameraUiState.kt              Immutable screen state and one-shot signals
    CameraViewModel.kt            StateFlow state and user intents
  ui/
    CameraScreenView.kt           Screen composition, rendering and interaction
    CameraTheme.kt                Spacing, size and palette tokens
    CameraControls.kt             Icon, chip, segment, shutter and recording controls
    ViewfinderView.kt             Aspect frame, preview surface, grid and focus ring
    CameraSettingsDialogs.kt      Settings, manual controls and camera error dialogs
    ContextExt.kt                 App settings and media-view intents
  data/UiPreferences.kt           Grid, timer, save crop, mode and media preferences
  core/                            Camera math and request models, independent of Android
```

The UI calls `CameraViewModel` intents. The view-model calls `CameraController`, whose
callbacks are adapted into `CameraUiState`. Pure camera math is covered by JVM unit tests.

## Processing requests

Still and record requests ask Camera2 to disable noise reduction, edge enhancement,
hot-pixel correction, chromatic aberration correction, distortion correction, and video
stabilization where supported. Tone mapping and color behavior are device-dependent; the
controller verifies accepted settings from `TotalCaptureResult` and logs them.

Use this command to inspect device capabilities and capture verification:

```powershell
adb logcat -s RawCam/Caps RawCam/Verify RawCam/Controller RawCam/Video RawCam/Save
```

Camera devices and OEM camera pipelines can still apply unavoidable processing. The app
requests supported controls and reports the values accepted by the camera; it cannot bypass
the device's ISP.
