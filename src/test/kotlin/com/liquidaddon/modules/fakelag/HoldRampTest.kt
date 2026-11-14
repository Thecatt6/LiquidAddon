package com.liquidaddon.modules.fakelag

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HoldRampTest {
    @Test
    fun `the hold climbs four ticks at a time`() {
        assertEquals(4, HoldRamp.next(0, 8, 40))
        assertEquals(8, HoldRamp.next(4, 8, 40))
        assertEquals(8, HoldRamp.next(8, 8, 40))
    }

    @Test
    fun `the hold never climbs past what is wanted`() {
        assertEquals(2, HoldRamp.next(0, 2, 40))
        assertEquals(1, HoldRamp.next(0, 1, 40))
        assertEquals(0, HoldRamp.next(0, 0, 40))
    }

    @Test
    fun `the hold comes down one tick per tick and stops at zero`() {
        var held = 8
        val seen = mutableListOf(held)

        repeat(12) {
            held = HoldRamp.next(held, 0, 40)
            seen += held
        }

        assertEquals(listOf(8, 7, 6, 5, 4, 3, 2, 1, 0, 0, 0, 0, 0), seen)
    }

    @Test
    fun `losing ground is always slower than gaining it`() {
        // The same distance, walked in both directions: up in one tick, down in four.
        assertEquals(4, HoldRamp.next(0, 4, 40))

        var held = 4
        var ticks = 0
        while (held > 0) {
            held = HoldRamp.next(held, 0, 40)
            ticks++
        }
        assertEquals(4, ticks)
    }

    @Test
    fun `a hold outside the cap is clamped before the ramp, not trusted`() {
        // Wanting more than the cap still only gains UP_TICKS per tick, and stops at the cap.
        assertEquals(4, HoldRamp.next(0, 999, 40))
        assertEquals(40, HoldRamp.next(36, 999, 40))
        assertEquals(40, HoldRamp.next(45, 45, 40))
        // Coming down from above the cap starts at the cap.
        assertEquals(39, HoldRamp.next(99, 0, 40))
        assertEquals(0, HoldRamp.next(-3, 0, 40))
        assertEquals(0, HoldRamp.next(0, -12, 40))
        // A cap below zero cannot hold anything.
        assertEquals(0, HoldRamp.next(5, 5, 0))
        assertEquals(0, HoldRamp.next(5, 5, -8))
    }

    @Test
    fun `the cutoff keeps the newest held ticks plus one in the queue`() {
        // Five known tick starts, holding two of them: the three newest stay, the two oldest leave.
        assertEquals(2, HoldRamp.cutoffIndex(5, 2))
        // Holding nothing releases up to the tick we are inside.
        assertEquals(4, HoldRamp.cutoffIndex(5, 0))
        // Holding all of them releases nothing.
        assertEquals(0, HoldRamp.cutoffIndex(5, 4))
    }

    @Test
    fun `an empty history or a hold longer than the history releases nothing`() {
        assertTrue(HoldRamp.cutoffIndex(0, 0) < 0)
        assertTrue(HoldRamp.cutoffIndex(2, 5) < 0)
        assertTrue(HoldRamp.cutoffIndex(-4, 0) < 0)
    }

    @Test
    fun `a packet is queued while the hold is in force`() {
        assertEquals(HeldPacket.QUEUE, HoldRamp.packet(holding = true, heldTicks = 3, shouldHold = true))
        // Even at the end of the drain: as long as holding is allowed, nothing is let out early.
        assertEquals(HeldPacket.QUEUE, HoldRamp.packet(holding = true, heldTicks = 0, shouldHold = true))
        assertEquals(HeldPacket.QUEUE, HoldRamp.packet(holding = false, heldTicks = 0, shouldHold = true))
    }

    @Test
    fun `a packet passes when nothing of ours is queued`() {
        assertEquals(HeldPacket.PASS, HoldRamp.packet(holding = false, heldTicks = 0, shouldHold = false))
        assertEquals(HeldPacket.PASS, HoldRamp.packet(holding = false, heldTicks = 6, shouldHold = false))
    }

    @Test
    fun `the drain queues packets until the last held tick is out`() {
        // This is the rule that keeps the order: a packet that arrives while older ticks are still
        // held must not overtake them, even though holding is no longer allowed.
        for (held in 1..8) {
            assertEquals(
                HeldPacket.QUEUE,
                HoldRamp.packet(holding = true, heldTicks = held, shouldHold = false),
                "held $held let a packet overtake the queue",
            )
        }
    }

    @Test
    fun `the drain ends with one flush of the tick we are inside`() {
        assertEquals(HeldPacket.FLUSH_ALL, HoldRamp.packet(holding = true, heldTicks = 0, shouldHold = false))
        assertEquals(HeldPacket.FLUSH_ALL, HoldRamp.packet(holding = true, heldTicks = -1, shouldHold = false))
    }
}
