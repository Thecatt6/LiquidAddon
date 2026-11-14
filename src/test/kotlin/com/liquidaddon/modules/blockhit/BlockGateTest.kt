package com.liquidaddon.modules.blockhit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlockGateTest {
    @Test
    fun `delay 0 and delay 1 open the block on the same tick`() {
        assertEquals(0, BlockGate.startIn(0))
        assertEquals(0, BlockGate.startIn(1))
    }

    @Test
    fun `every delay above one pushes the block one whole tick later`() {
        for (delay in 1..5) {
            assertEquals(delay - 1, BlockGate.startIn(delay))
        }
        // A delay below the slider range cannot open the block before the click.
        assertEquals(0, BlockGate.startIn(-3))
    }

    @Test
    fun `a block never opens while the last one is inside the release cooldown`() {
        for (since in 0..1) {
            val reason = BlockGate.reason(since, 2, null, 1, false)
            assertTrue(reason != null && reason.startsWith("released"), "since $since went through")
        }

        assertNull(BlockGate.reason(2, 2, null, 1, false))
        assertNull(BlockGate.reason(5, 2, null, 1, false))
    }

    @Test
    fun `a block never opens while your attack is inside the gap`() {
        for (since in 0..2) {
            val reason = BlockGate.reason(null, 2, since, 3, false)
            assertTrue(reason != null && reason.startsWith("your attack"), "since $since went through")
        }

        assertNull(BlockGate.reason(null, 2, 3, 3, false))
    }

    @Test
    fun `an unchanged aim costs one tick more than the gap`() {
        // Exactly on the gap, aim frozen: refused.
        assertEquals("same rotation as your attack", BlockGate.reason(null, 2, 3, 3, true))
        // One tick past the gap, aim frozen: allowed - the extra tick is only for that one tick.
        assertNull(BlockGate.reason(null, 2, 4, 3, true))
        // Aim moved: the gap alone is enough.
        assertNull(BlockGate.reason(null, 2, 3, 3, false))
    }

    @Test
    fun `gap zero still refuses an attack and a block sharing one tick`() {
        assertEquals("same rotation as your attack", BlockGate.reason(null, 0, 0, 0, true))
        assertNull(BlockGate.reason(null, 0, 0, 0, false))
        assertNull(BlockGate.reason(null, 0, 1, 0, true))
    }

    @Test
    fun `nothing recorded means nothing to wait for`() {
        assertNull(BlockGate.reason(null, 6, null, 4, true))
    }

    @Test
    fun `the release cooldown is looked at before the attack gap`() {
        val reason = BlockGate.reason(0, 2, 0, 3, false)
        assertTrue(reason != null && reason.startsWith("released"), "the wrong rule answered: $reason")
    }

    @Test
    fun `a cooldown below zero cannot hold a block back`() {
        assertNull(BlockGate.reason(0, -1, null, -4, false))
        assertNull(BlockGate.reason(0, 0, null, 0, false))
    }
}
