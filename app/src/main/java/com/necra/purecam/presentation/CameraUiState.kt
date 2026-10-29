package com.necra.purecam.presentation

import android.net.Uri
import com.necra.purecam.LensCatalog
import com.necra.purecam.core.AspectPlan
import com.necra.purecam.core.CameraState
import com.necra.purecam.core.ExportFormat
import com.necra.purecam.core.Facing
import com.necra.purecam.core.LensProfile
import com.necra.purecam.core.SizeI
import com.necra.purecam.core.VideoConfig
import com.necra.purecam.core.VideoMath
import com.necra.purecam.core.VideoQuality

enum class CaptureMode { PHOTO, VIDEO }

/**
 * One immutable snapshot of everything the camera screen renders.
 * Produced only by [CameraViewModel]; the UI never mutates it.
 */
data class CameraUiState(
    val ready: Boolean = false,
    val hasPermission: Boolean = false,
    val permissionDenied: Boolean = false,
    val cameraState: CameraState? = null,
    val plan: AspectPlan? = null,
    val catalog: LensCatalog = LensCatalog(emptyMap()),
    val capturing: Boolean = false,
    val zoom: Float = 1f,
    val grid: Boolean = false,
    val timer: TimerMode = TimerMode.OFF,
    val countdown: Int? = null,
    val lastPhoto: Uri? = null,
    val lastMediaIsVideo: Boolean = false,
    val deviceRotation: Int = 0,
    val cameraError: String? = null,
    val captureMode: CaptureMode = CaptureMode.PHOTO,
    val recording: Boolean = false,
    /** True between the record tap and the first encoder frame; a second tap means stop. */
    val recordingStarting: Boolean = false,
    val videoQuality: VideoQuality = VideoQuality.FHD_1080P,
    val videoFps: Int = VideoMath.DEFAULT_FPS,
    val exportFormat: ExportFormat = ExportFormat.JPEG,
    val audioGranted: Boolean = false
) {
    val lens: LensProfile? get() = cameraState?.lens

    val lenses: List<LensProfile> get() = lens?.let { catalog.lenses(it.facing) }.orEmpty()

    /** Still buffer in use: always the lens's largest JPEG, cropped to the selected ratio. */
    val photoSize: SizeI?
        get() = lens?.jpegSizes?.maxByOrNull { it.area }

    val facing: Facing? get() = lens?.facing

    val videoMode: Boolean get() = captureMode == CaptureMode.VIDEO

    val supportsVideo: Boolean get() = lens?.supportsVideo == true && videoConfig != null

    val availableVideoQualities: List<VideoQuality>
        get() = VideoMath.availableQualities(lens?.videoSizes.orEmpty())

    val availableVideoFps: List<Int>
        get() = VideoMath.supportedFps(lens?.videoFpsRanges.orEmpty())

    /** The exact encoder buffer and frame rate this lens will use, or null if it cannot record. */
    val videoConfig: VideoConfig?
        get() {
            val l = lens ?: return null
            val qualities = availableVideoQualities
            val quality = if (videoQuality in qualities) {
                videoQuality
            } else {
                VideoMath.defaultQuality(qualities) ?: return null
            }
            val size = VideoMath.sizeFor(quality, l.videoSizes) ?: return null
            return VideoConfig(quality, size, VideoMath.fpsFor(videoFps, availableVideoFps))
        }

    /** True when at least one control is out of Auto, used to highlight the tune button. */
    val manualControlsActive: Boolean
        get() = cameraState?.let { s ->
            s.exposure !is com.necra.purecam.core.ExposureMode.Auto ||
                s.focus !is com.necra.purecam.core.FocusMode.Auto ||
                s.wb !is com.necra.purecam.core.WhiteBalanceMode.Auto
        } ?: false
}

enum class TimerMode(val seconds: Int) {
    OFF(0),
    THREE(3),
    TEN(10);

    fun next(): TimerMode = when (this) {
        OFF -> THREE
        THREE -> TEN
        TEN -> OFF
    }
}

/** One-shot effects that must not be replayed on recomposition. */
sealed interface CameraSignal {
    data class Message(val text: String) : CameraSignal
    data object CaptureFlash : CameraSignal
    data object RequestAudioPermission : CameraSignal
}
