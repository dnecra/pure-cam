package com.necra.purecam.presentation

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.necra.purecam.CameraController
import com.necra.purecam.LensCatalog
import com.necra.purecam.R
import com.necra.purecam.core.AspectMath
import com.necra.purecam.core.AspectPlan
import com.necra.purecam.core.CameraState
import com.necra.purecam.core.CaptureAspectMode
import com.necra.purecam.core.ExposureMode
import com.necra.purecam.core.Facing
import com.necra.purecam.core.FocusMode
import com.necra.purecam.core.LensMode
import com.necra.purecam.core.LensProfile
import com.necra.purecam.core.VideoConfig
import com.necra.purecam.core.VideoMath
import com.necra.purecam.core.VideoQuality
import com.necra.purecam.core.OverlayStyle
import com.necra.purecam.core.WbPreset
import com.necra.purecam.core.WhiteBalanceMode
import com.necra.purecam.data.UiPreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Owns the camera session and turns user intents into [CameraUiState] snapshots.
 *
 * The camera machinery itself lives in [CameraController] (data layer); this view-model only
 * adapts its callback API into flows and implements the screen's intent functions. All state
 * changes land on the main thread because the controller marshals callbacks through its
 * main handler.
 */
class CameraViewModel(application: Application) : AndroidViewModel(application), CameraController.Listener {

    private val prefs = UiPreferences(application)

    private val _uiState = MutableStateFlow(
        CameraUiState(
            hasPermission = hasPermission(),
            grid = prefs.gridVisible,
            timer = prefs.timer,
            lastPhoto = prefs.lastPhoto,
            lastMediaIsVideo = prefs.lastMediaIsVideo,
            captureMode = prefs.captureMode,
            videoQuality = prefs.videoQuality,
            videoFps = prefs.videoFps,
            aspectMode = prefs.aspectMode,
            enabledWbPresets = prefs.enabledWbPresets,
            enabledOverlays = prefs.enabledOverlays,
            audioGranted = hasAudioPermission()
        )
    )
    val uiState = _uiState.asStateFlow()
    private val _previewFacing = MutableStateFlow<Facing?>(null)

    /**
     * Minimal state consumed by the live preview surface. Extracted into its own flow so that
     * zoom, capture-state, and other control changes never cause the [AndroidView] hosting
     * the [TextureView] to recompose — it only reacts to lens/session changes that actually
     * alter the framing.
     *
     * The selected lens is the source of truth for the selfie mirror: it changes the instant the
     * camera switches, while the preview-ready callback can still hold the previous lens. The
     * callback only fills in while no lens has been selected yet.
     */
    val previewState = combine(_previewFacing, _uiState) { facing, ui ->
        PreviewState(facing = ui.lens?.facing ?: facing)
    }.distinctUntilChanged()

    private val _signals = MutableSharedFlow<CameraSignal>(extraBufferCapacity = 16)
    val signals = _signals.asSharedFlow()

    private var controller: CameraController? = null
    private var preview: TextureView? = null
    private var resumed = false
    private var countdownJob: Job? = null
    /** Live zoom for gesture accumulation; not pushed through UI state while pinching. */
    private var currentZoom = 1f
    private var audioPermissionAsked = false
    private var pendingRecordConfig: VideoConfig? = null
    private var restoredWbCameraId: String? = null

    // ------------------------------------------------------------------ host lifecycle

    /** Called from the AndroidView factory. Re-attaches cleanly after activity recreation. */
    fun attachPreview(previewView: TextureView) {
        if (preview === previewView && controller != null) return
        _previewFacing.value = null
        controller?.shutdown()
        restoredWbCameraId = null
        controller = CameraController(getApplication(), previewView, this).also {
            preview = previewView
            // Queued before start(), so restored preferences are honoured from the first session.
            it.setVideoMode(_uiState.value.videoMode)
            it.setCaptureAspectMode(_uiState.value.aspectMode)
            it.setDeviceRotation(_uiState.value.deviceRotation)
            it.setOverlays(_uiState.value.enabledOverlays)
        }
        startIfAllowed()
    }

    fun onPreviewSurfaceAvailable(surfaceTexture: SurfaceTexture) {
        controller?.onSurfaceAvailable(surfaceTexture)
    }

    fun onPreviewSurfaceDestroyed(surfaceTexture: SurfaceTexture) {
        _previewFacing.value = null
        controller?.onSurfaceDestroyed(surfaceTexture)
    }

    fun onResume() {
        resumed = true
        _uiState.update { it.copy(hasPermission = hasPermission()) }
        startIfAllowed()
    }

    fun onPause() {
        resumed = false
        cancelCountdown()
        controller?.stop()
        _previewFacing.value = null
        _uiState.update { it.copy(recording = false, recordingStarting = false) }
    }

    fun setPermission(granted: Boolean) {
        _uiState.update { it.copy(hasPermission = granted, permissionDenied = it.permissionDenied && !granted) }
        if (granted) startIfAllowed()
    }

    fun onPermissionDenied() {
        _uiState.update { it.copy(permissionDenied = true) }
    }

    fun setDeviceRotation(degrees: Int) {
        controller?.setDeviceRotation(degrees)
        _uiState.update { it.copy(deviceRotation = degrees) }
    }

    private fun startIfAllowed() {
        if (resumed && _uiState.value.hasPermission) controller?.start()
    }

    private fun hasPermission(): Boolean = getApplication<Application>()
        .checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun hasAudioPermission(): Boolean = getApplication<Application>()
        .checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------ intents

    fun setCaptureMode(mode: CaptureMode) {
        if (_uiState.value.captureMode == mode || _uiState.value.recording || _uiState.value.recordingStarting) return
        if (mode == CaptureMode.VIDEO) {
            val canRecord = _uiState.value.supportsVideo ||
                _uiState.value.lens?.let {
                    VideoMath.availableQualities(
                        it.videoSizes,
                        AspectMath.videoOutputAspect(it, _uiState.value.aspectMode)
                    ).isNotEmpty()
                } == true
            if (!canRecord) {
                emitMessage(message(R.string.video_unavailable))
                return
            }
        }
        cancelCountdown()
        prefs.captureMode = mode
        _uiState.update { it.copy(captureMode = mode) }
        controller?.setVideoMode(mode == CaptureMode.VIDEO)
    }

    fun setVideoQuality(quality: VideoQuality) {
        if (_uiState.value.recording) return
        if (!_uiState.value.availableVideoQualities.contains(quality)) return
        prefs.videoQuality = quality
        _uiState.update { it.copy(videoQuality = quality) }
    }

    fun setVideoFps(fps: Int) {
        if (_uiState.value.recording) return
        if (!_uiState.value.availableVideoFps.contains(fps)) return
        prefs.videoFps = fps
        _uiState.update { it.copy(videoFps = fps) }
    }

    fun setCaptureAspectMode(mode: CaptureAspectMode) {
        if (_uiState.value.aspectMode == mode || _uiState.value.recording || _uiState.value.recordingStarting) return
        prefs.aspectMode = mode
        _uiState.update { it.copy(aspectMode = mode) }
        controller?.setCaptureAspectMode(mode)
    }

    fun setFlash(on: Boolean) {
        val s = _uiState.value.cameraState ?: return
        if (on && !s.lens.flashAvailable) {
            emitMessage(message(R.string.no_flash))
            return
        }
        if (s.flash == on) return
        controller?.setFlash(on)
        emitMessage(message(if (on) R.string.flash_on else R.string.flash_off))
    }

    fun toggleFlash() {
        val s = _uiState.value.cameraState ?: return
        setFlash(!s.flash)
    }

    fun setTimer(mode: TimerMode) {
        if (_uiState.value.recording || _uiState.value.timer == mode) return
        cancelCountdown()
        prefs.timer = mode
        _uiState.update { it.copy(timer = mode) }
        emitMessage(
            message(
                when (mode) {
                    TimerMode.OFF -> R.string.timer_off
                    TimerMode.THREE -> R.string.timer_3s_set
                    TimerMode.TEN -> R.string.timer_10s_set
                }
            )
        )
    }

    fun cycleTimer() {
        setTimer(_uiState.value.timer.next())
    }

    fun setGrid(visible: Boolean) {
        if (_uiState.value.grid == visible) return
        prefs.gridVisible = visible
        _uiState.update { it.copy(grid = visible) }
    }

    fun toggleGrid() {
        setGrid(!_uiState.value.grid)
    }

    fun flipCamera() {
        if (_uiState.value.recording || _uiState.value.recordingStarting) return
        cancelCountdown()
        controller?.flipFacing()
    }

    fun selectLens(lens: LensProfile) {
        if (_uiState.value.recording || _uiState.value.recordingStarting) return
        cancelCountdown()
        controller?.setLens(lens)
    }

    fun tapToFocus(viewX: Float, viewY: Float) {
        controller?.tapToFocus(viewX, viewY)
    }

    fun zoomBy(factor: Float) {
        if (_uiState.value.cameraState == null) return
        setZoom(currentZoom * factor)
    }

    /**
     * Sets the zoom ratio directly, clamped to the active lens's supported range.
     *
     * Pinch gestures fire many times per second. The value is kept in a plain field and pushed
     * straight to the controller, so a pinch never recomposes the camera screen; the next
     * camera state notification carries the authoritative zoom back into the UI state.
     */
    fun setZoom(zoom: Float) {
        val s = _uiState.value.cameraState ?: return
        val max = s.lens.maxZoom.coerceAtLeast(1f)
        val next = zoom.coerceIn(1f, max)
        if (abs(next - currentZoom) < 0.002f) return
        currentZoom = next
        controller?.setZoom(next)
    }

    /**
     * Cycles Auto → the enabled hardware presets → Auto. Only presets the lens advertises
     * (and the user enabled in Settings) are offered, in a fixed order so repeated taps are
     * predictable.
     */
    fun cycleWhiteBalance() {
        val state = _uiState.value
        val s = state.cameraState ?: return
        val options = wbCycleOptions(s.lens, state)
        val next = when (val current = s.wb) {
            is WhiteBalanceMode.Auto -> options.firstOrNull() ?: WhiteBalanceMode.Auto
            else -> {
                val index = options.indexOf(current)
                when {
                    index == -1 -> options.firstOrNull() ?: WhiteBalanceMode.Auto
                    index == options.lastIndex -> WhiteBalanceMode.Auto
                    else -> options[index + 1]
                }
            }
        }
        if (next == s.wb) return
        controller?.setWhiteBalance(next)
        prefs.whiteBalance = next
    }

    /** Enables or disables a hardware preset in the viewfinder WB button cycle. */
    fun setWbPresetEnabled(preset: WbPreset, enabled: Boolean) {
        val s = _uiState.value.cameraState ?: return
        if (!s.lens.awbPresets.contains(preset)) return
        val current = _uiState.value.enabledWbPresets
        val next = if (enabled) current + preset else current - preset
        if (next == current) return
        prefs.enabledWbPresets = next
        _uiState.update { it.copy(enabledWbPresets = next) }
        if (!enabled && (s.wb as? WhiteBalanceMode.Preset)?.preset == preset) setWhiteBalanceAuto()
    }

    /** Enables or disables a procedural film overlay applied to saved stills. */
    fun setOverlayEnabled(style: OverlayStyle, enabled: Boolean) {
        val current = _uiState.value.enabledOverlays
        val next = if (enabled) current + style else current - style
        if (next == current) return
        prefs.enabledOverlays = next
        _uiState.update { it.copy(enabledOverlays = next) }
        controller?.setOverlays(next)
    }

    private fun wbCycleOptions(lens: LensProfile, state: CameraUiState): List<WhiteBalanceMode> =
        WbPreset.CYCLE_ORDER
            .filter { lens.awbPresets.contains(it) && it in state.enabledWbPresets }
            .map { WhiteBalanceMode.Preset(it) }

    /** Exposure compensation in EV, applied on top of auto exposure. */
    fun setExposureCompensation(ev: Float) {
        controller?.setExposureCompensation(ev)
    }

    /**
     * Shutter intent shared by the on-screen button and the volume keys.
     *
     * @return true when the intent was accepted (a shot, countdown or recording transition
     *   actually started), so the hardware shutter can give feedback only for real actions.
     */
    fun onShutter(): Boolean {
        if (_uiState.value.countdown != null) {
            cancelCountdown()
            return true
        }
        if (_uiState.value.videoMode) {
            if (_uiState.value.recording || _uiState.value.recordingStarting) {
                controller?.stopRecording()
            } else {
                requestRecording()
            }
            return true
        }
        if (_uiState.value.capturing) return false
        val c = controller ?: return false
        val seconds = _uiState.value.timer.seconds
        if (seconds == 0) c.capture() else startCountdown(seconds)
        return true
    }

    /**
     * Audio is requested lazily, on the first recording. Declining it is not fatal: the clip is
     * recorded silently and the user is told once.
     */
    private fun requestRecording() {
        val state = _uiState.value
        if (state.recording || state.capturing) return
        val config = state.videoConfig ?: run {
            emitMessage(message(R.string.video_unavailable))
            return
        }
        if (!hasAudioPermission() && !audioPermissionAsked) {
            audioPermissionAsked = true
            pendingRecordConfig = config
            _signals.tryEmit(CameraSignal.RequestAudioPermission)
            return
        }
        beginRecording(config, hasAudioPermission())
    }

    /** Result of the lazy RECORD_AUDIO prompt. */
    fun onAudioPermissionResult(granted: Boolean) {
        audioPermissionAsked = true
        _uiState.update { it.copy(audioGranted = granted) }
        val config = pendingRecordConfig ?: return
        pendingRecordConfig = null
        if (!granted) emitMessage(message(R.string.recording_without_audio))
        beginRecording(config, granted)
    }

    private fun beginRecording(config: VideoConfig, withAudio: Boolean) {
        pendingRecordConfig = null
        _uiState.update { it.copy(recordingStarting = true) }
        controller?.startRecording(config, withAudio)
    }

    fun cancelCountdown() {
        countdownJob?.cancel()
        countdownJob = null
        if (_uiState.value.countdown != null) _uiState.update { it.copy(countdown = null) }
    }

    fun setExposureAuto() {
        controller?.setExposure(ExposureMode.Auto)
    }

    fun setExposureManual() {
        val c = controller ?: return
        c.setExposure(c.suggestedManualExposure())
    }

    fun setExposureValues(iso: Int, shutterNs: Long) {
        controller?.setExposure(ExposureMode.Manual(iso, shutterNs))
    }

    fun setFocusAuto() {
        controller?.setFocus(FocusMode.Auto)
    }

    fun setFocusManual() {
        val c = controller ?: return
        c.setFocus(c.suggestedManualFocus())
    }

    fun setFocusDiopters(diopters: Float) {
        controller?.setFocus(FocusMode.Manual(diopters))
    }

    fun setWhiteBalanceAuto() {
        prefs.whiteBalance = WhiteBalanceMode.Auto
        controller?.setWhiteBalance(WhiteBalanceMode.Auto)
    }

    fun setWhiteBalancePreset(preset: WbPreset) {
        val mode = WhiteBalanceMode.Preset(preset)
        prefs.whiteBalance = mode
        controller?.setWhiteBalance(mode)
    }

    fun setWhiteBalanceKelvin(kelvin: Int) {
        val mode = WhiteBalanceMode.Kelvin(kelvin)
        prefs.whiteBalance = mode
        controller?.setWhiteBalance(mode)
    }

    fun suggestedKelvin(): Int = controller?.suggestedKelvin() ?: 5200

    fun dismissError() {
        _uiState.update { it.copy(cameraError = null) }
    }

    fun retryCamera() {
        _uiState.update { it.copy(cameraError = null) }
        controller?.retry()
    }

    private fun startCountdown(seconds: Int) {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            for (remaining in seconds downTo 1) {
                _uiState.update { it.copy(countdown = remaining) }
                delay(1_000)
            }
            _uiState.update { it.copy(countdown = null) }
            controller?.capture()
        }
    }

    private fun emitMessage(text: String) {
        _signals.tryEmit(CameraSignal.Message(text))
    }

    private fun message(@StringRes id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)

    // ------------------------------------------------------------------ controller callbacks

    override fun onCatalog(catalog: LensCatalog, facing: Facing) {
        _uiState.update { it.copy(catalog = catalog) }
    }

    override fun onStateChanged(
        state: CameraState,
        plan: AspectPlan?,
        strategy: LensMode
    ) {
        if (restoredWbCameraId != state.lens.id) {
            restoredWbCameraId = state.lens.id
            controller?.setWhiteBalance(prefs.whiteBalance)
        }
        // A lens change (or a fallback) can land on a camera that cannot record; leave video mode
        // instead of showing a record button that can never work.
        currentZoom = state.zoom
        if (_uiState.value.videoMode &&
            VideoMath.availableQualities(
                state.lens.videoSizes,
                AspectMath.videoOutputAspect(state.lens, _uiState.value.aspectMode)
            ).isEmpty()
        ) {
            prefs.captureMode = CaptureMode.PHOTO
            _uiState.update { it.copy(captureMode = CaptureMode.PHOTO) }
            controller?.setVideoMode(false)
            emitMessage(message(R.string.video_unavailable))
        }
        _uiState.update {
            it.copy(ready = true, cameraState = state, plan = plan, zoom = state.zoom)
        }
    }

    override fun onPreviewReady(facing: Facing) {
        if (resumed) _previewFacing.value = facing
    }

    override fun onCaptureStarted(exposureNs: Long?) {
        _uiState.update { it.copy(capturing = true) }
        _signals.tryEmit(CameraSignal.CaptureFlash)
    }

    override fun onCaptureFinished(uri: Uri?, error: String?) {
        _uiState.update { it.copy(capturing = false) }
        if (uri != null) {
            onPhotoSaved(uri, null)
        } else if (error != null && error != "Capture cancelled") {
            emitMessage(error)
        }
    }

    override fun onPhotoSaved(uri: Uri?, error: String?) {
        if (uri != null) {
            prefs.lastPhoto = uri
            prefs.lastMediaIsVideo = false
            _uiState.update { it.copy(lastPhoto = uri, lastMediaIsVideo = false) }
            emitMessage(message(R.string.saved_to_dcim))
        } else if (error != null) {
            emitMessage(error)
        }
    }

    override fun onRecordingStarted(config: VideoConfig, withAudio: Boolean) {
        prefs.videoQuality = config.quality
        prefs.videoFps = config.fps
        _uiState.update {
            it.copy(
                recording = true,
                recordingStarting = false,
                videoQuality = config.quality,
                videoFps = config.fps
            )
        }
        if (!withAudio) emitMessage(message(R.string.recording_without_audio))
    }

    override fun onRecordingFinished(uri: Uri?, error: String?) {
        _uiState.update { it.copy(recording = false, recordingStarting = false) }
        if (uri != null) {
            prefs.lastPhoto = uri
            prefs.lastMediaIsVideo = true
            _uiState.update { it.copy(lastPhoto = uri, lastMediaIsVideo = true) }
            emitMessage(message(R.string.video_saved))
        } else if (error != null) {
            emitMessage(error)
        }
    }

    override fun onCameraError(message: String) {
        if (message.startsWith("Camera is not ready")) {
            emitMessage(message)
        } else {
            _uiState.update { it.copy(cameraError = message) }
        }
    }

    override fun onLensRemoved(removed: LensProfile, replacement: LensProfile?) {
        emitMessage(message(R.string.lens_unavailable, removed.label))
    }

    override fun onVerification(issues: List<String>, summary: String) {
        // Verification is logged by CameraController; it is diagnostic-only in the lean UI.
    }

    override fun onCleared() {
        countdownJob?.cancel()
        controller?.shutdown()
        controller = null
        super.onCleared()
    }
}
