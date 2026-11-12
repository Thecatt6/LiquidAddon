package com.liquidaddon.aiming

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HandCoherenceTest {

    @Test
    fun `a hand going with the goal gets all of the help`() {
        assertEquals(1f, HandCoherence.factor(2f, 1f, 4f, 2f), 1.0e-6f)
    }

    @Test
    fun `a hand going nowhere near the goal gets the floor`() {
        assertEquals(HandCoherence.FLOOR, HandCoherence.factor(2f, 0f, 0f, 3f), 1.0e-6f)
        assertEquals(HandCoherence.FLOOR, HandCoherence.factor(2f, 0f, -2f, 0f), 1.0e-6f, "opposed: the turn check owns the cut")
    }

    @Test
    fun `half agreement sits between floor and full`() {
        val f = HandCoherence.factor(1f, 0f, 1f, 1f)
        assertTrue(f > HandCoherence.FLOOR && f < 1f, "45 degrees apart: $f")
    }

    @Test
    fun `a degenerate direction compares nothing`() {
        assertEquals(1f, HandCoherence.factor(0f, 0f, 2f, 2f), "a still hand is the gate's business")
        assertEquals(1f, HandCoherence.factor(2f, 2f, 0f, 0f), "a reached goal is the rest check's business")
    }
}
