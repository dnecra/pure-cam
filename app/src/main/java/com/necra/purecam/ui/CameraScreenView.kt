package com.necra.purecam.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.ScaleGestureDetector
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.necra.purecam.R
import com.necra.purecam.core.AspectMath
import com.necra.purecam.core.Facing
import com.necra.purecam.core.ManualWhiteBalance
import com.necra.purecam.core.OrientationMath
import com.necra.purecam.core.SaveCrop
import com.necra.purecam.core.SizeI
import com.necra.purecam.core.WhiteBalanceMode
import com.necra.purecam.presentation.CameraSignal
import com.necra.purecam.presentation.CameraUiState
import com.necra.purecam.presentation.CameraViewModel
import com.necra.purecam.presentation.CaptureMode
import com.necra.purecam.presentation.TimerMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Camera screen.
 *
 * The composition is a single column: a 56 dp top bar, the rounded viewfinder card, then the lens
 * row, shutter row and mode bar. Every section shares the same 16 dp page margin and 8 dp gaps,
 * and every interactive control is at least 48 dp. Controls never overlap the preview, so the
 * framed image stays unobstructed.
 */
@SuppressLint("ClickableViewAccessibility") // Touch listeners drive gestures and animation, not clicks.
class CameraScreenView(
    context: Context,
    private val viewModel: CameraViewModel,
    private val scope: CoroutineScope,
    private val requestAudio: () -> Unit
) : FrameLayout(context) {

    private val palette = CameraPalette(context)
    private val preview = PreviewTexture(context)
    private val grid = GridView(context, palette)
    private val focusRing = FocusRingView(context, palette)
    private val card = AspectFrame(context, palette)
    private val column = LinearLayout(context)
    private val previewBox = FrameLayout(context)
    private val captureFlash: View
    private val countdownLabel: TextView
    private val recordingPill = RecordingPill(context, palette)
    private val lensButtons = LinkedHashMap<String, MaterialButton>()
    private val uiHandler = Handler(Looper.getMainLooper())

    private lateinit var summaryButton: MaterialButton
    private lateinit var flashButton: MaterialButton
    private lateinit var timerButton: MaterialButton
    private lateinit var gridButton: MaterialButton
    private lateinit var modeSwitch: ModeSwitchView
    private lateinit var whiteBalanceButton: MaterialButton
    private lateinit var flipButton: MaterialButton
    private lateinit var shutter: ShutterButton
    private lateinit var gallery: FrameLayout
    private lateinit var galleryImage: ImageView
    private lateinit var galleryPlaceholder: ImageView
    private lateinit var lensScroll: HorizontalScrollView
    private lateinit var lensRow: LinearLayout
    private lateinit var permissionOverlay: FrameLayout
    private lateinit var permissionPanel: LinearLayout
    private lateinit var permissionButton: MaterialButton

    private var latest: CameraUiState? = null
    private var loadedUri: Uri? = null
    private var recordingStartedAt = 0L
    private var recordingTickerRunning = false
    private var permissionPermanentlyDenied = false

    private val dialogs = CameraDialogs(context, palette, viewModel) { latest }

    init {
        setBackgroundColor(Color.BLACK)
        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                viewModel.onPreviewSurfaceAvailable()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                preview.applyTransform()
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                viewModel.onPreviewSurfaceDestroyed()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }

        captureFlash = View(context).apply {
            setBackgroundColor(Color.WHITE)
            alpha = 0f
            isClickable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        countdownLabel = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 64f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setShadowLayer(dp(8).toFloat(), 0f, 0f, Color.BLACK)
            gravity = Gravity.CENTER
            visibility = GONE
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        buildColumn()
        buildPermissionOverlay()
        applyInsets()

        preview.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            tapDetector.onTouchEvent(event)
            true
        }
        viewModel.attachPreview(preview)
        scope.launch { viewModel.uiState.collectLatest(::render) }
        scope.launch {
            viewModel.signals.collectLatest { signal ->
                when (signal) {
                    is CameraSignal.Message -> Toast.makeText(context, signal.text, Toast.LENGTH_SHORT).show()
                    CameraSignal.CaptureFlash -> flashScreen()
                    CameraSignal.RequestAudioPermission -> requestAudio()
                }
            }
        }
    }

    // ------------------------------------------------------------------ layout

    private fun buildColumn() {
        column.orientation = LinearLayout.VERTICAL
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val topBar = buildTopBar()
        column.addView(topBar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(CameraSize.TOP_BAR)))

        previewBox.addView(
            card,
            FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER)
        )
        card.addView(preview, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        card.addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        card.addView(focusRing, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        card.addView(
            countdownLabel,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )
        card.addView(
            recordingPill,
            LayoutParams(LayoutParams.WRAP_CONTENT, dp(CameraSize.RECORDING_PILL), Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                .apply { topMargin = dp(CameraSpace.M) }
        )
        card.addView(captureFlash, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        column.addView(
            previewBox,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(CameraSpace.S)
            }
        )

        lensScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            isFillViewport = true
            visibility = GONE
            setBackgroundColor(Color.TRANSPARENT)
            lensRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            addView(
                lensRow,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
            )
        }
        column.addView(
            lensScroll,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(CameraSize.LENS_ROW)).apply {
                topMargin = dp(CameraSpace.XS)
                bottomMargin = dp(CameraSpace.XS)
            }
        )

        column.addView(
            buildShutterRow(),
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(CameraSize.SHUTTER_ROW)).apply {
                topMargin = dp(CameraSpace.S)
            }
        )
        column.addView(
            buildModeBar(),
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(CameraSize.BOTTOM_BAR)).apply {
                topMargin = dp(CameraSpace.S)
            }
        )
    }

    private fun buildTopBar(): FrameLayout {
        val description = { id: Int -> context.getString(id) }

        flashButton = cameraToolbarButton(context, palette, R.drawable.ic_flash_off, description(R.string.flash)) {
            viewModel.toggleFlash()
        }
        timerButton = cameraToolbarButton(context, palette, R.drawable.ic_timer, description(R.string.timer)) {
            viewModel.cycleTimer()
        }
        summaryButton = cameraChip(
            context, palette,
            text = description(R.string.summary_unknown),
            description = description(R.string.capture_settings),
            minWidthDp = 0,
            textSizeSp = 13f
        ) { dialogs.showSettings() }.apply {
            maxWidth = dp(176)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        gridButton = cameraToolbarButton(context, palette, R.drawable.ic_grid, description(R.string.grid)) {
            viewModel.toggleGrid()
        }

        val left = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(flashButton, LinearLayout.LayoutParams(dp(CameraSize.TOUCH), dp(CameraSize.TOUCH)))
            addView(
                timerButton,
                LinearLayout.LayoutParams(dp(CameraSize.TOUCH), dp(CameraSize.TOUCH)).apply {
                    leftMargin = dp(CameraSpace.XS)
                }
            )
        }
        return FrameLayout(context).apply {
            background = context.pill(
                palette.withAlpha(palette.surfaceContainerHigh, 0.92f),
                CameraShape.BAR,
                1,
                palette.withAlpha(palette.outlineVariant, 0.6f)
            )
            setPadding(dp(CameraSpace.XS), 0, dp(CameraSpace.XS), 0)
            addView(
                left,
                FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT, Gravity.START or Gravity.CENTER_VERTICAL)
            )
            addView(
                summaryButton,
                FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(40), Gravity.CENTER)
            )
            addView(
                gridButton,
                FrameLayout.LayoutParams(dp(CameraSize.TOUCH), dp(CameraSize.TOUCH), Gravity.END or Gravity.CENTER_VERTICAL)
            )
        }
    }

    private fun buildShutterRow(): LinearLayout {
        shutter = ShutterButton(context, palette).apply {
            contentDescription = context.getString(R.string.shutter)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                viewModel.onShutter()
            }
        }
        gallery = buildGallery()
        flipButton = cameraIconButton(context, palette, R.drawable.ic_flip, context.getString(R.string.flip_camera)) {
            viewModel.flipCamera()
        }

        val gallerySlot = FrameLayout(context).apply {
            addView(
                gallery,
                FrameLayout.LayoutParams(dp(54), dp(54), Gravity.START or Gravity.CENTER_VERTICAL)
            )
        }
        val flipSlot = FrameLayout(context).apply {
            addView(
                flipButton,
                FrameLayout.LayoutParams(dp(CameraSize.TOUCH), dp(CameraSize.TOUCH), Gravity.END or Gravity.CENTER_VERTICAL)
            )
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(gallerySlot, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(shutter, LinearLayout.LayoutParams(dp(80), dp(80)))
            addView(flipSlot, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
    }

    private fun buildGallery(): FrameLayout {
        galleryImage = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = GONE
        }
        galleryPlaceholder = ImageView(context).apply {
            setImageResource(R.drawable.ic_photo)
            imageTintList = ColorStateList.valueOf(palette.onSurfaceVariant)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val radius = dp(18).toFloat()
        return FrameLayout(context).apply {
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            background = RippleDrawable(
                ColorStateList.valueOf(palette.withAlpha(palette.onSurface, 0.12f)),
                context.pill(palette.surfaceContainerHigh, 18),
                null
            )
            foreground = context.pill(Color.TRANSPARENT, 18, 1, palette.withAlpha(palette.onSurface, 0.22f))
            addView(galleryImage, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(galleryPlaceholder, LayoutParams(dp(26), dp(26), Gravity.CENTER))
            contentDescription = context.getString(R.string.gallery)
            isClickable = true
            isFocusable = true
            setOnClickListener { latest?.lastPhoto?.let { openMedia(context, it) } }
        }
    }

    private fun buildModeBar(): LinearLayout {
        val settingsButton = cameraToolbarButton(context, palette, R.drawable.ic_settings, context.getString(R.string.settings)) {
            dialogs.showSettings()
        }
        whiteBalanceButton = cameraToolbarButton(context, palette, R.drawable.ic_white_balance, context.getString(R.string.white_balance)) {
            dialogs.showWhiteBalance()
        }

        modeSwitch = ModeSwitchView(context, palette) { video ->
            viewModel.setCaptureMode(if (video) CaptureMode.VIDEO else CaptureMode.PHOTO)
        }

        val capsule = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = context.pill(
                palette.withAlpha(palette.surfaceContainerHigh, 0.92f),
                CameraShape.BAR,
                1,
                palette.withAlpha(palette.outlineVariant, 0.6f)
            )
            setPadding(dp(CameraSpace.XS), 0, dp(CameraSpace.XS), 0)
            addView(settingsButton, LinearLayout.LayoutParams(dp(CameraSize.TOUCH), dp(CameraSize.TOUCH)))
            addView(
                modeSwitch,
                LinearLayout.LayoutParams(dp(116), dp(CameraSize.TOUCH)).apply {
                    leftMargin = dp(CameraSpace.XS)
                    rightMargin = dp(CameraSpace.XS)
                }
            )
            addView(whiteBalanceButton, LinearLayout.LayoutParams(dp(CameraSize.TOUCH), dp(CameraSize.TOUCH)))
        }
        val slot = FrameLayout(context).apply {
            addView(
                capsule,
                FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(CameraSize.TOP_BAR), Gravity.CENTER)
            )
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(slot, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
    }

    private fun buildPermissionOverlay() {
        val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_camera)
            imageTintList = ColorStateList.valueOf(palette.onPrimaryContainer)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val iconCircle = FrameLayout(context).apply {
            background = context.pill(palette.primaryContainer, 36)
            addView(icon, LayoutParams(dp(32), dp(32), Gravity.CENTER))
        }
        val title = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(palette.onSurface)
            gravity = Gravity.CENTER
            setText(R.string.camera_permission_title)
        }
        val body = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(palette.onSurfaceVariant)
            gravity = Gravity.CENTER
            setText(R.string.camera_permission_body)
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        permissionButton = MaterialButton(context).apply {
            text = context.getString(R.string.grant_permission)
            isAllCaps = false
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(palette.onPrimary)
            backgroundTintList = ColorStateList.valueOf(palette.primary)
            cornerRadius = dp(CameraShape.BAR)
            minHeight = dp(56)
            minimumHeight = dp(56)
            insetLeft = 0
            insetRight = 0
            insetTop = 0
            insetBottom = 0
            installPressAnimation(0.96f)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                if (permissionPermanentlyDenied) {
                    openAppSettings(context)
                } else {
                    (context.findActivity() as? PermissionHost)?.requestCameraPermission()
                }
            }
        }

        permissionPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), dp(32), dp(32), dp(32))
            background = context.pill(
                palette.withAlpha(palette.surfaceContainer, 0.98f),
                CameraSize.CARD_RADIUS,
                1,
                palette.withAlpha(palette.outlineVariant, 0.6f)
            )
            addView(
                iconCircle,
                LinearLayout.LayoutParams(dp(72), dp(72)).apply { gravity = Gravity.CENTER_HORIZONTAL }
            )
            addView(
                title,
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(CameraSpace.XL)
                }
            )
            addView(
                body,
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(CameraSpace.M)
                }
            )
            addView(
                permissionButton,
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(56)).apply {
                    topMargin = dp(CameraSpace.XL)
                }
            )
        }
        permissionOverlay = FrameLayout(context).apply {
            setBackgroundColor(palette.withAlpha(palette.surface, 0.96f))
            visibility = GONE
            addView(
                permissionPanel,
                FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            )
        }
        addView(permissionOverlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun applyInsets() {
        setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(
                android.view.WindowInsets.Type.systemBars() or
                    android.view.WindowInsets.Type.displayCutout()
            )
            column.setPadding(
                dp(CameraSpace.L) + bars.left,
                dp(CameraSpace.S) + bars.top,
                dp(CameraSpace.L) + bars.right,
                dp(CameraSpace.M) + bars.bottom
            )
            permissionOverlay.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (::permissionPanel.isInitialized) {
            val width = min(w - dp(64), dp(420)).coerceAtLeast(dp(240))
            (permissionPanel.layoutParams as? LayoutParams)?.let { params ->
                params.width = width
                permissionPanel.layoutParams = params
            }
        }
    }

    // ------------------------------------------------------------------ state

    private fun render(state: CameraUiState) {
        latest = state
        dialogs.onState(state)

        grid.visibility = if (state.grid) VISIBLE else GONE
        renderTopBar(state)
        renderMode(state)
        renderShutter(state)
        renderRecording(state)
        renderLensRow(state)
        renderPreview(state)
        renderPermission(state)
        dialogs.syncError(state.cameraError)
    }

    private fun renderTopBar(state: CameraUiState) {
        val flashOn = state.cameraState?.flash == true
        flashButton.setIconResource(if (flashOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
        flashButton.setActive(
            active = flashOn,
            enabled = state.lens?.flashAvailable == true,
            state = context.getString(if (flashOn) R.string.flash_on else R.string.flash_off)
        )

        val timerOn = state.timer != TimerMode.OFF
        if (timerOn) {
            timerButton.icon = null
            timerButton.text = context.getString(
                if (state.timer == TimerMode.THREE) R.string.timer_3s else R.string.timer_10s
            )
            timerButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            timerButton.setTypeface(timerButton.typeface, Typeface.BOLD)
        } else {
            timerButton.setIconResource(R.drawable.ic_timer)
            timerButton.text = null
        }
        timerButton.setActive(
            active = timerOn,
            state = context.getString(
                when (state.timer) {
                    TimerMode.OFF -> R.string.timer_state_off
                    TimerMode.THREE -> R.string.timer_state_3s
                    TimerMode.TEN -> R.string.timer_state_10s
                }
            )
        )

        summaryButton.text = captureSummary(state)
        summaryButton.contentDescription = context.getString(R.string.capture_settings)

        gridButton.setActive(
            active = state.grid,
            state = context.getString(if (state.grid) R.string.state_on else R.string.state_off)
        )
    }

    private fun captureSummary(state: CameraUiState): String {
        if (state.videoMode) {
            return state.videoConfig?.let {
                context.getString(R.string.summary_video, it.quality.label, it.fps)
            } ?: context.getString(R.string.summary_unknown)
        }
        val plan = state.plan ?: return context.getString(R.string.summary_unknown)
        val lens = state.lens ?: return context.getString(R.string.summary_unknown)
        val cropAspect = AspectMath.saveCropAspect(state.saveCrop, displayAspect(), lens.sensorOrientation)
        val saved = AspectMath.savedSize(plan.jpegSize, cropAspect, lens.sensorOrientation)
        val mp = megapixels(saved)
        return when (state.saveCrop) {
            SaveCrop.RAW -> context.getString(R.string.summary_photo, mp)
            SaveCrop.R16_9 -> context.getString(
                R.string.summary_photo_crop,
                mp,
                context.getString(R.string.save_crop_16_9)
            )
            SaveCrop.FULLSCREEN -> context.getString(
                R.string.summary_photo_crop,
                mp,
                context.getString(R.string.save_crop_fullscreen)
            )
        }
    }

    private fun displayAspect(): Float {
        val metrics = resources.displayMetrics
        if (metrics.heightPixels <= 0) return 9f / 20f
        return metrics.widthPixels.toFloat() / metrics.heightPixels.toFloat()
    }

    private fun megapixels(size: SizeI): String {
        val mp = size.area / 1_000_000.0
        return if (abs(mp - mp.roundToInt()) < 0.05) {
            mp.roundToInt().toString()
        } else {
            String.format(Locale.US, "%.1f", mp)
        }
    }

    private fun renderMode(state: CameraUiState) {
        val locked = state.recording || state.recordingStarting
        modeSwitch.setMode(
            video = state.videoMode,
            canSwitch = !locked,
            videoAvailable = state.supportsVideo,
            animate = true
        )
    }

    private fun renderShutter(state: CameraUiState) {
        shutter.setState(
            videoMode = state.videoMode,
            isRecording = state.recording,
            isBusy = state.capturing || state.recordingStarting
        )
        shutter.stateDescription = context.getString(
            when {
                state.recording -> R.string.shutter_stop_recording
                state.videoMode -> R.string.shutter_start_recording
                state.capturing -> R.string.shutter_busy
                else -> R.string.shutter_take_photo
            }
        )

        val canFlip = state.lens != null &&
            state.catalog.lenses(
                if (state.facing == Facing.FRONT) Facing.BACK else Facing.FRONT
            ).isNotEmpty()
        flipButton.setActive(
            active = false,
            enabled = canFlip && !state.recording && !state.recordingStarting
        )
        flipButton.rotation = OrientationMath.iconRotation(state.deviceRotation)

        val wb = state.cameraState?.wb
        val wbActive = wb != null && wb !is WhiteBalanceMode.Auto
        whiteBalanceButton.setActive(
            active = wbActive,
            enabled = wb != null,
            state = wb?.let(ManualWhiteBalance::describe) ?: context.getString(R.string.auto)
        )

        gallery.isEnabled = state.lastPhoto != null
        gallery.alpha = if (gallery.isEnabled) 1f else 0.5f
        gallery.contentDescription = context.getString(
            if (state.lastMediaIsVideo) R.string.last_video else R.string.gallery
        )
        loadGallery(state.lastPhoto)
    }

    private fun renderRecording(state: CameraUiState) {
        recordingPill.visibility = if (state.recording) VISIBLE else GONE
        updateRecordingClock(state.recording)
        countdownLabel.text = state.countdown?.let { context.getString(R.string.countdown_value, it) }.orEmpty()
        countdownLabel.visibility = if (state.countdown != null) VISIBLE else GONE
    }

    private fun renderLensRow(state: CameraUiState) {
        val lenses = state.lenses
        if (lenses.size <= 1) {
            lensScroll.visibility = GONE
            lensRow.removeAllViews()
            lensButtons.clear()
            return
        }
        lensScroll.visibility = VISIBLE
        if (lensButtons.keys.toList() != lenses.map { it.id }) {
            lensRow.removeAllViews()
            lensButtons.clear()
            lenses.forEach { lens ->
                val chip = cameraChip(
                    context, palette,
                    text = lens.label,
                    description = lens.label,
                    minWidthDp = 48,
                    textSizeSp = 12f
                ) { viewModel.selectLens(lens) }
                lensButtons[lens.id] = chip
                lensRow.addView(
                    chip,
                    LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(36)).apply {
                        leftMargin = dp(CameraSpace.XS)
                        rightMargin = dp(CameraSpace.XS)
                    }
                )
            }
        }
        val locked = state.recording || state.recordingStarting || state.capturing
        lenses.forEach { lens ->
            val selected = lens.id == state.lens?.id
            val chip = lensButtons[lens.id] ?: return@forEach
            val wasSelected = chip.isSelected
            chip.setActive(
                active = selected,
                enabled = !locked,
                state = context.getString(if (selected) R.string.state_on else R.string.state_off)
            )
            if (selected && !wasSelected) chip.bump()
        }
    }

    private fun renderPreview(state: CameraUiState) {
        state.plan?.let { plan ->
            // The camera HAL already mirrors the front preview stream, so no extra flip is
            // applied here. PhotoSaver mirrors the still to match, keeping the file identical to
            // what the user framed.
            preview.contentAspect = plan.contentAspect
            if (plan.displayAspect.isFinite() && plan.displayAspect > 0.1f) {
                card.aspect = plan.displayAspect
            }
        }
    }

    private fun renderPermission(state: CameraUiState) {
        permissionOverlay.visibility = if (state.hasPermission) GONE else VISIBLE
        if (state.hasPermission) return
        val activity = context.findActivity()
        permissionPermanentlyDenied = state.permissionDenied &&
            activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == false
        permissionButton.text = context.getString(
            if (permissionPermanentlyDenied) R.string.open_settings else R.string.grant_permission
        )
    }

    // ------------------------------------------------------------------ interaction

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            viewModel.zoomBy(detector.scaleFactor)
            return true
        }
    })

    private val tapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(event: android.view.MotionEvent): Boolean {
            viewModel.tapToFocus(event.x, event.y)
            focusRing.showAt(event.x, event.y)
            return true
        }

        override fun onDoubleTap(event: android.view.MotionEvent): Boolean {
            val zoom = latest?.zoom ?: 1f
            viewModel.setZoom(if (zoom < 1.5f) 2f else 1f)
            return true
        }
    })

    private fun updateRecordingClock(recording: Boolean) {
        if (recording && !recordingTickerRunning) {
            recordingStartedAt = SystemClock.elapsedRealtime()
            recordingTickerRunning = true
            uiHandler.post(recordingTick)
        } else if (!recording && recordingTickerRunning) {
            recordingTickerRunning = false
            uiHandler.removeCallbacks(recordingTick)
            recordingPill.setElapsed(0)
        }
    }

    private val recordingTick = object : Runnable {
        override fun run() {
            if (!recordingTickerRunning) return
            val seconds = ((SystemClock.elapsedRealtime() - recordingStartedAt) / 1000).toInt()
            recordingPill.setElapsed(seconds)
            recordingPill.pulse()
            uiHandler.postDelayed(this, 500)
        }
    }

    private fun flashScreen() {
        captureFlash.animate().cancel()
        captureFlash.alpha = 0.45f
        captureFlash.animate().alpha(0f).setDuration(280).start()
    }

    private fun loadGallery(uri: Uri?) {
        if (uri == loadedUri) return
        loadedUri = uri
        if (uri == null) {
            galleryImage.setImageDrawable(null)
            galleryImage.visibility = GONE
            galleryPlaceholder.visibility = VISIBLE
            return
        }
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.loadThumbnail(uri, Size(dp(96), dp(96)), null) }.getOrNull()
            }
            if (loadedUri != uri) return@launch
            galleryImage.setImageBitmap(bitmap)
            galleryImage.visibility = if (bitmap != null) VISIBLE else GONE
            galleryPlaceholder.visibility = if (bitmap == null) VISIBLE else GONE
        }
    }

    private fun dp(value: Int): Int = context.dp(value)

    override fun onDetachedFromWindow() {
        recordingTickerRunning = false
        uiHandler.removeCallbacks(recordingTick)
        super.onDetachedFromWindow()
    }
}

interface PermissionHost {
    fun requestCameraPermission()
}
