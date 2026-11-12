package com.liquidaddon.aiming

import kotlin.test.Test
import kotlin.test.assertEquals

class DrawnTest {

    @Test
    fun `the draw walks the span and clamps outside it`() {
        assertEquals(225f, Drawn.span(450f, 0f, 0.5f, 1.3f), 1.0e-6f)
        assertEquals(405f, Drawn.span(450f, 0.5f, 0.5f, 1.3f), 1.0e-6f)
        assertEquals(585f, Drawn.span(450f, 1f, 0.5f, 1.3f), 1.0e-6f)
        assertEquals(225f, Drawn.span(450f, -2f, 0.5f, 1.3f), 1.0e-6f)
        assertEquals(585f, Drawn.span(450f, 4f, 0.5f, 1.3f), 1.0e-6f)
    }

    @Test
    fun `two draws apart give two values apart`() {
        val low = Drawn.span(1f, 0.1f, 0.6f, 1.6f)
        val high = Drawn.span(1f, 0.9f, 0.6f, 1.6f)
        assertEquals(0.7f, low, 1.0e-6f)
        assertEquals(1.5f, high, 1.0e-6f)
    }
}
