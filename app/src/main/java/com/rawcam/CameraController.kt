package com.rawcam

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
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
import android.view.TextureView
import com.rawcam.core.Aspect
import com.rawcam.core.AspectMath
import com.rawcam.core.AspectPlan
import com.rawcam.core.CameraState
import com.rawcam.core.CaptureIntent
import com.rawcam.core.ExposureMath
import com.rawcam.core.ExposureMode
import com.rawcam.core.Facing
import com.rawcam.core.FocusMath
import com.rawcam.core.FocusMode
import com.rawcam.core.LensMode
import com.rawcam.core.LensProfile
import com.rawcam.core.ManualWhiteBalance
import com.rawcam.core.OrientationMath
import com.rawcam.core.RangeI
import com.rawcam.core.RequestBuilder
import com.rawcam.core.RequestSpec
import com.rawcam.core.VideoConfig
import com.rawcam.core.VideoMath
import com.rawcam.core.WhiteBalanceMode
import java.util.concurrent.Executor

class CameraController(
    private val context: Context,
    private val textureView: TextureView,
    private val listener: Listener
) {
    interface Listener {
        fun onCatalog(catalog: LensCatalog, facing: Facing)
        fun onStateChanged(
            state: CameraState,
            plan: AspectPlan?,
            aspects: List<Aspect>,
            strategy: LensMode
        )
        fun onReadout(readout: LiveReadout)
        fun onCaptureStarted(exposureNs: Long?)
        fun onCaptureFinished(uri: Uri?, error: String?)
        fun onRecordingStarted(config: VideoConfig, withAudio: Boolean)
        fun onRecordingFinished(uri: Uri?, error: String?)
        fun onCameraError(message: String)
        fun onLensRemoved(removed: LensProfile, replacement: LensProfile?)
        fun onVerification(issues: List<String>, summary: String)
    }

    private data class PendingCapture(
        val needAe: Boolean,
        val needAf: Boolean,
        val needLens: Boolean,
        /** Only results at or after this time may satisfy the readiness checks. */
        val startedMs: Long,
        val deadlineMs: Long
    )

    private val tag = "RawCam/Controller"
    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val prefs = context.getSharedPreferences("rawcam_probe", Context.MODE_PRIVATE)
    private val handlerThread = HandlerThread("rawcam-camera").apply { start() }
    private val cameraHandler = Handler(handlerThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val saver = PhotoSaver(
        context,
        onSaved = { uri, _ -> cameraHandler.post { finishCapture(uri, null) } },
        onError = { message -> cameraHandler.post { finishCapture(null, message) } }
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
    private var started = false

    private var surfaceTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null
    private var reader: ImageReader? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null

    private var generation = 0
    private var sessionToken = 0

    private var deviceRotation = 0
    private var forceLogicalLensId: String? = null
    private val unsupportedLensIds = HashSet<String>()

    private var aeRegion: com.rawcam.core.RectI? = null
    private var afRegion: com.rawcam.core.RectI? = null
    private var focusAssist = false

    private var capturing = false
    private var pending: PendingCapture? = null
    private var stillSpec: RequestSpec? = null
    private var stillLens: LensProfile? = null
    private var captureWatchdog: Runnable? = null
    private var settleTimeout: Runnable? = null

    @Volatile
    private var videoMode = false
    private var photoAspect: Aspect = Aspect.R4_3

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
    private var lastReadoutPostMs = 0L
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
            facing = if (catalog.lenses(Facing.BACK).isNotEmpty()) Facing.BACK else Facing.FRONT
            val lens = catalog.main(facing)
            if (lens == null) {
                notifyError("No usable camera lens found")
                return@post
            }
            state = CameraState.defaults(lens)
                .copy(
                    aspect = if (videoMode) {
                        photoAspect = chooseAspect(lens, Aspect.R4_3)
                        chooseAspect(lens, Aspect.R16_9)
                    } else {
                        chooseAspect(lens, Aspect.R4_3)
                    }
                )
                .clamped()
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
            handlerThread.quitSafely()
        }
        saver.shutdown()
    }

    fun onSurfaceAvailable() {
        cameraHandler.post {
            surfaceTexture = textureView.surfaceTexture
            if (started) openCameraIfReady()
        }
    }

    fun onSurfaceDestroyed() {
        cameraHandler.post {
            closeCamera()
            surfaceTexture = null
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

    fun setLens(lens: LensProfile) {
        cameraHandler.post {
            if (recording) return@post
            val s = state ?: return@post
            if (s.lens.id == lens.id) return@post
            if (unsupportedLensIds.contains(lens.id)) return@post
            pendingRecording = null
            val oldCamera = s.lens.cameraId
            state = s.withLens(lens)
                .copy(aspect = chooseAspect(lens, s.aspect))
                .clamped()
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

    fun setAspect(aspect: Aspect) {
        cameraHandler.post {
            if (recording || videoMode) return@post
            val s = state ?: return@post
            if (s.aspect == aspect) return@post
            if (!CameraCaps.aspectsFor(s.lens).contains(aspect)) return@post
            state = s.copy(aspect = aspect).clamped()
            aeRegion = null
            afRegion = null
            closeSessionAndReader()
            if (device != null) createSession() else notifyState()
        }
    }

    fun flipFacing() {
        cameraHandler.post {
            if (recording) return@post
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
            state = base.withLens(lens)
                .copy(aspect = chooseAspect(lens, base.aspect))
                .clamped()
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
            applyPreview()
        }
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
            val width = textureView.width
            val height = textureView.height
            if (width <= 0 || height <= 0) return@post
            val useZoomRatio = effectiveStrategy == LensMode.LOGICAL && lens.zoomRatioRange != null
            val cropArray = if (effectiveStrategy == LensMode.LOGICAL) lens.logicalActiveArray else lens.activeArray
            val crop = AspectMath.cropRect(cropArray, plan.outputAspect, if (useZoomRatio) 1f else s.zoom)
            val point = FocusMath.mapTap(
                viewX = if (s.lens.facing == Facing.FRONT) width - viewX else viewX,
                viewY = viewY,
                viewWidth = width,
                viewHeight = height,
                contentAspect = plan.displayAspect,
                sensorOrientation = lens.sensorOrientation,
                crop = crop,
                activeArray = cropArray
            )
            val rect = FocusMath.meteringRect(point, crop, cropArray)
            afRegion = rect
            aeRegion = if (s.exposure is ExposureMode.Auto) rect else null

            val builder = buildPreviewRequest() ?: return@post
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            if (s.exposure is ExposureMode.Auto) {
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

    /**
     * Switches between stills and video. Video forces a 16:9 framing so the preview, the sensor
     * crop and the encoder buffer all show the same thing; the photo aspect is restored on exit.
     */
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
            state = if (enabled) {
                photoAspect = s.aspect
                s.copy(aspect = chooseAspect(s.lens, Aspect.R16_9)).clamped()
            } else {
                s.copy(aspect = chooseAspect(s.lens, photoAspect)).clamped()
            }
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
            if (device == null || session == null || surfaceTexture == null) {
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
        val st = surfaceTexture ?: return
        val d = device ?: return
        val lens = state?.lens ?: return

        val plan = AspectMath.planForOutput(lens, config.size)
        currentPlan = plan

        // Recording needs exactly two outputs: preview and encoder. The JPEG reader goes away so
        // the session stays within every device's guaranteed stream combination.
        closeSessionAndReader()
        st.setDefaultBufferSize(plan.previewSize.width, plan.previewSize.height)
        val preview = Surface(st)
        previewSurface = preview

        val rec = VideoRecorder(context)
        var usedAudio = withAudio
        val surface = try {
            rec.open(config, videoOrientationHint(), withAudio)
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
                rec.open(config, videoOrientationHint(), false)
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
        val qualities = VideoMath.availableQualities(lens.videoSizes)
        val quality = requested?.quality?.takeIf { qualities.contains(it) }
            ?: VideoMath.defaultQuality(qualities)
            ?: return null
        val size = VideoMath.sizeFor(quality, lens.videoSizes) ?: return null
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
        return OrientationMath.jpegOrientation(lens.sensorOrientation, deviceRotation, false)
    }

    // ---------------------------------------------------------------- camera open / session

    private fun openCameraIfReady() {
        if (!started) return
        val st = surfaceTexture ?: return
        if (st.isReleased) return
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
        val st = surfaceTexture ?: return
        val s0 = state ?: return

        val aspects = CameraCaps.aspectsFor(s0.lens)
        val aspect = if (aspects.contains(s0.aspect)) s0.aspect else chooseAspect(s0.lens, aspects.firstOrNull() ?: s0.aspect)
        if (aspect != s0.aspect) state = s0.copy(aspect = aspect)
        val s = state ?: return

        // Video keeps the preview on the encoder's aspect even before recording starts, so the
        // framing does not jump when the user presses record.
        val photoPlan = AspectMath.plan(s.lens, aspect, screenAspect())
        val plan = if (videoMode) {
            resolveVideoConfig(s.lens)?.let { AspectMath.planForOutput(s.lens, it.size) }
                ?: photoPlan
        } else {
            photoPlan
        }
        currentPlan = plan
        st.setDefaultBufferSize(plan.previewSize.width, plan.previewSize.height)

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

        val pSurface = Surface(st)
        previewSurface = pSurface

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
        state = base.withLens(replacement)
            .copy(aspect = chooseAspect(replacement, base.aspect))
            .clamped()
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
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        session = null
        runCatching { reader?.setOnImageAvailableListener(null, null) }
        runCatching { reader?.close() }
        reader = null
        runCatching { previewSurface?.release() }
        previewSurface = null
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

    private fun buildPreviewRequest(): CaptureRequest.Builder? = try {
        buildPreviewRequestUnchecked()
    } catch (t: Throwable) {
        // A single bad request key must never take down the camera thread; fall back to the
        // last working repeating request until the state changes again.
        Log.e(tag, "could not build preview request", t)
        null
    }

    private fun buildPreviewRequestUnchecked(): CaptureRequest.Builder? {
        val s = state ?: return null
        val plan = currentPlan ?: return null
        val d = device ?: return null
        val surface = previewSurface ?: return null
        val spec = RequestBuilder.build(
            state = s,
            profile = s.lens,
            intent = CaptureIntent.PREVIEW,
            outputAspect = plan.outputAspect,
            strategy = effectiveStrategy,
            previewClamp = true,
            focusAssist = focusAssist,
            fallbackTransform = lastTransform,
            videoFps = if (videoMode) resolveVideoConfig(s.lens)?.fps ?: VideoMath.DEFAULT_FPS else null,
            videoMode = videoMode
        )
        val builder = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        builder.addTarget(surface)
        RequestApplier.apply(builder, spec, jpegOrientation(), aeRegion, afRegion)
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
                videoFpsRange = range?.let { RangeI(it.first, it.last) },
                videoMode = true
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
        return OrientationMath.jpegOrientation(
            lens.sensorOrientation,
            deviceRotation,
            lens.facing == Facing.FRONT
        )
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

        val needAe = s.exposure is ExposureMode.Auto && s.flash && s.lens.alwaysFlashSupported
        val needAf = s.focus is FocusMode.Auto && s.lens.afContinuousSupported
        val needLens = s.focus is FocusMode.Manual
        val now = SystemClock.elapsedRealtime()
        val deadline = now + when {
            needAe -> 1_600L
            needAf || needLens -> 1_100L
            else -> 0L
        }
        pending = PendingCapture(needAe, needAf, needLens, now, deadline)

        if (needAe) {
            val builder = buildPreviewRequest()
            if (builder != null) {
                builder.set(
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START
                )
                try {
                    session?.setRepeatingRequest(builder.build(), previewCallback, cameraHandler)
                } catch (t: Throwable) {
                    Log.w(tag, "precapture request failed", t)
                }
            }
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

    private fun maybeFireCapture(force: Boolean) {
        val p = pending ?: return
        if (!capturing) return
        // A result from before this capture started says nothing about the current exposure.
        val r = latestReadout?.takeIf { it.timestampMs >= p.startedMs }
        val aeReady = !p.needAe || r?.aeState == CameraMetadata.CONTROL_AE_STATE_CONVERGED ||
            r?.aeState == CameraMetadata.CONTROL_AE_STATE_LOCKED
        val afReady = !p.needAf || isAfReady(r?.afState)
        val lensReady = !p.needLens || r?.lensState == CameraMetadata.LENS_STATE_STATIONARY
        val timedOut = SystemClock.elapsedRealtime() >= p.deadlineMs
        if (force || timedOut || (aeReady && afReady && lensReady)) {
            if (force && !(aeReady && afReady && lensReady)) {
                Log.w(tag, "capture settling timed out; firing anyway (ae=$aeReady af=$afReady lens=$lensReady)")
            }
            fireCapture()
        }
    }

    private fun isAfReady(afState: Int?): Boolean = when (afState) {
        CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED,
        CameraMetadata.CONTROL_AF_STATE_PASSIVE_FOCUSED,
        CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED,
        CameraMetadata.CONTROL_AF_STATE_PASSIVE_UNFOCUSED -> true
        else -> false
    }

    private fun fireCapture() {
        val s = state ?: return
        val plan = currentPlan ?: return
        val d = device ?: return
        val r = reader ?: return
        val sess = session ?: return
        pending = null
        settleTimeout?.let { cameraHandler.removeCallbacks(it) }
        settleTimeout = null

        val still = try {
            val spec = RequestBuilder.build(
                state = s,
                profile = s.lens,
                intent = CaptureIntent.STILL,
                outputAspect = plan.outputAspect,
                strategy = effectiveStrategy,
                previewClamp = false,
                focusAssist = false,
                fallbackTransform = lastTransform
            )
            val builder = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            builder.addTarget(r.surface)
            RequestApplier.apply(builder, spec, jpegOrientation(), aeRegion, afRegion)
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
            Log.i(tag, "still capture: iso=${spec.iso} shutter=${spec.exposureNs} strategy=$effectiveStrategy orientation=${jpegOrientation()}")
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

    private val stillCallback = object : CameraCaptureSession.CaptureCallback() {        override fun onCaptureCompleted(
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
        val readout = ProcessingVerifier.read(result)
        latestReadout = readout
        val s = state
        if (s != null && s.wb is WhiteBalanceMode.Auto) {
            readout.transform?.let { lastTransform = it.copyOf() }
            readout.gains?.let { lastAutoGains = it.copyOf() }
        }
        if (capturing) maybeFireCapture(false)
        val now = SystemClock.elapsedRealtime()
        if (now - lastReadoutPostMs > 200) {
            lastReadoutPostMs = now
            mainHandler.post { listener.onReadout(readout) }
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
            saver.save(bytes)
        } catch (t: Throwable) {
            Log.e(tag, "JPEG read failed", t)
            failCapture("Could not read JPEG: ${t.message}")
        } finally {
            image.close()
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun chooseAspect(lens: LensProfile, requested: Aspect): Aspect {
        val aspects = CameraCaps.aspectsFor(lens)
        return when {
            aspects.contains(requested) -> requested
            aspects.isNotEmpty() -> aspects.first()
            else -> Aspect.R4_3
        }
    }

    private fun screenAspect(): Float {
        val metrics = context.resources.displayMetrics
        if (metrics.heightPixels <= 0) return 9f / 20f
        return metrics.widthPixels.toFloat() / metrics.heightPixels.toFloat()
    }

    private fun notifyCatalog() {
        val currentFacing = state?.lens?.facing ?: facing
        mainHandler.post { listener.onCatalog(catalog, currentFacing) }
    }

    private fun notifyState() {
        val s = state ?: return
        val plan = currentPlan
        val aspects = CameraCaps.aspectsFor(s.lens)
        val strategy = effectiveStrategy
        mainHandler.post { listener.onStateChanged(s, plan, aspects, strategy) }
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
