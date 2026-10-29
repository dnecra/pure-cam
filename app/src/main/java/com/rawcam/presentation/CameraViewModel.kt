package com.rawcam.presentation

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.view.TextureView
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rawcam.CameraController
import com.rawcam.LensCatalog
import com.rawcam.LiveReadout
import com.rawcam.R
import com.rawcam.core.Aspect
import com.rawcam.core.AspectPlan
import com.rawcam.core.CameraState
import com.rawcam.core.ExposureMode
import com.rawcam.core.Facing
import com.rawcam.core.FocusMode
import com.rawcam.core.LensMode
import com.rawcam.core.LensProfile
import com.rawcam.core.VideoConfig
import com.rawcam.core.VideoMath
import com.rawcam.core.VideoQuality
import com.rawcam.core.WbPreset
import com.rawcam.core.WhiteBalanceMode
import com.rawcam.data.UiPreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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
            audioGranted = hasAudioPermission()
        )
    )
    val uiState = _uiState.asStateFlow()

    private val _signals = MutableSharedFlow<CameraSignal>(extraBufferCapacity = 16)
    val signals = _signals.asSharedFlow()

    private var controller: CameraController? = null
    private var preview: TextureView? = null
    private var resumed = false
    private var countdownJob: Job? = null
    private var recordingJob: Job? = null
    private var audioPermissionAsked = false
    private var pendingRecordConfig: VideoConfig? = null

    // ------------------------------------------------------------------ host lifecycle

    /** Called from the AndroidView factory. Re-attaches cleanly after activity recreation. */
    fun attachPreview(textureView: TextureView) {
        if (preview === textureView && controller != null) return
        controller?.shutdown()
        controller = CameraController(getApplication(), textureView, this).also {
            preview = textureView
            // Queued before start(), so a restored video mode is honoured from the first session.
            it.setVideoMode(_uiState.value.videoMode)
            it.setDeviceRotation(_uiState.value.deviceRotation)
        }
        startIfAllowed()
    }

    fun onPreviewSurfaceAvailable() {
        controller?.onSurfaceAvailable()
    }

    fun onPreviewSurfaceDestroyed() {
        controller?.onSurfaceDestroyed()
    }

    fun onResume() {
        resumed = true
        _uiState.update { it.copy(hasPermission = hasPermission()) }
        startIfAllowed()
    }

    fun onPause() {
        resumed = false
        cancelCountdown()
        recordingJob?.cancel()
        recordingJob = null
        controller?.stop()
        _uiState.update { it.copy(recording = false, recordingStarting = false, recordingElapsedMs = 0L) }
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
                _uiState.value.lens?.let { VideoMath.availableQualities(it.videoSizes).isNotEmpty() } == true
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

    fun toggleFlash() {
        val s = _uiState.value.cameraState ?: return
        if (!s.lens.flashAvailable) {
            emitMessage(message(R.string.no_flash))
            return
        }
        controller?.setFlash(!s.flash)
        emitMessage(message(if (s.flash) R.string.flash_off else R.string.flash_on))
    }

    fun cycleTimer() {
        if (_uiState.value.recording) return
        cancelCountdown()
        val next = _uiState.value.timer.next()
        prefs.timer = next
        _uiState.update { it.copy(timer = next) }
        emitMessage(
            message(
                when (next) {
                    TimerMode.OFF -> R.string.timer_off
                    TimerMode.THREE -> R.string.timer_3s_set
                    TimerMode.TEN -> R.string.timer_10s_set
                }
            )
        )
    }

    fun toggleGrid() {
        val next = !_uiState.value.grid
        prefs.gridVisible = next
        _uiState.update { it.copy(grid = next) }
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
        val s = _uiState.value.cameraState ?: return
        val max = s.lens.maxZoom.coerceAtLeast(1f)
        val next = (s.zoom * factor).coerceIn(1f, max)
        if (abs(next - s.zoom) < 0.002f) return
        _uiState.update { it.copy(zoom = next, cameraState = s.copy(zoom = next)) }
        controller?.setZoom(next)
    }

    /** Double-tap: jump between 1x and a quick 2x crop. */
    fun toggleQuickZoom() {
        val s = _uiState.value.cameraState ?: return
        val target = if (s.zoom > 1.05f) 1f else 2f.coerceAtMost(s.lens.maxZoom.coerceAtLeast(1f))
        _uiState.update { it.copy(zoom = target, cameraState = s.copy(zoom = target)) }
        controller?.setZoom(target)
    }

    fun onShutter() {
        if (_uiState.value.countdown != null) {
            cancelCountdown()
            return
        }
        if (_uiState.value.videoMode) {
            if (_uiState.value.recording || _uiState.value.recordingStarting) {
                controller?.stopRecording()
            } else {
                requestRecording()
            }
            return
        }
        if (_uiState.value.capturing) return
        val seconds = _uiState.value.timer.seconds
        if (seconds == 0) controller?.capture() else startCountdown(seconds)
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
        controller?.setWhiteBalance(WhiteBalanceMode.Auto)
    }

    fun setWhiteBalancePreset(preset: WbPreset) {
        controller?.setWhiteBalance(WhiteBalanceMode.Preset(preset))
    }

    fun setWhiteBalanceKelvin(kelvin: Int) {
        controller?.setWhiteBalance(WhiteBalanceMode.Kelvin(kelvin))
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
        aspects: List<Aspect>,
        strategy: LensMode
    ) {
        // A lens change (or a fallback) can land on a camera that cannot record; leave video mode
        // instead of showing a record button that can never work.
        if (_uiState.value.videoMode &&
            VideoMath.availableQualities(state.lens.videoSizes).isEmpty()
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

    override fun onReadout(readout: LiveReadout) {
        _uiState.update { current ->
            val previous = current.readout
            // The readout arrives ~5x per second. Only surface it when a displayed value
            // changes so the control layer is not recomposed at that rate.
            if (previous != null && previous.iso == readout.iso && previous.shutterNs == readout.shutterNs) {
                current
            } else {
                current.copy(readout = readout)
            }
        }
    }

    override fun onCaptureStarted(exposureNs: Long?) {
        _uiState.update { it.copy(capturing = true) }
        _signals.tryEmit(CameraSignal.CaptureFlash)
    }

    override fun onCaptureFinished(uri: Uri?, error: String?) {
        _uiState.update { it.copy(capturing = false) }
        if (uri != null) {
            prefs.lastPhoto = uri
            prefs.lastMediaIsVideo = false
            _uiState.update { it.copy(lastPhoto = uri, lastMediaIsVideo = false) }
            emitMessage(message(R.string.saved_to_dcim))
        } else if (error != null && error != "Capture cancelled") {
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
                recordingElapsedMs = 0L,
                videoQuality = config.quality,
                videoFps = config.fps
            )
        }
        val startedAt = SystemClock.elapsedRealtime()
        recordingJob?.cancel()
        recordingJob = viewModelScope.launch {
            while (true) {
                delay(200)
                _uiState.update { it.copy(recordingElapsedMs = SystemClock.elapsedRealtime() - startedAt) }
            }
        }
        if (!withAudio) emitMessage(message(R.string.recording_without_audio))
    }

    override fun onRecordingFinished(uri: Uri?, error: String?) {
        recordingJob?.cancel()
        recordingJob = null
        _uiState.update { it.copy(recording = false, recordingStarting = false, recordingElapsedMs = 0L) }
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
        recordingJob?.cancel()
        controller?.shutdown()
        controller = null
        super.onCleared()
    }
}
