package com.necra.purecam

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.graphics.SurfaceTexture
import android.view.TextureView
import com.necra.purecam.core.AspectMath
import com.necra.purecam.core.AspectPlan
import com.necra.purecam.core.CameraState
import com.necra.purecam.core.CaptureIntent
import com.necra.purecam.core.CaptureAspectMode
import com.necra.purecam.core.ExposureMath
import com.necra.purecam.core.ExposureMode
import com.necra.purecam.core.Facing
import com.necra.purecam.core.FocusMath
import com.necra.purecam.core.FocusMode
import com.necra.purecam.core.LensMode
import com.necra.purecam.core.LensProfile
import com.necra.purecam.core.SizeI
import com.necra.purecam.core.ManualWhiteBalance
import com.necra.purecam.core.OrientationMath
import com.necra.purecam.core.OverlayStyle
import com.necra.purecam.core.RangeI
import com.necra.purecam.core.RequestBuilder
import com.necra.purecam.core.RequestSpec
import com.necra.purecam.core.VideoConfig
import com.necra.purecam.core.VideoMath
import com.necra.purecam.core.WhiteBalanceMode
import java.util.concurrent.Executor
import kotlin.math.roundToInt

/** How often the manual-control readout is sampled while the preview runs. */
private const val READOUT_INTERVAL_MS = 200L
/** Coalesce pinch updates at up to 120 Hz without rebuilding requests for every pointer event. */
private const val ZOOM_REQUEST_INTERVAL_MS = 8L
/**
 * How long to wait for AE to report PRECAPTURE before accepting a settled state anyway.
 * Most HALs enter PRECAPTURE within a few frames; this only covers devices that don't
 * expose the intermediate state.
 */
private const val PRECAPTURE_ENTER_TIMEOUT_MS = 900L
private const val AE_SETTLED_RESULT_COUNT = 2

/**
 * Normal flash exposure. The device does not give flash-aware AE for stills, and a preflash
 * precapture only produced a second visible flash whose metering the still then ignored. So
 * Normal exposes the still deterministically for the flash: a short, fixed shutter and an ISO
 * that follows flash falloff (light drops with distance squared). Tune these on the device.
 */
/**
 * Sensors read out row by row (rolling shutter) and the LED pulse is short. If the exposure is
 * shorter than readout time + pulse, only the rows whose exposure window overlaps the pulse are
 * lit (a bright band, the rest dark). The exposure must be long enough for EVERY row to overlap
 * the pulse. The flash energy is fixed, so a longer shutter does not brighten the flash itself.
 */
/** Quality of every saved still. */
private const val STILL_JPEG_QUALITY = 80
private const val FLASH_STILL_SHUTTER_NS = 66_666_667L  // 1/15 s; raise if a band remains
private const val FLASH_STILL_ISO_AT_1M = 100.0         // ISO at 1 m subject distance
private const val FLASH_STILL_MAX_ISO = 800
private const val FLASH_SUBJECT_MIN_M = 0.3
private const val FLASH_SUBJECT_MAX_M = 3.0
private const val FLASH_SUBJECT_DEFAULT_M = 1.5

class CameraController(
    private val context: Context,
    private val previewView: TextureView,
    private val listener: Listener
) {
    interface Listener {
        fun onCatalog(catalog: LensCatalog, facing: Facing)
        fun onStateChanged(
            state: CameraState,
            plan: AspectPlan?,
            strategy: LensMode
        )
        fun onPreviewReady(facing: Facing)
        fun onCaptureStarted(exposureNs: Long?)
        fun onCaptureFinished(uri: Uri?, error: String?)
        /** Camera-embedded thumbnail of a shot, upright, delivered before the file is saved. */
        fun onPhotoPreview(thumbnail: android.graphics.Bitmap)
        fun onPhotoSaved(uri: Uri?, error: String?)
        fun onRecordingStarted(config: VideoConfig, withAudio: Boolean)
        /** The clip stopped and is being finalized before [onRecordingFinished]. */
        fun onRecordingSaving()
        fun onRecordingFinished(uri: Uri?, error: String?)
        fun onCameraError(message: String)
        fun onLensRemoved(removed: LensProfile, replacement: LensProfile?)
        fun onVerification(issues: List<String>, summary: String)
    }

    private data class PendingCapture(
        val needAe: Boolean,
        /** True when AE is metering for a flash, which needs the longer preflash deadline. */
        val flashAe: Boolean,
        val needLens: Boolean,
        /** Only results at or after this time may satisfy the readiness checks. */
        val startedMs: Long,
        val deadlineMs: Long
    )

    private val tag = "PureCam/Controller"
    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val prefs = context.getSharedPreferences("purecam_probe", Context.MODE_PRIVATE)
    private val handlerThread = HandlerThread("purecam-camera").apply { start() }
    private val cameraHandler = Handler(handlerThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val saver = PhotoSaver(
        context,
        onSaved = { uri, _ -> listener.onPhotoSaved(uri, null) },
        onError = { message -> listener.onPhotoSaved(null, message) }
    )

    @Volatile
    private var catalog: LensCatalog = LensCatalog(emptyMap())

    @Volatile
    private var state: CameraState? = null

    @Volatile
    private var currentPlan: AspectPlan? = null

    @Volatile
    private var latestReadout: LiveReadout? = null

    @Volatile
    private var effectiveStrategy: LensMode = LensMode.LOGICAL

    private var facing: Facing = Facing.BACK
    /** Camera to open on the first start when this controller has no state yet (null = back). */
    @Volatile
    private var initialFacing: Facing? = null
    private var started = false
    @Volatile
    private var captureAspectMode = CaptureAspectMode.PORTRAIT_9_16

    /** Procedural film effects applied to the saved still; never touches the camera pipeline. */
    @Volatile
    private var overlayStyles: Set<OverlayStyle> = emptySet()
    @Volatile private var mirrorFrontPhotos = true
    @Volatile private var lateFlashBehavior = false

    /**
     * Photo exposure that follows the video recorder's auto exposure. The photo-mode viewfinder
     * then runs the same request video uses (record template and intent, AE range pinned to the
     * selected video frame rate), and a shot is taken with the exact ISO and shutter that AE
     * converged to there, instead of the darker result the stock still pipeline settles on.
     */
    @Volatile private var videoStyleExposure = true
    @Volatile private var videoFpsPreference = VideoMath.DEFAULT_FPS
    /** Newest ISO/shutter AE chose on the repeating request; fed to stills in video style. */
    @Volatile private var liveIso: Int? = null
    @Volatile private var liveShutterNs: Long? = null
    private val thumbnailSizes = HashMap<String, SizeI>()

    private var surfaceReady = false
    private var previewSurface: Surface? = null
    private var reader: ImageReader? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewReadySession: CameraCaptureSession? = null

    private var generation = 0
    private var sessionToken = 0

    /** Latest physical rotation, written from the sensor thread and read on the capture thread. */
    @Volatile
    private var deviceRotation = 0
    private var forceLogicalLensId: String? = null
    private val unsupportedLensIds = HashSet<String>()
    /** Last AE range pinned on the preview, only used to keep the diagnostic log quiet. */
    private var lastPreviewAeRange: IntRange? = null

    private var aeRegion: com.necra.purecam.core.RectI? = null
    private var afRegion: com.necra.purecam.core.RectI? = null
    private var focusAssist = false
    private var exposureCompensationSteps = 0
    private val compensationByCamera = HashMap<String, Pair<android.util.Range<Int>, Float>>()

    private var capturing = false
    private var pending: PendingCapture? = null
    /**
     * Flash precapture handshake phase:
     * 0 = idle, 1 = trigger sent and waiting for AE to enter PRECAPTURE,
     * 2 = preflash observed and waiting for it to finish.
     */
    private var precapturePhase = 0
    /** Consecutive distinct capture results confirming settled AE after the preflash. */
    private var settledAeResultCount = 0
    private var lastAeResultFrameNumber = -1L
    /** Wall clock when the precapture trigger was submitted, for the compatibility fallback. */
    private var precaptureTriggerMs = 0L
    /** Throttles the settling debug log so a long preflash does not flood logcat. */
    private var lastSettleKey: String? = null
    private var stillSpec: RequestSpec? = null
    private var stillLens: LensProfile? = null
    private var captureWatchdog: Runnable? = null
    private var settleTimeout: Runnable? = null

    @Volatile
    private var videoMode = false
    @Volatile
    private var recording = false
    private var recordingStarting = false
    private var stopAfterStart = false
    private var recorder: VideoRecorder? = null
    private var recorderSurface: Surface? = null
    private var recordingConfig: VideoConfig? = null
    private var recordingStartedMs = 0L
    private var recordSpec: RequestSpec? = null
    private var recordVerified = false
    private var pendingStop: Runnable? = null
    /** A record request that arrived while the camera was still reopening. */
    private var pendingRecording: Pair<VideoConfig, Boolean>? = null

    private var lastTransform: FloatArray? = null
    private var lastAutoGains: FloatArray? = null
    private var lastReadoutMs = 0L
    private var pendingZoomRequest: Runnable? = null
    private var lastVerification = "No still capture yet."
    private var lastIssues: List<String> = emptyList()

    // ---------------------------------------------------------------- lifecycle

    fun start() {
        cameraHandler.post {
            if (started) return@post
            started = true
            if (catalog.byFacing.isEmpty()) {
                catalog = CameraCaps.discover(cameraManager)
            }
            if (catalog.byFacing.isEmpty()) {
                notifyError("No camera found on this device")
                return@post
            }
            val previous = state
            // start() also runs on every resume (app switch, screen off, the audio-permission
            // dialog...). Coming back must never change the camera, so keep the lens the user was
            // on. Only a first start picks a default: the remembered facing, else the back camera.
            val resumeLens = previous?.lens?.let { prev ->
                catalog.lenses(prev.facing).firstOrNull { it.id == prev.id }
            }
            val lens = resumeLens
                ?: (previous?.lens?.facing ?: initialFacing)?.let { catalog.main(it) }
                ?: catalog.main(Facing.BACK)
                ?: catalog.main(Facing.FRONT)
            if (lens == null) {
                notifyError("No usable camera lens found")
                return@post
            }
            facing = lens.facing
            val base = when {
                previous == null -> CameraState.defaults(lens)
                previous.lens.id == lens.id -> previous
                else -> previous.withLens(lens)
            }
            state = base.clamped()
            notifyCatalog()
            notifyState()
            openCameraIfReady()
        }
    }

    fun stop() {
        cameraHandler.post {
            started = false
            closeCamera()
        }
    }

    fun shutdown() {
        cameraHandler.post {
            started = false
            closeCamera()
            previewSurface?.release()
            previewSurface = null
            handlerThread.quitSafely()
        }
        saver.shutdown()
    }

    fun onSurfaceAvailable(surfaceTexture: SurfaceTexture) {
        cameraHandler.post {
            previewSurface?.release()
            previewSurface = Surface(surfaceTexture)
            surfaceReady = true
            if (started) openCameraIfReady()
        }
    }

    private fun preparePreviewSurface(plan: AspectPlan): Surface? {
        val texture = previewView.surfaceTexture ?: return null
        return try {
            texture.setDefaultBufferSize(plan.previewSize.width, plan.previewSize.height)
            previewSurface?.takeIf { it.isValid } ?: Surface(texture).also { previewSurface = it }
        } catch (t: Throwable) {
            Log.e(tag, "could not prepare preview buffer ${plan.previewSize}", t)
            null
        }
    }

    fun onSurfaceDestroyed(surfaceTexture: SurfaceTexture) {
        cameraHandler.post {
            closeCamera()
            surfaceReady = false
            previewSurface?.release()
            previewSurface = null
            surfaceTexture.release()
        }
    }

    fun retry() {
        cameraHandler.post {
            closeCamera()
            openCameraIfReady()
        }
    }

    fun setDeviceRotation(degrees: Int) {
        deviceRotation = OrientationMath.normalizeDegrees(degrees)
    }

    fun setOverlays(styles: Set<OverlayStyle>) {
        overlayStyles = styles
    }

    fun setVideoStyleExposure(enabled: Boolean) {
        cameraHandler.post {
            if (videoStyleExposure == enabled) return@post
            videoStyleExposure = enabled
            liveIso = null
            liveShutterNs = null
            applyPreview()
        }
    }

    /** The saved video frame rate; the photo viewfinder follows it in video-style exposure. */
    fun setVideoFpsPreference(fps: Int) {
        cameraHandler.post {
            if (videoFpsPreference == fps) return@post
            videoFpsPreference = fps
            if (state?.let { videoStyleActive(it) } == true) applyPreview()
        }
    }

    private fun videoStyleActive(s: CameraState): Boolean =
        videoStyleExposure && !videoMode && !focusAssist &&
            s.exposure is ExposureMode.Auto && s.lens.supportsVideo && s.lens.supportsManualExposure &&
            !(s.flash && s.lens.flashAvailable)

    /** Frame rate the video recorder would pin for this lens. */
    private fun videoStyleFps(lens: LensProfile): Int =
        VideoMath.fpsFor(videoFpsPreference, VideoMath.supportedFps(lens.videoFpsRanges))

    /** [s] with the exposure video-style AE converged to, for the still request. */
    private fun videoStyleStillState(s: CameraState): CameraState {
        if (!videoStyleActive(s)) return s
        val iso = liveIso ?: return s
        val shutter = liveShutterNs ?: return s
        val lens = s.lens
        return s.copy(
            exposure = ExposureMode.Manual(
                lens.isoRange?.clamp(iso) ?: iso,
                lens.exposureRange?.clamp(shutter) ?: shutter
            )
        )
    }

    /** Largest embedded-thumbnail size the lens offers (up to 512 px wide) matching the JPEG ratio. */
    private fun thumbnailSizeFor(lens: LensProfile, outputAspect: Float): SizeI {
        val key = "${lens.cameraId}:${(outputAspect * 1000).toInt()}"
        return thumbnailSizes.getOrPut(key) {
            val offered = try {
                cameraManager.getCameraCharacteristics(lens.cameraId)
                    .get(CameraCharacteristics.JPEG_AVAILABLE_THUMBNAIL_SIZES)
                    ?.filter { it.width > 0 && it.height > 0 }
                    ?.map { SizeI(it.width, it.height) }
                    .orEmpty()
            } catch (t: Throwable) {
                Log.w(tag, "could not read thumbnail sizes", t)
                emptyList()
            }
            val pool = offered.filter { it.width <= 512 }.ifEmpty { offered }
            pool.minWithOrNull(
                compareBy<SizeI> { (AspectMath.relativeAspectError(it.aspect, outputAspect) * 20f).roundToInt() }
                    .thenByDescending { it.area }
            ) ?: SizeI(0, 0)
        }
    }

    fun setInitialFacing(facing: Facing?) {
        initialFacing = facing
    }

    fun setMirrorFrontPhotos(enabled: Boolean) {
        mirrorFrontPhotos = enabled
    }

    /**
     * Drops every control that is not a saved preference: lens choice, zoom, flash, manual
     * exposure/focus and AE compensation. The saved white balance is kept, and the camera is
     * moved back to the default (main back) lens like a cold start.
     */
    fun resetSessionState() {
        cameraHandler.post {
            if (recording || recordingStarting) finishRecording(save = true, reconfigure = false)
            val leftVideoMode = videoMode
            videoMode = false
            pendingRecording = null
            exposureCompensationSteps = 0
            aeRegion = null
            afRegion = null
            pendingZoomRequest?.let { cameraHandler.removeCallbacks(it) }
            pendingZoomRequest = null

            val s = state ?: return@post
            val targetFacing = if (catalog.lenses(Facing.BACK).isNotEmpty()) {
                Facing.BACK
            } else {
                s.lens.facing
            }
            val lens = catalog.main(targetFacing) ?: s.lens
            val next = CameraState.defaults(lens).copy(wb = s.wb).clamped()
            facing = lens.facing
            state = next
            forceLogicalLensId = null
            if (lens.id == s.lens.id && lens.cameraId == s.lens.cameraId) {
                notifyState()
                if (device == null) {
                    // Paused: the next start() builds a session from this state.
                } else if (leftVideoMode) {
                    // Rebuild so the still-capable session and photo framing come back.
                    closeSessionAndReader()
                    createSession()
                } else {
                    applyPreview()
                }
            } else {
                notifyCatalog()
                notifyState()
                closeCamera()
                openCameraIfReady()
            }
        }
    }

    fun setLateFlashBehavior(enabled: Boolean) {
        lateFlashBehavior = enabled
    }

    fun setCaptureAspectMode(mode: CaptureAspectMode) {
        cameraHandler.post {
            if (captureAspectMode == mode || recording || recorder != null) return@post
            captureAspectMode = mode
            aeRegion = null
            afRegion = null
            if (device != null) {
                closeSessionAndReader()
                if (surfaceReady) createSession()
            }
        }
    }

    // ---------------------------------------------------------------- public state

    fun state(): CameraState? = state
    fun catalog(): LensCatalog = catalog
    fun readout(): LiveReadout? = latestReadout
    fun plan(): AspectPlan? = currentPlan
    fun strategy(): LensMode = effectiveStrategy
    fun isCapturing(): Boolean = capturing
    fun isRecording(): Boolean = recording
    fun isVideoMode(): Boolean = videoMode
    fun unsupportedLenses(): Set<String> = unsupportedLensIds.toSet()
    fun lastIssues(): List<String> = lastIssues

    fun capabilityReport(): String {
        val probes = prefs.all.entries.joinToString("\n") { "${it.key} = ${it.value}" }
        return buildString {
            append(CameraCaps.report(catalog))
            appendLine()
            appendLine("Effective strategy: $effectiveStrategy")
            appendLine()
            appendLine("Last capture verification:")
            appendLine(lastVerification)
            appendLine()
            appendLine("Physical-lens probe cache:")
            appendLine(probes.ifEmpty { "(empty)" })
        }
    }

    // ---------------------------------------------------------------- user actions

    /** True while a recording is running, starting, or waiting for the camera to come back. */
    private fun recordingBusy(): Boolean = recording || recordingStarting || pendingRecording != null

    fun setLens(lens: LensProfile) {
        cameraHandler.post {
            if (recordingBusy()) return@post
            val s = state ?: return@post
            if (s.lens.id == lens.id) return@post
            if (unsupportedLensIds.contains(lens.id)) return@post
            pendingRecording = null
            val oldCamera = s.lens.cameraId
            state = s.withLens(lens).clamped()
            forceLogicalLensId = null
            aeRegion = null
            afRegion = null
            if (oldCamera != lens.cameraId) {
                closeCamera()
                openCameraIfReady()
            } else {
                closeSessionAndReader()
                notifyState()
                createSession()
            }
        }
    }

    fun flipFacing() {
        cameraHandler.post {
            if (recordingBusy()) return@post
            val current = state?.lens?.facing ?: facing
            val next = when {
                current == Facing.BACK && catalog.lenses(Facing.FRONT).isNotEmpty() -> Facing.FRONT
                current == Facing.FRONT && catalog.lenses(Facing.BACK).isNotEmpty() -> Facing.BACK
                else -> current
            }
            if (next == current) return@post
            pendingRecording = null
            val lens = catalog.main(next) ?: return@post
            val base = state ?: CameraState.defaults(lens)
            facing = next
            state = base.withLens(lens).clamped()
            forceLogicalLensId = null
            aeRegion = null
            afRegion = null
            notifyCatalog()
            notifyState()
            closeCamera()
            openCameraIfReady()
        }
    }

    fun setFlash(on: Boolean) {
        cameraHandler.post {
            val s = state ?: return@post
            val next = s.copy(flash = on && s.lens.flashAvailable).clamped()
            if (next == s) return@post
            state = next
            applyPreview()
            notifyState()
        }
    }

    fun setZoom(zoom: Float) {
        cameraHandler.post {
            val s = state ?: return@post
            val clamped = zoom.coerceIn(1f, s.lens.maxZoom.coerceAtLeast(1f))
            if (kotlin.math.abs(clamped - s.zoom) < 0.001f) return@post
            state = s.copy(zoom = clamped)
            if (pendingZoomRequest == null) {
                val update = Runnable {
                    pendingZoomRequest = null
                    applyPreview()
                }
                pendingZoomRequest = update
                cameraHandler.postDelayed(update, ZOOM_REQUEST_INTERVAL_MS)
            }
        }
    }

    /**
     * Auto-exposure compensation in EV. The value is converted to the device's compensation
     * steps and clamped to the range it advertises, so the slider never sends invalid values.
     */
    fun setExposureCompensation(ev: Float) {
        cameraHandler.post {
            val lens = state?.lens ?: return@post
            val (range, step) = compensationByCamera.getOrPut(lens.cameraId) {
                try {
                    val c = cameraManager.getCameraCharacteristics(lens.cameraId)
                    val r = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                        ?: android.util.Range(0, 0)
                    val s = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
                    r to (s?.let { it.numerator.toFloat() / it.denominator.toFloat() } ?: 0f)
                } catch (t: Throwable) {
                    Log.w(tag, "could not read AE compensation range", t)
                    android.util.Range(0, 0) to 0f
                }
            }
            if (step <= 0f) return@post
            val steps = (ev / step).roundToInt().coerceIn(range.lower, range.upper)
            if (steps == exposureCompensationSteps) return@post
            exposureCompensationSteps = steps
            applyPreview()
        }
    }

    /**
     * Manual exposure for a Normal flash still, or null when the lens cannot do manual sensor
     * control (then the precapture path with plain AE is used). Subject distance comes from the
     * lens focus distance reported by continuous AF.
     */
    private fun normalFlashStillExposure(s: CameraState): ExposureMode.Manual? {
        if (lateFlashBehavior || !s.flash || !s.lens.flashAvailable) return null
        if (s.exposure !is ExposureMode.Auto || !s.lens.supportsManualExposure) return null
        val isoRange = s.lens.isoRange ?: return null
        val exposureRange = s.lens.exposureRange ?: return null
        val diopters = latestReadout?.focusDiopters
        val meters = if (diopters != null && diopters > 0.01f) {
            (1.0 / diopters).coerceIn(FLASH_SUBJECT_MIN_M, FLASH_SUBJECT_MAX_M)
        } else FLASH_SUBJECT_DEFAULT_M
        val iso = (FLASH_STILL_ISO_AT_1M * meters * meters)
            .coerceAtMost(FLASH_STILL_MAX_ISO.toDouble()).roundToInt()
        return ExposureMode.Manual(
            isoRange.clamp(iso),
            exposureRange.clamp(FLASH_STILL_SHUTTER_NS)
        )
    }

    fun setExposure(mode: ExposureMode) {
        cameraHandler.post {
            val s = state ?: return@post
            val next = s.copy(exposure = mode).clamped()
            if (next == s) return@post
            state = next
            applyPreview()
            notifyState()
        }
    }

    fun setFocus(mode: FocusMode) {
        cameraHandler.post {
            val s = state ?: return@post
            val next = s.copy(focus = mode).clamped()
            if (next == s) return@post
            state = next
            applyPreview()
            notifyState()
        }
    }

    fun setWhiteBalance(mode: WhiteBalanceMode) {
        cameraHandler.post {
            val s = state ?: return@post
            val next = s.copy(wb = mode).clamped()
            if (next == s) return@post
            state = next
            applyPreview()
            notifyState()
        }
    }

    fun setFocusAssist(active: Boolean) {
        cameraHandler.post {
            if (focusAssist == active) return@post
            focusAssist = active
            applyPreview()
        }
    }

    fun tapToFocus(viewX: Float, viewY: Float) {
        cameraHandler.post {
            val s = state ?: return@post
            if (s.focus is FocusMode.Manual) return@post
            val plan = currentPlan ?: return@post
            val lens = s.lens
            val width = previewView.width
            val height = previewView.height
            if (width <= 0 || height <= 0) return@post
            val useZoomRatio = effectiveStrategy == LensMode.LOGICAL && lens.zoomRatioRange != null
            val cropArray = if (effectiveStrategy == LensMode.LOGICAL) lens.logicalActiveArray else lens.activeArray
            val crop = AspectMath.cropRect(cropArray, plan.outputAspect, if (useZoomRatio) 1f else s.zoom)
            val point = FocusMath.mapTap(
                viewX = viewX,
                viewY = viewY,
                viewWidth = width,
                viewHeight = height,
                contentAspect = plan.contentAspect,
                sensorOrientation = lens.sensorOrientation,
                crop = crop,
                activeArray = cropArray
            )
            val rect = FocusMath.meteringRect(point, crop, cropArray)
            afRegion = rect
            aeRegion = if (s.exposure is ExposureMode.Auto) rect else null

            val builder = buildPreviewRequest() ?: return@post
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            // AE precapture is useful for metering the tapped spot, but with the flash armed
            // it makes the HAL run its preflash sequence, so the LED would blink every time
            // the user taps the viewfinder. Keep it for plain (flash-off) auto exposure only.
            if (s.exposure is ExposureMode.Auto && !s.flash) {
                builder.set(
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START
                )
            }
            try {
                session?.capture(builder.build(), null, cameraHandler)
            } catch (t: Throwable) {
                Log.w(tag, "tap focus trigger failed", t)
            }
            cameraHandler.postDelayed({ applyPreview() }, 400)
        }
    }

    fun capture() {
        cameraHandler.post { captureInternal() }
    }

    // ---------------------------------------------------------------- video

    /** Switches still and video sessions to the selected saved framing. */
    fun setVideoMode(enabled: Boolean) {
        cameraHandler.post {
            if (videoMode == enabled || recording || recorder != null) return@post
            val s = state
            if (s == null) {
                // Called before the first camera open (restored preference); start() picks it up.
                videoMode = enabled
                return@post
            }
            if (enabled && !s.lens.supportsVideo) {
                notifyError("This lens cannot record video")
                return@post
            }
            videoMode = enabled
            state = s
            pendingRecording = null
            aeRegion = null
            afRegion = null
            closeSessionAndReader()
            notifyState()
            if (device != null) createSession()
        }
    }

    fun startRecording(config: VideoConfig, withAudio: Boolean) {
        cameraHandler.post {
            if (recording || recordingStarting || recorder != null || !videoMode) return@post
            val s = state ?: return@post
            val actual = resolveVideoConfig(s.lens, config) ?: run {
                notifyError("This lens cannot record video")
                return@post
            }
            if (device == null || session == null || !surfaceReady) {
                // The audio-permission dialog pauses the activity and the camera reopens
                // asynchronously; honour the tap once the session is back.
                pendingRecording = actual to withAudio
                return@post
            }
            startRecordingSession(actual, withAudio)
        }
    }

    fun stopRecording() {
        cameraHandler.post {
            if (pendingRecording != null && !recording && !recordingStarting) {
                // The tap to start has not reached a session yet (audio dialog): cancel it.
                pendingRecording = null
                mainHandler.post { listener.onRecordingFinished(null, null) }
                return@post
            }
            if (recordingStarting) {
                // The encoder has not produced a frame yet; remember the tap and stop as soon
                // as it starts, so a double tap never leaves the camera recording.
                stopAfterStart = true
                return@post
            }
            if (!recording || pendingStop != null) return@post
            scheduleStop()
        }
    }

    private fun scheduleStop() {
        val rec = recorder ?: return
        val elapsed = rec.elapsedMs()
        if (elapsed >= VideoMath.MIN_RECORDING_MS) {
            stopRecordingNow()
        } else {
            // A very quick tap would otherwise produce an empty, unplayable MP4.
            val runnable = Runnable {
                pendingStop = null
                stopRecordingNow()
            }
            pendingStop = runnable
            cameraHandler.postDelayed(runnable, VideoMath.MIN_RECORDING_MS - elapsed)
        }
    }

    private fun stopRecordingNow() {
        if (!recording) return
        finishRecording(save = true, reconfigure = true)
    }

    private fun startRecordingSession(config: VideoConfig, withAudio: Boolean) {
        if (!surfaceReady) return
        val d = device ?: return
        val lens = state?.lens ?: return

        val plan = AspectMath.planForOutput(lens, config.size)
        currentPlan = plan

        // Recording needs exactly two outputs: preview and encoder. The JPEG reader goes away so
        // the session stays within every device's guaranteed stream combination.
        closeSessionAndReader()
        val preview = preparePreviewSurface(plan) ?: run {
            notifyError("Preview surface is not ready")
            return
        }

        val rec = VideoRecorder(context)
        var usedAudio = withAudio
        // Like the front JPEG, the front recorder stream arrives already mirrored on this device
        // (the live preview is not). Keep those pixels for "Mirrored" and flip the clip only for
        // "As previewed", exactly like the photo path.
        val mirrorVideo = !mirrorFrontPhotos && lens.facing == Facing.FRONT
        val surface = try {
            rec.open(config, videoOrientationHint(), withAudio, mirrorVideo)
        } catch (first: Throwable) {
            if (!withAudio) {
                Log.e(tag, "could not prepare video recorder", first)
                rec.release(deleteRow = true)
                notifyError("Could not start the video recorder")
                createSession()
                return
            }
            Log.w(tag, "audio recording unavailable; retrying without sound", first)
            usedAudio = false
            try {
                rec.open(config, videoOrientationHint(), false, mirrorVideo)
            } catch (second: Throwable) {
                Log.e(tag, "could not prepare silent video recorder", second)
                rec.release(deleteRow = true)
                notifyError("Could not start the video recorder")
                createSession()
                return
            }
        }
        recorder = rec
        recorderSurface = surface
        recordingConfig = config
        recordSpec = null
        recordVerified = false
        recordingStarting = true
        stopAfterStart = false

        val token = ++sessionToken
        val previewCfg = OutputConfiguration(preview)
        val recordCfg = OutputConfiguration(surface)
        if (effectiveStrategy == LensMode.PHYSICAL && lens.physicalId != null) {
            previewCfg.setPhysicalCameraId(lens.physicalId)
            recordCfg.setPhysicalCameraId(lens.physicalId)
        }

        try {
            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(previewCfg, recordCfg),
                Executor { runnable -> cameraHandler.post(runnable) },
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(created: CameraCaptureSession) {
                        if (token != sessionToken || device == null) {
                            created.close()
                            rec.cancel()
                            if (recorder === rec) {
                                recorder = null
                                recorderSurface = null
                                recordingConfig = null
                                recordingStarting = false
                            }
                            return
                        }
                        session = created
                        val builder = buildRecordRequest()
                        if (builder == null) {
                            notifyError("Could not configure the video request")
                            finishRecording(save = false, reconfigure = true)
                            return
                        }
                        try {
                            created.setRepeatingRequest(builder.build(), recordCallback, cameraHandler)
                            rec.start()
                            recording = true
                            recordingStarting = false
                            recordingStartedMs = SystemClock.elapsedRealtime()
                            Log.i(
                                tag,
                                "recording ${config.size} ${config.fps}fps audio=$usedAudio " +
                                    "strategy=$effectiveStrategy"
                            )
                            mainHandler.post { listener.onRecordingStarted(config, usedAudio) }
                            if (stopAfterStart) {
                                stopAfterStart = false
                                scheduleStop()
                            }
                        } catch (t: Throwable) {
                            Log.e(tag, "could not start recording", t)
                            finishRecording(save = false, reconfigure = true)
                            notifyError("Could not start recording: ${t.message}")
                        }
                    }

                    override fun onConfigureFailed(created: CameraCaptureSession) {
                        if (token != sessionToken) return
                        Log.e(tag, "recording session configuration failed")
                        rec.cancel()
                        recorder = null
                        recorderSurface = null
                        recordingConfig = null
                        recordingStarting = false
                        stopAfterStart = false
                        notifyError("Could not configure the video session")
                        createSession()
                    }
                }
            )
            d.createCaptureSession(sessionConfig)
        } catch (t: Throwable) {
            Log.e(tag, "createCaptureSession (video) failed", t)
            rec.cancel()
            recorder = null
            recorderSurface = null
            recordingConfig = null
            recordingStarting = false
            stopAfterStart = false
            notifyError("Could not configure the video session")
            createSession()
        }
    }

    private fun resolveVideoConfig(lens: LensProfile, requested: VideoConfig? = null): VideoConfig? {
        val preferredAspect = AspectMath.videoOutputAspect(lens, captureAspectMode)
        val qualities = VideoMath.availableQualities(lens.videoSizes, preferredAspect)
        val quality = requested?.quality?.takeIf { qualities.contains(it) }
            ?: VideoMath.defaultQuality(qualities)
            ?: return null
        val size = VideoMath.sizeFor(quality, lens.videoSizes, preferredAspect) ?: return null
        val fpsOptions = VideoMath.supportedFps(lens.videoFpsRanges)
        val fps = VideoMath.fpsFor(requested?.fps ?: VideoMath.DEFAULT_FPS, fpsOptions)
        return VideoConfig(quality, size, fps)
    }

    /**
     * Stops the encoder and publishes the file. With [reconfigure] the still-capable session comes
     * back so the next shutter press is instant; teardown paths pass false.
     */
    private fun finishRecording(save: Boolean, reconfigure: Boolean, errorOverride: String? = null) {
        val rec = recorder ?: return
        pendingStop?.let { cameraHandler.removeCallbacks(it) }
        pendingStop = null
        val wasRecording = recording
        recording = false
        recordingStarting = false
        stopAfterStart = false
        recorder = null
        recorderSurface = null
        recordingConfig = null
        recordSpec = null
        runCatching { session?.stopRepeating() }
        runCatching { session?.abortCaptures() }

        var uri: Uri? = null
        var error: String? = errorOverride
        if (save && wasRecording && errorOverride == null) {
            if (rec.isMirroring) {
                // Mirroring re-encodes the clip; tell the UI before the blocking finalize.
                mainHandler.post { listener.onRecordingSaving() }
            }
            try {
                uri = rec.stop()
                Log.i(tag, "recording saved: $uri")
            } catch (t: Throwable) {
                Log.e(tag, "could not finish recording", t)
                error = "Could not save the video"
            }
        } else {
            rec.cancel()
        }

        mainHandler.post { listener.onRecordingFinished(uri, error) }
        if (reconfigure && device != null) createSession() else notifyState()
    }

    private fun videoOrientationHint(): Int {
        val lens = state?.lens ?: return 0
        return OrientationMath.jpegOrientation(lens.sensorOrientation, deviceRotation, lens.facing)
    }

    // ---------------------------------------------------------------- camera open / session

    private fun openCameraIfReady() {
        if (!started) return
        if (!surfaceReady) return
        val s = state ?: return
        if (device != null) closeCamera()
        val gen = ++generation
        try {
            cameraManager.openCamera(s.lens.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (gen != generation) {
                        camera.close()
                        return
                    }
                    device = camera
                    Log.i(tag, "opened camera ${s.lens.cameraId} lens=${s.lens.label}")
                    createSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (gen != generation) return
                    device = null
                    closeSessionAndReader()
                    notifyError("Camera disconnected")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (gen != generation) return
                    device = null
                    closeSessionAndReader()
                    notifyError("Camera error $error")
                }

                override fun onClosed(camera: CameraDevice) {
                    if (gen == generation) device = null
                }
            }, cameraHandler)
        } catch (e: SecurityException) {
            notifyError("Camera permission is required")
        } catch (e: CameraAccessException) {
            notifyError("Could not open camera: ${e.reason}")
        } catch (t: Throwable) {
            notifyError("Could not open camera: ${t.message}")
        }
    }

    private fun createSession() {
        val d = device ?: return
        if (!surfaceReady) return
        val s = state ?: return

        // Video keeps the preview on the encoder's aspect even before recording starts, so the
        // framing does not jump when the user presses record.
        val photoPlan = AspectMath.planForCapture(s.lens, captureAspectMode)
        val plan = if (videoMode) {
            resolveVideoConfig(s.lens)?.let { AspectMath.planForOutput(s.lens, it.size) }
                ?: photoPlan
        } else {
            photoPlan
        }
        currentPlan = plan
        closeSessionAndReader()
        val token = ++sessionToken

        val jpegReader = try {
            ImageReader.newInstance(photoPlan.jpegSize.width, photoPlan.jpegSize.height, ImageFormat.JPEG, 2)
        } catch (t: Throwable) {
            notifyError("Could not create JPEG output: ${t.message}")
            return
        }
        jpegReader.setOnImageAvailableListener(jpegListener, cameraHandler)
        reader = jpegReader

        val pSurface = preparePreviewSurface(plan) ?: run {
            notifyError("Preview surface is not ready")
            return
        }

        val lens = s.lens
        val physicalId = lens.physicalId
        effectiveStrategy = when {
            lens.mode == LensMode.DIRECT -> LensMode.DIRECT
            lens.mode == LensMode.LOGICAL -> LensMode.LOGICAL
            physicalId == null -> LensMode.LOGICAL
            forceLogicalLensId == lens.id -> LensMode.LOGICAL
            physicalProbe(d, lens, plan, pSurface, jpegReader.surface) -> LensMode.PHYSICAL
            else -> LensMode.LOGICAL
        }

        if (effectiveStrategy == LensMode.LOGICAL && lens.mode == LensMode.PHYSICAL) {
            if (!logicalZoomCanReach(lens)) {
                Log.w(tag, "lens ${lens.label} (${lens.id}) cannot be selected: physical stream unsupported and zoom range ${lens.zoomRatioRange} cannot reach ${lens.baseZoom}")
                removeLensAndFallback(lens)
                return
            }
        }

        val previewCfg = OutputConfiguration(pSurface)
        val jpegCfg = OutputConfiguration(jpegReader.surface)
        if (effectiveStrategy == LensMode.PHYSICAL && physicalId != null) {
            previewCfg.setPhysicalCameraId(physicalId)
            jpegCfg.setPhysicalCameraId(physicalId)
        }

        try {
            val config = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(previewCfg, jpegCfg),
                Executor { runnable -> cameraHandler.post(runnable) },
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (token != sessionToken || device == null) {
                            session.close()
                            return
                        }
                        this@CameraController.session = session
                        Log.i(tag, "session ready: lens=${lens.label} strategy=$effectiveStrategy plan=$plan")
                        notifyState()
                        applyPreview()
                        pendingRecording?.let { (config, withAudio) ->
                            pendingRecording = null
                            startRecordingSession(config, withAudio)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (token != sessionToken) return
                        handleConfigureFailed(lens)
                    }
                }
            )
            d.createCaptureSession(config)
        } catch (t: Throwable) {
            Log.e(tag, "createCaptureSession failed", t)
            handleConfigureFailed(lens)
        }
    }

    private fun handleConfigureFailed(lens: LensProfile) {
        if (effectiveStrategy == LensMode.PHYSICAL) {
            Log.w(tag, "physical session failed for ${lens.id}; falling back to logical camera")
            markProbe(lens, false)
            forceLogicalLensId = lens.id
            closeSessionAndReader()
            if (!logicalZoomCanReach(lens)) {
                removeLensAndFallback(lens)
            } else {
                createSession()
            }
        } else {
            closeSessionAndReader()
            notifyError("Could not configure the camera session")
        }
    }

    private fun physicalProbe(
        d: CameraDevice,
        lens: LensProfile,
        plan: AspectPlan,
        preview: Surface,
        jpeg: Surface
    ): Boolean {
        val physicalId = lens.physicalId ?: return false
        val key = probeKey(lens, plan)
        if (prefs.contains(key)) return prefs.getBoolean(key, false)
        val ok = try {
            val p = OutputConfiguration(preview).apply { setPhysicalCameraId(physicalId) }
            val j = OutputConfiguration(jpeg).apply { setPhysicalCameraId(physicalId) }
            val cfg = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(p, j),
                Executor { it.run() },
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) = session.close()
                    override fun onConfigureFailed(session: CameraCaptureSession) = Unit
                }
            )
            d.isSessionConfigurationSupported(cfg)
        } catch (t: Throwable) {
            Log.w(tag, "isSessionConfigurationSupported failed for $physicalId: ${t.message}")
            false
        }
        markProbe(lens, ok)
        Log.i(tag, "probe physical $physicalId (${plan.previewSize}/${plan.jpegSize}): $ok")
        return ok
    }

    private fun probeKey(lens: LensProfile, plan: AspectPlan): String =
        "probe:${lens.cameraId}:${lens.physicalId}:${plan.previewSize}:${plan.jpegSize}"

    private fun markProbe(lens: LensProfile, ok: Boolean) {
        val plan = currentPlan ?: return
        prefs.edit().putBoolean(probeKey(lens, plan), ok).apply()
    }

    private fun logicalZoomCanReach(lens: LensProfile): Boolean {
        if (kotlin.math.abs(lens.baseZoom - 1f) < 0.05f) return true
        val range = lens.zoomRatioRange ?: return false
        return lens.baseZoom >= range.min - 0.01f && lens.baseZoom <= range.max + 0.01f
    }

    private fun removeLensAndFallback(lens: LensProfile) {
        unsupportedLensIds.add(lens.id)
        val updated = catalog.byFacing.mapValues { (_, list) -> list.filterNot { it.id == lens.id } }
        catalog = LensCatalog(updated)
        val replacement = catalog.main(lens.facing)
        mainHandler.post { listener.onLensRemoved(lens, replacement) }
        notifyCatalog()
        if (replacement == null) {
            notifyError("No usable lens on this camera")
            return
        }
        val base = state ?: CameraState.defaults(replacement)
        state = base.withLens(replacement).clamped()
        forceLogicalLensId = null
        notifyState()
        closeSessionAndReader()
        if (device != null) createSession() else openCameraIfReady()
    }

    private fun closeSessionAndReader() {
        sessionToken++
        pendingStop?.let { cameraHandler.removeCallbacks(it) }
        pendingStop = null
        // Pausing or reconfiguring mid-recording must still publish what was written so far.
        if (recording || recordingStarting) finishRecording(save = true, reconfigure = false)
        if (capturing) {
            removeCaptureRunnables()
            capturing = false
            pending = null
            mainHandler.post { listener.onCaptureFinished(null, "Capture cancelled") }
        }
        stillSpec = null
        stillLens = null
        // The next session belongs to a (possibly different) camera or mode, so the last sampled
        // AE/AWB state must not be reused to decide whether the new preview has settled.
        latestReadout = null
        lastReadoutMs = 0L
        liveIso = null
        liveShutterNs = null
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        session = null
        runCatching { reader?.setOnImageAvailableListener(null, null) }
        runCatching { reader?.close() }
        reader = null
    }

    private fun closeCamera() {
        generation++
        pendingRecording = null
        recordingStarting = false
        stopAfterStart = false
        closeSessionAndReader()
        runCatching { device?.close() }
        device = null
        currentPlan = null
    }

    // ---------------------------------------------------------------- preview requests

    private fun buildPreviewRequest(flashMetering: Boolean = false): CaptureRequest.Builder? = try {
        buildPreviewRequestUnchecked(flashMetering)
    } catch (t: Throwable) {
        // A single bad request key must never take down the camera thread; fall back to the
        // last working repeating request until the state changes again.
        Log.e(tag, "could not build preview request", t)
        null
    }

    private fun buildPreviewRequestUnchecked(flashMetering: Boolean): CaptureRequest.Builder? {
        val s = state ?: return null
        val plan = currentPlan ?: return null
        val d = device ?: return null
        val surface = previewSurface ?: return null
        val videoStyle = videoStyleActive(s)
        val requestedVideoFps = when {
            videoMode -> resolveVideoConfig(s.lens)?.fps ?: VideoMath.DEFAULT_FPS
            videoStyle -> videoStyleFps(s.lens)
            else -> null
        }
        val previewFpsRange = requestedVideoFps?.let {
            VideoMath.aeRangeFor(it, s.lens.videoFpsRanges)
        } ?: VideoMath.previewAeRange(s.lens.videoFpsRanges)
        if (previewFpsRange != lastPreviewAeRange) {
            lastPreviewAeRange = previewFpsRange
            Log.i(tag, "preview AE range=${previewFpsRange ?: "device default"}")
        }
        val spec = RequestBuilder.build(
            state = s,
            profile = s.lens,
            // Video-style exposure uses exactly the request the recorder repeats: record intent,
            // record template, AE range pinned to the video frame rate.
            intent = if (videoStyle) CaptureIntent.RECORD else CaptureIntent.PREVIEW,
            outputAspect = plan.outputAspect,
            strategy = effectiveStrategy,
            previewClamp = true,
            focusAssist = focusAssist,
            fallbackTransform = lastTransform,
            videoFps = requestedVideoFps,
            aeFpsRange = previewFpsRange?.let { RangeI(it.first, it.last) },
            videoMode = videoMode || videoStyle,
            exposureCompensation = exposureCompensationSteps,
            flashMetering = flashMetering
        )
        val builder = d.createCaptureRequest(
            if (videoStyle) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
        )
        builder.addTarget(surface)
        RequestApplier.apply(builder, spec, jpegOrientation(), aeRegion, afRegion)
        if (videoStyle) builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
        return builder
    }

    /**
     * Repeating request for an active recording: preview plus encoder, pinned frame rate, torch
     * instead of flash. Rebuilt whenever exposure/focus/WB change, so manual controls stay live.
     */
    private fun buildRecordRequest(): CaptureRequest.Builder? {
        val s = state ?: return null
        val plan = currentPlan ?: return null
        val d = device ?: return null
        val preview = previewSurface ?: return null
        val recSurface = recorderSurface ?: return null
        val config = recordingConfig ?: return null
        val range = VideoMath.aeRangeFor(config.fps, s.lens.videoFpsRanges)
        return try {
            val spec = RequestBuilder.build(
                state = s,
                profile = s.lens,
                intent = CaptureIntent.RECORD,
                outputAspect = plan.outputAspect,
                strategy = effectiveStrategy,
                previewClamp = true,
                focusAssist = false,
                fallbackTransform = lastTransform,
                videoFps = config.fps,
                aeFpsRange = range?.let { RangeI(it.first, it.last) },
                videoMode = true,
                exposureCompensation = exposureCompensationSteps
            )
            val builder = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            builder.addTarget(preview)
            builder.addTarget(recSurface)
            RequestApplier.apply(builder, spec, jpegOrientation(), aeRegion, afRegion)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            recordSpec = spec
            builder
        } catch (t: Throwable) {
            Log.e(tag, "could not build record request", t)
            null
        }
    }

    private fun applyPreview() {
        if (capturing) return
        val builder = (if (recording) buildRecordRequest() else buildPreviewRequest()) ?: return
        val callback = if (recording) recordCallback else previewCallback
        try {
            session?.setRepeatingRequest(builder.build(), callback, cameraHandler)
        } catch (t: Throwable) {
            Log.e(tag, "setRepeatingRequest failed", t)
            notifyError("Preview request failed: ${t.message}")
        }
    }

    private fun jpegOrientation(): Int {
        val lens = state?.lens ?: return 0
        return OrientationMath.jpegOrientation(lens.sensorOrientation, deviceRotation, lens.facing)
    }

    // ---------------------------------------------------------------- capture

    private fun captureInternal() {
        if (capturing || videoMode) return
        val s = state ?: return
        currentPlan ?: return
        if (session == null || reader == null || previewSurface == null || device == null) {
            notifyError("Camera is not ready yet")
            return
        }

        capturing = true
        lastIssues = emptyList()
        stillSpec = null
        stillLens = null
        val manualShutter = (s.exposure as? ExposureMode.Manual)?.shutterNs
        mainHandler.post { listener.onCaptureStarted(manualShutter) }

        // Normal mode lets the HAL run and finish its AE/flash metering sequence before the
        // forced single-flash still. Late mode intentionally skips metering and captures directly.
        // Normal with manual sensor support needs no preflash/precapture at all (see
        // normalFlashStillExposure): one flash, no extra delay.
        val flashOn = s.flash && s.lens.flashAvailable
        val needFlashAe = !lateFlashBehavior && s.exposure is ExposureMode.Auto &&
            flashOn && normalFlashStillExposure(s) == null
        // A flash-less still also needs a settled AE. Firing immediately (as this used to do)
        // captures whatever exposure the preview currently has, which can still be searching and
        // much darker than the scene - especially on the front camera right after switching to it.
        val aeSettled = latestReadout?.aeState.let {
            it == CameraMetadata.CONTROL_AE_STATE_CONVERGED ||
                it == CameraMetadata.CONTROL_AE_STATE_LOCKED
        }
        val needPlainAe = !lateFlashBehavior && s.exposure is ExposureMode.Auto &&
            !flashOn && !aeSettled
        val needAe = needFlashAe || needPlainAe
        val needLens = s.focus is FocusMode.Manual
        val now = SystemClock.elapsedRealtime()
        val deadline = now + when {
            // A flash precapture needs the preflash to meter the scene and the flash unit to
            // recharge before the main flash. Slow HALs regularly need more than 1.6 s.
            needFlashAe -> 4_000L
            // Plain AE normally settles in a few frames; do not hold the shutter for long.
            needPlainAe -> 1_200L
            needLens -> 1_100L
            else -> 0L
        }
        // Continuous autofocus keeps working on the preview stream. Don't add shutter lag by
        // waiting for its passive AF state to change; capture with the current focus position.
        pending = PendingCapture(needAe, needFlashAe, needLens, now, deadline)
        precapturePhase = if (needAe) 1 else 0
        settledAeResultCount = 0
        lastAeResultFrameNumber = -1L
        lastSettleKey = null

        if (needAe) {
            startAePrecapture()
        } else {
            // With AE precapture running there is nothing useful to check until a fresh result
            // arrives, so only try an immediate capture when no settling is required.
            maybeFireCapture(false)
        }
        if (pending != null) {
            val timeout = if (deadline > now) deadline - now else 1L
            val runnable = Runnable {
                settleTimeout = null
                maybeFireCapture(true)
            }
            settleTimeout = runnable
            cameraHandler.postDelayed(runnable, timeout)
        }
    }

    /**
     * Starts the AE precapture sequence with a single one-shot request.
     *
     * The repeating preview already runs in a flash-capable AE mode while the flash is on,
     * which arms the flash without firing it. Only the one-shot request carries the START
     * trigger; replacing the repeating request at the same time (as the old code did) resets
     * the handshake, so AE never settled and the shot was released at the wrong moment.
     *
     * With the flash off this is a plain AE precapture: it makes the HAL converge on the
     * current scene so the one-shot still does not inherit an exposure that is still searching.
     */
    private fun startAePrecapture() {
        val sess = session ?: return
        val capture = pending ?: return
        val start = buildPreviewRequest(flashMetering = true) ?: return
        start.set(
            CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
            CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START
        )
        val callback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureProgressed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                partialResult: CaptureResult
            ) {
                if (pending === capture) {
                    maybeFireCapture(false, null, partialResult.get(CaptureResult.CONTROL_AE_STATE))
                }
            }

            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                // Feed the fresh result through so a settled AE state can fire immediately.
                if (pending === capture) maybeFireCapture(false, result)
            }

            override fun onCaptureFailed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                failure: CaptureFailure
            ) {
                Log.w(tag, "precapture failed: reason=${failure.reason}")
            }
        }
        try {
            precaptureTriggerMs = SystemClock.elapsedRealtime()
            sess.capture(start.build(), callback, cameraHandler)
        } catch (t: Throwable) {
            Log.w(tag, "precapture request failed", t)
        }
    }

    private fun maybeFireCapture(
        force: Boolean,
        result: TotalCaptureResult? = null,
        aeStateOverride: Int? = null
    ) {
        val p = pending ?: return
        if (!capturing) return
        val aeState = aeStateOverride
            ?: result?.get(CaptureResult.CONTROL_AE_STATE)
            ?: latestReadout?.aeState
        val lensState = result?.get(CaptureResult.LENS_STATE) ?: latestReadout?.lensState
        val flashState = result?.get(CaptureResult.FLASH_STATE) ?: latestReadout?.flashState
        // Follow the documented precapture handshake: wait until AE reports that it is running
        // the preflash sequence (PRECAPTURE), then wait for it to leave that state. Firing on
        // the first "flash required" result (as the old code did) released the shutter while
        // the preflash was still charging, so the main flash never had time to fire.
        if (p.needAe && precapturePhase == 1 && aeState == CameraMetadata.CONTROL_AE_STATE_PRECAPTURE) {
            precapturePhase = 2
            settledAeResultCount = 0
            Log.i(tag, "precapture: AE metering started")
        }
        val settled = aeState == CameraMetadata.CONTROL_AE_STATE_CONVERGED ||
            aeState == CameraMetadata.CONTROL_AE_STATE_LOCKED ||
            aeState == CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED
        // The AE preflash can consume the charge needed for the still. Let it recharge before
        // submitting the final single-flash request when the HAL reports its flash state.
        val flashReady = flashState == null ||
            flashState == CameraMetadata.FLASH_STATE_UNAVAILABLE ||
            flashState == CameraMetadata.FLASH_STATE_READY
        val waitedForPreflash = SystemClock.elapsedRealtime() - precaptureTriggerMs
        // Some HALs skip reporting PRECAPTURE. In that case, only start counting settled AE
        // results after the entry timeout; never treat SEARCHING/INACTIVE (or a missing state)
        // as completion. Require two distinct settled frames to avoid capturing on a transient.
        // A plain AE precapture has no flash handshake to wait for, so its results count at once.
        val mayCountSettledResult = !p.flashAe || precapturePhase == 2 ||
            (precapturePhase == 1 && waitedForPreflash >= PRECAPTURE_ENTER_TIMEOUT_MS)
        if (mayCountSettledResult && settled && flashReady && result != null &&
            result.frameNumber != lastAeResultFrameNumber) {
            settledAeResultCount++
            lastAeResultFrameNumber = result.frameNumber
        } else if (!settled || !flashReady || aeState == CameraMetadata.CONTROL_AE_STATE_PRECAPTURE) {
            settledAeResultCount = 0
        }
        val aeReady = !p.needAe || settledAeResultCount >= AE_SETTLED_RESULT_COUNT
        val lensReady = !p.needLens || lensState == CameraMetadata.LENS_STATE_STATIONARY
        val timedOut = SystemClock.elapsedRealtime() >= p.deadlineMs
        if (capturing) {
            val settleKey = "$precapturePhase/$aeState/$flashState/$settledAeResultCount/$aeReady/$flashReady/$timedOut"
            if (settleKey != lastSettleKey) {
                lastSettleKey = settleKey
                Log.d(
                    tag,
                    "settle phase=$precapturePhase ae=${aeStateName(aeState)} " +
                        "flash=${flashStateName(flashState)} aeFrames=$settledAeResultCount/$AE_SETTLED_RESULT_COUNT " +
                        "ready=$aeReady/$flashReady lens=$lensReady " +
                        "timedOut=$timedOut"
                )
            }
        }
        if (force || timedOut || (aeReady && lensReady && flashReady)) {
            if ((force || timedOut) && !(aeReady && lensReady && flashReady)) {
                Log.w(tag, "capture settling timed out; firing anyway (ae=$aeReady flash=$flashReady lens=$lensReady)")
            }
            fireCapture()
        }
    }

    private fun aeStateName(state: Int?): String = when (state) {
        null -> "?"
        CameraMetadata.CONTROL_AE_STATE_INACTIVE -> "INACTIVE"
        CameraMetadata.CONTROL_AE_STATE_SEARCHING -> "SEARCHING"
        CameraMetadata.CONTROL_AE_STATE_CONVERGED -> "CONVERGED"
        CameraMetadata.CONTROL_AE_STATE_LOCKED -> "LOCKED"
        CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED -> "FLASH_REQUIRED"
        CameraMetadata.CONTROL_AE_STATE_PRECAPTURE -> "PRECAPTURE"
        else -> state.toString()
    }

    private fun flashStateName(state: Int?): String = when (state) {
        null -> "?"
        CameraMetadata.FLASH_STATE_UNAVAILABLE -> "UNAVAILABLE"
        CameraMetadata.FLASH_STATE_CHARGING -> "CHARGING"
        CameraMetadata.FLASH_STATE_READY -> "READY"
        CameraMetadata.FLASH_STATE_FIRED -> "FIRED"
        CameraMetadata.FLASH_STATE_PARTIAL -> "PARTIAL"
        else -> state.toString()
    }

    private fun fireCapture() {
        val s = videoStyleStillState(state ?: return)
        val plan = currentPlan ?: return
        val d = device ?: return
        val r = reader ?: return
        val sess = session ?: return
        pending = null
        settleTimeout?.let { cameraHandler.removeCallbacks(it) }
        settleTimeout = null

        val orientation = jpegOrientation()
        val flashExposure = normalFlashStillExposure(s)
        val flashOn = s.flash && s.lens.flashAvailable
        val still = try {
            val spec = RequestBuilder.build(
                state = if (flashExposure != null) s.copy(exposure = flashExposure) else s,
                profile = s.lens,
                intent = CaptureIntent.STILL,
                outputAspect = plan.outputAspect,
                strategy = effectiveStrategy,
                previewClamp = false,
                focusAssist = false,
                fallbackTransform = lastTransform,
                exposureCompensation = exposureCompensationSteps,
                lateFlashBehavior = lateFlashBehavior,
                jpegQuality = STILL_JPEG_QUALITY,
                thumbnailSize = thumbnailSizeFor(s.lens, plan.outputAspect)
            )
            val builder = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            builder.addTarget(r.surface)
            RequestApplier.apply(builder, spec, orientation, aeRegion, afRegion)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            spec to builder
        } catch (t: Throwable) {
            Log.e(tag, "could not build still request", t)
            failCapture("Could not configure the capture: ${t.message}")
            return
        }
        val spec = still.first
        val builder = still.second

        stillSpec = spec
        stillLens = s.lens

        val timeoutMs = ExposureMath.estimatedCaptureTimeoutMs(spec.exposureNs ?: 0L)
        val watchdog = Runnable {
            captureWatchdog = null
            failCapture("Capture timed out after $timeoutMs ms")
        }
        captureWatchdog = watchdog
        cameraHandler.postDelayed(watchdog, timeoutMs)

        try {
            sess.capture(builder.build(), stillCallback, cameraHandler)
            Log.i(tag, "still capture: iso=${spec.iso} shutter=${spec.exposureNs} ae=${spec.aeMode} flashSingle=${spec.flashSingle} flashState=${flashStateName(latestReadout?.flashState)} flashManual=${flashExposure != null} strategy=$effectiveStrategy orientation=$orientation rotation=$deviceRotation")
        } catch (t: Throwable) {
            Log.e(tag, "capture failed", t)
            failCapture("Capture failed: ${t.message}")
        }
    }

    private fun failCapture(message: String) {
        if (!capturing) return
        Log.e(tag, message)
        finishCapture(null, message)
    }

    private fun finishCapture(uri: Uri?, error: String?) {
        if (!capturing) return
        removeCaptureRunnables()
        capturing = false
        pending = null
        precapturePhase = 0
        // Keep stillSpec/stillLens: the TotalCaptureResult may arrive after the JPEG callback.
        // They are cleared when the next capture starts or when the session is torn down.
        if (device != null && session != null) applyPreview()
        mainHandler.post { listener.onCaptureFinished(uri, error) }
    }

    private fun removeCaptureRunnables() {
        settleTimeout?.let { cameraHandler.removeCallbacks(it) }
        settleTimeout = null
        captureWatchdog?.let { cameraHandler.removeCallbacks(it) }
        captureWatchdog = null
    }

    // ---------------------------------------------------------------- callbacks

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            handleResult(result)
            if (session === this@CameraController.session && session !== previewReadySession) {
                previewReadySession = session
                val facing = state?.lens?.facing ?: return
                mainHandler.post { listener.onPreviewReady(facing) }
            }
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure
        ) {
            if (capturing) failCapture("Capture failed: ${failure.reason}")
        }
    }

    /**
     * Repeating-request callback while recording. The first frame after record starts is
     * verified exactly like a still capture, so the capability report proves the camera really
     * honored the "no processing" settings in video mode too.
     */
    private val recordCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            handleResult(result)
            if (!recording || recordVerified) return
            recordVerified = true
            val spec = recordSpec ?: return
            val lens = state?.lens ?: return
            val issues = ProcessingVerifier.verify(result, spec, lens)
            lastIssues = issues
            lastVerification = buildString {
                appendLine("Video recording:")
                appendLine(ProcessingVerifier.summary(result))
                if (issues.isEmpty()) {
                    appendLine("All requested settings were honored.")
                } else {
                    issues.forEach { appendLine("- $it") }
                }
            }
            mainHandler.post { listener.onVerification(issues, lastVerification) }
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure
        ) {
            if (!recording) return
            Log.e(tag, "recording capture failed: reason=${failure.reason}")
            // A failed capture means frames did not reach the encoder, so the pending MP4 is
            // unusable; drop it and tell the user instead of publishing a broken file.
            finishRecording(save = false, reconfigure = true, errorOverride = "Recording failed")
        }
    }

    private val stillCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            handleResult(result)
            val spec = stillSpec ?: return
            val lens = stillLens ?: state?.lens ?: return
            val issues = ProcessingVerifier.verify(result, spec, lens)
            lastIssues = issues
            lastVerification = buildString {
                appendLine(ProcessingVerifier.summary(result))
                if (issues.isEmpty()) {
                    appendLine("All requested settings were honored.")
                } else {
                    issues.forEach { appendLine("- $it") }
                }
            }
            mainHandler.post { listener.onVerification(issues, lastVerification) }
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure
        ) {
            failCapture("Still capture failed: ${failure.reason}")
        }

        override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
            if (capturing) failCapture("Capture aborted")
        }
    }

    private fun handleResult(result: TotalCaptureResult) {
        if (capturing) maybeFireCapture(false, result)
        // Remember what AE chose, so a video-style still can reuse that exact exposure.
        val aeIso = result.get(CaptureResult.SENSOR_SENSITIVITY)
        val aeShutter = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        if (aeIso != null && aeShutter != null) {
            liveIso = aeIso
            liveShutterNs = aeShutter
        }
        // The readout is only needed when the manual controls ask for a starting value, so
        // sample it a few times a second instead of allocating on every preview frame.
        val now = SystemClock.elapsedRealtime()
        if (now - lastReadoutMs <= READOUT_INTERVAL_MS) return
        lastReadoutMs = now
        val readout = ProcessingVerifier.read(result)
        latestReadout = readout
        val s = state
        if (s != null && s.wb is WhiteBalanceMode.Auto) {
            readout.transform?.let { lastTransform = it }
            readout.gains?.let { lastAutoGains = it }
        }
    }

    private val jpegListener = ImageReader.OnImageAvailableListener { source ->
        val image = try {
            source.acquireNextImage()
        } catch (t: Throwable) {
            Log.w(tag, "acquireNextImage failed", t)
            null
        } ?: return@OnImageAvailableListener

        try {
            if (!capturing) return@OnImageAvailableListener
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            Log.i(tag, "JPEG received: ${bytes.size} bytes")
            // The watchdog guards the camera -> JPEG path only. A MediaStore write can take
            // seconds on a busy device, so stop it here; saving reports its own result.
            captureWatchdog?.let { cameraHandler.removeCallbacks(it) }
            captureWatchdog = null
            // Front camera output is already mirrored on this device. Keep those pixels
            // for the Mirrored option; flip them only when the user chooses As previewed.
            val flipSaved = !mirrorFrontPhotos && (state?.lens?.facing ?: facing) == Facing.FRONT
            // The gallery button can show the camera's embedded thumbnail right now, long
            // before the full-size file has been written.
            runCatching {
                com.necra.purecam.ui.previewFromJpeg(bytes, image.width, image.height, flipSaved)
            }.getOrNull()?.let { thumb -> mainHandler.post { listener.onPhotoPreview(thumb) } }
            saver.save(
                bytes, image.width, image.height,
                overlays = overlayStyles,
                mirrorHorizontal = flipSaved
            )
            // The camera is ready for the next frame now. Let the independent save worker finish
            // writing the original JPEG without keeping the shutter UI in a busy state.
            finishCapture(null, null)
        } catch (t: Throwable) {
            Log.e(tag, "JPEG read failed", t)
            failCapture("Could not read JPEG: ${t.message}")
        } finally {
            image.close()
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun notifyCatalog() {
        val currentFacing = state?.lens?.facing ?: facing
        mainHandler.post { listener.onCatalog(catalog, currentFacing) }
    }

    private fun notifyState() {
        val s = state ?: return
        val plan = currentPlan
        val strategy = effectiveStrategy
        mainHandler.post { listener.onStateChanged(s, plan, strategy) }
    }

    private fun notifyError(message: String) {
        Log.e(tag, message)
        mainHandler.post { listener.onCameraError(message) }
    }

    // Exposed for the manual-control UI: entering manual should start from live auto values.
    fun suggestedManualExposure(): ExposureMode.Manual {
        val r = latestReadout
        val profile = state?.lens
        val iso = r?.iso ?: profile?.isoRange?.clamp(100) ?: 100
        val shutter = r?.shutterNs ?: profile?.exposureRange?.clamp(16_666_667L) ?: 16_666_667L
        return ExposureMode.Manual(iso, shutter)
    }

    fun suggestedManualFocus(): FocusMode.Manual {
        val r = latestReadout
        val profile = state?.lens
        return FocusMode.Manual((r?.focusDiopters ?: 0f).coerceIn(0f, profile?.minFocusDiopters ?: 0f))
    }

    fun suggestedKelvin(): Int {
        val profile = state?.lens ?: return 5200
        return ManualWhiteBalance.estimateKelvinFromGains(profile, lastAutoGains)
    }

    fun lastAutoTransform(): FloatArray? = lastTransform?.copyOf()
}
