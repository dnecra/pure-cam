Why This App Feels Slow: Root-Cause Analysis

1. TextureView for Camera Preview (Primary Cause) — CameraPreview.kt:96-133

The preview is rendered through an AndroidView wrapping a TextureView. TextureView is the wrong rendering surface for a live camera feed:

- Software compositing. TextureView does not get a dedicated hardware overlay layer. Every frame the camera produces is copied from the hardware buffer into GPU memory, then composited by TextureView's parent ViewGroup, then composited again by Compose's ViewCompositionStrategy. This double-composite happens 30-60 times per second.

- Compose interop overhead. AndroidView re-runs its update lambda on every recomposition. The update block calls view.applyTransform() — a UI operation — so any state change that triggers a recomposition (zoom, focus, WB, etc.) forces a layout pass inside the embedded View hierarchy while the camera is pushing frames.

- TextureView vs SurfaceView. SurfaceView gets a dedicated hardware layer that SurfaceFlinger composites directly with zero extra copies. For a performance-critical surface like a camera preview, SurfaceView is the standard choice.

This is not a Compose problem. Compose is lightweight, but you are embedding a legacy View (TextureView) that does a full extra buffer copy per frame. The "snappiness" of Compose is overwhelmed by the preview surface's inherent cost.

2. Whole-Tree Recomposition on Every State Change — PureCamApp.kt:54, CameraViewModel.kt

PureCamApp collects viewModel.uiState as a single StateFlow<CameraUiState> via collectAsStateWithLifecycle. Every field mutation creates a new CameraUiState object, which triggers a recomposition of the entire tree rooted at PureCamApp:


PureCamApp
  └── GoogleCameraUI         (rebuild + re-run update on AndroidView)
      ├── CameraPreview      (AndroidView.update runs, TextureView re-laid-out)
      ├── GridOverlay        (Canvas redraw)
      ├── CountdownOverlay   (AnimatedContent)
      ├── CaptureFlash       (White overlay fade)
      ├── FocusIndicator     (Animatable)
      └── RecordingPill      (already isolated)


Fields like capturing, recording, countdown, deviceRotation, lastPhoto (thumbnail URI change), and even ready flow through that single flow. A zoom gesture that calls controller?.setZoom() triggers applyPreview() on the camera thread, and when the camera reports back a new TotalCaptureResult, handleResult calls notifyState() → mainHandler.post { listener.onStateChanged(...) } → _uiState.update { ... } → full recomposition.

The app has some good mitigations (zoom kept in a local field, recording pill self-ticking), but the central StateFlow means every camera state notification recomposes the whole screen including the preview's AndroidView.

3. Synchronous Physical-Lens Probing — CameraController.kt:874-903

CameraCaps.discover() and physicalProbe() run synchronously on the camera handler thread. The initial start() call invokes discover(), which iterates every logical group and, for physical cameras, calls manager.getCameraCharacteristics(pid) for each physical ID — a Binder round-trip to the camera HAL. On devices with 3-4 physical lenses per group, this is 10-15 HAL calls blocking the camera thread before the first frame.

physicalProbe() calls d.isSessionConfigurationSupported(cfg) synchronously — if it's not cached, it's another blocking call that can take 50-200ms. The results ARE cached in SharedPreferences (purecam_probe), but the first run pays the full cost, and any cache miss (orientation change, new size) blocks again.

4. Request Rebuilding on Every Interaction — CameraController.kt:1049-1058

Every UI control change (flash toggle, zoom, tap-to-focus, exposure/WB/focus mode switch) calls applyPreview(), which rebuilds the entire CaptureRequest.Builder via RequestBuilder.build() → RequestApplier.apply(). That's ~80 lines of setting individual Camera2 keys, plus:

- ManualWhiteBalance computation (CameraController.kt:149-152): When using Kelvin WB, computeKelvin does matrix interpolation on the camera handler thread inside the request builder.
- ColorSpaceTransform allocation: result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.toFloatArray() allocates a new array on every preview frame in handleResult (line 1340-1345).

These are all on the camera callback thread, so they directly affect preview frame timing.

5. Camera2 API Instead of CameraX — CameraController.kt (entire file)

The entire CameraController (1,415 lines) reimplements what CameraX provides:

- Session lifecycle management: Manual createCaptureSession / closeSessionAndReader / closeCamera with generation tokens and sessionToken tracking (lines 717-972). CameraX handles all of this.
- Stream configuration: Manual OutputConfiguration, SurfaceTexture/Surface management, setDefaultBufferSize (line 780). CameraX's Preview use case does this automatically and correctly handles aspect ratio matching, surface sharing optimization, and buffer recycling.
- Concurrent camera: The physical-lens fallback strategy (strategies A/B/C with isSessionConfigurationSupported probing) is complex and error-prone. CameraX's ConcurrentCamera with CameraSelector is a battle-tested solution.
- Lifecycle binding: The app manually implements onResume/onPause/onSurfaceAvailable/onSurfaceDestroyed coordination. CameraX binds to lifecycle automatically.

CameraX also has its own performance optimizations: surface sharing (avoiding duplicate buffers), Preview.SurfaceProvider with zero-copy SurfaceTexture/SurfaceView backing, and baseline-profile-aware startup optimization. The README says "No CameraX" as a design principle, but that's the single decision causing the most performance debt.

6. Animation and Recomposition Overhead — GoogleCameraUI.kt, Controls.kt

The UI has many animations:
- animateColorAsState with 160ms tweens on every button (GoogleCameraUI.kt:279-292, 444-457, 600-613)
- AnimatedContent on the spec pill, countdown, capture mode (GoogleCameraUI.kt:248-261, 367-393)
- animateFloatAsState on the shutter button scale and flip button rotation
- LaunchedEffect blocks that run on recomposition (e.g., the WB transient label at GoogleCameraUI.kt:106-112)

These are individually cheap, but they multiply because they recomposes with the whole tree. A collectAsStateWithLifecycle at the root means even a ready boolean flip re-runs all these animation specs.

7. Thumbnail Loading on Every Recomposition — Controls.kt:191-198

PhotoThumbnail uses produceState keyed on uri. When lastPhoto changes, it loads the thumbnail. But since the entire tree recomposes on every CameraUiState change, produceState is re-evaluated even though the key hasn't changed. The produceState is correct (it memoizes on key change), but the recomposition of the surrounding Box(weight=1f) still happens — and in the GoogleCameraUI tree, that's the entire bottom row including the shutter button.

8. No Baseline Profile / Startup Optimization

The gradle.properties has no R8 baseline profile configuration, and the build config shows isMinifyEnabled = true for release but no baseline profile generation. On first launch (cold start), the JIT hasn't compiled the Compose UI tree, the Camera2 initialization code, or the request builder — adding 200-500ms of jank on first frame.

The README notes the app is 1.8 MB unsigned (lean), but there's no baseline-prof.txt or BaselineProfile test, which is a standard Compose performance optimization.

9. No Frame Rate Matching

The app does not call preferredFrameRate or set window.setPreferredDisplayModeId(). The CaptureSettingsSheet does use Modifier.preferredFrameRate(FrameRateCategory.High) (line 53) but only on the settings sheet, not the main preview. On devices that support variable refresh rate, the preview may run at 60Hz while the camera produces 30fps, causing redundant frames or stutter.

10. Hardware Shutter Key Handling — MainActivity.kt:62-68

Volume key events go through onKeyDown which calls cameraViewModel.onShutter(). This posts to the activity's main thread, which is fine, but the event?.repeatCount == 0 check means auto-repeat on held volume keys is correctly handled. This is actually well-implemented.



Summary: Why "Jetpack Compose should make it snappy" Doesn't Apply Here

You assumed Compose would make it snappy because it's "lightweight." That assumption is correct for what Compose controls — but the camera preview is the performance bottleneck, and it's not rendered by Compose at all. It's rendered by a TextureView embedded via AndroidView, and TextureView forces a software composition pass on every frame. Compose's lightweight drawing pipeline only sees the final composited View bitmap, not the raw camera frames — so it inherits all the lag.

The fix requires addressing the root cause:

Issue: TextureView preview surface
Severity: Critical
Fix: Switch to SurfaceView via AndroidView, or migrate to CameraX PreviewView (which defaults to SurfaceView backend)
────────────────────────────────────────
Issue: Whole-tree recomposition
Severity: High
Fix: Split CameraUiState into granular flows (e.g., previewState, controlState, recordingState) or use
  requestKey-based snapshots; hoist preview-independent state
────────────────────────────────────────
Issue: Physical lens probing
Severity: Medium
Fix: Cache probe results more aggressively; consider CameraX's built-in multi-camera API
────────────────────────────────────────
Issue: Camera2 manual implementation
Severity: Architectural
Fix: Adopt CameraX for session/lifecycle/stream management — eliminates ~1,400 lines of error-prone code with
  optimized implementations
────────────────────────────────────────
Issue: Missing baseline profile
Severity: Medium
Fix: Add BaselineProfileRule test for camera startup path

The "modern material expressive guidelines" part (Material You dynamic color, the Pixel-style UI) is actually well-executed and doesn't contribute meaningfully to the performance problem. The issue is purely the camera preview pipeline and state management architecture.