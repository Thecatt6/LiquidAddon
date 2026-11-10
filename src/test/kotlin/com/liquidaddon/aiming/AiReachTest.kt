package com.liquidaddon.aiming

import kotlin.test.Test
import kotlin.test.assertEquals

class AiReachTest {

    @Test
    fun `the range the profiles fight in is trusted whole`() {
        assertEquals(1f, AiReach.blend(blocks = 3.4f, degrees = 2f))
        assertEquals(1f, AiReach.blend(blocks = 6f, degrees = 30f))
    }

    @Test
    fun `a wide swing at close range is not the model's case`() {
        // 30..45°: half way is 37.5.
        assertEquals(0.5f, AiReach.blend(blocks = 3f, degrees = 37.5f), 1.0e-4f)
        assertEquals(0f, AiReach.blend(blocks = 3f, degrees = 45f))
    }

    @Test
    fun `a far target is not either, however small the correction`() {
        // 6..8 blocks: half way is 7.
        assertEquals(0.5f, AiReach.blend(blocks = 7f, degrees = 1f), 1.0e-4f)
        assertEquals(0f, AiReach.blend(blocks = 9f, degrees = 1f))
    }

    @Test
    fun `the stricter of the two ramps wins`() {
        // Far (half) and wide (nothing): the answer is nothing, not a product of the two.
        assertEquals(0f, AiReach.blend(blocks = 7f, degrees = 50f))
        assertEquals(0.5f, AiReach.blend(blocks = 7f, degrees = 20f), 1.0e-4f)
    }

    @Test
    fun `the ramp runs from one to zero without a jump`() {
        val steps = (0..20).map { AiReach.blend(blocks = 6f + it * 0.1f, degrees = 0f) }
        assertEquals(1f, steps.first())
        assertEquals(0f, steps.last())
        for (i in 1 until steps.size) {
            assertEquals(true, steps[i] <= steps[i - 1], "the ramp must not go back up")
            assertEquals(true, steps[i - 1] - steps[i] <= 0.051f, "the ramp must not jump")
        }
    }

    @Test
    fun `a number that is not a number is not a range`() {
        assertEquals(0f, AiReach.blend(blocks = Float.NaN, degrees = 1f))
        assertEquals(0f, AiReach.blend(blocks = 3f, degrees = Float.NaN))
        assertEquals(0f, AiReach.blend(blocks = Float.POSITIVE_INFINITY, degrees = 1f))
    }

    @Test
    fun `within the training regime nothing is taken away`() {
        for (blocks in listOf(0f, 1f, 2f, 4f, 6f)) {
            for (degrees in listOf(0f, 1f, 10f, 30f)) {
                assertEquals(1f, AiReach.blend(blocks, degrees), "blocks=$blocks degrees=$degrees")
            }
        }
    }
}
