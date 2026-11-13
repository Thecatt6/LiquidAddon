package com.liquidaddon.aiming

/**
 * A dip with edges instead of a rectangle.
 *
 * Two of the engine's humanizers stop the help for a few ticks: the beats of rest a hand takes
 * while tracking, and the idle ticks where the mouse barely moves. Both used to be a rectangle —
 * full help, then nothing (or a fixed 15%) for the length of the beat, then full help again on the
 * very next tick. On screen that is the thing a player reports as "it snaps, stops, then snaps
 * again": the help leaves in one tick and comes back in one tick, and the tick it comes back on is
 * the biggest of the whole beat, because the target moved while the assist was off.
 *
 * [share] gives the beat a shape: it eases from 1 down to [depth] over [ramp] ticks, sits there
 * for as long as the beat has room, and eases back symmetrically. A beat too short for both ramps
 * becomes a triangle that never reaches the bottom, which is the right answer — a two-tick pause
 * has no business spending both ticks at zero. [ramp] of 0 is the old rectangle, kept reachable so
 * the difference stays testable instead of assumed.
 *
 * Pure and stateless: the caller owns the clock (see `BeatTest`).
 */
internal object Beat {

    /**
     * The share of the help on tick [index] (0-based) of a beat [length] ticks long, whose bottom
     * is [depth] (0 = nothing, 1 = no dip at all) and whose edges take [ramp] ticks.
     */
    fun share(index: Int, length: Int, depth: Float, ramp: Int): Float {
        if (length <= 0) return 1f
        val i = index.coerceIn(0, length - 1)
        // Distance from the nearest end of the beat: the shape is symmetric, so one formula draws
        // both the ease out and the ease back in, and a short beat becomes a triangle by itself.
        val edge = minOf(i, length - 1 - i)
        val t = if (ramp <= 0) 1f else minOf(1f, (edge + 1) / (ramp + 1f))
        val smooth = t * t * (3f - 2f * t)
        return 1f + (depth.coerceIn(0f, 1f) - 1f) * smooth
    }
}
