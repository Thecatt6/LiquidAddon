package com.liquidaddon.aiming

import kotlin.math.abs
import kotlin.math.round

/**
 * Friction that stays on the mouse lattice.
 *
 * Damping a hand by a fraction (the old `delta *= 0.8`) moves the rotation off the lattice the
 * mouse lives on: one count becomes 0.8 of a count, and mixed with the assist's whole counts the
 * stream lands on fifths of a count — a finer lattice than the client's mouse can produce, which
 * no vanilla client ever emits. A check that reasons about the rotation lattice (the old GCD
 * families) reads it as an impossible sensitivity.
 *
 * So friction does not scale: it *drops whole counts*. Each event hands in how many counts the
 * hand moved and the share to keep; the class keeps the nearest integer to `counts * scale` plus
 * a running carry, so a stream of one-count events at scale 0.8 keeps eight counts out of ten,
 * in an irregular pattern (1,1,1,0,1,…) instead of a periodic four-fifths. Every emitted movement
 * stays a whole number of counts, the average damping is exactly the configured share, and the
 * carry never grows past half a count.
 *
 * Stateful, dependency-free, testable (see `LatticeFrictionTest`).
 */
internal class LatticeFriction {

    private var carry = 0f

    /** Counts taken away from the hand so far (absolute, both directions), for telemetry. */
    var dropped = 0f
        private set

    /**
     * The whole counts to let through for a hand movement of [counts] counts at keep-share
     * [scale] (0..1; above 1 reads as 1). Always an integer, always within half a count of the
     * exact share plus what previous events owed.
     */
    fun keep(counts: Float, scale: Float): Int {
        val want = counts * scale.coerceIn(0f, 1f) + carry
        val kept = round(want).toInt()
        carry = want - kept
        dropped += abs(counts - kept)
        return kept
    }

    fun reset() {
        carry = 0f
        dropped = 0f
    }
}
