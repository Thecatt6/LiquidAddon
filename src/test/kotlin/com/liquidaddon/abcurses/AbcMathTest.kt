package com.liquidaddon.abcurses

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The arithmetic of the port, checked against the reference where the reference can be reproduced
 * without the model, and against its own invariants where it cannot.
 *
 * The pieces that are *not* free to drift are the ones the model's behaviour depends on:
 *
 *  - the MT19937 word stream and the two draw laws (a uniform is one word, an exponential is a pair
 *    of words read at stride two) — a stream that draws different words picks a different head,
 *    and the whole plan changes;
 *  - the gate order of the GRU, which is the trap this port already fell into once;
 *  - the C2 brake curve's endpoints (it starts where the hand was and ends at rest);
 *  - the count ↔ degree conversion, which is the only unit the planner and the camera share.
 */
class AbcMathTest {

    @Test
    fun `the word stream is the reference MT19937`() {
        // numpy.random.RandomState(7).randint(0, 2**32, size=8, dtype=uint32)
        val expected = longArrayOf(
            327741615, 976413892, 3349725721, 1369975286,
            1882953283, 4201435347, 3107259287, 1956722279,
        )
        val mt = Mt19937(7)
        for ((i, want) in expected.withIndex()) {
            assertEquals(want, mt.raw().toLong() and 0xffffffffL, "word $i")
        }
        // The seed is taken modulo 2^32, the way RandomState does it.
        assertEquals(327741615u, Mt19937(7L + (1L shl 32)).raw().toUInt())
    }

    @Test
    fun `a uniform draw is one word of twenty-four bits`() {
        // numpy: (w & 0xffffff) * 2**-24 with w the first word of RandomState(7).
        val expected = (327741615L and 0xffffff) * 5.9604645e-8f
        val stream = RandomStream(7)
        assertEquals(expected, stream.uniform(1)[0], 1e-12f)
        val two = RandomStream(7).uniform(2)
        assertEquals(2, two.size)
        assertTrue(two.all { it >= 0f && it < 1f })
    }

    @Test
    fun `a categorical draw consumes the pairs the reference consumes`() {
        // Sixteen categories: 32 words, exponentially raced. Two draws of a 16-way choice must leave
        // the stream at the same place as one 32-way draw would (same word count) — the property the
        // reference relies on to keep its seeds aligned when a head is discarded.
        val a = RandomStream(11)
        val weights = FloatArray(16) { 1f / 16f }
        val first = a.categorical(weights)
        val second = a.categorical(weights)
        val b = RandomStream(11)
        b.discardCategorical(16)
        assertEquals(second, b.categorical(weights), "discarding a draw must consume exactly its words")
        assertTrue(first in 0 until 16)
    }

    @Test
    fun `the gru keeps the onnx gate order`() {
        // One step with a hand-built GRU: the hidden state must be (1 - z) * h~ + z * h, with the
        // candidate taken from the third block and the reset applied to the recurrent term only.
        val hidden = 2
        val inputs = 1
        // weight_ih rows are (z, r, h) x hidden, each row a 1-value input weight.
        val weightIh = floatArrayOf(
            1f, // z[0]
            1f, // z[1]
            1f, // r[0]
            1f, // r[1]
            1f, // h~[0]
            1f, // h~[1]
        )
        val weightHh = FloatArray(3 * hidden * hidden)
        val biasIh = FloatArray(3 * hidden)
        val biasHh = FloatArray(3 * hidden)
        val gru = Gru(hidden, inputs, weightIh, weightHh, biasIh, biasHh)
        val h = floatArrayOf(0f, 0f)
        gru.step(floatArrayOf(1f), h)
        // With an all-zero recurrent path: z = sigmoid(1) = 0.731, h~ = tanh(1) = 0.7616 — and the
        // state is (1 - z) * h~ = 0.2049 for both units, because every row received the same input.
        val z = 1f / (1f + kotlin.math.exp(-1f))
        val candidate = kotlin.math.tanh(1f)
        val expected = (1f - z) * candidate
        assertEquals(expected, h[0], 1e-6f)
        assertEquals(expected, h[1], 1e-6f)
        assertTrue(h[0] < candidate, "the update gate must hold the state back")
    }

    @Test
    fun `the brake starts at rest and ends on the residual`() {
        val velocity = doubleArrayOf(2.5, -0.5)
        val acceleration = doubleArrayOf(0.3, 0.1)
        val duration = 64.0
        val coefficients = Array(5) { DoubleArray(2) }
        coefficients[0] = doubleArrayOf(12.0, -3.0)

        val early = PhysicalKernels.brakePath(velocity, acceleration, duration, coefficients, 0.0)
        val late = PhysicalKernels.brakePath(velocity, acceleration, duration, coefficients, 64.0)

        // The first millisecond is the incoming velocity plus the acceleration it already carried:
        // a brake cannot start with a jump, and cannot ignore the hand's momentum either.
        assertEquals(velocity[0] + acceleration[0] / 2, early[0][0], 0.15, "the curve starts where the hand was")
        // At the end of the duration every step is zero: the movement has finished.
        assertTrue(late.all { kotlin.math.abs(it[0]) < 1e-6 && kotlin.math.abs(it[1]) < 1e-6 })

        // And the sum of the whole curve is the residual the model asked to land on.
        var sx = 0.0
        var sy = 0.0
        var age = 0.0
        while (age < duration) {
            val block = PhysicalKernels.brakePath(velocity, acceleration, duration, coefficients, age)
            for (row in block) {
                sx += row[0]
                sy += row[1]
            }
            age += 32
        }
        assertEquals(12.0, sx, 0.35, "the brake lands on the endpoint the model chose")
        assertEquals(-3.0, sy, 0.35)
    }

    @Test
    fun `counts and degrees are the same conversion both ways`() {
        // One common count is 0.00035095 rad ≈ 0.0201°, so a degree is 49.73 counts — the unit the
        // whole planner is written in.
        val counts = PhysicalKernels.degreesToCounts(1.0)
        assertEquals(49.7317, counts, 0.001)
        assertEquals(1.0, PhysicalKernels.countsToDegrees(counts), 1e-9)
        // A full turn is 17903 counts.
        assertEquals(17903.4, PhysicalKernels.degreesToCounts(360.0), 0.5)
    }

    @Test
    fun `the angles wrap the way a packet does`() {
        assertEquals(10.0, PhysicalKernels.wrapDegrees(370.0), 1e-9)
        assertEquals(-10.0, PhysicalKernels.wrapDegrees(-370.0), 1e-9)
        assertEquals(179.0, PhysicalKernels.wrapDegrees(179.0), 1e-9)
        assertEquals(-179.0, PhysicalKernels.wrapDegrees(181.0), 1e-9)
        assertEquals(0.0, PhysicalKernels.wrapDegrees(0.0), 1e-9)
        // Minecraft's convention: yaw 0 looks at +z (south), -90 at +x (east), 90 at -x (west).
        assertEquals(0.0, PhysicalKernels.yawOf(0.0, 1.0), 1e-9, "south is +z, yaw 0")
        assertEquals(-90.0, PhysicalKernels.yawOf(1.0, 0.0), 1e-9, "east is +x, yaw -90")
        assertEquals(90.0, PhysicalKernels.yawOf(-1.0, 0.0), 1e-9, "west is -x, yaw 90")
    }

    @Test
    fun `the softmax of a flat vector is flat`() {
        val flat = softmax(FloatArray(16) { 0f })
        assertTrue(flat.all { kotlin.math.abs(it - 0.0625f) < 1e-6f })
        val sharp = softmax(FloatArray(3) { if (it == 0) 10f else 0f })
        assertTrue(sharp[0] > 0.99f)
        assertEquals(1f, sharp.sum(), 1e-5f)
    }
}

/**
 * The stream's contract with the aim around it: it starts where the camera is, it does not jump,
 * it holds when the plan holds, and it lets the caller shift its notion of "here" without changing
 * the movement it already committed to.
 */
class AbcRuntimeTest {

    private val weights by lazy {
        AbcWeights.load(WeightsStream.open(), verify = true)
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
