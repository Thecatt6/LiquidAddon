package com.liquidaddon.aiming

import kotlin.test.Test
import kotlin.test.assertEquals

class AimGoalTest {

    private val box = AimGoal.Box(0.0, 1.0, 0.0, 0.8, 2.8, 0.8)
    private val margin = 0.02

    @Test
    fun `no offset leaves the base where it was`() {
        val p = AimGoal.withOffset(0.4, 2.0, 0.4, 0.0, 0.0, 0.0, box, margin)
        assertEquals(0.4, p.first, 1.0e-9)
        assertEquals(2.0, p.second, 1.0e-9)
        assertEquals(0.4, p.third, 1.0e-9)
    }

    @Test
    fun `an offset that leaves the box stops at the margin`() {
        val p = AimGoal.withOffset(0.4, 2.0, 0.4, 10.0, 10.0, -10.0, box, margin)
        assertEquals(box.maxX - margin, p.first, 1.0e-9)
        assertEquals(box.maxY - margin, p.second, 1.0e-9)
        assertEquals(box.minZ + margin, p.third, 1.0e-9)
    }

    @Test
    fun `an offset inside the box passes through`() {
        val p = AimGoal.withOffset(0.4, 2.0, 0.4, 0.1, -0.2, 0.05, box, margin)
        assertEquals(0.5, p.first, 1.0e-9)
        assertEquals(1.8, p.second, 1.0e-9)
        assertEquals(0.45, p.third, 1.0e-9)
    }

    @Test
    fun `a box narrower than the margin collapses instead of inverting`() {
        val narrow = AimGoal.Box(0.5, 1.0, 0.5, 0.5, 1.01, 0.5)
        val p = AimGoal.withOffset(0.5, 1.005, 0.5, 0.0, 0.0, 0.0, narrow, margin)
        // No point of the box is `margin` inside it: the clamp collapses to its low edge on the
        // thin axes, and the height axis keeps its ordinary range.
        assertEquals(narrow.minX + margin, p.first, 1.0e-9)
        assertEquals(narrow.minY + margin, p.second, 1.0e-9)
        assertEquals(narrow.minZ + margin, p.third, 1.0e-9)
    }
}
