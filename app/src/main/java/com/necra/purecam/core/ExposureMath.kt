package com.necra.purecam.core

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class ExposureValues(val iso: Int, val shutterNs: Long)

object ExposureMath {

    /** Longest shutter used for the live preview; longer still exposures are clamped in preview only. */
    const val MAX_PREVIEW_EXPOSURE_NS: Long = 100_000_000L

    const val DEFAULT_FRAME_NS: Long = 33_333_333L

    /** 1/3-stop ISO steps from the device minimum to its maximum. */
    fun isoStops(range: RangeI): List<Int> {
        val values = ArrayList<Int>(48)
        val min = range.min.coerceAtLeast(1)
        var i = 0
        while (i < 160) {
            val v = (min * 2.0.pow(i / 3.0)).roundToInt().coerceAtLeast(min)
            if (v > range.max) break
            if (values.isEmpty() || values.last() != v) values.add(v)
            i++
        }
        if (values.isEmpty()) values.add(range.clamp(min))
        if (values.last() != range.max) values.add(range.max)
        return values
    }

    /** 1/3-stop shutter steps, from the fastest to the longest supported exposure. */
    fun shutterStops(range: RangeL): List<Long> {
        val values = ArrayList<Long>(96)
        val min = range.min.coerceAtLeast(1L)
        var i = 0
        while (i < 320) {
            val v = (min * 2.0.pow(i / 3.0)).roundToLong().coerceAtLeast(min)
            if (v > range.max) break
            if (values.isEmpty() || values.last() != v) values.add(v)
            i++
        }
        if (values.isEmpty()) values.add(range.clamp(min))
        if (values.last() != range.max) values.add(range.max)
        return values
    }

    fun nearestIndex(values: List<Long>, target: Long): Int {
        if (values.isEmpty()) return 0
        var best = 0
        var bestDiff = Long.MAX_VALUE
        for (i in values.indices) {
            val d = abs(values[i] - target)
            if (d < bestDiff) {
                bestDiff = d
                best = i
            }
        }
        return best
    }

    fun nearestIsoIndex(values: List<Int>, target: Int): Int {
        if (values.isEmpty()) return 0
        var best = 0
        var bestDiff = Int.MAX_VALUE
        for (i in values.indices) {
            val d = abs(values[i] - target)
            if (d < bestDiff) {
                bestDiff = d
                best = i
            }
        }
        return best
    }

    fun formatShutter(ns: Long): String {
        if (ns <= 0L) return "--"
        if (ns < 1_000_000_000L) {
            val denominator = (1_000_000_000.0 / ns.toDouble()).roundToLong().coerceAtLeast(1L)
            return "1/$denominator"
        }
        val seconds = ns / 1_000_000_000.0
        val rounded = seconds.roundToInt()
        return if (abs(seconds - rounded) < 0.05) "$rounded\"" else String.format("%.1f\"", seconds)
    }

    fun formatIso(iso: Int): String = iso.toString()

    fun formatExposure(iso: Int, shutterNs: Long): String = "ISO $iso  ${formatShutter(shutterNs)}"

    /**
     * Preview must not wait multiple seconds per frame. Long stills are previewed with the
     * shutter capped and ISO raised to keep roughly the same brightness. Still capture always
     * uses the exact user values.
     */
    fun previewValues(exposure: ExposureValues, profile: LensProfile): ExposureValues {
        if (exposure.shutterNs <= MAX_PREVIEW_EXPOSURE_NS) return exposure
        val factor = exposure.shutterNs.toDouble() / MAX_PREVIEW_EXPOSURE_NS.toDouble()
        val iso = (exposure.iso * factor).roundToInt()
            .coerceIn(profile.isoRange?.min ?: exposure.iso, profile.isoRange?.max ?: exposure.iso)
        return ExposureValues(iso, MAX_PREVIEW_EXPOSURE_NS)
    }

    fun isPreviewApproximate(exposure: ExposureValues): Boolean =
        exposure.shutterNs > MAX_PREVIEW_EXPOSURE_NS

    /** Initial manual values: 1/60 s and ISO 100, clamped into the device range. */
    fun initialManual(profile: LensProfile): ExposureValues {
        val iso = profile.isoRange?.clamp(100) ?: 100
        val shutter = profile.exposureRange?.clamp(16_666_667L) ?: 16_666_667L
        return ExposureValues(iso, shutter)
    }

    /** Frame duration must be at least the exposure time and is bounded by the device maximum. */
    fun frameDurationNs(
        shutterNs: Long,
        maxFrameDurationNs: Long,
        minFrameNs: Long = DEFAULT_FRAME_NS
    ): Long {
        val minimum = max(minFrameNs, shutterNs)
        val max = maxFrameDurationNs.coerceAtLeast(shutterNs)
        return minimum.coerceAtMost(max)
    }

    fun estimatedCaptureTimeoutMs(shutterNs: Long): Long =
        (shutterNs / 1_000_000L + 3_000L).coerceAtLeast(3_000L)

    /** Number of full stops between two exposures, for UI hints. */
    fun stopsBetween(aNs: Long, bNs: Long): Double {
        if (aNs <= 0L || bNs <= 0L) return 0.0
        return ln(aNs.toDouble() / bNs.toDouble()) / ln(2.0)
    }
}
