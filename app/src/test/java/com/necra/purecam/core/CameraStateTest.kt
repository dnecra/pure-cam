package com.necra.purecam.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraStateTest {

    @Test
    fun defaults_areAutoWithFlashOffAndZoomOne() {
        val lens = TestProfiles.lens()

        val state = CameraState.defaults(lens)

        assertEquals(lens, state.lens)
        assertFalse(state.flash)
        assertEquals(1f, state.zoom, 0f)
        assertEquals(ExposureMode.Auto, state.exposure)
        assertEquals(FocusMode.Auto, state.focus)
        assertEquals(WhiteBalanceMode.Auto, state.wb)
    }

    @Test
    fun clamped_boundsManualExposureAndFocus() {
        val lens = TestProfiles.lens(
            isoRange = RangeI(100, 800),
            exposureRange = RangeL(1_000_000L, 500_000_000L),
            minFocusDiopters = 5f
        )
        val state = TestProfiles.manualState(
            lens,
            iso = 12_800,
            shutterNs = 10_000_000_000L,
            diopters = 50f
        )

        val clamped = state.clamped()

        assertEquals(ExposureMode.Manual(800, 500_000_000L), clamped.exposure)
        assertEquals(FocusMode.Manual(5f), clamped.focus)
    }

    @Test
    fun clamped_revertsManualModesWhenUnsupported() {
        val lens = TestProfiles.lens(
            manualSensor = false,
            minFocusDiopters = 5f
        )
        val state = TestProfiles.manualState(lens, iso = 400, shutterNs = 8_000_000L, diopters = 1f)

        val clamped = state.clamped()

        assertEquals(ExposureMode.Auto, clamped.exposure)
        assertEquals(FocusMode.Auto, clamped.focus)
    }

    @Test
    fun clamped_revertsManualFocusForFixedFocusLens() {
        val lens = TestProfiles.lens(minFocusDiopters = 0f)
        val state = TestProfiles.manualState(lens, diopters = 1f)

        assertEquals(FocusMode.Auto, state.clamped().focus)
    }

    @Test
    fun clamped_dropsUnsupportedPresetAndKelvin() {
        val lens = TestProfiles.lens(
            awbPresets = setOf(WbPreset.DAYLIGHT),
            manualPostProcessing = false
        )

        val preset = TestProfiles.autoState(lens, wb = WhiteBalanceMode.Preset(WbPreset.SHADE)).clamped()
        val kelvin = TestProfiles.autoState(lens, wb = WhiteBalanceMode.Kelvin(5000)).clamped()

        assertEquals(WhiteBalanceMode.Auto, preset.wb)
        assertEquals(WhiteBalanceMode.Auto, kelvin.wb)
    }

    @Test
    fun clamped_keepsSupportedPresetAndRoundsKelvin() {
        val lens = TestProfiles.lens(awbPresets = setOf(WbPreset.DAYLIGHT))

        val preset = TestProfiles.autoState(lens, wb = WhiteBalanceMode.Preset(WbPreset.DAYLIGHT)).clamped()
        val kelvin = TestProfiles.autoState(lens, wb = WhiteBalanceMode.Kelvin(5012)).clamped()

        assertEquals(WhiteBalanceMode.Preset(WbPreset.DAYLIGHT), preset.wb)
        assertEquals(WhiteBalanceMode.Kelvin(5000), kelvin.wb)
    }

    @Test
    fun clamped_boundsFlashAndZoom() {
        val lens = TestProfiles.lens(flashAvailable = false, maxZoom = 3f)
        val state = TestProfiles.autoState(lens, flash = true, zoom = 9f)

        val clamped = state.clamped()

        assertFalse(clamped.flash)
        assertEquals(3f, clamped.zoom, 0f)
    }

    @Test
    fun withLens_resetsZoomAndFocusAndCarriesExposure() {
        val oldLens = TestProfiles.lens(id = "old", isoRange = RangeI(100, 3200))
        val newLens = TestProfiles.lens(
            id = "new",
            isoRange = RangeI(200, 800),
            exposureRange = RangeL(1_000_000L, 100_000_000L)
        )
        val state = TestProfiles.manualState(
            oldLens,
            iso = 1600,
            shutterNs = 500_000_000L,
            diopters = 2f,
            zoom = 3f,
            wb = WhiteBalanceMode.Preset(WbPreset.DAYLIGHT)
        )

        val switched = state.withLens(newLens)

        assertEquals(newLens, switched.lens)
        assertEquals(1f, switched.zoom, 0f)
        assertEquals(FocusMode.Auto, switched.focus)
        assertEquals(ExposureMode.Manual(800, 100_000_000L), switched.exposure)
        assertEquals(WhiteBalanceMode.Preset(WbPreset.DAYLIGHT), switched.wb)
    }

    @Test
    fun capabilityFlags_followProfile() {
        val full = TestProfiles.lens()
        val limited = TestProfiles.lens(
            manualSensor = false,
            manualPostProcessing = false,
            colorTransform1 = null,
            colorTransform2 = null,
            minFocusDiopters = 0f
        )

        assertTrue(full.supportsManualExposure)
        assertTrue(full.supportsManualFocus)
        assertTrue(full.supportsKelvin)
        assertTrue(full.hasMultipleNativeZooms)
        assertTrue(full.sensorIsRotated)

        assertFalse(limited.supportsManualExposure)
        assertFalse(limited.supportsManualFocus)
        assertFalse(limited.supportsKelvin)
    }
}
