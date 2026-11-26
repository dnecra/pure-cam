package com.necra.purecam.data

import android.content.Context
import android.net.Uri
import com.necra.purecam.core.CaptureAspectMode
import com.necra.purecam.core.VideoMath
import com.necra.purecam.core.VideoQuality
import com.necra.purecam.core.WbPreset
import com.necra.purecam.core.OverlayStyle
import com.necra.purecam.core.WhiteBalanceMode
import com.necra.purecam.presentation.CaptureMode
import com.necra.purecam.presentation.TimerMode
import com.necra.purecam.presentation.FlashBehavior

/** Small persisted UI preferences. Kept out of the camera code so it stays framework-light. */
class UiPreferences(context: Context) {

    private val prefs = context.getSharedPreferences("rawcam_ui", Context.MODE_PRIVATE)

    var gridVisible: Boolean
        get() = prefs.getBoolean(KEY_GRID, false)
        set(value) = prefs.edit().putBoolean(KEY_GRID, value).apply()

    var timer: TimerMode
        get() = runCatching { TimerMode.valueOf(prefs.getString(KEY_TIMER, null) ?: TimerMode.OFF.name) }
            .getOrDefault(TimerMode.OFF)
        set(value) = prefs.edit().putString(KEY_TIMER, value.name).apply()

    var lastPhoto: Uri?
        get() = prefs.getString(KEY_LAST_PHOTO, null)?.let(Uri::parse)
        set(value) = prefs.edit().putString(KEY_LAST_PHOTO, value?.toString()).apply()

    var lastMediaIsVideo: Boolean
        get() = prefs.getBoolean(KEY_LAST_MEDIA_VIDEO, false)
        set(value) = prefs.edit().putBoolean(KEY_LAST_MEDIA_VIDEO, value).apply()

    var captureMode: CaptureMode
        get() = runCatching {
            CaptureMode.valueOf(prefs.getString(KEY_MODE, null) ?: CaptureMode.PHOTO.name)
        }.getOrDefault(CaptureMode.PHOTO)
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    var videoQuality: VideoQuality
        get() = runCatching {
            VideoQuality.valueOf(prefs.getString(KEY_VIDEO_QUALITY, null) ?: VideoQuality.FHD_1080P.name)
        }.getOrDefault(VideoQuality.FHD_1080P)
        set(value) = prefs.edit().putString(KEY_VIDEO_QUALITY, value.name).apply()

    var videoFps: Int
        get() = prefs.getInt(KEY_VIDEO_FPS, VideoMath.DEFAULT_FPS)
        set(value) = prefs.edit().putInt(KEY_VIDEO_FPS, value).apply()

    var aspectMode: CaptureAspectMode
        get() = runCatching {
            CaptureAspectMode.valueOf(
                prefs.getString(KEY_ASPECT_MODE, CaptureAspectMode.PORTRAIT_9_16.name)
                    ?: CaptureAspectMode.PORTRAIT_9_16.name
            )
        }.getOrDefault(CaptureAspectMode.PORTRAIT_9_16)
        set(value) = prefs.edit().putString(KEY_ASPECT_MODE, value.name).apply()

    var whiteBalance: WhiteBalanceMode
        get() = when (prefs.getString(KEY_WB_MODE, null)) {
            WB_PRESET -> runCatching {
                WhiteBalanceMode.Preset(
                    WbPreset.valueOf(prefs.getString(KEY_WB_PRESET, WbPreset.DAYLIGHT.name)!!)
                )
            }.getOrDefault(WhiteBalanceMode.Auto)
            WB_KELVIN -> WhiteBalanceMode.Kelvin(prefs.getInt(KEY_WB_KELVIN, 5200))
            else -> WhiteBalanceMode.Auto
        }
        set(value) {
            val edit = prefs.edit()
            when (value) {
                WhiteBalanceMode.Auto -> edit.putString(KEY_WB_MODE, WB_AUTO)
                is WhiteBalanceMode.Preset -> edit
                    .putString(KEY_WB_MODE, WB_PRESET)
                    .putString(KEY_WB_PRESET, value.preset.name)
                is WhiteBalanceMode.Kelvin -> edit
                    .putString(KEY_WB_MODE, WB_KELVIN)
                    .putInt(KEY_WB_KELVIN, value.kelvin)
            }
            edit.apply()
        }

    /**
     * Hardware presets the viewfinder WB button cycles through. Missing key means the
     * minimal default (Auto + Daylight); an explicitly empty set means Auto only.
     */
    var enabledWbPresets: Set<WbPreset>
        get() {
            val stored = prefs.getStringSet(KEY_ENABLED_WB_PRESETS, null)
                ?: return DEFAULT_ENABLED_WB_PRESETS
            return stored.mapNotNull { name ->
                runCatching { WbPreset.valueOf(name) }.getOrNull()
            }.toSet()
        }
        set(value) = prefs.edit()
            .putStringSet(KEY_ENABLED_WB_PRESETS, value.map { it.name }.toSet())
            .apply()

    /**
     * Procedural film effects applied to saved stills. Independent from the white-balance
     * preset, so both can be active at once. Empty by default (pure photos).
     */
    var enabledOverlays: Set<OverlayStyle>
        get() {
            val stored = prefs.getStringSet(KEY_ENABLED_OVERLAYS, null) ?: return emptySet()
            return stored.mapNotNull { name ->
                runCatching { OverlayStyle.valueOf(name) }.getOrNull()
            }.toSet()
        }
        set(value) = prefs.edit()
            .putStringSet(KEY_ENABLED_OVERLAYS, value.map { it.name }.toSet())
            .apply()

    /**
     * When true the front-camera JPEG is flipped horizontally before saving, so the photo
     * matches what the user saw in the preview (mirror image). Default on — matches stock
     * camera behaviour. The user can turn this off to save the unmirrored sensor image.
     */
    var frontCameraFlipSave: Boolean
        get() = prefs.getBoolean(KEY_FRONT_FLIP_SAVE, true)
        set(value) = prefs.edit().putBoolean(KEY_FRONT_FLIP_SAVE, value).apply()

    /** True = photos use the video recorder's auto exposure; false = the standard still pipeline. */
    var photoVideoExposure: Boolean
        get() = prefs.getBoolean(KEY_PHOTO_VIDEO_EXPOSURE, true)
        set(value) = prefs.edit().putBoolean(KEY_PHOTO_VIDEO_EXPOSURE, value).apply()

    var flashBehavior: FlashBehavior
        get() = runCatching {
            FlashBehavior.valueOf(prefs.getString(KEY_FLASH_BEHAVIOR, FlashBehavior.NORMAL.name)!!)
        }.getOrDefault(FlashBehavior.NORMAL)
        set(value) = prefs.edit().putString(KEY_FLASH_BEHAVIOR, value.name).apply()

    /**
     * When true the live preview is also horizontally mirrored for the front camera,
     * matching what most stock cameras show. Off by default — the standard (unmirrored)
     * sensor view.
     */
    private companion object {
        const val KEY_GRID = "grid"
        const val KEY_TIMER = "timer"
        const val KEY_LAST_PHOTO = "last_photo_uri"
        const val KEY_LAST_MEDIA_VIDEO = "last_media_video"
        const val KEY_MODE = "capture_mode"
        const val KEY_VIDEO_QUALITY = "video_quality"
        const val KEY_VIDEO_FPS = "video_fps"
        const val KEY_ASPECT_MODE = "capture_aspect_mode"
        const val KEY_WB_MODE = "white_balance_mode"
        const val KEY_WB_PRESET = "white_balance_preset"
        const val KEY_WB_KELVIN = "white_balance_kelvin"
        const val KEY_ENABLED_WB_PRESETS = "enabled_wb_presets"
        const val KEY_ENABLED_OVERLAYS = "enabled_overlays"
        const val KEY_FRONT_FLIP_SAVE = "front_camera_flip_save"
        const val KEY_FLASH_BEHAVIOR = "flash_behavior"
        const val KEY_PHOTO_VIDEO_EXPOSURE = "photo_video_exposure"
        const val WB_AUTO = "auto"
        const val WB_PRESET = "preset"
        const val WB_KELVIN = "kelvin"
        val DEFAULT_ENABLED_WB_PRESETS = setOf(WbPreset.DAYLIGHT)
    }
}
