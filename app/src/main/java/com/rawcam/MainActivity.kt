package com.rawcam

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.TextureView
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.rawcam.core.Aspect
import com.rawcam.core.AspectPlan
import com.rawcam.core.CameraState
import com.rawcam.core.ExposureMath
import com.rawcam.core.ExposureMode
import com.rawcam.core.Facing
import com.rawcam.core.FocusMode
import com.rawcam.core.LensMode
import com.rawcam.core.LensProfile
import com.rawcam.core.ManualFocus
import com.rawcam.core.ManualWhiteBalance
import com.rawcam.core.OrientationMath
import com.rawcam.core.WhiteBalanceMode
import kotlin.math.roundToInt

class MainActivity : android.app.Activity(), CameraController.Listener {

    private enum class Panel { NONE, EXP, FOCUS, WB }
    private enum class TimerMode(val seconds: Int) { OFF(0), THREE(3), TEN(10) }

    private lateinit var root: FrameLayout
    private lateinit var previewContainer: FrameLayout
    private lateinit var viewfinder: TextureView
    private lateinit var focusRing: View
    private lateinit var zoomIndicator: TextView
    private lateinit var captureIndicator: TextView
    private lateinit var topBar: LinearLayout
    private lateinit var bottomContainer: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var manualScrim: View
    private lateinit var manualSheet: LinearLayout
    private lateinit var manualClose: TextView
    private lateinit var manualMode: TextView
    private lateinit var photoMode: TextView
    private lateinit var infoMode: TextView
    private lateinit var flashButton: Button
    private lateinit var timerButton: Button
    private lateinit var rawButton: Button
    private lateinit var flipButton: ImageButton
    private lateinit var aspectButton: Button
    private lateinit var gridButton: Button
    private lateinit var gridOverlay: GridOverlay
    private lateinit var thumbFrame: FrameLayout
    private lateinit var thumbImage: ImageView
    private lateinit var thumbPlaceholder: ImageView
    private lateinit var readout: TextView
    private lateinit var lensScroll: HorizontalScrollView
    private lateinit var lensRow: LinearLayout
    private lateinit var expChip: Button
    private lateinit var focusChip: Button
    private lateinit var wbChip: Button
    private lateinit var expPanel: LinearLayout
    private lateinit var focusPanel: LinearLayout
    private lateinit var wbPanel: LinearLayout
    private lateinit var expAuto: Button
    private lateinit var expManual: Button
    private lateinit var focusAuto: Button
    private lateinit var focusManual: Button
    private lateinit var isoLabel: TextView
    private lateinit var shutterLabel: TextView
    private lateinit var previewNote: TextView
    private lateinit var focusLabel: TextView
    private lateinit var wbLabel: TextView
    private lateinit var isoSeek: SeekBar
    private lateinit var shutterSeek: SeekBar
    private lateinit var focusSeek: SeekBar
    private lateinit var wbSeek: SeekBar
    private lateinit var wbPresets: LinearLayout
    private lateinit var shutterButton: Button
    private lateinit var flashOverlay: View
    private lateinit var countdown: TextView
    private lateinit var toastView: TextView
    private lateinit var permissionPanel: LinearLayout
    private lateinit var grantButton: Button

    private lateinit var controller: CameraController
    private lateinit var orientationHelper: OrientationHelper

    private var state: CameraState? = null
    private var plan: AspectPlan? = null
    private var aspects: List<Aspect> = emptyList()
    private var strategy: LensMode = LensMode.LOGICAL
    private var readoutData: LiveReadout? = null

    private var isoStops: List<Int> = emptyList()
    private var shutterStops: List<Long> = emptyList()
    private var stopsLensId: String? = null
    private var wbLensId: String? = null

    private var selectedPanel = Panel.NONE
    private var updatingUi = false
    private var cameraStarted = false
    private var permissionAsked = false
    private var permanentlyDenied = false

    private val handler = Handler(Looper.getMainLooper())
    private val lensChips = HashMap<String, Button>()
    private val wbChips = HashMap<String, Button>()
    private var focusRingHide: Runnable? = null
    private var zoomHide: Runnable? = null
    private var errorDialog: AlertDialog? = null

    private var currentZoom = 1f
    private var lastLensId: String? = null
    private var introPlayed = false
    private var timerMode = TimerMode.OFF
    private var countingDown = false
    private var countdownRunnable: Runnable? = null
    private var toastHide: Runnable? = null
    private var lastPhotoUri: Uri? = null
    private var lastLayoutWidth = 0
    private var lastLayoutHeight = 0
    private var lastLayoutAspect = 0f
    private var lastLayoutMirrored = false

    private lateinit var gestureDetector: GestureDetector
    private lateinit var scaleDetector: ScaleGestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = getColor(R.color.ui_surface)
        val windowAttrs = window.attributes
        windowAttrs.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        window.attributes = windowAttrs

        setContentView(R.layout.activity_main)
        bindViews()
        setupInsets()
        setupClicks()
        setupSeekBars()
        setupGestures()

        controller = CameraController(this, viewfinder, this)
        orientationHelper = OrientationHelper(this) { degrees ->
            controller.setDeviceRotation(degrees)
            rotateIcons(degrees)
        }

        viewfinder.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                controller.onSurfaceAvailable()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                lastLayoutWidth = 0
                applyPreviewLayout()
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                controller.onSurfaceDestroyed()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }

        previewContainer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyPreviewLayout()
        }

        readout.setOnLongClickListener {
            showCapabilityReport()
            true
        }

        root.post { playIntro() }

        if (hasCameraPermission()) {
            beginCamera()
        } else {
            permissionPanel.visibility = View.VISIBLE
            updateGrantButton()
            requestCameraPermission()
        }
    }

    override fun onResume() {
        super.onResume()
        orientationHelper.enable()
        if (hasCameraPermission()) {
            permissionPanel.visibility = View.GONE
            if (!cameraStarted) beginCamera()
        }
    }

    override fun onPause() {
        orientationHelper.disable()
        if (countingDown) cancelCountdown()
        if (cameraStarted) {
            controller.stop()
            cameraStarted = false
        }
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (selectedPanel != Panel.NONE) {
            closeManualSheet()
        } else if (errorDialog?.isShowing == true) {
            errorDialog?.dismiss()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        if (::controller.isInitialized) controller.shutdown()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ setup

    private fun bindViews() {
        root = findViewById(R.id.root)
        previewContainer = findViewById(R.id.previewContainer)
        viewfinder = findViewById(R.id.viewfinder)
        focusRing = findViewById(R.id.focusRing)
        zoomIndicator = findViewById(R.id.zoomIndicator)
        captureIndicator = findViewById(R.id.captureIndicator)
        topBar = findViewById(R.id.topBar)
        bottomContainer = findViewById(R.id.bottomContainer)
        bottomBar = findViewById(R.id.bottomBar)
        manualScrim = findViewById(R.id.manualScrim)
        manualSheet = findViewById(R.id.manualSheet)
        manualClose = findViewById(R.id.manualClose)
        manualMode = findViewById(R.id.manualMode)
        photoMode = findViewById(R.id.photoMode)
        infoMode = findViewById(R.id.infoMode)
        flashButton = findViewById(R.id.flashButton)
        timerButton = findViewById(R.id.timerButton)
        rawButton = findViewById(R.id.rawButton)
        flipButton = findViewById(R.id.flipButton)
        aspectButton = findViewById(R.id.aspectButton)
        gridButton = findViewById(R.id.gridButton)
        gridOverlay = findViewById(R.id.gridOverlay)
        thumbFrame = findViewById(R.id.thumbFrame)
        thumbImage = findViewById(R.id.thumbImage)
        thumbPlaceholder = findViewById(R.id.thumbPlaceholder)
        readout = findViewById(R.id.readout)
        lensScroll = findViewById(R.id.lensScroll)
        lensRow = findViewById(R.id.lensRow)
        expChip = findViewById(R.id.expChip)
        focusChip = findViewById(R.id.focusChip)
        wbChip = findViewById(R.id.wbChip)
        expPanel = findViewById(R.id.expPanel)
        focusPanel = findViewById(R.id.focusPanel)
        wbPanel = findViewById(R.id.wbPanel)
        expAuto = findViewById(R.id.expAuto)
        expManual = findViewById(R.id.expManual)
        focusAuto = findViewById(R.id.focusAuto)
        focusManual = findViewById(R.id.focusManual)
        isoLabel = findViewById(R.id.isoLabel)
        shutterLabel = findViewById(R.id.shutterLabel)
        previewNote = findViewById(R.id.previewNote)
        focusLabel = findViewById(R.id.focusLabel)
        wbLabel = findViewById(R.id.wbLabel)
        isoSeek = findViewById(R.id.isoSeek)
        shutterSeek = findViewById(R.id.shutterSeek)
        focusSeek = findViewById(R.id.focusSeek)
        wbSeek = findViewById(R.id.wbSeek)
        wbPresets = findViewById(R.id.wbPresets)
        shutterButton = findViewById(R.id.shutterButton)
        flashOverlay = findViewById(R.id.flashOverlay)
        countdown = findViewById(R.id.countdown)
        toastView = findViewById(R.id.toastView)
        permissionPanel = findViewById(R.id.permissionPanel)
        grantButton = findViewById(R.id.grantButton)

        viewfinder.isClickable = true
        focusRing.alpha = 0f
        setTopButtonActive(rawButton, true)
        updateTimerButton()
        updateAspectButton()
        restoreGrid()
        restoreThumbnail()
    }

    private fun setupInsets() {
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            topBar.setPadding(dp(4), bars.top + dp(2), dp(4), dp(2))
            bottomBar.setPadding(dp(34), dp(4), dp(34), bars.bottom + dp(8))
            manualSheet.setPadding(dp(22), dp(12), dp(22), bars.bottom + dp(20))
            val tlp = toastView.layoutParams as FrameLayout.LayoutParams
            val toastMargin = bars.bottom + dp(246)
            if (tlp.bottomMargin != toastMargin) {
                tlp.bottomMargin = toastMargin
                toastView.layoutParams = tlp
            }
            insets
        }
    }

    private fun playIntro() {
        if (introPlayed || isFinishing || isDestroyed) return
        introPlayed = true
        topBar.alpha = 0f
        topBar.translationY = -dp(12).toFloat()
        previewContainer.alpha = 0f
        previewContainer.scaleX = 0.97f
        previewContainer.scaleY = 0.97f
        bottomContainer.alpha = 0f
        bottomContainer.translationY = dp(36).toFloat()
        topBar.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(60)
            .setDuration(320)
            .setInterpolator(DecelerateInterpolator())
            .start()
        previewContainer.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(40)
            .setDuration(420)
            .setInterpolator(DecelerateInterpolator())
            .start()
        bottomContainer.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(140)
            .setDuration(420)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun setupClicks() {
        flashButton.setOnClickListener { cycleFlash() }
        timerButton.setOnClickListener { cycleTimer() }
        rawButton.setOnClickListener { showToast(getString(R.string.raw_fixed)) }
        flipButton.setOnClickListener { controller.flipFacing() }
        aspectButton.setOnClickListener { showAspectMenu() }
        shutterButton.setOnClickListener { onShutterPressed() }
        expChip.setOnClickListener { selectPanel(Panel.EXP) }
        focusChip.setOnClickListener { selectPanel(Panel.FOCUS) }
        wbChip.setOnClickListener { selectPanel(Panel.WB) }

        manualMode.setOnClickListener { openManualSheet(Panel.NONE) }
        photoMode.setOnClickListener { closeManualSheet() }
        infoMode.setOnClickListener {
            if (selectedPanel != Panel.NONE) closeManualSheet()
            showCapabilityReport()
        }
        manualClose.setOnClickListener { closeManualSheet() }
        manualScrim.setOnClickListener { closeManualSheet() }
        gridButton.setOnClickListener { toggleGrid() }
        thumbFrame.setOnClickListener { openLastPhoto() }

        expAuto.setOnClickListener {
            selectedPanel = Panel.EXP
            controller.setExposure(ExposureMode.Auto)
            refreshPanelVisibility()
        }
        expManual.setOnClickListener {
            selectedPanel = Panel.EXP
            controller.setExposure(controller.suggestedManualExposure())
            refreshPanelVisibility()
        }
        focusAuto.setOnClickListener {
            selectedPanel = Panel.FOCUS
            controller.setFocus(FocusMode.Auto)
            refreshPanelVisibility()
        }
        focusManual.setOnClickListener {
            selectedPanel = Panel.FOCUS
            controller.setFocus(controller.suggestedManualFocus())
            refreshPanelVisibility()
        }
        grantButton.setOnClickListener {
            if (hasCameraPermission()) {
                beginCamera()
            } else if (permanentlyDenied) {
                openAppSettings()
            } else {
                requestCameraPermission()
            }
        }
    }

    private fun cycleFlash() {
        val s = state ?: return
        if (!s.lens.flashAvailable) {
            showToast(getString(R.string.no_flash))
            return
        }
        controller.setFlash(!s.flash)
        showToast(getString(if (s.flash) R.string.flash_off else R.string.flash_on))
    }

    private fun cycleTimer() {
        if (countingDown) cancelCountdown()
        timerMode = when (timerMode) {
            TimerMode.OFF -> TimerMode.THREE
            TimerMode.THREE -> TimerMode.TEN
            TimerMode.TEN -> TimerMode.OFF
        }
        updateTimerButton()
        val label = when (timerMode) {
            TimerMode.OFF -> getString(R.string.off)
            TimerMode.THREE -> getString(R.string.timer_3s)
            TimerMode.TEN -> getString(R.string.timer_10s)
        }
        showToast("${getString(R.string.timer)} $label")
    }

    private fun updateTimerButton() {
        timerButton.text = when (timerMode) {
            TimerMode.OFF -> getString(R.string.off)
            TimerMode.THREE -> getString(R.string.timer_3s)
            TimerMode.TEN -> getString(R.string.timer_10s)
        }
        setTopButtonActive(timerButton, timerMode != TimerMode.OFF)
    }

    private fun onShutterPressed() {
        if (countingDown) return
        if (timerMode == TimerMode.OFF) {
            doCapture()
        } else {
            startCountdown(timerMode.seconds)
        }
    }

    private fun doCapture() {
        playFlash()
        shutterButton.animate().cancel()
        shutterButton.animate().scaleX(0.92f).scaleY(0.92f)
            .setDuration(90)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                shutterButton.animate().scaleX(1f).scaleY(1f)
                    .setDuration(190)
                    .setInterpolator(OvershootInterpolator(1.4f))
                    .start()
            }.start()
        controller.capture()
    }

    private fun startCountdown(seconds: Int) {
        if (countingDown) return
        countingDown = true
        countdown.animate().cancel()
        countdown.visibility = View.VISIBLE
        countdown.alpha = 1f
        val runnable = object : Runnable {
            var remaining = seconds
            override fun run() {
                if (!countingDown) return
                if (remaining <= 0) {
                    countdown.visibility = View.GONE
                    countingDown = false
                    countdownRunnable = null
                    doCapture()
                    return
                }
                countdown.text = remaining.toString()
                countdown.scaleX = 1.12f
                countdown.scaleY = 1.12f
                countdown.animate().scaleX(1f).scaleY(1f)
                    .setDuration(280)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
                remaining--
                handler.postDelayed(this, 1000)
            }
        }
        countdownRunnable = runnable
        handler.post(runnable)
    }

    private fun cancelCountdown() {
        countdownRunnable?.let { handler.removeCallbacks(it) }
        countdownRunnable = null
        countingDown = false
        countdown.visibility = View.GONE
    }

    private fun playFlash() {
        flashOverlay.animate().cancel()
        flashOverlay.alpha = 0.55f
        flashOverlay.animate().alpha(0f).setDuration(300).start()
    }

    private fun setTopButtonActive(button: Button, active: Boolean) {
        val color = getColor(if (active) R.color.ui_accent else R.color.ui_text_dim)
        button.setTextColor(color)
        button.compoundDrawableTintList = ColorStateList.valueOf(color)
    }

    private fun showToast(message: String) {
        if (isFinishing || isDestroyed) return
        toastView.animate().cancel()
        toastView.text = message
        toastView.visibility = View.VISIBLE
        if (toastView.alpha < 0.1f) toastView.translationY = dp(8).toFloat()
        toastView.animate().alpha(1f).translationY(0f).setDuration(180).start()
        toastHide?.let { handler.removeCallbacks(it) }
        val hide = Runnable {
            toastView.animate().alpha(0f).translationY(dp(8).toFloat()).setDuration(200)
                .withEndAction { toastView.visibility = View.GONE }.start()
        }
        toastHide = hide
        handler.postDelayed(hide, 1800)
    }

    private fun setupSeekBars() {
        isoSeek.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || updatingUi) return
                val s = state ?: return
                val manual = s.exposure as? ExposureMode.Manual ?: return
                if (progress !in isoStops.indices) return
                controller.setExposure(ExposureMode.Manual(isoStops[progress], manual.shutterNs))
            }
        })
        shutterSeek.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || updatingUi) return
                val s = state ?: return
                val manual = s.exposure as? ExposureMode.Manual ?: return
                if (progress !in shutterStops.indices) return
                controller.setExposure(ExposureMode.Manual(manual.iso, shutterStops[progress]))
            }
        })
        focusSeek.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                val s = state ?: return
                if (s.focus is FocusMode.Manual) controller.setFocusAssist(true)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                controller.setFocusAssist(false)
            }

            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || updatingUi) return
                val s = state ?: return
                if (s.focus is FocusMode.Manual) {
                    controller.setFocus(FocusMode.Manual(ManualFocus.dioptersAt(progress, s.lens.minFocusDiopters)))
                }
            }
        })
        wbSeek.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || updatingUi) return
                val s = state ?: return
                if (s.wb is WhiteBalanceMode.Kelvin) {
                    val kelvin = ManualWhiteBalance.MIN_KELVIN + progress * ManualWhiteBalance.KELVIN_STEP
                    controller.setWhiteBalance(WhiteBalanceMode.Kelvin(kelvin))
                }
            }
        })
    }

    private fun setupGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (scaleDetector.isInProgress) return false
                val s = state ?: return false
                if (selectedPanel != Panel.NONE) {
                    selectedPanel = Panel.NONE
                    refreshPanelVisibility()
                }
                if (s.focus is FocusMode.Manual) return true
                controller.tapToFocus(e.x, e.y)
                showFocusRing(e.x, e.y)
                return true
            }
        })
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val s = state ?: return false
                currentZoom = (currentZoom * detector.scaleFactor)
                    .coerceIn(1f, s.lens.maxZoom.coerceAtLeast(1f))
                controller.setZoom(currentZoom)
                showZoomIndicator(currentZoom)
                return true
            }
        })
        viewfinder.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            scaleDetector.onTouchEvent(event)
            true
        }
    }

    // ------------------------------------------------------------------ permission

    private fun beginCamera() {
        if (cameraStarted) return
        cameraStarted = true
        permissionPanel.visibility = View.GONE
        controller.start()
    }

    private fun hasCameraPermission(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() {
        permissionAsked = true
        requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
    }

    private fun updateGrantButton() {
        grantButton.setText(if (permanentlyDenied) R.string.open_settings else R.string.grant_permission)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA) return
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (granted) {
            permanentlyDenied = false
            permissionPanel.visibility = View.GONE
            beginCamera()
        } else {
            permanentlyDenied = !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
            updateGrantButton()
        }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
        )
    }

    // ------------------------------------------------------------------ controller callbacks

    override fun onCatalog(catalog: LensCatalog, facing: Facing) {
        buildLensChips(catalog, facing)
        val hasBack = catalog.lenses(Facing.BACK).isNotEmpty()
        val hasFront = catalog.lenses(Facing.FRONT).isNotEmpty()
        flipButton.visibility = if (hasBack && hasFront) View.VISIBLE else View.GONE
    }

    override fun onStateChanged(
        state: CameraState,
        plan: AspectPlan?,
        aspects: List<Aspect>,
        strategy: LensMode
    ) {
        this.state = state
        this.plan = plan
        this.aspects = aspects
        this.strategy = strategy
        currentZoom = state.zoom
        updateUiFromState()
    }

    override fun onReadout(readout: LiveReadout) {
        readoutData = readout
        updateReadout()
        updateControlLabelsOnly()
    }

    override fun onCaptureStarted(exposureNs: Long?) {
        if (exposureNs != null && exposureNs >= 300_000_000L) {
            captureIndicator.text = "Capturing ${ExposureMath.formatShutter(exposureNs)}\u2026"
            captureIndicator.visibility = View.VISIBLE
        }
    }

    override fun onCaptureFinished(uri: Uri?, error: String?) {
        captureIndicator.visibility = View.GONE
        if (error != null) {
            // Session teardown (lens/aspect change, pause) cancels an in-flight capture. That is
            // expected and must not interrupt the user with an error dialog.
            if (!error.startsWith("Capture cancelled")) showErrorDialog(error)
        } else if (uri != null) {
            loadThumbnail(uri)
            showToast(getString(R.string.saved_to_dcim))
        }
    }

    override fun onCameraError(message: String) {
        captureIndicator.visibility = View.GONE
        showErrorDialog(message)
    }

    override fun onLensRemoved(removed: LensProfile, replacement: LensProfile?) {
        val text = if (replacement != null) {
            "${removed.label} lens is not usable on this device; switched to ${replacement.label}"
        } else {
            "${removed.label} lens is not usable on this device"
        }
        showToast(text)
    }

    override fun onVerification(issues: List<String>, summary: String) {
        // The detail is available from the capability report (Info or long-press the readout).
        if (issues.isNotEmpty()) {
            val lensIssue = issues.firstOrNull { it.startsWith("Lens:") }
            if (lensIssue != null) showToast(lensIssue)
        }
    }

    // ------------------------------------------------------------------ UI updates

    private fun updateUiFromState() {
        val s = state ?: return
        refreshStops(s)
        updateLensSelection()
        updateAspectButton()
        updateFlashButton(s)
        updateModeChips(s)
        updatePanelControls(s)
        updateReadout()
        applyPreviewLayout()
    }

    private fun refreshStops(s: CameraState) {
        if (stopsLensId == s.lens.id) return
        stopsLensId = s.lens.id
        isoStops = s.lens.isoRange?.let { ExposureMath.isoStops(it) }.orEmpty()
        shutterStops = s.lens.exposureRange?.let { ExposureMath.shutterStops(it) }.orEmpty()
        updatingUi = true
        isoSeek.max = (isoStops.size - 1).coerceAtLeast(0)
        shutterSeek.max = (shutterStops.size - 1).coerceAtLeast(0)
        updatingUi = false
    }

    private fun updateFlashButton(s: CameraState) {
        flashButton.visibility = View.VISIBLE
        flashButton.isEnabled = s.lens.flashAvailable
        flashButton.alpha = if (s.lens.flashAvailable) 1f else 0.35f
        flashButton.text = getString(if (s.flash) R.string.on else R.string.off)
        flashButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (s.flash) R.drawable.ic_flash_on else R.drawable.ic_flash_off,
            0, 0, 0
        )
        setTopButtonActive(flashButton, s.flash && s.lens.flashAvailable)
    }

    private fun updateAspectButton() {
        aspectButton.text = portraitAspectLabel(state?.aspect ?: Aspect.R4_3)
        setTopButtonActive(aspectButton, false)
    }

    private fun portraitAspectLabel(aspect: Aspect): String = when {
        aspect.isFull -> "Full"
        aspect == Aspect.R4_3 -> "3:4"
        aspect == Aspect.R3_2 -> "2:3"
        aspect == Aspect.R16_9 -> "9:16"
        aspect == Aspect.R1_1 -> "1:1"
        else -> aspect.label
    }

    private fun updateModeChips(s: CameraState) {
        val expOk = s.lens.supportsManualExposure
        val focusOk = s.lens.supportsManualFocus
        val wbOk = s.lens.awbPresets.isNotEmpty() || s.lens.supportsKelvin
        expChip.visibility = if (expOk) View.VISIBLE else View.GONE
        focusChip.visibility = if (focusOk) View.VISIBLE else View.GONE
        wbChip.visibility = if (wbOk) View.VISIBLE else View.GONE
        val manualAvailable = expOk || focusOk || wbOk
        manualMode.visibility = if (manualAvailable) View.VISIBLE else View.GONE

        if (selectedPanel != Panel.NONE && !panelSupported(selectedPanel, s)) {
            selectedPanel = Panel.NONE
        }
        if (!manualAvailable) selectedPanel = Panel.NONE

        setChipSelected(expChip, selectedPanel == Panel.EXP)
        setChipSelected(focusChip, selectedPanel == Panel.FOCUS)
        setChipSelected(wbChip, selectedPanel == Panel.WB)
    }

    private fun panelSupported(panel: Panel, s: CameraState): Boolean = when (panel) {
        Panel.NONE -> true
        Panel.EXP -> s.lens.supportsManualExposure
        Panel.FOCUS -> s.lens.supportsManualFocus
        Panel.WB -> s.lens.awbPresets.isNotEmpty() || s.lens.supportsKelvin
    }

    private fun firstSupportedPanel(s: CameraState): Panel = when {
        s.lens.supportsManualExposure -> Panel.EXP
        s.lens.supportsManualFocus -> Panel.FOCUS
        s.lens.awbPresets.isNotEmpty() || s.lens.supportsKelvin -> Panel.WB
        else -> Panel.NONE
    }

    private fun updatePanelControls(s: CameraState) {
        updatingUi = true
        // ---- exposure
        val manual = s.exposure as? ExposureMode.Manual
        setChipSelected(expAuto, manual == null)
        setChipSelected(expManual, manual != null)
        isoSeek.isEnabled = manual != null && isoStops.isNotEmpty()
        shutterSeek.isEnabled = manual != null && shutterStops.isNotEmpty()
        isoSeek.alpha = if (manual != null) 1f else 0.42f
        shutterSeek.alpha = if (manual != null) 1f else 0.42f
        if (manual != null) {
            val isoIndex = ExposureMath.nearestIsoIndex(isoStops, manual.iso)
            if (isoSeek.progress != isoIndex) isoSeek.progress = isoIndex
            val shutterIndex = ExposureMath.nearestIndex(shutterStops, manual.shutterNs)
            if (shutterSeek.progress != shutterIndex) shutterSeek.progress = shutterIndex
            val digital = s.lens.maxAnalogIso != null && manual.iso > s.lens.maxAnalogIso
            isoLabel.text = "ISO ${manual.iso}${if (digital) "  (digital gain)" else ""}"
            shutterLabel.text = "Shutter ${ExposureMath.formatShutter(manual.shutterNs)}"
            previewNote.visibility =
                if (ExposureMath.isPreviewApproximate(ExposureValuesFor(manual))) View.VISIBLE else View.GONE
        } else {
            val autoIso = readoutData?.iso
            val autoShutter = readoutData?.shutterNs
            isoLabel.text = if (autoIso != null) "ISO auto \u00b7 $autoIso" else "ISO auto"
            shutterLabel.text = if (autoShutter != null) {
                "Shutter auto \u00b7 ${ExposureMath.formatShutter(autoShutter)}"
            } else {
                "Shutter auto"
            }
            previewNote.visibility = View.GONE
        }

        // ---- focus
        val manualFocus = s.focus as? FocusMode.Manual
        setChipSelected(focusAuto, manualFocus == null)
        setChipSelected(focusManual, manualFocus != null)
        focusSeek.isEnabled = manualFocus != null
        focusSeek.alpha = if (manualFocus != null) 1f else 0.42f
        if (manualFocus != null) {
            val progress = ManualFocus.progressFor(manualFocus.diopters, s.lens.minFocusDiopters)
            if (focusSeek.progress != progress) focusSeek.progress = progress
            focusLabel.text = "Focus ${ManualFocus.label(manualFocus.diopters, s.lens.focusCalibrated)}"
        } else {
            focusLabel.text = focusLabelFor(s, readoutData?.focusDiopters)
        }

        // ---- white balance
        buildWbChipsIfNeeded(s.lens)
        updateWbSelection(s.wb)
        when (val wb = s.wb) {
            is WhiteBalanceMode.Auto -> {
                wbLabel.text = "Auto white balance"
                wbSeek.visibility = View.GONE
            }
            is WhiteBalanceMode.Preset -> {
                wbLabel.text = "${wb.preset.label} \u00b7 ${wb.preset.cct} K"
                wbSeek.visibility = View.GONE
            }
            is WhiteBalanceMode.Kelvin -> {
                wbLabel.text = "${wb.kelvin} K"
                wbSeek.visibility = View.VISIBLE
                wbSeek.alpha = 1f
                val progress =
                    (wb.kelvin - ManualWhiteBalance.MIN_KELVIN) / ManualWhiteBalance.KELVIN_STEP
                if (wbSeek.progress != progress) wbSeek.progress = progress
            }
        }
        updatingUi = false

        refreshPanelVisibility()
    }

    private fun updateControlLabelsOnly() {
        val s = state ?: return
        if (s.exposure is ExposureMode.Auto) {
            val iso = readoutData?.iso
            val shutter = readoutData?.shutterNs
            if (iso != null) isoLabel.text = "ISO auto \u00b7 $iso"
            if (shutter != null) shutterLabel.text = "Shutter auto \u00b7 ${ExposureMath.formatShutter(shutter)}"
        }
        if (s.focus is FocusMode.Auto) {
            focusLabel.text = focusLabelFor(s, readoutData?.focusDiopters)
        }
        updateReadout()
    }

    private fun updateReadout() {
        val s = state ?: return
        val r = readoutData
        val iso = when (val e = s.exposure) {
            is ExposureMode.Manual -> e.iso
            is ExposureMode.Auto -> r?.iso
        }
        val shutter = when (val e = s.exposure) {
            is ExposureMode.Manual -> e.shutterNs
            is ExposureMode.Auto -> r?.shutterNs
        }
        val focus = when (val f = s.focus) {
            is FocusMode.Manual -> f.diopters
            is FocusMode.Auto -> r?.focusDiopters
        }
        val focusText = focusLabelFor(s, focus)
        val wb = ManualWhiteBalance.describe(s.wb)
        readout.text = buildString {
            append(if (iso != null) "ISO $iso" else "ISO --")
            append("  \u00b7  ")
            append(if (shutter != null) ExposureMath.formatShutter(shutter) else "1/--")
            append("  \u00b7  ")
            append(focusText)
            append("  \u00b7  ")
            append(wb)
        }
    }

    /**
     * Focus is not a meaningful number on fixed-focus and AF-less lenses, so label the actual
     * capability instead of showing a misleading "relative" distance.
     */
    private fun focusLabelFor(s: CameraState, diopters: Float?): String {
        val manual = s.focus is FocusMode.Manual
        if (!manual && !s.lens.afContinuousSupported && !s.lens.afAutoSupported) {
            return if (s.lens.minFocusDiopters <= 0f) "Fixed focus" else "No AF"
        }
        if (diopters == null) return "AF"
        return if (manual) {
            ManualFocus.label(diopters, s.lens.focusCalibrated)
        } else {
            "AF \u00b7 ${ManualFocus.label(diopters, s.lens.focusCalibrated)}"
        }
    }

    private fun selectPanel(panel: Panel) {
        val s = state ?: return
        if (!panelSupported(panel, s)) {
            selectedPanel = Panel.NONE
        } else {
            selectedPanel = panel
        }
        refreshPanelVisibility()
    }

    private fun openManualSheet(requested: Panel) {
        val s = state ?: return
        val panel = when {
            requested != Panel.NONE && panelSupported(requested, s) -> requested
            else -> firstSupportedPanel(s)
        }
        if (panel == Panel.NONE) {
            showToast(getString(R.string.no_manual_controls))
            return
        }
        selectedPanel = panel
        refreshPanelVisibility()
    }

    private fun closeManualSheet() {
        selectedPanel = Panel.NONE
        refreshPanelVisibility()
    }

    private fun refreshPanelVisibility() {
        val s = state
        val open = selectedPanel != Panel.NONE
        val wasOpen = manualSheet.visibility == View.VISIBLE
        manualSheet.animate().cancel()
        manualScrim.animate().cancel()

        if (open) {
            manualScrim.visibility = View.VISIBLE
            manualSheet.visibility = View.VISIBLE
            if (!wasOpen) {
                manualScrim.alpha = 0f
                manualSheet.alpha = 0.8f
                manualSheet.translationY = dp(140).toFloat()
                bottomContainer.animate().cancel()
                bottomContainer.animate()
                    .translationY((bottomContainer.height + dp(48)).toFloat())
                    .setDuration(260)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
                manualScrim.animate().alpha(1f).setDuration(180)
                    .setInterpolator(DecelerateInterpolator()).start()
                manualSheet.animate().alpha(1f).translationY(0f).setDuration(320)
                    .setInterpolator(OvershootInterpolator(0.85f)).start()
            } else {
                manualScrim.alpha = 1f
                manualSheet.alpha = 1f
                manualSheet.translationY = 0f
            }
        } else if (wasOpen) {
            val travel = manualSheet.height.toFloat().coerceAtLeast(dp(180).toFloat())
            manualSheet.animate().alpha(0f).translationY(travel).setDuration(190)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    if (selectedPanel == Panel.NONE) {
                        manualSheet.visibility = View.GONE
                        manualSheet.alpha = 1f
                        manualSheet.translationY = 0f
                    }
                }.start()
            manualScrim.animate().alpha(0f).setDuration(160)
                .withEndAction {
                    if (selectedPanel == Panel.NONE) {
                        manualScrim.visibility = View.GONE
                        manualScrim.alpha = 1f
                    }
                }.start()
            bottomContainer.animate().cancel()
            bottomContainer.animate().translationY(0f).setDuration(340)
                .setInterpolator(OvershootInterpolator(0.7f)).start()
        } else {
            manualScrim.visibility = View.GONE
            manualScrim.alpha = 1f
            manualSheet.visibility = View.GONE
        }

        applyModeTabState(open)
        expPanel.visibility = if (selectedPanel == Panel.EXP) View.VISIBLE else View.GONE
        focusPanel.visibility = if (selectedPanel == Panel.FOCUS) View.VISIBLE else View.GONE
        wbPanel.visibility = if (selectedPanel == Panel.WB) View.VISIBLE else View.GONE
        if (s != null) {
            setChipSelected(expChip, selectedPanel == Panel.EXP)
            setChipSelected(focusChip, selectedPanel == Panel.FOCUS)
            setChipSelected(wbChip, selectedPanel == Panel.WB)
        }
    }

    private fun applyModeTabState(manualOpen: Boolean) {
        setModeTabSelected(photoMode, !manualOpen)
        setModeTabSelected(manualMode, manualOpen)
        setModeTabSelected(infoMode, false)
    }

    private fun setModeTabSelected(tab: TextView, selected: Boolean) {
        val changed = tab.isSelected != selected
        tab.isSelected = selected
        tab.setBackgroundResource(
            if (selected) R.drawable.bg_mode_selected else R.drawable.bg_mode_item
        )
        tab.setTextColor(
            getColor(if (selected) R.color.ui_accent else R.color.ui_text_dim)
        )
        if (selected && changed) {
            tab.animate().cancel()
            tab.scaleX = 0.94f
            tab.scaleY = 0.94f
            tab.animate().scaleX(1f).scaleY(1f).setDuration(240)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun buildLensChips(catalog: LensCatalog, facing: Facing) {
        lensRow.removeAllViews()
        lensChips.clear()
        val unsupported = controller.unsupportedLenses()
        val lenses = catalog.lenses(facing).filterNot { unsupported.contains(it.id) }
        lensScroll.visibility = if (lenses.size > 1) View.VISIBLE else View.GONE
        lenses.forEach { lens ->
            val chip = makeLensChip(lens)
            chip.setOnClickListener { controller.setLens(lens) }
            lensRow.addView(chip)
            lensChips[lens.id] = chip
        }
        updateLensSelection()
    }

    private fun updateLensSelection() {
        val selected = state?.lens?.id
        val changed = selected != null && selected != lastLensId
        lastLensId = selected
        lensChips.forEach { (id, chip) ->
            val isSelected = id == selected
            setLensSelected(chip, isSelected)
            if (isSelected && changed) {
                chip.animate().cancel()
                chip.scaleX = 1.09f
                chip.scaleY = 1.09f
                chip.animate().scaleX(1f).scaleY(1f).setDuration(240)
                    .setInterpolator(OvershootInterpolator(2.2f)).start()
            }
        }
    }

    private fun makeLensChip(lens: LensProfile): Button {
        val chip = Button(this)
        chip.text = shortLensLabel(lens.label)
        chip.isAllCaps = false
        chip.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        chip.setTextColor(getColor(R.color.ui_text_dim))
        chip.textSize = 12f
        chip.minWidth = dp(34)
        chip.minimumWidth = dp(34)
        chip.minHeight = 0
        chip.minimumHeight = 0
        chip.setPadding(dp(8), 0, dp(8), 0)
        chip.background = getDrawable(R.drawable.bg_lens_item)
        chip.stateListAnimator = null
        chip.elevation = 0f
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(34)
        )
        lp.marginEnd = dp(2)
        chip.layoutParams = lp
        return chip
    }

    private fun shortLensLabel(label: String): String {
        val trimmed = label.trim()
        val bare = trimmed.removeSuffix("\u00d7").removeSuffix("x").removeSuffix("X").trim()
        val value = bare.toFloatOrNull()
        return when {
            value == null -> trimmed
            value < 1f -> bare.removePrefix("0")
            value == 1f -> "1\u00d7"
            else -> bare
        }
    }

    private fun setLensSelected(chip: Button, selected: Boolean) {
        chip.setBackgroundResource(
            if (selected) R.drawable.bg_lens_item_selected else R.drawable.bg_lens_item
        )
        chip.setTextColor(
            getColor(if (selected) R.color.ui_accent else R.color.ui_text_dim)
        )
        chip.textSize = if (selected) 13f else 12f
        val minWidth = dp(if (selected) 46 else 34)
        chip.minWidth = minWidth
        chip.minimumWidth = minWidth
    }

    private fun buildWbChipsIfNeeded(lens: LensProfile) {
        if (wbLensId == lens.id) return
        wbLensId = lens.id
        wbPresets.removeAllViews()
        wbChips.clear()

        val auto = makeChip(getString(R.string.auto), small = true)
        auto.setOnClickListener { controller.setWhiteBalance(WhiteBalanceMode.Auto) }
        wbPresets.addView(auto)
        wbChips["auto"] = auto

        lens.awbPresets.sortedBy { it.ordinal }.forEach { preset ->
            val chip = makeChip(preset.label, small = true)
            chip.setOnClickListener { controller.setWhiteBalance(WhiteBalanceMode.Preset(preset)) }
            wbPresets.addView(chip)
            wbChips["preset:${preset.name}"] = chip
        }
        if (lens.supportsKelvin) {
            val kelvin = makeChip("K", small = true)
            kelvin.setOnClickListener {
                val s = state ?: return@setOnClickListener
                if (s.wb !is WhiteBalanceMode.Kelvin) {
                    controller.setWhiteBalance(WhiteBalanceMode.Kelvin(controller.suggestedKelvin()))
                }
            }
            wbPresets.addView(kelvin)
            wbChips["kelvin"] = kelvin
        }
    }

    private fun updateWbSelection(mode: WhiteBalanceMode) {
        wbChips.forEach { (key, chip) ->
            val selected = when (mode) {
                is WhiteBalanceMode.Auto -> key == "auto"
                is WhiteBalanceMode.Preset -> key == "preset:${mode.preset.name}"
                is WhiteBalanceMode.Kelvin -> key == "kelvin"
            }
            setChipSelected(chip, selected)
        }
    }

    private fun makeChip(text: String, small: Boolean = false): Button {
        val chip = Button(this)
        chip.text = text
        chip.isAllCaps = false
        chip.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        chip.setTextColor(getColor(R.color.ui_text_dim))
        chip.textSize = if (small) 12f else 13f
        chip.minWidth = 0
        chip.minimumWidth = 0
        chip.minHeight = 0
        chip.minimumHeight = 0
        chip.setPadding(dp(14), 0, dp(14), 0)
        chip.background = getDrawable(R.drawable.bg_chip)
        chip.stateListAnimator = null
        chip.elevation = 0f
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(36)
        )
        lp.marginEnd = dp(6)
        chip.layoutParams = lp
        return chip
    }

    private fun setChipSelected(chip: Button, selected: Boolean) {
        chip.setBackgroundResource(if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip)
        chip.setTextColor(
            getColor(if (selected) R.color.black else R.color.ui_text_dim)
        )
    }

    private fun showAspectMenu() {
        if (aspects.isEmpty()) return
        val popup = PopupMenu(this, aspectButton)
        aspects.forEach { aspect ->
            popup.menu.add(0, aspect.ordinal, aspect.ordinal, portraitAspectLabel(aspect))
        }
        popup.setOnMenuItemClickListener { item ->
            val aspect = Aspect.entries.firstOrNull { it.ordinal == item.itemId }
                ?: return@setOnMenuItemClickListener false
            controller.setAspect(aspect)
            true
        }
        popup.show()
    }

    private fun toggleGrid() {
        val on = gridOverlay.visibility != View.VISIBLE
        applyGridState(on)
        gridButton.animate().cancel()
        gridButton.scaleX = 1.04f
        gridButton.scaleY = 1.04f
        gridButton.animate().scaleX(1f).scaleY(1f).setDuration(180)
            .setInterpolator(DecelerateInterpolator()).start()
        prefs().edit().putBoolean(KEY_GRID, on).apply()
    }

    private fun restoreGrid() {
        applyGridState(prefs().getBoolean(KEY_GRID, false))
    }

    private fun applyGridState(on: Boolean) {
        gridOverlay.visibility = if (on) View.VISIBLE else View.GONE
        gridButton.text = getString(if (on) R.string.grid_on else R.string.off)
        setTopButtonActive(gridButton, on)
    }

    // ------------------------------------------------------------------ thumbnail

    private fun restoreThumbnail() {
        val uri = prefs().getString(KEY_LAST_PHOTO, null)?.let(Uri::parse) ?: return
        loadThumbnail(uri)
    }

    private fun loadThumbnail(uri: Uri) {
        lastPhotoUri = uri
        prefs().edit().putString(KEY_LAST_PHOTO, uri.toString()).apply()
        Thread {
            val bitmap = runCatching { decodeThumbnail(uri) }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (bitmap != null) {
                    thumbImage.setImageBitmap(bitmap)
                    thumbPlaceholder.visibility = View.GONE
                } else {
                    clearThumbnail()
                }
            }
        }.start()
    }

    private fun decodeThumbnail(uri: Uri): android.graphics.Bitmap? {
        val target = dp(96)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target &&
            bounds.outHeight / (sample * 2) >= target
        ) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    private fun clearThumbnail() {
        lastPhotoUri = null
        thumbImage.setImageDrawable(null)
        thumbPlaceholder.visibility = View.VISIBLE
        prefs().edit().remove(KEY_LAST_PHOTO).apply()
    }

    private fun openLastPhoto() {
        val uri = lastPhotoUri
        if (uri == null) {
            showToast(getString(R.string.no_photos))
            return
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "image/jpeg")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            showToast(getString(R.string.open_photo_failed))
        }
    }

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    // ------------------------------------------------------------------ preview layout

    private fun applyPreviewLayout() {
        val frame = previewContainer as AspectFrameLayout
        val targetAspect = plan?.displayAspect ?: (3f / 4f)
        if (frame.aspectRatio != targetAspect) {
            frame.aspectRatio = targetAspect
        }

        val containerW = previewContainer.width
        val containerH = previewContainer.height
        if (containerW <= 0 || containerH <= 0) {
            previewContainer.post { applyPreviewLayout() }
            return
        }
        val p = plan
        if (p == null) {
            // No session yet: keep the plain rounded frame and an untransformed preview.
            if (lastLayoutAspect != 0f) {
                viewfinder.setTransform(Matrix())
                lastLayoutAspect = 0f
                lastLayoutMirrored = false
            }
            lastLayoutWidth = containerW
            lastLayoutHeight = containerH
            return
        }
        val s = state
        val mirrored = s?.lens?.facing == Facing.FRONT
        if (containerW == lastLayoutWidth && containerH == lastLayoutHeight &&
            p.displayAspect == lastLayoutAspect && mirrored == lastLayoutMirrored
        ) {
            return
        }
        lastLayoutWidth = containerW
        lastLayoutHeight = containerH
        lastLayoutAspect = p.displayAspect
        lastLayoutMirrored = mirrored

        // The rounded frame already uses the upright preview ratio. The texture only needs a
        // centre crop for any small preview-size mismatch, plus the front-camera mirror.
        val viewAspect = containerW.toFloat() / containerH.toFloat()
        val contentAspect = p.displayAspect
        val matrix = Matrix()
        if (contentAspect >= viewAspect) {
            val scale = contentAspect / viewAspect
            matrix.setScale(scale, 1f, containerW / 2f, containerH / 2f)
        } else {
            val scale = viewAspect / contentAspect
            matrix.setScale(1f, scale, containerW / 2f, containerH / 2f)
        }
        if (mirrored) {
            matrix.postScale(-1f, 1f, containerW / 2f, containerH / 2f)
        }
        viewfinder.setTransform(matrix)
    }

    private fun showFocusRing(x: Float, y: Float) {
        val size = dp(76)
        focusRing.translationX = viewfinder.left + x - size / 2f
        focusRing.translationY = viewfinder.top + y - size / 2f
        focusRing.visibility = View.VISIBLE
        focusRing.animate().cancel()
        focusRing.alpha = 1f
        focusRing.scaleX = 0.75f
        focusRing.scaleY = 0.75f
        focusRing.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
        focusRingHide?.let { handler.removeCallbacks(it) }
        val hide = Runnable {
            focusRing.animate().alpha(0f).setDuration(260).withEndAction {
                focusRing.visibility = View.GONE
            }.start()
        }
        focusRingHide = hide
        handler.postDelayed(hide, 850)
    }

    private fun showZoomIndicator(zoom: Float) {
        zoomIndicator.text = String.format("%.1f\u00d7", zoom)
        zoomIndicator.visibility = View.VISIBLE
        zoomHide?.let { handler.removeCallbacks(it) }
        val hide = Runnable { zoomIndicator.visibility = View.GONE }
        zoomHide = hide
        handler.postDelayed(hide, 1200)
    }

    private fun rotateIcons(degrees: Int) {
        flipButton.rotation = OrientationMath.iconRotation(degrees)
    }

    // ------------------------------------------------------------------ dialogs

    private fun showErrorDialog(message: String) {
        if (isFinishing || isDestroyed) return
        if (errorDialog?.isShowing == true) errorDialog?.dismiss()
        errorDialog = AlertDialog.Builder(this)
            .setTitle(R.string.camera_error)
            .setMessage(message)
            .setPositiveButton(R.string.retry) { _, _ -> controller.retry() }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showCapabilityReport() {
        if (isFinishing || isDestroyed) return
        val text = TextView(this).apply {
            setText(controller.capabilityReport())
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val scroll = ScrollView(this).apply { addView(text) }
        AlertDialog.Builder(this)
            .setTitle(R.string.capability_report)
            .setView(scroll)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    // ------------------------------------------------------------------ helpers

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private open class SimpleSeekListener : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = Unit
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }

    private fun ExposureValuesFor(exposure: ExposureMode.Manual): com.rawcam.core.ExposureValues =
        com.rawcam.core.ExposureValues(exposure.iso, exposure.shutterNs)

    companion object {
        private const val REQUEST_CAMERA = 41
        private const val PREFS = "rawcam_ui"
        private const val KEY_GRID = "grid"
        private const val KEY_LAST_PHOTO = "last_photo_uri"
    }
}
