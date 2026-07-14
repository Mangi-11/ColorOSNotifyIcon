package com.fankes.coloros.notify.hook.icon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationIconMaskClassifierTest {

    @Test
    fun `single chromatic foreground is a usable alpha glyph`() {
        assertTrue(
            hasUsableNotificationMask(
                pixels = intArrayOf(
                    T, T, T, T, T,
                    T, T, PINK, T, T,
                    T, PINK, PINK, PINK, T,
                    T, T, PINK, T, T,
                    T, T, T, T, T,
                ),
                width = 5,
                height = 5,
            )
        )
    }

    @Test
    fun `minor rasterization differences and alpha variation keep one foreground`() {
        assertTrue(
            hasUsableNotificationMask(
                pixels = intArrayOf(
                    T, T, T, T, T,
                    T, T, 0x80FB7299.toInt(), T, T,
                    T, 0xFFF06F90.toInt(), PINK, 0xFFF77296.toInt(), T,
                    T, T, 0x40FB7299, T, T,
                    T, T, T, T, T,
                ),
                width = 5,
                height = 5,
            )
        )
    }

    @Test
    fun `distinct chromatic colors are not a notification glyph`() {
        assertFalse(
            hasUsableNotificationMask(
                pixels = intArrayOf(
                    T, T, T, T, T,
                    T, T, 0xFF6666FB.toInt(), T, T,
                    T, 0xFF336AB6.toInt(), 0xFF5CA8E9.toInt(), 0xFFFF4081.toInt(), T,
                    T, T, 0xFF4CAF50.toInt(), T, T,
                    T, T, T, T, T,
                ),
                width = 5,
                height = 5,
            )
        )
    }

    @Test
    fun `chromatic foreground mixed with neutral detail is not a glyph`() {
        assertFalse(
            hasUsableNotificationMask(
                pixels = intArrayOf(
                    T, T, T, T, T,
                    T, T, PINK, T, T,
                    T, PINK, O, PINK, T,
                    T, T, PINK, T, T,
                    T, T, T, T, T,
                ),
                width = 5,
                height = 5,
            )
        )
    }

    @Test
    fun `grayscale glyph with transparent background is usable`() {
        assertTrue(
            hasUsableNotificationMask(
                pixels = intArrayOf(
                    T, T, T, T, T,
                    T, O, O, O, T,
                    T, O, O, O, T,
                    T, O, O, O, T,
                    T, T, T, T, T,
                ),
                width = 5,
                height = 5,
            )
        )
    }

    @Test
    fun `large sparse glyph remains usable`() {
        assertTrue(
            hasUsableNotificationMask(
                pixels = intArrayOf(
                    O, T, T, T, O,
                    T, O, T, O, T,
                    T, T, O, T, T,
                    T, O, T, O, T,
                    O, T, T, T, O,
                ),
                width = 5,
                height = 5,
            )
        )
    }

    @Test
    fun `transparent rounded launcher plate is not a glyph`() {
        assertFalse(
            hasUsableNotificationMask(
                pixels = intArrayOf(
                    T, T, O, O, O, O, T, T,
                    T, O, O, O, O, O, O, T,
                    O, O, O, O, O, O, O, O,
                    O, O, O, O, O, O, O, O,
                    O, O, O, O, O, O, O, O,
                    O, O, O, O, O, O, O, O,
                    T, O, O, O, O, O, O, T,
                    T, T, O, O, O, O, T, T,
                ),
                width = 8,
                height = 8,
            )
        )
    }

    @Test
    fun `fully opaque image has no notification background mask`() {
        assertFalse(
            hasUsableNotificationMask(
                pixels = intArrayOf(O, O, O, O),
                width = 2,
                height = 2,
            )
        )
    }

    @Test
    fun `fully transparent image has no visible glyph`() {
        assertFalse(
            hasUsableNotificationMask(
                pixels = intArrayOf(T, T, T, T),
                width = 2,
                height = 2,
            )
        )
    }

    @Test
    fun `low alpha noise does not count as a visible glyph`() {
        assertFalse(
            hasUsableNotificationMask(
                pixels = intArrayOf(T, 0x31000000, T, T),
                width = 2,
                height = 2,
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `dimensions cannot exceed the buffer`() {
        hasUsableNotificationMask(intArrayOf(T), width = 2, height = 1)
    }

    private companion object {
        const val T = 0x00000000
        const val O = -0x1
        const val PINK = -0x48D67
    }
}
