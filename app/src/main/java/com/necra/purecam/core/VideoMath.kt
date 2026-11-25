package com.necra.purecam.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Recording resolutions the UI offers. The actual buffer is matched per lens: a device that
 * has no 4K stream simply does not offer the 4K choice.
 */
enum class VideoQuality(val label: String, val width: Int, val height: Int) {
    UHD_4K("4K", 3840, 2160),
    FHD_1080P("1080p", 1920, 1080),
    HD_720P("720p", 1280, 720)
}

/** A fully resolved recording configuration: what to ask the encoder and the camera for. */
data class VideoConfig(
    val quality: VideoQuality,
    val size: SizeI,
    val fps: Int
) {
    val bitRate: Int get() = VideoMath.bitRate(size, fps)
}

/**
 * Pure helpers for video recording. Sizes and frame rates come from the camera's own
 * advertised lists, so nothing here guesses at hardware support.
 */
object VideoMath {

    val CANDIDATE_FPS = listOf(24, 30, 60)
    const val DEFAULT_FPS = 60

    /** MediaRecorder needs a few frames before the MP4 header is valid. */
    const val MIN_RECORDING_MS = 500L

    /** Landscape buffers that a camera advertises for encoder output, at any aspect ratio. */
    fun recordableSizes(sizes: List<SizeI>): List<SizeI> =
        sizes.filter { it.width > it.height && it.width <= 4096 }
            .sortedByDescending { it.area }

    /** Pick a supported encoder size for the quality tier, preferring the camera's native ratio. */
    fun sizeFor(
        quality: VideoQuality,
        sizes: List<SizeI>,
        preferredAspect: Float? = null
    ): SizeI? {
        val pool = recordableSizes(sizes)
        if (pool.isEmpty()) return null
        val tolerance = (quality.width * 0.05f).coerceAtLeast(48f)
        val tierSizes = pool.filter { abs(it.width - quality.width) <= tolerance }
        val nativeRatio = preferredAspect?.takeIf { it.isFinite() && it > 0f }
        if (nativeRatio != null && tierSizes.isNotEmpty()) {
            return tierSizes.minWithOrNull(
                compareBy<SizeI> { AspectMath.relativeAspectError(it.aspect, nativeRatio) }
                    .thenBy { abs(it.width - quality.width) }
                    .thenByDescending { it.area }
            )
        }
        pool.firstOrNull { it.width == quality.width && it.height == quality.height }?.let { return it }
        return pool.filter {
            abs(it.width - quality.width) <= tolerance && abs(it.height - quality.height) <= tolerance
        }.minByOrNull { abs(it.width - quality.width) }
            ?: pool.filter { abs(it.width - quality.width) <= tolerance }
                .minByOrNull { abs(it.width - quality.width) }
    }

    fun availableQualities(sizes: List<SizeI>, preferredAspect: Float? = null): List<VideoQuality> =
        VideoQuality.entries.filter { sizeFor(it, sizes, preferredAspect) != null }

    /** Prefer 1080p, then 720p, then whatever else the lens offers. */
    fun defaultQuality(available: List<VideoQuality>): VideoQuality? =
        listOf(VideoQuality.FHD_1080P, VideoQuality.HD_720P, VideoQuality.UHD_4K)
            .firstOrNull { it in available }
            ?: available.firstOrNull()

    /**
     * Frame rates the sensor advertises. Fixed ranges are the only ones that can be pinned
     * exactly, so they win; a device that only lists bounded ranges still offers their
     * candidate rates for preview/record.
     */
    fun supportedFps(ranges: List<IntRange>): List<Int> {
        if (ranges.isEmpty()) return listOf(DEFAULT_FPS)
        val exact = ranges.filter { it.first == it.last }.map { it.first }.toSet()
        val candidateExact = CANDIDATE_FPS.filter { it in exact }
        if (candidateExact.isNotEmpty()) return candidateExact
        val exactOptions = exact.filter { it in 5..240 }.sorted()
        if (exactOptions.isNotEmpty()) return exactOptions
        val containing = CANDIDATE_FPS.filter { fps -> ranges.any { fps in it } }
        if (containing.isNotEmpty()) return containing
        return listOf(ranges.maxOf { it.last }.coerceIn(5, 240))
    }

    /**
     * The AE target range to pin. Camera2 rejects ranges the device did not advertise, so this
     * never invents one; null means "leave the sensor's own frame rate alone".
     */
    fun aeRangeFor(fps: Int, ranges: List<IntRange>): IntRange? {
        if (ranges.isEmpty()) return null
        ranges.firstOrNull { it.first == fps && it.last == fps }?.let { return it }
        return ranges.filter { fps in it }.minByOrNull { it.last - it.first }
    }

    /**
     * Highest advertised preview rate. Prefer an exact fixed range when available; otherwise
     * retain the camera's advertised variable range so it can adapt in dim scenes.
     */
    fun previewAeRange(ranges: List<IntRange>): IntRange? {
        val maxFps = ranges.maxOfOrNull { it.last } ?: return null
        ranges.firstOrNull { it.first == maxFps && it.last == maxFps }?.let { return it }
        return ranges.filter { it.last == maxFps }.minByOrNull { it.first }
    }

    fun fpsFor(requested: Int, supported: List<Int>): Int {
        if (supported.isEmpty()) return DEFAULT_FPS
        if (requested in supported) return requested
        return supported.minByOrNull { abs(it - requested) } ?: DEFAULT_FPS
    }

    /** One frame at [fps], in nanoseconds. */
    fun frameDurationNs(fps: Int): Long = 1_000_000_000L / fps.coerceIn(1, 240)

    /**
     * Whether mirroring the *displayed* image horizontally means flipping the stored frames'
     * horizontal axis.
     *
     * MP4 frames are stored in sensor orientation and the container carries a rotation hint that
     * the player applies. For a 0/180 hint the display axes line up with the stored axes, so the
     * stored X axis is flipped. For a 90/270 hint the image is shown rotated, so display-left/right
     * corresponds to stored-top/bottom and the stored Y axis is flipped instead.
     */
    fun mirrorFlipsX(orientationHint: Int): Boolean =
        OrientationMath.normalizeDegrees(orientationHint) % 180 == 0

    /** See [mirrorFlipsX]; true when the stored vertical axis has to be flipped. */
    fun mirrorFlipsY(orientationHint: Int): Boolean = !mirrorFlipsX(orientationHint)

    /**
     * Manual exposure has to fit inside one video frame, otherwise the encoder gets frames
     * late and the clip plays back at the wrong speed. Brightness is preserved by raising ISO,
     * exactly like the preview clamp for stills.
     */
    fun clampExposure(exposure: ExposureValues, fps: Int, profile: LensProfile): ExposureValues {
        val max = frameDurationNs(fps)
        if (exposure.shutterNs <= max) return exposure
        val factor = exposure.shutterNs.toDouble() / max.toDouble()
        val iso = (exposure.iso * factor).roundToInt()
            .coerceIn(profile.isoRange?.min ?: exposure.iso, profile.isoRange?.max ?: exposure.iso)
        return ExposureValues(iso, max)
    }

    fun isExposureClamped(exposure: ExposureValues, fps: Int): Boolean =
        exposure.shutterNs > frameDurationNs(fps)

    /** ~0.12 bits per pixel per frame: generous enough for H.264 at high quality. */
    fun bitRate(size: SizeI, fps: Int): Int {
        val raw = size.area.toDouble() * fps.coerceAtLeast(1) * 0.12
        return raw.toLong().coerceIn(2_000_000L, 120_000_000L).toInt()
    }

    /** "0:07", "12:34" */
    fun formatDuration(elapsedMs: Long): String {
        val seconds = (elapsedMs / 1000L).coerceAtLeast(0L)
        return String.format(Locale.US, "%d:%02d", seconds / 60L, seconds % 60L)
    }

    fun formatBitRate(bitsPerSecond: Int): String =
        String.format(Locale.US, "%.1f Mbps", bitsPerSecond / 1_000_000f)
}
