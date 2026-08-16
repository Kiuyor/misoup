package com.misoup.app.splitting.candidates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CropCalculatorTest {

    @Test
    fun `box fully inside page clamps to itself`() {
        val r = CropCalculator.clamp(BoundingBox(10f, 20f, 100f, 50f), 500, 500)
        assertEquals(IntRect(10, 20, 100, 50), r)
    }

    @Test
    fun `box partially outside page is clamped`() {
        val r = CropCalculator.clamp(BoundingBox(-10f, -20f, 100f, 50f), 500, 500)
        assertEquals(IntRect(0, 0, 90, 30), r)
    }

    @Test
    fun `box beyond right bottom is clamped to page`() {
        val r = CropCalculator.clamp(BoundingBox(490f, 490f, 100f, 100f), 500, 500)
        assertEquals(IntRect(490, 490, 10, 10), r)
    }

    @Test
    fun `box completely outside page returns null`() {
        assertNull(CropCalculator.clamp(BoundingBox(600f, 600f, 100f, 100f), 500, 500))
        assertNull(CropCalculator.clamp(BoundingBox(-200f, -200f, 50f, 50f), 500, 500))
    }

    @Test
    fun `zero or negative page size returns null`() {
        assertNull(CropCalculator.clamp(BoundingBox(0f, 0f, 10f, 10f), 0, 500))
        assertNull(CropCalculator.clamp(BoundingBox(0f, 0f, 10f, 10f), 500, 0))
        assertNull(CropCalculator.clamp(BoundingBox(0f, 0f, 10f, 10f), -1, 500))
    }

    @Test
    fun `zero width or height box returns null`() {
        assertNull(CropCalculator.clamp(BoundingBox(0f, 0f, 0f, 50f), 500, 500))
        assertNull(CropCalculator.clamp(BoundingBox(0f, 0f, 50f, 0f), 500, 500))
    }

    @Test
    fun `float bounds truncate toward zero for pixel conversion`() {
        val r = CropCalculator.clamp(BoundingBox(0.9f, 1.1f, 99.9f, 50.7f), 500, 500)
        // left=0, top=1, right=100, bottom=51 → w=100 h=50
        assertEquals(IntRect(0, 1, 100, 50), r)
    }
}
