package com.fankes.coloros.notify.hook.icon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IconBitmapClassifierTest {

    @Test
    fun `transparent and grayscale pixels remain monochrome`() {
        assertTrue(
            isMonochromePixelBuffer(
                intArrayOf(
                    0x00000000,
                    0x31FF0000,
                    0xFF000000.toInt(),
                    0xFF808080.toInt(),
                    0xFFFFFFFF.toInt(),
                )
            )
        )
    }

    @Test
    fun `single chromatic color with varying alpha is monochrome`() {
        assertTrue(
            isMonochromePixelBuffer(
                intArrayOf(
                    0xFFFB7299.toInt(),
                    0x80FB7299.toInt(),
                    0x40FB7299,
                    0x10FB7299,
                )
            )
        )
    }

    @Test
    fun `small rasterization differences keep a single color monochrome`() {
        assertTrue(
            isMonochromePixelBuffer(
                intArrayOf(
                    0xFFFB7299.toInt(),
                    0xFFF06F90.toInt(),
                )
            )
        )
    }

    @Test
    fun `distinct chromatic colors are not monochrome`() {
        assertFalse(
            isMonochromePixelBuffer(
                intArrayOf(
                    0xFFFB7299.toInt(),
                    0xFF03A9F4.toInt(),
                )
            )
        )
    }

    @Test
    fun `opaque neutral detail is a second color beside a chromatic color`() {
        assertFalse(
            isMonochromePixelBuffer(
                intArrayOf(
                    0xFFFB7299.toInt(),
                    0xFFFFFFFF.toInt(),
                )
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `pixel count cannot exceed its buffer`() {
        isMonochromePixelBuffer(intArrayOf(0), size = 2)
    }
}
