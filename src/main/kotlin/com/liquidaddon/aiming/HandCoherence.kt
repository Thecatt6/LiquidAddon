package com.liquidaddon.aiming

import kotlin.math.hypot

/**
 * How much of the assist's direction the hand was already going.
 *
 * An assist adds a target-directed component on top of whatever the hand does. While the hand
 * moves roughly towards the target that component reads as a person helping their own aim; while
 * the hand moves somewhere else entirely — sideways, vertically, anywhere but the target — the
 * added component comes from nowhere, and a rotation stream in which target-directed steps appear
 * on top of hand movement pointed elsewhere is exactly the stream an assist leaves behind.
 *
 * So the share of the help scales with the cosine between the hand's own turn of this tick and the
 * direction of the goal: parallel hands get all of it, perpendicular and opposed hands get [FLOOR]
 * — not zero, because a hand does drift while correcting, and zero would put a hard edge on every
 * change of direction. The hard cut for a player genuinely turning away stays where it was
 * (`turnCheck`); this is the gradient underneath it.
 *
 * When either direction is degenerate (a still hand, or a goal already reached) there is nothing
 * to compare and the factor is 1: the hand gate and the rest check own those ticks.
 */
internal object HandCoherence {

    /** The share of the help when the hand's direction says nothing in its favour. */
    const val FLOOR = 0.35f

    fun factor(ownYaw: Float, ownPitch: Float, goalYaw: Float, goalPitch: Float): Float {
        val own = hypot(ownYaw, ownPitch)
        val goal = hypot(goalYaw, goalPitch)
        if (own < 1.0e-4f || goal < 1.0e-4f) return 1f
        val cos = ((ownYaw * goalYaw + ownPitch * goalPitch) / (own * goal)).coerceIn(-1f, 1f)
        return FLOOR + (1f - FLOOR) * maxOf(0f, cos)
    }
}
