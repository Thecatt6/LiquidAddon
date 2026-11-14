package com.liquidaddon.aiming

import kotlin.math.abs

/**
 * Never lets the same rotation delta leave three ticks in a row.
 *
 * A silent aim has no hand behind it, so nothing dithers its wire the way a mouse dithers a hand's:
 * a steady chase quantizes to the same mouse counts every tick, and a rest re-sends the same
 * rotation every tick, and both read as a run of identical `(deltaYaw, deltaPitch)` pairs. That run
 * is exactly what a "constant rotations" heuristic counts — the flag this class answers to arrived
 * as `Vulcan > failed Aim (Constant)`, `Info: Constant rotations (3)`, `Component: Heuristic`, with
 * a `(Linear)` sibling on the same window. Three in a row is the number the heuristic itself
 * printed, so the guard makes three in a row impossible: the third pair of a run leaves moved by
 * **one mouse count** on one axis, which keeps the wire on the mouse lattice instead of inventing a
 * degree amount of its own.
 *
 * The break alternates sign, and alternates axis when the run is a rest (zero, zero), so a long
 * rest drifts one count out and one count back the way a resting hand breathes, instead of walking
 * off or stamping a fixed period. Whether any anticheat stops flagging after this is **not
 * measured** — what is measured, in `RepeatGuardTest`, is that the shape the heuristic names cannot
 * leave the class.
 */
internal class RepeatGuard {

    /** How many times a run had to be broken: the `wire` field of the telemetry line. */
    var breaks = 0
        private set

    private var last: Pair<Float, Float>? = null
    private var before: Pair<Float, Float>? = null
    private var flip = false

    /**
     * The delta pair as it may leave on this tick: [yaw]/[pitch] untouched, unless they would be
     * the third identical pair in a row — then one [unit] (one mouse count, the client's GCD) is
     * added to one axis, with the sign and, on a rest, the axis alternating.
     *
     * A [unit] of zero or less means there is no lattice to stay on, and the pair passes untouched:
     * breaking a run with an off-lattice nudge would be a worse tell than the run.
     */
    fun pass(yaw: Float, pitch: Float, unit: Float): Pair<Float, Float> {
        val incoming = yaw to pitch
        val out = if (unit > EPS && same(before, last) && same(last, incoming)) {
            breaks++
            flip = !flip
            val sign = if (flip) unit else -unit
            val rest = abs(yaw) < EPS && abs(pitch) < EPS
            when {
                rest && flip -> (yaw + sign) to pitch
                rest -> yaw to (pitch + sign)
                abs(yaw) >= abs(pitch) -> (yaw + sign) to pitch
                else -> yaw to (pitch + sign)
            }
        } else {
            incoming
        }

        before = last
        last = out
        return out
    }

    /** Forgets the run and the counter: a new session has repeated nothing yet. */
    fun reset() {
        breaks = 0
        last = null
        before = null
        flip = false
    }

    private fun same(a: Pair<Float, Float>?, b: Pair<Float, Float>?): Boolean =
        a != null && b != null && abs(a.first - b.first) < EPS && abs(a.second - b.second) < EPS

    private companion object {
        /** Quantized deltas of the same mouse counts are the same floats; this is only float dust. */
        const val EPS = 1.0e-6f
    }
}
