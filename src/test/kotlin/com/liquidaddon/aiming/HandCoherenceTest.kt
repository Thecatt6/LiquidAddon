package com.liquidaddon.aiming

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HandCoherenceTest {

    @Test
    fun `an axis the hand is moving with the goal gets all of the help`() {
        assertEquals(1f, HandCoherence.factorAxis(2f, 4f), 1.0e-6f)
        assertEquals(1f, HandCoherence.factorAxis(-2f, -4f), 1.0e-6f)
    }

    @Test
    fun `an axis the hand invents pays the floor`() {
        assertEquals(HandCoherence.FLOOR, HandCoherence.factorAxis(0f, 3f), 1.0e-6f, "hand still on this axis")
        assertEquals(HandCoherence.FLOOR, HandCoherence.factorAxis(2f, -3f), 1.0e-6f, "hand against the goal")
    }

    @Test
    fun `a hand going towards the goal however far participates in full`() {
        // Covering the whole error is the assist's job: direction is what coherence reads.
        assertEquals(1f, HandCoherence.factorAxis(0.2f, 2f), 1.0e-6f)
        assertEquals(1f, HandCoherence.factorAxis(-0.2f, -2f), 1.0e-6f)
    }

    @Test
    fun `a goal of zero on an axis is the caps' business, not coherence's`() {
        assertEquals(1f, HandCoherence.factorAxis(0f, 0f), 1.0e-6f)
        assertEquals(1f, HandCoherence.factorAxis(3f, 0f), 1.0e-6f)
    }
}
