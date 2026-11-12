package com.liquidaddon.aiming

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoalAnchorTest {

    // A standing player: box from 0 to 1.8, eyes at 1.62.
    private val minY = 0.0
    private val maxY = 1.8
    private val eyeY = 1.62

    @Test
    fun `the draws land on the centre and on the eyes at the ends`() {
        assertEquals(0.9, GoalAnchor.anchorY(minY, maxY, eyeY, 0f), 1.0e-9)
        assertEquals(eyeY, GoalAnchor.anchorY(minY, maxY, eyeY, 1f), 1.0e-9)
    }

    @Test
    fun `a draw in between sits on the segment, and the draw clamps`() {
        assertEquals(1.26, GoalAnchor.anchorY(minY, maxY, eyeY, 0.5f), 1.0e-9)
        assertEquals(0.9, GoalAnchor.anchorY(minY, maxY, eyeY, -4f), 1.0e-9)
        assertEquals(eyeY, GoalAnchor.anchorY(minY, maxY, eyeY, 9f), 1.0e-9)
    }

    @Test
    fun `two draws spread the band over the box, not over a fifth of it`() {
        val low = GoalAnchor.anchorY(minY, maxY, eyeY, 0.1f)
        val high = GoalAnchor.anchorY(minY, maxY, eyeY, 0.9f)
        assertTrue(high - low > 0.3 * (maxY - minY), "engagements must disagree in height: $low vs $high")
        assertTrue(low > minY && high < eyeY + 1.0e-9, "the anchor stays on the body")
    }
}
