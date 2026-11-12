package com.liquidaddon.aiming

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HandGateTest {

    /** One mouse count at the default sensitivity: the degree value `RotationUtil.gcd` reports. */
    private val count = 0.15f

    @Test
    fun `a moving hand is allowed in full`() {
        val gate = HandGate()
        // The gate opens over the same fade it closes over: four moving ticks, then full.
        repeat(4) { gate.update(movementCounts = 2f, threshold = 1f, fadeTicks = 4) }
        assertEquals(1f, gate.activity)
        assertEquals(1f, gate.update(movementCounts = 2f, threshold = 1f, fadeTicks = 4))
    }

    @Test
    fun `a still hand is refused`() {
        val gate = HandGate()
        assertEquals(0f, gate.update(movementCounts = 0f, threshold = 1f, fadeTicks = 4))
    }

    @Test
    fun `a stopping hand closes the gate on the first still tick`() {
        val gate = HandGate()
        repeat(4) { gate.update(movementCounts = 3f, threshold = 1f, fadeTicks = 4) }

        // A mouse has no inertia: the view stops with the hand, and no coast follows the stop.
        assertEquals(0f, gate.update(0f, 1f, 4))
        assertEquals(0f, gate.update(0f, 1f, 4))
    }

    @Test
    fun `no fade means the gate closes on the first still tick`() {
        val gate = HandGate()
        gate.update(movementCounts = 3f, threshold = 1f, fadeTicks = 0)
        assertEquals(0f, gate.update(movementCounts = 0f, threshold = 1f, fadeTicks = 0))
    }

    @Test
    fun `the hand coming back ramps the gate over the same fade`() {
        val gate = HandGate()
        repeat(4) { gate.update(movementCounts = 3f, threshold = 1f, fadeTicks = 4) }
        repeat(4) { gate.update(movementCounts = 0f, threshold = 1f, fadeTicks = 4) }
        assertEquals(0f, gate.activity)

        // A single count no longer re-arms the assist in full: the gate walks back up.
        assertEquals(0.25f, gate.update(movementCounts = 1f, threshold = 1f, fadeTicks = 4))
        assertEquals(0.50f, gate.update(movementCounts = 1f, threshold = 1f, fadeTicks = 4))
        assertEquals(0.75f, gate.update(movementCounts = 1f, threshold = 1f, fadeTicks = 4))
        assertEquals(1f, gate.update(movementCounts = 1f, threshold = 1f, fadeTicks = 4))
    }

    @Test
    fun `a resting hand decays squared, a moving one unchanged`() {
        val gate = HandGate()
        repeat(4) { gate.update(movementCounts = 3f, threshold = 1f, fadeTicks = 4) }
        assertEquals(1f, gate.restDecay(movementCounts = 3f, threshold = 1f))
        gate.update(movementCounts = 0f, threshold = 1f, fadeTicks = 4)
        assertEquals(0f, gate.activity)
        assertEquals(0f, gate.restDecay(movementCounts = 0f, threshold = 1f), "no coast: the view stops with the hand")
        assertEquals(1f, gate.restDecay(movementCounts = 0f, threshold = 0f), "a gate switched off never decays")
    }

    @Test
    fun `a threshold of zero switches the gate off`() {
        val gate = HandGate()
        // A module that asked for no gate must keep working with the mouse perfectly still.
        repeat(5) { assertEquals(1f, gate.update(movementCounts = 0f, threshold = 0f, fadeTicks = 0)) }
    }

    @Test
    fun `a movement under the threshold does not open the gate`() {
        val gate = HandGate()
        assertEquals(0f, gate.update(movementCounts = 0.9f, threshold = 1f, fadeTicks = 4))
    }

    @Test
    fun `the decay never goes below zero`() {
        val gate = HandGate()
        gate.update(movementCounts = 3f, threshold = 1f, fadeTicks = 2)
        repeat(10) {
            val share = gate.update(movementCounts = 0f, threshold = 1f, fadeTicks = 2)
            assertTrue(share >= 0f, "the share went negative: $share")
        }
        assertEquals(0f, gate.activity)
    }

    @Test
    fun `reset forgets the hand`() {
        val gate = HandGate()
        gate.update(movementCounts = 3f, threshold = 1f, fadeTicks = 4)
        gate.reset()

        assertEquals(0f, gate.activity)
        assertEquals(0f, gate.update(movementCounts = 0f, threshold = 1f, fadeTicks = 4))
    }

    @Test
    fun `movement is counted in mouse counts, on both axes`() {
        // A diagonal movement of one count on each axis is a movement of two counts.
        assertEquals(2f, HandGate.movementCounts(count, -count, count))
        assertEquals(1f, HandGate.movementCounts(0f, count, count))
        assertEquals(0f, HandGate.movementCounts(0f, 0f, count))
        // Half a count is not a movement on this lattice, but it is still half a count.
        assertEquals(0.5f, HandGate.movementCounts(count / 2f, 0f, count))
    }

    @Test
    fun `without a lattice nothing is measured and nothing is blocked`() {
        // No GcdFix, an unknown sensitivity: there is no count to compare against, so the gate must
        // not be the thing that stops the assist.
        assertEquals(
            Float.POSITIVE_INFINITY,
            HandGate.movementCounts(0.4f, 0.1f, gcd = 0f)
        )
    }

    @Test
    fun `a rotation out of nowhere is not a movement`() {
        assertEquals(0f, HandGate.movementCounts(Float.NaN, 0f, count))
    }
}
