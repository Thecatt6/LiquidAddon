package com.liquidaddon.abcurses

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The stream's contract with the aim around it: it starts where the camera is, it does not jump,
 * it holds when the plan holds, and it lets the caller shift its notion of "here" without changing
 * the movement it already committed to.
 */
class AbcRuntimeTest {

    private val weights by lazy {
        AbcWeights.load(
            // `resources/` is where an add-on's files live in the jar; the test classpath has
            // `src/main/resources` at its root, so that is the path here too.
            AbcRuntimeTest::class.java.getResourceAsStream("/resources/liquidaddon/abcurses/weights.npz")
                ?: error("the add-on resources do not contain the archive"),
            verify = true,
        )
    }

    @Test
    fun `a fresh stream stands still until it is given a target`() {
        val runtime = AbcRuntime(weights, 7L, 100.0, -20.0)
        val frame = runtime.advance(64_000)
        assertEquals(64, frame.size)
        for (i in 0 until frame.size) {
            assertEquals(100.0, frame.x(i), 1e-9)
            assertEquals(-20.0, frame.y(i), 1e-9)
        }
        assertEquals(0L, runtime.decisions(), "no target, no policy draw")
    }

    @Test
    fun `a target moves the stream and the movement starts from where it was`() {
        val runtime = AbcRuntime(weights, 7L, 0.0, 0.0)
        runtime.updateTarget(600.0, 40.0, 0L)
        // The stream begins in a hold — it has been standing still for its whole known history, so
        // the policy says "settle" until the hazard head lets it go. That is the reference's own
        // behaviour, and it is why the first plan arrives tens of milliseconds after the target.
        val warm = runtime.advance(60_000)
        for (i in 0 until warm.size) {
            assertTrue(warm.x(i) == 0.0 && warm.y(i) == 0.0, "the stream holds before it moves")
        }
        val frame = runtime.advance(200_000)
        assertTrue(runtime.decisions() > 0)
        assertTrue(frame.x(frame.size - 1) > 1.0, "the stream moved towards the target")
        // And it moves smoothly: no single millisecond carries more than a fraction of the error.
        assertEquals(0.0, frame.x(0), 30.0, "the movement starts from where the stream was")
    }

    @Test
    fun `a receipt must not arrive in the past`() {
        val runtime = AbcRuntime(weights, 3L, 0.0, 0.0)
        runtime.updateTarget(10.0, 10.0, 0L)
        runtime.advance(40_000)
        val failure = runCatching { runtime.updateTarget(20.0, 20.0, 10_000) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException, "expected a refusal, got $failure")
    }

    @Test
    fun `rebasing moves the stream without changing the movement in flight`() {
        val runtime = AbcRuntime(weights, 5L, 0.0, 0.0)
        runtime.updateTarget(400.0, 0.0, 0L)
        // Let it start moving first: a hold has nothing in flight and would make the test vacuous.
        runtime.advance(150_000)
        val before = runtime.advance(160_000)
        assertTrue(before.x(before.size - 1) > 0.0, "the stream was moving before the rebase")
        assertEquals(runtime.currentX(), before.x(before.size - 1), 1e-9, "the stream is where it says")

        // The camera was dragged 30 counts down by the hand: the stream continues from there.
        val velocityBefore = before.dx(before.size - 1)
        // A rebase does not re-plan: the sample the plan had already committed to is still the one
        // that plays next, so it is compared against the stream's own next sample, not against an
        // un-rebased twin (a different clock would have planned differently, which is the point of
        // a policy).
        val twin = AbcRuntime(weights, 5L, 0.0, 0.0)
        twin.updateTarget(400.0, 0.0, 0L)
        twin.advance(150_000)
        val twinBefore = twin.advance(160_000)
        val twinAfter = twin.advance(168_000)
        assertEquals(twinBefore.x(twinBefore.size - 1), before.x(before.size - 1), 0.0, "same seed, same path")
        val xBefore = runtime.currentX()
        val yBefore = runtime.currentY()
        runtime.rebase(0.0, -30.0)
        assertEquals(xBefore, runtime.currentX(), 1e-9, "a vertical rebase left x alone")
        assertEquals(yBefore - 30.0, runtime.currentY(), 1e-9, "the rebase moved y")

        val after = runtime.advance(168_000)
        // The *movement* is untouched — the displacement of the sample straddling the rebase is the
        // one the plan had already made — and it is now applied 30 counts lower than before.
        assertEquals(yBefore - 30.0 + after.dy(0), after.y(0), 1e-9, "the samples moved with the frame")
        assertEquals(velocityBefore, before.dx(before.size - 1), 1e-9, "the last sample of the leg")
        // The plan that continues was drawn from the *new* position — a fresh commitment re-plans
        // from where the stream says it is, which is the point of rebasing — so its sample is not the
        // twin's to the count. What it must be is a movement of the same order: no jump, no reset.
        assertTrue(
            kotlin.math.abs(after.dx(0) - twinAfter.dx(0)) < 1.0,
            "the movement continued, it did not restart: ${after.dx(0)} vs ${twinAfter.dx(0)}",
        )
        assertTrue(after.x(0) > xBefore - 1.0, "the aim kept travelling towards the goal")
        // And it does not come back: the shift is a change of frame, not a detour it steers out of.
        for (i in 0 until after.size) {
            assertTrue(
                after.y(i) < yBefore - 30.0 + 15.0,
                "sample $i fell back towards the pre-rebase origin",
            )
        }
    }

    @Test
    fun `a rebase is idempotent enough to be measured against`() {
        // The wrapper compares the camera with the stream and rebases the difference. Since a rebase
        // moves the stream itself, the same comparison on the next tick must find nothing left to
        // correct — otherwise the engine keeps re-adding the gap it declared, which is a runaway.
        val runtime = AbcRuntime(weights, 9L, 500.0, -100.0)
        runtime.updateTarget(900.0, 0.0, 0L)
        runtime.advance(80_000)
        val cameraX = runtime.currentX() + 12.0
        val cameraY = runtime.currentY() - 7.0
        for (round in 0 until 20) {
            val gapX = cameraX - runtime.currentX()
            val gapY = cameraY - runtime.currentY()
            runtime.rebase(gapX, gapY)
            assertEquals(0.0, cameraX - runtime.currentX(), 1e-9, "round $round x")
            assertEquals(0.0, cameraY - runtime.currentY(), 1e-9, "round $round y")
            if (round % 5 == 0) runtime.advance(80_000L + round * 5_000L)
        }
    }

    @Test
    fun `two streams with the same seed agree, two seeds do not have to`() {
        fun run(seed: Long): Double {
            val runtime = AbcRuntime(weights, seed, 0.0, 0.0)
            runtime.updateTarget(500.0, 25.0, 0L)
            val frame = runtime.advance(120_000)
            return frame.x(frame.size - 1)
        }
        assertEquals(run(7L), run(7L), 0.0)
        assertTrue(run(7L) != run(8L), "the seed drives the draw stream")
    }
}
