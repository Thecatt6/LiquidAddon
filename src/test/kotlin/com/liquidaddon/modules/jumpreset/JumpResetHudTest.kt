package com.liquidaddon.modules.jumpreset

import kotlin.test.Test
import kotlin.test.assertEquals

class JumpResetHudTest {

    @Test
    fun `the probability is there before anything happens`() {
        assertEquals("100%", JumpResetHud.tag(chance = 100, perfect = 0, attempts = 0))
        assertEquals("60%", JumpResetHud.tag(chance = 60, perfect = 0, attempts = 0))
    }

    @Test
    fun `a chance of zero is a number, not an empty tag`() {
        // A module that never fires is exactly what somebody wants to see in the HUD.
        assertEquals("0%", JumpResetHud.tag(chance = 0, perfect = 0, attempts = 0))
    }

    @Test
    fun `the tally joins the probability once there are jumps`() {
        assertEquals("100% 3/4", JumpResetHud.tag(chance = 100, perfect = 3, attempts = 4))
        assertEquals("60% 0/1", JumpResetHud.tag(chance = 60, perfect = 0, attempts = 1))
    }

    @Test
    fun `a jump that is not perfect still counts as a jump`() {
        // Every jump is an attempt, only the well-timed ones are perfect: 0/5 is the honest read of
        // a module that is jumping and never resetting (no sprint, most likely).
        assertEquals("85% 0/5", JumpResetHud.tag(chance = 85, perfect = 0, attempts = 5))
    }

    @Test
    fun `a perfect jump is never more than a jump`() {
        assertEquals("60% 4/4", JumpResetHud.tag(chance = 60, perfect = 9, attempts = 4))
    }

    @Test
    fun `a percentage outside its range is clamped`() {
        // A hand-edited config file must not be able to print "140%".
        assertEquals("100%", JumpResetHud.tag(chance = 140, perfect = 0, attempts = 0))
        assertEquals("0%", JumpResetHud.tag(chance = -5, perfect = 0, attempts = 0))
    }

    @Test
    fun `a negative count is not a jump`() {
        assertEquals("70%", JumpResetHud.tag(chance = 70, perfect = -3, attempts = -4))
    }
}
