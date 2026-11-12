package com.liquidaddon.aiming

import kotlin.math.abs
import kotlin.math.min

/**
 * The hand on the mouse, as a gate the assist has to pass.
 *
 * Everything else in the aiming engine shapes *how* a correction looks. This is about whether there
 * is a correction to make at all, and it is the one difference between an assist and an aimbot that
 * no amount of noise can hide:
 *
 *  - a **hand** changes the rotation only while it moves. Stop the mouse and the view stops, and
 *    that unchanged rotation is what the server receives: a normal, boring packet every tick.
 *  - a **module** keeps correcting with the mouse standing still. The target drifts a degree, the
 *    loop answers, and every step in the packets is the module's own — the case a "pattern" or a
 *    "constant rotation" check is built on, and the case [MousePatternGuard] has to spend its
 *    repairs on.
 *
 * So the assist is only granted while the player is actually moving the mouse. The measurement is
 * free and already exact: `AimassistCore` reads the player's own turn every tick
 * (`me.yRot - lastCameraYaw - lastAssistYaw`), i.e. with the module's own contribution removed, so
 * the gate cannot certify itself. Divided by the mouse lattice (`RotationUtil.gcd`) that angle
 * becomes **mouse counts**, which is the unit the player's hand actually works in: half a count of
 * movement is not a movement, it is the camera sitting still.
 *
 * The two directions are deliberately not symmetric. Stopping cuts at once: a mouse has no
 * inertia, the vanilla view stops with the hand, and a coast would be motion with no input
 * behind it. Starting again walks up over [fadeTicks] ticks: a re-arm that jumps to full on the
 * first count is what lets a one-count jitter hold the assist forever, and that ramp is the only
 * edge this class still smooths.
 *
 * The class is stateful but has no dependencies, so the whole rule is testable without a game
 * running (see `HandGateTest`).
 */
internal class HandGate {

    /** What the gate is allowing this tick, 0..1. Read by the debug line. */
    var activity = 0f
        private set

    /** Forgets the hand: nothing is moving yet. Called when the module starts. */
    fun reset() {
        activity = 0f
    }

    /**
     * One tick of the gate: [movementCounts] is how many mouse counts the player moved, [threshold]
     * how much counts as a movement, [fadeTicks] how many ticks the pull survives after the hand
     * stops. Returns the share of the help that is allowed this tick, 0..1.
     *
     * A [threshold] of zero (or less) switches the gate off entirely: the assist behaves as it did
     * before this class existed, which is also what the profiles that aim on their own ask for.
     */
    fun update(movementCounts: Float, threshold: Float, fadeTicks: Int): Float {
        if (threshold <= 0f) {
            activity = 1f
            return 1f
        }
        if (movementCounts >= threshold) {
            // The hand moves again: the gate opens over the same number of ticks it closes over.
            // Coming straight back to full on the first count was a shape of its own — a mouse
            // jittered by one count every few ticks held the assist at full forever, a steady
            // "hand" that never moves enough to be one.
            activity = if (fadeTicks <= 0) 1f else min(1f, activity + 1f / fadeTicks)
            return activity
        }

        // The hand stopped: the gate closes on this tick. A mouse has no inertia — a vanilla
        // client's view stops the very tick the hand stops, and that edge is the most normal
        // packet shape there is. Coast after a stop would be motion with no input behind it, the
        // one thing the whole gate exists to refuse. [fadeTicks] rules the way back up, only.
        activity = 0f
        return 0f
    }

    /**
     * The second factor of a hand at rest: [activity] again, which since the stop cuts at once is
     * simply 0 on every still tick — the assist contributes nothing to a view whose hand is not
     * moving, not even a coast. Kept as its own reading (instead of trusting [activity] at the
     * call site) so the rule "a still hand moves nothing" stays named, and testable, in one place.
     *
     * While the hand moves (or the gate is off) this is 1 and changes nothing.
     */
    fun restDecay(movementCounts: Float, threshold: Float): Float =
        if (threshold <= 0f || movementCounts >= threshold) 1f else activity

    companion object {

        /**
         * How far the player moved the mouse this tick, in whole mouse counts.
         *
         * The two axes are added, not compared: a diagonal movement of half a count on each is a
         * mouse that moved, and taking the larger of the two would call it still. A lattice of zero
         * (no `GcdFix`, an unknown sensitivity) means there is no count to measure in, and the gate
         * must not block on a unit it cannot compute: the movement is reported as unbounded.
         */
        fun movementCounts(ownYaw: Float, ownPitch: Float, gcd: Float): Float {
            if (gcd <= 1.0e-6f) return Float.POSITIVE_INFINITY
            val counts = (abs(ownYaw) + abs(ownPitch)) / gcd
            // A NaN (a rotation that came out of nowhere) is not a movement: it has to decay.
            return if (counts.isNaN()) 0f else counts
        }
    }
}
