package com.liquidaddon.abcurses

import com.liquidaddon.aiming.AbcEngine
import com.liquidaddon.aiming.AbcSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `AbcEngine` is the seam between the planner and the aim, and it is the one part of the integration
 * that needs no Minecraft at all: a camera angle in, a step in degrees out. That makes the contract
 * with the aim around it testable, and it is exactly the contract that is easy to get wrong.
 *
 * The four rules, each of which has already been broken once in this port:
 *
 *  - a step is a *movement*, so no tick may ask the camera to travel further than a hand could;
 *  - a resync must not feed itself: the stream is moved, so the same gap is not corrected twice;
 *  - a hold is `null` and is never a fallback;
 *  - a failure is `null` **and** a reason, so the aim can say why it fell back to the curve.
 */
class AbcEngineTest {

    private class Knobs(
        var resync: Float = 16f,
        var strength: Float = 100f,
        var pitch: Float = 50f,
    ) : AbcSettings {
        override val maxSpeed: Float get() = 20f
        override val pitchAssist: Float get() = pitch
        override val abcStrength: Float get() = strength
        override val abcResyncCounts: Float get() = resync
    }

    /**
     * Plays the engine the way the client's render loop does — a call every millisecond or so, time
     * taken from [`System.nanoTime`] inside the engine — and returns the final camera yaw.
     *
     * The engine samples the plan on wall clock, so this test can only check *behaviour*; the exact
     * numbers belong to `AbcParityTest`, which drives the runtime directly.
     */
    private fun play(
        cameraYaw: Double,
        goalYaw: Double,
        millis: Int,
        knobs: Knobs = Knobs(),
        hand: (Double) -> Double = { 0.0 },
    ): Double = playWith(cameraYaw, goalYaw, millis, knobs, hand).first

    /** [play], plus how many ticks the loop actually got through. */
    private fun playWith(
        cameraYaw: Double,
        goalYaw: Double,
        millis: Int,
        knobs: Knobs = Knobs(),
        hand: (Double) -> Double = { 0.0 },
    ): Pair<Double, Int> {
        val engine = AbcEngine { knobs }
        engine.begin(cameraYaw, 0.0)
        var yaw = cameraYaw
        var ticks = 0
        val started = System.nanoTime()
        while ((System.nanoTime() - started) / 1_000_000 < millis) {
            ticks++
            val step = engine.step(yaw, 0.0, goalYaw, 0.0)
            if (step != null) {
                assertTrue(step.first.isFinite() && step.second.isFinite(), "a step must be a number")
                assertTrue(
                    kotlin.math.abs(step.first) < 90f && kotlin.math.abs(step.second) < 90f,
                    "a tick asked for ${step.first}°/${step.second}°: the stream is not a plan anymore",
                )
                yaw += step.first
            }
            yaw += hand(yaw)
            Thread.sleep(1)
        }
        return yaw to ticks
    }

    /** Distance between two yaw angles, in degrees, the short way round. */
    private fun apart(a: Double, b: Double): Double {
        var d = (a - b) % 360.0
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return kotlin.math.abs(d)
    }

    @Test
    fun `a plain approach lands on the goal`() {
        val yaw = play(0.0, 6.0, 1_200)
        assertTrue(apart(yaw, 6.0) < 1.0, "landed at $yaw for a 6° goal")
    }

    @Test
    fun `a turn across north is planned the short way`() {
        // 179° to -179° is two degrees to the right. A planner working in wrapped angles would take
        // the 358° way round; the engine unwraps the camera, so it does not.
        // A camera yaw is an angle, not a number: the two are compared around the circle, which is
        // the whole reason the engine unwraps what it is given.
        val forward = play(179.0, -179.0, 900)
        val back = play(-179.0, 179.0, 900)
        assertTrue(apart(forward, -179.0) < 3.0, "forward: ended at $forward")
        assertTrue(apart(back, 179.0) < 3.0, "backward: ended at $back")
    }

    @Test
    fun `a hand that keeps pulling away is followed, never fought`() {
        // Half a degree of hand away from the goal on every tick: the engine must keep rebasing and
        // must never answer with a runaway. The old deferred rebase did exactly that — 105 resyncs
        // and steps in the millions of counts.
        val (yaw, ticks) = playWith(0.0, 10.0, 700) { -0.5 }
        assertTrue(yaw < 0.0, "the hand pulled the aim away and it stayed away: ended at $yaw")
        // Whatever the engine did, it did not make the drift worse than the hand's own: the aim is
        // never south of what half a degree per tick pulls it to. A runaway shows up here first.
        assertTrue(
            yaw > -0.5 * ticks - 5.0,
            "the engine amplified the drift: $yaw after $ticks ticks of -0.5°",
        )
    }

    @Test
    fun `a smaller resync threshold means more rebasing, not a different plan`() {
        // Both end near the goal; what changes is how often the stream is pulled back onto the camera.
        val loose = play(0.0, 12.0, 900, Knobs(resync = 64f))
        val tight = play(0.0, 12.0, 900, Knobs(resync = 2f))
        assertTrue(apart(loose, 12.0) < 2.0, "loose: ended at $loose")
        assertTrue(apart(tight, 12.0) < 2.0, "tight: ended at $tight")
    }

    @Test
    fun `an engine that cannot load reports itself as missing`() {
        // Nothing is promised about *which* failure this is (the archive is bundled in the jar and
        // found through the class loader), only that a broken engine is a reason and not a step.
        val engine = AbcEngine { null }
        assertEquals(null, engine.step(0.0, 0.0, 10.0, 0.0), "no settings, no stream")
        assertTrue(engine.status.startsWith("abc "), "status: ${engine.status}")
    }

    @Test
    fun `status counts decisions once the stream is running`() {
        val engine = AbcEngine { Knobs() }
        engine.begin(0.0, 0.0)
        val started = System.nanoTime()
        while ((System.nanoTime() - started) / 1_000_000 < 400) {
            engine.step(0.0, 0.0, 8.0, 0.0)
            Thread.sleep(1)
        }
        assertTrue(engine.isReady, "the bundled archive must load: ${engine.status}")
        assertTrue(engine.decisionCount > 0, "the planner decided: ${engine.status}")
        assertTrue(engine.status.contains("dec"), "the line carries the count: ${engine.status}")
    }

    @Test
    fun `a reset forgets the session`() {
        val engine = AbcEngine { Knobs() }
        engine.begin(0.0, 0.0)
        val started = System.nanoTime()
        while ((System.nanoTime() - started) / 1_000_000 < 300) {
            engine.step(0.0, 0.0, 8.0, 0.0)
            Thread.sleep(1)
        }
        engine.reset()
        assertEquals(0L, engine.decisionCount)
        assertEquals(0, engine.resyncCount)
        assertEquals(0.0, engine.movedDegrees)
        assertTrue(!engine.isReady, "the stream is gone until it is begun again")
    }
}
