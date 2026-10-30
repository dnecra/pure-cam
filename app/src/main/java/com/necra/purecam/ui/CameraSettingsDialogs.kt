package com.necra.purecam.ui

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.necra.purecam.R
import com.necra.purecam.core.CameraState
import com.necra.purecam.core.ExposureMode
import com.necra.purecam.core.FocusMode
import com.necra.purecam.core.LensProfile
import com.necra.purecam.core.ManualWhiteBalance
import com.necra.purecam.core.SaveCrop
import com.necra.purecam.core.VideoMath
import com.necra.purecam.core.WhiteBalanceMode
import com.necra.purecam.presentation.CameraUiState
import com.necra.purecam.presentation.CameraViewModel
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * All camera settings surfaces. One sheet is visible at a time; opening another replaces it instead
 * of stacking. Settings use large-radius bottom sheets to match the Expressive chrome. The manual
 * panel is state driven, so toggling Auto/Manual shows the sliders without closing the sheet.
 */
internal class CameraDialogs(
    private val context: Context,
    private val palette: CameraPalette,
    private val viewModel: CameraViewModel,
    private val latest: () -> CameraUiState?
) {
    private var active: Dialog? = null
    private var errorDialog: AlertDialog? = null
    private var errorShown: String? = null
    private var manualPanel: ManualControlsPanel? = null

    fun onState(state: CameraUiState) {
        val camera = state.cameraState ?: return
        manualPanel?.bind(camera)
    }

    fun showSettings() {
        val state = latest() ?: return
        val content = sheetColumn()
        content.addView(
            optionRow(
                title = getString(R.string.settings_manual_controls),
                supporting = getString(R.string.settings_manual_controls_hint)
            ) { showManual() }
        )
        content.addView(
            optionRow(
                title = getString(R.string.save_crop),
                supporting = cropLabel(state.saveCrop)
            ) { showSaveCrop() }
        )
        content.addView(
            optionRow(
                title = getString(R.string.settings_video),
                supporting = videoSummary(state)
            ) { showVideoSettings() }
        )
        showSheet(R.string.capture_settings, content)
    }

    /** "Save crop": full sensor, 16:9, or the current display. Applied when saving. */
    fun showSaveCrop() {
        val state = latest() ?: return
        val content = sheetColumn()
        SaveCrop.entries.forEach { crop ->
            content.addView(
                optionRow(
                    title = cropOptionLabel(crop),
                    supporting = cropHint(crop),
                    selected = crop == state.saveCrop
                ) {
                    viewModel.setSaveCrop(crop)
                    dismissActive()
                }
            )
        }
        showSheet(R.string.save_crop, content)
    }

    private fun cropLabel(crop: SaveCrop): String = when (crop) {
        SaveCrop.RAW -> getString(R.string.save_crop_raw)
        SaveCrop.R16_9 -> getString(R.string.save_crop_16_9)
        SaveCrop.FULLSCREEN -> getString(R.string.save_crop_fullscreen)
    }

    private fun cropOptionLabel(crop: SaveCrop): String = when (crop) {
        SaveCrop.RAW -> getString(R.string.save_crop_raw_option)
        SaveCrop.R16_9 -> getString(R.string.save_crop_16_9)
        SaveCrop.FULLSCREEN -> getString(R.string.save_crop_fullscreen)
    }

    private fun cropHint(crop: SaveCrop): String = when (crop) {
        SaveCrop.RAW -> getString(R.string.save_crop_raw_hint)
        SaveCrop.R16_9 -> getString(R.string.save_crop_16_9_hint)
        SaveCrop.FULLSCREEN -> getString(R.string.save_crop_fullscreen_hint)
    }

    private fun videoSummary(state: CameraUiState): String {
        val config = state.videoConfig ?: return getString(R.string.settings_video_hint)
        return "${config.size.width} × ${config.size.height} · " +
            getString(R.string.frame_rate_value, config.fps)
    }

    fun showVideoSettings() {
        val state = latest() ?: return
        if (state.availableVideoQualities.isEmpty()) {
            showMessage(R.string.settings_video, R.string.no_video_options)
            return
        }
        val quality = state.videoConfig?.quality ?: state.availableVideoQualities.first()
        val content = sheetColumn()
        content.addView(
            optionRow(
                title = getString(R.string.video_quality_title),
                supporting = quality.label
            ) { showVideoQuality() }
        )
        content.addView(
            optionRow(
                title = getString(R.string.frame_rate),
                supporting = getString(R.string.frame_rate_value, state.videoFps)
            ) { showFrameRate() }
        )
        showSheet(R.string.settings_video, content)
    }

    fun showVideoQuality() {
        val state = latest() ?: return
        val qualities = state.availableVideoQualities
        if (qualities.isEmpty()) return
        val current = state.videoConfig?.quality ?: qualities.first()
        val content = sheetColumn()
        qualities.forEach { quality ->
            val size = VideoMath.sizeFor(quality, state.lens?.videoSizes.orEmpty())
            content.addView(
                optionRow(
                    title = quality.label,
                    supporting = size?.let { "${it.width} × ${it.height}" },
                    selected = quality == current
                ) {
                    viewModel.setVideoQuality(quality)
                    dismissActive()
                }
            )
        }
        showSheet(R.string.video_quality_title, content)
    }

    fun showFrameRate() {
        val state = latest() ?: return
        val rates = state.availableVideoFps
        if (rates.isEmpty()) return
        val content = sheetColumn()
        rates.forEach { fps ->
            content.addView(
                optionRow(
                    title = getString(R.string.frame_rate_value, fps),
                    supporting = if (fps >= 60) {
                        getString(R.string.frame_rate_smooth_hint)
                    } else {
                        getString(R.string.frame_rate_standard_hint)
                    },
                    selected = fps == state.videoFps
                ) {
                    viewModel.setVideoFps(fps)
                    dismissActive()
                }
            )
        }
        showSheet(R.string.frame_rate, content)
    }

    private fun showManual() {
        val state = latest()?.cameraState ?: return
        val panel = ManualControlsPanel(context, palette, state.lens, viewModel) { showWhiteBalance() }
        panel.bind(state)
        manualPanel = panel
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            addView(
                panel,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            )
        }
        showSheet(R.string.settings_manual_controls, scroll) {
            if (manualPanel === panel) manualPanel = null
        }
    }

    fun showWhiteBalance() {
        val state = latest() ?: return
        val lens = state.cameraState?.lens ?: return
        val presets = lens.awbPresets.sortedBy { it.cct }
        val current = state.cameraState.wb
        val content = sheetColumn()
        content.addView(
            optionRow(
                title = getString(R.string.auto),
                selected = current is WhiteBalanceMode.Auto
            ) {
                viewModel.setWhiteBalanceAuto()
                dismissActive()
            }
        )
        presets.forEach { preset ->
            content.addView(
                optionRow(
                    title = preset.label,
                    supporting = "${preset.cct} K",
                    selected = current is WhiteBalanceMode.Preset && current.preset.cct == preset.cct
                ) {
                    viewModel.setWhiteBalancePreset(preset)
                    dismissActive()
                }
            )
        }
        if (lens.supportsKelvin) {
            content.addView(
                optionRow(
                    title = getString(R.string.kelvin),
                    selected = current is WhiteBalanceMode.Kelvin
                ) { showKelvin() }
            )
        }
        showSheet(R.string.white_balance, content)
    }

    private fun showKelvin() {
        val current = latest()?.cameraState?.wb as? WhiteBalanceMode.Kelvin
        val start = ManualWhiteBalance.clampKelvin(current?.kelvin ?: viewModel.suggestedKelvin())
        val content = sheetColumn()
        val value = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(palette.onSurface)
            text = context.getString(R.string.kelvin_value, start)
        }
        val slider = Slider(context).apply {
            valueFrom = ManualWhiteBalance.MIN_KELVIN.toFloat()
            valueTo = ManualWhiteBalance.MAX_KELVIN.toFloat()
            stepSize = ManualWhiteBalance.KELVIN_STEP.toFloat()
            setTickVisible(false)
            this.value = start.toFloat()
        }
        slider.addOnChangeListener { _, progress, fromUser ->
            if (fromUser) value.text = context.getString(R.string.kelvin_value, progress.roundToInt())
        }
        val apply = sheetButton(R.string.apply) {
            viewModel.setWhiteBalanceKelvin(slider.value.roundToInt())
            dismissActive()
        }
        content.addView(value, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        content.addView(
            slider,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(CameraSpace.S)
            }
        )
        content.addView(
            apply,
            LayoutParams(LayoutParams.MATCH_PARENT, context.dp(52)).apply {
                topMargin = context.dp(CameraSpace.L)
            }
        )
        showSheet(R.string.color_temperature, content)
    }

    private fun showMessage(titleRes: Int, messageRes: Int) {
        val content = sheetColumn()
        content.addView(
            TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(palette.onSurfaceVariant)
                setText(messageRes)
                setLineSpacing(context.dp(4).toFloat(), 1f)
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )
        showSheet(titleRes, content)
    }

    /** Shows a camera error once per distinct message; dismissing it clears the error state. */
    fun syncError(message: String?) {
        if (message == null) {
            errorShown = null
            errorDialog?.takeIf { it.isShowing }?.dismiss()
            errorDialog = null
            return
        }
        if (errorShown == message) return
        errorShown = message
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.camera_error)
            .setMessage(message)
            .setPositiveButton(R.string.retry) { _, _ -> viewModel.retryCamera() }
            .setNegativeButton(R.string.close) { _, _ -> viewModel.dismissError() }
            .create()
        errorDialog = dialog
        present(dialog) {
            if (errorShown == message) errorShown = null
            if (viewModel.uiState.value.cameraError != null) viewModel.dismissError()
        }
    }

    // ------------------------------------------------------------------ sheet building blocks

    private fun showSheet(titleRes: Int, content: View, onDismiss: (() -> Unit)? = null) {
        val sheet = BottomSheetDialog(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                context.dp(CameraSpace.XL),
                context.dp(CameraSpace.S),
                context.dp(CameraSpace.XL),
                context.dp(CameraSpace.XL)
            )
        }
        root.addView(
            View(context).apply {
                background = context.pill(
                    palette.withAlpha(palette.outlineVariant, 0.8f),
                    2
                )
            },
            LayoutParams(context.dp(32), context.dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = context.dp(CameraSpace.M)
            }
        )
        root.addView(
            TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(palette.onSurface)
                letterSpacing = -0.01f
                setText(titleRes)
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = context.dp(CameraSpace.M)
            }
        )
        val body = if (content is ScrollView) {
            content
        } else {
            NestedScrollView(context).apply {
                isFillViewport = false
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }
        }
        root.addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        sheet.setContentView(root)
        sheet.behavior.isDraggable = true
        sheet.behavior.isFitToContents = true
        sheet.behavior.skipCollapsed = true
        present(sheet, onDismiss)
        root.post { sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED }
    }

    private fun sheetColumn(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    private fun optionRow(
        title: String,
        supporting: String? = null,
        selected: Boolean = false,
        onClick: () -> Unit
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dp(56)
        setPadding(
            context.dp(CameraSpace.L),
            context.dp(CameraSpace.M),
            context.dp(CameraSpace.L),
            context.dp(CameraSpace.M)
        )
        background = android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(palette.withAlpha(palette.primary, 0.12f)),
            if (selected) context.pill(palette.primaryContainer, CameraShape.CHIP) else null,
            null
        )
        isClickable = true
        isFocusable = true
        installPressAnimation(0.98f)
        setOnClickListener {
            performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            onClick()
        }

        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        textColumn.addView(
            TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(if (selected) palette.onPrimaryContainer else palette.onSurface)
                text = title
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )
        supporting?.let {
            textColumn.addView(
                TextView(context).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(
                        if (selected) {
                            palette.withAlpha(palette.onPrimaryContainer, 0.78f)
                        } else {
                            palette.onSurfaceVariant
                        }
                    )
                    text = it
                },
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = context.dp(2)
                }
            )
        }
        addView(textColumn, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        if (selected) {
            addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.ic_check)
                    imageTintList = ColorStateList.valueOf(palette.onPrimaryContainer)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LayoutParams(context.dp(22), context.dp(22)).apply {
                    leftMargin = context.dp(CameraSpace.M)
                }
            )
        }
    }

    private fun sheetButton(textRes: Int, onClick: () -> Unit): MaterialButton =
        MaterialButton(context).apply {
            text = context.getString(textRes)
            isAllCaps = false
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(palette.onPrimary)
            backgroundTintList = ColorStateList.valueOf(palette.primary)
            cornerRadius = context.dp(CameraShape.BAR)
            minHeight = context.dp(52)
            minimumHeight = context.dp(52)
            insetLeft = 0
            insetTop = 0
            insetRight = 0
            insetBottom = 0
            installPressAnimation(0.97f)
            setOnClickListener { onClick() }
        }

    private fun dismissActive() {
        active?.dismiss()
    }

    private fun present(dialog: Dialog, onDismiss: (() -> Unit)? = null) {
        if (active !== dialog) active?.takeIf { it.isShowing }?.dismiss()
        active = dialog
        dialog.setOnDismissListener {
            if (active === dialog) active = null
            onDismiss?.invoke()
        }
        dialog.show()
    }

    private fun getString(id: Int, vararg args: Any): String = context.getString(id, *args)
}

/**
 * State-driven manual exposure/focus/WB panel.
 *
 * Sliders are only pushed from state when the user is not dragging them, which keeps the
 * camera's echo from fighting the gesture.
 */
private class ManualControlsPanel(
    context: Context,
    private val palette: CameraPalette,
    private val lens: LensProfile,
    private val viewModel: CameraViewModel,
    private val onWhiteBalance: () -> Unit
) : LinearLayout(context) {
    private var binding = false
    private var draggingIso = false
    private var draggingShutter = false
    private var draggingFocus = false

    private val isoRange = lens.isoRange
    private val shutterRange = lens.exposureRange

    private var exposureGroup: MaterialButtonToggleGroup? = null
    private var exposureAutoId = 0
    private var exposureManualId = 0
    private var isoRow: LinearLayout? = null
    private var isoSlider: Slider? = null
    private var isoLabel: TextView? = null
    private var shutterSlider: Slider? = null
    private var shutterLabel: TextView? = null

    private var focusGroup: MaterialButtonToggleGroup? = null
    private var focusAutoId = 0
    private var focusManualId = 0
    private var focusSlider: Slider? = null
    private var focusLabel: TextView? = null

    private val whiteBalanceValue: TextView

    init {
        orientation = VERTICAL

        addView(sectionHeader(R.string.exposure))
        if (lens.supportsManualExposure && isoRange != null && shutterRange != null) {
            val group = modeGroup { manual ->
                if (manual) viewModel.setExposureManual() else viewModel.setExposureAuto()
            }
            exposureAutoId = group.getChildAt(0).id
            exposureManualId = group.getChildAt(1).id
            exposureGroup = group
            addView(
                group,
                LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, context.dp(40)).apply {
                    topMargin = context.dp(CameraSpace.S)
                }
            )

            isoLabel = valueLabel().apply { text = context.getString(R.string.iso_value, isoRange.min) }
            isoSlider = Slider(context).apply {
                valueFrom = 0f
                valueTo = (isoRange.max - isoRange.min).coerceAtLeast(1).toFloat()
                value = 0f
            }
            isoRow = LinearLayout(context).apply {
                orientation = VERTICAL
                addView(isoLabel, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                addView(isoSlider, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }
            addView(isoRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

            shutterLabel = valueLabel()
            shutterSlider = Slider(context).apply {
                valueFrom = 0f
                valueTo = 1000f
                value = 0f
            }
            addView(shutterLabel, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(shutterSlider, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            wireExposureSliders()
        } else {
            addView(valueLabel(R.string.manual_not_supported))
        }

        addView(sectionHeader(R.string.focus))
        if (lens.supportsManualFocus) {
            val group = modeGroup { manual ->
                if (manual) viewModel.setFocusManual() else viewModel.setFocusAuto()
            }
            focusAutoId = group.getChildAt(0).id
            focusManualId = group.getChildAt(1).id
            focusGroup = group
            addView(
                group,
                LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, context.dp(40)).apply {
                    topMargin = context.dp(CameraSpace.S)
                }
            )
            focusLabel = valueLabel()
            focusSlider = Slider(context).apply {
                valueFrom = 0f
                valueTo = 1000f
                value = 0f
            }
            addView(focusLabel, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(focusSlider, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            wireFocusSlider()
        } else {
            addView(valueLabel(R.string.manual_not_supported))
        }

        val wbHeader = sectionHeader(R.string.white_balance)
        whiteBalanceValue = valueLabel().apply {
            gravity = Gravity.END
            setPadding(context.dp(CameraSpace.S), 0, 0, 0)
        }
        wbHeader.addView(
            whiteBalanceValue,
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                gravity = Gravity.END
            }
        )
        addView(
            wbHeader,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )
        val wbRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val auto = cameraChip(
            context, palette,
            text = context.getString(R.string.auto),
            description = context.getString(R.string.auto)
        ) { viewModel.setWhiteBalanceAuto() }.outlinedButton()
        val choose = cameraChip(
            context, palette,
            text = context.getString(R.string.choose),
            description = context.getString(R.string.white_balance)
        ) { onWhiteBalance() }.outlinedButton()
        wbRow.addView(auto, LinearLayout.LayoutParams(0, context.dp(40), 1f))
        wbRow.addView(choose, LinearLayout.LayoutParams(0, context.dp(40), 1f).apply {
            leftMargin = context.dp(CameraSpace.S)
        })
        addView(
            wbRow,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, context.dp(40)).apply {
                topMargin = context.dp(CameraSpace.S)
                bottomMargin = context.dp(CameraSpace.S)
            }
        )
    }

    fun bind(state: CameraState) {
        binding = true
        try {
            bindExposure(state)
            bindFocus(state)
            whiteBalanceValue.text = when (val wb = state.wb) {
                is WhiteBalanceMode.Auto -> context.getString(R.string.auto)
                is WhiteBalanceMode.Preset -> wb.preset.label
                is WhiteBalanceMode.Kelvin -> context.getString(R.string.kelvin_value, wb.kelvin)
            }
        } finally {
            binding = false
        }
    }

    private fun bindExposure(state: CameraState) {
        val manual = state.exposure as? ExposureMode.Manual
        exposureGroup?.check(if (manual != null) exposureManualId else exposureAutoId)
        val isoVisible = manual != null
        isoRow?.visibility = if (isoVisible) View.VISIBLE else View.GONE
        shutterLabel?.visibility = if (isoVisible) View.VISIBLE else View.GONE
        shutterSlider?.visibility = if (isoVisible) View.VISIBLE else View.GONE

        val iso = manual?.iso ?: isoRange?.clamp(100) ?: 100
        isoSlider?.let { slider ->
            if (!draggingIso) {
                slider.value = (iso - (isoRange?.min ?: 0)).coerceAtLeast(0).toFloat()
                    .coerceAtMost(slider.valueTo)
            }
            isoLabel?.text = context.getString(R.string.iso_value, iso)
        }
        val shutter = manual?.shutterNs ?: shutterRange?.clamp(16_666_667L) ?: 16_666_667L
        shutterSlider?.let { slider ->
            if (!draggingShutter) slider.value = shutterProgress(shutter)
            shutterLabel?.text = context.getString(R.string.shutter_value, formatShutter(shutter))
        }
    }

    private fun bindFocus(state: CameraState) {
        val manual = state.focus as? FocusMode.Manual
        focusGroup?.check(if (manual != null) focusManualId else focusAutoId)
        focusSlider?.visibility = if (manual != null) View.VISIBLE else View.GONE
        focusLabel?.visibility = if (manual != null) View.VISIBLE else View.GONE
        val diopters = (manual?.diopters ?: 0f).coerceIn(0f, lens.minFocusDiopters)
        focusSlider?.let { slider ->
            if (!draggingFocus) {
                slider.value = if (lens.minFocusDiopters > 0f) {
                    (diopters / lens.minFocusDiopters * 1000f).coerceIn(0f, 1000f)
                } else {
                    0f
                }
            }
            focusLabel?.text = if (diopters < 0.005f) {
                context.getString(R.string.focus_infinity)
            } else {
                context.getString(R.string.focus_value, String.format(Locale.US, "%.2f D", diopters))
            }
        }
    }

    private fun wireExposureSliders() {
        val iso = isoSlider ?: return
        val shutter = shutterSlider ?: return
        val isoMin = isoRange?.min ?: 0
        iso.addOnChangeListener { _, value, fromUser ->
            if (fromUser) isoLabel?.text = context.getString(R.string.iso_value, isoMin + value.roundToInt())
        }
        iso.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                draggingIso = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                draggingIso = false
                applyExposure(iso.value, shutter.value)
            }
        })
        shutter.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                shutterLabel?.text = context.getString(
                    R.string.shutter_value,
                    formatShutter(shutterFromProgress(value))
                )
            }
        }
        shutter.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                draggingShutter = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                draggingShutter = false
                applyExposure(iso.value, shutter.value)
            }
        })
    }

    private fun applyExposure(isoProgress: Float, shutterProgress: Float) {
        val range = isoRange ?: return
        viewModel.setExposureValues(
            (range.min + isoProgress.roundToInt()).coerceIn(range.min, range.max),
            shutterFromProgress(shutterProgress)
        )
    }

    private fun wireFocusSlider() {
        val slider = focusSlider ?: return
        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val diopters = lens.minFocusDiopters * value / 1000f
                focusLabel?.text = if (diopters < 0.005f) {
                    context.getString(R.string.focus_infinity)
                } else {
                    context.getString(R.string.focus_value, String.format(Locale.US, "%.2f D", diopters))
                }
            }
        }
        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                draggingFocus = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                draggingFocus = false
                viewModel.setFocusDiopters(lens.minFocusDiopters * slider.value / 1000f)
            }
        })
    }

    private fun shutterProgress(shutterNs: Long): Float {
        val range = shutterRange ?: return 0f
        if (range.min <= 0L || range.max <= range.min) return 0f
        val value = shutterNs.coerceIn(range.min, range.max).toDouble()
        return (ln(value / range.min) / ln(range.max.toDouble() / range.min) * 1000.0)
            .toFloat().coerceIn(0f, 1000f)
    }

    private fun shutterFromProgress(progress: Float): Long {
        val range = shutterRange ?: return 16_666_667L
        if (range.min <= 0L || range.max <= range.min) return range.min
        val ratio = ln(range.max.toDouble() / range.min)
        return (range.min * exp(ratio * progress.coerceIn(0f, 1000f) / 1000.0))
            .toLong().coerceIn(range.min, range.max)
    }

    private fun modeGroup(onSelected: (Boolean) -> Unit): MaterialButtonToggleGroup {
        val group = MaterialButtonToggleGroup(context).apply {
            isSingleSelection = true
            isSelectionRequired = true
            background = context.pill(palette.surfaceContainerHigh, 20)
        }
        listOf(context.getString(R.string.auto), context.getString(R.string.manual)).forEach { label ->
            val button = cameraSegmentButton(context, palette, label, label).apply {
                id = View.generateViewId()
            }
            group.addView(button, LayoutParams(context.dp(76), context.dp(40)))
        }
        group.addOnButtonCheckedListener { _, checkedId, checked ->
            if (!checked || binding) return@addOnButtonCheckedListener
            onSelected(checkedId == group.getChildAt(1).id)
        }
        return group
    }

    private fun sectionHeader(titleRes: Int): LinearLayout {
        val title = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(palette.onSurface)
            setText(titleRes)
        }
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, context.dp(CameraSpace.L), 0, context.dp(CameraSpace.XS))
            addView(
                title,
                LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            )
        }
    }

    /** Gives a chip the outlined container that marks it as an action next to the value it controls. */
    private fun MaterialButton.outlinedButton(): MaterialButton = apply {
        backgroundTintList = ColorStateList.valueOf(palette.surfaceContainer)
        strokeWidth = context.dp(1)
        strokeColor = ColorStateList.valueOf(palette.outlineVariant)
    }

    private fun valueLabel(textRes: Int? = null): TextView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(palette.onSurfaceVariant)
        textRes?.let { setText(it) }
    }
}

internal fun formatShutter(ns: Long): String {
    if (ns <= 0L) return "—"
    val seconds = ns / 1_000_000_000.0
    return if (seconds >= 0.3) {
        String.format(Locale.US, "%.1f s", seconds)
    } else {
        "1/${(1.0 / seconds).roundToInt()} s"
    }
}
