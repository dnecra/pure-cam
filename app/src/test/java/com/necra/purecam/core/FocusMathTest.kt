package com.necra.purecam.core

import org.junit.Assert.assertEquals
import org.junit.Test

class FocusMathTest {

    @Test
    fun invertRotation_matchesDisplayRotation() {
        assertEquals(0.25f to 0.75f, FocusMath.invertRotation(0.25f, 0.75f, 0))
        assertEquals(0.75f to 0.75f, FocusMath.invertRotation(0.25f, 0.75f, 90))
        assertEquals(0.75f to 0.25f, FocusMath.invertRotation(0.25f, 0.75f, 180))
        assertEquals(0.25f to 0.25f, FocusMath.invertRotation(0.25f, 0.75f, 270))
    }

    @Test
    fun invertRotation_normalizesOutOfRangeAngles() {
        assertEquals(FocusMath.invertRotation(0.2f, 0.4f, 90), FocusMath.invertRotation(0.2f, 0.4f, 450))
        assertEquals(FocusMath.invertRotation(0.2f, 0.4f, 270), FocusMath.invertRotation(0.2f, 0.4f, -90))
    }

    @Test
    fun mapTap_centerMapsToCropCenterForEveryOrientation() {
        val active = RectI(0, 0, 4000, 3000)
        val crop = RectI(1000, 750, 3000, 2250)

        for (orientation in listOf(0, 90, 180, 270)) {
            val point = FocusMath.mapTap(
                viewX = 200f,
                viewY = 150f,
                viewWidth = 400,
                viewHeight = 300,
                contentAspect = 4f / 3f,
                sensorOrientation = orientation,
                crop = crop,
                activeArray = active
            )
            assertEquals(2000, point.x)
            assertEquals(1500, point.y)
        }
    }

    @Test
    fun mapTap_cornerFollowsSensorRotation() {
        val active = RectI(0, 0, 4000, 3000)
        val crop = active

        assertEquals(
            FocusPoint(0, 0),
            FocusMath.mapTap(0f, 0f, 400, 300, 4f / 3f, 0, crop, active)
        )
        assertEquals(
            FocusPoint(0, 3000),
            FocusMath.mapTap(0f, 0f, 400, 300, 4f / 3f, 90, crop, active)
        )
        assertEquals(
            FocusPoint(4000, 3000),
            FocusMath.mapTap(0f, 0f, 400, 300, 4f / 3f, 180, crop, active)
        )
        assertEquals(
            FocusPoint(4000, 0),
            FocusMath.mapTap(0f, 0f, 400, 300, 4f / 3f, 270, crop, active)
        )
    }

    @Test
    fun mapTap_offsetsCropInsideActiveArray() {
        val active = RectI(100, 50, 4100, 3050)
        val crop = RectI(1100, 800, 3100, 2300)

        val point = FocusMath.mapTap(
            viewX = 200f,
            viewY = 150f,
            viewWidth = 400,
            viewHeight = 300,
            contentAspect = 4f / 3f,
            sensorOrientation = 0,
            crop = crop,
            activeArray = active
        )

        assertEquals(crop.centerX, point.x)
        assertEquals(crop.centerY, point.y)
    }

    @Test
    fun mapTap_correctsCenterCropInFullMode() {
        val active = RectI(0, 0, 4000, 3000)
        val crop = active

        val point = FocusMath.mapTap(
            viewX = 0f,
            viewY = 150f,
            viewWidth = 400,
            viewHeight = 300,
            contentAspect = 16f / 9f,
            sensorOrientation = 0,
            crop = crop,
            activeArray = active
        )

        assertEquals(500, point.x)
        assertEquals(1500, point.y)
    }

    @Test
    fun mapTap_correctsVerticalCenterCrop() {
        val active = RectI(0, 0, 4000, 3000)

        val point = FocusMath.mapTap(
            viewX = 200f,
            viewY = 0f,
            viewWidth = 400,
            viewHeight = 225,
            contentAspect = 4f / 3f,
            sensorOrientation = 0,
            crop = active,
            activeArray = active
        )

        assertEquals(2000, point.x)
        assertEquals(375, point.y)
    }

    @Test
    fun mapTap_clampsViewCoordinatesAndHandlesEmptyCrop() {
        val active = RectI(0, 0, 4000, 3000)

        val outside = FocusMath.mapTap(
            viewX = -50f,
            viewY = 999f,
            viewWidth = 400,
            viewHeight = 300,
            contentAspect = 4f / 3f,
            sensorOrientation = 0,
            crop = active,
            activeArray = active
        )
        assertEquals(0, outside.x)
        assertEquals(3000, outside.y)

        val empty = FocusMath.mapTap(
            viewX = 100f,
            viewY = 100f,
            viewWidth = 0,
            viewHeight = 0,
            contentAspect = 4f / 3f,
            sensorOrientation = 0,
            crop = RectI(10, 20, 10, 20),
            activeArray = active
        )
        assertEquals(10, empty.x)
        assertEquals(20, empty.y)
    }

    @Test
    fun meteringRect_isTenPercentOfCropAndClamped() {
        val active = RectI(0, 0, 4000, 3000)
        val crop = active

        assertEquals(
            RectI(1800, 1350, 2200, 1650),
            FocusMath.meteringRect(FocusPoint(2000, 1500), crop, active)
        )
        assertEquals(
            RectI(0, 0, 200, 150),
            FocusMath.meteringRect(FocusPoint(0, 0), crop, active)
        )
        assertEquals(
            RectI(3800, 2850, 4000, 3000),
            FocusMath.meteringRect(FocusPoint(4000, 3000), crop, active)
        )
    }

    @Test
    fun meteringRect_hasMinimumSize() {
        val active = RectI(0, 0, 4000, 3000)
        val crop = RectI(100, 100, 101, 101)

        val rect = FocusMath.meteringRect(FocusPoint(100, 100), crop, active)

        assertEquals(16, rect.width)
        assertEquals(16, rect.height)
    }
}
