package com.fankes.coloros.notify.hook.icon

import org.junit.Assert.assertEquals
import org.junit.Test

class IconRenderSizingTest {

    @Test
    fun `fit preserves aspect ratio without exceeding the bound`() {
        assertEquals(RenderSize(64, 32), fitWithinRenderBounds(4_096, 2_048, 64))
        assertEquals(RenderSize(32, 64), fitWithinRenderBounds(1_000, 2_000, 64))
    }

    @Test
    fun `fit gives unknown intrinsic dimensions a bounded canvas`() {
        assertEquals(RenderSize(64, 64), fitWithinRenderBounds(-1, -1, 64))
        assertEquals(RenderSize(64, 1), fitWithinRenderBounds(10_000, -1, 64))
    }

    @Test
    fun `theme square render size is capped`() {
        assertEquals(192, cappedSquareRenderSize(192, 96, 144, 512))
        assertEquals(512, cappedSquareRenderSize(192, 8_192, 4_096, 512))
    }
}
