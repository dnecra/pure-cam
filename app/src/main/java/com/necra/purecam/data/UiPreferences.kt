package com.necra.purecam.data

import android.content.Context
import android.net.Uri
import com.necra.purecam.core.VideoMath
import com.necra.purecam.core.VideoQuality
import com.necra.purecam.presentation.CaptureMode
import com.necra.purecam.presentation.TimerMode

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

    private companion object {
        const val KEY_GRID = "grid"
        const val KEY_TIMER = "timer"
        const val KEY_LAST_PHOTO = "last_photo_uri"
        const val KEY_LAST_MEDIA_VIDEO = "last_media_video"
        const val KEY_MODE = "capture_mode"
        const val KEY_VIDEO_QUALITY = "video_quality"
        const val KEY_VIDEO_FPS = "video_fps"
    }
}
