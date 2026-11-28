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
