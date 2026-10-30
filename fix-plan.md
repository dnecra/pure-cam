Fix Plan

The three highest-impact changes, in priority order:

1. Replace TextureView with SurfaceView (Critical — preview smoothness)
- CameraPreview.kt: Change PreviewTextureView : TextureView to PreviewSurfaceView : SurfaceView
- CameraController.kt: Change textureView: TextureView → surfaceView: SurfaceView; replace SurfaceTextureListener callbacks with SurfaceHolder.Callback
- CameraViewModel.kt: Update attachPreview to accept SurfaceView instead of TextureView
- PureCamApp.kt: Update the AndroidView factory

This is a mechanical swap — SurfaceView has a SurfaceHolder instead of SurfaceTexture, but the camera controller's Surface usage stays the same. Zero-copy hardware overlay = the biggest single smoothness win.

2. Split CameraUiState into granular flows (High — recomposition reduction)
- Extract CameraUiState into multiple StateFlows: cameraStateFlow, recordingStateFlow, previewStateFlow
- PureCamApp and GoogleCameraUI collect only what they need
- The preview's AndroidView no longer recomposes on every zoom/focus/zoom change

3. Migrate to CameraX (Architectural — eliminates 1400 lines)
- Replace CameraController.kt (~1400 lines) with CameraX use cases (Preview, ImageCapture, VideoCapture)
- Use PreviewView (SurfaceView-backed by default) for the preview
- Physical lens selection via CameraX's CameraInfo / CameraSelector
