package com.liquidaddon.aiming

import kotlin.math.abs
import kotlin.math.max
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
 * The gate does not cut on the last moving tick, in either direction. A hand that stops still
 * carries the pull for a moment, and a hand that starts again does not pull in full on its first
 * count — both edges are shapes a check can see, and a re-arm that jumps to full is what lets a
 * one-count jitter hold the assist forever. So the activity walks over [fadeTicks] ticks down
 * *and* up instead of dropping and jumping.
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

        // The hand stopped: the pull runs out over [fadeTicks] ticks, never below zero and never
        // in a single step (that step would be the edge this exists to remove).
        activity = if (fadeTicks <= 0) 0f else max(0f, activity - 1f / fadeTicks)
        return activity
    }

    /**
     * The second factor of a hand at rest, 0..1: [activity] again, so that while the mouse stands
     * still the help decays with the *square* of the fade (0.56, 0.25, 0.06 over four ticks)
     * instead of linearly. A linear fade keeps the view walking towards the target at half
     * strength for ticks after the hand stopped — a camera that moves with no input behind it,
     * which is the one thing a hand never does. Squaring it lets the view coast to a standstill
     * within a tick or two, smoothly: no edge, no drift.
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
