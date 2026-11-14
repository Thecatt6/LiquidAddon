package com.liquidaddon.modules.triggerbot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReachArithmeticTest {
    @Test
    fun `both stale positions are paid for, ours and the target's`() {
        // One tick of our own delay at 0.2 blocks per tick, two ticks of theirs at 0.3.
        val drift = ReachArithmetic.drift(
            selfClosing = 0.2,
            targetClosing = 0.3,
            myPingMs = 100,
            theirPingMs = 200,
            percent = 100,
        )

        assertEquals(0.2 * 1.0 + 0.3 * 2.0, drift, 1.0e-9)
    }

    @Test
    fun `nobody closing means no drift, whatever the ping says`() {
        assertEquals(0.0, ReachArithmetic.drift(0.0, 0.0, 500, 500, 100), 1.0e-9)
        assertEquals(0.0, ReachArithmetic.drift(0.0, 0.0, 0, 300, 100), 1.0e-9)
        // One side closing still pays for that side, and only for it.
        assertEquals(0.4 * 3.0, ReachArithmetic.drift(0.0, 0.4, 0, 300, 100), 1.0e-9)
        // A ping of zero on both sides is a LAN server: nothing is stale, nothing is given back.
        assertEquals(0.0, ReachArithmetic.drift(0.5, 0.5, 0, 0, 100), 1.0e-9)
    }

    @Test
    fun `a ping outside zero to half a second is clamped, not trusted`() {
        assertEquals(5.0, ReachArithmetic.delayTicks(500), 1.0e-9)
        assertEquals(5.0, ReachArithmetic.delayTicks(4000), 1.0e-9)
        assertEquals(0.0, ReachArithmetic.delayTicks(-80), 1.0e-9)
        assertEquals(1.5, ReachArithmetic.delayTicks(150), 1.0e-9)

        val clamped = ReachArithmetic.drift(0.1, 0.1, 9000, -50, 100)
        val honest = ReachArithmetic.drift(0.1, 0.1, 500, 0, 100)
        assertEquals(honest, clamped, 1.0e-9)
    }

    @Test
    fun `DesyncPercent scales the estimate and never makes it negative`() {
        val full = ReachArithmetic.drift(0.2, 0.2, 200, 200, 100)

        assertEquals(full / 2, ReachArithmetic.drift(0.2, 0.2, 200, 200, 50), 1.0e-9)
        assertEquals(full * 2, ReachArithmetic.drift(0.2, 0.2, 200, 200, 200), 1.0e-9)
        assertEquals(0.0, ReachArithmetic.drift(0.2, 0.2, 200, 200, 0), 1.0e-9)
        // Outside the slider range the percent is clamped, so a bad config cannot flip the sign.
        assertEquals(0.0, ReachArithmetic.drift(0.2, 0.2, 200, 200, -400), 1.0e-9)
        assertEquals(full * 2, ReachArithmetic.drift(0.2, 0.2, 200, 200, 900), 1.0e-9)
    }

    @Test
    fun `the allowance is never negative and never above one block`() {
        assertEquals(0.0, ReachArithmetic.allowance(desync = false, drift = 0.9, inVehicle = false, 0.25), 1.0e-9)
        assertEquals(0.9, ReachArithmetic.allowance(desync = true, drift = 0.9, inVehicle = false, 0.25), 1.0e-9)
        assertEquals(1.0, ReachArithmetic.allowance(desync = true, drift = 4.0, inVehicle = false, 0.25), 1.0e-9)
        assertEquals(1.0, ReachArithmetic.allowance(desync = true, drift = 0.9, inVehicle = true, 0.25), 1.0e-9)
        assertEquals(0.25, ReachArithmetic.allowance(desync = true, drift = 0.0, inVehicle = true, 0.25), 1.0e-9)
        assertEquals(0.0, ReachArithmetic.allowance(desync = true, drift = -3.0, inVehicle = false, -1.0), 1.0e-9)
    }

    @Test
    fun `the ceiling is the smallest honest distance, minus the safety gap`() {
        assertEquals(
            3.0 - 0.05,
            ReachArithmetic.ceiling(base = 3.4, serverReach = 3.0, interactionRange = 4.0, overReach = 0.0, safetyGap = 0.05),
            1.0e-9,
        )
        // A Reach setting that widens the interaction range cannot lift the ceiling.
        assertEquals(
            3.0 - 0.05,
            ReachArithmetic.ceiling(base = 3.4, serverReach = 3.0, interactionRange = 6.0, overReach = 0.0, safetyGap = 0.05),
            1.0e-9,
        )
        // OverReach is the only thing that adds, and it is zero by default.
        assertEquals(
            3.0 + 0.2 - 0.05,
            ReachArithmetic.ceiling(base = 3.4, serverReach = 3.0, interactionRange = 4.0, overReach = 0.2, safetyGap = 0.05),
            1.0e-9,
        )
    }

    @Test
    fun `with ServerSafe an attack is never farther than the smallest honest distance`() {
        for (base in listOf(1.0, 3.0, 3.4, 6.0)) {
            for (serverReach in listOf(3.0, 3.5)) {
                for (interaction in listOf(3.0, 4.5, 6.0)) {
                    for (allowance in listOf(0.0, 0.3, 1.0, 2.5)) {
                        val limit = ReachArithmetic.attackLimit(
                            serverSafe = true,
                            base = base,
                            serverReach = serverReach,
                            interactionRange = interaction,
                            overReach = 0.0,
                            safetyGap = 0.05,
                            allowance = allowance,
                            earlyMargin = 0.9,
                        )
                        val honest = minOf(base, serverReach, interaction)

                        assertTrue(
                            limit <= honest,
                            "base $base reach $serverReach interaction $interaction allowance $allowance " +
                                "attacked at $limit, past the honest $honest",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `without ServerSafe the attack happens at the base plus the cushion`() {
        assertEquals(
            3.2 + 0.55,
            ReachArithmetic.attackLimit(
                serverSafe = false, base = 3.2, serverReach = 3.0, interactionRange = 3.0,
                overReach = 0.0, safetyGap = 0.05, allowance = 1.0, earlyMargin = 0.55,
            ),
            1.0e-9,
        )
        // The early cushion is never subtracted from the limit: it cannot make it negative.
        assertEquals(
            3.0,
            ReachArithmetic.attackLimit(
                serverSafe = false, base = 3.0, serverReach = 3.0, interactionRange = 3.0,
                overReach = 0.0, safetyGap = 0.05, allowance = 1.0, earlyMargin = -2.0,
            ),
            1.0e-9,
        )
    }

    @Test
    fun `the limit never drops below the minimum trigger distance`() {
        val limit = ReachArithmetic.attackLimit(
            serverSafe = true, base = 0.2, serverReach = 0.1, interactionRange = 0.3,
            overReach = 0.0, safetyGap = 0.5, allowance = 1.0, earlyMargin = 0.0,
        )

        assertEquals(ReachArithmetic.MIN_TRIGGER, limit, 1.0e-9)
    }

    @Test
    fun `the closing speed is a decaying maximum, not a snapshot`() {
        // Standing still one tick does not erase the speed the server still sees.
        assertEquals(0.4, ReachArithmetic.closing(0.0, 0.5), 1.0e-9)
        assertEquals(0.32, ReachArithmetic.closing(0.0, 0.4), 1.0e-9)
        // A speed that is really there always wins over the decayed one.
        assertEquals(0.6, ReachArithmetic.closing(0.6, 0.5), 1.0e-9)
        // A negative reading cannot make the margin optimistic or negative.
        assertEquals(0.4, ReachArithmetic.closing(-1.0, 0.5), 1.0e-9)
        assertEquals(0.0, ReachArithmetic.closing(-1.0, -2.0), 1.0e-9)
        // And it decays away: three ticks of standing still leaves a fifth of the speed.
        var speed = 0.5
        repeat(3) { speed = ReachArithmetic.closing(0.0, speed) }
        assertTrue(speed < 0.3, "the speed did not decay: $speed")
        // A decay outside zero to one is clamped, so it can never grow on its own.
        assertEquals(0.5, ReachArithmetic.closing(0.0, 0.5, decay = 1.0), 1.0e-9)
        assertEquals(0.0, ReachArithmetic.closing(0.0, 0.5, decay = -3.0), 1.0e-9)
    }
}
